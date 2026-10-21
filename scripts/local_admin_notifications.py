"""Apply I outbox migration only to the verified private development database."""
from pathlib import Path
import argparse,json,sys
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'.local/pydeps'))
import pymysql
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--configure-local-testing',action='store_true')
args=parser.parse_args()
c=json.loads((ROOT/'.local/auth-dev/private.json').read_text(encoding='utf-8'))
assert (c['host'],c['port'],c['database'])==('127.0.0.1',33320,'campus_pi_auth_local')
wanted={'notification_publish_jobs','notification_outbox'}
with pymysql.connect(host=c['host'],port=c['port'],user='root',password=c['root_password'],database=c['database'],charset='utf8mb4',autocommit=True) as db:
 with db.cursor() as cur:
  cur.execute('SELECT @@datadir,@@port');directory,port=cur.fetchone()
  assert Path(directory).resolve()==(ROOT/'.local/auth-dev/mysql-data').resolve() and port==33320
  cur.execute('SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=%s',(c['database'],));tables={r[0] for r in cur.fetchall()}
  assert {'business_number_sequences','notification_delivery','auth_admin_guard'}<=tables
  assert not tables&wanted or wanted<=tables,'Partial outbox migration requires review'
  if not tables&wanted:
   source='\n'.join(line for line in (ROOT/'database/migrations/V004__i_notification_outbox.sql').read_text(encoding='utf-8').splitlines() if not line.lstrip().startswith('--'))
   for statement in source.split(';'):
    if statement.strip():cur.execute(statement)
  cur.execute("SELECT TABLE_NAME,COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=%s AND ((TABLE_NAME='notification_preferences' AND COLUMN_NAME='config_version') OR (TABLE_NAME='notification_outbox' AND COLUMN_NAME IN ('available_at','preference_version')))",(c['database'],))
  upgraded=set(cur.fetchall());expected={('notification_preferences','config_version'),('notification_outbox','available_at'),('notification_outbox','preference_version')}
  assert not upgraded or upgraded==expected,'Partial V005 migration requires review'
  if not upgraded:
   source='\n'.join(line for line in (ROOT/'database/migrations/V005__i_notification_preferences.sql').read_text(encoding='utf-8').splitlines() if not line.lstrip().startswith('--'))
   for statement in source.split(';'):
    if statement.strip():cur.execute(statement)
  cur.execute('SELECT user_id FROM users WHERE username=%s AND role=%s',(c['admin_username'],'ADMIN'));admin=cur.fetchone();assert admin
if args.configure_local_testing:
 target=ROOT/'.local/admin-security/application-local.yml'
 config=json.loads(target.read_text(encoding='utf-8'));campus=config.setdefault('campus',{})
 grants=json.loads(campus.setdefault('admin',{})['duties-json']);uid=str(admin[0]);grants[uid]=sorted(set(grants.get(uid,[]))|{'NOTIFICATION_PUBLISH'})
 campus['admin']['duties-json']=json.dumps(grants)
 # Worker starts explicitly via command-line during acceptance. Do not replace personal settings.
 target.write_text(json.dumps(config,ensure_ascii=False,indent=2),encoding='utf-8')
print('V004/V005 ready only on verified private MySQL; shared database and personal WeChat configuration unchanged.')
