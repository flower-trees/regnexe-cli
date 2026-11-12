"""No model/network: baseline acceptance, drift and invalid-input behavior."""
import copy
import tempfile
from pathlib import Path
import unittest

from skill_baseline import canonical, sha, compare, files_digest


class BaselineChecks(unittest.TestCase):
    def baseline(self):
        components = {'artifact': {'sha256': 'old'}, 'config': {'sha256': 'fixed'}}
        return {'schema_version': 1, 'components': components,
                'baseline_id': sha(canonical(components)), 'repositories': {'commit': 'before'}}

    def test_documentation_revision_does_not_invalidate_content(self):
        old = self.baseline()
        new = copy.deepcopy(old)
        new['repositories']['commit'] = 'documentation-commit'
        self.assertEqual([], compare(old, new))

    def test_artifact_drift_is_reported(self):
        old = self.baseline()
        new = copy.deepcopy(old)
        new['components']['artifact']['sha256'] = 'changed'
        self.assertEqual(['artifact'], compare(old, new))

    def test_modified_manifest_is_rejected(self):
        old = self.baseline()
        old['components']['config']['sha256'] = 'tampered'
        with self.assertRaises(ValueError):
            compare(old, self.baseline())

    def test_missing_files_fail_and_caches_are_excluded(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            with self.assertRaises(ValueError):
                files_digest(root, ['**/*'])
            (root/'source.py').write_text('pass\n')
            (root/'__pycache__').mkdir()
            (root/'__pycache__/source.pyc').write_bytes(b'cache')
            self.assertEqual(['source.py'], list(files_digest(root, ['**/*'])))


if __name__ == '__main__':
    unittest.main()
