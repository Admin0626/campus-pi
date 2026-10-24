# 校园Π启动步骤

更新日期：2026-10-24。先导入数据库、填写后端配置，再启动服务。

|目录 / 文件|用途|
|---|---|
|`backend/`|Spring Boot 后端，Java 17，默认端口 8080|
|`frontend/`|Vue 前端，包含用户端和管理后台；开发端口 5173|
|`miniprogram/`|微信原生小程序，导入微信开发者工具运行|
|[database/init.sql](database/init.sql)|首次安装的完整数据库导入脚本|
|`database/migrations/`|已有数据库的增量升级 SQL|
|[scripts/start.ps1](scripts/start.ps1)|Windows PowerShell 启动脚本|
|[后端配置说明](config/后端配置说明.md)|数据库、文件、通知、权限及微信配置|

## 1. 准备环境

安装 Git、JDK **17**、Maven **3.6.3+**、Node.js **22.12+** 和 MySQL **8.0.16+**。以下命令在 Windows PowerShell 执行；路径按本机安装位置修改。

```powershell
Set-Location D:\demo_series\campus-pi
$env:JAVA_HOME = 'D:\Java\jdk-17.0.12'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version
mvn -version
node -v
mysql --version
```

Java 和 Maven 都应显示 Java 17。MySQL 服务需事先启动；项目不会自动安装 MySQL 或修改 root 密码。

## 2. 导入数据库

**首次安装只导入 `database/init.sql`。** 它已包含基础结构和 V001–V009，导入后有 40 张应用表。

```powershell
cmd /d /c "mysql --default-character-set=utf8mb4 -h127.0.0.1 -P3306 -uroot -p < database\init.sql"
```

提示输入的是自己的 MySQL root 密码。也可用支持 `DELIMITER` 的 Navicat 执行整个文件，开启遇错停止。脚本创建 `campus_pi_local`；同名库已存在会报错，**不要删库重试、不要使用 `--force`**。已有库只执行确认尚未应用的增量迁移；V009 将商品订单切换为线下交易，先备份、停后端，再执行一次，不能重跑初始化脚本。

初始化只创建本地管理员 `admin / 123456`、五条基础分类及零余额账户；商品、任务、订单为空是正常结果。该短密码只适用于初始化账号与本地配置，普通账号密码仍要求 12–64 位。

为应用创建专用数据库账号，先进入 MySQL：

```powershell
mysql -h127.0.0.1 -P3306 -uroot -p
```

然后执行下面的 SQL，将密码替换为自己设置的数据库应用密码：

```sql
CREATE USER 'campus_pi_app'@'127.0.0.1' IDENTIFIED BY '请替换成自己的数据库应用密码';
GRANT SELECT, INSERT, UPDATE, DELETE ON campus_pi_local.* TO 'campus_pi_app'@'127.0.0.1';
EXIT;
```

## 3. 填写后端配置

后端配置统一放在 `backend/src/main/resources/`：

|文件|用途|
|---|---|
|`application.example.yml`|配置模板，供复制使用|
|`application.yml`|实际使用的本机配置，只修改这一份；Git 忽略，不放进 JAR|

新电脑首次复制模板；已有 `application.yml` 时直接编辑原文件：

```powershell
if (Test-Path backend/src/main/resources/application.yml) { throw '配置已存在，请直接编辑原文件' }
Copy-Item backend/src/main/resources/application.example.yml backend/src/main/resources/application.yml
```

直接在 `application.yml` 的 `spring.datasource.username`、`spring.datasource.password` 中填写 MySQL 账号和密码，再按[后端配置说明](config/后端配置说明.md)填写稳定随机密钥、文件目录和扫描器路径。无需配置 `DB_USER`、`DB_PASSWORD` 环境变量。

使用 IDEA 时，选择 JDK 17，直接运行 `CampusPiApplication.main()`。如果之前添加过 `spring.config.additional-location` 程序参数，删除旧参数；正常运行无需额外指定配置文件。首次复制后或修改配置后，重新构建并重启后端。

## 4. 启动 Web

### 方式 A：统一启动，适合演示

```powershell
powershell -ExecutionPolicy Bypass -File scripts/start.ps1
```

脚本依次安装锁定的前端依赖、构建 Vue、打包 Java，然后在当前终端运行后端。Maven 打包会跳过测试，不代表测试通过。首次构建需联网下载依赖。保持终端开启，访问：

|地址|用途|
|---|---|
|http://127.0.0.1:8080/login|用户登录|
|http://127.0.0.1:8080/register|普通账号注册|
|http://127.0.0.1:8080/admin/login|管理员登录，本地 `admin / 123456`|

前端会打入 JAR，此模式无需另开 5173。仅复用上一次构建结果时加 `-SkipBuild`；修改代码后应重新完整构建。重打包前先在原终端按 Ctrl+C 停止旧 JAR，避免 Windows 文件占用。

### 方式 B：前后端分开启动，适合改页面

在仓库根目录打开两个 PowerShell 终端：

```powershell
# 终端一：后端
powershell -ExecutionPolicy Bypass -File scripts/start.ps1 -Mode backend
```

```powershell
# 终端二：Vue，首次会安装依赖
powershell -ExecutionPolicy Bypass -File scripts/start.ps1 -Mode frontend
```

此模式访问 `http://127.0.0.1:5173/login` 或 `/admin/login`，Vue 将 `/api` 和 `/file-content` 代理到后端 8080。两种方式选一种，避免重复占用同一端口。每个服务在所在终端按 Ctrl+C 停止；MySQL 独立运行。

只检查本机命令和配置文件是否具备、不构建或启动服务：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/start.ps1 -Check
```

脚本默认读取上述 `application.yml`；打包运行时也从这个路径读取，不会将实际配置放进 JAR。`-Check` 不校验配置值、数据库连接或端口占用。

## 5. 启动微信小程序

1. 先启动上述 Spring Boot 后端。
2. 首次复制本机配置；已有文件直接编辑，避免覆盖。

```powershell
if (Test-Path miniprogram/project.config.json) { throw '小程序项目配置已存在，请直接编辑' }
Copy-Item miniprogram/project.config.example.json miniprogram/project.config.json
if (Test-Path miniprogram/config.js) { throw '小程序接口配置已存在，请直接编辑' }
Copy-Item miniprogram/config.example.js miniprogram/config.js
```

3. 在本机 `project.config.json` 填自己的 AppID；AppSecret 只放后端私有配置。
4. 将本机 `miniprogram/config.js` 设置为开发者工具联调地址：

```javascript
module.exports = {
  apiBaseUrl: "",
  devtoolsApiBaseUrl: "http://127.0.0.1:8080"
};
```

5. 微信开发者工具导入 **`miniprogram/`** 目录，核对 AppID 后编译。仅本机 HTTP 调试时，在工具的本地设置中勾选“不校验合法域名、web-view 域名、TLS 版本及 HTTPS 证书”。小程序无需运行 Vue，也不连接 MySQL。
6. 使用校园Π账号密码登录；真实微信换码需另配后端。采用先密码登录、再主动绑定微信的流程。

`apiBaseUrl` 供真机使用，留空会显示“服务地址尚未配置”。手机上的 `127.0.0.1` 指向手机自身。真机需可访问的 HTTPS 后端、微信合法域名及相应监听/文件地址配置，具体见[后端配置说明](config/后端配置说明.md)。当前本机启动步骤不代表真机或正式部署已验收。

## 6. 常见启动问题

|现象|检查方法|
|---|---|
|Java 版本错误|设置 `JAVA_HOME` 为 JDK 17，再打开终端运行脚本|
|找不到 mysql、mvn、node|将相应程序的 bin 目录加入 PATH|
|数据库连接失败|检查 MySQL 服务、端口、库名、应用账号密码及私有配置路径|
|账号服务未完成本地配置|填写至少 32 字符的稳定 `idempotency-secret`|
|admin 登录失败|确认使用本文新建库及 `local-short-password-admin-id: 1`|
|文件上传失败|检查私有文件目录和实际 Defender 扫描器路径|
|通知或批量任务不执行|确认对应 worker 已启用|
|5173 接口失败|确认后端运行；修改端口时同时修改 `frontend/.env.local` 的 `CAMPUS_API_TARGET`|
|端口占用或 JAR 被占用|停止原终端中本项目的旧进程，不结束其他 Java 项目|

首次数据库没有测试资金；商品采用线下自提，平台不代收款。分析图表目前使用 JSON 模拟数据。短信找回、真实微信换码和完整真机流程仍需相应外部配置与验收。

如需人工测试数据，可在专用测试环境运行 `python -m pip install -r scripts/requirements-auth-local.txt`，再运行 `python scripts/build_manual_test_sql.py`。生成的 SQL、用例和图片位于被忽略的 `.local/manual-test/generated/`；只用于独立 `campus_pi_manual_test` 库，不能导入上述日常库。普通首次部署无需 Python、无需这批数据。
