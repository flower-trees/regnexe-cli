#!/usr/bin/env python3
"""Capture/check a local Skill evaluation manifest. No model, database or network calls."""
import argparse
from datetime import datetime, timezone
import hashlib
import io
import json
from pathlib import Path
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parent.parent


def sha(data):
    return hashlib.sha256(data).hexdigest()


def canonical(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(',', ':')).encode()


def files_digest(root, patterns):
    paths = sorted({p for pattern in patterns for p in root.glob(pattern) if p.is_file() and not any(part in {'__pycache__', '.venv', '.git'} for part in p.parts)
                    and p.suffix != '.pyc'})
    if not paths:
        raise ValueError('Required source/input file set is empty')
    return {str(p.relative_to(root)): sha(p.read_bytes()) for p in paths}


def git(repo, *args):
    return subprocess.check_output(['git', '-C', str(repo), *args], text=True).strip()


def python_environment(executable):
    # Isolated interpreter: no user site, no application imports or connection side effects.
    code = ('import sys,json,importlib.metadata as m; '
            'print(json.dumps({"version":sys.version.split()[0],'
            '"packages":sorted((d.metadata["Name"].lower(),d.version) for d in m.distributions())}))')
    return json.loads(subprocess.check_output([str(executable), '-I', '-B', '-c', code], text=True, timeout=15))


def snapshot(args):
    repositories = {name: {'commit': git(repo, 'rev-parse', 'HEAD'),
                            'branch': git(repo, 'branch', '--show-current'),
                            'dirty': bool(git(repo, 'status', '--porcelain'))}
                    for name, repo in [('cli', ROOT), ('agent', ROOT.parent/'regnexe-agent'),
                                       ('skills', ROOT.parent/'salt-robot-skills')]}
    jar_bytes = args.jar.read_bytes()
    with zipfile.ZipFile(io.BytesIO(jar_bytes)) as archive:
        dependencies = {}
        for name in ['regnexe-agent', 'j-langchain']:
            entries = [p for p in archive.namelist() if p.startswith('BOOT-INF/lib/'+name+'-') and p.endswith('.jar')]
            if len(entries) != 1:
                raise ValueError('Expected exactly one embedded '+name)
            dependencies[name] = {'entry': entries[0], 'sha256': sha(archive.read(entries[0]))}
    components = {
        'cli_sources': files_digest(ROOT, ['src/**/*', 'pom.xml']),
        'agent_sources': files_digest(ROOT.parent/'regnexe-agent', ['src/**/*', 'pom.xml']),
        'artifact': {'cli_jar_sha256': sha(jar_bytes), 'embedded_dependencies': dependencies},
        'skills': files_digest(ROOT.parent/'salt-robot-skills', ['.claude/skills/**/*', 'lib/**/*.py']),
        'config': {'path': str(args.config.resolve().relative_to(ROOT)), 'sha256': sha(args.config.read_bytes()),
                   'scope': args.scope},
        'evaluation': files_digest(ROOT, ['evals/*.py', 'evals/java/*.java', 'evals/requirements*.txt',
                                          'evals/fixtures/skill-week1/*']),
        'runner_python': python_environment(ROOT/'evals/.venv/bin/python'),
        'business_python': python_environment(ROOT/'target/skill-eval-week1/business-venv/bin/python'),
    }
    identity = sha(canonical(components))
    return {'schema_version': 1, 'baseline_id': identity, 'components': components,
            'recorded_at': datetime.now(timezone.utc).isoformat(), 'repositories': repositories,
            'limitations': ['Content identity, not proof that the JAR was built from these sources.',
                            'Git revisions/dirty flags are provenance; documentation-only commits do not change content identity.',
                            'Does not validate effective model requests, live tools, database schema or fixture state.',
                            'Database identity, schema and initial fixture state require separate per-run preflight.',
                            'Scope loading-only is not an executable model-evaluation configuration.']}


def compare(expected, current):
    if expected.get('schema_version') != 1:
        raise ValueError('Unsupported baseline schema')
    if expected.get('baseline_id') != sha(canonical(expected['components'])):
        raise ValueError('Baseline content ID is invalid')
    keys = sorted(set(expected['components']) | set(current['components']))
    return [key for key in keys if expected['components'].get(key) != current['components'].get(key)]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['capture', 'check'])
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--jar', type=Path, default=ROOT/'target/regnexe-cli.jar')
    parser.add_argument('--config', type=Path, default=ROOT/'evals/skill-week1-inventory.yml')
    parser.add_argument('--scope', choices=['loading-only', 'model-evaluation'], default='loading-only')
    args = parser.parse_args()
    current = snapshot(args)
    if args.action == 'capture':
        args.manifest.parent.mkdir(parents=True, exist_ok=True)
        with args.manifest.open('x') as output:
            output.write(json.dumps(current, ensure_ascii=False, indent=2)+'\n')
        result = {'status': 'captured', 'baseline_id': current['baseline_id'], 'scope': args.scope}
    else:
        expected = json.loads(args.manifest.read_text())
        changed = compare(expected, current)
        result = {'status': 'changed' if changed else 'matched', 'baseline_id': expected['baseline_id'],
                  'current_id': current['baseline_id'], 'changed_components': changed,
                  'provenance_changed': expected['repositories'] != current['repositories']}
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 1 if changed else 0
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (OSError, ValueError, KeyError, subprocess.SubprocessError, zipfile.BadZipFile):
        print(json.dumps({'status': 'error', 'message': 'Cannot capture/verify required baseline inputs; no baseline accepted.'}))
        sys.exit(2)
