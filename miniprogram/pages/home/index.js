const { guard } = require("../../utils/account");
Page({
  data: {
    user: null,
    modules: [
      "商品与分类",
      "校园帖子",
      "悬赏任务",
      "任务验收与结算",
      "订单与售后",
      "私信与举报",
      "通知与投递",
    ],
  },
  onShow() {
    if (guard()) this.setData({ user: getApp().globalData.user });
  },
  me() {
    wx.navigateTo({ url: "/pages/me/index" });
  },
  pending(event) {
    wx.navigateTo({
      url:
        "/pages/pending/index?name=" +
        encodeURIComponent(event.currentTarget.dataset.name),
    });
  },
});
