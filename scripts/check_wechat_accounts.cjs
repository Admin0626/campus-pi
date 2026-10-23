// Runs actual page methods in DevTools and real wx.request against the local backend.
// This is not a tap, screenshot, visual, or physical-device acceptance test.
const path = require("node:path");
const fs = require("node:fs");
const crypto = require("node:crypto");
const assert = require("node:assert/strict");
const root = path.resolve(__dirname, "..");
const automator = require(
  process.env.WECHAT_AUTOMATOR_MODULE ||
    path.join(
      root,
      ".local/auth-dev/wechat-automation/node_modules/miniprogram-automator",
    ),
);
const output = path.join(
  root,
  ".local/auth-dev/wechat-automation/account-acceptance.json",
);
const report = {
  checked_at: new Date().toISOString(),
  status: "RUNNING",
  scope:
    "Real DevTools app service, native page methods and real wx.request; no visual or device acceptance",
  checks: [],
  network_flow_verified: false,
  page_method_flow_verified: false,
  visual_verified: false,
  device_run: false,
  source_hashes: Object.fromEntries([
    "app.json", "utils/navigation.js", "utils/request.js", "custom-tab-bar/index.js",
    "pages/login/index.js", "pages/register/index.js", "pages/me/index.js",
  ].map(name => [name, crypto.createHash("sha256").update(fs.readFileSync(path.join(root, "miniprogram", name))).digest("hex")])),
};
fs.mkdirSync(path.dirname(output), { recursive: true });
fs.writeFileSync(output, JSON.stringify(report, null, 2));
function check(value, name) {
  assert.ok(value, name);
  report.checks.push({ name, status: "PASS" });
  console.log("PASS " + name);
}
async function wait(mp, predicate) {
  const until = Date.now() + 20000;
  let lastState;
  while (Date.now() < until) {
    const state = await mp.evaluate(() => {
      const stack = getCurrentPages(),
        page = stack[stack.length - 1];
      return {
        route: page.route,
        busy: !!page.data.busy,
        error: page.data.error || "",
        username: page.data.user && page.data.user.username,
        hasToken: !!getApp().globalData.token,
        hasPassword: !!page.data.password,
      };
    });
    lastState = state;
    if (predicate(state)) return state;
    await new Promise((resolve) => setTimeout(resolve, 200));
  }
  report.last_state = lastState;
  throw new Error(
    "Native page state did not reach expected result within 20 seconds",
  );
}
async function rendered(mp, route) {
  const page = await mp.currentPage();
  assert.equal(page.path, route, "Expected native route before checking its rendered view");
  let timer;
  try {
    await Promise.race([
      (async () => {
        await page.data();
        await page.waitFor("button");
        // The view can exist before the native page-transition animation has finished.
        await page.waitFor(1000);
      })(),
      new Promise((_, reject) => {
        timer = setTimeout(() => reject(new Error("Native view not ready: " + route)), 20000);
      }),
    ]);
  } finally { clearTimeout(timer); }
}
(async () => {
  let mp;
  const watchdog = setTimeout(() => {
    report.status = "INCOMPLETE";
    fs.writeFileSync(output, JSON.stringify(report, null, 2));
    process.exit(2);
  }, 200000);
  try {
    mp = await automator.connect({
      wsEndpoint:
        process.env.WECHAT_AUTOMATOR_ENDPOINT || "ws://127.0.0.1:9420",
    });
    const environment = await mp.evaluate(() => ({
      platform: wx.getDeviceInfo().platform,
      base: getApp().globalData.apiBaseUrl,
      SDKVersion: wx.getAppBaseInfo().SDKVersion,
    }));
    report.environment = environment;
    check(
      environment.platform === "devtools" &&
        environment.base === "http://127.0.0.1:8080",
      "Only the explicitly selected local DevTools environment is tested",
    );
    const networkAccount = {
      username: "wxnet_" + Date.now().toString(36),
      password: crypto.randomBytes(18).toString("base64url") + "!Aa1",
      key: crypto.randomBytes(16).toString("hex"),
    };
    const network = await mp.evaluate(async (account) => {
      const call = (route, method, data, token, key) =>
        new Promise((resolve, reject) =>
          wx.request({
            url: getApp().globalData.apiBaseUrl + "/api/v1" + route,
            method,
            data,
            timeout: 15000,
            header: {
              "Content-Type": "application/json",
              ...(token ? { Authorization: "Bearer " + token } : {}),
              ...(key ? { "Idempotency-Key": key } : {}),
            },
            success: resolve,
            fail: () => reject(new Error("Real wx.request failed")),
          }),
        );
      const registered = await call(
        "/auth/register",
        "POST",
        {
          username: account.username,
          password: account.password,
          email: account.username + "@example.invalid",
        },
        "",
        account.key,
      );
      if (registered.data.code !== "OK") return { registered: false };
      const login = await call("/auth/login", "POST", {
        account: account.username,
        password: account.password,
      });
      if (login.data.code !== "OK") return { registered: true, login: false };
      const token = login.data.data.access_token;
      const me = await call("/me", "GET", undefined, token);
      const logout = await call("/auth/logout", "POST", undefined, token);
      const revoked = await call("/me", "GET", undefined, token);
      return {
        registered: true,
        login: !!token,
        me: me.data.code === "OK" && me.data.data.username === account.username,
        logout: logout.data.code === "OK",
        revoked: revoked.statusCode === 401,
      };
    }, networkAccount);
    for (const name of ["registered", "login", "me", "logout", "revoked"]) {
      check(network[name], "Real wx.request account network flow: " + name);
    }
    report.network_flow_verified = true;
    // Do not destroy an already-open login view before exercising its real navigation.
    if ((await mp.currentPage()).path !== "pages/login/index") {
      await mp.reLaunch("/pages/login/index");
    }
    await rendered(mp, "pages/login/index");
    await mp.evaluate(() => {
      const p = getCurrentPages().slice(-1)[0];
      p.submit();
    });
    check(
      (await wait(mp, (s) => !s.busy && !!s.error)).error.includes(
        "请输入账号",
      ),
      "Native login validation",
    );
    await mp.evaluate(() => getCurrentPages().slice(-1)[0].register());
    await wait(mp, (s) => s.route === "pages/register/index");
    await rendered(mp, "pages/register/index");
    await mp.evaluate(() => getCurrentPages().slice(-1)[0].submit());
    check(
      (await wait(mp, (s) => !s.busy && !!s.error)).error.includes("用户名"),
      "Native register validation",
    );
    const account = {
      username: "wxide_" + Date.now().toString(36),
      password: crypto.randomBytes(18).toString("base64url") + "!Aa1",
    };
    await mp.evaluate((value) => {
      const p = getCurrentPages().slice(-1)[0];
      p.setData({ ...value, email: value.username + "@example.invalid" });
      p.submit();
    }, account);
    const registered = await wait(
      mp,
      (s) => s.route === "pages/login/index" || (!s.busy && !!s.error),
    );
    assert.equal(
      registered.error,
      "",
      "Registration failed: " + registered.error,
    );
    check(
      registered.route === "pages/login/index" && !registered.hasToken,
      "Real wx.request registration returns to login without auto-login",
    );
    await rendered(mp, "pages/login/index");
    await mp.evaluate((value) => {
      const p = getCurrentPages().slice(-1)[0];
      p.setData({
        account: value.username,
        password: "IncorrectPassword2026!",
      });
      p.submit();
    }, account);
    check(
      (await wait(mp, (s) => !s.busy && !!s.error)).error.includes(
        "账号或密码错误",
      ),
      "Backend rejects wrong password via real wx.request",
    );
    await mp.evaluate((value) => {
      const p = getCurrentPages().slice(-1)[0];
      p.setData({ account: value.username, password: value.password });
      p.submit();
    }, account);
    const signedIn = await wait(
      mp,
      (s) => s.route === "pages/home/index" || (!s.busy && !!s.error),
    );
    assert.equal(signedIn.error, "", "Login failed: " + signedIn.error);
    check(
      signedIn.route === "pages/home/index" && signedIn.hasToken,
      "Native login reaches home with real backend token",
    );
    await rendered(mp, "pages/home/index");
    check(await mp.evaluate(() => {
      const page = getCurrentPages().slice(-1)[0], bar = page.getTabBar && page.getTabBar();
      return !!bar && bar.data.selected === "/pages/home/index";
    }), "Native home uses shared custom tab component");
    await mp.evaluate(() => getCurrentPages().slice(-1)[0].me());
    const profile = await wait(
      mp,
      (s) => s.route === "pages/me/index" && (!!s.username || !!s.error),
    );
    check(
      profile.username === account.username && !profile.error,
      "Native me loads real authenticated user",
    );
    await rendered(mp, "pages/me/index");
    check(await mp.evaluate(() => {
      const page = getCurrentPages().slice(-1)[0], bar = page.getTabBar && page.getTabBar();
      return !!bar && bar.data.selected === "/pages/me/index";
    }), "Native profile synchronizes shared tab selection");
    await mp.evaluate(() => getCurrentPages().slice(-1)[0].logout());
    const signedOut = await wait(
      mp,
      (s) => s.route === "pages/login/index" || (!s.busy && !!s.error),
    );
    check(
      signedOut.route === "pages/login/index" && !signedOut.hasToken,
      "Native logout revokes session and returns to login",
    );
    await rendered(mp, "pages/login/index");
    report.status = "PASS";
    report.page_method_flow_verified = true;
    console.log(
      JSON.stringify({
        status: "PASS",
        checks: report.checks.length,
        visual_verified: false,
        device_run: false,
      }),
    );
  } catch (error) {
    report.status = "INCOMPLETE";
    report.error = error.message;
    console.error(error.message);
    process.exitCode = 2;
  } finally {
    clearTimeout(watchdog);
    fs.mkdirSync(path.dirname(output), { recursive: true });
    fs.writeFileSync(output, JSON.stringify(report, null, 2));
    if (mp) await mp.disconnect();
  }
})();
