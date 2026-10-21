// Fresh cases from check_task_disputes_http.py; actual packaged UI and HTTP, no route replacements.
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || "playwright");
const fs = require("node:fs"), path = require("node:path"), assert = require("node:assert/strict");
const root = path.resolve(__dirname, ".."), out = path.join(root, ".local/auth-dev/task-disputes");
const local = JSON.parse(fs.readFileSync(path.join(root, ".local/auth-dev/private.json"), "utf8"));
const f = JSON.parse(fs.readFileSync(path.join(out, "fixtures.json"), "utf8"));
const productFixture = JSON.parse(fs.readFileSync(path.join(root, ".local/auth-dev/admin-trade/fixtures.json"), "utf8"));
const base = process.env.WEB_BASE_URL || "http://127.0.0.1:8080", checked_at = new Date().toISOString();
const checks = [], errors = []; let complete = false;
fs.writeFileSync(path.join(out, "browser-acceptance.json"), JSON.stringify({ status: "RUNNING", checked_at }));
(async () => {
 const browser = await chromium.launch({ executablePath: process.env.CHROME_PATH || "C:/Program Files/Google/Chrome/Application/chrome.exe", headless: true });
 const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
 page.on("pageerror", error => errors.push(String(error)));
 page.on("dialog", async dialog => { errors.push("Unexpected script dialog"); await dialog.dismiss(); });
 const check = (value, name) => { assert.ok(value, name); checks.push({ name, passed: true }); };
 const ready = () => page.locator(".admin-content-panel[aria-busy=false]").waitFor();
 async function findRow(id) {
  const row = page.locator("tbody tr").filter({ has: page.locator("td:first-child").filter({ hasText: new RegExp("^"+id+"$") }) });
  for (let n = 0; n < 100; n++) {
   await ready(); if (await row.count()) return row;
   const next = page.getByRole("button", { name: "下一页", exact: true });
   assert.ok(await next.isEnabled(), "Missing prepared case"); await next.click();
  }
  throw new Error("Case pagination bound exceeded");
 }
 let token;
 async function get(route) {
  const response = await page.request.get(base + "/api/v1" + route, { headers: { Authorization: "Bearer " + token } });
  assert.equal(response.status(), 200); return (await response.json()).data;
 }
 async function snapshots() {
  const result = [];
  for (const [order,task] of [[f.approve_order,f.approve_task],[f.reject_order,f.reject_task]]) {
   const o = await get("/admin/orders/"+order), t = await get("/tasks/"+task);
   result.push({ order: o.order_status, task: t.task_status, actual: o.actual_amount, reward: t.reward_amount });
  }
  return result;
 }
 try {
  await page.goto(base+"/admin/login", { waitUntil: "networkidle" });
  await page.getByLabel("账号", { exact: true }).fill(local.admin_username);
  await page.getByLabel("密码", { exact: true }).fill(local.admin_password);
  const login = page.waitForResponse(r => r.url().endsWith("/api/v1/auth/login") && r.request().method()==="POST");
  await page.getByRole("button", { name: "登录", exact: true }).click();
  const response = await login; assert.equal(response.status(),200); token = (await response.json()).data.access_token;
  await page.waitForURL("**/admin");
  await page.locator(".side-menu").getByRole("button", { name: "订单管理", exact: true }).click();
  await page.getByRole("button", { name: "售后工单", exact: true }).click(); await ready();
  check(await page.getByText("任务零金额争议可审核结案，不改变任务或奖励；商品退款暂未启用。", { exact: true }).isVisible(), "Queue distinguishes task decisions from unavailable product refunds");
  const before = await snapshots();
  for (const [id,decision,expected] of [[f.approve,"通过","已完成"],[f.reject,"驳回","已驳回"]]) {
   await page.getByRole("button", { name: "重置", exact: true }).click();
   const row = await findRow(id); await row.getByRole("button", { name: "查看工单", exact: true }).click();
   let dialog = page.getByRole("dialog");
   await dialog.getByText("任务零金额争议，仅记录审核结论，不退款或变更任务。", { exact: true }).waitFor();
   check((await dialog.getByRole("button", { name: "通过争议", exact: true }).count())===1 && (await dialog.getByRole("button", { name: "驳回争议", exact: true }).count())===1, "Task case offers both real review decisions "+decision);
   check((await dialog.locator("script").count())===0 && (await dialog.innerText()).includes("<script>not executed</script>"), "Application evidence text stays inert "+decision);
   await dialog.getByRole("button", { name: decision+"争议", exact: true }).click();
   await dialog.getByText("争议金额为0.00，仅记录审核结论；任务状态、积分和现金均不改变。", { exact: true }).waitFor();
   check(await dialog.getByLabel("操作原因").getAttribute("required")!==null, "Reason is required before review "+decision);
   await page.screenshot({ path: path.join(out, "review-"+(decision==="通过"?"approve":"reject")+".png"), fullPage: true });
   await dialog.getByLabel("操作原因").fill("浏览器真实争议审核 "+decision+" "+f.tag);
   const saved = page.waitForResponse(r=>r.url().endsWith("/api/v1/admin/aftersales/"+id+"/review")&&r.request().method()==="POST");
   await dialog.getByRole("button", { name: "确认操作", exact: true }).click();
   const result = await saved, body = result.request().postDataJSON();
   check(result.status()===200 && (await result.json()).data.aftersale_status===expected, "Browser actual review returns expected case state "+decision);
   check(body.decision===decision && (decision==="通过" ? body.approve_amount==="0.00" : !("approve_amount" in body)), "Browser sends contracted amount semantics "+decision);
   await dialog.waitFor({ state: "hidden" }); await ready();
   check((await (await findRow(id)).innerText()).includes(expected), "Queue refresh shows persisted review "+decision);
  }
  check(JSON.stringify(await snapshots())===JSON.stringify(before), "Both browser reviews leave actual task/order state and amounts unchanged");
  await page.getByRole("button", { name: "重置", exact: true }).click();
  const product = await findRow(productFixture.aftersale_id); await product.getByRole("button", { name: "查看工单", exact: true }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByText("商品退款暂未启用；异常任务工单需核对原始记录。", { exact: true }).waitFor();
  check((await dialog.getByRole("button", { name: /通过争议|驳回争议/ }).count())===0, "Product refund has no enabled task-only approval controls");
  check(errors.length===0, "No script execution, unexpected dialog or page error");
  complete=true; console.log(JSON.stringify({ status:"PASS", checks:checks.length, scope:"real Chromium task dispute reviews" }));
 } finally {
  fs.writeFileSync(path.join(out,"browser-acceptance.json"), JSON.stringify({status:complete?"PASS":"INCOMPLETE",checked_at,checks,errors},null,2));
  await browser.close();
 }
})().catch(error=>{ console.error(String(error)); process.exitCode=1; });
