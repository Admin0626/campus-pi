const { guard } = require("../../utils/account");
const { request } = require("../../utils/request");
Page({
  data: { user: null, statusHeight: 0, error: "", busy: false },
  onLoad() {
    this.setData({
      statusHeight: wx.getWindowInfo ? wx.getWindowInfo().statusBarHeight : 0,
    });
  },
  noop() {},
  home() {
    wx.reLaunch({ url: "/pages/home/index" });
  },
  pending(event) {
    wx.navigateTo({
      url:
        "/pages/pending/index?name=" +
        encodeURIComponent(event.currentTarget.dataset.name),
    });
  },
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
