function set(result) {
  const app = getApp();
  app.globalData.token = result.access_token;
  app.globalData.user = result.user;
}
function clear() {
  const app = getApp();
  app.globalData.token = "";
  app.globalData.user = null;
}
module.exports = { set, clear };
