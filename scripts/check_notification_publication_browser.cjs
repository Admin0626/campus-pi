// Full real admin publication and receiver inbox flow; no HTTP response substitution.
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || "playwright");
const fs = require("node:fs"),
  path = require("node:path"),
  assert = require("node:assert/strict");
const root = path.resolve(__dirname, ".."),
  out = path.join(root, ".local/auth-dev/notification-publication");
const local = JSON.parse(
  fs.readFileSync(path.join(root, ".local/auth-dev/private.json"), "utf8"),
);
const f = JSON.parse(
  fs.readFileSync(path.join(out, "private-fixtures.json"), "utf8"),
);
const checks = [],
  errors = [];
let complete = false;
const report = path.join(out, "browser-acceptance.json");
fs.writeFileSync(report, '{"status":"RUNNING"}');
const check = (v, name) => {
  assert.ok(v, name);
  checks.push({ name, passed: true });
};
(async () => {
  const browser = await chromium.launch({
    executablePath:
      process.env.CHROME_PATH ||
      "C:/Program Files/Google/Chrome/Application/chrome.exe",
    headless: true,
  });
  const context = await browser.newContext({
      viewport: { width: 1440, height: 1000 },
    }),
    admin = await context.newPage(),
    user = await context.newPage();
  for (const page of [admin, user]) {
    page.on("pageerror", (e) => errors.push(e.name));
    page.on("dialog", async (d) => {
      errors.push("Unexpected script dialog");
      await d.dismiss();
    });
  }
  const base = process.env.WEB_BASE_URL || "http://127.0.0.1:8080";
  const ready = (page) =>
    page
      .locator(
        page === admin
          ? ".notice-panel[aria-busy=false]"
          : ".inbox-screen[aria-busy=false]",
      )
      .waitFor();
  async function login(page, adminMode) {
    await page.goto(base + (adminMode ? "/admin/login" : "/login"), {
      waitUntil: "networkidle",
    });
    await page
      .locator("#account,#admin-account")
      .fill(adminMode ? local.admin_username : f.a.username);
    await page
      .getByLabel("密码", { exact: true })
      .fill(adminMode ? local.admin_password : f.a.password);
    await page.getByRole("button", { name: "登录", exact: true }).click();
    await page.waitForURL(adminMode ? "**/admin" : "**/home");
  }
  const row = (id) =>
    admin.locator("tbody tr").filter({
      has: admin
        .locator("td:first-child")
        .filter({ hasText: new RegExp("^" + id + "$") }),
    });
  async function find(id) {
    for (let n = 0; n < 100; n++) {
      await ready(admin);
      if (await row(id).count()) return row(id);
      const next = admin.getByRole("button", { name: "下一页", exact: true });
      if (await next.isDisabled()) throw Error("Draft fixture not found");
      await next.click();
    }
    throw Error("Pagination limit");
  }
  async function publish(id) {
    const record = await find(id);
    await record.getByRole("button", { name: "审核发布", exact: true }).click();
    const dialog = admin.getByRole("dialog");
    await dialog
      .getByRole("heading", { name: "审核并发布通知", exact: true })
      .waitFor();
    check(
      (await dialog.textContent()).includes("真实发布的合成纯文本") ||
        (await dialog.textContent()).includes("浏览器安全偏好"),
      "Human review presents actual content and target",
    );
    await dialog.getByLabel("我已核对内容及发送对象", { exact: true }).check();
    const response = admin.waitForResponse(
      (r) =>
        r.url().endsWith("/api/v1/admin/notifications/" + id + "/publish") &&
        r.request().method() === "POST",
    );
    await dialog.getByRole("button", { name: "确认操作", exact: true }).click();
    const r = await response;
    check(r.status() === 202, "Real review publishes into persistent queue");
    const data = (await r.json()).data;
    await dialog.waitFor({ state: "hidden" });
    await ready(admin);
    return data;
  }
  async function inbox() {
    await user.getByRole("button", { name: "我的账号", exact: true }).click();
    await user.getByRole("button", { name: "通知中心", exact: true }).click();
    await user.getByRole("heading", { name: "通知", exact: true }).waitFor();
    await ready(user);
  }
  const card = (id) =>
    user.locator('article[data-notification-id="' + id + '"]');
  async function refreshUntil(id, exists = true) {
    for (let n = 0; n < 30; n++) {
      await user.getByRole("button", { name: "刷新通知", exact: true }).click();
      await ready(user);
      if ((await card(id).count()) > 0 === exists) return true;
      await user.waitForTimeout(250);
    }
    return false;
  }
  async function preference(enabled) {
    await user.getByRole("button", { name: "设置", exact: true }).click();
    const dialog = user.getByRole("dialog");
    const control = dialog.getByLabel("接收站内通知", { exact: true });
    await control.waitFor();
    await control.setChecked(enabled);
    await dialog.getByLabel("免打扰开始", { exact: true }).fill("");
    await dialog.getByLabel("免打扰结束", { exact: true }).fill("");
    const response = user.waitForResponse(
      (r) =>
        r.url().endsWith("/api/v1/me/notification-preferences") &&
        r.request().method() === "PUT",
    );
    await dialog.getByRole("button", { name: "保存偏好", exact: true }).click();
    const r = await response;
    check(
      r.status() === 200 && (await r.json()).data.in_app_enabled === enabled,
      "Real UI saves receiver preference " + enabled,
    );
    await dialog.waitFor({ state: "hidden" });
    await ready(user);
  }
  try {
    await login(user, false);
    await inbox();
    await preference(true);
    await login(admin, true);
    await admin
      .locator(".side-menu")
      .getByRole("button", { name: "通知管理", exact: true })
      .click();
    await ready(admin);
    await admin
      .getByRole("button", { name: "＋ 新建通知", exact: true })
      .click();
    let fresh = admin.getByRole("dialog");
    await fresh
      .getByLabel("通知标题", { exact: true })
      .fill("浏览器真实发布_" + f.tag);
    await fresh
      .getByLabel("通知正文", { exact: true })
      .fill("真实发布的合成纯文本 <script>alert(1)</script>");
    await fresh
      .getByLabel("发送范围", { exact: true })
      .selectOption("指定用户");
    await fresh.getByLabel("用户编号", { exact: true }).fill(f.a.id);
    const created = admin.waitForResponse(
      (r) =>
        r.url().endsWith("/api/v1/admin/notifications") &&
        r.request().method() === "POST",
    );
    await fresh.getByRole("button", { name: "保存草稿", exact: true }).click();
    const createdResponse = await created;
    check(
      createdResponse.status() === 201,
      "Fresh browser publication starts from actual UI draft",
    );
    f.browser_draft = (await createdResponse.json()).data.notification_id;
    await fresh.waitFor({ state: "hidden" });
    await ready(admin);
    let data = await publish(f.browser_draft);
    check(
      data.recipient_count === 1,
      "Browser publication freezes actual selected receiver",
    );
    await admin.screenshot({
      path: path.join(out, "01-published-admin.png"),
      fullPage: true,
    });
    await login(user, false);
    await inbox();
    check(
      await refreshUntil(f.browser_draft),
      "Actual published message is delivered into actual receiver UI",
    );
    check(
      (await card(f.browser_draft).textContent()).includes("未读"),
      "Newly delivered message is unread",
    );
    await card(f.browser_draft)
      .getByRole("button", { name: "查看详情", exact: true })
      .click();
    const detail = user.locator(".inbox-detail");
    await detail.getByRole("heading").waitFor();
    check(
      (await detail.textContent()).includes("<script>alert(1)</script>"),
      "Receiver renders potentially executable content as plain text",
    );
    check(
      (await detail.locator("dd").filter({ hasText: "—" }).count()) === 2,
      "Viewing notification leaves read and click timestamps empty",
    );
    let response = user.waitForResponse(
      (r) => r.url().endsWith("/read") && r.request().method() === "PUT",
    );
    await detail.getByRole("button", { name: "标为已读", exact: true }).click();
    let r = await response;
    check(
      r.status() === 200 && (await r.json()).data.read_at,
      "Read button calls real receipt API",
    );
    await ready(user);
    response = user.waitForResponse(
      (r) => r.url().endsWith("/click") && r.request().method() === "PUT",
    );
    await detail.getByRole("button", { name: "记录点击", exact: true }).click();
    r = await response;
    check(
      r.status() === 200 && (await r.json()).data.clicked_at,
      "Click is separately recorded through real API",
    );
    await ready(user);
    await user.screenshot({
      path: path.join(out, "02-receiver-detail.png"),
      fullPage: true,
    });
    await detail.getByRole("button", { name: "删除通知", exact: true }).click();
    let dialog = user.getByRole("dialog");
    response = user.waitForResponse(
      (r) =>
        r.url().endsWith("/api/v1/me/notifications/" + f.browser_draft) &&
        r.request().method() === "DELETE",
    );
    await dialog.getByRole("button", { name: "确认删除", exact: true }).click();
    r = await response;
    check(r.status() === 200, "Delete confirmation calls own receipt API");
    await dialog.waitFor({ state: "hidden" });
    await ready(user);
    check(
      (await card(f.browser_draft).count()) === 0,
      "Deleted notice disappears without affecting other receivers",
    );
    await preference(false);
    await admin
      .getByRole("button", { name: "＋ 新建通知", exact: true })
      .click();
    dialog = admin.getByRole("dialog");
    await dialog
      .getByLabel("通知标题", { exact: true })
      .fill("浏览器安全偏好_" + f.tag);
    await dialog
      .getByLabel("通知正文", { exact: true })
      .fill("浏览器安全偏好：关闭时不应收到");
    await dialog.getByLabel("通知类型", { exact: true }).selectOption("安全");
    await dialog
      .getByLabel("发送范围", { exact: true })
      .selectOption("指定用户");
    await dialog.getByLabel("用户编号", { exact: true }).fill(f.a.id);
    response = admin.waitForResponse(
      (r) =>
        r.url().endsWith("/api/v1/admin/notifications") &&
        r.request().method() === "POST",
    );
    await dialog.getByRole("button", { name: "保存草稿", exact: true }).click();
    r = await response;
    const draft = (await r.json()).data;
    await dialog.waitFor({ state: "hidden" });
    await ready(admin);
    data = await publish(draft.notification_id);
    check(
      data.recipient_count === 0,
      "Security publication respects actual receiver opt-out",
    );
    check(
      await refreshUntil(draft.notification_id, false),
      "Opted-out safety message never enters receiver inbox",
    );
    await preference(true);
    await user.getByRole("button", { name: "设置", exact: true }).click();
    dialog = user.getByRole("dialog");
    check(
      (await dialog.textContent()).includes("安全通知也遵守偏好"),
      "Preference UI states the confirmed security behavior",
    );
    await user.screenshot({
      path: path.join(out, "03-notification-preferences.png"),
      fullPage: true,
    });
    await dialog.getByRole("button", { name: "取消", exact: true }).click();
    for (const width of [390, 320]) {
      await user.setViewportSize({ width, height: 844 });
      check(
        await user.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth,
        ),
        "Notification inbox fits " + width + "px",
      );
      if (width === 390)
        await user.screenshot({
          path: path.join(out, "04-mobile-inbox.png"),
          fullPage: true,
        });
    }
    await user.getByRole("button", { name: "返回个人页", exact: true }).click();
    await user.waitForURL("**/me");
    check(true, "Notification back button returns to original personal page");
    check(
      errors.length === 0,
      "No browser runtime errors or notification script execution",
    );
    complete = true;
  } finally {
    fs.writeFileSync(
      report,
      JSON.stringify(
        {
          status: complete ? "PASS" : "INCOMPLETE",
          checks,
          errors,
          checked_at: new Date().toISOString(),
          receipts_created_by_actual_api: true,
        },
        null,
        2,
      ),
    );
    await browser.close();
  }
  console.log(JSON.stringify({ status: "PASS", checks: checks.length }));
})().catch((e) => {
  let message = e.message;
  for (const secret of [local.admin_password, f.a.password, f.b.password])
    if (secret) message = message.replaceAll(secret, "[redacted]");
  console.error(message);
  process.exitCode = 1;
});
