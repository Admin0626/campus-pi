// Actual packaged manual-test service and synthetic accounts. No mocked UI/network.
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || "playwright");
const fs = require("node:fs"),
  path = require("node:path"),
  assert = require("node:assert/strict");
const out = path.resolve(__dirname, "../.local/completion-20261023");
fs.mkdirSync(out, { recursive: true });
const base = "http://127.0.0.1:8082",
  checks = [],
  errors = [];
(async () => {
  const browser = await chromium.launch({
    executablePath: "C:/Program Files/Google/Chrome/Application/chrome.exe",
    headless: true,
  });
  const page = await browser.newPage({
    viewport: { width: 1280, height: 800 },
  });
  page.on("pageerror", (e) => errors.push(e.message));
  const check = (value, name) => {
    assert.ok(value, name);
    checks.push({ name, passed: true });
  };
  const nav = async (route) => {
    await page.evaluate((r) => {
      history.pushState({}, "", r);
      dispatchEvent(new PopStateEvent("popstate"));
    }, route);
    await page.waitForLoadState("networkidle");
    await page.waitForTimeout(150);
  };
  let finished = false;
  try {
    await page.goto(base + "/admin/login");
    await page.getByLabel("账号", { exact: true }).fill("admin");
    await page.getByLabel("密码", { exact: true }).fill("123456");
    await page.getByRole("button", { name: "登录", exact: true }).click();
    await page.waitForURL("**/admin");
    await page.locator(".admin-real-metrics article").first().waitFor();
    check(
      (await page.locator(".admin-real-metrics article").count()) === 4,
      "Dashboard uses four real metrics",
    );
    check(
      (await page.locator(".admin-source-note").allTextContents())
        .join("")
        .includes("JSON"),
      "Dashboard labels the reused mock charts",
    );
    await page.screenshot({
      path: path.join(out, "dashboard-1280.png"),
      fullPage: true,
    });
    await nav("/admin/modules/content");
    await page
      .getByRole("button", { name: "悬赏任务审核", exact: true })
      .click();
    check(
      page.url().endsWith("/admin/modules/content") &&
        (await page
          .locator(".admin-content-panel .admin-content-panel")
          .count()) === 1,
      "Task content tab embeds the existing task panel without leaving content context",
    );
    await nav("/admin/modules/tasks");
    await page.getByRole("button", { name: "审核记录", exact: true }).click();
    await page.locator("[data-admin-records] tbody tr").first().waitFor();
    check(
      (await page.locator("[data-admin-records] tbody").textContent()).includes(
        "人工测试",
      ),
      "Global task review history works without preselecting a task",
    );
    await nav("/admin/modules/notices");
    await page.locator('.admin-tabs').getByRole("button", { name: "发送记录", exact: true }).click();
    await page.locator(".admin-data-table tbody tr").first().waitFor();
    check(
      (await page.locator(".admin-data-table tbody").textContent()).includes(
        "人工测试",
      ),
      "Global notification history works without selecting a notice",
    );
    for (const [width, height] of [
      [1280, 800],
      [1918, 944],
      [1512, 1137],
    ]) {
      await page.setViewportSize({ width, height });
      await nav("/admin/modules/analytics");
      await page.locator(".analytics-trend-canvas svg").waitFor();
      await page.waitForTimeout(150);
      check(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth,
        ),
        "Analytics no horizontal overflow " + width,
      );
      check(
        await page.evaluate(() => {
          const card = document.querySelector(".analytics-content-card");
          return (
            Math.max(
              ...Array.from(
                card.querySelectorAll("svg path"),
                (e) => e.getBoundingClientRect().bottom,
              ),
            ) <
            card
              .querySelector(".analytics-category-legend")
              .getBoundingClientRect().top
          );
        }),
        "Donut and legend do not overlap " + width,
      );
      check(
        await page
          .locator(".sidebar")
          .evaluate((e) => e.getBoundingClientRect().width === 240),
        "Shared admin sidebar scale " + width,
      );
      await page.screenshot({
        path: path.join(out, "analytics-" + width + ".png"),
        fullPage: true,
      });
    }
    await nav("/admin/modules/products");
    await page.locator('.admin-batch details').waitFor();
    check(
      (await page.locator(".admin-batch details").getAttribute("open")) ===
        null,
      "Batch history defaults collapsed",
    );
    await nav("/admin/users");
    const row = page
      .locator(".word-user-table tbody tr")
      .filter({ hasText: "manual_buyer" });
    await row.waitFor();
    check(
      await row
        .locator("td")
        .last()
        .evaluate((e) => {
          const r = e.getBoundingClientRect();
          return (
            getComputedStyle(e).position === "sticky" &&
            r.right <= innerWidth + 1
          );
        }),
      "User actions remain visible in a fixed column",
    );
    await nav("/admin/modules/categories");
    check(
      await page
        .locator(".side-menu button.selected")
        .textContent()
        .then((v) => v.includes("交易管理")),
      "Categories highlight the parent trading menu",
    );
    await page.goto(base + "/login");
    await page.getByLabel("学号/用户名", { exact: true }).fill("manual_buyer");
    await page.getByLabel("密码", { exact: true }).fill("CampusPi!2026");
    await page.getByRole("button", { name: "登录", exact: true }).click();
    await page.waitForURL("**/home");
    for (const width of [390, 320]) {
      await page.setViewportSize({ width, height: 844 });
      await nav("/modules/C");
      await page.locator(".member-product-grid").waitFor();
      await page.locator(".member-product-grid img").first().waitFor();
      check(true, "Product cards reuse real file previews " + width);
      await page
        .locator(".member-content")
        .evaluate((e) => (e.scrollTop = e.scrollHeight));
      check(
        await page
          .locator(".member-screen > .word-mobile-tabs")
          .evaluate((e) => {
            const r = e.getBoundingClientRect();
            return r.top >= 0 && r.bottom <= innerHeight + 1;
          }),
        "Bottom tabs stay in the viewport " + width,
      );
      await nav("/modules/D");
      await page.getByRole("button", { name: "发布帖子", exact: true }).click();
      check(
        await page.locator(".member-check-field input").evaluate((e) => {
          const r = e.getBoundingClientRect();
          return r.width === 18 && r.height === 18;
        }),
        "Shared checkbox is compact " + width,
      );
      await nav("/modules/H");
      await page
        .getByRole("button", { name: "进入会话", exact: true })
        .first()
        .click();
      await page.locator(".member-messages").waitFor();
      check(
        await page.locator(".member-chat-compose").evaluate((e) => {
          const r = e.getBoundingClientRect();
          return r.top >= 0 && r.bottom <= innerHeight;
        }),
        "Chat composer stays visible " + width,
      );
      check(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth,
        ),
        "Member no horizontal overflow " + width,
      );
      await page.screenshot({
        path: path.join(out, "chat-" + width + ".png"),
        fullPage: true,
      });
    }
    check(errors.length === 0, "No uncaught browser exceptions");
    finished = true;
  } finally {
    if (!finished)
      await page.screenshot({
        path: path.join(out, "layout-incomplete.png"),
        fullPage: true,
      });
    fs.writeFileSync(
      path.join(out, "layout-verification.json"),
      JSON.stringify(
        {
          status: finished ? "PASS" : "FAIL",
          count: checks.length,
          checks,
          errors,
        },
        null,
        2,
      ),
    );
    await browser.close();
  }
  console.log(JSON.stringify({ status: "PASS", checks: checks.length }));
})().catch((e) => {
  console.error(e.message);
  process.exitCode = 1;
});
