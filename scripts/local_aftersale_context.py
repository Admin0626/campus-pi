"""Apply application-time context only on this task's verified private MySQL."""
from pathlib import Path
import json,sys
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'.local/pydeps'))
import pymysql
c=json.loads((ROOT/'.local/auth-dev/private.json').read_text(encoding='utf-8'))
assert (c['host'],c['port'],c['database'])==('127.0.0.1',33320,'campus_pi_auth_local')
with pymysql.connect(host=c['host'],port=c['port'],user='root',password=c['root_password'],database=c['database'],charset='utf8mb4',autocommit=True) as db:
 with db.cursor() as cur:
  cur.execute('SELECT @@datadir,@@port');directory,port=cur.fetchone()
  assert Path(directory).resolve()==(ROOT/'.local/auth-dev/mysql-data').resolve() and port==33320
  cur.execute('SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=%s',(c['database'],));tables={r[0] for r in cur.fetchall()}
  assert {'notification_outbox','business_file_bindings','business_number_sequences'}<=tables
  if 'aftersale_order_context' not in tables:
   source='\n'.join(line for line in (ROOT/'database/migrations/V006__g_aftersale_context.sql').read_text(encoding='utf-8').splitlines() if not line.lstrip().startswith('--'))
   for statement in source.split(';'):
    if statement.strip():cur.execute(statement)
  cur.execute("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=%s AND TABLE_NAME='aftersale_order_context'",(c['database'],))
  assert {r[0] for r in cur.fetchall()}=={'aftersale_id','order_id','order_type','original_order_status','captured_at'}
print('V006 verified only in private development MySQL; no legacy backfill or shared upgrade.')
