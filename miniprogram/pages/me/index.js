const { guard } = require("../../utils/account");
const accounts = require("../../services/account");
const {request}=require("../../utils/request");
const session = require("../../utils/session");
const { syncTab, switchMain, openPage, openPending, goLogin } = require("../../utils/navigation");
Page({
  data: { counts:["—","—","—"], user: null, statusHeight: 0, error: "", busy: false },
  onLoad() { this._unloaded = false; this.setData({ statusHeight: wx.getWindowInfo ? wx.getWindowInfo().statusBarHeight : 0 }); },
  onUnload() { this._unloaded = true; },
  business(event){return openPage("/pages/business/index?module="+event.currentTarget.dataset.module).catch(error=>this.setData({error:error.message}));},
  home() { return switchMain("/pages/home/index"); },
  pending(event) { return openPending(event.currentTarget.dataset.name).catch(error => this.setData({ error: error.message })); },
  wechatBinding() {
    return openPage("/pages/wechat-binding/index").catch(error => {
      if (!this._unloaded) this.setData({ error: error.message });
    });
  },
  async onShow() {
    syncTab(this, "/pages/me/index");
    if (!guard()) { this.setData({ user: null }); return; }
    const token = getApp().globalData.token;
    try {
      const user = await accounts.me();
      if (this._unloaded || getApp().globalData.token !== token) return;
      getApp().globalData.user = user;
      this.setData({ user, error: "" });
      const results=await Promise.allSettled(['/me/products','/me/posts','/me/tasks'].map(p=>request(p+'?page_size=1')));
      if(!this._unloaded&&getApp().globalData.token===token)this.setData({counts:results.map(r=>r.status==='fulfilled'?r.value.total:'—')});
    } catch (error) {
      if (!this._unloaded && getApp().globalData.token === token) this.setData({ error: error.message });
    }
  },
  async logout() {
    if (this.data.busy) return;
    this.setData({ busy: true, error: "" });
    try {
      await accounts.logout();
      session.clear();
      this.setData({ user: null });
      await goLogin();
    } catch (error) {
      if (!this._unloaded) this.setData({ error: error.message });
    } finally {
      if (!this._unloaded) this.setData({ busy: false });
    }
  },
});
