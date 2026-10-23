const { guard } = require("../../utils/account");
const wechat = require("../../services/wechat");
Page({
  data: { username: "", loading: true, busy: false, available: false, bound: false, error: "" },
  onLoad() { this._unloaded = false; },
  onUnload() { this._unloaded = true; },
  async onShow() {
    if (!guard()) return;
    this.setData({ username: getApp().globalData.user.username, loading: true, error: "" });
    const token = getApp().globalData.token;
    try {
      const result = await wechat.status();
      if (!this._unloaded && token === getApp().globalData.token) this.setData(result);
    } catch (error) {
      if (!this._unloaded) this.setData({ error: error.message });
    } finally {
      if (!this._unloaded) this.setData({ loading: false });
    }
  },
  async bind() {
    if (this.data.busy || this.data.loading || this.data.bound || !this.data.available || !guard()) return;
    this.setData({ busy: true, error: "" });
    const token = getApp().globalData.token;
    try {
      const result = await wechat.bind();
      if (!this._unloaded && token === getApp().globalData.token) this.setData({ bound: result.bound });
    } catch (error) {
      if (!this._unloaded) this.setData({ error: error.message });
    } finally {
      if (!this._unloaded) this.setData({ busy: false });
    }
  },
});
