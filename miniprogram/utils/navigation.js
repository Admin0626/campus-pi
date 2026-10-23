const tabs = [
  { path: "/pages/home/index", text: "首页", icon: "⌂" },
  { path: "/pages/discover/index", text: "发现", icon: "◉" },
  { path: "/pages/publish/index", text: "发布", icon: "＋", publish: true },
  { path: "/pages/messages/index", text: "私信", icon: "☏" },
  { path: "/pages/me/index", text: "个人", icon: "♙" },
];
function navigate(method, options, message) {
  return new Promise((resolve, reject) => {
    let settled = false;
    const finish = (callback, value) => {
      if (settled) return;
      settled = true; clearTimeout(timer); callback(value);
    };
    const timer = setTimeout(() => finish(reject, new Error(message + "（页面跳转超时）")), 12000);
    try {
      wx[method]({ ...options, success: value => finish(resolve, value),
        fail: () => finish(reject, new Error(message)) });
    } catch (_) { finish(reject, new Error(message)); }
  });
}
function switchMain(path) {
  if (!tabs.some(tab => tab.path === path)) return Promise.reject(new Error("无效的主页面"));
  return navigate("switchTab", { url: path }, "切换页面失败，请重试");
}
function syncTab(page, path) {
  const bar = typeof page.getTabBar === "function" && page.getTabBar();
  if (bar) bar.setData({ selected: path });
}
function openPending(name) {
  return openPage("/pages/pending/index?name=" + encodeURIComponent(name || "此功能"));
}
function openPage(url) { return navigate("navigateTo", { url }, "打开页面失败，请重试"); }
let loginNavigation;
function goLogin() {
  if (loginNavigation) return loginNavigation;
  const pages = getCurrentPages();
  if (pages.length && pages[pages.length - 1].route === "pages/login/index") return Promise.resolve();
  loginNavigation = navigate("reLaunch", { url: "/pages/login/index" }, "返回登录页失败，请重试")
    .finally(() => { loginNavigation = null; });
  return loginNavigation;
}
function returnToLogin(account) {
  const pages = getCurrentPages();
  let index = -1;
  for (let i = pages.length - 1; i >= 0; i--) {
    if (pages[i].route === "pages/login/index") { index = i; break; }
  }
  if (index >= 0) {
    pages[index].setData({ ...(account ? { account } : {}), password: "", error: "", busy: false });
    const delta = pages.length - 1 - index;
    return delta ? navigate("navigateBack", { delta }, "返回登录页失败，请稍后重试") : Promise.resolve();
  }
  return navigate("reLaunch", { url: "/pages/login/index" + (account ? "?account=" + encodeURIComponent(account) : "") }, "返回登录页失败，请稍后重试");
}
module.exports = { tabs, switchMain, syncTab, openPage, openPending, goLogin, returnToLogin };
