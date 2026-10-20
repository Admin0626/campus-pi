# 校园Π后端

Spring Boot 3.5.16、Java 17、Maven。账号、会话与管理员用户管理已接入真实 MySQL；其他模块仍按112项契约逐步实现。当前范围与证据见仓库 docs/verification/账号功能验证记录.md。

先读 docs/development/账号功能本地启动.md：数据库迁移显式执行、密钥从外部配置载入，应用不自动建表。先构建 frontend，再用 mvn clean verify 将页面打包进 JAR，管理端和登录页可由8080同源访问。原112项OpenAPI保留设计基线元数据，当前实现状态单独记录。

令牌为不透明随机Bearer，数据库只存摘要，2小时绝对/30分钟闲置过期。角色调整默认关闭，P1找回通道未接入。mvn test中的原404测试不代表业务验收，真实HTTP检查由 scripts/check_auth_http.py 在隔离开发库完成。

不要将已有存储过程再包装在外层未提交事务中；数据和事务规则见../database/README.md及模块任务书。
