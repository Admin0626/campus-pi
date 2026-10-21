const config = require("./config");
App({
  globalData: { apiBaseUrl: config.apiBaseUrl, token: "", user: null },
  onLaunch() {
    const device = wx.getDeviceInfo
      ? wx.getDeviceInfo()
      : wx.getSystemInfoSync();
    this.globalData.apiBaseUrl =
      device.platform === "devtools"
        ? config.devtoolsApiBaseUrl
        : config.apiBaseUrl;
    /* 小程序冷启动后重新登录；账号持久化方案待确认。 */
  },
});
