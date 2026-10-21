// Real packaged UI and HTTP acceptance; no route interception or fake server responses.
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || "playwright");
const fs = require("node:fs"),
  path = require("node:path"),
  assert = require("node:assert/strict");
const root = path.resolve(__dirname, ".."),
  out = path.join(root, ".local/auth-dev/admin-notifications");
const config = JSON.parse(
  fs.readFileSync(path.join(root, ".local/auth-dev/private.json"), "utf8"),
);
const f = JSON.parse(fs.readFileSync(path.join(out, "fixtures.json"), "utf8"));
const checks = [],
  errors = [];
let complete = false;
const report = path.join(out, "browser-acceptance.json");
fs.writeFileSync(report, '{"status":"RUNNING"}');
(async () => {
  const browser = await chromium.launch({
    executablePath:
      process.env.CHROME_PATH ||
      "C:/Program Files/Google/Chrome/Application/chrome.exe",
    headless: true,
  });
  const page = await browser.newPage({
    viewport: { width: 1440, height: 1000 },
  });
  page.on("pageerror", (e) => errors.push(String(e)));
  page.on("dialog", async (dialog) => {
    errors.push("Unexpected executable content");
    await dialog.dismiss();
  });
  const check = (value, name) => {
    assert.ok(value, name);
    checks.push({ name, passed: true });
  };
  const ready = () => page.locator(".notice-panel[aria-busy=false]").waitFor();
  const row = (id) =>
    page
      .locator("tbody tr")
      .filter({
        has: page
          .locator("td:first-child")
          .filter({ hasText: new RegExp("^" + id + "$") }),
      });
  async function find(id) {
    for (let n = 0; n < 100; n++) {
      await ready();
      if (await row(id).count()) return row(id);
      const next = page.getByRole("button", { name: "下一页", exact: true });
      if (await next.isDisabled()) throw Error("Fixture not found");
      await next.click();
    }
    throw Error("Pagination exceeded");
  }
  async function saveDraft(method) {
    const response = page.waitForResponse(
      (r) =>
        r.url().includes("/api/v1/admin/notifications") &&
        r.request().method() === method,
    );
    await page
      .getByRole("dialog")
      .getByRole("button", { name: "保存草稿", exact: true })
      .click();
    const r = await response;
    check(
      r.status() === (method === "POST" ? 201 : 200),
      "Real " + method + " draft mutation succeeds",
    );
    const data = (await r.json()).data;
    await page.getByRole("dialog").waitFor({ state: "hidden" });
    await ready();
    return data;
  }
  async function confirm(route, status) {
    const dialog = page.getByRole("dialog");
    await dialog.getByLabel("操作原因").fill("浏览器合成验收");
    const response = page.waitForResponse(
      (r) =>
        r.url().endsWith("/api/v1" + route) && r.request().method() === "POST",
    );
    await dialog.getByRole("button", { name: "确认操作", exact: true }).click();
    const r = await response;
    check(r.status() === status, "Real action succeeds " + route);
    const data = (await r.json()).data;
    await dialog.waitFor({ state: "hidden" });
    await ready();
    return data;
  }
  try {
    await page.goto(
      (process.env.WEB_BASE_URL || "http://127.0.0.1:8080") + "/admin/login",
      { waitUntil: "networkidle" },
    );
    await page.getByLabel("账号", { exact: true }).fill(config.admin_username);
    await page.getByLabel("密码", { exact: true }).fill(config.admin_password);
    await page.getByRole("button", { name: "登录", exact: true }).click();
    await page.waitForURL("**/admin");
    await page
      .locator(".side-menu")
      .getByRole("button", { name: "通知管理", exact: true })
      .click();
    await page
      .getByRole("heading", { name: "通知管理", exact: true })
      .waitFor();
    await ready();
    check(
      page.url().endsWith("/admin/modules/notices"),
      "Original notification sidebar opens functional page",
    );
    check(
      (await page.locator(".side-menu button").count()) === 10,
      "Original ten sidebar entries retained",
    );
    await page
      .getByRole("button", { name: "＋ 新建通知", exact: true })
      .click();
    let dialog = page.getByRole("dialog");
    await dialog
      .getByLabel("通知标题", { exact: true })
      .fill("浏览器通知_" + f.tag);
    await dialog
      .getByLabel("通知正文", { exact: true })
      .fill("<script>alert(1)</script>\n浏览器纯文本");
    await dialog.getByLabel("发送范围", { exact: true }).selectOption("校区");
    await dialog.getByLabel("目标校区", { exact: true }).fill("测试校区");
    let data = await saveDraft("POST");
    const id = data.notification_id;
    let record = await find(id);
    check(
      (await record.textContent()).includes("测试校区") &&
        (await record.textContent()).includes("草稿"),
      "New draft appears with campus target and draft status",
    );
    check(
      await record
        .getByRole("button", { name: "发布（开发中）", exact: true })
        .isDisabled(),
      "Publication is explicitly disabled while business rule is unconfirmed",
    );
    await record.getByRole("button", { name: "查看", exact: true }).click();
    dialog = page.getByRole("dialog");
    check(
      (await dialog.textContent()).includes("<script>alert(1)</script>"),
      "Notification HTML is displayed as literal text",
    );
    await dialog.getByRole("button", { name: "关闭详情", exact: true }).click();
    await record.getByRole("button", { name: "编辑", exact: true }).click();
    dialog = page.getByRole("dialog");
    await dialog
      .getByLabel("发送范围", { exact: true })
      .selectOption("指定用户");
    await dialog
      .getByLabel("用户编号", { exact: true })
      .fill(f.recipient + "," + f.recipient);
    await dialog.getByRole("button", { name: "保存草稿", exact: true }).click();
    check(
      (await dialog.getByRole("alert").textContent()).includes("不重复"),
      "Duplicate target IDs are rejected in the real form",
    );
    await dialog.getByLabel("用户编号", { exact: true }).fill(f.recipient);
    await dialog
      .getByLabel("通知标题", { exact: true })
      .fill("浏览器已编辑_" + f.tag);
    data = await saveDraft("PUT");
    record = await find(id);
    check(
      data.target_filter.user_ids[0] === f.recipient &&
        (await record.textContent()).includes("指定 1 位用户"),
      "Editing updates the selected target and live table",
    );
    await page.getByLabel("通知状态", { exact: true }).selectOption("草稿");
    await page.getByRole("button", { name: "查询", exact: true }).click();
    await ready();
    check(
      !(await page.locator("tbody").textContent()).includes("已发送"),
      "Draft status filter excludes sent notices",
    );
    await page.getByRole("button", { name: "重置", exact: true }).click();
    await ready();
    record = await find(f.withdraw);
    await record.getByRole("button", { name: "撤回", exact: true }).click();
    await confirm("/admin/notifications/" + f.withdraw + "/withdraw", 200);
    record = await find(f.withdraw);
    check(
      (await record.textContent()).includes("已撤回") &&
        (await record
          .getByRole("button", { name: "撤回", exact: true })
          .count()) === 0,
      "Withdrawal refreshes state and removes action",
    );
    await page.screenshot({
      path: path.join(out, "01-notification-list.png"),
      fullPage: true,
    });
    await page.getByRole("button", { name: "刷新", exact: true }).click();
    await ready();
    record = await find(f.retry_notice);
    await record.getByRole("button", { name: "发送记录", exact: true }).click();
    await ready();
    record = await find(f.retry_delivery);
    check(
      (await record.textContent()).includes("失败"),
      "Original send-record tab shows actual failed attempt",
    );
    await record.getByRole("button", { name: "重试", exact: true }).click();
    data = await confirm(
      "/admin/notification-deliveries/" + f.retry_delivery + "/retry",
      202,
    );
    check(data.attempt_no === 2, "Browser retry receives a new second attempt");
    let succeeded = false;
    for (let n = 0; n < 30; n++) {
      await page.getByRole("button", { name: "刷新", exact: true }).click();
      await ready();
      const target = await find(data.delivery_id);
      if ((await target.textContent()).includes("成功")) {
        succeeded = true;
        break;
      }
      await page.waitForTimeout(250);
    }
    check(succeeded, "Real scheduled worker completes browser-created retry");
    check(
      (await row(f.retry_delivery).textContent()).includes("失败"),
      "Previous failed attempt remains in send records",
    );
    await page.getByLabel("投递状态", { exact: true }).selectOption("成功");
    await page.getByRole("button", { name: "查询", exact: true }).click();
    await ready();
    check(
      (await row(data.delivery_id).count()) === 1 &&
        (await row(f.retry_delivery).count()) === 0,
      "Delivery filter uses actual backend statuses",
    );
    await page.screenshot({
      path: path.join(out, "02-delivery-records.png"),
      fullPage: true,
    });
    await page.getByRole("button", { name: "通知列表", exact: true }).click();
    await ready();
    check(
      (await page
        .getByRole("columnheader", { name: "标题", exact: true })
        .count()) === 1 &&
        (await page
          .getByRole("columnheader", { name: "用户ID", exact: true })
          .count()) === 0,
      "Tab switching clears incompatible row data and table columns",
    );
    await page
      .getByRole("button", { name: "＋ 新建通知", exact: true })
      .click();
    dialog = page.getByRole("dialog");
    await dialog.getByLabel("通知标题", { exact: true }).fill("全体通知草稿");
    await dialog.getByLabel("通知正文", { exact: true }).fill("尚未发送");
    await page.screenshot({
      path: path.join(out, "03-draft-form.png"),
      fullPage: true,
    });
    await dialog.getByRole("button", { name: "取消", exact: true }).click();
    check(
      errors.length === 0,
      "No runtime script errors or executable notification content",
    );
    complete = true;
  } finally {
    fs.writeFileSync(
      report,
      JSON.stringify(
        {
          status: complete ? "PASS" : "INCOMPLETE",
          checked_at: new Date().toISOString(),
          checks,
          errors,
          publication_is_synthetic_fixture: true,
        },
        null,
        2,
      ),
    );
    await browser.close();
  }
  console.log(JSON.stringify({ status: "PASS", checks: checks.length }));
})().catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});
