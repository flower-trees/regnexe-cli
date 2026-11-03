"""Thin wrapper around the `rex` CLI for the eval harness.

rex has no non-interactive single-shot flag (see CliMain.java — only --session exists); the
piped-stdin REPL pattern (`printf '...\\n/exit\\n' | rex --session <name>`) is the real interface.
It also has no explicit success/failure exit code, so this module does not trust the exit code —
the actual answer is pulled from the structured Opik trace (metadata.session_name correlates it),
with raw stdout kept only as a debugging fallback.
"""
from __future__ import annotations

import os
import subprocess
import time
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

import opik

REX_BIN = "rex"
RUN_TIMEOUT_S = 240
TRACE_WAIT_TIMEOUT_S = 20
EVAL_CONFIG_PATH = str(Path(__file__).parent / "rex-eval-config.yml")


@dataclass
class RexRunResult:
    session_name: str
    stdout: str
    stderr: str
    returncode: int
    elapsed_s: float
    answer_text: str  # best-available final answer: trace output, else raw stdout
    trace_id: Optional[str] = None
    trace_status: Optional[str] = None  # "FINISHED" / "FAILED" / etc, parsed from trace output
    workdir: Path = field(default=None)


def write_fixtures(workdir: Path, fixture_files: dict[str, str]) -> None:
    for rel_path, content in (fixture_files or {}).items():
        p = workdir / rel_path
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(content)


def run_case(case_id: str, turns: list[str], workdir: Path, run_tag: str,
             opik_client: Optional[opik.Opik] = None, project_name: str = "regnexe-cli") -> RexRunResult:
    session_name = f"eval-{run_tag}-{case_id}-{uuid.uuid4().hex[:6]}"
    stdin_text = "\n".join(turns) + "\n/exit\n"

    env = os.environ.copy()
    env["REX_CONFIG"] = EVAL_CONFIG_PATH

    start = time.monotonic()
    proc = subprocess.run(
        [REX_BIN, "--session", session_name],
        input=stdin_text,
        cwd=str(workdir),
        capture_output=True,
        text=True,
        timeout=RUN_TIMEOUT_S,
        env=env,
    )
    elapsed = time.monotonic() - start

    answer_text = _strip_cli_chrome(proc.stdout)
    trace_id = None
    trace_status = None

    # The root span is exported twice: a zero-length "started" marker the instant the task
    # begins, then a PATCH with the real output once it finishes (see OpikEventListener's
    # class doc). search_traces(wait_for_at_least=1) is satisfied by the marker alone, so
    # polling here has to additionally wait for `output` to actually show up, not just for
    # the trace to exist — rex has already exited by this point (subprocess.run returned),
    # so this is only waiting out the backend's own ingest/PATCH-visibility lag.
    client = opik_client or opik.Opik()
    try:
        deadline = time.monotonic() + TRACE_WAIT_TIMEOUT_S
        t = None
        while time.monotonic() < deadline:
            traces = client.search_traces(
                project_name=project_name,
                filter_string=f'metadata.session_name = "{session_name}"',
                wait_for_at_least=1,
                wait_for_timeout=max(1, int(deadline - time.monotonic())),
            )
            if traces and traces[0].output:
                t = traces[0]
                break
            time.sleep(1)
        if t is not None:
            trace_id = t.id
            out_value = (t.output or {}).get("value", "")
            if out_value:
                answer_text = out_value
                if out_value.startswith("Status: "):
                    trace_status = out_value.split("|", 1)[0].replace("Status:", "").strip()
    except Exception:
        pass  # fall back to raw stdout; tracing is best-effort for the eval harness itself

    return RexRunResult(
        session_name=session_name,
        stdout=proc.stdout,
        stderr=proc.stderr,
        returncode=proc.returncode,
        elapsed_s=elapsed,
        answer_text=answer_text,
        trace_id=trace_id,
        trace_status=trace_status,
        workdir=workdir,
    )


_CHROME_PREFIXES = ("rex ", "cwd ", "›", "⟳", "tokens ")


def _strip_cli_chrome(stdout: str) -> str:
    """Fallback extraction when the Opik trace lookup fails: drop rex's own status/banner
    lines (spinner, token-usage line, banner, `cwd`, the `›` prompt) and keep the rest."""
    kept = []
    for line in stdout.splitlines():
        stripped = line.strip()
        if not stripped:
            continue
        if any(stripped.startswith(p) for p in _CHROME_PREFIXES):
            continue
        kept.append(line)
    return "\n".join(kept).strip()
