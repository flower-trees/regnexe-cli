#!/usr/bin/env python3
"""One bounded Reflector-only replay of frozen P07 input/output; no answer regeneration."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import subprocess
from types import SimpleNamespace
import urllib.request
import zipfile
from skill_baseline import snapshot, compare
from skill_process import run_bounded
from skill_week1_runner import ROOT, CONFIG, sha, write

INPUT = ROOT/'evals/fixtures/skill-week1/p09-reflection-input.json'
JAVA = ROOT/'evals/java/FactualReviewReplay.java'


def manifest():
    return snapshot(SimpleNamespace(jar=ROOT/'target/regnexe-cli.jar', config=CONFIG, scope='model-evaluation'))


def prepare(out, mock):
    out = out.resolve()
    out.mkdir(parents=True, exist_ok=False)
    for name in ['runtime/classes', 'runtime/lib', 'classes', 'home']:
        (out/name).mkdir(parents=True)
    with zipfile.ZipFile(ROOT/'target/regnexe-cli.jar') as archive:
        for name in archive.namelist():
            if name.endswith('/'):
                continue
            if name.startswith('BOOT-INF/classes/'):
                destination = out/'runtime/classes'/name[len('BOOT-INF/classes/'):]
            elif name.startswith('BOOT-INF/lib/'):
                destination = out/'runtime/lib'/name[len('BOOT-INF/lib/'):]
            else:
                continue
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(archive.read(name))
    cp = os.pathsep.join([str(out/'runtime/classes'), str(out/'runtime/lib/*')])
    with (out/'javac.log').open('w') as log:
        subprocess.run(['javac','--release','17','-encoding','UTF-8','-cp',cp,'-d',str(out/'classes'),str(JAVA)],
                       stdout=log,stderr=subprocess.STDOUT,check=True,timeout=60)
    data = json.loads(INPUT.read_text())
    write(out/'mock-response.json', data['originalReflection'])
    write(out/'baseline.json', manifest())
    write(out/'prepared.json', {'mock':mock,'input_sha256':sha(INPUT),'source_sha256':sha(JAVA),
          'model':'deepseek-flash','model_call_cap':1,'timeout_seconds':120,'attempts_allowed':1,
          'purpose':'Replay the original bad answer through Reflector only; no Skill/Planner/tool execution.'})
    return out


def run(out):
    out = out.resolve()
    meta = json.loads((out/'prepared.json').read_text())
    if sha(INPUT) != meta['input_sha256'] or sha(JAVA) != meta['source_sha256']:
        raise ValueError('Replay inputs changed')
    if compare(json.loads((out/'baseline.json').read_text()), manifest()):
        raise ValueError('Baseline changed')
    if not meta['mock']:
        if not os.environ.get('DEEPSEEK_KEY','').strip():
            raise ValueError('DEEPSEEK_KEY missing')
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with opener.open('http://localhost:28173/health',timeout=5) as response:
            if response.status != 200:
                raise ValueError('Opik unavailable')
    with (out/'attempt.json').open('x') as claim:
        json.dump({'attempt':1,'started_at':datetime.now(timezone.utc).isoformat()},claim)
    data = json.loads(INPUT.read_text())
    env = {key:value for key,value in os.environ.items() if key in {'PATH','JAVA_HOME','LANG','LC_ALL','TMPDIR','SYSTEMROOT'}}
    env.update(REX_CONFIG=str(CONFIG),SKILL_EVAL_FACTS_JSON=json.dumps(data['facts'],ensure_ascii=False),
               SKILL_EVAL_SOURCE_ROOT=str(ROOT.parent/'salt-robot-skills'),
               DEEPSEEK_KEY='local-only' if meta['mock'] else os.environ['DEEPSEEK_KEY'],NO_PROXY='localhost,127.0.0.1,::1')
    cp = os.pathsep.join([str(out/'classes'),str(out/'runtime/classes'),str(out/'runtime/lib/*')])
    command=['java','-Duser.home='+str(out/'home'),'-cp',cp,'FactualReviewReplay',str(INPUT),str(out/'review.json')]
    if meta['mock']:
        command.append(str(out/'mock-response.json'))
    process = run_bounded(command,cwd=out,env=env,timeout_s=120)
    (out/'stdout.txt').write_text(process.stdout)
    (out/'stderr.txt').write_text(process.stderr)
    result={'process_failure':process.failure,'returncode':process.returncode,'elapsed_s':process.elapsed_s,
            'baseline_id':json.loads((out/'baseline.json').read_text())['baseline_id'],'mock':meta['mock']}
    if (out/'review.json').exists():
        review=json.loads((out/'review.json').read_text())
        result.update({key:review[key] for key in ['task_id','session','failure','termination_reason','model_call_attempts','decision']})
        result['token_summary'] = next((json.loads(event['text']) for event in review['events'] if event['type']=='TASK_TOKEN_SUMMARY'),None)
    write(out/'result.json',result)
    return result


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action',choices=['prepare','run'])
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--mock',action='store_true')
    args=parser.parse_args()
    if args.action=='prepare':
        prepare(args.output,args.mock)
        print(json.dumps({'status':'prepared','mock':args.mock}))
    else:
        result=run(args.output)
        print(json.dumps(result,ensure_ascii=False,indent=2))
        return 1 if result['process_failure'] or result.get('failure') or not result.get('decision') else 0
    return 0


if __name__=='__main__':
    try:
        raise SystemExit(main())
    except Exception as error:
        print(json.dumps({'status':'error','error_type':type(error).__name__,'message':'Inspect local artifacts; no automatic retry.'}))
        raise SystemExit(2)
