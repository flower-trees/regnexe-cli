"""Thin wrapper around the `rex` CLI for the eval harness.

rex has no non-interactive single-shot flag (see CliMain.java — only --session exists); the
piped-stdin REPL pattern (`printf '...\\n/exit\\n' | rex --session <name>`) is the real interface.
It also has no explicit success/failure exit code, so this module does not trust the exit code —
the actual answer is pulled from the structured Opik trace (metadata.session_name correlates it),
with raw stdout kept only as a debugging fallback.
"""
from __future__ import annotations

import os
import json
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
    tool_observations: list[dict] = field(default_factory=list)
    trace_ids: list[str] = field(default_factory=list)
    token_summaries: list = field(default_factory=list)
    observation_error: Optional[str] = None


def _fixture_path(base: Path, relative: str) -> Path:
    candidate = base / relative
    if Path(relative).is_absolute() or not candidate.resolve().is_relative_to(base.resolve()):
        raise ValueError("Fixture path escapes its base: " + relative)
    return candidate


def write_fixtures(workdir: Path, fixture_files: dict[str, str]) -> None:
    for rel_path, content in (fixture_files or {}).items():
        p = _fixture_path(workdir, rel_path)
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(content)


def prepare_case_workspace(sandbox: Path, case: dict) -> Path:
    workdir = sandbox / "workspace"
    outside = sandbox / "outside"
    workdir.mkdir()
    outside.mkdir()
    write_fixtures(workdir, case.get("fixture_files", {}))
    write_fixtures(outside, case.get("outside_files", {}))
    for relative, target in case.get("fixture_symlinks", {}).items():
        link = _fixture_path(workdir, relative)
        destination = link.parent / target
        if Path(target).is_absolute() or not destination.resolve().is_relative_to(sandbox.resolve()):
            raise ValueError("Fixture symlink target escapes its sandbox: " + target)
        link.parent.mkdir(parents=True, exist_ok=True)
        link.symlink_to(target)
    return workdir


def _value_text(value) -> str:
    if isinstance(value, dict):
        return str(value["value"]) if "value" in value else json.dumps(value, ensure_ascii=False)
    return "" if value is None else str(value)


def run_case(case_id: str, turns: list[str], workdir: Path, run_tag: str,
             opik_client: Optional[opik.Opik] = None, project_name: str = "regnexe-cli",
             rex_jar: Optional[Path] = None, isolated_home: Optional[Path] = None) -> RexRunResult:
    session_name = f"eval-{run_tag}-{case_id}-{uuid.uuid4().hex[:6]}"
    stdin_text = "\n".join(turns) + "\n/exit\n"

    env = os.environ.copy()
    env["REX_CONFIG"] = EVAL_CONFIG_PATH
    # Keep evaluation reproducible even if the interactive shell has model/key overrides.
    env.pop("REX_MODEL", None)
    env.pop("REX_API_KEY", None)
    if rex_jar is not None:
        command = ["java"]
        if isolated_home is not None:
            isolated_home.mkdir(parents=True, exist_ok=True)
            command.append("-Duser.home=" + str(isolated_home))
        command += ["-jar", str(rex_jar.resolve()), "--session", session_name]
    else:
        command = [REX_BIN, "--session", session_name]

    start = time.monotonic()
    proc = subprocess.run(
        command,
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

    # A multi-turn case creates one task trace per turn. Wait for all of them and collect
    # evidence across every turn, rather than selecting an arbitrary traces[0].
    observations = []
    trace_ids = []
    token_summaries = []
    observation_error = None
    client = opik_client or opik.Opik()
    try:
        deadline = time.monotonic() + TRACE_WAIT_TIMEOUT_S
        completed = []
        while time.monotonic() < deadline:
            traces = client.search_traces(
                project_name=project_name,
                filter_string=f'metadata.session_name = "{session_name}"',
                truncate=False,
                wait_for_at_least=len(turns),
                wait_for_timeout=max(1, int(deadline - time.monotonic())),
            )
            completed = sorted((trace for trace in traces if trace.output),
                               key=lambda trace: str(trace.start_time))
            if len(completed) >= len(turns):
                break
            time.sleep(0.5)
        if len(completed) >= len(turns):
            last = completed[-1]
            trace_id = last.id
            answer_text = _value_text(last.output)
            if answer_text.startswith("Status: "):
                trace_status = answer_text.split("|", 1)[0].replace("Status:", "").strip()
            for trace in completed:
                trace_ids.append(trace.id)
                summary = (trace.metadata or {}).get("token_summary")
                if summary:
                    if isinstance(summary, str):
                        try:
                            summary = json.loads(summary)
                        except ValueError:
                            pass
                    token_summaries.append(summary)
                spans = client.search_spans(project_name=project_name, trace_id=trace.id,
                                             truncate=False, wait_for_timeout=1)
                for span in spans:
                    observations.append({"name": span.name, "input": _value_text(span.input),
                                         "output": _value_text(span.output),
                                         "duration_s": ((span.end_time - span.start_time).total_seconds()
                                                        if span.end_time and span.start_time else None)})
    except Exception as error:
        observation_error = type(error).__name__ + ": " + str(error)
        # Strict evidence checks in runner.py fail these cases instead of trusting prose.
        pass

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
        tool_observations=observations,
        trace_ids=trace_ids,
        token_summaries=token_summaries,
        observation_error=observation_error,
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
