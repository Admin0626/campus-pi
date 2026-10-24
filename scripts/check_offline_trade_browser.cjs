// Real loopback browser/API regression. Requires the existing private synthetic UI fixtures.
// Creates a scanned synthetic product and orders; never substitutes HTTP responses.
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || "playwright");
const fs = require("node:fs"), path = require("node:path"), assert = require("node:assert/strict"), crypto = require("node:crypto");
const root = path.resolve(__dirname, ".."), base = "http://127.0.0.1:8080";
const fixtures = JSON.parse(fs.readFileSync(path.join(root, ".local/remaining/ui-fixtures.json"), "utf8"));
const config = JSON.parse(fs.readFileSync(path.join(root, ".local/auth-dev/private.json"), "utf8"));
const out = path.join(root, ".local/offline-browser");fs.mkdirSync(out, { recursive: true });
const checks = [], errors = [];
const check = (ok, name) => { assert.ok(ok, name); checks.push(name); };
async function call(route, method = "GET", body, token, expected = 200) {
  const headers = { "Idempotency-Key": crypto.randomUUID() };
  if (token) headers.Authorization = "Bearer " + token;
  if (body && !(body instanceof FormData)) { headers["Content-Type"] = "application/json"; body = JSON.stringify(body); }
  const response = await fetch(base + "/api/v1" + route, { method, headers, body });
  const value = await response.json();
  assert.equal(response.status, expected, route + " HTTP " + response.status);
  return value.data;
}
async function token(account, password) { return (await call("/auth/login", "POST", { account, password })).access_token; }
async function login(page, who, admin = false) {
  await page.goto(base + (admin ? "/admin/login" : "/login"));
  await page.locator(admin ? "#admin-account" : "#account").fill(who.name);
  await page.locator(admin ? "#admin-password" : "#login-password").fill(who.password);
  await page.getByRole("button", { name: "登录", exact: true }).click();
  await page.waitForURL(admin ? "**/admin" : "**/home");
}
async function navigate(page, target) {
  // SPA login is deliberately memory-only. Use the existing router without reloading the document.
  await page.evaluate(target => { history.pushState({}, "", target); window.dispatchEvent(new PopStateEvent("popstate")); }, target);
}
async function openOrder(page, name) {
  const card = page.locator(".member-card").filter({ has: page.getByRole("heading", { name, exact: true }) });
  await card.getByRole("button", { name: "订单详情", exact: true }).first().click();
  await page.locator(".member-detail").waitFor();
}
(async () => {
  const browser = await chromium.launch({ executablePath: process.env.CHROME_PATH || "C:/Program Files/Google/Chrome/Application/chrome.exe", headless: true });
  const contexts = [], sessions = [];
  try {
    const sellerToken = await token(fixtures.seller.name, fixtures.seller.password);sessions.push(sellerToken);
    const buyerToken = await token(fixtures.buyer.name, fixtures.buyer.password);sessions.push(buyerToken);
    const adminToken = await token(config.admin_username, config.admin_password);sessions.push(adminToken);
    const form = new FormData();form.append("purpose", "PRODUCT_IMAGE");
    form.append("file", new Blob([fs.readFileSync(path.join(root, "database/manual/上传样例/测试商品.png"))], { type: "image/png" }), "offline-synthetic.png");
    const file = await call("/files", "POST", form, sellerToken, 201);
    const productName = "线下验收_" + crypto.randomBytes(4).toString("hex");
    const product = await call("/products", "POST", { category_id: Number(fixtures.category), product_name: productName, product_desc: "浏览器线下交易合成测试", campus: "测试校区", trade_mode: "校园自提", original_price: "12.00", discount_price: "10.00", stock_quantity: 4, image_refs: [file.file_ref] }, sellerToken, 201);
    await call("/admin/products/" + product.product_id + "/review", "POST", { decision: "通过", reason: "线下订单浏览器验收" }, adminToken);
    async function pageFor(who, admin = false) {
      const context = await browser.newContext({ viewport: { width: 390, height: 844 } });contexts.push(context);
      context.setDefaultTimeout(12000);
      const page = await context.newPage();page.on("pageerror", e => errors.push(e.message));page.on("dialog", d => d.accept());
      await login(page, who, admin);return page;
    }
    const buyer = await pageFor(fixtures.buyer), seller = await pageFor(fixtures.seller);
    await navigate(buyer, "/modules/C");
    await buyer.getByPlaceholder("搜索商品名称").fill(productName);
    await buyer.getByRole("button", { name: "搜索", exact: true }).click();
    await buyer.locator(".member-product-card").filter({ hasText: productName }).getByRole("button", { name: "查看商品", exact: true }).click();
    await buyer.getByRole("button", { name: "预约线下交易", exact: true }).click();
    await buyer.getByLabel("自提交接地点", { exact: true }).fill("合成图书馆线下交接点");
    const createdResponse = buyer.waitForResponse(r => r.url().endsWith("/api/v1/orders/product") && r.request().method() === "POST");
    await buyer.getByRole("button", { name: "确认创建线下订单", exact: true }).click();
    const created = (await (await createdResponse).json()).data;
    await buyer.getByRole("heading", { name: "线下订单已创建", exact: true }).waitFor();
    check(created.order_status === "待线下交易" && created.trade_channel === "OFFLINE", "real product form creates an OFFLINE reservation");
    await buyer.getByRole("button", { name: "查看新订单", exact: true }).click();
    await buyer.getByRole("button", { name: "取消订单", exact: true }).waitFor();
    check(await buyer.getByRole("button", { name: "确认已线下收货", exact: true }).count() === 0, "buyer has no premature receipt control");
    await buyer.screenshot({ path: path.join(out, "buyer-pending.png"), fullPage: true });
    await navigate(seller, "/modules/G");await openOrder(seller, productName);
    check(await seller.getByRole("button", { name: "取消订单", exact: true }).count() === 0, "seller cannot cancel the buyer order");
    await seller.getByRole("button", { name: "确认已线下交付", exact: true }).click();
    await seller.getByText("已确认线下交付，等待买家确认收货", { exact: true }).waitFor();
    check((await call("/orders/" + created.order_id, "GET", undefined, buyerToken)).order_status === "待确认收货", "seller acknowledgement persists through the real API");
    await buyer.getByRole("button", { name: "返回列表", exact: true }).click();await openOrder(buyer, productName);
    await buyer.getByRole("button", { name: "确认已线下收货", exact: true }).click();
    await buyer.getByText("已确认线下收货，订单已完成", { exact: true }).waitFor();
    const completed = await call("/orders/" + created.order_id, "GET", undefined, buyerToken);
    check(completed.order_status === "已完成" && completed.actual_amount === "0.00" && completed.payment_time === null, "receipt completes progress without recording payment");
    check(await buyer.getByRole("button", { name: "取消订单", exact: true }).count() === 0, "completed order cannot be cancelled from the page");
    await buyer.screenshot({ path: path.join(out, "buyer-completed.png"), fullPage: true });
    await buyer.getByRole("button", { name: "联系卖家", exact: true }).click();await buyer.waitForURL("**/modules/H");
    check(true, "order reuses the existing private chat entry");
    const pending = await call("/orders/product", "POST", { product_id: product.product_id, quantity: 1, shipping_address: "合成取消测试点" }, buyerToken, 201);
    await navigate(buyer, "/modules/G");
    // The newest matching order is the pending cancellation fixture.
    await openOrder(buyer, productName);await buyer.getByRole("button", { name: "取消订单", exact: true }).click();
    await buyer.getByText("订单已取消，库存已释放", { exact: true }).waitFor();
    check((await call("/orders/" + pending.order_id, "GET", undefined, buyerToken)).order_status === "已取消", "buyer can cancel an undelivered reservation");
    const admin = await pageFor({ name: config.admin_username, password: config.admin_password }, true);
    await admin.setViewportSize({ width: 1440, height: 1000 });
    await admin.locator(".side-menu").getByRole("button", { name: "订单管理", exact: true }).click();
    await admin.getByPlaceholder("请输入完整订单编号").fill(created.order_id);
    await admin.getByRole("button", { name: "查询", exact: true }).click();
    await admin.locator("tbody tr").filter({ hasText: created.order_id }).waitFor();
    await admin.getByRole("button", { name: "查看", exact: true }).click();
    await admin.getByRole("dialog").getByText("线下交易（校园自提）", { exact: true }).waitFor();
    check(true, "administrator detail identifies the offline channel");
    await admin.screenshot({ path: path.join(out, "admin-completed.png"), fullPage: true });
    await call("/orders/" + created.order_id + "/payment-intents", "POST", { channel: "微信" }, buyerToken, 404);
    check(true, "removed payment route returns 404 on the deployed JAR");
    check((await call("/products/" + product.product_id, "GET", undefined, sellerToken)).stock_quantity === 3, "only the completed item consumes inventory after cancellation");
    check(errors.length === 0, "no browser JavaScript errors");
    fs.writeFileSync(path.join(out, "acceptance.json"), JSON.stringify({ status: "PASS", checks, errors }, null, 2));
    console.log(JSON.stringify({ status: "PASS", checks: checks.length, actual_http: true, screenshots: 3 }));
  } catch (e) {
    fs.writeFileSync(path.join(out, "acceptance.json"), JSON.stringify({ status: "FAIL", checks, error: e.message, errors }, null, 2));
    throw e;
  } finally {
    for (const t of sessions) await call("/auth/logout", "POST", {}, t).catch(() => {});
    for (const c of contexts) await c.close();await browser.close();
  }
})().catch(e => { console.error(e.message); process.exitCode = 1; });
