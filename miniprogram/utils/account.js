const { goLogin } = require("./navigation");
function passwordValid(value) { const size = Array.from(value).length; return size >= 12 && size <= 64; }
function guard() {
  if (getApp().globalData.token) return true;
  goLogin().catch(error => wx.showToast({ title: error.message, icon: "none" }));
  return false;
}
module.exports = { passwordValid, guard };
