// Check every available placeholder and direct page entry against the packaged app.
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || "playwright");
const fs = require("node:fs"),
  path = require("node:path");
const root = path.resolve(__dirname, ".."),
  out = path.join(root, ".local/auth-dev"),
  base = process.env.WEB_BASE_URL || "http://127.0.0.1:8080";
const local = JSON.parse(
  fs.readFileSync(path.join(out, "private.json"), "utf8"),
);
(async () => {
  const browser = await chromium.launch({
    executablePath:
      process.env.CHROME_PATH ||
      "C:/Program Files/Google/Chrome/Application/chrome.exe",
    headless: true,
  });
  const page = await browser.newPage({
      viewport: { width: 1440, height: 1000 },
    }),
    checks = [],
    errors = [];
  page.on("pageerror", (error) => errors.push(String(error)));
  function ok(value, name) {
    if (!value) throw Error(name);
    checks.push({ name, passed: true });
  }
  const adminEntries = [
    ["内容发布管理", "content"],
    ["交易管理", "products"],
    ["悬赏任务", "tasks"],
    ["订单管理", "orders"],
    ["通知管理", "notices"],
    ["数据分析", "analytics"],
    ["系统设置", "settings"],
    ["权限管理", "permissions"],
  ];
  const userEntries = [
    ["商品与分类", "C"],
    ["校园帖子", "D"],
    ["悬赏任务", "E"],
    ["任务验收与结算", "F"],
    ["订单与售后", "G"],
    ["私信与举报", "H"],
    ["通知与投递", "I"],
  ];
  const routes = [
    "/login",
    "/admin/login",
    "/register",
    "/forgot-password",
    "/home",
    "/me",
    "/me/profile",
    "/me/password",
    "/admin",
    "/admin/users",
    "/admin/users/1",
    "/admin/modules/categories",
    "/admin/modules/audit",
    "/admin/modules/verifications",
    "/admin/modules/reports",
    "/modules/B",
    ...adminEntries.map(([, code]) => "/admin/modules/" + code),
    ...userEntries.map(([, code]) => "/modules/" + code),
  ];
  for (const route of routes) {
    const response = await page.request.get(base + route);
    ok(
      response.status() === 200 &&
        (await response.text()).includes('<div id="app">'),
      route + " serves the packaged app",
    );
  }
  await page.goto(base + "/admin/users", { waitUntil: "networkidle" });
  await page.waitForURL("**/admin/login");
  ok(true, "unauthenticated direct admin entry requires admin sign in");
  await page.getByLabel("账号", { exact: true }).fill(local.admin_username);
  await page.getByLabel("密码", { exact: true }).fill(local.admin_password);
  await page.getByRole("button", { name: "登录", exact: true }).click();
  await page.waitForURL("**/admin");
  for (const [name, code] of adminEntries) {
    await page.getByRole("button", { name, exact: true }).click();
    await page
      .getByRole("heading", {
        name:
          {
            analytics: "用户增长趋势",
            content: "内容审核",
            products: "商品信息管理",
            permissions: "权限管理",
            tasks: "悬赏任务管理",
            orders: "订单管理",
            notices: "通知管理",
          }[code] || name + "还在开发",
        exact: true,
      })
      .waitFor();
    ok(
      page.url().endsWith("/admin/modules/" + code),
      "admin " +
        name +
        ([
          "content",
          "products",
          "permissions",
          "tasks",
          "orders",
          "notices",
        ].includes(code)
          ? " functional page reachable"
          : code === "analytics"
            ? " mock page reachable"
            : " placeholder reachable"),
    );
  }
  await page.getByRole("button", { name: "用户端", exact: true }).click();
  await page.waitForURL("**/home");
  const userNavigation = [
    ["二手交易", "商品与分类", "C", false],
    ["发布信息", "校园帖子", "D", false],
    ["悬赏任务", "悬赏任务", "E", false],
    ["任务记录", "任务验收与结算", "F", true],
    ["订单管理", "订单与售后", "G", true],
    ["私信", "私信与举报", "H", false],
    ["通知中心", "通知与投递", "I", true],
  ];
  for (const [button, name, code, viaMe] of userNavigation) {
    if (viaMe)
      await page.getByRole("button", { name: "我的账号", exact: true }).click();
    await page.getByRole("button", { name: button, exact: true }).click();
    await page
      .getByRole("heading", { name: name + "还在开发", exact: true })
      .waitFor();
    ok(
      page.url().endsWith("/modules/" + code),
      "user " + name + " placeholder reachable",
    );
    await page
      .getByRole("button", { name: "返回校园生活", exact: false })
      .click();
  }
  await page.getByRole("button", { name: "我的账号", exact: true }).click();
  await page.getByRole("button", { name: "退出登录", exact: true }).click();
  await page.waitForURL("**/login");
  await page.getByRole("button", { name: "实名认证", exact: true }).click();
  await page
    .getByRole("status")
    .filter({ hasText: "实名认证还在开发" })
    .waitFor();
  ok(true, "verification entry reports development status");
  await page.getByRole("button", { name: "忘记密码?", exact: true }).click();
  await page
    .getByRole("heading", { name: "密码找回还在开发", exact: true })
    .waitFor();
  ok(true, "password recovery accurately unavailable");
  ok(errors.length === 0, "page entries contain no browser script errors");
  fs.writeFileSync(
    path.join(out, "page-entry-acceptance.json"),
    JSON.stringify({ status: "PASS", checks, errors }, null, 2),
  );
  console.log(JSON.stringify({ status: "PASS", checks: checks.length }));
  await browser.close();
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
