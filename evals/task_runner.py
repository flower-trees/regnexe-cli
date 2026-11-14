#!/usr/bin/env python3
"""Run one offline description task through the normal CLI; inspect output and cost."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import subprocess
import urllib.parse
import uuid

import yaml
from skill_description_score import score_description
from skill_process import run_bounded

ROOT = Path(__file__).resolve().parent.parent
CONFIG = ROOT/'evals/task-config.yml'
CASE = ROOT/'evals/cases/description/input.json'
SKILL = ROOT.parent/'salt-robot-skills/.claude/skills/robot-description-style/SKILL.md'
ALLOWED = {'read_file', 'list_files', 'search_files', 'write_todos'}


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2)+'\n')


def prepare(out, mock_url=None):
    out = out.resolve()
    out.mkdir(parents=True, exist_ok=False)
    case = json.loads(CASE.read_text())
    workspace = out/'workspace'
    workspace.mkdir()
    (out/'home/.rex').mkdir(parents=True)
    packet = {'facts': case['facts'], 'before': case['before']}
    write(workspace/'input.json', packet)
    prompt = case['auto_prompt']+'\n本次为离线文本改写，不联网、不读写数据库、不上传。所需事实已提供，无须探索文件。\n'+json.dumps(packet, ensure_ascii=False)
    # Piped REPL receives exactly one user turn and then /exit. Escape embedded newlines in data.
    stdin = '/robot-description-style '+prompt.replace('\n', ' ')+'\n/exit\n'
    (out/'stdin.txt').write_text(stdin)
    config_path = CONFIG
    if mock_url:
        url = urllib.parse.urlsplit(mock_url)
        if url.scheme != 'http' or url.hostname not in {'127.0.0.1', 'localhost'}:
            raise ValueError('Mock server must be local HTTP')
        config = yaml.safe_load(CONFIG.read_text())
        config['model'].update(vendor='custom', name='local-skill-test', chat_url=mock_url,
                               planner_name='local-skill-test', reflector_name='local-skill-test')
        config['opik']['enabled'] = False
        config_path = out/'config.yml'
        config_path.write_text(yaml.safe_dump(config, allow_unicode=True))
    write(out/'prepared.json', {'mock': bool(mock_url), 'config': str(config_path),
          'config_sha256': sha(config_path), 'case_sha256': sha(CASE), 'skill_sha256': sha(SKILL),
          'stdin_sha256': sha(out/'stdin.txt'), 'packet_sha256': sha(workspace/'input.json'),
          'jar_sha256': sha(ROOT/'target/regnexe-cli.jar'), 'session': 'task-'+uuid.uuid4().hex[:12],
          'cli_commit': subprocess.check_output(['git', '-C', str(ROOT), 'rev-parse', 'HEAD'], text=True).strip(),
          'cli_dirty': bool(subprocess.check_output(['git', '-C', str(ROOT), 'status', '--porcelain'], text=True).strip()),
          'model': yaml.safe_load(config_path.read_text())['model']['name'],
          'timeout_seconds': 120, 'automatic_retries': 0, 'manual_reference_review': 'pending'})
    return out


def field(value, name, default=None):
    return value.get(name, value.get(re.sub(r'(?<!^)(?=[A-Z])', '_', name).lower(), default))


def strings(value):
    if isinstance(value, str):
        return [value]
    if isinstance(value, dict):
        return [s for v in value.values() for s in strings(v)]
    if isinstance(value, list):
        return [s for v in value for s in strings(v)]
    return []


def observe(session, task, answer):
    os.environ.update(OPIK_URL_OVERRIDE='http://localhost:28173/api/', OPIK_WORKSPACE='default',
                      OPIK_TRACK_DISABLE='true', OPIK_ANONYMOUS_STATISTICS_REPORT_ENABLED='false',
                      NO_PROXY='localhost,127.0.0.1,::1')
    os.environ.pop('OPIK_API_KEY', None)
    import opik
    client = opik.Opik(project_name='regnexe-skill-week1', batching=False)
    traces = client.search_traces(project_name='regnexe-skill-week1', filter_string=f'metadata.session_name = "{session}"',
                                 truncate=False, wait_for_at_least=1, wait_for_timeout=20)
    if len(traces) != 1:
        raise ValueError('Expected one uniquely correlated task trace')
    trace = client.get_trace_content(id=traces[0].id)
    if trace.metadata.get('task_id') != task or trace.end_time is None:
        raise ValueError('Trace identity/completion missing')
    return {'trace_id': trace.id, 'task_id': task, 'project': 'regnexe-skill-week1',
            'answer_matches_ledger': any(answer in s for s in strings(trace.output)) if answer else False,
            'token_summary': (trace.metadata or {}).get('token_summary')}


def run(out):
    out = out.resolve()
    meta = json.loads((out/'prepared.json').read_text())
    config = Path(meta['config'])
    for path, expected in [(config, meta['config_sha256']), (CASE, meta['case_sha256']), (SKILL, meta['skill_sha256']),
                           (out/'stdin.txt', meta['stdin_sha256']), (out/'workspace/input.json', meta['packet_sha256'])]:
        if sha(path) != expected:
            raise ValueError('Prepared input changed; do not run')
    if sha(ROOT/'target/regnexe-cli.jar') != meta['jar_sha256']:
        raise ValueError('CLI artifact changed after preparing this task')
    if not meta['mock'] and not os.environ.get('DEEPSEEK_KEY', '').strip():
        raise ValueError('DEEPSEEK_KEY missing')
    # Claim once. A failure, timeout or observation error never launches another process.
    with (out/'attempt.json').open('x') as claim:
        json.dump({'started_at': datetime.now(timezone.utc).isoformat(), 'attempt': 1}, claim)
    env = {key: value for key, value in os.environ.items() if key in {'PATH','JAVA_HOME','LANG','LC_ALL','TMPDIR','SYSTEMROOT'}}
    env.update(REX_CONFIG=str(config), SKILL_EVAL_SOURCE_ROOT=str(ROOT.parent/'salt-robot-skills'),
               DEEPSEEK_KEY='local-test-only' if meta['mock'] else os.environ['DEEPSEEK_KEY'],
               NO_PROXY='localhost,127.0.0.1,::1', TERM='dumb')
    process = run_bounded(['java', '-Duser.home='+str(out/'home'), '-jar', str(ROOT/'target/regnexe-cli.jar'),
                           '--session', meta['session']], cwd=out/'workspace', env=env,
                          timeout_s=meta['timeout_seconds'], stdin=(out/'stdin.txt').read_text())
    (out/'stdout.txt').write_text(process.stdout)
    (out/'stderr.txt').write_text(process.stderr)
    result = {'kind': 'mock' if meta['mock'] else 'real-model-diagnostic',
              'session': meta['session'], 'attempts': 1,
              'process_returncode': process.returncode, 'elapsed_s': process.elapsed_s,
              'failure': process.failure, 'manual_review': 'pending', 'automatic_status': 'not_evaluated'}
    state = None
    db_path = out/'home/.rex/rex.db'
    if db_path.exists():
        with sqlite3.connect('file:'+str(db_path)+'?mode=ro', uri=True) as db:
            rows = db.execute('SELECT data FROM task_execution_states').fetchall()
        if len(rows) == 1:
            state = json.loads(rows[0][0])
    if state is None:
        result['failure'] = result['failure'] or 'TASK_EVIDENCE_MISSING'
    else:
        write(out/'state.json', state)
        rounds = field(state, 'rounds', [])
        answer = ''
        if rounds and field(rounds[-1], 'executionResult'):
            answer = field(field(rounds[-1], 'executionResult'), 'finalText', '')
        (out/'answer.txt').write_text(answer)
        score = score_description(answer)
        write(out/'score.json', score)
        records = [r for rd in rounds for r in field(field(rd, 'executionResult', {}) or {}, 'toolExecutions', [])]
        result.update(task_id=field(state, 'taskId'), task_status=field(state, 'status'),
                      termination_reason=field(state, 'terminationReason'), model_call_attempts=field(state, 'modelCallAttempts'),
                      applied_iterations=[field(r, 'appliedMaxIterations') for r in rounds], tool_records=records,
                      automatic_status=score['automatic_status'])
        if field(state, 'status') != 'FINISHED':
            result['failure'] = result['failure'] or field(state, 'terminationReason') or 'TASK_NOT_FINISHED'
        if field(field(state, 'request'), 'requiredSkillId') != 'robot-description-style':
            result['failure'] = result['failure'] or 'SKILL_CONSTRAINT_MISSING'
        if any(not re.search(r'(?:^|[:\s])(?:read_file|list_files|search_files|write_todos)(?:$|\s)', field(record, 'toolName', '')) for record in records):
            result['failure'] = result['failure'] or 'UNEXPECTED_TOOL'
        if not meta['mock']:
            try:
                observation = observe(meta['session'], result['task_id'], answer)
                write(out/'opik.json', observation)
                result['trace_id'] = observation['trace_id']
                result['token_summary'] = observation['token_summary']
                if not observation['answer_matches_ledger']:
                    result['observation_error'] = 'TRACE_ANSWER_MISMATCH'
            except Exception as error:
                result['observation_error'] = type(error).__name__

    if sha(out/'workspace/input.json') != meta['packet_sha256']:
        result['failure'] = result['failure'] or 'INPUT_MUTATED'
    if result['automatic_status'] == 'failed':
        result['failure'] = result['failure'] or 'OUTPUT_RULE_FAILURE'
    write(out/'result.json', result)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['prepare', 'run'])
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--mock-url')
    args = parser.parse_args()
    if args.action == 'prepare':
        prepare(args.output, args.mock_url)
        print(json.dumps({'status': 'prepared', 'output': str(args.output)}))
    else:
        result = run(args.output)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 1 if result['failure'] or result['automatic_status'] == 'failed' else 0
    return 0


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except Exception as error:
        # Never emit raw provider errors, which might embed credentials.
        print(json.dumps({'status': 'error', 'error_type': type(error).__name__, 'message': 'Inspect local evidence; no automatic retry.'}))
        raise SystemExit(2)
