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
import traceback
from datetime import datetime, timedelta, timezone
from pathlib import Path

import httpx
import trafilatura

import sandbox
from safe_fetch import UnsafeURL, fetch_html

# ── Config ──────────────────────────────────────────────────────────────────

SUPABASE_URL = os.environ["SUPABASE_URL"]
SUPABASE_SERVICE_KEY = os.environ["SUPABASE_SERVICE_KEY"]  # service_role key

POLL_INTERVAL = int(os.environ.get("POLL_INTERVAL", "5"))  # seconds
MAX_CONCURRENT = int(os.environ.get("MAX_CONCURRENT", "1"))
JOB_TIMEOUT = int(os.environ.get("JOB_TIMEOUT", "300"))    # 5 minutes, for ebook-convert
# Short Calibre / poppler helpers (ebook-meta, pdftotext).
HELPER_TIMEOUT = int(os.environ.get("HELPER_TIMEOUT", "60"))
# Whole job, including downloads, helpers and uploads.
JOB_DEADLINE = JOB_TIMEOUT + 300
# A job still `processing` this long after it started was lost (worker crashed or was
# redeployed mid-job) and is failed by the cleanup loop. Must exceed JOB_DEADLINE.
STUCK_JOB_SECONDS = JOB_TIMEOUT + int(os.environ.get("STUCK_JOB_MARGIN", "900"))
# Jobs whose uploads never finished are deleted (rows and any partial files) after this.
ABANDONED_UPLOAD_HOURS = float(os.environ.get("ABANDONED_UPLOAD_HOURS", "1"))
HTTP_TIMEOUT = 60.0  # seconds per connect/read/write on Supabase calls
ARTIFACT_DIR = Path("/tmp/pagedrop-artifacts")
# Sources, artifacts and job rows older than this are deleted (README: 24-hour expiry).
RETENTION_HOURS = float(os.environ.get("RETENTION_HOURS", "24"))
CLEANUP_INTERVAL = int(os.environ.get("CLEANUP_INTERVAL", "600"))  # seconds

# Keep in sync with create-job and 003_launch_hardening.sql.
MAX_SOURCE_BYTES = 100 * 1024 * 1024
MAX_COVER_BYTES = 15 * 1024 * 1024
# Matches the artifacts bucket's file_size_limit (005_upload_limits.sql).
MAX_ARTIFACT_BYTES = 200 * 1024 * 1024
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
    with httpx.Client(timeout=HTTP_TIMEOUT) as c:
        r = c.get(url, headers=HEADERS, params=params)
        r.raise_for_status()
        return r.json()


def supabase_patch(path: str, data: dict):
    url = f"{SUPABASE_URL}/rest/v1/{path.lstrip('/')}"
    headers = {**HEADERS, "Prefer": "return=minimal"}
    with httpx.Client(timeout=HTTP_TIMEOUT) as c:
        r = c.patch(url, headers=headers, json=data)
        r.raise_for_status()


def supabase_patch_returning(path: str, params: dict, data: dict) -> list[dict]:
    """PATCH the rows matching params; return the updated rows."""
    url = f"{SUPABASE_URL}/rest/v1/{path.lstrip('/')}"
    with httpx.Client(timeout=HTTP_TIMEOUT) as c:
        r = c.patch(url, headers={**HEADERS, "Prefer": "return=representation"}, params=params, json=data)
        r.raise_for_status()
        return r.json()


def supabase_claim(job_id: str) -> dict | None:
    """Atomically move a pending job to processing; return the claimed row, or None if another worker won."""
    url = f"{SUPABASE_URL}/rest/v1/jobs"
    headers = {**HEADERS, "Prefer": "return=representation"}
    with httpx.Client(timeout=HTTP_TIMEOUT) as c:
        r = c.patch(
            url, headers=headers,
            params={"id": f"eq.{job_id}", "status": "eq.pending"},
            json={"status": "processing", "started_at": datetime.now(timezone.utc).isoformat()},
        )
        r.raise_for_status()
        rows = r.json()
    return rows[0] if rows else None


def supabase_upload(bucket: str, storage_path: str, file_bytes: bytes, content_type: str = "application/octet-stream"):
    """Upload a file to Supabase Storage using the service role."""
    url = f"{SUPABASE_URL}/storage/v1/object/{bucket}/{storage_path}"
    headers = {
        "apikey": SUPABASE_SERVICE_KEY,
        "Authorization": f"Bearer {SUPABASE_SERVICE_KEY}",
        "Content-Type": content_type,
    }
    headers["x-upsert"] = "true"
    with httpx.Client(timeout=HTTP_TIMEOUT) as c:
        r = c.post(url, headers=headers, content=file_bytes)
        r.raise_for_status()
    return storage_path


def supabase_delete(path: str, params: dict = None):
    url = f"{SUPABASE_URL}/rest/v1/{path.lstrip('/')}"
    headers = {**HEADERS, "Prefer": "return=minimal"}
    with httpx.Client(timeout=HTTP_TIMEOUT) as c:
        r = c.delete(url, headers=headers, params=params)
        r.raise_for_status()


def supabase_list(bucket: str, prefix: str) -> list[str]:
    """Names of the objects directly under prefix (a folder ending in "/")."""
    url = f"{SUPABASE_URL}/storage/v1/object/list/{bucket}"
    names = []
    with httpx.Client(timeout=HTTP_TIMEOUT) as c:
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
    with httpx.Client(timeout=HTTP_TIMEOUT) as c:
        r = c.request("DELETE", url, headers=HEADERS, json={"prefixes": storage_paths})
        r.raise_for_status()


def supabase_download(bucket: str, storage_path: str) -> bytes:
    """Download a file from Supabase Storage."""
    url = f"{SUPABASE_URL}/storage/v1/object/{bucket}/{storage_path}"
    headers = {
        "apikey": SUPABASE_SERVICE_KEY,
        "Authorization": f"Bearer {SUPABASE_SERVICE_KEY}",
    }
    with httpx.Client(timeout=HTTP_TIMEOUT) as c:
        r = c.get(url, headers=headers)
        r.raise_for_status()
        return r.content


# ── Job validation ──────────────────────────────────────────────────────────

UUID_RE = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
# The characters create-job's sanitizeFileName() leaves in a file name.
STORAGE_FILE_NAME_RE = re.compile(r"^[A-Za-z0-9._ -]{1,255}$")

# Input formats handed to Calibre; keep in sync with create-job's validation.ts. Calibre runs
# .recipe files as Python and HTML-based inputs can pull in local files, so nothing outside
# this list is ever converted, whatever create-job let through.
ALLOWED_SOURCE_EXTENSIONS = frozenset({
    "epub", "kepub", "pdf", "mobi", "azw", "azw3", "prc", "pdb", "docx", "odt", "rtf",
    "txt", "md", "fb2", "fbz", "lit", "cbz",
})
ALLOWED_COVER_EXTENSIONS = frozenset({"jpg", "jpeg", "png", "webp", "gif"})


def file_extension(path) -> str:
    """Lower-case extension without the dot, or "" if there is none."""
    name = str(path).rsplit("/", 1)[-1]
    return name.rsplit(".", 1)[-1].lower() if "." in name.lstrip(".") else ""


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
    if file_extension(job["source_storage_path"]) not in ALLOWED_SOURCE_EXTENSIONS:
        return "Unsupported file type"
    cover = job.get("input_cover_storage_path")
    if cover is not None and not is_job_storage_path(job, cover):
        return "Cover path does not belong to this job"
    if cover is not None and file_extension(cover) not in ALLOWED_COVER_EXTENSIONS:
        return "Unsupported cover image type"
    return None


def check_download_size(data: bytes, declared, maximum: int, what: str) -> str | None:
    """Reject a download larger than the hard cap or than the size declared to create-job."""
    if len(data) > maximum:
        return f"{what} exceeds the {maximum // (1024 * 1024)} MB limit"
    if declared is not None and len(data) > int(declared):
        return f"{what} is larger than the size declared when the job was created"
    return None


# ── Conversion ──────────────────────────────────────────────────────────────


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


async def find_repeated_pdf_lines(pdf_path: Path, ws: sandbox.Workspace, max_lines: int = 6) -> list[str]:
    """Find running headers/footers: short lines (digits ignored) that recur on many pages."""
    try:
        # pdftotext parses the untrusted PDF too, so it runs in the sandbox like Calibre.
        result = await sandbox.run(["pdftotext", "-layout", str(pdf_path), "-"], ws, HELPER_TIMEOUT)
    except Exception as exc:
        print(f"[worker] pdftotext could not run: {type(exc).__name__}: {exc}")
        return []
    if result.returncode != 0:
        return []
    out = result.stdout
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


async def pdf_convert_args(pdf_path: Path, ws: sandbox.Workspace) -> list[str]:
    """Extra ebook-convert options that turn Calibre's page-oriented PDF HTML into a reflowable book."""
    running = await find_repeated_pdf_lines(pdf_path, ws)
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
    ws: sandbox.Workspace,
    title: str | None = None,
    author: str | None = None,
    cover_path: Path | None = None,
    timeout: int | None = None,
    extra_args: list[str] | None = None,
) -> sandbox.Result:
    """Run ebook-convert in the sandbox (default timeout: JOB_TIMEOUT)."""
    cmd = ["ebook-convert", str(input_path), str(output_path)]
    if title and title.strip():
        cmd.extend(["--title", title.strip()])
    if author and author.strip():
        cmd.extend(["--authors", author.strip()])
    if cover_path is not None:
        cmd.extend(["--cover", str(cover_path)])
    if extra_args:
        cmd.extend(extra_args)
    return await sandbox.run(cmd, ws, timeout or JOB_TIMEOUT)


async def apply_metadata(
    ebook_path: Path,
    ws: sandbox.Workspace,
    title: str | None,
    author: str | None,
    cover_path: Path | None = None,
) -> sandbox.Result:
    """Apply PageDrop's user-facing metadata to the converted ebook."""
    cmd = ["ebook-meta", str(ebook_path)]
    if title and title.strip():
        cmd.extend(["--title", title.strip()])
    if author and author.strip():
        cmd.extend(["--authors", author.strip()])
    if cover_path is not None:
        cmd.extend(["--cover", str(cover_path)])
    if len(cmd) == 2:
        return sandbox.Result(0, "", "")
    return await sandbox.run(cmd, ws, HELPER_TIMEOUT)


class ArticleError(Exception):
    """Article could not be fetched or extracted; the message is for server logs only."""


def build_article_html(downloaded: str, url: str) -> tuple[str, str, str | None]:
    """Extract a readable article; return (html, title, author). Runs in a thread."""
    metadata = trafilatura.extract_metadata(downloaded)
    extracted = trafilatura.extract(
        downloaded,
        output_format="html",
        include_comments=False,
        include_tables=True,
        include_images=True,
    )
    if not extracted:
        raise ArticleError("Failed to extract readable article")

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
    return html, title, author


async def fetch_article(url: str, timeout: int = JOB_TIMEOUT) -> tuple[str, str, str | None]:
    """Fetch and extract an article; raises ArticleError (details for the server log only)."""
    try:
        # SSRF guard: validated per hop, pinned to the checked IP, size and time capped.
        downloaded = await fetch_html(url, timeout=min(timeout, 60))
    except UnsafeURL as exc:
        raise ArticleError(f"Refused URL: {exc}") from None
    if not downloaded:
        raise ArticleError("Failed to fetch URL")
    # trafilatura is CPU-bound on untrusted HTML; keep the event loop (health checks,
    # the job deadline) responsive.
    return await asyncio.to_thread(build_article_html, downloaded, url)


def escape_html(value: str) -> str:
    return (
        value.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace('"', "&quot;")
    )


async def extract_cover(ebook_path: Path, output_path: Path, ws: sandbox.Workspace) -> bool:
    """Extract the cover image using ebook-meta; True if a non-empty regular file was written."""
    cmd = ["ebook-meta", str(ebook_path), "--get-cover", str(output_path)]
    result = await sandbox.run(cmd, ws, HELPER_TIMEOUT)
    if result.timed_out:
        return False
    return bool(sandbox.output_size(output_path))


# Messages the job owner can see. jobs.error_message and jobs.logs are both readable by the
# owner (the "Users can read own jobs" RLS policy plus the API roles' default SELECT grant
# on public.jobs), so they only ever get these short generic texts and step names. Calibre
# stderr, exception text and anything else detailed goes to the server log (stdout).
ERR_CONVERSION = "Conversion failed"
ERR_TIMEOUT = "Conversion timed out"
ERR_TOO_LARGE = "File too large"
ERR_UNSUPPORTED = "Unsupported file type"
ERR_UNSUPPORTED_URL = "Unsupported URL"
ERR_INVALID_JOB = "Invalid job"
ERR_ARTICLE = "Could not fetch the article"
PUBLIC_ERRORS = frozenset({
    ERR_CONVERSION, ERR_TIMEOUT, ERR_TOO_LARGE, ERR_UNSUPPORTED, ERR_UNSUPPORTED_URL,
    ERR_INVALID_JOB, ERR_ARTICLE,
})


def public_rejection(reason: str) -> str:
    """Map a validate_job() reason to the message shown to the user."""
    if reason in ("Unsupported file type", "Unsupported cover image type", "Unsupported target format"):
        return ERR_UNSUPPORTED
    if reason == "Only http/https URLs are supported":
        return ERR_UNSUPPORTED_URL
    return ERR_INVALID_JOB


def server_log(job_id, message: str) -> None:
    print(f"[job {job_id}] {message}", flush=True)


def tail(text: str, limit: int = 4000) -> str:
    return text[-limit:] if text else ""


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


async def process_job(job: dict) -> dict:
    """Process a single conversion job. Returns updated job fields."""
    job_id = job.get("id")
    user_id = job.get("user_id")
    source_path = job.get("source_storage_path")
    input_cover_path = job.get("input_cover_storage_path")
    source_url = job.get("source_url")
    target_fmt = job.get("target_format", "mobi")
    job_type = job.get("job_type")

    log_lines = []  # user-visible (jobs.logs): fixed step names only, no error details

    def log(msg):
        log_lines.append(f"[{now_iso()}] {msg}")

    def fail(public: str, detail: str | None = None, **extra) -> dict:
        assert public in PUBLIC_ERRORS
        server_log(job_id, f"failed: {public}" + (f": {detail}" if detail else ""))
        log(public)
        return {"status": "failed", "error_message": public, "logs": log_lines, "completed_at": now_iso(), **extra}

    log(f"Starting {job_type} job")

    rejection = validate_job(job)
    if rejection:
        return fail(public_rejection(rejection), f"rejected: {rejection}")

    ws = None
    try:
        # Fails closed (SandboxUnavailable) if the worker is root but cannot switch user.
        ws = sandbox.create_workspace(f"pagedrop_{job_id}_", sandbox.sandbox_identity())

        # 1. Prepare source. File names inside the workspace are ours: only allowlisted
        # extensions get through validate_job, and the uploaded name is never used.
        if job_type == "article":
            log("Fetching article")
            try:
                html, title, author = await fetch_article(source_url)
            except ArticleError as exc:
                return fail(ERR_ARTICLE, str(exc))
            if title and not job.get("title"):
                job["title"] = title
            if author and not job.get("author"):
                job["author"] = author
            source_bytes = html.encode("utf-8")
            source_file = sandbox.place_file(ws, "article.html", source_bytes)
        else:
            log("Downloading source")
            source_bytes = await asyncio.to_thread(supabase_download, "sources", source_path)
            too_big = check_download_size(source_bytes, job.get("file_size_bytes"), MAX_SOURCE_BYTES, "Source file")
            if too_big:
                return fail(ERR_TOO_LARGE, too_big)
            source_file = sandbox.place_file(ws, f"source.{file_extension(source_path)}", source_bytes)
        fingerprint = hashlib.sha256(source_bytes).hexdigest()

        requested_cover_file = None
        if input_cover_path:
            log("Downloading selected cover")
            cover_bytes = await asyncio.to_thread(supabase_download, "sources", input_cover_path)
            too_big = check_download_size(cover_bytes, job.get("input_cover_size_bytes"), MAX_COVER_BYTES, "Cover image")
            if too_big:
                return fail(ERR_TOO_LARGE, too_big)
            requested_cover_file = sandbox.place_file(ws, "requested_cover", cover_bytes)

        # 2. Run Calibre conversion
        log(f"Converting to {target_fmt}")
        output_file = ws.work / f"output.{target_fmt}"
        extra_args = None
        if is_pdf(source_file):
            extra_args = await pdf_convert_args(source_file, ws)
            log("PDF source: applying reflow heuristics and header/footer cleanup")
        result = await run_calibre(
            source_file,
            output_file,
            ws,
            title=job.get("title"),
            author=job.get("author"),
            cover_path=requested_cover_file,
            extra_args=extra_args,
        )
        if result.timed_out:
            return fail(ERR_TIMEOUT, f"ebook-convert exceeded {JOB_TIMEOUT}s", source_fingerprint=fingerprint)
        if result.returncode != 0:
            return fail(ERR_CONVERSION, f"ebook-convert exit {result.returncode}: {tail(result.stderr)}",
                        source_fingerprint=fingerprint)
        size = sandbox.output_size(output_file)
        if not size:
            return fail(ERR_CONVERSION, "ebook-convert wrote no regular output file", source_fingerprint=fingerprint)
        if size > MAX_ARTIFACT_BYTES:
            return fail(ERR_TOO_LARGE, f"output is {size} bytes", source_fingerprint=fingerprint)
        log("Conversion done")

        # 3. Calibre otherwise keeps metadata from the source document (often a PDF's
        # table-of-contents title), which is not the title PageDrop shows to the user.
        meta = await apply_metadata(output_file, ws, job.get("title"), job.get("author"), requested_cover_file)
        if meta.timed_out:
            return fail(ERR_TIMEOUT, "ebook-meta timed out", source_fingerprint=fingerprint)
        if meta.returncode != 0:
            return fail(ERR_CONVERSION, f"ebook-meta exit {meta.returncode}: {tail(meta.stderr or meta.stdout)}",
                        source_fingerprint=fingerprint)
        if job.get("title") or job.get("author"):
            log("Applied PageDrop metadata")

        # 4. Extract cover. Everything read back from the workspace goes through
        # read_output (no symlinks, regular files only, size-capped): the worker is root.
        cover_storage_path = None
        cover_file = ws.work / "cover.jpg"
        if await extract_cover(output_file, cover_file, ws):
            try:
                cover_data = sandbox.read_output(cover_file, MAX_COVER_BYTES)
            except (sandbox.UnsafeOutput, OSError) as exc:
                server_log(job_id, f"cover not used: {exc}")
                cover_data = None
            if cover_data:
                log("Cover extracted")
                cover_storage_path = await asyncio.to_thread(
                    supabase_upload, "artifacts", f"{user_id}/{job_id}/cover.jpg", cover_data, "image/jpeg"
                )
        if cover_storage_path is None:
            log("No cover extracted")

        # 5. Upload result to Storage
        result_bytes = sandbox.read_output(output_file, MAX_ARTIFACT_BYTES)
        result_storage_path = await asyncio.to_thread(
            supabase_upload, "artifacts", f"{user_id}/{job_id}/output.{target_fmt}", result_bytes
        )
        log("Result uploaded")

        return {
            "status": "complete",
            "result_storage_path": result_storage_path,
            "cover_storage_path": cover_storage_path,
            "source_fingerprint": fingerprint,
            "title": job.get("title"),
            "author": job.get("author"),
            "warnings": [],
            "logs": log_lines,
            "completed_at": now_iso(),
        }

    except Exception as exc:
        return fail(ERR_CONVERSION, f"{type(exc).__name__}: {exc}\n{traceback.format_exc()}")
    finally:
        if ws is not None:
            sandbox.remove_workspace(ws)


async def run_job_with_deadline(job: dict, deadline: float = JOB_DEADLINE) -> dict:
    """process_job bounded by an overall deadline; a cancelled job's children are killed."""
    try:
        return await asyncio.wait_for(process_job(job), deadline)
    except asyncio.TimeoutError:
        server_log(job.get("id"), f"failed: {ERR_TIMEOUT}: job exceeded {deadline}s")
        return {
            "status": "failed",
            "error_message": ERR_TIMEOUT,
            "logs": [f"[{now_iso()}] {ERR_TIMEOUT}"],
            "completed_at": now_iso(),
        }


# ── Retention ───────────────────────────────────────────────────────────────


RECORDED_PATH_COLUMNS = {
    "sources": ("source_storage_path", "input_cover_storage_path"),
    "artifacts": ("result_storage_path", "cover_storage_path"),
}


def _list_job_objects(bucket: str, prefix: str, job_id: str) -> list[str]:
    """Full paths of everything the listing shows for a job, asking with and without the trailing slash."""
    found = [prefix + name for name in supabase_list(bucket, prefix)]
    # Some storage versions treat "<user>/<job>" differently from "<user>/<job>/"; that
    # listing may also echo the job folder itself, which is not an object.
    bare = prefix.rstrip("/")
    found += [f"{bare}/{name}" for name in supabase_list(bucket, bare) if name != job_id]
    return found


def expire_job_files(job: dict) -> bool:
    """Remove every stored file of a job. True only if both buckets are verified empty afterwards."""
    prefix = job_storage_prefix(job)
    if prefix is None:
        return True  # nothing trustworthy to look for; the row can go
    job_id = str(job["id"])
    clean = True
    for bucket, columns in RECORDED_PATH_COLUMNS.items():
        # Exact recorded paths (only if they sit inside this job's folder), plus whatever is listed.
        targets = {job[c] for c in columns if is_job_storage_path(job, job.get(c))}
        targets.update(_list_job_objects(bucket, prefix, job_id))
        if targets:
            supabase_remove(bucket, sorted(targets))
        if _list_job_objects(bucket, prefix, job_id):
            print(f"[cleanup] Job {job_id}: files still present in {bucket} after removal, will retry")
            clean = False
    return clean


def cleanup_expired_jobs(now: datetime | None = None, batch: int = 200) -> int:
    """Delete the storage files and rows of jobs older than RETENTION_HOURS.

    A row is deleted only after its files are removed and a fresh listing shows nothing
    left in either bucket; otherwise it stays for the next pass to retry.
    Returns the number of job rows deleted.
    """
    cutoff = (now or datetime.now(timezone.utc)) - timedelta(hours=RETENTION_HOURS)
    jobs = supabase_get("/jobs", params={
        "select": JOB_FILE_COLUMNS,
        "created_at": f"lt.{cutoff.isoformat()}",
        "order": "created_at.asc",
        "limit": str(batch),
    })
    deleted = 0
    for job in jobs:
        try:
            if not expire_job_files(job):
                continue
            supabase_delete("/jobs", params={"id": f"eq.{job['id']}"})
            deleted += 1
        except Exception as e:
            print(f"[cleanup] Could not expire job {job.get('id')}: {type(e).__name__}: {e}")
    # Daily counters are only read for today; keep yesterday's for debugging.
    old_day = (cutoff - timedelta(days=1)).date().isoformat()
    supabase_delete("/rate_limits", params={"date": f"lt.{old_day}"})
    for table in ("ip_rate_limits", "daily_totals"):
        try:
            supabase_delete(f"/{table}", params={"date": f"lt.{old_day}"})
        except Exception as e:  # table missing until migration 004 is applied
            print(f"[cleanup] Could not prune {table}: {type(e).__name__}")
    return deleted


JOB_FILE_COLUMNS = "id,user_id,source_storage_path,input_cover_storage_path,result_storage_path,cover_storage_path"


def cleanup_abandoned_uploads(now: datetime | None = None, batch: int = 200) -> int:
    """Delete jobs still awaiting_upload after ABANDONED_UPLOAD_HOURS, files first.

    Their storage objects (possibly partial uploads) count against nobody's quota once the
    row is gone, so they are removed before the row; the row delete only matches while the
    job is still awaiting_upload, so a job queued in the meantime is left alone.
    """
    cutoff = (now or datetime.now(timezone.utc)) - timedelta(hours=ABANDONED_UPLOAD_HOURS)
    jobs = supabase_get("/jobs", params={
        "select": JOB_FILE_COLUMNS,
        "status": "eq.awaiting_upload",
        "created_at": f"lt.{cutoff.isoformat()}",
        "order": "created_at.asc",
        "limit": str(batch),
    })
    deleted = 0
    for job in jobs:
        try:
            if not expire_job_files(job):
                continue
            supabase_delete("/jobs", params={"id": f"eq.{job['id']}", "status": "eq.awaiting_upload"})
            deleted += 1
        except Exception as e:
            print(f"[cleanup] Could not delete abandoned job {job.get('id')}: {type(e).__name__}: {e}")
    return deleted


def reset_stuck_jobs(now: datetime | None = None) -> int:
    """Fail jobs left in `processing` for longer than STUCK_JOB_SECONDS (lost by a crash or redeploy)."""
    now = now or datetime.now(timezone.utc)
    cutoff = (now - timedelta(seconds=STUCK_JOB_SECONDS)).strftime("%Y-%m-%dT%H:%M:%SZ")
    rows = supabase_patch_returning("/jobs", params={
        "status": "eq.processing",
        "or": f"(started_at.lt.{cutoff},started_at.is.null)",
        "select": "id",
    }, data={
        "status": "failed",
        "error_message": ERR_TIMEOUT,
        "logs": [f"[{now.isoformat()}] {ERR_TIMEOUT}"],
        "completed_at": now.isoformat(),
    })
    for row in rows:
        server_log(row.get("id"), f"failed: {ERR_TIMEOUT}: still processing after {STUCK_JOB_SECONDS}s, reset")
    return len(rows)


def run_cleanup_cycle() -> None:
    """One pass of every maintenance task; a failure in one does not skip the others."""
    tasks = (
        ("reset stuck jobs", reset_stuck_jobs, "Reset {} stuck job(s)"),
        ("abandoned uploads", cleanup_abandoned_uploads, "Deleted {} abandoned upload(s)"),
        ("retention", cleanup_expired_jobs, f"Expired {{}} job(s) older than {RETENTION_HOURS:g}h"),
    )
    for name, task, message in tasks:
        try:
            count = task()
            if count:
                print(f"[cleanup] {message.format(count)}")
        except Exception as e:
            print(f"[cleanup] Error in {name}: {type(e).__name__}: {e}")


async def cleanup_loop():
    while True:
        await asyncio.to_thread(run_cleanup_cycle)
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
            jobs = await asyncio.to_thread(supabase_get, "/jobs", params)
            if not jobs:
                return

            job = jobs[0]
            job_id = job["id"]

            # The PATCH only matches while the row is still pending; an empty
            # response means someone else claimed it first, so skip it.
            claimed = await asyncio.to_thread(supabase_claim, job_id)
            if not claimed:
                return
            job = claimed

            try:
                result = await run_job_with_deadline(job)
            finally:
                # Jobs run one at a time, so no other sandbox process should be alive now;
                # kill any that escaped their session (setsid) before the next job starts.
                await asyncio.to_thread(sandbox.kill_stray_processes, sandbox_identity_or_none())
            # If this write fails the job stays `processing`; reset_stuck_jobs fails it later.
            await asyncio.to_thread(supabase_patch, f"/jobs?id=eq.{job_id}", result)

    while True:
        try:
            await claim_and_process()
        except Exception as e:
            print(f"[worker] Error in poll cycle: {e}")
        await asyncio.sleep(POLL_INTERVAL)


def sandbox_identity_or_none():
    try:
        return sandbox.sandbox_identity()
    except sandbox.SandboxUnavailable:
        return None


def main():
    print(f"[worker] Starting PageDrop Worker")
    try:
        identity = sandbox.sandbox_identity()
    except sandbox.SandboxUnavailable as exc:
        print(f"[worker] Refusing to start: {exc}")
        raise SystemExit(1)
    if identity is None:
        print("[worker] WARNING: not running as root, Calibre runs as the worker's own user "
              "(no uid sandbox). Only acceptable for local development.")
    else:
        print(f"[worker] Calibre sandbox: user {sandbox.SANDBOX_USER} (uid {identity.uid})")
    # A job that was processing when the previous worker stopped will never finish.
    try:
        reset = reset_stuck_jobs()
        if reset:
            print(f"[worker] Reset {reset} stuck job(s) at startup")
    except Exception as e:
        print(f"[worker] Could not reset stuck jobs at startup: {type(e).__name__}: {e}")
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
