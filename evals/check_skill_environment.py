#!/usr/bin/env python3
"""P02 only: imports and actual Shell checks; one labelled trace in a preflight project.

Never builds an Agent, calls a model, or connects to a business database/MCP.
Install requirements-skill-week1.txt into an isolated business venv beforehand.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import urllib.request

ROOT = Path(__file__).resolve().parent.parent

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline', type=Path, default=ROOT/'target/skill-eval-week1/20261109T132544Z')
    parser.add_argument('--business-python', type=Path, default=ROOT/'target/skill-eval-week1/business-venv/bin/python')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    baseline = args.baseline.resolve(); python = args.business_python.absolute()
    out = (args.output or ROOT/'target/skill-eval-week1'/datetime.now(timezone.utc).strftime('p02-%Y%m%dT%H%M%SZ')).resolve()
    out.mkdir(parents=True,exist_ok=True)
    if (out/'shell.json').exists():
        raise RuntimeError('Use a fresh output directory; do not overwrite an earlier preflight')
    assert python.exists(), 'Create the dedicated business venv first'
    assert sha(ROOT/'target/regnexe-cli.jar') == json.loads((baseline/'baseline.json').read_text())['jar']['sha256'], 'JAR changed: refresh P00 first'
    for name in ['home','tmp','classes','workspace/lib']:
        (out/name).mkdir(parents=True,exist_ok=True)
    db_source = ROOT.parent/'salt-robot-skills/lib/db.py'
    shutil.copyfile(db_source,out/'workspace/lib/db.py')
    shutil.copyfile(ROOT/'evals/fixtures/skill-week1/business_import_probe.py',out/'workspace/probe-business.py')
    assert not (out/'workspace/.env').exists()
    env={key:value for key,value in os.environ.items() if key in {'PATH','JAVA_HOME','LANG','LC_ALL','TMPDIR','SYSTEMROOT'}}
    env['PATH']=str(python.parent)+os.pathsep+env.get('PATH','')
    cp=os.pathsep.join([str(baseline/'runtime/classes'),str(baseline/'runtime/lib/*')])
    def run(command, log, cwd=ROOT, timeout=45):
        with (out/log).open('w') as stream:
            result=subprocess.run(command,cwd=cwd,env=env,stdout=stream,stderr=subprocess.STDOUT,timeout=timeout)
        if result.returncode:
            raise RuntimeError(f'Command failed: inspect {out/log}')
    run(['javac','--release','17','-encoding','UTF-8','-cp',cp,'-d',str(out/'classes'),str(ROOT/'evals/java/SkillEnvironmentProbe.java')],'compile.log')
    # All network traffic below is explicitly directed at local Opik.
    opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open('http://localhost:28173/health',timeout=5) as response:
        health={'status':response.status,'body':response.read().decode()}
    run(['java','-Duser.home='+str(out/'home'),'-Djava.io.tmpdir='+str(out/'tmp'),'-cp',str(out/'classes')+os.pathsep+cp,
         'SkillEnvironmentProbe',str(out/'workspace'),str(python),str(out/'shell.json')], 'shell-and-otel.log',out/'workspace')
    shell=json.loads((out/'shell.json').read_text())
    business=json.loads((out/'workspace/business.json').read_text())
    assert shell['passed'] and business['database_connections']==0
    assert business['cwd']==str((out/'workspace').resolve())
    assert Path(business['python']).parent==python.parent
    assert Path(business['prefix'])==python.parent.parent
    assert sha(db_source)==sha(out/'workspace/lib/db.py')
    run([str(python),'-m','pip','freeze'],'business-requirements.lock')
    os.environ.update(OPIK_URL_OVERRIDE='http://localhost:28173/api/', OPIK_WORKSPACE='default',
                      OPIK_TRACK_DISABLE='true',OPIK_ANONYMOUS_STATISTICS_REPORT_ENABLED='false',NO_PROXY='localhost,127.0.0.1,::1')
    os.environ.pop('OPIK_API_KEY',None)
    import opik
    client=opik.Opik(project_name=shell['project'],batching=False)
    projects=client.rest_client.projects.find_projects(name='regnexe-cli')
    assert any(p.name=='regnexe-cli' for p in projects.content), 'Main project missing'
    traces=client.search_traces(project_name=shell['project'],filter_string=f'metadata.session_name = "{shell["session_name"]}"',
                              truncate=False,wait_for_at_least=1,wait_for_timeout=20)
    assert len(traces)==1, 'Expected one uniquely labelled preflight trace'
    trace=client.get_trace_content(id=traces[0].id)
    assert trace.metadata['task_id']==shell['task_id']
    assert trace.metadata['session_name']==shell['session_name']
    assert trace.thread_id==shell['session_id']
    assert trace.output and 'PREFLIGHT_NOT_BUSINESS_EVALUATION' in str(trace.output)
    assert trace.end_time is not None
    spans=client.search_spans(project_name=shell['project'],trace_id=trace.id,truncate=False,
                             wait_for_at_least=6,wait_for_timeout=20)
    tools=[span for span in spans if span.type=='tool']
    assert len(tools)==4, f'Expected four controlled Shell spans, got {len(tools)}'
    assert not any(span.type=='llm' for span in spans)
    result={'stage':'P02','recorded_at':datetime.now(timezone.utc).isoformat(),'status':'passed',
            'cli_jar_sha256':sha(ROOT/'target/regnexe-cli.jar'),'business':business,'health':health,
            'checks':shell['checks'],'db_source_sha256':sha(db_source),'business_lock_sha256':sha(out/'business-requirements.lock'),
            'probe_java_sha256':sha(ROOT/'evals/java/SkillEnvironmentProbe.java'),
            'probe_python_sha256':sha(ROOT/'evals/fixtures/skill-week1/business_import_probe.py'),
            'opik':{'main_project_exists':True,'project':shell['project'],'trace_id':trace.id,
                    'task_id':shell['task_id'],'session_name':shell['session_name'],'session_id':shell['session_id'],
                    'tool_spans':len(tools),'total_spans':len(spans),'kind':'preflight only, not evaluation scores'},
            'model_calls':0,'business_db_connections':0,'mcp_connections':0,'uploads':0,
            'limitations':['Imports are not database connectivity or schema verification (P03/P10).',
                           'Shell checks use the packaged backend directly, not Agent selection or approval UI.',
                           'Opik received one synthetic-wrapper trace of actual controlled commands; no experiments, datasets or scores.']}
    (out/'summary.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'output':str(out),'status':result['status'],'packages':business['packages'],
                      'shell_checks':len(shell['checks']),'opik':result['opik'],'model_calls':0,'business_db_connections':0},ensure_ascii=False,indent=2))

if __name__=='__main__':
    main()
