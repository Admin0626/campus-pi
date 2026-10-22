"""Apply only V007 to the verified, isolated local acceptance database."""
from pathlib import Path
import json, sys
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'.local/pydeps'))
import pymysql
c=json.loads((ROOT/'.local/auth-dev/private.json').read_text(encoding='utf-8'))
assert (c['host'],c['port'],c['database'])==('127.0.0.1',33320,'campus_pi_auth_local')
with pymysql.connect(host=c['host'],port=c['port'],user='root',password=c['root_password'],database=c['database'],charset='utf8mb4',autocommit=True) as db, db.cursor() as cur:
    cur.execute('SELECT @@datadir,@@port')
    directory,port=cur.fetchone()
    assert Path(directory).resolve()==(ROOT/'.local/auth-dev/mysql-data').resolve() and port==33320
    cur.execute('SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=%s',(c['database'],))
    tables={row[0].lower() for row in cur.fetchall()}
    required={'users','auth_sessions','auth_idempotency','auth_admin_guard','notification_preferences','aftersale_order_context'}
    assert required <= tables, 'Earlier local account/notification migrations are missing'
    batch={'admin_batch_jobs','admin_batch_items'}
    if not batch & tables:
        for statement in (ROOT/'database/migrations/V007__admin_batch_operations.sql').read_text(encoding='utf-8').split(';'):
            if statement.strip():cur.execute(statement)
    else:
        assert batch <= tables, 'Partial V007 DDL exists. Inspect it before continuing; do not drop existing data'
    for table in batch:
        cur.execute('SELECT COUNT(*) FROM `'+table+'`')
        assert cur.fetchone()[0]>=0
print('V007 ready in verified isolated local MySQL. No shared database changed.')
