function request(path, options = {}) {
  const app = getApp(),
    base = app.globalData.apiBaseUrl;
  if (!base)
    return Promise.reject(
      new Error("服务地址尚未配置，请先确认小程序联调环境"),
    );
  const header = { Accept: "application/json" };
  if (options.body !== undefined) header["Content-Type"] = "application/json";
  if (app.globalData.token)
    header.Authorization = "Bearer " + app.globalData.token;
  if (options.key) header["Idempotency-Key"] = options.key;
  return new Promise((resolve, reject) =>
    wx.request({
      url: base.replace(/\/$/, "") + "/api/v1" + path,
      method: options.method || "GET",
      data: options.body,
      header,
      timeout: 15000,
      success(result) {
        if (
          result.statusCode >= 200 &&
          result.statusCode < 300 &&
          result.data &&
          result.data.code === "OK"
        )
          return resolve(result.data.data);
        if (result.statusCode === 401 && !path.startsWith("/auth/")) {
          app.globalData.token = "";
          app.globalData.user = null;
          wx.reLaunch({ url: "/pages/login/index" });
        }
        reject(
          new Error(
            (result.data && result.data.message) || "服务请求失败，请稍后重试",
          ),
        );
      },
      fail() {
        reject(new Error("无法连接服务，请检查网络或联调配置"));
      },
    }),
  );
}
function requestKey() {
  // WeChat 原生安全随机数，不能把账号或密码作为幂等标识。
  return new Promise((resolve, reject) =>
    wx.getRandomValues({
      length: 16,
      success(result) {
        resolve(
          Array.from(new Uint8Array(result.randomValues))
            .map((n) => n.toString(16).padStart(2, "0"))
            .join(""),
        );
      },
      fail() {
        reject(new Error("无法生成请求标识，请升级微信后重试"));
      },
    }),
  );
}
module.exports = { request, requestKey };
