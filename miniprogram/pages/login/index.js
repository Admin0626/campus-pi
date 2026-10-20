const { request } = require("../../utils/request");
const { passwordValid } = require("../../utils/account");
Page({
  data: { account: "", password: "", busy: false, error: "" },
  onLoad(options) {
    if (options.account)
      this.setData({ account: decodeURIComponent(options.account) });
  },
  input(event) {
    this.setData({ [event.currentTarget.dataset.field]: event.detail.value });
  },
  async submit() {
    if (this.data.busy) return;
    const account = this.data.account.trim(),
      password = this.data.password;
    if (!account || !passwordValid(password)) {
      this.setData({ error: "请输入账号和12–64字符的密码" });
      return;
    }
    this.setData({ busy: true, error: "" });
    try {
      const result = await request("/auth/login", {
        method: "POST",
        body: { account, password },
      });
      const app = getApp();
      app.globalData.token = result.access_token;
      app.globalData.user = result.user;
      this.setData({ password: "" });
      wx.reLaunch({ url: "/pages/home/index" });
    } catch (error) {
      this.setData({ error: error.message });
    } finally {
      this.setData({ busy: false });
    }
  },
  register() {
    wx.navigateTo({ url: "/pages/register/index" });
  },
  forgot() {
    wx.navigateTo({
      url: "/pages/pending/index?name=" + encodeURIComponent("密码找回"),
    });
  },
});
