// Real packaged entry/cache checks; simulated future HTML is explicitly scoped to update-notice behavior.
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || "playwright");
const fs = require("node:fs"),
  path = require("node:path"),
  assert = require("node:assert/strict");
const base = process.env.WEB_BASE_URL || "http://127.0.0.1:8080";
const out = path.resolve(__dirname, "../.local/auth-dev/frontend-updates");
fs.mkdirSync(out, { recursive: true });
(async () => {
  const browser = await chromium.launch({
    executablePath:
      process.env.CHROME_PATH ||
      "C:/Program Files/Google/Chrome/Application/chrome.exe",
    headless: true,
  });
  const checks = [],
    errors = [];
  const ok = (value, name) => {
    assert.ok(value, name);
    checks.push({ name, passed: true });
  };
  try {
    const page = await browser.newPage({
      viewport: { width: 1280, height: 800 },
    });
    page.on("pageerror", (e) => errors.push(e.name));
    let html;
    for (const route of [
      "/",
      "/index.html",
      "/admin/login",
      "/admin/modules/analytics",
    ]) {
      const response = await page.request.get(base + route, {
        headers: { "If-Modified-Since": "Thu, 31 Dec 2099 23:59:59 GMT" },
      });
      const headers = response.headers();
      ok(
        response.status() === 200 &&
          headers["cache-control"] === "no-store" &&
          !headers["last-modified"],
        route +
          ": fresh HTML even when a cached manifest timestamp is supplied",
      );
      html = await response.text();
    }
    for (const asset of [
      "/assets/index-Dp8Rk23C.js",
      "/assets/index-D8TSQ-sl.css",
      "/assets/__stale_probe__.js",
    ]) {
      const response = await page.request.get(base + asset);
      ok(
        response.status() === 404,
        asset + ": stale build is absent, not silently served as JavaScript",
      );
    }
    await page.goto(base + "/admin/login", { waitUntil: "networkidle" });
    ok(
      (await page.getByRole("status", { name: "页面版本更新" }).count()) === 0,
      "same live build does not show update notice",
    );
    await page
      .getByLabel("账号", { exact: true })
      .fill("synthetic_unsaved_account");
    const pulse = async () => {
      const response = page.waitForResponse((r) =>
        r.url().includes("__campus_version_check="),
      );
      await page.evaluate(() => window.dispatchEvent(new Event("focus")));
      await response;
    };
    await page.route("**/index.html?__campus_version_check=*", (route) =>
      route.fulfill({
        contentType: "application/json",
        body: '{"error":"synthetic invalid manifest"}',
      }),
    );
    await pulse();
    ok(
      (await page.getByRole("status", { name: "页面版本更新" }).count()) === 0,
      "non-HTML error response does not imply a release",
    );
    await page.unroute("**/index.html?__campus_version_check=*");
    const future = html.replace(
      /\/assets\/[^"\s]+\.js/,
      "/assets/index-future-layout-fixture.js",
    );
    assert.notEqual(future, html);
    await page.route("**/index.html?__campus_version_check=*", (route) =>
      route.fulfill({ contentType: "text/html", body: future }),
    );
    await pulse();
    await page.getByRole("status", { name: "页面版本更新" }).waitFor();
    ok(true, "changed hashed asset manifest shows one reusable update notice");
    ok(
      (await page.getByLabel("账号", { exact: true }).inputValue()) ===
        "synthetic_unsaved_account",
      "detecting a release preserves current input and does not auto-reload",
    );
    await page.screenshot({
      path: path.join(out, "01-update-notice.png"),
      fullPage: true,
    });
    await page.unroute("**/index.html?__campus_version_check=*");
    await Promise.all([
      page.waitForNavigation(),
      page.getByRole("button", { name: "刷新并重新登录", exact: true }).click(),
    ]);
    await page.waitForLoadState("networkidle");
    ok(
      (await page.getByLabel("账号", { exact: true }).inputValue()) === "" &&
        (await page.getByRole("status", { name: "页面版本更新" }).count()) ===
          0,
      "explicit update reloads current real build and returns to sign-in",
    );
    ok(errors.length === 0, "no browser JavaScript errors");
    fs.writeFileSync(
      path.join(out, "acceptance.json"),
      JSON.stringify(
        {
          status: "PASS",
          checked_at: new Date().toISOString(),
          futureManifestIsFixture: true,
          checks,
          errors,
        },
        null,
        2,
      ),
    );
    console.log(
      JSON.stringify({
        status: "PASS",
        checks: checks.length,
        futureManifestIsFixture: true,
      }),
    );
  } finally {
    await browser.close();
  }
})().catch((error) => {
  console.error(error.message);
  process.exit(1);
});
