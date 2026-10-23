const api = require("../config/api");
const { request } = require("../utils/request");
function loginCode() {
  return new Promise((resolve, reject) => wx.login({
    timeout: 10000,
    success(result) {
      if (result.code) resolve(result.code);
      else reject(new Error("未取得微信登录凭证，请重新点击"));
    },
    fail() { reject(new Error("微信登录暂时不可用，请稍后重试")); },
  }));
}
async function withCode(path, binding = false) {
  const token = getApp().globalData.token;
  // A new one-use code for every attempt. Never cache a code or send an openid.
  const config = await request(api.wechatConfig);
  if (!config.available) throw new Error("微信登录尚未配置，请先使用账号密码登录");
  const code = await loginCode();
  if (binding && (!token || token !== getApp().globalData.token)) throw new Error("登录账号已变化，请重新打开绑定页");
  return request(path, { method: "POST", body: { code } });
}
module.exports = {
  status: () => request(api.wechatBinding),
  bind: () => withCode(api.wechatBinding, true),
  login: () => withCode(api.wechatLogin),
};
