# Tests for the Calibre sandbox (sandbox.py) and for process_job driving fake Calibre
# binaries through it. They run unprivileged, so the uid switch itself is only checked
# through the arguments handed to subprocess; everything else (scrubbed environment,
# rlimits, own session, process-group kill, symlink-safe reads) runs for real.
#   python -m pytest server/worker/tests
import asyncio
import os
import stat
import sys
import time
from pathlib import Path
from unittest import mock

import pytest

os.environ.setdefault("SUPABASE_URL", "http://supabase.invalid")
os.environ.setdefault("SUPABASE_SERVICE_KEY", "test-key")
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import poll  # noqa: E402
import sandbox  # noqa: E402

USER = "aaaaaaaa-0000-0000-0000-000000000001"
JOB = "11111111-0000-0000-0000-000000000001"


@pytest.fixture
def ws(tmp_path, monkeypatch):
    monkeypatch.setattr(sandbox, "WORK_ROOT", str(tmp_path))
    workspace = sandbox.create_workspace("t_", None)
    yield workspace
    sandbox.remove_workspace(workspace)


def alive(pid: int) -> bool:
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    # A killed child of an exited shell may linger as a zombie until init reaps it.
    try:
        return os.waitpid(pid, os.WNOHANG) == (0, 0)
    except ChildProcessError:
        return Path(f"/proc/{pid}").exists() and "Z" not in Path(f"/proc/{pid}/stat").read_text().split()[2]


# ── Identity ────────────────────────────────────────────────────────────────

def test_unprivileged_worker_runs_children_as_itself_only_when_allowed(monkeypatch):
    monkeypatch.setattr(sandbox.os, "geteuid", lambda: 1000)
    monkeypatch.setattr(sandbox, "SANDBOX_REQUIRED", False)
    assert sandbox.sandbox_identity() is None
    monkeypatch.setattr(sandbox, "SANDBOX_REQUIRED", True)
    with pytest.raises(sandbox.SandboxUnavailable):
        sandbox.sandbox_identity()


def test_root_worker_requires_an_unprivileged_sandbox_user(monkeypatch):
    monkeypatch.setattr(sandbox.os, "geteuid", lambda: 0)
    monkeypatch.setattr(sandbox.pwd, "getpwnam", mock.Mock(side_effect=KeyError("convert")))
    with pytest.raises(sandbox.SandboxUnavailable):
        sandbox.sandbox_identity()
    monkeypatch.setattr(sandbox.pwd, "getpwnam", lambda name: mock.Mock(pw_uid=0, pw_gid=0))
    with pytest.raises(sandbox.SandboxUnavailable):
        sandbox.sandbox_identity()
    monkeypatch.setattr(sandbox.pwd, "getpwnam", lambda name: mock.Mock(pw_uid=999, pw_gid=998))
    assert sandbox.sandbox_identity() == sandbox.Identity(999, 998)


def test_children_drop_to_the_sandbox_user_in_their_own_session(ws):
    identity = sandbox.Identity(999, 998)
    kwargs = sandbox.popen_kwargs(sandbox.Workspace(ws.root, ws.work, identity), 60)
    assert kwargs["user"] == 999 and kwargs["group"] == 998 and kwargs["extra_groups"] == []
    assert kwargs["start_new_session"] is True
    assert kwargs["umask"] == 0o077
    assert kwargs["cwd"] == str(ws.work)
    assert callable(kwargs["preexec_fn"])
    # Unprivileged (dev/test) mode cannot switch user and must not pretend to.
    assert "user" not in sandbox.popen_kwargs(ws, 60)


# ── Environment and limits ──────────────────────────────────────────────────

def test_environment_is_minimal_and_has_no_supabase_secrets(ws, monkeypatch):
    monkeypatch.setenv("SUPABASE_SERVICE_KEY", "super-secret")
    env = sandbox.build_env(ws)
    assert not [k for k in env if "SUPABASE" in k]
    assert "super-secret" not in "".join(env.values())
    assert set(env) >= {"PATH", "HOME", "LANG", "TMPDIR", "CALIBRE_CONFIG_DIRECTORY"}
    for key in ("HOME", "TMPDIR", "CALIBRE_CONFIG_DIRECTORY"):
        assert Path(env[key]).is_relative_to(ws.work), key


def test_child_sees_only_the_minimal_environment_and_rlimits(ws, monkeypatch):
    monkeypatch.setenv("SUPABASE_SERVICE_KEY", "super-secret")
    result = asyncio.run(sandbox.run(
        ["/bin/sh", "-c", "env; echo core=$(ulimit -c); echo fsize=$(ulimit -f); echo cpu=$(ulimit -t); echo umask=$(umask)"],
        ws, 20))
    assert result.returncode == 0, result.stderr
    out = result.stdout
    assert "super-secret" not in out and "SUPABASE" not in out
    assert f"HOME={ws.home}" in out
    assert "core=0" in out
    assert "cpu=50" in out  # timeout + 30
    assert "umask=0077" in out
    probe = asyncio.run(sandbox.run([sys.executable, "-I", "-c", (
        "import resource as r; print(r.getrlimit(r.RLIMIT_FSIZE)[0], r.getrlimit(r.RLIMIT_CORE)[1])")], ws, 20))
    assert probe.stdout.split() == [str(sandbox.MAX_FILE_BYTES), "0"], probe


def test_file_size_limit_stops_a_runaway_writer(ws, monkeypatch):
    monkeypatch.setattr(sandbox, "MAX_FILE_BYTES", 1024 * 1024)
    result = asyncio.run(sandbox.run(
        ["/bin/sh", "-c", "head -c 2000000 /dev/zero > big"], ws, 20))
    assert result.returncode != 0
    assert (ws.work / "big").stat().st_size <= 1024 * 1024


# ── Timeouts ────────────────────────────────────────────────────────────────

def test_timeout_kills_the_whole_process_group(ws):
    script = "sleep 60 & echo $! > bg.pid; sleep 60"
    started = time.monotonic()
    result = asyncio.run(sandbox.run(["/bin/sh", "-c", script], ws, 1))
    assert result.timed_out
    assert time.monotonic() - started < 15
    bg = int((ws.work / "bg.pid").read_text())
    for _ in range(50):
        if not alive(bg):
            break
        time.sleep(0.1)
    assert not alive(bg), "background child survived the timeout"


def test_cancellation_kills_the_process_group(ws):
    async def scenario():
        task = asyncio.create_task(sandbox.run(["/bin/sh", "-c", "sleep 60 & echo $! > bg.pid; sleep 60"], ws, 60))
        for _ in range(50):
            await asyncio.sleep(0.1)
            if (ws.work / "bg.pid").exists() and (ws.work / "bg.pid").read_text().strip():
                break
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task

    asyncio.run(scenario())
    bg = int((ws.work / "bg.pid").read_text())
    for _ in range(50):
        if not alive(bg):
            break
        time.sleep(0.1)
    assert not alive(bg)


# ── Workspace and file handling ─────────────────────────────────────────────

def test_workspace_is_private(ws):
    assert stat.S_IMODE(os.stat(ws.root).st_mode) == 0o711
    for path in (ws.work, ws.home, ws.tmp, ws.config):
        assert stat.S_IMODE(os.stat(path).st_mode) == 0o700, path


def test_place_file_never_writes_through_a_planted_symlink(ws, tmp_path):
    victim = tmp_path / "victim"
    victim.write_text("original")
    os.symlink(victim, ws.work / "source.epub")
    with pytest.raises(OSError):
        sandbox.place_file(ws, "source.epub", b"attacker data")
    assert victim.read_text() == "original"
    path = sandbox.place_file(ws, "other.epub", b"data")
    assert path.read_bytes() == b"data" and stat.S_IMODE(path.stat().st_mode) == 0o600


def test_read_output_refuses_symlinks_special_files_and_oversize(ws, tmp_path):
    secret = tmp_path / "environ"
    secret.write_text("SUPABASE_SERVICE_KEY=leak")
    os.symlink(secret, ws.work / "output.mobi")
    with pytest.raises(OSError):
        sandbox.read_output(ws.work / "output.mobi", 1000)
    assert sandbox.output_size(ws.work / "output.mobi") is None

    os.mkfifo(ws.work / "fifo.mobi")
    with pytest.raises(sandbox.UnsafeOutput):
        sandbox.read_output(ws.work / "fifo.mobi", 1000)

    (ws.work / "big.mobi").write_bytes(b"x" * 2000)
    with pytest.raises(sandbox.UnsafeOutput):
        sandbox.read_output(ws.work / "big.mobi", 1000)
    (ws.work / "ok.mobi").write_bytes(b"book")
    assert sandbox.read_output(ws.work / "ok.mobi", 1000) == b"book"


# ── process_job with fake Calibre binaries ──────────────────────────────────

def convert_job(**overrides):
    job = {
        "id": JOB, "user_id": USER, "job_type": "convert", "target_format": "mobi",
        "source_storage_path": f"{USER}/{JOB}/book.epub", "input_cover_storage_path": None,
        "file_size_bytes": 10, "title": "T", "author": "A",
    }
    job.update(overrides)
    return job


@pytest.fixture
def fake_calibre(tmp_path, monkeypatch):
    """Install fake ebook-convert / ebook-meta scripts on the sandbox PATH; returns uploads."""
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    work_root = tmp_path / "jobs"
    work_root.mkdir()
    monkeypatch.setattr(sandbox, "SAFE_PATH", f"{bin_dir}:/usr/bin:/bin")
    monkeypatch.setattr(sandbox, "WORK_ROOT", str(work_root))
    monkeypatch.setattr(poll, "supabase_download", lambda bucket, path: b"0123456789")
    uploads = {}
    monkeypatch.setattr(poll, "supabase_upload",
                        lambda bucket, path, data, content_type="": uploads.setdefault(path, data) and path)

    def install(convert: str, meta: str = "exit 0"):
        for name, body in (("ebook-convert", convert), ("ebook-meta", meta)):
            script = bin_dir / name
            script.write_text("#!/bin/sh\n" + body + "\n")
            script.chmod(0o755)
        return uploads

    install.work_root = work_root
    return install


def test_successful_job_runs_calibre_without_secrets(fake_calibre, monkeypatch):
    monkeypatch.setenv("SUPABASE_SERVICE_KEY", "super-secret")
    uploads = fake_calibre('env > "$2"; echo "core=$(ulimit -c)" >> "$2"')
    result = asyncio.run(poll.process_job(convert_job()))
    assert result["status"] == "complete", result
    book = uploads[f"{USER}/{JOB}/output.mobi"].decode()
    assert "super-secret" not in book and "SUPABASE" not in book and "test-key" not in book
    assert "core=0" in book
    assert list(fake_calibre.work_root.iterdir()) == [], "workspace not removed"


def test_calibre_failure_shows_only_a_generic_message(fake_calibre, capsys):
    fake_calibre('echo "Traceback: /secret/internal/path.py line 3" >&2; exit 1')
    result = asyncio.run(poll.process_job(convert_job()))
    assert result["status"] == "failed"
    assert result["error_message"] == "Conversion failed"
    assert "secret" not in str(result["logs"])
    assert "/secret/internal/path.py" in capsys.readouterr().out  # kept for the operator


def test_symlinked_output_is_never_uploaded(fake_calibre, tmp_path):
    secret = tmp_path / "environ"
    secret.write_text("SUPABASE_SERVICE_KEY=leak")
    uploads = fake_calibre(f'ln -s "{secret}" "$2"')
    result = asyncio.run(poll.process_job(convert_job()))
    assert result["status"] == "failed" and result["error_message"] == "Conversion failed"
    assert not any(b"leak" in data for data in uploads.values())


def test_conversion_timeout_kills_calibre_and_reports_a_timeout(fake_calibre, monkeypatch):
    monkeypatch.setattr(poll, "JOB_TIMEOUT", 1)
    fake_calibre('sleep 60 & echo $! > "$(dirname "$2")/bg.pid"; sleep 60')
    pid_file = []
    real_remove = sandbox.remove_workspace

    def remember(ws):
        pid_file.append((ws.work / "bg.pid").read_text())
        real_remove(ws)

    monkeypatch.setattr(sandbox, "remove_workspace", remember)
    started = time.monotonic()
    result = asyncio.run(poll.process_job(convert_job()))
    assert time.monotonic() - started < 20
    assert result["error_message"] == "Conversion timed out"
    bg = int(pid_file[0])
    for _ in range(50):
        if not alive(bg):
            break
        time.sleep(0.1)
    assert not alive(bg)


def test_job_deadline_fails_the_job(monkeypatch):
    async def slow(job):
        await asyncio.sleep(60)

    monkeypatch.setattr(poll, "process_job", slow)
    result = asyncio.run(poll.run_job_with_deadline(convert_job(), deadline=0.2))
    assert result["status"] == "failed" and result["error_message"] == "Conversion timed out"


def test_rejections_use_generic_messages():
    bad_type = poll.process_job(convert_job(source_storage_path=f"{USER}/{JOB}/x.recipe"))
    assert asyncio.run(bad_type)["error_message"] == "Unsupported file type"
    foreign = poll.process_job(convert_job(source_storage_path=f"aaaaaaaa-0000-0000-0000-000000000002/{JOB}/x.epub"))
    assert asyncio.run(foreign)["error_message"] == "Invalid job"


def test_unsafe_article_url_details_stay_in_the_server_log(monkeypatch, capsys):
    async def refuse(url, timeout):
        raise poll.UnsafeURL("resolves to private address 10.0.0.7")

    monkeypatch.setattr(poll, "fetch_html", refuse)
    job = convert_job(job_type="article", source_storage_path=None, source_url="https://internal.example/")
    result = asyncio.run(poll.process_job(job))
    assert result["error_message"] == "Could not fetch the article"
    assert "10.0.0.7" not in str(result)
    assert "10.0.0.7" in capsys.readouterr().out
