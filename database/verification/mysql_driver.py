from pathlib import Path
import argparse,subprocess
parser=argparse.ArgumentParser(description="仅在已安装01和02的新测试空库运行")
parser.add_argument('--mysql-exe',required=True)
parser.add_argument('--defaults-file',required=True)
parser.add_argument('--database',required=True)
args=parser.parse_args()
ROOT=Path(__file__).resolve().parent
MYSQL=args.mysql_exe
CONF=Path(args.defaults_file).resolve()
DB=args.database
def run(sql,database=True,check=True):
    cmd=[MYSQL,f'--defaults-extra-file={CONF.as_posix()}','--default-character-set=utf8mb4','--batch','--raw','--skip-column-names']
    if database:cmd.append(DB)
    r=subprocess.run(cmd,input=sql.encode('utf-8'),stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    if check and r.returncode:raise RuntimeError(r.stderr.decode('utf-8','replace'))
    return r
if run('SELECT COUNT(*) FROM users;').stdout.decode().strip()!='0':
    raise RuntimeError('拒绝执行：users非空，请指定新建且已安装结构及过程的测试空库')
