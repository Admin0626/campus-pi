"""Start a private development MySQL instance; never connect to the user's MySQL service."""
from pathlib import Path
import json, os, re, secrets, socket, subprocess, sys, time

ROOT = Path(__file__).resolve().parents[1]
LOCAL = ROOT / '.local' / 'auth-dev'
sys.path.insert(0, str(ROOT / '.local' / 'pydeps'))
import pymysql
from argon2 import PasswordHasher

MYSQLD = Path(os.environ.get('CAMPUS_MYSQLD', r'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqld.exe'))
LOCAL.mkdir(parents=True, exist_ok=True)
DATA = (LOCAL / 'mysql-data').resolve()
assert DATA.is_relative_to(LOCAL.resolve())
CONFIG = LOCAL / 'private.json'
if CONFIG.exists():
    config = json.loads(CONFIG.read_text(encoding='utf-8'))
else:
    config = dict(host='127.0.0.1', port=33320, database='campus_pi_auth_local', user='campus_auth_app', password=secrets.token_urlsafe(30), root_password=secrets.token_urlsafe(30), idempotency_secret=secrets.token_hex(32), admin_username='local_admin', admin_password=secrets.token_urlsafe(20))
    CONFIG.write_text(json.dumps(config, indent=2), encoding='utf-8')
assert config['host']=='127.0.0.1' and config['port']==33320 and config['database']=='campus_pi_auth_local', 'Refusing a changed database target'

def connection(root=False, database=False, empty_password=False):
    return pymysql.connect(host=config['host'], port=config['port'], user='root' if root else config['user'], password='' if empty_password else config['root_password'] if root else config['password'], database=config['database'] if database else None, charset='utf8mb4', autocommit=True, connect_timeout=2)

running = False
try:
    with socket.create_connection((config['host'], config['port']), timeout=1): running = True
except OSError: pass
if not running:
    if not (DATA / 'mysql').exists():
        DATA.mkdir(parents=True, exist_ok=True)
        initialized = subprocess.run([str(MYSQLD), '--no-defaults', '--initialize-insecure', '--basedir='+str(MYSQLD.parent.parent), '--datadir='+str(DATA)], capture_output=True, creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        if initialized.returncode: raise RuntimeError('Private MySQL initialization failed; see local diagnostics')
    log = (LOCAL / 'mysql.log').open('ab')
    process = subprocess.Popen([str(MYSQLD), '--no-defaults', '--basedir='+str(MYSQLD.parent.parent), '--datadir='+str(DATA), '--port='+str(config['port']), '--bind-address=127.0.0.1', '--mysqlx=0', '--console'], stdout=log, stderr=log, creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
    for attempt in range(100):
        try:
            with socket.create_connection((config['host'], config['port']), timeout=.3): break
        except OSError:
            if process.poll() is not None: raise RuntimeError('Private MySQL exited; see .local/auth-dev/mysql.log')
            time.sleep(.2)
    (LOCAL / 'mysql-process.json').write_text(json.dumps({'pid': process.pid, 'port': config['port'], 'datadir': str(DATA)}), encoding='utf-8')
try:
    conn=connection(root=True)
except pymysql.err.OperationalError:
    conn=connection(root=True, empty_password=True)
    with conn.cursor() as cursor:
        cursor.execute('SELECT @@datadir,@@port')
        candidate_dir,candidate_port=cursor.fetchone()
        assert Path(candidate_dir).resolve()==DATA and candidate_port==33320, 'Refusing to change another MySQL instance'
        cursor.execute("ALTER USER 'root'@'localhost' IDENTIFIED BY %s", (config['root_password'],))

with conn, conn.cursor() as cursor:
    cursor.execute('SELECT @@datadir,@@port')
    datadir, port=cursor.fetchone()
    assert Path(datadir).resolve()==DATA and port==config['port'], 'Refusing an unexpected MySQL instance'
    cursor.execute('SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME=%s', (config['database'],))
    fresh=cursor.fetchone() is None
    if fresh: cursor.execute('CREATE DATABASE `'+config['database']+'` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci')
    cursor.execute('USE `'+config['database']+'`')
    cursor.execute("SET time_zone='+00:00'")
    if fresh:
        for statement in (ROOT/'database/schema/01_数据库结构.sql').read_text(encoding='utf-8').split(';'):
            if statement.strip(): cursor.execute(statement)
    cursor.execute("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=%s AND TABLE_NAME='users' AND COLUMN_NAME='auth_version'", (config['database'],))
    migrated=cursor.fetchone() is not None
    if not migrated:
        for statement in (ROOT/'database/migrations/V001__a_account_sessions.sql').read_text(encoding='utf-8').split(';'):
            if statement.strip(): cursor.execute(statement)
    cursor.execute('SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=%s',(config['database'],))
    tables={row[0] for row in cursor.fetchall()}
    assert {'users','reward_accounts','admin_audit_log','auth_sessions','auth_login_throttle','auth_idempotency','auth_admin_guard'} <= tables, 'Local migration incomplete; review the isolated database before retrying'
    cursor.execute('SELECT user_id FROM users WHERE username=%s', (config['admin_username'],))
    if cursor.fetchone() is None:
        hashed=PasswordHasher(time_cost=3,memory_cost=65536,parallelism=1).hash(config['admin_password'])
        conn.begin()
        try:
            cursor.execute("INSERT INTO users(username,password_hash,email,role,privacy_settings) VALUES(%s,%s,'local-admin@example.invalid','ADMIN',JSON_OBJECT('show_campus',false,'show_personal_bio',false))",(config['admin_username'],hashed))
            admin_id=cursor.lastrowid
            cursor.execute("INSERT INTO reward_accounts(user_id,reward_type) VALUES(%s,'积分'),(%s,'现金')",(admin_id,admin_id))
            cursor.execute("INSERT INTO admin_audit_log(operator_id,object_type,object_id,action_name,reason,after_data) VALUES(%s,'USER',%s,'LOCAL_BOOTSTRAP','独立本地开发库初始化',JSON_OBJECT('role','ADMIN'))",(admin_id,str(admin_id)))
            conn.commit()
        except Exception:
            conn.rollback()
            raise
    cursor.execute("CREATE USER IF NOT EXISTS 'campus_auth_app'@'localhost' IDENTIFIED BY %s", (config['password'],))
    cursor.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON `"+config['database']+"`.* TO 'campus_auth_app'@'localhost'")

# Runtime configuration is private and ignored by Git; passwords are never printed.
private_yml=LOCAL/'application-local.yml'
private_yml.write_text('spring:\n  datasource:\n    url: '+json.dumps('jdbc:mysql://127.0.0.1:33320/'+config['database']+'?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&allowPublicKeyRetrieval=true&sslMode=DISABLED')+'\n    username: '+json.dumps(config['user'])+'\n    password: '+json.dumps(config['password'])+'\ncampus:\n  auth:\n    idempotency-secret: '+json.dumps(config['idempotency_secret'])+'\n',encoding='utf-8')
(LOCAL/'管理员登录.txt').write_text('仅限本机独立开发库，勿提交或用于生产。\n地址：http://127.0.0.1:8080/login\n账号：'+config['admin_username']+'\n密码：'+config['admin_password']+'\n',encoding='utf-8')
local_short_id = int(config.get('local_short_password_admin_id', 0))
if local_short_id > 0:
    with private_yml.open('a', encoding='utf-8') as private_file:
        private_file.write('    local-short-password-admin-id: '+str(local_short_id)+'\n')
print('Private MySQL ready on 127.0.0.1:33320; 25 baseline tables + A migration; local admin credentials saved under .local/auth-dev. No existing service changed.')
