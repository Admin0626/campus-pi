// Actual UI + HTTP. No route interception, injected response, SQL seed or private-duty changes.
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || "playwright");
const fs = require("node:fs"),
  path = require("node:path"),
  assert = require("node:assert/strict"),
  crypto = require("node:crypto");
const root = path.resolve(__dirname, ".."),
  out = path.join(root, ".local/auth-dev/admin-entry-links");
fs.mkdirSync(out, { recursive: true });
const local = JSON.parse(
  fs.readFileSync(path.join(root, ".local/auth-dev/private.json"), "utf8"),
);
const base = process.env.WEB_BASE_URL || "http://127.0.0.1:8080",
  tag = crypto.randomBytes(4).toString("hex"),
  checks = [],
  errors = [],
  checked_at = new Date().toISOString();
let complete = false,
  adminToken,
  temporaryAdmin;
fs.writeFileSync(
  path.join(out, "browser-acceptance.json"),
  JSON.stringify({ status: "RUNNING", checked_at }),
);
const check = (value, name) => {
  assert.ok(value, name);
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
    page = await context.newPage(),
    other = await context.newPage();
  for (const p of [page, other]) {
    p.on("pageerror", (e) => errors.push(e.name));
    p.on("dialog", async (d) => {
      errors.push("Unexpected script dialog");
      await d.dismiss();
    });
  }
  async function api(route, method = "GET", body, token = adminToken) {
    const r = await context.request.fetch(base + "/api/v1" + route, {
      method,
      headers: {
        Accept: "application/json",
        ...(token ? { Authorization: "Bearer " + token } : {}),
        ...(method === "POST"
          ? { "Idempotency-Key": crypto.randomUUID() }
          : {}),
      },
      ...(body !== undefined ? { data: body } : {}),
    });
    return { status: r.status(), value: await r.json() };
  }
  async function user(label) {
    const name = "links_" + label + "_" + tag,
      password = crypto.randomBytes(18).toString("base64url");
    const r = await api(
      "/auth/register",
      "POST",
      { username: name, password, email: name + "@example.invalid" },
      null,
    );
    assert.equal(r.status, 201);
    return { id: r.value.data.user_id, name, password };
  }
  async function login(p, name, password) {
    await p.goto(base + "/admin/login", { waitUntil: "networkidle" });
    await p.getByLabel("账号", { exact: true }).fill(name);
    await p.getByLabel("密码", { exact: true }).fill(password);
    const [r] = await Promise.all([
      p.waitForResponse(
        (r) =>
          r.url().endsWith("/api/v1/auth/login") &&
          r.request().method() === "POST",
      ),
      p.getByRole("button", { name: "登录", exact: true }).click(),
    ]);
    assert.equal(r.status(), 200);
    await p.waitForURL("**/admin");
    return (await r.json()).data.access_token;
  }
  const ready = (p) =>
    p.locator(".admin-content-panel[aria-busy=false]").waitFor();
  async function findUser(p, u) {
    await p
      .locator(".side-menu")
      .getByRole("button", { name: "用户管理", exact: true })
      .click();
    await p.getByLabel("用户名", { exact: true }).fill(u.name);
    await p.getByRole("button", { name: "搜索", exact: true }).click();
    const row = p.locator("tbody tr").filter({ hasText: u.name });
    await row.waitFor();
    return row;
  }
  async function userLogs(p, row, u) {
    const [r] = await Promise.all([
      p.waitForResponse(
        (r) => new URL(r.url()).pathname === "/api/v1/admin/audit-logs",
      ),
      row.getByRole("button", { name: "日志", exact: true }).click(),
    ]);
    const params = new URL(r.url()).searchParams;
    check(
      params.get("object_type") === "USER" && params.get("object_id") === u.id,
      "User log link sends exact contracted USER/id filter " + u.name,
    );
    return r;
  }
  try {
    adminToken = await login(page, local.admin_username, local.admin_password);
    const a = await user("a"),
      b = await user("b");
    temporaryAdmin = b;
    for (const state of ["DISABLED", "ACTIVE"]) {
      const r = await api("/admin/users/" + a.id + "/status", "POST", {
        account_status: state,
        reason: "Entry link <script>not executed</script> " + tag,
      });
      assert.equal(r.status, 200);
    }
    assert.equal(
      (
        await api("/admin/users/" + b.id + "/role", "POST", {
          role: "ADMIN",
          reason: "Synthetic entry-link permission test",
        })
      ).status,
      200,
    );
    const result = await userLogs(page, await findUser(page, a), a);
    check(
      result.status() === 200,
      "Privileged user-row log entry uses real API112",
    );
    await ready(page);
    check(
      (await page.getByLabel("对象类型", { exact: true }).inputValue()) ===
        "USER" &&
        (await page.getByLabel("对象编号", { exact: true }).inputValue()) ===
          a.id,
      "Audit page initializes both filters",
    );
    const logs = (await result.json()).data.items;
    check(
      logs.length === 2 &&
        logs.every((r) => r.object_type === "USER" && r.object_id === a.id),
      "User logs contain only the actual two target status audits",
    );
    check(
      (await page.locator("tbody").innerText()).includes(
        "<script>not executed</script>",
      ) && (await page.locator("tbody script").count()) === 0,
      "Audit reasons render as inert text",
    );
    await page.screenshot({
      path: path.join(out, "user-specific-audit.png"),
      fullPage: true,
    });
    const [all] = await Promise.all([
      page.waitForResponse(
        (r) => new URL(r.url()).pathname === "/api/v1/admin/audit-logs",
      ),
      page.getByRole("button", { name: "重置", exact: true }).click(),
    ]);
    check(
      new URL(all.url()).searchParams.get("object_id") === null &&
        (await page.getByLabel("对象编号", { exact: true }).inputValue()) ===
          "",
      "Audit reset deliberately clears context instead of reapplying it",
    );
    const row = await findUser(page, a);
    await row.getByRole("button", { name: "查看", exact: true }).click();
    await page.getByRole("heading", { name: a.name, exact: true }).waitFor();
    const [detailLog] = await Promise.all([
      page.waitForResponse(
        (r) => new URL(r.url()).pathname === "/api/v1/admin/audit-logs",
      ),
      page.getByRole("button", { name: "查看操作日志", exact: true }).click(),
    ]);
    check(
      detailLog.status() === 200 &&
        new URL(detailLog.url()).searchParams.get("object_id") === a.id,
      "User detail also opens exact target audit",
    );
    await page
      .locator(".side-menu")
      .getByRole("button", { name: "控制台", exact: true })
      .click();
    await ready(page);
    const [globalLog] = await Promise.all([
      page.waitForResponse(
        (r) => new URL(r.url()).pathname === "/api/v1/admin/audit-logs",
      ),
      page.getByRole("button", { name: "操作日志 →", exact: true }).click(),
    ]);
    check(
      !new URL(globalLog.url()).searchParams.has("object_id"),
      "Global audit entry does not inherit a prior target filter",
    );
    const otherToken = await login(other, b.name, b.password);
    const denied = await userLogs(other, await findUser(other, a), a);
    await ready(other);
    check(
      denied.status() === 403 &&
        (await other.locator("tbody tr").count()) === 1 &&
        !(await other.locator("tbody").innerText()).includes("ACCOUNT_STATUS"),
      "ADMIN without AUDIT sees denial and no previously loaded audit rows",
    );
    check(
      await other.getByRole("alert").filter({ hasText: "AUDIT" }).isVisible(),
      "Missing duty is explained by server authorization",
    );
    const title = "后台个人通知_" + tag;
    const draft = await api("/admin/notifications", "POST", {
      notification_title: title,
      notification_content: "后台入口的实际站内通知",
      notification_type: "系统",
      target_scope: "指定用户",
      target_filter: { user_ids: [b.id] },
    });
    assert.equal(draft.status, 201);
    const id = draft.value.data.notification_id;
    assert.equal(
      (
        await api("/admin/notifications/" + id + "/publish", "POST", {
          channels: ["站内"],
        })
      ).status,
      202,
    );
    let received = false;
    for (let n = 0; n < 40; n++) {
      const r = await api(
        "/me/notifications?unread_only=true&page_size=1",
        "GET",
        undefined,
        otherToken,
      );
      if (r.status === 200 && r.value.data.total === "1") {
        received = true;
        break;
      }
      await new Promise((resolve) => setTimeout(resolve, 250));
    }
    check(
      received,
      "Actual worker delivered publication to the synthetic administrator",
    );
    await other
      .locator(".side-menu")
      .getByRole("button", { name: "控制台", exact: true })
      .click();
    await ready(other);
    await other
      .getByRole("button", { name: "我的通知", exact: true })
      .filter({ has: other.locator(".report-notice-count") })
      .waitFor();
    check(
      (await other.locator(".word-notice-entry").getAttribute("title")) ===
        "未读通知 1 条",
      "Header count comes from real personal unread API",
    );
    await other.getByRole("button", { name: "我的通知", exact: true }).click();
    await other.waitForURL("**/modules/I");
    await other.locator(".inbox-screen[aria-busy=false]").waitFor();
    const card = other.locator('[data-notification-id="' + id + '"]');
    await card.getByRole("button", { name: "查看详情", exact: true }).click();
    await other.getByRole("heading", { name: title, exact: true }).waitFor();
    check(
      await other
        .getByRole("button", { name: "返回管理后台", exact: true })
        .isVisible(),
      "Header opens real personal inbox with an explicit admin return target",
    );
    const [read] = await Promise.all([
      other.waitForResponse(
        (r) =>
          r.url().endsWith("/" + id + "/read") &&
          r.request().method() === "PUT",
      ),
      other.getByRole("button", { name: "标为已读", exact: true }).click(),
    ]);
    assert.equal(read.status(), 200);
    await other
      .getByRole("button", { name: "返回管理后台", exact: true })
      .click();
    await other.waitForURL("**/admin");
    await ready(other);
    await other.locator('.word-notice-entry[title="未读通知 0 条"]').waitFor();
    check(
      (await other
        .locator(".word-notice-entry .report-notice-count")
        .count()) === 0,
      "Returning to admin refreshes zero unread without a fake badge",
    );
    await other.screenshot({
      path: path.join(out, "header-notification-return.png"),
      fullPage: true,
    });
    const member = await user("member");
    const memberPage = await context.newPage();
    await memberPage.goto(base + "/login", { waitUntil: "networkidle" });
    await memberPage.locator("#account").fill(member.name);
    await memberPage.locator("#login-password").fill(member.password);
    await memberPage.getByRole("button", { name: "登录", exact: true }).click();
    await memberPage.waitForURL("**/home");
    await memberPage
      .getByRole("button", { name: /^(我的账号|个人)$/, exact: true })
      .click();
    await memberPage
      .getByRole("button", { name: "通知中心", exact: true })
      .click();
    await memberPage.locator(".inbox-screen[aria-busy=false]").waitFor();
    check(
      await memberPage
        .getByRole("button", { name: "返回个人页", exact: true })
        .isVisible(),
      "Normal member inbox retains its original personal-page return",
    );
    await memberPage.close();
    check(errors.length === 0, "No page errors or executable content");
    complete = true;
  } finally {
    if (temporaryAdmin && adminToken) {
      const r = await api(
        "/admin/users/" + temporaryAdmin.id + "/role",
        "POST",
        { role: "USER", reason: "Restore synthetic entry-link test account" },
      );
      if (r.status !== 200) {
        complete = false;
        errors.push("Synthetic admin cleanup failed");
      }
    }
    fs.writeFileSync(
      path.join(out, "browser-acceptance.json"),
      JSON.stringify(
        {
          status: complete ? "PASS" : "INCOMPLETE",
          checked_at,
          checks,
          errors,
        },
        null,
        2,
      ),
    );
    await browser.close();
  }
  assert.ok(complete, "Acceptance incomplete");
  console.log(
    JSON.stringify({
      status: "PASS",
      checks: checks.length,
      scope: "actual user-audit links and admin personal notification entry",
    }),
  );
})().catch((error) => {
  console.error(String(error));
  process.exitCode = 1;
});
