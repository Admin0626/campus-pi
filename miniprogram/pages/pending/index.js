Page({
  data: { name: "此功能" },
  onLoad(options) {
    this.setData({
      name: options.name ? decodeURIComponent(options.name) : "此功能",
    });
  },
  back() {
    wx.navigateBack({
      delta: 1,
      fail() {
        wx.reLaunch({ url: "/pages/home/index" });
      },
    });
  },
});
