# 前端基础工程

Vue 3 + Vite。npm ci后用npm run dev启动，npm run build构建。Node.js至少22.12，推荐团队统一24 LTS。

当前只提供开发起始页，用户端和管理端业务页面均待开发。业务代码放src/modules，公共组件放src/shared，布局放src/layouts。A负责请求与登录权限基础，D负责公共布局导航；其余成员完成自己模块页面和真实接口联调。页面归属见../docs/allocation/页面归属与联调验收.md。

/api由Vite开发代理转发到后端，当前业务接口尚未实现。部署时需另配置反向代理或同源托管，Vite开发代理不是生产部署方案。

# 前端启动
```bash
#npm ci
npm run dev
```
# 后续上线时添加环境配置
```bash
npm run build
```
