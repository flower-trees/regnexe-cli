"""Local subprocess checks; no model or external services."""
import os
from pathlib import Path
import sys
import tempfile
import time
import unittest
from skill_process import run_bounded


class ProcessDeadlineTests(unittest.TestCase):
    def test_nonzero_exit_is_not_retried(self):
        with tempfile.TemporaryDirectory() as tmp:
            code = "from pathlib import Path; p=Path('attempts'); p.write_text(p.read_text()+'x' if p.exists() else 'x'); print('partial',flush=True); raise SystemExit(7)"
            result = run_bounded([sys.executable, '-c', code], cwd=Path(tmp), env=os.environ.copy(), timeout_s=3)
            self.assertEqual('PROCESS_ERROR', result.failure)
            self.assertEqual(7, result.returncode)
            self.assertEqual('x', Path(tmp, 'attempts').read_text())
            self.assertIn('partial', result.stdout)

    def test_deadline_kills_child_and_retains_output(self):
        with tempfile.TemporaryDirectory() as tmp:
            code = "import subprocess,sys,time; subprocess.Popen([sys.executable,'-c',\"import time; from pathlib import Path; time.sleep(1); Path('late-write').write_text('bad')\"]); print('started',flush=True); time.sleep(20)"
            result = run_bounded([sys.executable, '-c', code], cwd=Path(tmp), env=os.environ.copy(), timeout_s=.3)
            self.assertEqual('RUNNER_TIMEOUT', result.failure)
            self.assertIn('started', result.stdout)
            self.assertLess(result.elapsed_s, 4)
            time.sleep(1)
            self.assertFalse(Path(tmp, 'late-write').exists())

    def test_success_and_invalid_deadline(self):
        result = run_bounded([sys.executable, '-c', "print('中文')"], cwd=Path.cwd(), env=os.environ.copy(), timeout_s=3)
        self.assertIsNone(result.failure)
        self.assertIn('中文', result.stdout)
        with self.assertRaises(ValueError):
            run_bounded([], cwd=Path.cwd(), env={}, timeout_s=0)


if __name__ == '__main__':
    unittest.main()
