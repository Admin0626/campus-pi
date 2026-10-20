const { guard } = require("../../utils/account");
const { request } = require("../../utils/request");
Page({
  data: { user: null, error: "", busy: false },
  async onShow() {
    if (!guard()) return;
    try {
      const user = await request("/me");
      getApp().globalData.user = user;
      this.setData({ user, error: "" });
    } catch (error) {
      this.setData({ error: error.message });
    }
  },
  async logout() {
    if (this.data.busy) return;
    this.setData({ busy: true, error: "" });
    try {
      await request("/auth/logout", { method: "POST" });
      getApp().globalData.token = "";
      getApp().globalData.user = null;
      wx.reLaunch({ url: "/pages/login/index" });
    } catch (error) {
      this.setData({ error: error.message });
    } finally {
      this.setData({ busy: false });
    }
  },
});
