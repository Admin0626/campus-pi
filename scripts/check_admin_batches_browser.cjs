// Actual packaged UI + real APIs. Run check_admin_batches_http.py first for fresh synthetic fixtures.
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || "playwright");
const fs = require("node:fs"), path = require("node:path"), assert = require("node:assert/strict");
const root = path.resolve(__dirname, ".."), out = path.join(root, ".local/auth-dev/admin-batches");
const c = JSON.parse(fs.readFileSync(path.join(root, ".local/auth-dev/private.json"), "utf8"));
const f = JSON.parse(fs.readFileSync(path.join(out, "fixtures.json"), "utf8"));
const base = "http://127.0.0.1:8080", checks = [], errors = [];
let finished = false;
(async () => {
  const browser = await chromium.launch({ executablePath: "C:/Program Files/Google/Chrome/Application/chrome.exe", headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  page.on("pageerror", e => errors.push(String(e)));
  const ok = (value, name) => { assert.ok(value, name); checks.push({ name, passed: true }); };
  const navigate = async label => {
    await page.locator(".side-menu").getByRole("button", { name: label, exact: true }).click();
    const routes = { "交易管理": "/admin/modules/products", "用户管理": "/admin/users", "内容发布管理": "/admin/modules/content", "悬赏任务": "/admin/modules/tasks" };
    await page.waitForURL(base + routes[label]);
    await page.waitForFunction(() => !document.querySelector('.admin-content-panel[aria-busy="true"],.word-table-loading'));
  };
  const batch = () => page.locator(".admin-batch");
  const dialog = () => page.getByRole("dialog", { name: "批量操作确认" });
  const complete = async (success, failure) => {
    await page.waitForFunction(({ success, failure }) => {
      const text = document.querySelector(".admin-batch summary")?.textContent || "";
      return text.includes("已完成") && text.includes(`成功 ${success}`) && text.includes(`失败 ${failure}`);
    }, { success, failure });
  };
  const submit = async (button, choice) => {
    await batch().getByRole("button", { name: button, exact: true }).click();
    await dialog().getByRole("combobox").selectOption(choice);
    await dialog().getByLabel("操作原因", { exact: true }).fill("真实浏览器批量验收 " + f.tag);
    await dialog().getByRole("button", { name: "确认批量操作", exact: true }).click();
    await dialog().waitFor({ state: "hidden" });
  };
  try {
    await page.goto(base + "/admin/login");
    await page.getByLabel("账号", { exact: true }).fill(c.admin_username);
    await page.getByLabel("密码", { exact: true }).fill(c.admin_password);
    const response = page.waitForResponse(r => r.url().endsWith("/api/v1/auth/login") && r.request().method() === "POST");
    await page.getByRole("button", { name: "登录", exact: true }).click();
    const token = (await (await response).json()).data.access_token;
    await page.waitForURL(base + "/admin");
    await page.locator(".admin-workspace").evaluate(el => el.__batchLayoutIdentity = "same-layout");
    await navigate("交易管理");
    ok(await batch().count() === 1, "Product screen has one shared batch control");
    ok(await batch().getByRole("button", { name: "批量商品审核", exact: true }).isDisabled(), "Empty selection cannot submit");
    await page.getByLabel("选择记录" + f.products[0], { exact: true }).check();
    await page.getByLabel("选择记录" + f.products[1], { exact: true }).check();
    ok((await batch().textContent()).includes("已选 2 条"), "Current page selection count is visible");
    // A real competing single-item request changes one selected record before the batch is confirmed.
    const competing = await page.request.post(base + `/api/v1/admin/products/${f.products[0]}/review`, {
      headers: { Authorization: "Bearer " + token, "Idempotency-Key": crypto.randomUUID() },
      data: { decision: "通过", reason: "浏览器并发状态变更" },
    });
    ok(competing.status() === 200, "Competing single-item review changes actual state");
    await submit("批量商品审核", "通过"); await complete(1, 1);
    ok(await batch().locator(".admin-table-scroll").evaluate(el => el.getBoundingClientRect().height < 250), "Short batch results do not create a large blank table");
    ok((await batch().getByRole("table", { name: "批量操作结果" }).textContent()).includes("STATE_CONFLICT"), "Partial failure reason is presented beside retained success");
    const firstBatchId = (await batch().locator(".admin-batch-id").textContent()).split("：")[1].trim();
    await batch().getByRole("button", { name: "只重试失败项", exact: true }).click();
    ok((await dialog().textContent()).includes("独立处理 1 条记录") && !(await dialog().textContent()).includes(f.products[1]), "Retry confirmation contains only failed item ID");
    await dialog().getByRole("button", { name: "关闭批量确认" }).click();
    await page.screenshot({ path: path.join(out, "01-products-partial.png"), fullPage: true });
    await page.getByLabel("选择记录" + f.products[2], { exact: true }).check();
    await page.locator(".admin-filters").getByRole("button", { name: "筛选", exact: true }).click();
    await page.waitForFunction(() => document.querySelector(".admin-batch-toolbar")?.textContent.includes("已选 0 条"));
    ok(await page.getByLabel("选择记录" + f.products[2], { exact: true }).isChecked() === false, "Filtering clears previous selection");
    await navigate("用户管理");
    await page.getByLabel("用户名", { exact: true }).fill(f.username);
    await page.locator(".word-user-filters").getByRole("button", { name: "搜索", exact: true }).click();
    await page.getByLabel("选择用户" + f.username, { exact: true }).waitFor();
    await page.getByLabel("选择用户" + f.username, { exact: true }).check();
    await submit("批量用户状态", "DISABLED"); await complete(1, 0);
    const userRow = page.locator(".word-user-table tbody tr").filter({ hasText: f.username });
    await userRow.getByText("禁用", { exact: true }).first().waitFor();
    ok((await userRow.textContent()).includes("禁用"), "Bulk user state updates the actual user table");
    const size = await batch().getByRole("button", { name: "查询最近批次", exact: true }).evaluate(el => ({ font: getComputedStyle(el).fontSize, height: el.getBoundingClientRect().height }));
    ok(size.font === "14px" && size.height === 36, "Shared batch controls use the common 14px/36px scale on user page");
    await page.screenshot({ path: path.join(out, "02-users.png"), fullPage: true });
    await navigate("内容发布管理");
    await page.getByRole("button", { name: "帖子信息审核", exact: true }).click();
    await page.getByLabel("选择记录" + f.post, { exact: true }).check();
    await submit("批量帖子审核", "通过"); await complete(1, 0);
    const postRow = page.locator(".admin-content-panel > .admin-table-scroll tbody tr").filter({ hasText: f.post });
    await postRow.getByText("已发布", { exact: true }).waitFor();
    ok(await postRow.count() === 1, "Bulk post review updates actual published state");
    await navigate("悬赏任务");
    await page.getByLabel("选择任务" + f.task, { exact: true }).check();
    await submit("批量任务发布审核", "通过"); await complete(1, 0);
    const taskRow = page.locator(".admin-content-panel > .admin-table-scroll tbody tr").filter({ hasText: "批量_browser_" + f.tag });
    await taskRow.getByText("待接取", { exact: true }).waitFor();
    ok(await taskRow.count() === 1, "Bulk task publication uses real task state");
    await page.screenshot({ path: path.join(out, "03-tasks.png"), fullPage: true });
    await navigate("交易管理");
    await batch().getByRole("combobox").selectOption(firstBatchId);
    await complete(1, 1);
    ok((await batch().textContent()).includes(firstBatchId), "Returning to module restores durable historical result");
    ok(await page.locator(".admin-workspace").evaluate(el => el.__batchLayoutIdentity === "same-layout"), "All batch modules reuse the same workspace layout instance");
    await page.setViewportSize({ width: 900, height: 800 });
    ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), "Batch controls do not overflow the page at 900px");
    ok(errors.length === 0, "No browser runtime errors"); finished = true;
    console.log(JSON.stringify({ status: "PASS", checks: checks.length, scope: "real batch UI and APIs" }));
  } finally {
    if (!finished) await page.screenshot({ path: path.join(out, "browser-incomplete.png"), fullPage: true });
    fs.writeFileSync(path.join(out, "browser-acceptance.json"), JSON.stringify({ checked_at: new Date().toISOString(), status: finished ? "PASS" : "INCOMPLETE", count: checks.length, checks, errors }, null, 2));
    await browser.close();
  }
})().catch(e => { console.error(e); process.exitCode = 1; });
