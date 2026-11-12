"""Deterministic real-PTY controls tests against a local, deliberately stalled model API.
No official model key, Opik server, or paid requests are used.
Run: evals/.venv/bin/python -m unittest discover -s evals -p test_interactive_controls.py -v
"""
import json
import os
from pathlib import Path
import queue
import sqlite3
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pexpect
import yaml

JAR = Path(__file__).resolve().parent.parent / "target/regnexe-cli.jar"


@unittest.skipUnless(JAR.exists(), "Build the CLI jar first")
class InteractiveControlsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="rex-live-input-")
        self.root = Path(self.temp.name)
        self.requests = queue.Queue()
        self.release = threading.Event()
        self.mode = "stall"
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                owner.requests.put(body)
                if owner.mode == "stall":
                    owner.release.wait(30)
                    message = {"role": "assistant", "content": "late response"}
                elif "task planner" in str(body.get("messages", [])).lower():
                    message = {"role": "assistant", "content": json.dumps({
                        "narrative": "Use bash to execute the requested command once.",
                        "selectedCapabilityIds": ["target" if owner.mode == "budget" else "bash"],
                        "capabilityInputDescriptions": {"bash": "Run the command once"},
                        "finalAnswerRequirements": ["Report actual output"]})}
                elif body.get("tools") and not any(m.get("role") == "tool" for m in body["messages"]):
                    if owner.mode == "steer" and "corrected.txt" not in json.dumps(body):
                        owner.release.wait(30)
                    command = ("sleep 20 & child=$!; printf '%s' \"$child\" > child.pid; echo started; wait"
                               if owner.mode == "shell" else
                               "printf corrected > corrected.txt" if owner.mode == "steer" and "corrected.txt" in json.dumps(body)
                               else "printf approved > approved.txt")
                    message = {"role": "assistant", "content": "", "tool_calls": [{
                        "id": "local-call", "type": "function",
                        "function": {"name": "bash", "arguments": json.dumps({"command": command})}}]}
                elif "reflect" in str(body.get("messages", [])).lower() and not body.get("tools"):
                    message = {"role": "assistant", "content": json.dumps({"action": "FINISH", "reason": "Tool evidence verified"})}
                else:
                    message = {"role": "assistant", "content": "Command completed successfully."}
                try:
                    payload = json.dumps({"choices": [{"message": message, "finish_reason": "tool_calls" if message.get("tool_calls") else "stop"}]}).encode()
                    self.send_response(200); self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", str(len(payload))); self.end_headers(); self.wfile.write(payload)
                except (BrokenPipeError, ConnectionResetError):
                    pass
            def log_message(self, *args):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.server.daemon_threads = True
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        config = {
            "model": {"vendor": "custom", "name": "local-test", "api_key": "local-test-only",
                      "chat_url": f"http://127.0.0.1:{self.server.server_port}/v1/chat/completions"},
            "agent": {"max_rounds": 2},
            "ui": {"fullscreen": False, "theme": "minimal", "color": "never", "icons": False},
            "opik": {"enabled": False},
            "tools": {"bash": {"require_confirmation": False}, "fs": {"require_confirmation": False}},
        }
        config_path = self.root / "config.yml"
        config_path.write_text(yaml.safe_dump(config))
        env = dict(os.environ, REX_CONFIG=str(config_path), TERM="xterm-256color")
        env.pop("REX_MODEL", None); env.pop("REX_API_KEY", None)
        self.log = (self.root / "terminal.txt").open("w")
        self.child = pexpect.spawn("java", ["-Duser.home=" + str(self.root), "-jar", str(JAR), "--session", "live"],
                                   cwd=str(self.root), env=env, encoding="utf-8", timeout=25, dimensions=(40, 140))
        self.child.logfile_read = self.log
        try:
            self.child.expect_exact("Enter: send / add instruction")
            self.child.expect(r"rex>|\x1b\[\d+;6H")
        except Exception as error:
            output = self.child.before
            self.tearDown()
            raise AssertionError("CLI failed to start: " + output) from error

    def tearDown(self):
        self.release.set()
        if hasattr(self, "child"):
            if self.child.isalive():
                self.child.sendcontrol("c")
                try:
                    self.child.expect(pexpect.EOF, timeout=7)
                except (pexpect.EOF, pexpect.TIMEOUT):
                    pass
            self.child.close(force=True)
        marker = self.root / "child.pid"
        if marker.exists():
            try:
                import signal
                os.kill(int(marker.read_text()), signal.SIGKILL)
            except ProcessLookupError:
                pass
        self.server.shutdown(); self.server.server_close()
        self.log.close(); self.temp.cleanup()

    def next_request(self, timeout):
        # A real terminal continuously consumes output, including per-key dock redraws.
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                return self.requests.get_nowait()
            except queue.Empty:
                try:
                    self.child.read_nonblocking(8192, timeout=0.05)
                except pexpect.TIMEOUT:
                    pass
        raise queue.Empty()

    def test_bottom_input_footer_survives_output_and_resize(self):
        # Repeated help fills the scroll region without calling a model.
        for _ in range(4):
            self.child.sendline("/help")
            self.child.expect_exact("Commands:", timeout=5)
            self.child.expect(r"\x1b\[38;6H", timeout=5)
        self.log.flush()
        output = (self.root / "terminal.txt").read_text()
        self.assertIn("local-test", output)
        self.assertIn("live", output)
        self.assertNotIn("Directory:", output)
        self.assertIn(str(self.root.resolve()), output)
        self.assertTrue("─" * 100 in output or "\x1b(0" + "q" * 100 in output)
        # The entire four-row dock is outside the output scroll region.
        self.assertIn("\x1b[38;1H", output)
        self.assertIn("\x1b[1;35r", output)
        # Check the resulting screen, rather than only the emitted cursor commands.
        import pyte
        screen = pyte.Screen(140, 40)
        terminal_stream = pyte.Stream(screen)
        with (self.root / "terminal.txt").open(newline="") as stream:
            terminal_stream.feed(stream.read())
        rows = screen.display
        self.assertIn("rex>", rows[-3])
        self.assertIn("local-test · live · " + str(self.root.resolve()), rows[-1], "\n".join(rows[-8:]))
        self.assertTrue(set(rows[-4].strip()) <= {"─", "q"})
        self.assertTrue(set(rows[-2].strip()) <= {"─", "q"})
        self.child.setwinsize(30, 100)
        self.child.sendline("/help")
        # A resize can repaint the old prompt before the command is dispatched.
        self.child.expect_exact("Commands:", timeout=5)
        self.child.expect(r"rex>|\x1b\[\d+;6H", timeout=5)
        self.log.flush()
        output = (self.root / "terminal.txt").read_text()
        self.assertIn("\x1b[28;6H", output)
        self.assertIn("\x1b[1;25r", output)
        self.assertTrue(self.requests.empty())

    def test_fullscreen_history_scroll_keeps_draft_status_and_colors(self):
        import pyte
        self.child.sendline("/exit"); self.child.expect(pexpect.EOF, timeout=7); self.child.close()
        path = self.root / "config.yml"
        config = yaml.safe_load(path.read_text())
        config["ui"].update(fullscreen=True, color="always")
        path.write_text(yaml.safe_dump(config))
        screen = pyte.Screen(140, 40)
        stream = pyte.Stream(screen)
        log = self.log
        received = []
        class Capture:
            def write(self, text):
                received.append(text)
                log.write(text); stream.feed(text)
            def flush(self):
                log.flush()
        env = dict(os.environ, REX_CONFIG=str(path), TERM="xterm-256color")
        env.pop("REX_MODEL", None); env.pop("REX_API_KEY", None)
        self.child = pexpect.spawn("java", ["-Duser.home=" + str(self.root), "-jar", str(JAR), "--session", "live"],
                                   cwd=str(self.root), env=env, encoding="utf-8", timeout=15, dimensions=(40, 140))
        self.child.logfile_read = Capture()
        def drain(seconds=0.35):
            deadline = time.monotonic() + seconds
            while time.monotonic() < deadline:
                try:
                    self.child.read_nonblocking(16384, timeout=0.05)
                except pexpect.TIMEOUT:
                    pass
        self.child.expect_exact("Enter: send / add instruction", timeout=15)
        drain()
        # Application selection and scrolling now share the default mouse mode.
        self.assertIn("\x1b[?1002h", "".join(received))
        self.assertIn("drag to select, release to copy", screen.display[-5])
        for _ in range(4):
            self.child.sendline("/help"); drain()
        bottom = screen.display[-4:]
        latest = screen.display[:35]
        self.child.send("\x1b[5~"); drain()
        self.assertNotEqual(latest, screen.display[:35])
        self.assertEqual(bottom, screen.display[-4:])
        # Click the application scrollbar at the top, then drag its thumb to the bottom.
        self.child.send("\x1b[M" + chr(32) + chr(172) + chr(33)); drain()
        self.assertIn("rex v", "\n".join(screen.display[:35]))
        self.child.send("\x1b[M" + chr(64) + chr(172) + chr(67)); drain()
        self.assertEqual(latest, screen.display[:35])
        self.child.send("\x1b[M" + chr(96) + chr(37) + chr(37)); drain()
        self.assertNotEqual(latest, screen.display[:35])
        self.assertEqual(bottom, screen.display[-4:])
        self.child.send("\x1b[6~"); drain()
        self.child.sendline("Analyze this project")
        self.next_request(timeout=10); drain()
        self.assertIn("Running", screen.display[-5])
        self.assertEqual("brown", screen.buffer[35][0].fg)  # pyte names ANSI yellow "brown"
        self.child.send("draft input"); drain()
        self.assertIn("draft input", screen.display[-3])
        self.child.send("\x1b[5~"); drain()
        self.assertIn("draft input", screen.display[-3])
        self.assertIn("Running", screen.display[-5])
        self.assertEqual("cyan", screen.buffer[37][0].fg)
        self.child.send("\x1b")
        deadline = time.monotonic() + 5
        while "Ready" not in screen.display[-5] and time.monotonic() < deadline:
            drain(0.15)
        self.assertIn("Ready", screen.display[-5])
        with sqlite3.connect(self.root / ".rex/rex.db") as db:
            self.assertEqual("PAUSED", db.execute("SELECT status FROM task_execution_states").fetchone()[0])
        self.child.send("\x1b")  # clear the retained draft before entering a UI command
        drain()
        self.child.sendline("/mouse off"); drain()
        self.assertIn("drag to select/copy", screen.display[-5])
        start = len(received)
        self.child.sendline("/help"); drain()
        subsequent = "".join(received[start:])
        self.assertNotRegex(subsequent, r"\x1b\[\?(?:1000|1002|1003|1006)h")
        before = screen.display[:35]
        self.child.send("\x1b[5~"); drain()
        self.assertNotEqual(before, screen.display[:35])

    def test_application_selection_copies_utf8_and_preserves_draft(self):
        import base64
        import pyte
        import re
        import uuid
        self.child.sendline("/exit"); self.child.expect(pexpect.EOF, timeout=7); self.child.close()
        path = self.root / "config.yml"
        config = yaml.safe_load(path.read_text())
        config["ui"].update(fullscreen=True, color="always")
        path.write_text(yaml.safe_dump(config))
        turn_id = str(uuid.uuid4())
        first = "SELECT: 中文e\u0301😀 copied"
        second = "second line without decoration"
        turn = {"id": turn_id, "type": "NORMAL", "created_at": 1, "messages": [
            {"role": "human", "content": "Selection fixture"},
            {"role": "ai", "content": first + "\n" + second + "\n" + "wrap-" * 40}]}
        with sqlite3.connect(self.root / ".rex/rex.db") as db:
            db.execute("INSERT INTO conversation_turns VALUES (?,0,0,'live',1,?)", (turn_id, json.dumps(turn)))
        fake_bin = self.root / "clipboard-bin"; fake_bin.mkdir()
        destination = self.root / "copied.txt"
        fail_marker = self.root / "clipboard-fail"
        script = '#!/bin/sh\nif [ -f "$REX_CLIPBOARD_FAIL" ]; then exit 1; fi\n/bin/cat > "$REX_TEST_CLIPBOARD"\n'
        # All platform backends are synthetic; this test never modifies the OS clipboard.
        for name in ("pbcopy", "wl-copy", "xclip", "xsel", "powershell.exe"):
            command = fake_bin / name; command.write_text(script); command.chmod(0o755)
        screen = pyte.Screen(140, 40); stream = pyte.Stream(screen)
        received = []; log = self.log
        class Capture:
            def write(self, text):
                received.append(text); log.write(text); stream.feed(text)
            def flush(self):
                log.flush()
        env = dict(os.environ, REX_CONFIG=str(path), TERM="xterm-256color",
                   PATH=str(fake_bin) + os.pathsep + os.environ.get("PATH", ""),
                   REX_TEST_CLIPBOARD=str(destination), REX_CLIPBOARD_FAIL=str(fail_marker))
        for name in ("REX_MODEL", "REX_API_KEY", "SSH_CONNECTION", "SSH_TTY"):
            env.pop(name, None)
        self.child = pexpect.spawn("java", ["-Duser.home=" + str(self.root), "-jar", str(JAR), "--session", "live"],
                                   cwd=str(self.root), env=env, encoding="utf-8", timeout=15, dimensions=(40, 140))
        self.child.logfile_read = Capture()
        def drain(seconds=0.25):
            deadline = time.monotonic() + seconds
            while time.monotonic() < deadline:
                try:
                    self.child.read_nonblocking(16384, timeout=0.05)
                except pexpect.TIMEOUT:
                    pass
        def mouse(code, x, y, release=False):
            self.child.send(f"\x1b[<{code};{x + 1};{y + 1}" + ("m" if release else "M"))
            drain()
        def copied(expected):
            deadline = time.monotonic() + 4
            while time.monotonic() < deadline:
                drain(0.1)
                if destination.exists() and destination.read_text() == expected:
                    return
            self.fail("Synthetic clipboard did not receive the exact selected text")
        self.child.expect_exact("Enter: send / add instruction", timeout=15); drain()
        self.child.send("retained draft"); drain()
        y = next(i for i, row in enumerate(screen.display[:35]) if "SELECT:" in row)
        mouse(0, 8, y); mouse(0, 8, y, release=True)
        self.assertFalse(destination.exists(), "A click without dragging must not overwrite the clipboard")
        mouse(0, 8, y); mouse(32, 14, y)
        self.assertTrue(screen.buffer[y][8].reverse, "Selected Chinese text must be visibly highlighted")
        self.assertIn("retained draft", screen.display[-3])
        mouse(0, 14, y, release=True)
        copied("中文e\u0301😀")
        self.assertNotIn("\x1b", destination.read_text())
        self.assertIn("Copied", screen.display[-5])
        mouse(0, 0, y); mouse(32, 4, y + 1); mouse(0, 4, y + 1, release=True)
        expected = first + "\nsecon"
        copied(expected)
        screen.resize(lines=30, columns=90)
        self.child.setwinsize(30, 90); drain(0.5)
        self.assertIn("retained draft", screen.display[-3])
        self.assertTrue(any(cell.reverse for row in screen.buffer.values() for cell in row.values()))
        # Retry a retained selection with a failed native backend: OSC 52 remains honest
        # about terminal permissions and contains exactly the original selected text.
        fail_marker.touch()
        self.child.send("\x1bc"); drain(0.6)
        matches = re.findall(r"\x1b\]52;c;([A-Za-z0-9+/=]*)\x07", "".join(received))
        self.assertTrue(matches)
        self.assertEqual(expected, base64.b64decode(matches[-1]).decode("utf-8"))
        self.assertIn("Copy sent via OSC 52", screen.display[-5])
        self.assertIn("retained draft", screen.display[-3])
        self.assertTrue(self.requests.empty(), "Selection and copying must never call the model")
        self.child.sendcontrol("c"); self.child.expect(pexpect.EOF, timeout=7)

    def test_live_instruction_and_escape_during_stalled_model(self):
        self.child.sendline("Analyze this project")
        self.next_request(timeout=10)
        started = time.monotonic()
        self.child.sendline("Focus on task recovery only")
        self.child.expect_exact("Instruction added to the current task.", timeout=3)
        updated = self.next_request(timeout=5)
        self.assertIn("Focus on task recovery only", json.dumps(updated))
        self.assertLess(time.monotonic() - started, 5)
        self.child.send("\x1b")
        self.child.expect_exact("[task complete] status=PAUSED", timeout=5)
        self.assertTrue(self.child.isalive())
        with sqlite3.connect(self.root / ".rex/rex.db") as db:
            rows = db.execute("SELECT status,data FROM task_execution_states").fetchall()
        self.assertEqual(1, len(rows))
        self.assertEqual("PAUSED", rows[0][0])
        self.assertIn("Focus on task recovery only", rows[0][1])
        # With no approval pending, Left remains a normal cursor-editing key.
        self.child.send("/resue")
        self.child.send("\x1b[D")
        self.child.sendline("m")
        self.child.expect_exact("live", timeout=3)

    def test_escape_stops_shell_and_keeps_partial_output(self):
        self.mode = "shell"
        self.child.sendline("Use bash to run a long sleep command")
        deadline = time.monotonic() + 12
        marker = self.root / "child.pid"
        while not marker.exists() and time.monotonic() < deadline:
            # Drain the PTY like a real terminal; otherwise its finite output buffer
            # can block the worker before the shell starts.
            try:
                self.child.read_nonblocking(4096, timeout=0.05)
            except pexpect.TIMEOUT:
                pass
        self.assertTrue(marker.exists(), "Shell never started")
        started = time.monotonic()
        self.child.send("\x1b")
        self.child.expect_exact("[task complete] status=PAUSED", timeout=5)
        self.assertLess(time.monotonic() - started, 5)
        pid = int(marker.read_text())
        with self.assertRaises(ProcessLookupError):
            os.kill(pid, 0)
        with sqlite3.connect(self.root / ".rex/rex.db") as db:
            status, data = db.execute("SELECT status,data FROM task_execution_states").fetchone()
        self.assertEqual("PAUSED", status)
        self.assertIn("started", data)

    def test_confirmation_answer_and_escape_share_one_input_reader(self):
        self.mode = "approval"
        self.restart_with_confirmation()
        self.child.sendline("Use bash to create approved.txt")
        self.child.expect_exact("Run? [no]", timeout=12)
        self.assertNotIn("[y/N/pause/always", self.child.before)
        # Preserve an unfinished input draft while Right + Enter approves the tool.
        self.child.send("draft instruction")
        self.child.send("\x1b[C")
        self.child.sendline("")
        self.child.expect_exact("[task complete] status=FINISHED", timeout=12)
        self.assertEqual("approved", (self.root / "approved.txt").read_text())
        # Enter again submits the draft that survived menu navigation and task completion.
        self.child.sendline("")
        self.child.expect_exact("Run? [no]", timeout=12)
        self.child.send("\x1b")
        self.child.expect_exact("[task complete] status=PAUSED", timeout=5)
        self.assertTrue(self.child.isalive())
        with sqlite3.connect(self.root / ".rex/rex.db") as db:
            rows = db.execute("SELECT data FROM task_execution_states ORDER BY updated_at").fetchall()
        self.assertEqual(2, len(rows))
        self.assertNotIn("draft instruction", rows[0][0])
        self.assertIn("draft instruction", rows[1][0])

    def restart_with_confirmation(self):
        # Apply new confirmation settings by exiting, then restart the same isolated session.
        self.child.sendline("/exit"); self.child.expect(pexpect.EOF, timeout=7)
        path = self.root / "config.yml"
        config = yaml.safe_load(path.read_text()); config["tools"]["bash"]["require_confirmation"] = True
        path.write_text(yaml.safe_dump(config))
        self.child.close()
        env = dict(os.environ, REX_CONFIG=str(path), TERM="xterm-256color")
        env.pop("REX_MODEL", None); env.pop("REX_API_KEY", None)
        self.child = pexpect.spawn("java", ["-Duser.home=" + str(self.root), "-jar", str(JAR), "--session", "live"],
                                   cwd=str(self.root), env=env, encoding="utf-8", timeout=15, dimensions=(40, 140))
        self.child.logfile_read = self.log
        self.child.expect_exact("Enter: send / add instruction")
        self.child.expect(r"rex>|\x1b\[\d+;6H")

    def test_arrow_menu_default_no_and_session_always(self):
        self.mode = "approval"
        self.restart_with_confirmation()
        self.child.sendline("Use bash to create approved.txt")
        self.child.expect_exact("Run? [no]", timeout=12)
        self.child.sendline("")  # Enter alone rejects; default must never grant access.
        self.child.expect_exact("[task complete] status=FINISHED", timeout=12)
        self.assertFalse((self.root / "approved.txt").exists())
        self.child.sendline("Use bash to create approved.txt")
        self.child.expect_exact("Run? [no]", timeout=12)
        # no -> pause -> no -> yes -> always -> yes -> always, using all directions.
        for key in ["\x1b[A", "\x1b[B", "\x1b[B", "\x1b[B", "\x1b[D", "\x1b[C"]:
            self.child.send(key)
        self.child.sendline("")
        self.child.expect_exact("[task complete] status=FINISHED", timeout=12)
        self.assertEqual("approved", (self.root / "approved.txt").read_text())
        self.child.sendline("Use bash to create approved.txt again")
        outcome = self.child.expect_exact(["[task complete] status=FINISHED",
                                           "Run? [no]"], timeout=12)
        self.assertEqual(0, outcome, "Session always approval was not retained")

    def test_steering_changes_the_tool_action_in_the_same_task(self):
        self.mode = "steer"
        self.child.sendline("Use bash to create approved.txt")
        deadline = time.monotonic() + 12
        while time.monotonic() < deadline:
            request = self.next_request(timeout=5)
            if request.get("tools"):
                break
        else:
            self.fail("Executor model request never started")
        self.child.sendline("Write corrected.txt instead")
        self.child.expect_exact("Instruction added to the current task.", timeout=3)
        self.child.expect_exact("[task complete] status=FINISHED", timeout=12)
        self.assertFalse((self.root / "approved.txt").exists())
        self.assertEqual("corrected", (self.root / "corrected.txt").read_text())
        with sqlite3.connect(self.root / ".rex/rex.db") as db:
            rows = db.execute("SELECT data FROM task_execution_states").fetchall()
        self.assertEqual(1, len(rows))
        self.assertIn("Write corrected.txt instead", rows[0][0])

    def test_explicit_skill_budget_applies_to_real_cli_and_persisted_state(self):
        self.child.sendline("/exit"); self.child.expect(pexpect.EOF, timeout=7); self.child.close()
        skill = self.root / ".rex/skills/target/SKILL.md"
        skill.parent.mkdir(parents=True)
        skill.write_text("---\nname: target\ndescription: local budget probe\n---\nUse bash once, then report actual output.\n")
        path = self.root / "config.yml"
        config = yaml.safe_load(path.read_text())
        config["agent"]["execution_budget"] = {"hard_max_iterations": 4, "max_model_calls": 2, "retry_execution": False}
        path.write_text(yaml.safe_dump(config))
        self.mode = "budget"
        env = dict(os.environ, REX_CONFIG=str(path), TERM="xterm-256color")
        env.pop("REX_MODEL", None); env.pop("REX_API_KEY", None)
        self.child = pexpect.spawn("java", ["-Duser.home=" + str(self.root), "-jar", str(JAR), "--session", "live"],
                                   cwd=str(self.root), env=env, encoding="utf-8", timeout=15, dimensions=(40, 140))
        self.child.logfile_read = self.log
        self.child.expect_exact("Enter: send / add instruction")
        self.child.expect(r"rex>|\x1b\[\d+;6H")
        self.child.sendline("/target execute controlled probe")
        self.child.expect_exact("[task complete] status=TIMEOUT", timeout=15)
        self.assertEqual(2, self.requests.qsize(), "No third model request is allowed")
        self.assertEqual("approved", (self.root / "approved.txt").read_text())
        with sqlite3.connect(self.root / ".rex/rex.db") as db:
            state = json.loads(db.execute("SELECT data FROM task_execution_states").fetchone()[0])
        self.assertEqual("MODEL_CALL_LIMIT", state.get("termination_reason", state.get("terminationReason")))
        self.assertEqual({"plan": 1, "execute": 1}, state.get("model_call_attempts", state.get("modelCallAttempts")))
        self.assertEqual("target", state["request"].get("required_skill_id", state["request"].get("requiredSkillId")))
        self.assertEqual(4, state["rounds"][0].get("applied_max_iterations", state["rounds"][0].get("appliedMaxIterations")))

    def test_named_startup_replays_history_and_next_input_resumes_same_task(self):
        self.child.sendline("Analyze this project")
        self.next_request(timeout=10)
        self.child.send("\x1b")
        self.child.expect_exact("[task complete] status=PAUSED", timeout=5)
        with sqlite3.connect(self.root / ".rex/rex.db") as db:
            original = db.execute("SELECT task_id FROM task_execution_states").fetchone()[0]
        self.child.sendline("/exit"); self.child.expect(pexpect.EOF, timeout=7); self.child.close()
        env = dict(os.environ, REX_CONFIG=str(self.root / "config.yml"), TERM="xterm-256color")
        env.pop("REX_MODEL", None); env.pop("REX_API_KEY", None)
        self.child = pexpect.spawn("java", ["-Duser.home=" + str(self.root), "-jar", str(JAR), "--session", "live"],
                                   cwd=str(self.root), env=env, encoding="utf-8", timeout=15, dimensions=(40, 140))
        self.child.logfile_read = self.log
        self.child.expect_exact("rex> Analyze this project", timeout=10)
        replayed = self.child.before
        self.assertNotIn("NORMAL", replayed)
        self.assertNotIn("human:", replayed)
        self.child.expect_exact("Found an unfinished task", timeout=5)
        self.child.expect_exact("Enter: send / add instruction")
        self.child.expect(r"rex>|\x1b\[\d+;6H")
        self.assertTrue(self.requests.empty(), "Opening a session must not invoke the model")
        self.child.sendline("Continue with startup supplement")
        request = self.next_request(timeout=10)
        self.assertIn("Analyze this project", json.dumps(request))
        self.assertIn("Continue with startup supplement", json.dumps(request))
        self.child.send("\x1b")
        self.child.expect_exact("[task complete] status=PAUSED", timeout=5)
        with sqlite3.connect(self.root / ".rex/rex.db") as db:
            tasks = db.execute("SELECT task_id FROM task_execution_states").fetchall()
        self.assertEqual([(original,)], tasks)

    def test_session_picker_opens_selected_session_with_saved_history(self):
        import uuid
        session_id, turn_id = str(uuid.uuid4()), str(uuid.uuid4())
        foreign_root = self.root / "other-workspace"
        foreign_root.mkdir()
        with sqlite3.connect(self.root / ".rex/rex.db") as db:
            db.execute("INSERT INTO sessions VALUES (?, 'demo', ?, '', 1, 1)", (session_id, str(self.root)))
            db.execute("INSERT INTO sessions VALUES (?, 'foreign-session', ?, '', 1, 1)",
                       (str(uuid.uuid4()), str(foreign_root)))
            turn = {"id": turn_id, "type": "NORMAL", "created_at": 1, "messages": [
                {"role": "human", "content": "Saved demo request"},
                {"role": "ai", "content": "Saved demo response"}]}
            db.execute("INSERT INTO conversation_turns VALUES (?,0,0,'demo',1,?)", (turn_id, json.dumps(turn)))
        self.child.sendline("/resume")
        self.child.expect_exact("↑/↓ select · Enter open · Esc cancel", timeout=3)
        self.assertIn("live", self.child.before)
        self.assertIn("demo", self.child.before)
        self.assertNotIn(session_id, self.child.before)
        self.assertNotIn("foreign-session", self.child.before)
        self.child.send("\x1b[B")  # The current live session is initially selected.
        self.child.sendline("")
        self.child.expect_exact("Opened session: demo", timeout=5)
        self.child.expect_exact("rex> Saved demo request", timeout=3)
        self.child.expect_exact("Saved demo response", timeout=3)
        self.child.expect(r"rex>|\x1b\[\d+;6H")
        self.assertTrue(self.requests.empty(), "Session selection must not invoke the model")
        self.child.sendline("/resume")
        self.child.expect_exact("↑/↓ select · Enter open · Esc cancel", timeout=3)
        self.child.send("\x1b[A")
        self.child.send("\x1b")  # Cancel without switching away from demo.
        self.child.sendline("")  # A cancelled menu must not open the highlighted live session.
        self.child.sendline("/resume demo")
        self.child.expect_exact("Saved demo response", timeout=3)
        self.assertNotIn("Opened session:", self.child.before)
        for removed in ("/sessions", "/sessins"):
            self.child.sendline(removed)
            self.child.expect_exact("Unknown command: " + removed, timeout=3)
            self.assertNotIn("↑/↓ select", self.child.before)
        self.assertTrue(self.requests.empty())

        # Explicit names still allow opening a different project; its picker uses that root.
        self.child.sendline("/resume foreign-session")
        self.child.expect_exact("Opened session: foreign-session", timeout=5)
        self.child.expect(r"rex>|\x1b\[\d+;6H")
        self.child.sendline("/resume")
        self.child.expect_exact("↑/↓ select · Enter open · Esc cancel", timeout=3)
        self.assertIn("foreign-session", self.child.before)
        self.assertNotIn("  demo", self.child.before)
        self.assertNotIn("  live", self.child.before)
        self.child.send("\x1b")
        self.assertTrue(self.requests.empty())

    def test_control_c_exits_during_stalled_model(self):
        self.child.sendline("Analyze this project")
        self.next_request(timeout=10)
        started = time.monotonic()
        self.child.sendcontrol("c")
        self.child.expect(pexpect.EOF, timeout=7)
        self.assertLess(time.monotonic() - started, 6)


if __name__ == "__main__":
    unittest.main()
