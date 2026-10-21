# 校园Π campus-pi

校园二手商品、悬赏任务、校园帖子、私信和站内通知项目。九位成员按业务模块全栈开发，每人负责本人模块的用户端页面、管理端页面、后端、数据库增量变更及联调测试。

当前账号模块已接入真实 Spring Boot + MySQL。管理后台按完整41项管理接口继续实现，用户、认证、内容、分类、概览审计、任务、订单查询取消和举报处理加上通知草稿、撤回、投递查询和重试累计40项P0已接入；剩余真实售后审核API099。通知已通过实际发布、持久投递和Web收件闭环验收，按关闭不接收、免打扰后投递、安全同样遵守的规则执行。详见[完整实施清单](docs/development/管理后台全功能实施清单.md)、[认证与文件配置](docs/development/管理认证与文件服务.md)、[任务奖励](docs/development/管理任务审核与奖励结算.md)、[订单与举报](docs/development/管理订单与举报处理.md)、[通知与持久投递](docs/development/管理通知草稿与持久投递.md)。正式职责与校区仍需配置，现金仅合成账本测试且默认关闭，真实退款审核返回501；头像资料修改和P1找回未接入，其他未接入业务继续显示开发中。

手机端已有原生小程序目录，AppID已提供，开发者工具本机8080地址已确认；真实微信网络账号链路已有通过记录，页面跳转与真机验收尚未完成。

先看[账号功能启动说明](docs/development/账号功能本地启动.md)、[当前验证记录](docs/verification/账号功能验证记录.md)和[待确认事项](docs/development/待确认事项.md)。112项接口契约及历史100项数据库测试保留为设计基线，不代表全项目或生产验收。

## 先读这些材料

- [九人全栈分工与接口索引](docs/allocation/校园Π九人全栈分工说明.md)
- [页面归属与组件交接](docs/allocation/页面归属与联调验收.md)
- [协作边界及统一验收](docs/allocation/协作边界与统一验收.md)
- [接口规范](docs/api/校园Π接口开发规范.md) / [Word版](docs/api/校园Π接口开发规范.docx) / [OpenAPI](docs/api/campus-pi-openapi.json)
- [设计报告修订版](docs/design/校园Π软件工程设计报告修订版.docx)
- [开发与合并规范](CONTRIBUTING.md) / [本地环境与启动](docs/development/本地开发指南.md)
- [数据库初始化与验证](database/README.md)

## 技术栈与环境

已确认Spring Boot + Vue。基础工程固定Spring Boot 3.5.16、Java 17、Vue 3.5.44、Vite 8.3.4；前端依赖版本由package-lock.json锁定。使用JDK 17、Maven 3.6.3以上、Node.js 22.12以上（本机验证使用24.13.0）。数据库使用MySQL，现有结构要求8.0.16以上；Python 3.10以上用于静态基线检查及独立数据库验收。

服务不自动执行DDL。账号功能需先显式配置数据库、安装原结构与A迁移、提供稳定密钥；本机可使用独立开发库工具。敏感职责与其余业务模块仍按九人任务书接入。

## 快速启动

先确认`java -version`和`mvn -version`实际使用JDK 17。先按账号功能启动说明初始化独立开发库并加载外部配置；Web开发时分别运行：

```text
cd backend
mvn spring-boot:run
```

```text
cd frontend
npm ci
npm run dev
```

浏览器开发入口`http://localhost:5173`，`/api`代理到后端；完整打包后可直接访问`http://127.0.0.1:8080/login`和`/admin`。独立开发库工具生成本机专用管理员，随机密码只保存在被忽略的`.local/auth-dev/管理员登录.txt`，不提供共享默认管理员密码。未接入业务页为占位，未知API仍返回404。

## 本地检查

```text
python scripts/check_baseline.py
```

后端在backend目录运行`mvn test`，前端在frontend目录运行`npm run build`。数据库验收单独在新测试空库中进行，见database/README.md。

## 九人职责

|选项|模块|接口数|负责人|
|---|---|---:|---|
|A|账号登录与用户权限；公共请求与鉴权|13|待认领|
|B|认证审核、文件与审计|8|待认领|
|C|商品、收藏、分类和审核|16|待认领|
|D|帖子与管理概览；公共布局导航|13|待认领|
|E|任务发布、接取与分配|12|待认领|
|F|任务凭证、验收、奖励结算|8|待认领|
|G|订单、支付与售后；迁移顺序汇总|16|待认领|
|H|私信会话与举报|11|待认领|
|I|通知、偏好与投递|15|待认领|

P0共104项；P1共8项，分别是A的2项找回密码和G的6项交易扩展。P1依赖模型、通道和配置齐备后另行验收。

## 目录

```text
frontend/       Vue基础工程及模块接入说明
miniprogram/    微信小程序原生接入；AppID与工具本机地址已配置；真机验收待完成
backend/        Spring Boot基础工程及模块接入说明
database/       结构、事务、合成数据、增量迁移、独立验收
docs/design/    设计报告及可编辑SVG图源
docs/api/       唯一接口契约
docs/allocation/九人任务、页面归属和接口责任索引
docs/development/开发环境及技术决定
docs/verification/历史验证证据与本次仓库检查边界
scripts/        静态检查、独立数据库工具与HTTP/浏览器验证
```

历史Git命令参考和测试文件保留；多人开发流程以CONTRIBUTING.md为准。先在功能分支开发并提出PR，审核后合并main。仓库内的规则文本不代表GitHub分支保护已配置。

当前账号界面以用户最后提供的原Word图为视觉基准，含 `/admin/login` 独立后台登录。对应页、原图资产校验与未完成控件见 [Word界面对照](docs/verification/Word界面对照.md)。

数据可视化现提供 [JSON模拟页面](docs/development/数据可视化页面.md)，管理员侧栏“数据分析”可进入。模拟图表不代表真实统计接口已实现。
