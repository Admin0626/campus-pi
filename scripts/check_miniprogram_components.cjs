// Explicit offline wx host adapter. Does not verify WeChat rendering or login exchange.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const root = path.resolve(__dirname, ".."), checks = [];
const app = { globalData: { token: "old-session", user: { username: "synthetic" }, apiBaseUrl: "http://adapter.invalid" } };
let pages = [], requests = [], navigations = [], loginCalls = 0;
global.getApp = () => app;
global.getCurrentPages = () => pages;
global.wx = {
  request(options) { requests.push(options); },
  reLaunch(options) { navigations.push(options); },
  switchTab(options) { navigations.push(options); },
  showToast() {},
  login(options) { loginCalls++; options.success({ code: "synthetic_code_" + loginCalls }); },
};
function check(value, name) { assert.ok(value, name); checks.push({ name, status: "PASS" }); }
function loadComponent(file, properties = {}) {
  let definition;
  global.Component = value => { definition = value; };
  require(path.join(root, "miniprogram", file));
  return { ...definition, ...definition.methods, properties,
    data: structuredClone(definition.data || {}), events: [],
    setData(value) { Object.assign(this.data, value); },
    triggerEvent(name, detail) { this.events.push({ name, detail }); },
  };
}
function reply(request, status, data, code = "OK") {
  request.success({ statusCode: status, data: { code, data, message: "synthetic response" } });
}
async function flush() { await new Promise(resolve => setImmediate(resolve)); }
(async () => {
  const { request } = require(path.join(root, "miniprogram/utils/request"));
  const nav = require(path.join(root, "miniprogram/utils/navigation"));
  const field = loadComponent("components/auth-field/index.js", { name: "account", readonly: true, unavailableName: "验证码" });
  field.input({ detail: { value: "CampusUser" } });
  check(field.events[0].detail.field === "account" && field.events[0].detail.value === "CampusUser", "shared field preserves field name and input value");
  field.reveal(); field.unavailable();
  check(field.events[1].name === "reveal" && field.events[2].detail.name === "验证码", "shared password toggle and unavailable action are forwarded");
  const tabs = JSON.parse(fs.readFileSync(path.join(root, "miniprogram/app.json"), "utf8")).tabBar;
  check(tabs.custom && tabs.list.length === nav.tabs.length && tabs.list.every((tab, i) => "/" + tab.pagePath === nav.tabs[i].path), "custom navigation matches all native tab routes");
  await assert.rejects(nav.switchMain("/pages/login/index"), /无效/);
  check(navigations.length === 0, "ordinary page cannot be passed to switchTab");
  const vm = require("node:vm"), isolated = { exports: {} };
  let deadline;
  vm.runInNewContext(fs.readFileSync(path.join(root, "miniprogram/utils/navigation.js"), "utf8"), {
    module: isolated, wx: { switchTab() {} }, getCurrentPages: () => [],
    setTimeout(callback) { deadline = callback; return 1; }, clearTimeout() {},
  });
  const timed = isolated.exports.switchMain("/pages/home/index").catch(error => error);
  deadline();
  check((await timed).message.includes("跳转超时"), "missing native navigation callbacks cannot leave the caller pending forever");
  const bar = loadComponent("custom-tab-bar/index.js");
  pages = [{ route: "pages/me/index" }];bar.lifetimes.attached.call(bar);
  check(bar.data.selected === "/pages/me/index", "fresh tab component selects its actual page");
  const switching = bar.select({ currentTarget: { dataset: { route: "/pages/home/index" } } });
  await bar.select({ currentTarget: { dataset: { route: "/pages/home/index" } } });
  check(navigations.length === 1, "rapid repeated tab taps do not queue duplicate navigation");
  navigations.pop().fail();await switching;
  check(bar._navigating === false, "failed tab navigation releases its busy flag");
  nav.syncTab({ getTabBar: () => bar }, "/pages/me/index");
  check(bar.data.selected === "/pages/me/index", "page onShow synchronizes tab selection");
  const pending = request("/me").catch(error => error);
  const oldRequest = requests.pop();app.globalData.token = "new-session";
  reply(oldRequest, 401, null, "UNAUTHENTICATED");await pending;
  check(app.globalData.token === "new-session" && navigations.length === 0, "old 401 cannot erase a newer account session");
  const first = request("/me").catch(error => error), second = request("/me").catch(error => error);
  const one = requests.shift(), two = requests.shift();reply(one, 401, null, "UNAUTHENTICATED");reply(two, 401, null, "UNAUTHENTICATED");
  await Promise.all([first, second]);
  check(app.globalData.token === "" && app.globalData.user === null && navigations.length === 1, "concurrent expired requests clear identity and navigate once");
  navigations.pop().success();await flush();
  const wechat = require(path.join(root, "miniprogram/services/wechat"));
  let task = wechat.login().catch(error => error);reply(requests.shift(), 200, { available: false });
  check((await task).message.includes("尚未配置") && loginCalls === 0, "unconfigured service never invokes wx.login");
  task = wechat.login();reply(requests.shift(), 200, { available: true });await flush();
  const loginRequest = requests.shift();
  check(loginRequest.url.endsWith("/auth/wechat/login") && Object.keys(loginRequest.data).join() === "code", "wechat service submits only the fresh code to its backend");
  reply(loginRequest, 200, { access_token: "synthetic-result" });await task;
  task = wechat.login();reply(requests.shift(), 200, { available: true });await flush();
  const another = requests.shift();
  check(another.data.code !== loginRequest.data.code, "retry obtains a new one-use code");reply(another, 200, {});await task;
  app.globalData.token = "binding-account";
  wx.login = options => { app.globalData.token = "changed-account"; options.success({ code: "not_sent" }); };
  task = wechat.bind().catch(error => error);reply(requests.shift(), 200, { available: true });
  check((await task).message.includes("账号已变化") && requests.length === 0, "binding cannot silently follow an account switch during wx.login");
  wx.login = options => options.fail();
  task = wechat.login().catch(error => error);reply(requests.shift(), 200, { available: true });
  check((await task).message.includes("暂时不可用"), "wx.login failure completes with a visible error");
  const output = { status: "PASS", checks, scope: "Offline explicit wx host adapter", renderer_verified: false, real_wechat_exchange_verified: false };
  const target = path.join(root, ".local/auth-dev/wechat-automation/components-acceptance.json");fs.mkdirSync(path.dirname(target), { recursive: true });fs.writeFileSync(target, JSON.stringify(output, null, 2));
  console.log(JSON.stringify({ status: "PASS", checks: checks.length, renderer_verified: false }));
})().catch(error => { console.error(error); process.exitCode = 1; });
