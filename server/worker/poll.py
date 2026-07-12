# PageDrop Conversion Worker
#
# Polls Supabase for pending conversion jobs, runs Calibre CLI,
# uploads results back to Supabase Storage.
#
# Designed for Oracle Ampere A1 (ARM) or any Linux box with Docker.

import asyncio
import hashlib
import json
import os
import subprocess
import tempfile
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path

import httpx
import trafilatura

# ── Config ──────────────────────────────────────────────────────────────────

SUPABASE_URL = os.environ["SUPABASE_URL"]
SUPABASE_SERVICE_KEY = os.environ["SUPABASE_SERVICE_KEY"]  # service_role key

POLL_INTERVAL = int(os.environ.get("POLL_INTERVAL", "5"))  # seconds
MAX_CONCURRENT = int(os.environ.get("MAX_CONCURRENT", "1"))
JOB_TIMEOUT = int(os.environ.get("JOB_TIMEOUT", "300"))    # 5 minutes
ARTIFACT_DIR = Path("/tmp/pagedrop-artifacts")

HEADERS = {
    "apikey": SUPABASE_SERVICE_KEY,
    "Authorization": f"Bearer {SUPABASE_SERVICE_KEY}",
    "Content-Type": "application/json",
    "Prefer": "return=representation",
}

# ── Supabase REST helpers ───────────────────────────────────────────────────


def supabase_get(path: str, params: dict = None):
    url = f"{SUPABASE_URL}/rest/v1/{path.lstrip('/')}"
    with httpx.Client() as c:
        r = c.get(url, headers=HEADERS, params=params)
        r.raise_for_status()
        return r.json()


def supabase_patch(path: str, data: dict):
    url = f"{SUPABASE_URL}/rest/v1/{path.lstrip('/')}"
    headers = {**HEADERS, "Prefer": "return=minimal"}
    with httpx.Client() as c:
        r = c.patch(url, headers=headers, json=data)
        r.raise_for_status()


def supabase_upload(bucket: str, storage_path: str, file_bytes: bytes, content_type: str = "application/octet-stream"):
    """Upload a file to Supabase Storage using the service role."""
    url = f"{SUPABASE_URL}/storage/v1/object/{bucket}/{storage_path}"
    headers = {
        "apikey": SUPABASE_SERVICE_KEY,
        "Authorization": f"Bearer {SUPABASE_SERVICE_KEY}",
        "Content-Type": content_type,
    }
    headers["x-upsert"] = "true"
    with httpx.Client() as c:
        r = c.post(url, headers=headers, content=file_bytes)
        r.raise_for_status()
    return storage_path


def supabase_download(bucket: str, storage_path: str) -> bytes:
    """Download a file from Supabase Storage."""
    url = f"{SUPABASE_URL}/storage/v1/object/{bucket}/{storage_path}"
    headers = {
        "apikey": SUPABASE_SERVICE_KEY,
        "Authorization": f"Bearer {SUPABASE_SERVICE_KEY}",
    }
    with httpx.Client() as c:
        r = c.get(url, headers=headers)
        r.raise_for_status()
        return r.content


# ── Conversion ──────────────────────────────────────────────────────────────


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    h.update(path.read_bytes())
    return h.hexdigest()


async def run_calibre(
    input_path: Path,
    output_path: Path,
    title: str | None = None,
    author: str | None = None,
    cover_path: Path | None = None,
    timeout: int = JOB_TIMEOUT,
) -> tuple[int, str, str]:
    """Run ebook-convert, return (returncode, stdout, stderr)."""
    cmd = ["ebook-convert", str(input_path), str(output_path)]
    if title and title.strip():
        cmd.extend(["--title", title.strip()])
    if author and author.strip():
        cmd.extend(["--authors", author.strip()])
    if cover_path and cover_path.exists():
        cmd.extend(["--cover", str(cover_path)])
    proc = await asyncio.create_subprocess_exec(
        *cmd,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    try:
        stdout, stderr = await asyncio.wait_for(proc.communicate(), timeout=timeout)
        return proc.returncode or 0, stdout.decode(), stderr.decode()
    except asyncio.TimeoutError:
        proc.kill()
        return -1, "", "Timed out"


async def apply_metadata(
    ebook_path: Path,
    title: str | None,
    author: str | None,
    cover_path: Path | None = None,
) -> tuple[int, str]:
    """Apply PageDrop's user-facing metadata to the converted ebook."""
    cmd = ["ebook-meta", str(ebook_path)]
    if title and title.strip():
        cmd.extend(["--title", title.strip()])
    if author and author.strip():
        cmd.extend(["--authors", author.strip()])
    if cover_path and cover_path.exists():
        cmd.extend(["--cover", str(cover_path)])
    if len(cmd) == 2:
        return 0, ""

    proc = await asyncio.create_subprocess_exec(
        *cmd,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    stdout, stderr = await proc.communicate()
    return proc.returncode or 0, (stderr or stdout).decode()


async def write_article_source(url: str, output_path: Path, timeout: int = JOB_TIMEOUT) -> tuple[bool, str | None, str | None]:
    """Fetch and extract a readable article into a simple HTML file."""
    try:
        if not url.startswith(("http://", "https://")):
            return False, None, "Only http/https URLs are supported"

        downloaded = await asyncio.to_thread(trafilatura.fetch_url, url)
        if not downloaded:
            return False, None, "Failed to fetch URL"

        metadata = trafilatura.extract_metadata(downloaded)
        extracted = trafilatura.extract(
            downloaded,
            output_format="html",
            include_comments=False,
            include_tables=True,
            include_images=True,
        )
        if not extracted:
            return False, None, "Failed to extract readable article"

        title = metadata.title if metadata and metadata.title else "Untitled Article"
        author = metadata.author if metadata and metadata.author else None
        html = (
            "<html><head><meta charset=\"utf-8\"/>"
            f"<title>{escape_html(title)}</title></head><body>"
            f"<h1>{escape_html(title)}</h1>"
        )
        if author:
            html += f"<p><em>By {escape_html(author)}</em></p>"
        html += extracted
        html += f"<hr/><p><small>Source: {escape_html(url)}</small></p></body></html>"
        output_path.write_text(html, encoding="utf-8")
        return True, title, author
    except Exception as exc:
        return False, None, str(exc)


def escape_html(value: str) -> str:
    return (
        value.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace('"', "&quot;")
    )


async def extract_cover(ebook_path: Path, output_path: Path) -> bool:
    """Extract cover image using ebook-meta."""
    cmd = ["ebook-meta", str(ebook_path), "--get-cover", str(output_path)]
    proc = await asyncio.create_subprocess_exec(
        *cmd,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    stdout, stderr = await proc.communicate()
    return output_path.exists() and output_path.stat().st_size > 0


async def process_job(job: dict) -> dict:
    """Process a single conversion job. Returns updated job fields."""
    job_id = job["id"]
    user_id = job["user_id"]
    source_path = job.get("source_storage_path")
    input_cover_path = job.get("input_cover_storage_path")
    source_url = job.get("source_url")
    target_fmt = job.get("target_format", "mobi")
    job_type = job.get("job_type")

    log_lines = []
    warnings = []

    def log(msg):
        log_lines.append(f"[{datetime.now(timezone.utc).isoformat()}] {msg}")

    log(f"Starting job {job_id} ({job.get('job_type')})")

    try:
        # 1. Create temp workspace
        workdir = Path(tempfile.mkdtemp(prefix=f"pagedrop_{job_id}_"))
        source_suffix = Path(source_path or "source.html").suffix or ".epub"
        source_file = workdir / f"source{source_suffix}"
        output_file = workdir / f"output.{target_fmt}"
        cover_file = workdir / "cover.jpg"
        requested_cover_file = workdir / "requested_cover"

        # 2. Prepare source
        if job_type == "article":
            if not source_url:
                return {
                    "status": "failed",
                    "error_message": "Article job has no source_url",
                    "logs": log_lines,
                    "completed_at": datetime.now(timezone.utc).isoformat(),
                }
            source_file = workdir / "article.html"
            log(f"Extracting article: {source_url}")
            ok, title, author_or_error = await write_article_source(source_url, source_file)
            if not ok:
                return {
                    "status": "failed",
                    "error_message": author_or_error or "Article extraction failed",
                    "logs": log_lines,
                    "completed_at": datetime.now(timezone.utc).isoformat(),
                }
            if title and not job.get("title"):
                job["title"] = title
            if author_or_error and not job.get("author"):
                job["author"] = author_or_error
        else:
            if not source_path:
                return {
                    "status": "failed",
                    "error_message": "Convert job has no source_storage_path",
                    "logs": log_lines,
                    "completed_at": datetime.now(timezone.utc).isoformat(),
                }
            log(f"Downloading source: {source_path}")
            source_bytes = supabase_download("sources", source_path)
            source_file.write_bytes(source_bytes)

        if input_cover_path:
            log(f"Downloading selected cover: {input_cover_path}")
            requested_cover_file.write_bytes(supabase_download("sources", input_cover_path))
        else:
            requested_cover_file = None

        fingerprint = sha256_file(source_file)

        # 3. Run Calibre conversion
        log(f"Converting to {target_fmt}...")
        rc, sout, serr = await run_calibre(
            source_file,
            output_file,
            title=job.get("title"),
            author=job.get("author"),
            cover_path=requested_cover_file,
        )

        if rc != 0:
            log(f"Conversion failed (exit {rc})")
            return {
                "status": "failed",
                "error_message": serr[:2000] if serr else f"Exit code {rc}",
                "logs": log_lines,
                "source_fingerprint": fingerprint,
                "completed_at": datetime.now(timezone.utc).isoformat(),
            }

        log(f"Conversion done: {output_file.stat().st_size} bytes")

        # 4. Calibre otherwise keeps metadata from the source document (often a PDF's
        # table-of-contents title), which is not the title PageDrop shows to the user.
        metadata_rc, metadata_output = await apply_metadata(
            output_file,
            job.get("title"),
            job.get("author"),
            requested_cover_file,
        )
        if metadata_rc != 0:
            log(f"Metadata update failed (exit {metadata_rc})")
            return {
                "status": "failed",
                "error_message": metadata_output[:2000] or "Calibre could not update ebook metadata",
                "logs": log_lines,
                "source_fingerprint": fingerprint,
                "completed_at": datetime.now(timezone.utc).isoformat(),
            }
        if job.get("title") or job.get("author"):
            log("Applied PageDrop metadata")

        # 5. Extract cover
        cover_storage_path = None
        if await extract_cover(output_file, cover_file):
            log(f"Cover extracted: {cover_file.stat().st_size} bytes")
            cover_storage_path = supabase_upload(
                "artifacts", f"{user_id}/{job_id}/cover.jpg", cover_file.read_bytes(), "image/jpeg"
            )
        else:
            log("No cover extracted")

        # 6. Upload result to Storage
        result_bytes = output_file.read_bytes()
        result_storage_path = supabase_upload(
            "artifacts", f"{user_id}/{job_id}/output.{target_fmt}", result_bytes
        )

        log(f"Artifact uploaded: {result_storage_path}")

        return {
            "status": "complete",
            "result_storage_path": result_storage_path,
            "cover_storage_path": cover_storage_path,
            "source_fingerprint": fingerprint,
            "title": job.get("title"),
            "author": job.get("author"),
            "warnings": warnings,
            "logs": log_lines,
            "completed_at": datetime.now(timezone.utc).isoformat(),
        }

    except Exception as e:
        log(f"Error: {e}")
        return {
            "status": "failed",
            "error_message": str(e)[:2000],
            "logs": log_lines,
            "completed_at": datetime.now(timezone.utc).isoformat(),
        }
    finally:
        # Cleanup temp files
        import shutil
        try:
            shutil.rmtree(workdir)
        except Exception:
            pass


async def health_server(host: str = "0.0.0.0", port: int = 8080):
    """Minimal HTTP health endpoint for Coolify health checks."""
    started_at = datetime.now(timezone.utc)

    async def handler(reader, writer):
        stats = {
            "status": "healthy",
            "service": "pagedrop-worker",
            "started_at": started_at.isoformat(),
        }
        body = json.dumps(stats)
        response = (
            "HTTP/1.1 200 OK\r\n"
            "Content-Type: application/json\r\n"
            f"Content-Length: {len(body)}\r\n"
            "Connection: close\r\n"
            "\r\n"
            f"{body}"
        )
        writer.write(response.encode())
        await writer.drain()
        writer.close()
        await writer.wait_closed()

    server = await asyncio.start_server(handler, host, port)
    print(f"[health] Listening on {host}:{port}")
    async with server:
        await server.serve_forever()


async def poll_loop():
    """Main loop: poll Supabase for pending jobs, process them."""
    ARTIFACT_DIR.mkdir(parents=True, exist_ok=True)

    semaphore = asyncio.Semaphore(MAX_CONCURRENT)

    async def claim_and_process():
        """Atomically claim a pending job and process it."""
        async with semaphore:
            params = {
                "status": "eq.pending",
                "order": "created_at.asc",
                "limit": "1",
            }
            jobs = supabase_get("/jobs", params=params)
            if not jobs:
                return

            job = jobs[0]
            job_id = job["id"]

            supabase_patch(f"/jobs?id=eq.{job_id}&status=eq.pending", {
                "status": "processing",
                "started_at": datetime.now(timezone.utc).isoformat(),
            })

            result = await process_job(job)
            supabase_patch(f"/jobs?id=eq.{job_id}", result)

    while True:
        try:
            await claim_and_process()
        except Exception as e:
            print(f"[worker] Error in poll cycle: {e}")
        await asyncio.sleep(POLL_INTERVAL)


def main():
    print(f"[worker] Starting PageDrop Worker")
    print(f"[worker] Supabase: {SUPABASE_URL}")
    print(f"[worker] Poll interval: {POLL_INTERVAL}s")
    print(f"[worker] Max concurrent: {MAX_CONCURRENT}")
    print(f"[worker] Job timeout: {JOB_TIMEOUT}s")

    async def run_all():
        await asyncio.gather(
            poll_loop(),
            health_server(),
        )

    asyncio.run(run_all())


if __name__ == "__main__":
    main()
