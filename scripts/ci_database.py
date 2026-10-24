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

def main():
    if os.environ.get('GITHUB_ACTIONS')!='true':
        raise SystemExit('Refusing to initialize a database outside the disposable GitHub Actions job.')
    with pymysql.connect(host='127.0.0.1',port=33320,user='root',password=os.environ['CAMPUS_CI_ROOT_PASSWORD'],database='campus_pi_ci',charset='utf8mb4',autocommit=True) as db,db.cursor() as cursor:
        cursor.execute('SELECT @@datadir,@@port,DATABASE()')
        directory,port,schema=cursor.fetchone()
        assert directory.startswith('/var/lib/mysql') and port==3306 and schema=='campus_pi_ci'
        count=initialize(cursor)
        print('Disposable CI schema initialized:',count,'tables; no seed data or shared database changed.')

if __name__=='__main__':main()
