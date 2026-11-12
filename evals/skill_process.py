"""Bound a single evaluation process group. Never automatically retry a task."""
from dataclasses import dataclass
import os
from pathlib import Path
import signal
import subprocess
import time


@dataclass
class ProcessResult:
    returncode: int
    stdout: str
    stderr: str
    elapsed_s: float
    failure: str | None


def run_bounded(command: list[str], *, cwd: Path, env: dict[str, str], timeout_s: float,
                stdin: str = '') -> ProcessResult:
    if timeout_s <= 0:
        raise ValueError('A positive wall-clock deadline is required')
    start = time.monotonic()
    proc = subprocess.Popen(command, cwd=cwd, env=env, stdin=subprocess.PIPE,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                            start_new_session=True)
    failure = None
    try:
        stdout, stderr = proc.communicate(stdin, timeout=timeout_s)
    except subprocess.TimeoutExpired:
        failure = 'RUNNER_TIMEOUT'
        # Kill the whole group even if its leader exits before its children release stdout.
        try:
            os.killpg(proc.pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
        try:
            stdout, stderr = proc.communicate(timeout=1)
        except subprocess.TimeoutExpired:
            try:
                os.killpg(proc.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            stdout, stderr = proc.communicate(timeout=2)
        finally:
            try:
                os.killpg(proc.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
    if failure is None and proc.returncode:
        failure = 'PROCESS_ERROR'
    return ProcessResult(proc.returncode, stdout, stderr, time.monotonic()-start, failure)
