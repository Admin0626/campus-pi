// Prepare fresh fixtures with check_admin_trade_http.py. No response substitutions.
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || "playwright");
const fs = require("node:fs"),
  path = require("node:path"),
  assert = require("node:assert/strict");
const root = path.resolve(__dirname, ".."),
  out = path.join(root, ".local/auth-dev/admin-trade");
const local = JSON.parse(
  fs.readFileSync(path.join(root, ".local/auth-dev/private.json"), "utf8"),
);
const f = JSON.parse(fs.readFileSync(path.join(out, "fixtures.json"), "utf8"));
const base = process.env.WEB_BASE_URL || "http://127.0.0.1:8080",
  checked_at = new Date().toISOString();
const checks = [],
  errors = [];
let complete = false;
fs.writeFileSync(
  path.join(out, "browser-acceptance.json"),
  JSON.stringify({ status: "RUNNING", checked_at }),
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
  });
  page.on("pageerror", (e) => errors.push(String(e)));
  page.on("dialog", async (d) => {
    errors.push("Unexpected script dialog");
    await d.dismiss();
  });
  const check = (value, name) => {
    assert.ok(value, name);
    checks.push({ name, passed: true });
  };
  const ready = () =>
    page.locator(".admin-content-panel[aria-busy=false]").waitFor();
  const shot = (name) =>
    page.screenshot({ path: path.join(out, name + ".png"), fullPage: true });
  const nav = (name) =>
    page
      .locator(".side-menu")
      .getByRole("button", { name, exact: true })
      .click();
  async function findRow(id) {
    const row = page
      .locator("tbody tr")
      .filter({
        has: page
          .locator("td:first-child")
          .filter({ hasText: new RegExp("^" + id + "$") }),
      });
    for (let n = 0; n < 100; n++) {
      await ready();
      if (await row.count()) return row;
      const next = page.getByRole("button", { name: "下一页", exact: true });
      if (await next.isDisabled()) throw Error("Fixture not found");
      await next.click();
    }
    throw Error("Fixture pagination limit reached");
  }
  async function searchOrder(id) {
    await page.getByLabel("订单编号", { exact: true }).fill(id);
    await page.getByRole("button", { name: "查询", exact: true }).click();
    await ready();
    return findRow(id);
  }
  async function confirm(reason, route) {
    const dialog = page.getByRole("dialog");
    await dialog.getByLabel("操作原因").fill(reason);
    const saved = page.waitForResponse(
      (r) =>
        r.url().endsWith("/api/v1" + route) && r.request().method() === "POST",
    );
    await dialog.getByRole("button", { name: "确认操作", exact: true }).click();
    const response = await saved;
    check(
      response.status() === 200,
      "Actual admin mutation succeeded " + route,
    );
    const data = (await response.json()).data;
    await dialog.waitFor({ state: "hidden" });
    await ready();
    return data;
  }
  try {
    await page.goto(base + "/admin/login", { waitUntil: "networkidle" });
    await page.getByLabel("账号", { exact: true }).fill(local.admin_username);
    await page.getByLabel("密码", { exact: true }).fill(local.admin_password);
    await page.getByRole("button", { name: "登录", exact: true }).click();
    await page.waitForURL("**/admin");
    await nav("订单管理");
    await page
      .getByRole("heading", { name: "订单管理", exact: true })
      .waitFor();
    let row = await searchOrder(f.order_id);
    check(
      !(await page
        .locator("body")
        .innerText()
        .then((text) => text.includes(f.address))),
      "Order list never displays private shipping address",
    );
    check(
      (await row.getByText("25.00", { exact: true }).count()) === 1,
      "Order list renders actual snapshot amount",
    );
    await row.getByRole("button", { name: "查看", exact: true }).click();
    let dialog = page.getByRole("dialog");
    await dialog.getByText(f.address, { exact: true }).waitFor();
    check(
      true,
      "Authorized detail reads actual address through audited endpoint",
    );
    await shot("01-order-detail");
    await dialog.getByRole("button", { name: "关闭对话框" }).click();
    check(
      !(await page
        .locator("body")
        .innerText()
        .then((text) => text.includes(f.address))),
      "Closing detail clears address from the page",
    );
    await row.getByRole("button", { name: "取消订单", exact: true }).click();
    await page
      .getByRole("dialog")
      .getByRole("button", { name: "确认操作", exact: true })
      .click();
    check(
      await page
        .getByRole("dialog")
        .getByLabel("操作原因")
        .evaluate((el) => !el.validity.valid),
      "UI requires cancellation reason",
    );
    const canceled = await confirm(
      "浏览器真实取消订单",
      "/admin/orders/" + f.order_id + "/cancel",
    );
    check(
      canceled.shipping_address === null && canceled.order_status === "已取消",
      "Cancellation response is successful and remains address-masked",
    );
    check(
      (await row.getByText("已取消", { exact: true }).count()) === 1 &&
        (await row
          .getByRole("button", { name: "取消订单", exact: true })
          .count()) === 0,
      "Saved cancellation removes the cancel action",
    );
    await shot("02-canceled-order");
    row = await searchOrder(f.task_order);
    check(
      (await row.getByText("任务", { exact: true }).count()) === 1 &&
        (await row
          .getByRole("button", { name: "取消订单", exact: true })
          .count()) === 0,
      "Task order is distinguished from product cancellation",
    );
    await row.getByRole("button", { name: "查看", exact: true }).click();
    dialog = page.getByRole("dialog");
    await dialog
      .getByRole("button", { name: "查看关联任务", exact: true })
      .click();
    await dialog.getByText("20.00 积分", { exact: true }).waitFor();
    check(
      true,
      "Task order detail reads actual reward type instead of labeling points as cash",
    );
    await dialog.getByRole("button", { name: "关闭对话框" }).click();
    await page.getByRole("button", { name: "售后工单", exact: true }).click();
    row = await findRow(f.aftersale_id);
    check(
      (await row.getByRole("button", { name: /通过|驳回/ }).count()) === 0,
      "Product refund cannot be approved from the queue",
    );
    await row.getByRole("button", { name: "查看工单", exact: true }).click();
    dialog = page.getByRole("dialog");
    await dialog.getByText("商品退换及款项问题由双方线下协商；历史工单仅供核对。", { exact: true }).waitFor();
    await page.waitForFunction(() => {
      const image = document.querySelector('[aria-label="文件材料预览"] img');
      return image?.complete && image.naturalWidth > 0;
    });
    check(
      (await dialog.locator("img").count()) === 1,
      "Authorized aftersale evidence displays actual controlled image bytes",
    );
    await shot("03-aftersale-evidence");
    await dialog.getByRole("button", { name: "关闭对话框" }).click();
    await page.getByRole("button", { name: "已支付", exact: true }).click();
    await ready();
    check(
      (await page
        .getByLabel("订单或举报状态", { exact: true })
        .inputValue()) === "已支付" &&
        (await page
          .locator("tbody tr")
          .evaluateAll(
            (rows) =>
              rows.length > 0 &&
              rows.every((row) => row.children[7]?.textContent === "已支付"),
          )),
      "Switching from aftersales to a status tab preserves the requested order filter and clears old row schema",
    );
    await nav("控制台");
    await page.getByRole("button", { name: "举报处理", exact: true }).click();
    await page
      .getByRole("heading", { name: "举报处理", exact: true })
      .waitFor();
    row = await findRow(f.text_report);
    check(
      !(await page
        .locator("tbody")
        .innerText()
        .then((text) => text.includes("浏览器举报证据_" + f.tag))),
      "Report queue hides the linked private message content",
    );
    await row.getByRole("button", { name: "查看证据", exact: true }).click();
    dialog = page.getByRole("dialog");
    await dialog
      .getByText("<script>not executed</script> 浏览器举报证据_" + f.tag, {
        exact: true,
      })
      .waitFor();
    check(
      (await dialog.locator("script").count()) === 0,
      "Private text evidence is rendered as text without executing markup",
    );
    await shot("04-report-text");
    await dialog.getByRole("button", { name: "判定成立", exact: true }).click();
    const founded = await confirm(
      "浏览器举报成立",
      "/admin/message-reports/" + f.text_report + "/review",
    );
    check(
      founded.audit_status === "成立" &&
        (await row.getByText("成立", { exact: true }).count()) === 1,
      "Founded decision persists and updates the report queue",
    );
    row = await findRow(f.image_report);
    await row.getByRole("button", { name: "查看证据", exact: true }).click();
    dialog = page.getByRole("dialog");
    await page.waitForFunction(() => {
      const image = document.querySelector('[aria-label="文件材料预览"] img');
      return image?.complete && image.naturalWidth > 0;
    });
    check(
      (await dialog.locator("img").count()) === 1,
      "Only the reported image is previewed through controlled file access",
    );
    await shot("05-report-image");
    await dialog
      .getByRole("button", { name: "判定不成立", exact: true })
      .click();
    const rejected = await confirm(
      "浏览器举报不成立",
      "/admin/message-reports/" + f.image_report + "/review",
    );
    check(
      rejected.audit_status === "不成立" &&
        (await row.getByText("不成立", { exact: true }).count()) === 1,
      "Unfounded decision follows the actual review endpoint",
    );
    await page
      .getByLabel("订单或举报状态", { exact: true })
      .selectOption("待审核");
    await page.getByRole("button", { name: "查询", exact: true }).click();
    await ready();
    check(
      (await page
        .locator("tbody tr")
        .filter({
          has: page
            .locator("td:first-child")
            .filter({ hasText: new RegExp("^" + f.text_report + "$") }),
        })
        .count()) === 0,
      "Pending report filter excludes processed cases",
    );
    check(
      errors.length === 0,
      "No Vue errors, stale-schema rows or script dialogs across order and report views",
    );
    complete = true;
    process.stdout.write(
      JSON.stringify({
        status: "PASS",
        checks: checks.length,
        scope: "real Chromium + packaged application + real backend",
      }) + "\n",
    );
  } catch (error) {
    await shot("failure").catch(() => {});
    throw error;
  } finally {
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
})().catch((error) => {
  console.error(String(error));
  process.exitCode = 1;
});
