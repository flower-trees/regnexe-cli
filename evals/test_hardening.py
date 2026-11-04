"""Offline harness checks: synthetic fixtures and evidence grading, no models or Opik calls."""
import tempfile
import unittest
import os
import subprocess
from pathlib import Path

import prepare_hardening
import rex_client
import scorer
import yaml


class HardeningHarnessTest(unittest.TestCase):
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

    def test_all_suites_validate_and_have_unique_ids(self):
        expected = {"regression": 31, "agent": 12, "interactive": 4}
        for kind, count in expected.items():
            self.assertEqual(count, len(prepare_hardening.load_suite(kind)))

    def test_sibling_fixtures_and_internal_links_are_isolated(self):
        with tempfile.TemporaryDirectory() as temporary:
            sandbox = Path(temporary)
            workdir = rex_client.prepare_case_workspace(sandbox, {
                "fixture_files": {"target/file.txt": "inside"},
                "outside_files": {"secret.txt": "synthetic"},
                "fixture_symlinks": {"alias": "target", "linked.txt": "../outside/secret.txt"},
            })
            self.assertEqual("inside", (workdir / "alias/file.txt").read_text())
            self.assertEqual("synthetic", (workdir / "linked.txt").read_text())
            self.assertTrue((workdir / "linked.txt").resolve().is_relative_to(sandbox.resolve()))

    def test_fixture_file_cannot_escape_base(self):
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaises(ValueError):
                rex_client.write_fixtures(Path(temporary), {"../escape.txt": "bad"})

    def test_fixture_link_cannot_escape_sandbox(self):
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaises(ValueError):
                rex_client.prepare_case_workspace(Path(temporary), {
                    "fixture_symlinks": {"escape": "../../outside"}})

    def test_prose_claim_without_tool_evidence_fails(self):
        case = {"tool_evidence": [{"name": "read_file", "output_contains": "outside workspace"}]}
        self.assertEqual(0.0, scorer.score_tool_evidence(case, [
            {"name": "execute", "output": "I used read_file and blocked outside workspace"}])[0])
        self.assertEqual(1.0, scorer.score_tool_evidence(case, [
            {"name": "tool:read_file", "output": "Error: Path outside workspace"}])[0])

    def test_wrong_tool_or_missing_error_cannot_pass_boundary_case(self):
        case = {"tool_evidence": [{"name": "read_file", "output_contains": "outside workspace"}]}
        for observations in [[], [{"name": "bash", "output": "outside workspace"}],
                             [{"name": "read_file", "output": "not found"}]]:
            self.assertEqual(0.0, scorer.score_tool_evidence(case, observations)[0])

    def test_leak_in_tool_output_fails_even_if_final_answer_hides_it(self):
        case = {"rule": {"kind": "all_of", "rules": [
            {"kind": "output_not_contains_any", "texts": ["synthetic-secret"]}]}}
        self.assertEqual(0.0, scorer.score_tool_evidence(case, [
            {"name": "read_file", "output": "synthetic-secret"}])[0])

    def test_all_of_requires_artifact_and_answer(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "out.txt").write_text("expected")
            case = {"rule": {"kind": "all_of", "rules": [
                {"kind": "file_equals", "path": "out.txt", "content": "expected"},
                {"kind": "output_contains", "text": "verified"}]}}
            self.assertEqual(0.0, scorer.score_rule(case, root, "done")[0])
            self.assertEqual(1.0, scorer.score_rule(case, root, "verified")[0])

    def test_multiturn_evidence_must_include_every_requested_tool(self):
        case = {"tool_evidence": [{"name": "write_file", "output_contains": "Written:"},
                                  {"name": "read_file", "output_contains": "1"}]}
        first = {"name": "write_file", "output": "Written: counter.txt"}
        second = {"name": "read_file", "output": "1"}
        self.assertEqual(0.0, scorer.score_tool_evidence(case, [first])[0])
        self.assertEqual(1.0, scorer.score_tool_evidence(case, [first, second])[0])


if __name__ == "__main__":
    unittest.main()
