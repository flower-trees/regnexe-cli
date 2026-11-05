#!/usr/bin/env python3
"""Exercise the four prepared interactive scenarios using a real PTY and official model."""
import argparse
import json
import os
from pathlib import Path
import signal
import sqlite3
import subprocess
import time

import pexpect
import yaml

from prepare_hardening import load_suite

ROOT = Path(__file__).resolve().parent
PROMPT = "rex> "
CONFIRM = r"(?:Apply|Run|Call)\? \[y/N/pause/always \(this session\)\]"


def process_table():
    rows = subprocess.check_output(["ps", "-axo", "pid=,ppid=,comm="], text=True)
    return {int(parts[0]): (int(parts[1]), parts[2]) for row in rows.splitlines()
            if len(parts := row.strip().split(None, 2)) == 3}


def descendants(pid):
    table = process_table()
    found = {pid}
    while True:
        added = {child for child, (parent, _) in table.items() if parent in found}
        if added.issubset(found):
            return {child: table[child] for child in found if child != pid and child in table}
        found.update(added)


def strings(value):
    if isinstance(value, str):
        yield value
    elif isinstance(value, dict):
        for item in value.values():
            yield from strings(item)
    elif isinstance(value, list):
        for item in value:
            yield from strings(item)


class App:
    def __init__(self, sandbox, jar, session="A", suffix="first"):
        self.sandbox = sandbox
        self.workdir = sandbox / "workspace"
        self.home = sandbox / "home"
        self.workdir.mkdir(parents=True, exist_ok=True)
        self.home.mkdir(parents=True, exist_ok=True)
        config = yaml.safe_load((ROOT / "rex-eval-config.yml").read_text())
        config["tools"]["bash"]["require_confirmation"] = True
        config["tools"]["fs"]["require_confirmation"] = True
        config["agent"]["max_rounds"] = 4
        config_path = sandbox / "interactive.yml"
        config_path.write_text(yaml.safe_dump(config, sort_keys=False))
        env = dict(os.environ, REX_CONFIG=str(config_path), TERM="xterm-256color")
        env.pop("REX_MODEL", None)
        env.pop("REX_API_KEY", None)
        self.log_path = sandbox / (suffix + ".pty.txt")
        self.log = self.log_path.open("w")
        self.child = pexpect.spawn("java", ["-Xmx512m", "-Duser.home=" + str(self.home),
                                  "-jar", str(jar), "--session", session],
                                 cwd=str(self.workdir), env=env, encoding="utf-8",
                                 timeout=150, dimensions=(40, 140))
        self.child.logfile_read = self.log
        self.child.expect_exact(PROMPT)

    def slash(self, command, expected=None):
        self.child.sendline(command)
        if expected:
            self.child.expect_exact(expected)
            text = self.child.before + expected
        else:
            self.child.expect_exact(command)
            text = self.child.before
        self.child.expect_exact(PROMPT)
        text += self.child.before
        return text

    def write(self, filename, content, confirm, choice="yes", supplement=""):
        self.child.sendline(supplement + f" Use only write_file to create {filename} containing exactly "
                            f"{content} with no newline. Do not use bash. Stop after writing.")
        index = self.child.expect([CONFIRM, r"\[task complete\] status=", pexpect.EOF])
        asked = index == 0
        if asked:
            self.child.sendline(choice if confirm else "no")
            self.child.expect_exact("[task complete] status=")
        self.child.expect_exact(PROMPT)
        assert asked == confirm, f"{filename}: expected confirmation={confirm}, observed={asked}"
        target = self.workdir / filename
        assert target.exists() and target.read_text() == content, f"Actual file mismatch: {target}"
        return {"file": filename, "confirmation_observed": asked, "content_verified": True}

    def database(self):
        return sqlite3.connect(self.home / ".rex/rex.db")

    def close(self):
        try:
            if self.child.isalive():
                self.child.sendline("/exit")
                try:
                    self.child.expect(pexpect.EOF, timeout=5)
                except pexpect.TIMEOUT:
                    os.killpg(self.child.pid, signal.SIGTERM)
            self.child.close(force=True)
        finally:
            self.log.close()


def re_escape(text):
    import re
    return re.escape(text)


def session_a_b_a(sandbox, jar):
    app = App(sandbox, jar)
    try:
        steps = [app.write("a1.txt", "a1", True, "always")]
        app.slash("/resume B", "Opened session: B")
        steps.append(app.write("b1.txt", "b1", True))
        app.slash("/resume A", "Opened session: A")
        steps.append(app.write("a2.txt", "a2", False))
        with app.database() as db:
            rows = db.execute("SELECT name, session_id FROM sessions WHERE name IN ('A','B')").fetchall()
        assert len(rows) == 2 and rows[0][1] != rows[1][1], rows
        return {"steps": steps, "session_rows": rows}
    finally:
        app.close()


def agent_rebuild(sandbox, jar):
    app = App(sandbox, jar)
    try:
        source = app.workdir / "eval-rebuild"
        (source / ".claude-plugin").mkdir(parents=True)
        (source / ".claude-plugin/plugin.json").write_text(json.dumps({
            "name": "eval-rebuild", "pluginId": "eval-rebuild", "version": "1.0.0",
            "description": "Disposable empty plugin used only for agent rebuild testing"}))
        install = app.slash("/plugin install " + str(source), "Installed eval-rebuild@default")
        first = app.write("before.txt", "before", True, "always")
        rebuild = app.slash("/plugin disable eval-rebuild@default", "Disabled eval-rebuild@default")
        second = app.write("after.txt", "after", False)
        return {"steps": [first, second], "install_output": install, "rebuild_output": rebuild}
    finally:
        app.close()


def restart(sandbox, jar):
    first = App(sandbox, jar)
    try:
        first_result = first.write("before.txt", "before", True, "always")
        with first.database() as db:
            session_id = db.execute("SELECT session_id FROM sessions WHERE name='A'").fetchone()[0]
    finally:
        first.close()
    second = App(sandbox, jar, suffix="restarted")
    try:
        second_result = second.write("after.txt", "after", True)
        with second.database() as db:
            restored = db.execute("SELECT session_id FROM sessions WHERE name='A'").fetchone()[0]
        assert session_id == restored
        return {"steps": [first_result, second_result], "same_session_uuid": session_id}
    finally:
        second.close()


def interrupt_task(sandbox, jar):
    app = App(sandbox, jar)
    tracked = []
    try:
        app.child.sendline("Use bash to run exactly once: printf 'started\\n'; sleep 45. "
                           "Do not change or retry the command. Report the real result.")
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            current = descendants(app.child.pid)
            tracked = [pid for pid, (_, command) in current.items() if command.endswith("sleep")]
            if tracked:
                break
            try:
                app.child.expect(CONFIRM, timeout=0.25)
                app.child.sendline("yes")
            except pexpect.TIMEOUT:
                pass
        assert tracked, "No actual sleep child was observed"
        interrupted = time.monotonic()
        app.child.send("\x1b")
        app.child.expect_exact("[task complete] status=PAUSED", timeout=10)
        app.child.expect_exact(PROMPT, timeout=5)
        paused_output = app.child.before
        with app.database() as db:
            row = db.execute("SELECT status, data FROM task_execution_states ORDER BY updated_at DESC LIMIT 1").fetchone()
        assert row and row[0] == "PAUSED", f"Expected PAUSED; got {row[0] if row else None}"
        ledger = json.loads(row[1])
        partial = [text for text in strings(ledger)
                   if "started" in text and ("command cancelled" in text or "Exit: 130" in text)]
        assert partial, "No retained tool output containing started was found in task ledger"
        time.sleep(0.2)
        alive = process_table()
        assert not any(pid in alive for pid in tracked), f"Sleep processes remain: {tracked}"
        pause_seconds = time.monotonic() - interrupted
        app.slash("/resume A", "Type your next message to continue it.")
        resumed = app.write("resumed.txt", "resumed-ok", True,
                            supplement="Do not repeat the previous sleep. Finish by writing the file.")
        exit_started = time.monotonic()
        app.child.sendcontrol("c")
        app.child.expect(pexpect.EOF, timeout=7)
        exit_seconds = time.monotonic() - exit_started
        assert exit_seconds < 6, f"Ctrl+C exit took {exit_seconds}s"
        return {"interrupt_key": "Escape", "ctrl_c_exit_seconds": exit_seconds,
                "tracked_sleep_pids": tracked, "pause_seconds": pause_seconds,
                "ledger_status_before_resume": row[0], "partial_output": partial[0],
                "resume": resumed, "paused_terminal_output": paused_output}
    finally:
        for pid in tracked:
            if pid in process_table():
                try:
                    os.kill(pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass
        app.close()


SCENARIOS = {"interactive-session-a-b-a": session_a_b_a,
             "interactive-agent-rebuild": agent_rebuild,
             "interactive-restart": restart, "interactive-ctrl-c": interrupt_task}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--jar", type=Path, default=ROOT.parent / "target/regnexe-cli.jar")
    args = parser.parse_args()
    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=True)
    results = {}
    for case in load_suite("interactive"):
        case_id = case["id"]
        sandbox = output / (case_id + "-" + str(time.time_ns()))
        sandbox.mkdir()
        started = time.monotonic()
        try:
            evidence = SCENARIOS[case_id](sandbox, args.jar.resolve())
            row = {"case_score": 1.0, "status": "passed", "case_reason": "Real PTY assertions passed",
                   "evidence": evidence}
        except Exception as error:
            row = {"case_score": 0.0, "status": "failed",
                   "case_reason": type(error).__name__ + ": " + str(error)}
        row.update(case_id=case_id, elapsed_s=round(time.monotonic() - started, 3),
                   sandbox=str(sandbox), execution_mode="actual_pty_official_deepseek_flash")
        results[case_id] = row
        (output / "interactive-executions.json").write_text(json.dumps(results, ensure_ascii=False, indent=2))
        print(f"[{case_id}] {row['status']}: {row['case_reason'][:500]}", flush=True)


if __name__ == "__main__":
    main()
