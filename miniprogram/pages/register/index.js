const { request, requestKey } = require("../../utils/request");
const { passwordValid } = require("../../utils/account");
Page({
  data: {
    username: "",
    contactType: "email",
    contact: "",
    password: "",
    confirm: "",
    busy: false,
    error: "",
  },
  input(event) {
    this.setData({ [event.currentTarget.dataset.field]: event.detail.value });
  },
  contactType(event) {
    if (!this.data.busy)
      this.setData({
        contactType: event.currentTarget.dataset.type,
        contact: "",
      });
  },
  async submit() {
    if (this.data.busy) return;
    const value = { ...this.data },
      contact = value.contact.trim();
    if (!/^[A-Za-z][A-Za-z0-9_]{2,24}$/.test(value.username)) {
      this.setData({ error: "用户名以字母开头，使用3–25位字母、数字或下划线" });
      return;
    }
    if (
      value.contactType === "email"
        ? !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(contact) || contact.length > 100
        : !/^\+[1-9][0-9]{7,14}$/.test(contact)
    ) {
      this.setData({ error: "请填写有效邮箱或带国家区号的手机号" });
      return;
    }
    if (!passwordValid(value.password) || value.password !== value.confirm) {
      this.setData({ error: "密码需12–64字符，且两次输入一致" });
      return;
    }
    this.setData({ busy: true, error: "" });
    const body = {
      username: value.username,
      password: value.password,
      [value.contactType]: contact,
    };
    try {
      const serialized = JSON.stringify(body);
      if (this._body !== serialized) {
        this._key = await requestKey();
        this._body = serialized;
      }
      await request("/auth/register", { method: "POST", body, key: this._key });
      this._body = "";
      this.setData({ password: "", confirm: "" });
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
