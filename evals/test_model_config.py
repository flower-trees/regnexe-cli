"""Offline harness checks: synthetic fixtures and evidence grading, no models or Opik calls."""
import tempfile
import unittest
import os
import subprocess
from pathlib import Path

import yaml


class ModelConfigTest(unittest.TestCase):
    def test_model_config_update_preserves_other_settings_and_backs_up(self):
        script = Path(__file__).resolve().parent.parent / "scripts/configure-deepseek-flash.py"
        with tempfile.TemporaryDirectory() as temporary:
            config = Path(temporary) / "config.yml"
            original = "model:\n  vendor: custom\n  name: old\n  api_key: old-placeholder\nagent:\n  max_rounds: 7\nui:\n  icons: false\n"
            config.write_text(original)
            import sys
            result = subprocess.run([sys.executable, str(script), "--config", str(config), "--apply"],
                                    env={**os.environ, "DEEPSEEK_KEY": "synthetic-key-not-real"},
                                    capture_output=True, text=True)
            self.assertEqual(0, result.returncode)
            data = yaml.safe_load(config.read_text())
            self.assertEqual("deepseek-flash", data["model"]["name"])
            self.assertEqual("${DEEPSEEK_KEY}", data["model"]["api_key"])
            self.assertEqual({"max_rounds": 7}, data["agent"])
            self.assertEqual({"icons": False}, data["ui"])
            self.assertNotIn("synthetic-key-not-real", config.read_text() + result.stdout)
            backups = list(Path(temporary).glob("config.yml.bak.*"))
            self.assertEqual(1, len(backups))
            self.assertEqual(original, backups[0].read_text())
            self.assertEqual(0o600, backups[0].stat().st_mode & 0o777)

    def test_model_config_missing_key_never_changes_file(self):
        script = Path(__file__).resolve().parent.parent / "scripts/configure-deepseek-flash.py"
        with tempfile.TemporaryDirectory() as temporary:
            config = Path(temporary) / "config.yml"
            config.write_text("model:\n  name: original\n")
            import sys
            result = subprocess.run([sys.executable, str(script), "--config", str(config), "--apply"],
                                    env={**os.environ, "DEEPSEEK_KEY": ""}, capture_output=True, text=True)
            self.assertNotEqual(0, result.returncode)
            self.assertEqual("model:\n  name: original\n", config.read_text())

