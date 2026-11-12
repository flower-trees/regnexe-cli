#!/usr/bin/env python3
"""P03: verify the explicitly selected robot-pre database using read-only queries."""
import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parent.parent


def digest(value):
    return hashlib.sha256(str(value).encode()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--env-file', type=Path, default=ROOT.parent/'salt-robot-skills/.env')
    parser.add_argument('--output', type=Path, default=ROOT/'target/skill-eval-week1/p03-robot-pre.json')
    args = parser.parse_args()
    from dotenv import dotenv_values
    import pymysql
    # Explicit file values take priority; missing DB fields cannot fall back to inherited DB_*.
    values = dotenv_values(args.env_file)
    required = ['DB_HOST', 'DB_USER', 'DB_PASSWORD', 'DB_NAME']
    assert all(values.get(key) for key in required), 'Explicit database fields missing from configuration'
    for key in list(os.environ):
        if key.startswith('DB_'):
            del os.environ[key]
    for key, value in values.items():
        if key.startswith('DB_') and value is not None:
            os.environ[key] = value
    # User explicitly selected robot-pre; never connect to the file's production default.
    configured_database_matches = os.environ['DB_NAME'] == 'robot-pre'
    os.environ['DB_NAME'] = 'robot-pre'
    source = ROOT.parent/'salt-robot-skills/lib/db.py'
    spec = importlib.util.spec_from_file_location('skill_db_preflight', source)
    db = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(db)
    assert db._resolve_env('DB_NAME') == 'robot-pre', 'Only robot-pre is authorized for this probe'
    original_connect = pymysql.connect
    observed = {}

    def controlled_connect(**kwargs):
        assert kwargs['database'] == 'robot-pre'
        kwargs.update(connect_timeout=5, read_timeout=5, write_timeout=5)
        observed.update(host_sha256=digest(kwargs['host']), user_sha256=digest(kwargs['user']),
                        connect_timeout=5, read_timeout=5, write_timeout=5)
        return original_connect(**kwargs)

    pymysql.connect = controlled_connect
    conn = db.get_conn()
    try:
        with conn.cursor() as cur:
            cur.execute('SET SESSION TRANSACTION READ ONLY')
            cur.execute('SELECT DATABASE() AS db, CURRENT_USER() AS account, @@hostname AS server, @@session.transaction_read_only AS read_only')
            identity = cur.fetchone()
            assert identity['db'] == 'robot-pre'
            assert identity['read_only'] == 1
            cur.execute('SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE()')
            tables = [row['TABLE_NAME'] for row in cur.fetchall()]
            cur.execute("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'robot'")
            columns = {row['COLUMN_NAME'] for row in cur.fetchall()}
        result = dict(stage='P03', status='passed', recorded_at=datetime.now(timezone.utc).isoformat(),
                      database=identity['db'], env_database_matches_target=configured_database_matches, account_sha256=digest(identity['account']),
                      server_sha256=digest(identity['server']), session_read_only=True,
                      table_count=len(tables), robot_table_present='robot' in tables,
                      description_columns_present=sorted(columns & {'description', 'detail_description'}),
                      connection=observed, db_source_sha256=hashlib.sha256(source.read_bytes()).hexdigest(),
                      model_calls=0, database_writes=0,
                      limitations=['Connection and identity only; P10 write privileges, fixtures and schema acceptance remain unverified.',
                                   'Timeouts are enforced by this probe wrapper; upstream lib/db.py remains unchanged.'])
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n')
        print(json.dumps(result, ensure_ascii=False, indent=2))
    finally:
        conn.rollback()
        conn.close()
        pymysql.connect = original_connect


if __name__ == '__main__':
    try:
        main()
    except Exception as exc:
        # Driver errors can contain endpoint/account details; do not print raw errors.
        print(json.dumps({'status': 'failed', 'error_type': type(exc).__name__,
                          'driver_error_code': exc.args[0] if exc.args and isinstance(exc.args[0], int) else None,
                          'message': 'Configuration/connection/identity check failed; no credentials printed.'}))
        sys.exit(1)
