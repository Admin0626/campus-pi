"""Apply B migration/config only to this task's verified private MySQL instance."""
from pathlib import Path
import argparse,json,re,sys
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'.local/pydeps'))
import pymysql
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--configure-local-testing',action='store_true',help='Explicitly grant the private local_admin test duties and synthetic campus only')
args=parser.parse_args()
c=json.loads((ROOT/'.local/auth-dev/private.json').read_text(encoding='utf-8'))
assert (c['host'],c['port'],c['database'])==('127.0.0.1',33320,'campus_pi_auth_local'),'Refusing any other database target'
wanted={'business_files','business_file_bindings','business_file_grants','approved_campus_registry'}
with pymysql.connect(host=c['host'],port=c['port'],user='root',password=c['root_password'],database=c['database'],charset='utf8mb4',autocommit=True) as db:
 with db.cursor() as cursor:
  cursor.execute('SELECT @@datadir,@@port');directory,port=cursor.fetchone()
  assert Path(directory).resolve()==(ROOT/'.local/auth-dev/mysql-data').resolve() and port==33320,'Refusing another MySQL instance'
  cursor.execute("SET time_zone='+00:00'")
  cursor.execute('SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=%s',(c['database'],));tables={row[0] for row in cursor.fetchall()}
  assert {'users','auth_sessions','auth_idempotency','auth_admin_guard'}<=tables,'Apply the account baseline first'
  assert not (tables&wanted) or wanted<=tables,'Partial B migration found; review it before retrying'
  if not tables&wanted:
   cursor.execute("SELECT COUNT(*) FROM (SELECT campus,student_no FROM users WHERE campus_verification='已通过' GROUP BY campus,student_no HAVING COUNT(*)>1) duplicate_keys")
   assert cursor.fetchone()[0]==0,'Existing approved campus/student duplicates require review; no rows modified'
   source=(ROOT/'database/migrations/V002__b_private_files.sql').read_text(encoding='utf-8')
   sql='\n'.join(line for line in source.splitlines() if not line.lstrip().startswith('--'))
   for statement in sql.split(';'):
    if statement.strip():cursor.execute(statement)
  cursor.execute('SELECT user_id FROM users WHERE username=%s AND role=%s',(c['admin_username'],'ADMIN'))
  admin=cursor.fetchone();assert admin is not None,'Private local administrator not found'
target=ROOT/'.local/admin-security/application-local.yml'
target.parent.mkdir(parents=True,exist_ok=True)
# JSON is valid YAML; keep this generated overlay separate from existing private auth settings.
config=json.loads(target.read_text(encoding='utf-8')) if target.exists() else {}
campus=config.setdefault('campus',{})
campus.setdefault('files',{}).update({'root':str(ROOT/'.local/auth-dev/business-files'),'public-base-url':'http://127.0.0.1:8080'})
if args.configure_local_testing:
 executables=sorted(Path('C:/ProgramData/Microsoft/Windows Defender/Platform').glob('*/MpCmdRun.exe'))
 assert executables,'No installed Defender CLI; do not substitute a fake clean scanner'
 campus['files'].update({'scanner-provider':'DEFENDER','scanner-executable':str(executables[-1]),'cleanup-interval-ms':2000})
 existing=campus.setdefault('admin',{}).get('duties-json')
 if existing is None:
  text=(ROOT/'.local/auth-dev/application-local.yml').read_text(encoding='utf-8')
  found=re.search(r'^\s{4}duties-json:\s*(.+)$',text,re.M)
  existing=json.loads(found.group(1)) if found else '{}'
 grants=json.loads(existing) if isinstance(existing,str) else existing
 uid=str(admin[0]);grants[uid]=sorted(set(grants.get(uid,[]))|{'AUDIT','VERIFICATION_REVIEW','ROLES','ORDERS','REPORTS'})
 campus['admin']['duties-json']=json.dumps(grants)
 campus.setdefault('verification',{}).setdefault('allowed-campuses-json',json.dumps(['测试校区'],ensure_ascii=False))
target.write_text(json.dumps(config,ensure_ascii=False,indent=2),encoding='utf-8')
print('B migration ready on verified private instance. Separate local security overlay written; no shared configuration or credentials printed.')
