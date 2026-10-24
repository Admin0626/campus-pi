# 数据库基线与独立验收

首次在新电脑本地安装，请使用[单文件初始化SQL](local/init.sql)与[本地部署说明](../docs/deployment/本地部署说明.md)。它建立独立campus_pi_local库、全部40张应用表、本地管理员和基础分类，不带订单或测试资金。已有数据库使用增量迁移，下文为设计与历史验证资料。

原结构25张表、12个核心存储过程、4个触发器。SQL以MySQL 8.0.16以上为基线，历史验证环境为8.0.44。账号功能现已接入数据库，仍不在应用启动时自动执行DDL；A会话增量见migrations，独立本地工具及实际证据见账号功能启动说明。

## 新建空库

为本机开发或独立验收创建一个新的专用空库，字符集utf8mb4。确认连接目标后依次执行：

1. schema/01_数据库结构.sql。
2. transactions/02_核心业务事务.sql。

seeds/03_合成测试数据.sql仅是合成样例。手动查看或演示可在独立开发空库使用；若要运行自动验收，不要预先执行03，验收程序会自行生成数据。

初始脚本不适用于已有数据库自动迁移。后续变更写migrations，并登记顺序。不要清空、重新播种既有业务数据库。

## 自动数据库验收

先安装verification/requirements.txt中的Python依赖。准备私有MySQL客户端配置文件（host、port、user、password等）；放在仓库之外或.local中，不提交真实配置。使用已安装01和02的新测试空库：

```text
python -m pip install -r database/verification/requirements.txt
python database/verification/04_自动验收.py --mysql-exe <mysql客户端路径> --defaults-file <私有配置文件路径> --database <新的测试空库名>
```

该程序要求users为空，写入合成数据，输出当前运行的验证记录、SQL步骤和生成的种子到verification目录；这些运行产物已被忽略。原始可审阅种子在seeds中，不被程序覆盖。不要并行对同一个测试库重复运行。

本次仓库准备不创建数据库、不执行任何SQL。历史100项通过的原始证据在docs/verification/database，不能代替后续应用接口或生产验收。测试库的创建、权限和最终清理由使用者核对目标后管理。

## V009线下商品订单

线上支付接口已删除。已有库执行[migrations/V009__offline_product_trade.sql](migrations/V009__offline_product_trade.sql)一次，执行前备份并停止本项目服务；新安装local/init.sql已包含。保留历史付款字段与订单，不重置库存。详见[线下交易变更](../docs/api/线下交易接口变更.md)。基线01/02仅供历史验证，完整应用必须继续执行V001–V009。
