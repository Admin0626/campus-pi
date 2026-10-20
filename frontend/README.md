# 校园Π Web 前端

Vue 3 + Vite。npm ci后用npm run dev启动，npm run build构建。Node.js至少22.12，推荐团队统一24 LTS。

当前已提供真实接口登录、注册、个人资料、改密、管理工作台与用户管理页面；其余业务菜单为“还在开发”占位。界面以用户最后提供的《软件工程校园Π最终版.doc》原图为基准：紫色手机账号页、城市横幅与橙色后台登录、深色管理侧栏；仓库补画SVG不作为本轮视觉参照，已移除原型体验身份入口。A负责账号与请求，D继续协调公共布局，其余成员按原页面分工接入。

/api由Vite开发代理转发到真实后端。完整构建时Maven会将dist打包为Spring Boot静态资源；也可由正式反向代理提供同源访问。当前令牌仅保存在标签页内存，刷新重新登录，未决定长期持久化方式。

手机端另见 miniprogram 原生接入样例，不以Web手机排版代替微信小程序。真实AppID、域名和真机验收仍待确认。接口验证与启动方法见仓库 docs/development/账号功能本地启动.md。

# 前端启动
```bash
#npm ci
npm run dev
```
# 后续上线时添加环境配置
```bash
npm run build
```

原图归属、对应页面及尚未实现的原图控件见 [Word界面对照](../docs/verification/Word界面对照.md)。后台独立登录入口为 `/admin/login`。
