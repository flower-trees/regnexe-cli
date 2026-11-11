import json, sys
from pathlib import Path
from importlib.metadata import version
sys.path.insert(0,str(Path.cwd()))
import pymysql
calls=[]
def blocked(*args,**kwargs):
    calls.append(True)
    raise AssertionError('P02 must not connect to a database')
pymysql.connect=blocked
import dotenv
from lib import db
assert callable(db.get_conn)
assert not calls
assert not Path('.env').exists()
assert not any(k.startswith(('DB_','API_ADMIN','OSS_')) for k in __import__('os').environ)
result={'python':sys.executable,'prefix':sys.prefix,'python_version':sys.version.split()[0],
        'cwd':str(Path.cwd()),'utf8':'中文😀','db_module':str(Path(db.__file__).resolve()),
        'packages':{'PyMySQL':version('PyMySQL'),'python-dotenv':version('python-dotenv')},
        'database_connections':len(calls)}
Path('business.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(result,ensure_ascii=False))
