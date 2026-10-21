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
  scope:
    "Real DevTools app service, native page methods and real wx.request; no visual or device acceptance",
  checks: [],
  visual_verified: false,
  device_run: false,
};
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
(async () => {
  let mp;
  const watchdog = setTimeout(() => {
    report.status = "INCOMPLETE";
    fs.writeFileSync(output, JSON.stringify(report, null, 2));
    process.exit(2);
  }, 100000);
  try {
    mp = await automator.connect({
      wsEndpoint:
        process.env.WECHAT_AUTOMATOR_ENDPOINT || "ws://127.0.0.1:9420",
    });
    const environment = await mp.evaluate(() => ({
      platform: wx.getDeviceInfo().platform,
      base: getApp().globalData.apiBaseUrl,
    }));
    check(
      environment.platform === "devtools" &&
        environment.base === "http://127.0.0.1:8080",
      "Only the explicitly selected local DevTools environment is tested",
    );
    await mp.reLaunch("/pages/login/index");
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
    await mp.evaluate(() => getCurrentPages().slice(-1)[0].me());
    const profile = await wait(
      mp,
      (s) => s.route === "pages/me/index" && (!!s.username || !!s.error),
    );
    check(
      profile.username === account.username && !profile.error,
      "Native me loads real authenticated user",
    );
    await mp.evaluate(() => getCurrentPages().slice(-1)[0].logout());
    const signedOut = await wait(
      mp,
      (s) => s.route === "pages/login/index" || (!s.busy && !!s.error),
    );
    check(
      signedOut.route === "pages/login/index" && !signedOut.hasToken,
      "Native logout revokes session and returns to login",
    );
    report.status = "PASS";
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
