"""Apply only V008 to the verified isolated local database. No shared DB writes."""
from pathlib import Path
import json,sys
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'.local/pydeps'))
import pymysql
c=json.loads((ROOT/'.local/auth-dev/private.json').read_text(encoding='utf-8'))
assert (c['host'],c['port'],c['database'])==('127.0.0.1',33320,'campus_pi_auth_local')
with pymysql.connect(host=c['host'],port=c['port'],user='root',password=c['root_password'],database=c['database'],charset='utf8mb4',autocommit=True) as db,db.cursor() as cur:
 cur.execute('SELECT @@datadir,@@port')
 directory,port=cur.fetchone()
 assert Path(directory).resolve()==(ROOT/'.local/auth-dev/mysql-data').resolve() and port==33320
 cur.execute('SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=%s',(c['database'],))
 tables={row[0].lower() for row in cur.fetchall()}
 assert {'users','auth_sessions','auth_login_throttle','admin_batch_jobs','admin_batch_items'} <= tables
 if 'account_wechat_bindings' not in tables:
  source=(ROOT/'database/migrations/V008__a_wechat_bindings.sql').read_text(encoding='utf-8')
  source='\n'.join(line for line in source.splitlines() if not line.lstrip().startswith('--'))
  for statement in source.split(';'):
   if statement.strip():cur.execute(statement)
 cur.execute("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=%s AND TABLE_NAME='account_wechat_bindings'",(c['database'],))
 assert {row[0] for row in cur.fetchall()}=={'app_id','open_id','user_id','bound_at'}
 cur.execute('SHOW INDEX FROM account_wechat_bindings')
 indexes={row[2] for row in cur.fetchall()}
 assert {'PRIMARY','uq_wechat_account'} <= indexes
print('V008 ready in verified isolated MySQL. No shared DB or user account changed.')
