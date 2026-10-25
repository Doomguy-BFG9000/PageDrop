# Sandbox for the programs that parse untrusted files (Calibre's ebook-convert and
# ebook-meta, poppler's pdftotext).
#
# Why a separate uid matters: the worker holds SUPABASE_SERVICE_KEY (full access to every
# user's files and rows) in its environment. On Linux the environment of a process is
# exposed at /proc/<pid>/environ -- for the worker, which is PID 1 in its container,
# /proc/1/environ -- and the kernel lets only the same uid (or root) read it, ptrace the
# worker or read its memory. Calibre runs complex parsers on hostile input, executes
# Python for some formats, and its HTML-based inputs follow references to local files. If
# a child ran as the worker's uid, a crafted book that made Calibre read an arbitrary path
# (/proc/1/environ, /proc/self/environ of an inherited environment) could leak the key
# into the converted output, which is handed back to the uploader. So every child:
#   - runs as the unprivileged SANDBOX_USER ("convert"), with no supplementary groups;
#   - gets a minimal environment with no Supabase variables;
#   - runs in its own session, so a timeout can kill everything it spawned (os.killpg);
#   - has rlimits on address space, CPU time, file size, process count and core dumps;
#   - can only write to its job's work directory, which no other user can read.
# The worker itself stays root only so it can drop privileges for these children.

import asyncio
import os
import pwd
import resource
import shutil
import signal
import stat
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path

SANDBOX_USER = os.environ.get("SANDBOX_USER", "convert")
# Set to 1 in the Docker image: refuse to run children at all unless we can switch uid.
SANDBOX_REQUIRED = os.environ.get("SANDBOX_REQUIRED", "") == "1"
# Where per-job directories are created (default: the system temp dir).
WORK_ROOT = os.environ.get("WORK_ROOT") or None

MB = 1024 * 1024
# Virtual address space per process. Calibre's PDF output uses QtWebEngine (Chromium),
# which reserves a lot of address space up front, so this is deliberately generous; the
# container's mem_limit is the real memory cap. Lower it only after testing PDF output.
# Off by default: Calibre's PDF output runs Chromium (QtWebEngine), which reserves far more
# virtual address space than it uses, and any RLIMIT_AS makes its renderer fail to load the
# book ("Load of clbr://internal.invalid/... failed"). Real memory is capped by the
# container's cgroup limit (mem_limit in docker-compose). Set to a positive MB value to
# enforce an address-space limit anyway.
MEMORY_LIMIT_BYTES = int(os.environ.get("SANDBOX_MEMORY_LIMIT_MB", "0")) * MB
# Largest single file a child may write. Artifacts are capped at 200 MB by the bucket.
MAX_FILE_BYTES = int(os.environ.get("SANDBOX_MAX_FILE_MB", "256")) * MB
# Processes + threads of the sandbox user (Linux counts threads), across all jobs.
MAX_PROCESSES = int(os.environ.get("SANDBOX_MAX_PROCESSES", "256"))

SAFE_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
KILL_WAIT_SECONDS = 10


class SandboxUnavailable(RuntimeError):
    """The worker cannot run children as the unprivileged sandbox user."""


class UnsafeOutput(RuntimeError):
    """A file the sandbox produced is not a plain regular file (symlink, FIFO, ...)."""


@dataclass(frozen=True)
class Identity:
    uid: int
    gid: int


def sandbox_identity() -> Identity | None:
    """The uid/gid children run as, or None when the worker is itself unprivileged.

    As root, the sandbox user must exist (fail closed). Without root we cannot switch
    uid; that is only acceptable for local development and tests, never when
    SANDBOX_REQUIRED=1 (set in the Docker image).
    """
    if os.geteuid() != 0:
        if SANDBOX_REQUIRED:
            raise SandboxUnavailable("worker must run as root to start Calibre as the sandbox user")
        return None
    try:
        pw = pwd.getpwnam(SANDBOX_USER)
    except KeyError:
        raise SandboxUnavailable(f"sandbox user {SANDBOX_USER!r} does not exist") from None
    if pw.pw_uid == 0 or pw.pw_gid == 0:
        raise SandboxUnavailable(f"sandbox user {SANDBOX_USER!r} must not be root")
    return Identity(pw.pw_uid, pw.pw_gid)


@dataclass(frozen=True)
class Workspace:
    root: Path      # owned by the worker, mode 0711: the sandbox cannot rename or replace `work`
    work: Path      # owned by the sandbox user, mode 0700: the only place children can write
    identity: Identity | None

    @property
    def home(self) -> Path:
        return self.work / "home"

    @property
    def tmp(self) -> Path:
        return self.work / "tmp"

    @property
    def config(self) -> Path:
        return self.work / "calibre-config"


def create_workspace(prefix: str, identity: Identity | None) -> Workspace:
    root = Path(tempfile.mkdtemp(prefix=prefix, dir=WORK_ROOT))
    os.chmod(root, 0o711)
    work = root / "work"
    work.mkdir(mode=0o700)
    ws = Workspace(root=root, work=work, identity=identity)
    for sub in (ws.home, ws.tmp, ws.config):
        sub.mkdir(mode=0o700)
    if identity is not None:
        for path in (work, ws.home, ws.tmp, ws.config):
            os.chown(path, identity.uid, identity.gid, follow_symlinks=False)
    return ws


def remove_workspace(ws: Workspace) -> None:
    # On Linux shutil.rmtree uses fd-relative, O_NOFOLLOW traversal, so symlinks the
    # sandbox planted inside `work` cannot redirect the deletion.
    shutil.rmtree(ws.root, ignore_errors=True)


def place_file(ws: Workspace, name: str, data: bytes) -> Path:
    """Write an input file into the work directory, owned by the sandbox user.

    O_EXCL | O_NOFOLLOW: if anything (a stray sandbox process) already put a file or
    symlink there, fail instead of writing through it as root.
    """
    path = ws.work / name
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    try:
        if ws.identity is not None:
            os.fchown(fd, ws.identity.uid, ws.identity.gid)
        with os.fdopen(fd, "wb", closefd=False) as f:
            f.write(data)
    finally:
        os.close(fd)
    return path


def output_size(path: Path) -> int | None:
    """Size of a regular file the sandbox wrote, or None if it is missing or not a regular file."""
    try:
        st = os.lstat(path)
    except FileNotFoundError:
        return None
    return st.st_size if stat.S_ISREG(st.st_mode) else None


def read_output(path: Path, max_bytes: int) -> bytes:
    """Read a file the sandbox wrote, refusing symlinks and special files.

    The worker reads these as root; without O_NOFOLLOW a converted book replaced by a
    symlink to /proc/1/environ would be uploaded to the user as their result.
    """
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    try:
        st = os.fstat(fd)
        if not stat.S_ISREG(st.st_mode):
            raise UnsafeOutput(f"{path.name} is not a regular file")
        if st.st_size > max_bytes:
            raise UnsafeOutput(f"{path.name} is larger than {max_bytes} bytes")
        with os.fdopen(fd, "rb", closefd=False) as f:
            data = f.read(max_bytes + 1)
    finally:
        os.close(fd)
    if len(data) > max_bytes:
        raise UnsafeOutput(f"{path.name} is larger than {max_bytes} bytes")
    return data


def build_env(ws: Workspace) -> dict[str, str]:
    """The complete environment of a child: nothing is inherited from the worker."""
    return {
        "PATH": SAFE_PATH,
        "HOME": str(ws.home),
        "LANG": "C.UTF-8",
        "LC_ALL": "C.UTF-8",
        "TMPDIR": str(ws.tmp),
        "CALIBRE_TEMP_DIR": str(ws.tmp),
        "CALIBRE_CONFIG_DIRECTORY": str(ws.config),
        "XDG_CACHE_HOME": str(ws.home / ".cache"),
        "XDG_CONFIG_HOME": str(ws.home / ".config"),
        "XDG_RUNTIME_DIR": str(ws.tmp),
        # Calibre's PDF output runs QtWebEngine. Chromium's own sandbox needs user
        # namespaces, which Docker's default seccomp profile blocks; the isolation here is
        # the separate uid, rlimits and the container.
        "QTWEBENGINE_DISABLE_SANDBOX": "1",
    }


def _limit(res: int, value: int, hard: int | None = None) -> None:
    _, cur_hard = resource.getrlimit(res)
    new_hard = value if hard is None else hard
    if cur_hard != resource.RLIM_INFINITY:
        new_hard = min(new_hard, cur_hard)
    resource.setrlimit(res, (min(value, new_hard), new_hard))


def limits_preexec(cpu_seconds: int, limit_processes: bool):
    """preexec_fn for the child: runs after fork (and after the uid switch), before exec."""
    limits = [
        (resource.RLIMIT_CORE, 0, None),
        (resource.RLIMIT_CPU, cpu_seconds, cpu_seconds + 5),  # SIGXCPU, then SIGKILL at the hard limit
        (resource.RLIMIT_FSIZE, MAX_FILE_BYTES, None),
    ]
    if MEMORY_LIMIT_BYTES > 0:
        limits.append((resource.RLIMIT_AS, MEMORY_LIMIT_BYTES, None))
    if limit_processes:
        # Counted per real uid, so only meaningful (and only safe) once we run as the
        # dedicated sandbox user; it stops fork bombs from exhausting the container.
        limits.append((resource.RLIMIT_NPROC, MAX_PROCESSES, None))
    strict = sys.platform.startswith("linux")

    def apply():
        for res, value, hard in limits:
            try:
                _limit(res, value, hard)
            except (ValueError, OSError):
                # macOS rejects some limits (RLIMIT_AS); only tolerated off Linux, for local tests.
                if strict:
                    raise

    return apply


def popen_kwargs(ws: Workspace, cpu_seconds: int) -> dict:
    kwargs = dict(
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        env=build_env(ws),
        cwd=str(ws.work),
        close_fds=True,
        start_new_session=True,
        umask=0o077,
        preexec_fn=limits_preexec(cpu_seconds, limit_processes=ws.identity is not None),
    )
    if ws.identity is not None:
        kwargs.update(user=ws.identity.uid, group=ws.identity.gid, extra_groups=[])
    return kwargs


@dataclass(frozen=True)
class Result:
    returncode: int
    stdout: str
    stderr: str
    timed_out: bool = False


def _kill_group(pgid: int) -> None:
    try:
        os.killpg(pgid, signal.SIGKILL)
    except (ProcessLookupError, PermissionError):
        pass


async def _reap(proc) -> None:
    _kill_group(proc.pid)
    try:
        await asyncio.wait_for(proc.wait(), KILL_WAIT_SECONDS)
    except asyncio.TimeoutError:
        pass


async def run(cmd: list[str], ws: Workspace, timeout: float) -> Result:
    """Run cmd in the sandbox; on timeout (or cancellation) kill its whole process group."""
    proc = await asyncio.create_subprocess_exec(*cmd, **popen_kwargs(ws, int(timeout) + 30))
    try:
        stdout, stderr = await asyncio.wait_for(proc.communicate(), timeout)
    except asyncio.TimeoutError:
        await _reap(proc)
        return Result(-signal.SIGKILL, "", "", timed_out=True)
    except BaseException:  # cancelled by the job deadline, or anything else
        await _reap(proc)
        raise
    # The leader exited; anything it left behind in its session goes too.
    _kill_group(proc.pid)
    return Result(
        proc.returncode if proc.returncode is not None else -1,
        stdout.decode(errors="replace"),
        stderr.decode(errors="replace"),
    )


def kill_stray_processes(identity: Identity | None) -> None:
    """Kill every process of the sandbox user, e.g. one that escaped its session with setsid.

    Only call this when no job is running. kill(-1) from the sandbox user reaches exactly
    the processes that user may signal: its own.
    """
    if identity is None:
        return
    try:
        subprocess.run(
            [sys.executable, "-I", "-S", "-c", "import os, signal; os.kill(-1, signal.SIGKILL)"],
            env={"PATH": SAFE_PATH}, cwd="/", user=identity.uid, group=identity.gid, extra_groups=[],
            stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            start_new_session=True, timeout=KILL_WAIT_SECONDS,
        )
    except (subprocess.TimeoutExpired, OSError):
        pass
