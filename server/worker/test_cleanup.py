# Retention tests with an in-memory fake of the Supabase storage and REST helpers.
#   python3 -m unittest discover -s server/worker
import os
import sys
import types
import unittest
from datetime import datetime, timezone
from pathlib import Path
from unittest import mock

os.environ.setdefault("SUPABASE_URL", "http://supabase.invalid")
os.environ.setdefault("SUPABASE_SERVICE_KEY", "test-key")
sys.path.insert(0, str(Path(__file__).resolve().parent))
for missing in ("httpx", "trafilatura"):  # poll imports them; these tests never use them
    try:
        __import__(missing)
    except ImportError:
        sys.modules[missing] = types.ModuleType(missing)

import poll  # noqa: E402

USER = "aaaaaaaa-0000-0000-0000-000000000001"
JOB = "11111111-0000-0000-0000-000000000001"
JOB2 = "11111111-0000-0000-0000-000000000002"
OTHER_USER = "aaaaaaaa-0000-0000-0000-000000000002"
NOW = datetime(2026, 11, 10, 12, 0, tzinfo=timezone.utc)


class FakeSupabase:
    def __init__(self, jobs, storage, fail_remove=False, ignore_remove=False):
        self.jobs, self.storage = jobs, storage  # storage: {bucket: set(full paths)}
        self.fail_remove, self.ignore_remove = fail_remove, ignore_remove
        self.deleted_rows, self.removed = [], []

    def get(self, path, params=None):
        return list(self.jobs)

    def list(self, bucket, prefix):
        folder = prefix if prefix.endswith("/") else prefix + "/"
        return sorted(p[len(folder):] for p in self.storage[bucket] if p.startswith(folder) and "/" not in p[len(folder):])

    def remove(self, bucket, paths):
        self.removed.append((bucket, list(paths)))
        if self.fail_remove:
            raise RuntimeError("storage down")
        if not self.ignore_remove:
            self.storage[bucket] -= set(paths)

    def delete(self, path, params=None):
        if path == "/jobs":
            self.deleted_rows.append(params["id"])

    def run(self):
        with mock.patch.object(poll, "supabase_get", self.get), mock.patch.object(poll, "supabase_list", self.list), \
                mock.patch.object(poll, "supabase_remove", self.remove), mock.patch.object(poll, "supabase_delete", self.delete), \
                mock.patch.object(poll, "RETENTION_HOURS", 24):
            return poll.cleanup_expired_jobs(now=NOW)


def job_row(job_id=JOB, **kw):
    row = {"id": job_id, "user_id": USER,
           "source_storage_path": f"{USER}/{job_id}/book.epub", "input_cover_storage_path": f"{USER}/{job_id}/cover_c.jpg",
           "result_storage_path": f"{USER}/{job_id}/output.mobi", "cover_storage_path": f"{USER}/{job_id}/cover.jpg"}
    row.update(kw)
    return row


def full_storage(job_id=JOB):
    return {
        "sources": {f"{USER}/{job_id}/book.epub", f"{USER}/{job_id}/cover_c.jpg"},
        "artifacts": {f"{USER}/{job_id}/output.mobi", f"{USER}/{job_id}/cover.jpg"},
    }


class CleanupTests(unittest.TestCase):
    def test_removes_recorded_and_listed_files_then_the_row(self):
        storage = full_storage()
        storage["sources"].add(f"{USER}/{JOB}/stray.bin")  # not recorded anywhere
        fake = FakeSupabase([job_row()], storage)
        self.assertEqual(fake.run(), 1)
        self.assertEqual(fake.deleted_rows, [f"eq.{JOB}"])
        self.assertEqual(storage, {"sources": set(), "artifacts": set()})
        removed = dict(fake.removed)
        self.assertIn(f"{USER}/{JOB}/stray.bin", removed["sources"])
        self.assertIn(f"{USER}/{JOB}/output.mobi", removed["artifacts"])

    def test_recorded_paths_are_removed_even_if_the_listing_is_empty(self):
        class BlindList(FakeSupabase):
            def list(self, bucket, prefix):
                return []
        fake = BlindList([job_row()], full_storage())
        self.assertEqual(fake.run(), 1)  # nothing listed afterwards, removal of recorded paths was still requested
        removed = dict(fake.removed)
        self.assertEqual(sorted(removed["sources"]), sorted([f"{USER}/{JOB}/book.epub", f"{USER}/{JOB}/cover_c.jpg"]))
        self.assertEqual(sorted(removed["artifacts"]), sorted([f"{USER}/{JOB}/output.mobi", f"{USER}/{JOB}/cover.jpg"]))

    def test_row_is_kept_when_removal_fails(self):
        fake = FakeSupabase([job_row()], full_storage(), fail_remove=True)
        with mock.patch("builtins.print") as log:
            self.assertEqual(fake.run(), 0)
        self.assertEqual(fake.deleted_rows, [])
        self.assertTrue(any("Could not expire" in str(c) for c in log.call_args_list))
        self.assertFalse(any("test-key" in str(c) for c in log.call_args_list))

    def test_row_is_kept_when_files_remain_after_removal(self):
        fake = FakeSupabase([job_row()], full_storage(), ignore_remove=True)
        self.assertEqual(fake.run(), 0)
        self.assertEqual(fake.deleted_rows, [])

    def test_failure_on_one_job_does_not_block_the_next(self):
        storage = full_storage(JOB)
        for bucket, paths in full_storage(JOB2).items():
            storage[bucket] |= paths

        class OneBad(FakeSupabase):
            def remove(self, bucket, paths):
                if any(JOB in p for p in paths):
                    raise RuntimeError("boom")
                super().remove(bucket, paths)
        fake = OneBad([job_row(JOB), job_row(JOB2)], storage)
        self.assertEqual(fake.run(), 1)
        self.assertEqual(fake.deleted_rows, [f"eq.{JOB2}"])

    def test_job_without_files_still_expires(self):
        row = job_row(source_storage_path=None, input_cover_storage_path=None, result_storage_path=None, cover_storage_path=None)
        fake = FakeSupabase([row], {"sources": set(), "artifacts": set()})
        self.assertEqual(fake.run(), 1)
        self.assertEqual(fake.removed, [])

    def test_recorded_path_outside_the_job_folder_is_never_removed(self):
        evil = job_row(source_storage_path=f"{OTHER_USER}/{JOB2}/victim.epub")
        storage = full_storage()
        storage["sources"].add(f"{OTHER_USER}/{JOB2}/victim.epub")
        fake = FakeSupabase([evil], storage)
        fake.run()
        self.assertIn(f"{OTHER_USER}/{JOB2}/victim.epub", storage["sources"])

    def test_malformed_ids_get_no_storage_calls(self):
        fake = FakeSupabase([{"id": "bad", "user_id": USER}], {"sources": set(), "artifacts": set()})
        self.assertEqual(fake.run(), 1)
        self.assertEqual(fake.removed, [])



class AbandonedUploadTests(unittest.TestCase):
    def run_abandoned(self, fake):
        calls = []

        def get(path, params=None):
            calls.append(params)
            return list(fake.jobs)

        with mock.patch.object(poll, "supabase_get", get), mock.patch.object(poll, "supabase_list", fake.list), \
                mock.patch.object(poll, "supabase_remove", fake.remove), \
                mock.patch.object(poll, "supabase_delete", self.recording_delete(fake)), \
                mock.patch.object(poll, "ABANDONED_UPLOAD_HOURS", 1):
            return poll.cleanup_abandoned_uploads(now=NOW), calls

    def recording_delete(self, fake):
        self.delete_params = []

        def delete(path, params=None):
            self.delete_params.append(params)
            fake.delete(path, params)
        return delete

    def test_only_old_awaiting_upload_jobs_are_selected(self):
        fake = FakeSupabase([], {"sources": set(), "artifacts": set()})
        _, calls = self.run_abandoned(fake)
        self.assertEqual(calls[0]["status"], "eq.awaiting_upload")
        self.assertEqual(calls[0]["created_at"], "lt.2026-11-10T11:00:00+00:00")

    def test_partial_uploads_and_row_are_deleted(self):
        storage = {"sources": {f"{USER}/{JOB}/book.epub"}, "artifacts": set()}
        row = job_row(input_cover_storage_path=None, result_storage_path=None, cover_storage_path=None)
        fake = FakeSupabase([row], storage)
        deleted, _ = self.run_abandoned(fake)
        self.assertEqual(deleted, 1)
        self.assertEqual(storage["sources"], set())
        # The delete only matches while the job is still awaiting upload.
        self.assertEqual(self.delete_params, [{"id": f"eq.{JOB}", "status": "eq.awaiting_upload"}])

    def test_row_is_kept_if_files_cannot_be_removed(self):
        fake = FakeSupabase([job_row()], full_storage(), ignore_remove=True)
        deleted, _ = self.run_abandoned(fake)
        self.assertEqual(deleted, 0)
        self.assertEqual(fake.deleted_rows, [])


class StuckJobTests(unittest.TestCase):
    def test_processing_jobs_past_the_timeout_are_failed_generically(self):
        captured = {}

        def patch(path, params, data):
            captured.update(path=path, params=params, data=data)
            return [{"id": JOB}]

        with mock.patch.object(poll, "supabase_patch_returning", patch), \
                mock.patch.object(poll, "STUCK_JOB_SECONDS", 1200), mock.patch("builtins.print"):
            self.assertEqual(poll.reset_stuck_jobs(now=NOW), 1)
        self.assertEqual(captured["path"], "/jobs")
        self.assertEqual(captured["params"]["status"], "eq.processing")
        self.assertEqual(captured["params"]["or"], "(started_at.lt.2026-11-10T11:40:00Z,started_at.is.null)")
        self.assertEqual(captured["data"]["status"], "failed")
        self.assertEqual(captured["data"]["error_message"], "Conversion timed out")

    def test_stuck_threshold_exceeds_the_job_deadline(self):
        self.assertGreater(poll.STUCK_JOB_SECONDS, poll.JOB_DEADLINE)

    def test_cleanup_cycle_runs_every_task_even_if_one_fails(self):
        ran = []
        with mock.patch.object(poll, "reset_stuck_jobs", mock.Mock(side_effect=RuntimeError("down"))), \
                mock.patch.object(poll, "cleanup_abandoned_uploads", lambda: ran.append("abandoned") or 0), \
                mock.patch.object(poll, "cleanup_expired_jobs", lambda: ran.append("retention") or 0), \
                mock.patch("builtins.print"):
            poll.run_cleanup_cycle()
        self.assertEqual(ran, ["abandoned", "retention"])


if __name__ == "__main__":
    unittest.main()
