"""Install/manual-seed a dedicated loopback database and materialize its synthetic PNGs.
The config must be private JSON: host, port, user, password, database=campus_pi_manual_test.
Never resets an existing populated database or changes the ordinary local application's config.
"""
from pathlib import Path
import argparse, hashlib, json, os, secrets, uuid
import pymysql
from ci_database import initialize, statements

ROOT=Path(__file__).resolve().parents[1]
SCHEMA='campus_pi_manual_test'
LOCAL=ROOT/'.local/manual-test'

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--config',type=Path,required=True);args=parser.parse_args()
    c=json.loads(args.config.read_text(encoding='utf-8'))
    if c.get('host') not in ('127.0.0.1','localhost') or c.get('database')!=SCHEMA:raise SystemExit('Only the fixed dedicated loopback manual schema is allowed')
    LOCAL.mkdir(parents=True,exist_ok=True)
    with pymysql.connect(host=c['host'],port=int(c.get('port',3306)),user=c['user'],password=c['password'],charset='utf8mb4',autocommit=True) as db,db.cursor() as cur:
        cur.execute('SELECT @@port,@@datadir');server_port,directory=cur.fetchone()
        cur.execute('CREATE DATABASE IF NOT EXISTS '+SCHEMA+' CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci');cur.execute('USE '+SCHEMA)
        cur.execute('SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=%s',(SCHEMA,));tables=cur.fetchone()[0]
        if tables==0:tables=initialize(cur)
        else:
            cur.execute('SELECT COUNT(*) FROM users')
            if cur.fetchone()[0]:raise SystemExit('Refusing populated manual database. Existing data and files preserved.')
            cur.execute("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=%s AND TABLE_NAME='user_orders' AND COLUMN_NAME='trade_channel'",(SCHEMA,))
            if cur.fetchone()[0]!=1:raise SystemExit('Apply all V001-V009 migrations first; existing data is preserved')
        for sql in statements((ROOT/'database/manual/校园Π全功能人工测试.sql').read_text(encoding='utf-8')):
            cur.execute(sql)
            while cur.nextset():pass
        cur.execute('SELECT file_id,content,sha256 FROM manual_test_assets');assets=cur.fetchall()
        target=(LOCAL/'files/objects').resolve();target.mkdir(parents=True,exist_ok=True)
        if not target.is_relative_to(LOCAL.resolve()) or target.is_symlink():raise RuntimeError('Unsafe manual file directory')
        for fid,data,digest in assets:
            if str(uuid.UUID(fid))!=fid or hashlib.sha256(data).hexdigest()!=digest:raise RuntimeError('Invalid fixture asset')
            p=target/(fid+'.blob')
            if p.is_symlink() or not p.resolve().is_relative_to(target):raise RuntimeError('Unsafe fixture file target')
            if p.exists() and p.read_bytes()!=data:raise RuntimeError('Refusing to overwrite a different fixture file')
            p.write_bytes(data)
        cur.execute('SELECT COUNT(*) FROM users');users=cur.fetchone()[0]
        cur.execute('SELECT COUNT(*) FROM manual_test_cases');cases=cur.fetchone()[0]
    secret_path=LOCAL/'session-secret.txt'
    if not secret_path.exists():secret_path.write_text(secrets.token_urlsafe(48),encoding='utf-8')
    defender=sorted(Path(os.environ.get('ProgramData','C:/ProgramData')).glob('Microsoft/Windows Defender/Platform/*/MpCmdRun.exe'),reverse=True)
    scanner=str(defender[0]) if defender else ''
    duties=json.dumps({'900001':['ROLES','AUDIT','VERIFICATION_REVIEW','ORDERS','REPORTS','NOTIFICATION_PUBLISH']},separators=(',',':'))
    # JSON is valid YAML and avoids accidental scalar/escaping changes in generated private config.
    config={'server':{'port':8082,'address':'127.0.0.1'},'spring':{'datasource':{'url':f"jdbc:mysql://127.0.0.1:{c.get('port',3306)}/{SCHEMA}?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&allowPublicKeyRetrieval=true&sslMode=DISABLED",'username':c['user'],'password':c['password']}},'campus':{'auth':{'idempotency-secret':secret_path.read_text().strip(),'local-short-password-admin-id':900001},'admin':{'duties-json':duties},'verification':{'allowed-campuses-json':json.dumps(['测试校区','测试分校区'],ensure_ascii=False)},'files':{'root':str(LOCAL/'files'),'public-base-url':'http://127.0.0.1:8082','scanner-provider':'DEFENDER' if scanner else '', 'scanner-executable':scanner},'notifications':{'worker-enabled':False},'admin-batches':{'worker-enabled':True},'wechat':{'enabled':False}}}
    (LOCAL/'application-local.yml').write_text(json.dumps(config,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    summary={'status':'PASS','database':SCHEMA,'port':server_port,'synthetic_users':users,'manual_cases':cases,'materialized_pngs':len(assets),'scan_attestation':'TEST_FIXTURE - seeded history only, actual uploads use configured scanner','wechat_identity_seeded':False,'payment_verified':False,'existing_application_config_changed':False}
    (LOCAL/'setup-result.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(summary,ensure_ascii=False))

if __name__=='__main__':main()
