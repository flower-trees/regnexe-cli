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
                        "selectedCapabilityIds": ["bash"],
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
            "ui": {"theme": "minimal", "color": "never", "icons": False},
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
            self.child.expect_exact("rex> ")
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

    def test_live_instruction_and_escape_during_stalled_model(self):
        self.child.sendline("Analyze this project")
        self.requests.get(timeout=10)
        started = time.monotonic()
        self.child.sendline("Focus on task recovery only")
        self.child.expect_exact("Instruction added to the current task.", timeout=3)
        updated = self.requests.get(timeout=5)
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
        self.child.send("/sessios")
        self.child.send("\x1b[D")
        self.child.sendline("n")
        self.child.expect_exact("live", timeout=3)

    def test_escape_stops_shell_and_keeps_partial_output(self):
        self.mode = "shell"
        self.child.sendline("Use bash to run a long sleep command")
        deadline = time.monotonic() + 12
        marker = self.root / "child.pid"
        while not marker.exists() and time.monotonic() < deadline:
            time.sleep(0.05)
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
        self.child.expect_exact("rex> ")

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
            request = self.requests.get(timeout=5)
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

    def test_named_startup_replays_history_and_next_input_resumes_same_task(self):
        self.child.sendline("Analyze this project")
        self.requests.get(timeout=10)
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
        self.child.expect_exact("rex> ")
        self.assertTrue(self.requests.empty(), "Opening a session must not invoke the model")
        self.child.sendline("Continue with startup supplement")
        request = self.requests.get(timeout=10)
        self.assertIn("Analyze this project", json.dumps(request))
        self.assertIn("Continue with startup supplement", json.dumps(request))
        self.child.send("\x1b")
        self.child.expect_exact("[task complete] status=PAUSED", timeout=5)
        with sqlite3.connect(self.root / ".rex/rex.db") as db:
            tasks = db.execute("SELECT task_id FROM task_execution_states").fetchall()
        self.assertEqual([(original,)], tasks)

    def test_control_c_exits_during_stalled_model(self):
        self.child.sendline("Analyze this project")
        self.requests.get(timeout=10)
        started = time.monotonic()
        self.child.sendcontrol("c")
        self.child.expect(pexpect.EOF, timeout=7)
        self.assertLess(time.monotonic() - started, 6)


if __name__ == "__main__":
    unittest.main()
