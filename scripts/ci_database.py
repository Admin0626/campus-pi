"""Initialize only the fresh, disposable GitHub Actions MySQL service. Never a local/shared DB."""
from pathlib import Path
import os
import pymysql

ROOT=Path(__file__).resolve().parents[1]
def statements(source):
    delimiter=';'
    pending=''
    for line in source.splitlines():
        if line.lstrip().startswith('--'):
            continue
        if line.strip().upper().startswith('DELIMITER '):
            assert not pending.strip(), 'Unexpected pending SQL at delimiter change'
            delimiter=line.strip().split(None,1)[1]
            continue
        pending+=line+'\n'
        while delimiter in pending:
            statement,pending=pending.split(delimiter,1)
            if statement.strip():yield statement
    assert not pending.strip(), 'Unterminated SQL'

def initialize(cursor):
    """Caller must verify its isolated server, schema, and datadir before this function."""
    cursor.execute('SELECT DATABASE()')
    schema=cursor.fetchone()[0]
    cursor.execute('SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=%s',(schema,))
    assert cursor.fetchone()[0]==0, 'Refusing to overwrite an existing schema'
    cursor.execute("SET time_zone='+00:00'")
    sources=[ROOT/'database/schema.sql',*sorted((ROOT/'database/migrations').glob('V*.sql'))]
    for source in sources:
        for statement in statements(source.read_text(encoding='utf-8')):cursor.execute(statement)
    cursor.execute('SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=%s',(schema,))
    return cursor.fetchone()[0]


def verify_local_demo(cursor):
    """Verify the small demo and refusal of an existing schema, without changing rows."""
    from argon2 import PasswordHasher
    cursor.execute('SELECT DATABASE()')
    assert cursor.fetchone()[0]=='campus_pi_local'
    cursor.execute('SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()')
    assert cursor.fetchone()[0]==40
    counts={}
    for table,expected in [('users',4),('product_info',3),('post_info',2),('RewardTasks',2),
                           ('user_orders',2),('system_notifications',1),('t_chat_session',1),
                           ('t_private_msg',2),('category',5)]:
        cursor.execute('SELECT COUNT(*) FROM `'+table+'`')
        counts[table]=cursor.fetchone()[0]
        assert counts[table]==expected,(table,counts[table])
    for name,password in [('admin','123456'),('student01','CampusPi!2026'),
                           ('student02','CampusPi!2026'),('visitor01','CampusPi!2026')]:
        cursor.execute('SELECT password_hash FROM users WHERE username=%s',(name,))
        assert PasswordHasher().verify(cursor.fetchone()[0],password)
    cursor.execute("SELECT available_amount,frozen_amount FROM reward_accounts WHERE user_id=2 AND reward_type='积分'")
    assert tuple(cursor.fetchone())==(75,25)
    cursor.execute("SELECT SUM(reward_amount) FROM RewardTasks WHERE publisher_id=2 AND task_status IN ('待审核','待接取')")
    assert cursor.fetchone()[0]==25
    cursor.execute('SELECT SUM(frozen_delta) FROM reward_ledger WHERE user_id=2')
    assert cursor.fetchone()[0]==25
    cursor.execute("SELECT SUM(available_amount+frozen_amount) FROM reward_accounts WHERE reward_type='现金'")
    assert cursor.fetchone()[0]==0
    for table in ['account_wechat_bindings','business_files','auth_sessions','auth_login_throttle','auth_idempotency']:
        cursor.execute('SELECT COUNT(*) FROM `'+table+'`')
        assert cursor.fetchone()[0]==0,table
    cursor.execute("SELECT COUNT(*) FROM user_orders WHERE trade_channel='OFFLINE' AND actual_amount=0 AND payment_time IS NULL")
    assert cursor.fetchone()[0]==2
    cursor.execute("SELECT COUNT(*) FROM users WHERE user_id IN (2,3) AND campus_verification='已通过'")
    assert cursor.fetchone()[0]==2
    cursor.execute("SELECT COUNT(*) FROM notification_publish_jobs WHERE job_status<>'已完成'")
    assert cursor.fetchone()[0]==0
    cursor.execute("SELECT COUNT(*) FROM notification_outbox WHERE work_status<>'已完成'")
    assert cursor.fetchone()[0]==0
    try:
        cursor.execute('CREATE DATABASE `campus_pi_local` CHARACTER SET utf8mb4')
    except pymysql.MySQLError as error:
        assert error.args[0]==1007
    else:
        raise AssertionError('Repeated installer must refuse the existing database')
    return counts


def main():
    if os.environ.get('GITHUB_ACTIONS')!='true':
        raise SystemExit('Refusing to initialize a database outside the disposable GitHub Actions job.')
    with pymysql.connect(host='127.0.0.1',port=33320,user='root',password=os.environ['CAMPUS_CI_ROOT_PASSWORD'],database='campus_pi_ci',charset='utf8mb4',autocommit=True) as db,db.cursor() as cursor:
        cursor.execute('SELECT @@datadir,@@port,DATABASE()')
        directory,port,schema=cursor.fetchone()
        assert directory.startswith('/var/lib/mysql') and port==3306 and schema=='campus_pi_ci'
        count=initialize(cursor)
        print('Disposable CI schema initialized:',count,'tables; no seed data or shared database changed.')
        # This fresh second schema exists only inside the same disposable CI container.
        for statement in statements((ROOT/'database/init.sql').read_text(encoding='utf-8')):
            cursor.execute(statement)
            while cursor.nextset():pass
        counts=verify_local_demo(cursor)
        cursor.execute('USE campus_pi_ci')
        print('Fresh local installer verified:',counts,'; repeat import refused.')

if __name__=='__main__':main()
