const { chromium } = require(process.env.PLAYWRIGHT_MODULE || "playwright");
const fs = require("node:fs"),
  path = require("node:path"),
  { pathToFileURL } = require("node:url");
const root = path.resolve(__dirname, ".."),
  out = path.join(root, ".local/auth-dev/analytics");
fs.mkdirSync(out, { recursive: true });
const base = process.env.WEB_BASE_URL || "http://127.0.0.1:8080";
const local = JSON.parse(
  fs.readFileSync(path.join(root, ".local/auth-dev/private.json"), "utf8"),
);
const fixture = JSON.parse(
  fs.readFileSync(
    path.join(root, "frontend/public/mock/analytics.json"),
    "utf8",
  ),
);
(async () => {
  const { periodView, countFormat, moneyFormat } = await import(
    pathToFileURL(path.join(root, "frontend/src/analytics/data.js"))
  );
  const browser = await chromium.launch({
    executablePath:
      process.env.CHROME_PATH ||
      "C:/Program Files/Google/Chrome/Application/chrome.exe",
    headless: true,
  });
  try {
    const page = await browser.newPage({
      viewport: { width: 1474, height: 1114 },
      acceptDownloads: true,
    });
    await page.addInitScript(() => {
      window.__analyticsResizeErrors = [];
      window.addEventListener("error", (event) => {
        if (event.message.includes("ResizeObserver"))
          window.__analyticsResizeErrors.push(event.message);
      });
    });
    const checks = [],
      errors = [],
      requests = [];
    page.on("pageerror", (e) => errors.push(String(e)));
    page.on("request", (r) => requests.push(r.url()));
    const ok = (value, name) => {
      if (!value) throw Error(name);
      checks.push({ name, passed: true });
    };
    await page.goto(base + "/admin/modules/analytics", {
      waitUntil: "networkidle",
    });
    await page.waitForURL("**/admin/login");
    ok(true, "direct analytics entry requires administrator sign-in");
    await page.getByLabel("账号", { exact: true }).fill(local.admin_username);
    await page.getByLabel("密码", { exact: true }).fill(local.admin_password);
    await page.getByRole("button", { name: "登录", exact: true }).click();
    await page.waitForURL("**/admin");
    await page.getByRole("button", { name: "数据分析", exact: true }).click();
    await page
      .getByRole("heading", { name: "用户增长趋势", exact: true })
      .waitFor();
    ok(
      await page.getByText("JSON 模拟数据", { exact: true }).isVisible(),
      "mock source explicitly visible",
    );
    await page
      .getByLabel("关闭提示")
      .click()
      .catch(() => {});
    const periodButtons = page.getByRole("group", { name: "指标统计周期" });
    for (const [key, label] of [
      ["week", "本周"],
      ["month", "本月"],
      ["quarter", "本季度"],
    ]) {
      await periodButtons
        .getByRole("button", { name: label, exact: true })
        .click();
      const v = periodView(fixture, key);
      for (const field of [
        "totalUsers",
        "activeUsers",
        "tradeAmountFen",
        "contentTotal",
      ]) {
        const expected =
          field === "tradeAmountFen"
            ? moneyFormat(v.current[field], 0)
            : countFormat(v.current[field]);
        ok(
          (await page.locator("[data-metric=" + field + "]").innerText()) ===
            expected,
          key + " " + field + " matches JSON aggregation",
        );
      }
    }
    const rangeButtons = page.getByRole("group", { name: "用户趋势时间范围" });
    for (const [name, count] of [
      ["周", 7],
      ["月", 30],
      ["年", 12],
    ]) {
      await rangeButtons.getByRole("button", { name, exact: true }).click();
      ok(
        (await page.locator(".trend-hit").count()) === count,
        "trend " + name + " observation count",
      );
    }
    await rangeButtons.getByRole("button", { name: "周", exact: true }).click();
    await page.locator(".trend-hit").first().focus();
    await page.keyboard.press("ArrowRight");
    ok(
      await page
        .locator(".trend-hit")
        .nth(1)
        .evaluate((e) => e === document.activeElement),
      "trend points support keyboard navigation",
    );
    await page.getByRole("button", { name: "新增用户", exact: true }).click();
    ok(
      (await page
        .getByRole("button", { name: "新增用户", exact: true })
        .getAttribute("aria-pressed")) === "false",
      "trend legend toggles a series",
    );
    await page.getByRole("button", { name: "活跃用户", exact: true }).click();
    ok(
      await page
        .getByText("点击上方图例，选择需要展示的数据", { exact: true })
        .isVisible(),
      "all hidden trend series have a clear empty state",
    );
    await page.getByRole("button", { name: "新增用户", exact: true }).click();
    await page.getByRole("button", { name: "活跃用户", exact: true }).click();
    const tradeLegend = page
      .locator(".analytics-pie-card .analytics-category-legend button")
      .first();
    await tradeLegend.click();
    ok(
      (await tradeLegend.getAttribute("aria-pressed")) === "true" &&
        (
          await page
            .locator(".analytics-pie-card .analytics-category-readout")
            .innerText()
        ).includes("电子产品"),
      "pie selection exposes classification value",
    );
    const contentLegend = page
      .locator(".analytics-content-card .analytics-category-legend button")
      .nth(1);
    await contentLegend.focus();
    await page.keyboard.press("Enter");
    ok(
      (await contentLegend.getAttribute("aria-pressed")) === "true",
      "donut legend supports keyboard selection",
    );
    await page.locator(".analytics-bar").first().focus();
    ok(
      await page
        .locator(".analytics-bar-canvas .analytics-chart-tooltip")
        .isVisible(),
      "bar tooltip works on keyboard focus",
    );
    await periodButtons
      .getByRole("button", { name: "本月", exact: true })
      .click();
    const downloadPromise = page.waitForEvent("download");
    await page
      .locator(".analytics-toolbar")
      .getByRole("button", { name: "导出CSV", exact: true })
      .click();
    const download = await downloadPromise;
    const filename = path.join(out, download.suggestedFilename());
    await download.saveAs(filename);
    const bytes = fs.readFileSync(filename),
      csv = bytes.toString("utf8");
    ok(
      bytes.subarray(0, 3).equals(Buffer.from([239, 187, 191])) &&
        csv.includes("非真实业务统计") &&
        csv.includes(
          String(periodView(fixture, "month").current.tradeAmountFen),
        ),
      "downloaded CSV labels mock provenance and exact selected total",
    );
    await page
      .getByRole("button", { name: "查看交易分类明细", exact: true })
      .click();
    ok(
      (await page.locator(".analytics-data-table tbody tr").count()) ===
        periodView(fixture, "month").rows.length,
      "detail table matches selected date window",
    );
    await page
      .getByRole("button", { name: "收起模拟数据明细", exact: true })
      .click();
    await page.route("**/mock/analytics.json", (route) =>
      route.fulfill({ status: 503, body: "unavailable" }),
    );
    await page
      .getByRole("button", { name: "刷新模拟数据", exact: true })
      .click();
    await page
      .getByRole("heading", { name: "模拟数据暂不可用", exact: true })
      .waitFor();
    ok(
      true,
      "JSON load failure is explicit and does not silently fabricate data",
    );
    await page.unroute("**/mock/analytics.json");
    await page.getByRole("button", { name: "重新加载", exact: true }).click();
    await page
      .getByRole("heading", { name: "用户增长趋势", exact: true })
      .waitFor();
    ok(true, "mock load failure can be retried");
    await page.route("**/mock/analytics.json", (route) =>
      route.fulfill({
        contentType: "application/json",
        body: '{"source":"MOCK"}',
      }),
    );
    await page
      .getByRole("button", { name: "刷新模拟数据", exact: true })
      .click();
    await page
      .getByRole("heading", { name: "模拟数据暂不可用", exact: true })
      .waitFor();
    ok(
      (await page.getByRole("alert").innerText()).includes("格式不正确"),
      "invalid JSON schema is rejected",
    );
    await page.unroute("**/mock/analytics.json");
    const zero = structuredClone(fixture);
    zero.openingUsers = 0;
    zero.daily.forEach((r) => {
      r.newUsers = 0;
      r.activeUsers = 0;
      Object.keys(r.tradeAmountFen).forEach((k) => (r.tradeAmountFen[k] = 0));
      Object.keys(r.contentCounts).forEach((k) => (r.contentCounts[k] = 0));
    });
    Object.keys(zero.monthlyActiveUsers).forEach(
      (k) => (zero.monthlyActiveUsers[k] = 0),
    );
    Object.values(zero.periods).forEach((p) => {
      p.activeUsers = 0;
      p.comparisonActiveUsers = 0;
    });
    await page.route("**/mock/analytics.json", (route) =>
      route.fulfill({
        contentType: "application/json",
        body: JSON.stringify(zero),
      }),
    );
    await page.getByRole("button", { name: "重新加载", exact: true }).click();
    await page
      .getByRole("heading", { name: "用户增长趋势", exact: true })
      .waitFor();
    ok(
      (await page.getByText("暂无数据", { exact: true }).count()) === 2 &&
        !(await page.locator(".analytics-page").innerText()).match(
          /NaN|Infinity/,
        ),
      "zero dataset shows honest empty classifications and finite metrics",
    );
    await page.unroute("**/mock/analytics.json");
    await page
      .getByRole("button", { name: "刷新模拟数据", exact: true })
      .click();
    await page.waitForFunction(
      () =>
        document.querySelector("[data-metric=totalUsers]")?.textContent !== "0",
    );
    await periodButtons
      .getByRole("button", { name: "本周", exact: true })
      .click();
    await page.screenshot({
      path: path.join(out, "01-Word5-36-desktop.png"),
      fullPage: true,
      animations: "disabled",
    });
    for (const width of [390, 320]) {
      await page.setViewportSize({ width, height: 844 });
      await page.waitForFunction(() => {
        const canvas = document.querySelector(".analytics-trend-canvas");
        return (
          Math.abs(
            canvas.clientWidth -
              canvas.querySelector("svg").viewBox.baseVal.width,
          ) < 1.5
        );
      });
      ok(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth,
        ),
        width + "px layout has no horizontal page overflow",
      );
      ok(
        await page
          .locator(".analytics-trend-canvas .analytics-grid text")
          .first()
          .evaluate((e) => e.getBoundingClientRect().height >= 9),
        width + "px trend axis labels remain readable",
      );
      ok(
        await page
          .locator("[data-metric]")
          .evaluateAll((nodes) =>
            nodes.every((n) => n.scrollWidth <= n.clientWidth),
          ),
        width + "px metric numbers are not clipped",
      );
      await page.screenshot({
        path: path.join(out, "02-mobile-" + width + ".png"),
        fullPage: true,
        animations: "disabled",
      });
    }
    for (const width of [900, 700, 1474]) {
      await page.setViewportSize({ width, height: 1114 });
      await page.waitForFunction(() => {
        const canvas = document.querySelector(".analytics-trend-canvas");
        return (
          Math.abs(
            canvas.clientWidth -
              canvas.querySelector("svg").viewBox.baseVal.width,
          ) < 1.5
        );
      });
    }
    ok(
      await page.evaluate(() => window.__analyticsResizeErrors.length === 0),
      "responsive chart resizing produces no ResizeObserver loop warnings",
    );
    ok(
      !requests.some((url) =>
        /\/api\/.*(analytics|statistics|overview)/.test(url),
      ),
      "visualizations request local mock JSON without statistics API",
    );
    ok(
      errors.length === 0,
      "analytics interactions have no browser JavaScript errors",
    );
    fs.writeFileSync(
      path.join(out, "acceptance.json"),
      JSON.stringify(
        {
          status: "PASS",
          scope: "Mock analytics UI only; not backend statistics acceptance",
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
        statisticsBackendImplemented: false,
      }),
    );
  } finally {
    await browser.close();
  }
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
