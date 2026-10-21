"""Prepare the shared number counter only on the verified private development DB."""
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
  assert {'auth_admin_guard','business_files','approved_campus_registry'}<=tables,'Apply V001/V002 first'
  if 'business_number_sequences' not in tables:
   sql='\n'.join(line for line in (ROOT/'database/migrations/V003__business_numbers.sql').read_text(encoding='utf-8').splitlines() if not line.lstrip().startswith('--'))
   for statement in sql.split(';'):
    if statement.strip():cur.execute(statement)
  cur.execute("SELECT counter_value FROM business_number_sequences WHERE number_type='ORDER'")
  row=cur.fetchone();assert row is not None and 0<=row[0]<=99999999999999,'Review an incomplete or invalid number-counter migration'
print('V003 ready on the verified private MySQL instance; shared deployment not modified.')
