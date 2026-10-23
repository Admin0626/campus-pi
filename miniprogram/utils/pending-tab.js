const { guard } = require("./account");
const { syncTab, switchMain } = require("./navigation");
module.exports = function pendingTab(route, title, message) {
  return {
    data: { title, message },
    onShow() { syncTab(this, route); guard(); },
    home() { return switchMain("/pages/home/index").catch(error => wx.showToast({ title: error.message, icon: "none" })); },
  };
};
