function length(value) {
  return Array.from(value).length;
}
function passwordValid(value) {
  return length(value) >= 12 && length(value) <= 64;
}
function guard() {
  if (!getApp().globalData.token) {
    wx.reLaunch({ url: "/pages/login/index" });
    return false;
  }
  return true;
}
module.exports = { passwordValid, guard };
