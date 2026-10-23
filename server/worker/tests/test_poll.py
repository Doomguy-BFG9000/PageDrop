# Unit tests for the worker's storage-path trust and size checks.
#   pip install -r server/worker/requirements-dev.txt
#   python -m pytest server/worker/tests
import asyncio
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

os.environ.setdefault("SUPABASE_URL", "http://supabase.invalid")
os.environ.setdefault("SUPABASE_SERVICE_KEY", "test-key")
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import poll  # noqa: E402

USER = "aaaaaaaa-0000-0000-0000-000000000001"
OTHER = "aaaaaaaa-0000-0000-0000-000000000002"
JOB = "11111111-0000-0000-0000-000000000001"
OTHER_JOB = "11111111-0000-0000-0000-000000000002"


def convert_job(**overrides):
    job = {
        "id": JOB,
        "user_id": USER,
        "job_type": "convert",
        "target_format": "mobi",
        "source_storage_path": f"{USER}/{JOB}/book.epub",
        "input_cover_storage_path": None,
        "file_size_bytes": 10,
    }
    job.update(overrides)
    return job


def test_issued_paths_are_accepted():
    assert poll.validate_job(convert_job()) is None
    assert poll.validate_job(convert_job(input_cover_storage_path=f"{USER}/{JOB}/cover_c.jpg")) is None


def test_paths_outside_the_jobs_folder_are_rejected():
    bad_paths = [
        f"{OTHER}/{OTHER_JOB}/book.epub",       # another user's upload
        f"{USER}/{OTHER_JOB}/book.epub",        # same user, different job
        f"{USER}/{JOB}/../{OTHER_JOB}/book.epub",
        f"{USER}/{JOB}/..",
        f"{USER}/{JOB}/nested/book.epub",
        f"/{USER}/{JOB}/book.epub",
        f"{USER}/{JOB}/",
        f"{USER.upper()}/{JOB}/book.epub",
        f"{USER}/{JOB}/book.epub\nx",
        "",
        None,
        123,
    ]
    for path in bad_paths:
        assert poll.validate_job(convert_job(source_storage_path=path)), path
        assert poll.validate_job(convert_job(input_cover_storage_path=path or "x")), path


def test_malformed_ids_and_formats_are_rejected():
    assert poll.validate_job(convert_job(user_id="../x"))
    assert poll.validate_job(convert_job(id="not-a-uuid"))
    assert poll.validate_job(convert_job(target_format="../../etc/passwd"))
    assert poll.validate_job(convert_job(target_format=None))
    assert poll.validate_job(convert_job(job_type="shell"))


def test_article_jobs_need_http_urls():
    article = convert_job(job_type="article", source_storage_path=None, source_url="https://example.com/a")
    assert poll.validate_job(article) is None
    assert poll.validate_job({**article, "source_url": "file:///etc/passwd"})
    assert poll.validate_job({**article, "source_url": None})


def test_download_size_checks():
    assert poll.check_download_size(b"x" * 10, 10, 100, "Source") is None
    assert poll.check_download_size(b"x" * 11, 10, 100, "Source")
    assert poll.check_download_size(b"x" * 101, None, 100, "Source")


def test_process_job_never_downloads_a_foreign_path(monkeypatch):
    downloads = []
    monkeypatch.setattr(poll, "supabase_download", lambda bucket, path: downloads.append(path) or b"")
    result = asyncio.run(poll.process_job(convert_job(source_storage_path=f"{OTHER}/{OTHER_JOB}/book.epub")))
    assert result["status"] == "failed"
    assert downloads == []

    downloads.clear()
    result = asyncio.run(poll.process_job(convert_job(input_cover_storage_path=f"{OTHER}/{OTHER_JOB}/cover_c.jpg")))
    assert result["status"] == "failed"
    assert downloads == []


def test_process_job_rejects_oversized_upload(monkeypatch):
    monkeypatch.setattr(poll, "supabase_download", lambda bucket, path: b"x" * 11)
    result = asyncio.run(poll.process_job(convert_job(file_size_bytes=10)))
    assert result["status"] == "failed"
    assert result["error_message"] == "File too large"


# Retention tests live in ../test_cleanup.py (stdlib unittest, fake Supabase client).


def test_validate_job_rejects_inputs_calibre_could_execute_or_inline():
    for name in ["x.recipe", "x.downloaded_recipe", "x.html", "x.htmlz", "x.zip", "x.chm", "noext", "x.epub.recipe"]:
        assert poll.validate_job(convert_job(source_storage_path=f"{USER}/{JOB}/{name}")), name
    for name in ["c.recipe", "c.html", "c.svg"]:
        assert poll.validate_job(convert_job(input_cover_storage_path=f"{USER}/{JOB}/cover_{name}")), name
    assert poll.validate_job(convert_job(source_storage_path=f"{USER}/{JOB}/a.PDF")) is None


def test_recipe_job_is_failed_before_download(monkeypatch):
    calls = []
    monkeypatch.setattr(poll, "supabase_download", lambda *a, **k: calls.append(a) or b"")
    result = asyncio.run(poll.process_job(convert_job(source_storage_path=f"{USER}/{JOB}/x.recipe")))
    assert result["status"] == "failed"
    assert calls == []
