# Unit tests for the worker's storage-path trust, size checks and retention.
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
    assert "larger than the size declared" in result["error_message"]


def test_cleanup_deletes_files_then_rows(monkeypatch):
    calls = []
    now = datetime(2026, 11, 10, 12, 0, tzinfo=timezone.utc)

    def fake_get(path, params=None):
        calls.append(("get", path, params))
        return [{"id": JOB, "user_id": USER}, {"id": "bad", "user_id": USER}]

    def fake_list(bucket, prefix):
        calls.append(("list", bucket, prefix))
        return {"sources": ["book.epub", "cover_c.jpg"], "artifacts": ["output.mobi"]}[bucket]

    monkeypatch.setattr(poll, "supabase_get", fake_get)
    monkeypatch.setattr(poll, "supabase_list", fake_list)
    monkeypatch.setattr(poll, "supabase_remove", lambda bucket, paths: calls.append(("remove", bucket, paths)))
    monkeypatch.setattr(poll, "supabase_delete", lambda path, params=None: calls.append(("delete", path, params)))
    monkeypatch.setattr(poll, "RETENTION_HOURS", 24)

    assert poll.cleanup_expired_jobs(now=now) == 2

    _, path, params = calls[0]
    assert path == "/jobs" and params["created_at"] == "lt.2026-11-09T12:00:00+00:00"
    prefix = f"{USER}/{JOB}/"
    assert calls[1:6] == [
        ("list", "sources", prefix),
        ("remove", "sources", [prefix + "book.epub", prefix + "cover_c.jpg"]),
        ("list", "artifacts", prefix),
        ("remove", "artifacts", [prefix + "output.mobi"]),
        ("delete", "/jobs", {"id": f"eq.{JOB}"}),
    ]
    # A row with a malformed id gets no storage calls (its prefix is untrusted) but is still removed.
    assert calls[6] == ("delete", "/jobs", {"id": "eq.bad"})
    assert calls[7] == ("delete", "/rate_limits", {"date": "lt.2026-11-08"})


def test_cleanup_keeps_row_when_file_deletion_fails(monkeypatch):
    deleted = []
    monkeypatch.setattr(poll, "supabase_get", lambda path, params=None: [{"id": JOB, "user_id": USER}])
    monkeypatch.setattr(poll, "supabase_list", lambda bucket, prefix: ["book.epub"])

    def failing_remove(bucket, paths):
        raise RuntimeError("storage down")

    monkeypatch.setattr(poll, "supabase_remove", failing_remove)
    monkeypatch.setattr(poll, "supabase_delete", lambda path, params=None: deleted.append(path))
    assert poll.cleanup_expired_jobs() == 0
    assert deleted == ["/rate_limits"]
