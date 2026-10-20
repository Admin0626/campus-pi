const { request, requestKey } = require("../../utils/request");
const { passwordValid } = require("../../utils/account");
Page({
  data: {
    username: "",
    email: "",
    phone: "",
    revealed: false,
    statusHeight: 0,
    password: "",
    busy: false,
    error: "",
  },
  togglePassword() {
    this.setData({ revealed: !this.data.revealed });
  },
  unavailable(event) {
    wx.showToast({
      title: (event.currentTarget.dataset.name || "此功能") + "还在开发",
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
    this.setData({ [event.currentTarget.dataset.field]: event.detail.value });
  },
  onLoad() {
    this.setData({
      statusHeight: wx.getWindowInfo ? wx.getWindowInfo().statusBarHeight : 0,
    });
  },
  async submit() {
    if (this.data.busy) return;
    const value = { ...this.data },
      email = value.email.trim(),
      phone = value.phone.trim();
    if (!/^[A-Za-z][A-Za-z0-9_]{2,24}$/.test(value.username)) {
      this.setData({ error: "用户名以字母开头，使用3–25位字母、数字或下划线" });
      return;
    }
    if (
      (!email && !phone) ||
      (email &&
        (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) || email.length > 100)) ||
      (phone && !/^\+[1-9][0-9]{7,14}$/.test(phone))
    ) {
      this.setData({ error: "邮箱或手机号至少填写一项；手机号需带国家区号" });
      return;
    }
    if (!passwordValid(value.password)) {
      this.setData({ error: "密码需12–64字符" });
      return;
    }
    this.setData({ busy: true, error: "" });
    const body = {
      username: value.username,
      password: value.password,
      ...(email ? { email } : {}),
      ...(phone ? { phone } : {}),
    };
    try {
      const serialized = JSON.stringify(body);
      if (this._body !== serialized) {
        this._key = await requestKey();
        this._body = serialized;
      }
      await request("/auth/register", { method: "POST", body, key: this._key });
      this._body = "";
      this.setData({ password: "" });
      wx.reLaunch({
        url: "/pages/login/index?account=" + encodeURIComponent(value.username),
      });
      wx.showToast({ title: "注册成功，请登录", icon: "none" });
    } catch (error) {
      this.setData({ error: error.message });
    } finally {
      this.setData({ busy: false });
    }
  },
});
