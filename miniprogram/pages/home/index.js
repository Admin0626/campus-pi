const { guard } = require("../../utils/account");
const { syncTab, switchMain, openPending, openPage } = require("../../utils/navigation");
const {request}=require("../../utils/request");
Page({
  data: { user: null, statusHeight: 0,posts:[],products:[],loading:false,error:"" },
  onLoad() { this.setData({ statusHeight: wx.getWindowInfo ? wx.getWindowInfo().statusBarHeight : 0 }); },
  async onShow() {
    syncTab(this, "/pages/home/index");
    if(!guard())return;
    const token=getApp().globalData.token;this.setData({user:getApp().globalData.user,loading:true,error:''});
    const results=await Promise.allSettled([request('/posts?page_size=3'),request('/products?page_size=3')]);
    if(token!==getApp().globalData.token)return;
    this.setData({loading:false,posts:results[0].status==='fulfilled'?results[0].value.items:[],products:results[1].status==='fulfilled'?results[1].value.items:[],error:results.filter(r=>r.status==='rejected').map(r=>r.reason.message).join('；')});
  },
  business(event){return openPage("/pages/business/index?module="+event.currentTarget.dataset.module).catch(error=>this.showError(error));},
  me() { return switchMain("/pages/me/index").catch(error => this.showError(error)); },
  pending(event) { return openPending(event.currentTarget.dataset.name).catch(error => this.showError(error)); },
  showError(error) { wx.showToast({ title: error.message, icon: "none" }); },
});
