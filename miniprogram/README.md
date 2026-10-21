# 微信小程序接入样例

这是原生小程序目录，不是把手机浏览器页面嵌入 web-view。已准备登录、注册、校园生活、本人账号和开发中占位页，共用已有 `/api/v1` 账号接口。原生目录用于验证接入方式，团队最终采用原生还是 uni-app 仍列入待确认事项。

## 尚未具备的验收条件

- 用户已提供 AppID `YOUR_LOCAL_APPID`，已配置到模板及本机项目；主体与当前微信账号开发权限尚待验证。配置中不包含AppSecret。
- 上线 HTTPS 地址及 request 合法域名尚未确认，`config.js` 默认服务地址为空，明确报“尚未配置”。
- 已验证 JS 语法与宿主适配器调用真实后端；5个WXML页面与7个WXSS也通过本机官方独立编译器。尚未完成带真实AppID的开发者工具运行验收，不等于真机登录或发布验收。
- 本机发现已安装微信开发者工具；未绕过账号、AppID 或域名校验。上述条件齐备后继续在工具和真机里验证。

## 配置后导入

1. 模板已填入负责人提供的AppID `YOUR_LOCAL_APPID`；本机 `project.config.json` 已生成并被Git忽略。其他成员可复制模板创建本机配置。不要把 AppSecret 放进小程序。
2. 在 `config.js` 设置经确认的 API 主机地址，不包含 `/api/v1` 后缀。发布环境使用团队配置的 HTTPS 主机。
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
