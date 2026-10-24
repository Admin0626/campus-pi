# 数据库：只选一个入口

|你的场景|使用什么|
|---|---|
|第一次本地部署|直接运行[init.sql](init.sql)一次；具体命令见[本地部署说明](../docs/deployment/本地部署说明.md)|
|升级已有数据库|备份并停止服务，按[migrations登记](migrations/README.md)只执行尚未应用的版本|
|需要演示账号和测试数据|按[人工测试数据手册](../docs/verification/人工测试数据手册.md)生成并安装到独立测试库|

```text
database/
  README.md       使用入口
  init.sql        首次本地安装，一份SQL即可
  schema.sql      基础结构和设计过程源文件，供生成器与CI使用
  migrations/     已有数据库的逐版本升级文件
```

初次安装的init.sql已包含全部40张应用表、12个存储过程、4个触发器及V001–V009，初始化本地管理员和基础分类，不带普通用户、订单或测试资金。它会拒绝同名已存在数据库；已有库不能重新导入初始化SQL。

日常使用不需要手动执行schema.sql或挨个导入迁移。维护结构时修改基础源文件或新增迁移，再运行：

```powershell
python -m pip install -r scripts/requirements-auth-local.txt
python scripts/build_local_init_sql.py
python scripts/build_local_init_sql.py --check
```

大份人工测试SQL、用例矩阵及PNG样例由`scripts/build_manual_test_sql.py`按需生成到被Git忽略的`.local/manual-test/generated/`，不再重复上传。生成资料不会修改本机数据库。

原01建表与02事务脚本已原样合并为schema.sql。旧合成种子和独立验收工具已退出当前目录，仍可从[整理前的Git历史](https://github.com/Admin0626/campus-pi/tree/3af293d61b9723d6f4773f110cfa030340c74342/database)查看；旧报告是历史证据，不代表当前版本已再次验收。
