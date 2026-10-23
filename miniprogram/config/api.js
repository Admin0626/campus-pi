// Paths only. Environment hosts stay in the ignored local config.js.
module.exports = {
  register: "/auth/register", login: "/auth/login", logout: "/auth/logout",
  me: "/me", wechatConfig: "/auth/wechat/config",
  wechatLogin: "/auth/wechat/login", wechatBinding: "/me/wechat",
};
