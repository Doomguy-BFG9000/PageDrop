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
import re
import subprocess
import tempfile
import time
import uuid
from datetime import datetime, timedelta, timezone
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
# Sources, artifacts and job rows older than this are deleted (README: 24-hour expiry).
RETENTION_HOURS = float(os.environ.get("RETENTION_HOURS", "24"))
CLEANUP_INTERVAL = int(os.environ.get("CLEANUP_INTERVAL", "600"))  # seconds

# Keep in sync with create-job and 003_launch_hardening.sql.
MAX_SOURCE_BYTES = 100 * 1024 * 1024
MAX_COVER_BYTES = 15 * 1024 * 1024
ALLOWED_TARGET_FORMATS = {
    "azw3", "epub", "mobi", "docx", "fb2", "htmlz", "kepub", "lit", "lrf", "pdb",
    "pdf", "pmlz", "rb", "rtf", "snb", "tcr", "txt", "txtz", "zip",
}

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


def supabase_delete(path: str, params: dict = None):
    url = f"{SUPABASE_URL}/rest/v1/{path.lstrip('/')}"
    headers = {**HEADERS, "Prefer": "return=minimal"}
    with httpx.Client() as c:
        r = c.delete(url, headers=headers, params=params)
        r.raise_for_status()


def supabase_list(bucket: str, prefix: str) -> list[str]:
    """Names of the objects directly under prefix (a folder ending in "/")."""
    url = f"{SUPABASE_URL}/storage/v1/object/list/{bucket}"
    names = []
    with httpx.Client() as c:
        while True:
            r = c.post(url, headers=HEADERS, json={"prefix": prefix, "limit": 1000, "offset": len(names)})
            r.raise_for_status()
            page = [item["name"] for item in r.json()]
            names.extend(page)
            if len(page) < 1000:
                return names


def supabase_remove(bucket: str, storage_paths: list[str]):
    """Delete objects from Supabase Storage."""
    url = f"{SUPABASE_URL}/storage/v1/object/{bucket}"
    with httpx.Client() as c:
        r = c.request("DELETE", url, headers=HEADERS, json={"prefixes": storage_paths})
        r.raise_for_status()


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


# ── Job validation ──────────────────────────────────────────────────────────

UUID_RE = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
# The characters create-job's sanitizeFileName() leaves in a file name.
STORAGE_FILE_NAME_RE = re.compile(r"^[A-Za-z0-9._ -]{1,255}$")


def job_storage_prefix(job: dict) -> str | None:
    """The job owner's folder for this job, "<user_id>/<job_id>/", or None if either id is malformed."""
    user_id, job_id = str(job.get("user_id", "")), str(job.get("id", ""))
    if not (UUID_RE.match(user_id) and UUID_RE.match(job_id)):
        return None
    return f"{user_id}/{job_id}/"


def is_job_storage_path(job: dict, path) -> bool:
    """True if path is a single file directly inside the job owner's folder for this job.

    The worker runs as the service role and bypasses Storage RLS, so a path pointing
    anywhere else (another user's upload, "..", nested folders) must never be read.
    """
    prefix = job_storage_prefix(job)
    if prefix is None or not isinstance(path, str) or not path.startswith(prefix):
        return False
    name = path[len(prefix):]
    return bool(STORAGE_FILE_NAME_RE.match(name)) and name not in (".", "..")


def validate_job(job: dict) -> str | None:
    """Return why the job must not be processed, or None if it is safe to run."""
    if job_storage_prefix(job) is None:
        return "Job has an invalid id or owner"
    if job.get("target_format") not in ALLOWED_TARGET_FORMATS:
        return "Unsupported target format"
    job_type = job.get("job_type")
    if job_type == "article":
        url = job.get("source_url")
        if not isinstance(url, str) or not url.startswith(("http://", "https://")):
            return "Only http/https URLs are supported"
        return None
    if job_type != "convert":
        return "Unsupported job type"
    if not is_job_storage_path(job, job.get("source_storage_path")):
        return "Source path does not belong to this job"
    cover = job.get("input_cover_storage_path")
    if cover is not None and not is_job_storage_path(job, cover):
        return "Cover path does not belong to this job"
    return None


def check_download_size(data: bytes, declared, maximum: int, what: str) -> str | None:
    """Reject a download larger than the hard cap or than the size declared to create-job."""
    if len(data) > maximum:
        return f"{what} exceeds the {maximum // (1024 * 1024)} MB limit"
    if declared is not None and len(data) > int(declared):
        return f"{what} is larger than the size declared when the job was created"
    return None


# ── Conversion ──────────────────────────────────────────────────────────────


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    h.update(path.read_bytes())
    return h.hexdigest()


# Page furniture left in the text by Calibre's PDF engine (which feeds the pipeline <br>-separated
# lines with an <hr/> per page): "N | P a g e ..." running headers and lines that are only a page number.
PDF_PAGE_LABEL_RE = r"\d{1,4}\s*\|\s*P\s*a\s*g\s*e[^<]*|Page\s+\d{1,4}\s+of\s+\d{1,4}"
# A bare page number is only removed as the LAST line of a page, i.e. one followed (past blank lines)
# by the page's <hr/>; sr1 has already removed running footers/headers by then. A lone chapter number such as "1" above
# a chapter title mid-page is not followed by <hr/> and survives.
PDF_PAGE_END_LOOKAHEAD = r"(?=(?:[ \t]*(?:<br\s*/?>)?[ \t]*\n)*[ \t]*<hr\s*/?>)"
PDF_PAGE_NUMBER_LINE_RE = (
    r"(?m)^[ \t]*\d{1,4}[ \t]*<br\s*/?>[ \t]*\n" + PDF_PAGE_END_LOOKAHEAD
    + r"|<p[^>]*>\s*\d{1,4}\s*</p>\s*" + PDF_PAGE_END_LOOKAHEAD
)
# Per-page <hr/> and page anchors otherwise end up between the two halves of a paragraph that crosses a page and block unwrapping.
PDF_PAGE_BREAK_RE = r"<hr\s*/?>|<a\s+id=\"p\d+\"\s*></a>"
# Between words a running line may be split across lines or paragraphs ("...Planet eBook.com<br>11").
PDF_WORD_GAP_RE = r"(?:\s|</?p[^>]*>|<br\s*/?>)+"


def is_pdf(path: Path) -> bool:
    return path.suffix.lower() == ".pdf"


def find_repeated_pdf_lines(pdf_path: Path, max_lines: int = 6) -> list[str]:
    """Find running headers/footers: short lines (digits ignored) that recur on many pages."""
    try:
        out = subprocess.run(
            ["pdftotext", "-layout", str(pdf_path), "-"],
            capture_output=True, text=True, timeout=60, check=True,
        ).stdout
    except Exception:
        return []
    pages = [p for p in out.split("\f") if p.strip()]
    if len(pages) < 6:
        return []
    counts: dict[str, int] = {}
    for page in pages:
        lines = [ln.strip() for ln in page.splitlines() if ln.strip()]
        # Headers/footers live in the first or last few lines of a page.
        seen = {re.sub(r"\d+", "#", " ".join(ln.split())) for ln in lines[:3] + lines[-3:]}
        for key in seen:
            counts[key] = counts.get(key, 0) + 1
    repeated = [
        key for key, n in counts.items()
        if n >= max(4, len(pages) * 0.3) and 3 <= len(key) <= 120 and re.search(r"[A-Za-z]{3}", key)
    ]
    return sorted(repeated, key=lambda k: -counts[k])[:max_lines]


def running_line_pattern(lines: list[str]) -> str:
    """Regex for the given running lines in Calibre's HTML; page numbers are optional and may sit in their own paragraph.

    Every alternative must be a whole standalone line (start of line, after <br>, <p> or a page anchor, and
    followed by <br>, </p> or end of line), so the same words inside body sentences are never touched.
    """
    parts = []
    for line in lines:
        words = line.split()
        lead = r"(?:\d+" + PDF_WORD_GAP_RE + ")?" if words[0] == "#" else ""
        trail = "(?:" + PDF_WORD_GAP_RE + r"\d+)?" if words[-1] == "#" else ""
        core = [r"\d+".join(re.escape(piece) for piece in w.split("#")) for w in words if w != "#"]
        if core:
            parts.append(lead + PDF_WORD_GAP_RE.join(core) + trail)
    if not parts:
        return ""
    # Lookbehinds are fixed-width, hence one per allowed predecessor.
    start = r"(?:^|(?<=<br>)|(?<=<br/>)|(?<=<br />)|(?<=></a>)|(?<=<hr/>)|(?<=<p>)|(?<=<p/>))[ \t]*"
    end = r"[ \t]*(?:<br\s*/?>|(?=</p>|$))"
    return r"(?m)" + start + "(?:" + "|".join(parts) + ")" + end


def pdf_convert_args(pdf_path: Path) -> list[str]:
    """Extra ebook-convert options that turn Calibre's page-oriented PDF HTML into a reflowable book."""
    running = find_repeated_pdf_lines(pdf_path)
    # sr1: detected repeated header/footer text, sr2: "N | P a g e" labels and bare page-number
    # lines, sr3: per-page <hr/> breaks and page anchors.
    sr1 = running_line_pattern(running) or PDF_PAGE_LABEL_RE
    return [
        "--output-profile", "kindle_pw",  # 758x1024 Paperwhite screen
        "--minimum-line-height", "0",   # forced line-height makes older Kindle readers clip lines at page edges
        "--enable-heuristics",         # master switch; unwrap, blank-paragraph removal and dehyphenation are on by default
        "--unwrap-factor", "0.40",      # join hard-wrapped PDF lines and paragraphs split at page breaks
        "--remove-paragraph-spacing",   # no blank gap between paragraphs, indent instead
        "--remove-paragraph-spacing-indent-size", "1.5",
        "--sr1-search", sr1, "--sr1-replace", "",
        "--sr2-search", PDF_PAGE_NUMBER_LINE_RE + "|" + PDF_PAGE_LABEL_RE, "--sr2-replace", "",
        "--sr3-search", PDF_PAGE_BREAK_RE, "--sr3-replace", "",
        "--comments", "",               # source PDF subject/keywords often carry site spam
        "--tags", "",
    ]


async def run_calibre(
    input_path: Path,
    output_path: Path,
    title: str | None = None,
    author: str | None = None,
    cover_path: Path | None = None,
    timeout: int = JOB_TIMEOUT,
    extra_args: list[str] | None = None,
) -> tuple[int, str, str]:
    """Run ebook-convert, return (returncode, stdout, stderr)."""
    cmd = ["ebook-convert", str(input_path), str(output_path)]
    if title and title.strip():
        cmd.extend(["--title", title.strip()])
    if author and author.strip():
        cmd.extend(["--authors", author.strip()])
    if cover_path and cover_path.exists():
        cmd.extend(["--cover", str(cover_path)])
    if extra_args:
        cmd.extend(extra_args)
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

    rejection = validate_job(job)
    if rejection:
        log(f"Rejected: {rejection}")
        return {
            "status": "failed",
            "error_message": rejection,
            "logs": log_lines,
            "completed_at": datetime.now(timezone.utc).isoformat(),
        }

    workdir = None
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
            too_big = check_download_size(source_bytes, job.get("file_size_bytes"), MAX_SOURCE_BYTES, "Source file")
            if too_big:
                return {
                    "status": "failed",
                    "error_message": too_big,
                    "logs": log_lines,
                    "completed_at": datetime.now(timezone.utc).isoformat(),
                }
            source_file.write_bytes(source_bytes)

        if input_cover_path:
            log(f"Downloading selected cover: {input_cover_path}")
            cover_bytes = supabase_download("sources", input_cover_path)
            too_big = check_download_size(cover_bytes, job.get("input_cover_size_bytes"), MAX_COVER_BYTES, "Cover image")
            if too_big:
                return {
                    "status": "failed",
                    "error_message": too_big,
                    "logs": log_lines,
                    "completed_at": datetime.now(timezone.utc).isoformat(),
                }
            requested_cover_file.write_bytes(cover_bytes)
        else:
            requested_cover_file = None

        fingerprint = sha256_file(source_file)

        # 3. Run Calibre conversion
        log(f"Converting to {target_fmt}...")
        extra_args = None
        if is_pdf(source_file):
            extra_args = await asyncio.to_thread(pdf_convert_args, source_file)
            log("PDF source: applying reflow heuristics and header/footer cleanup")
        rc, sout, serr = await run_calibre(
            source_file,
            output_file,
            title=job.get("title"),
            author=job.get("author"),
            cover_path=requested_cover_file,
            extra_args=extra_args,
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
        if workdir is not None:
            shutil.rmtree(workdir, ignore_errors=True)


# ── Retention ───────────────────────────────────────────────────────────────


def cleanup_expired_jobs(now: datetime | None = None, batch: int = 200) -> int:
    """Delete the storage files and rows of jobs older than RETENTION_HOURS.

    Files go first, so a failure leaves the row behind for the next pass to retry.
    Returns the number of job rows deleted.
    """
    cutoff = (now or datetime.now(timezone.utc)) - timedelta(hours=RETENTION_HOURS)
    jobs = supabase_get("/jobs", params={
        "select": "id,user_id",
        "created_at": f"lt.{cutoff.isoformat()}",
        "order": "created_at.asc",
        "limit": str(batch),
    })
    deleted = 0
    for job in jobs:
        prefix = job_storage_prefix(job)
        try:
            if prefix is not None:
                for bucket in ("sources", "artifacts"):
                    names = supabase_list(bucket, prefix)
                    if names:
                        supabase_remove(bucket, [prefix + name for name in names])
            supabase_delete("/jobs", params={"id": f"eq.{job['id']}"})
            deleted += 1
        except Exception as e:
            print(f"[cleanup] Could not expire job {job.get('id')}: {e}")
    # Daily counters are only read for today; keep yesterday's for debugging.
    supabase_delete("/rate_limits", params={"date": f"lt.{(cutoff - timedelta(days=1)).date().isoformat()}"})
    return deleted


async def cleanup_loop():
    while True:
        try:
            deleted = await asyncio.to_thread(cleanup_expired_jobs)
            if deleted:
                print(f"[cleanup] Expired {deleted} job(s) older than {RETENTION_HOURS:g}h")
        except Exception as e:
            print(f"[cleanup] Error in cleanup cycle: {e}")
        await asyncio.sleep(CLEANUP_INTERVAL)


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
    print(f"[worker] Retention: {RETENTION_HOURS:g}h (cleanup every {CLEANUP_INTERVAL}s)")

    async def run_all():
        await asyncio.gather(
            poll_loop(),
            cleanup_loop(),
            health_server(),
        )

    asyncio.run(run_all())


if __name__ == "__main__":
    main()
