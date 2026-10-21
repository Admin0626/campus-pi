# 微信小程序接入样例

这是原生小程序目录，不是把手机浏览器页面嵌入 web-view。已准备登录、注册、校园生活、本人账号和开发中占位页，共用已有 `/api/v1` 账号接口。原生目录用于验证接入方式，团队最终采用原生还是 uni-app 仍列入待确认事项。

## 尚未具备的验收条件

- 实际AppID和接口地址仅保存在被Git忽略的本机配置；共享模板保持空值。主体与当前微信账号开发权限尚待验证，不在仓库中记录个人AppID或AppSecret。
- 上线 HTTPS 地址及 request 合法域名尚未确认。负责人已确认开发者工具使用本机`http://127.0.0.1:8080`；`app.js`按`platform === devtools`选择此地址，真机服务地址仍为空，明确报“尚未配置”。
- 已验证 JS 语法与宿主适配器调用真实后端；5个WXML页面与7个WXSS也通过本机官方独立编译器。尚未完成带真实AppID的开发者工具运行验收，不等于真机登录或发布验收。
- 本机发现已安装微信开发者工具；未绕过账号、AppID 或域名校验。上述条件齐备后继续在工具和真机里验证。

## 配置后导入

1. 复制`project.config.example.json`为`project.config.json`，只在这个被Git忽略的本机文件填写实际AppID；共享模板留空。不要把AppSecret放进小程序、文档或提交记录。
2. 复制`config.example.js`为被Git忽略的`config.js`，仅在本机填写API地址；共享模板的两个地址均留空。开发者工具与真机分别使用各自的地址。仅本机HTTP联调可在被忽略的`project.private.config.json`关闭URL校验；共享模板保持true。发布/真机须配置经确认的HTTPS地址及合法域名。
3. 微信开发者工具导入本目录，核对所选 AppID 与 request 合法域名。开发工具与手机真机访问的网络环境不同，本机 `127.0.0.1` 不是手机上的后端。
4. 注册后返回登录，不自动登录。登录取得服务端不透明令牌，后续请求附带 Bearer；冷启动后重新登录，尚未决定长期存储方式。
5. 验收注册校验、重复提交、登录错误、本人信息、退出、过期及服务不可用，再做真机检查。

未新增微信一键登录、手机号授权或现有账号自动绑定。这些业务规则与服务端换码接口需要先确认。

## 当前证据

`scripts/check_miniprogram_adapter.cjs` 使用明确的 Node 宿主适配器检查页面逻辑，并调用真实 Spring Boot + MySQL。它不能验证 WXML/WXSS 在微信中的显示、request 域名校验、基础库兼容或设备交互。

`scripts/check_wechat_compile.py` 调用已安装微信开发者工具附带的 `wcc.exe`、`wcsc.exe`，编译5个WXML和7个WXSS，且检查生成JS语法。结果位于`.local/auth-dev/wechat-compile/compile-acceptance.json`。此检查无需AppID，但只证明该版本独立编译器接受模板与样式；没有模拟完整微信客户端。

接口形状已对照本机微信开发者工具附带的 `lib.wx.api.d.ts`；网页文档抓取失败，不能将未读取的网页当作已核验依据。后续可查阅 [微信网络能力说明](https://developers.weixin.qq.com/miniprogram/dev/framework/ability/network.html)。

登录/注册、首页与我的页面已按原Word图5-1、5-4、5-18调整。保留真实账号接口，短信、第三方与未完成业务提示开发中；原图中的系统状态栏由微信宿主提供。Web截图不能证明此目录在真机上显示一致。对应说明见 [Word界面对照](../docs/verification/Word界面对照.md)。

2026-10-21本机验证：官方CLI `islogin`返回true，已用提供的AppID通过 `open --project` 打开工程；官方独立编译仍通过5个WXML和7个WXSS。项目开发权限、API地址与真机流程尚未验收。

## 真实开发者工具连接检查

2026-10-21（北京时间）：官方CLI已使用该AppID开启自动化；真实连接返回工具版本`2.01.2510280`、基础库`3.17.3`、路由`pages/login/index`，模拟器窗口390×844。App服务实际选中本机8080，并通过真实`wx.request`收到后端401错误响应。页面数据通道与截图请求超时，因此尚不能据此判断视觉渲染或真机成功。

可重复检查：将`miniprogram-automator@0.13.0`安装到被忽略的`.local/auth-dev/wechat-automation`，执行官方CLI `auto --project <仓库绝对路径>/miniprogram --auto-port 9420`，再在仓库根目录运行`node scripts/check_wechat_runtime.cjs`。也可用`WECHAT_AUTOMATOR_MODULE`指定已安装模块的绝对路径。脚本只读取连接、路由、运行环境和数据通道是否可用，不保存表单值、不发业务请求；失败返回非零，证据保存在`.local/auth-dev/wechat-automation/runtime-acceptance.json`。此工具依赖只供本机验收，不是小程序或生产运行依赖。

`node scripts/check_wechat_accounts.cjs`则调用真实页面方法和`wx.request`，会在独立本地库创建随机测试账号。当前验证了原生表单校验及后端注册成功，但返回登录页收到`reLaunch:fail timeout`，脚本如实返回非零，不能记为完整流程通过。已将成功提示移到跳转成功回调，并在失败时显示“注册已成功，返回登录页失败”，避免静默停留。该脚本没有用tap或截图验证视觉交互；凭证不写入证据文件。

09:10补充：通过真实`wx.request`顺序验证注册、登录、本人查询、退出及已退出令牌被401拒绝，五个网络步骤均通过。此检查在App服务执行，未替代页面跳转。完整账号探针仍因注册后路由超时而非零退出，`network_flow_verified`与`page_method_flow_verified`分别记录，避免把网络通过写成页面通过。已尝试清理本工程编译缓存，未清账号、存储或认证缓存；页面数据通道仍超时。最小无业务页面的查询亦未正常返回，但该对照未完成启动验收，不能据此确定根因。
