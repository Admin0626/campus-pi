const accounts = require("../../services/account");
const wechat = require("../../services/wechat");
const session = require("../../utils/session");
const { switchMain, openPage, openPending } = require("../../utils/navigation");
const { passwordValid } = require("../../utils/account");
Page({
  data: {
    account: "",
    password: "",
    busy: false,
    error: "",
    revealed: false,
    statusHeight: 0,
  },
  onLoad(options) {
    this._unloaded = false;
    this.setData({
      statusHeight: wx.getWindowInfo ? wx.getWindowInfo().statusBarHeight : 0,
    });
    if (options.account)
      this.setData({ account: decodeURIComponent(options.account) });
  },
  onUnload() { this._unloaded = true; },
  togglePassword() {
    this.setData({ revealed: !this.data.revealed });
  },
  unavailable(event) {
    wx.showToast({
      title: ((event.detail && event.detail.name) || event.currentTarget.dataset.name || "此功能") + "还在开发",
      icon: "none",
    });
  },
  dismiss() {
    this.setData({ error: "" });
  },
  back() {
    wx.navigateBack();
  },
  input(event) {
    this.setData({ [event.detail.field || event.currentTarget.dataset.field]: event.detail.value });
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
      const result = await accounts.login({ account, password });
      if (this._unloaded) return;
      session.set(result);
      this.setData({ password: "" });
      await switchMain("/pages/home/index");
    } catch (error) {
      if (!this._unloaded) this.setData({ error: error.message });
    } finally {
      if (!this._unloaded) this.setData({ busy: false });
    }
  },
  async wechatLogin() {
    if (this.data.busy) return;
    this.setData({ busy: true, error: "" });
    try {
      const result = await wechat.login();
      if (this._unloaded) return;
      session.set(result);
      this.setData({ password: "" });
      await switchMain("/pages/home/index");
    } catch (error) {
      if (!this._unloaded) this.setData({ error: error.message });
    } finally {
      if (!this._unloaded) this.setData({ busy: false });
    }
  },
  register() {
    return openPage("/pages/register/index").catch(error => {
      if (!this._unloaded) this.setData({ error: error.message });
    });
  },
  forgot() {
    return openPending("密码找回").catch(error => {
      if (!this._unloaded) this.setData({ error: error.message });
    });
  },
});
