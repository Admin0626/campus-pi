// Tests native page logic against the real backend using an explicit wx host adapter.
// This is not a WeChat renderer, DevTools compile, domain validation, or device test.
const fs = require("node:fs"),
  path = require("node:path"),
  crypto = require("node:crypto"),
  assert = require("node:assert/strict");
const root = path.resolve(__dirname, ".."),
  checks = [];
let lastNavigation = "";
const app = {
  globalData: { apiBaseUrl: "http://127.0.0.1:8080", token: "", user: null },
};
global.getApp = () => app;
global.wx = {
  request(options) {
    fetch(options.url, {
      method: options.method,
      headers: options.header,
      body:
        options.data === undefined ? undefined : JSON.stringify(options.data),
    })
      .then(async (res) =>
        options.success({ statusCode: res.status, data: await res.json() }),
      )
      .catch(options.fail);
  },
  getRandomValues(options) {
    const bytes = crypto.randomBytes(options.length);
    options.success({
      randomValues: bytes.buffer.slice(
        bytes.byteOffset,
        bytes.byteOffset + bytes.byteLength,
      ),
    });
  },
  reLaunch(options) {
    lastNavigation = options.url;
  },
  navigateTo(options) {
    lastNavigation = options.url;
  },
  navigateBack() {
    lastNavigation = "back";
  },
  showToast() {},
};
function page(name) {
  let definition;
  global.Page = (value) => (definition = value);
  require(path.join(root, "miniprogram/pages", name, "index.js"));
  const instance = {
    ...definition,
    data: JSON.parse(JSON.stringify(definition.data)),
    setData(value) {
      Object.assign(this.data, value);
    },
  };
  return instance;
}
function check(v, name) {
  assert.ok(v, name);
  checks.push({ name, passed: true });
}
(async () => {
  const config = JSON.parse(
    fs.readFileSync(path.join(root, "miniprogram/app.json"), "utf8"),
  );
  for (const route of config.pages) {
    check(
      fs.existsSync(path.join(root, "miniprogram", route + ".js")) &&
        fs.existsSync(path.join(root, "miniprogram", route + ".wxml")) &&
        fs.existsSync(path.join(root, "miniprogram", route + ".json")),
      route + " files complete",
    );
  }
  const register = page("register"),
    username = "wechat_" + Date.now().toString(36),
    password = "WeChatCampus2026!";
  await register.submit();
  check(register.data.error.includes("用户名"), "native username validation");
  register.setData({
    username,
    email: username + "@example.invalid",
    phone: "",
    password,
  });
  await register.submit();
  check(
    lastNavigation.startsWith("/pages/login/index?account="),
    "native registration reaches real backend and returns to login",
  );
  check(app.globalData.token === "", "native registration does not auto login");
  const login = page("login");
  login.setData({ account: username, password: "WrongPassword2026!" });
  await login.submit();
  check(
    login.data.error.includes("账号或密码错误"),
    "native login displays real backend error",
  );
  login.setData({ password });
  await login.submit();
  check(
    lastNavigation === "/pages/home/index" &&
      app.globalData.token.length === 43,
    "native login receives real opaque token",
  );
  const home = page("home");
  home.onShow();
  check(
    home.data.user.username === username,
    "native home uses current identity",
  );
  const me = page("me");
  await me.onShow();
  check(
    me.data.user.username === username &&
      me.data.user.email === username + "@example.invalid",
    "native me reads persisted backend profile",
  );
  await me.logout();
  check(
    app.globalData.token === "" && lastNavigation === "/pages/login/index",
    "native logout revokes real token and clears identity",
  );
  app.globalData.apiBaseUrl = "";
  login.setData({ password });
  await login.submit();
  check(
    login.data.error.includes("服务地址尚未配置"),
    "missing deployment config fails clearly",
  );
  fs.writeFileSync(
    path.join(root, ".local/auth-dev/miniprogram-adapter.json"),
    JSON.stringify(
      {
        status: "PASS",
        scope:
          "Node wx host adapter + real HTTP only; NOT WeChat runtime acceptance",
        checks,
      },
      null,
      2,
    ),
  );
  console.log(
    JSON.stringify({
      status: "PASS",
      checks: checks.length,
      WeChatRuntimeVerified: false,
    }),
  );
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
