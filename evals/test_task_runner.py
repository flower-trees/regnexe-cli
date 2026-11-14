"""Real CLI subprocess + local fixed-response HTTP server; no paid model/Opik/DB."""
import json
import os
import re
from pathlib import Path
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import task_runner as runner


@unittest.skipUnless((runner.ROOT/'target/regnexe-cli.jar').exists(), 'Build CLI first')
class RunnerTest(unittest.TestCase):
    def test_single_run_readonly_tools_no_answer_leak_and_repeat_is_rejected(self):
        requests = []
        expected = json.loads((runner.ROOT/'evals/cases/description/example.json').read_text())
        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                requests.append(body)
                messages = str(body['messages'])
                if 'You are a task planner' in messages:
                    content = json.dumps({'selectedCapabilityIds':['robot-description-style'], 'narrative':'Rewrite offline'})
                elif 'You are a reflection judge' in messages:
                    content = json.dumps({'action':'FINISH', 'reason':'Mock execution complete'})
                else:
                    content = json.dumps(expected, ensure_ascii=False)
                payload = json.dumps({'choices':[{'message':{'role':'assistant','content':content},'finish_reason':'stop'}]}).encode()
                self.send_response(200)
                self.send_header('Content-Type','application/json')
                self.send_header('Content-Length',str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)
            def log_message(self,*args):
                pass
        server = ThreadingHTTPServer(('127.0.0.1',0),Handler)
        server.daemon_threads = True
        thread = threading.Thread(target=server.serve_forever,daemon=True)
        thread.start()
        try:
            with tempfile.TemporaryDirectory(dir=runner.ROOT/'target', prefix='task-test-') as temp:
                out = Path(temp)/'run'
                runner.prepare(out, f'http://127.0.0.1:{server.server_port}/v1/chat/completions')
                before = (out/'workspace/input.json').read_text()
                self.assertNotIn('reference', before)
                self.assertNotIn('skill_source', before)
                result = runner.run(out)
                self.assertIsNone(result['failure'], (out/'stdout.txt').read_text()+(out/'stderr.txt').read_text())
                self.assertEqual('passed', result['automatic_status'])
                self.assertEqual('pending', result['manual_review'])
                self.assertEqual(3,len(requests))
                names = {tool['function']['name'] for tool in requests[1]['tools']}
                self.assertEqual(runner.ALLOWED, names)
                self.assertEqual({'plan':1,'execute':1,'reflect':1}, result['model_call_attempts'])
                self.assertEqual(before,(out/'workspace/input.json').read_text())
                state = json.loads((out/'state.json').read_text())
                self.assertNotIn('authoritativeFacts', state['request'])
                self.assertNotIn('offlineTextReview', state['request'])
                self.assertNotIn('groundingReview', str(requests[2]['messages']))
                with self.assertRaises(FileExistsError):
                    runner.run(out)
                self.assertEqual(3,len(requests))
        finally:
            server.shutdown()
            server.server_close()
            thread.join(2)


if __name__ == '__main__':
    unittest.main()
