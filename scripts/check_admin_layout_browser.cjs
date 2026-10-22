// Actual packaged application; read-only page visits, no HTTP response replacements.
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || "playwright");
const fs = require("node:fs"),
  path = require("node:path"),
  assert = require("node:assert/strict");
const root = path.resolve(__dirname, ".."),
  out = path.join(root, ".local/auth-dev/admin-layout");
const local = JSON.parse(
  fs.readFileSync(path.join(root, ".local/auth-dev/private.json"), "utf8"),
);
const base = process.env.WEB_BASE_URL || "http://127.0.0.1:8080";
fs.mkdirSync(out, { recursive: true });
(async () => {
  const browser = await chromium.launch({
    executablePath:
      process.env.CHROME_PATH ||
      "C:/Program Files/Google/Chrome/Application/chrome.exe",
    headless: true,
  });
  const checks = [],
    errors = [];
  const ok = (value, name) => {
    assert.ok(value, name);
    checks.push({ name, passed: true });
  };
  try {
    const page = await browser.newPage({
      viewport: { width: 1918, height: 944 },
    });
    const settle = async () => {
      await page.waitForFunction(
        () =>
          !document.querySelector(
            '.admin-workspace [aria-busy="true"], .word-table-loading',
          ),
      );
    };
    const historyEntry = async (route) => {
      await page.evaluate((route) => {
        history.pushState({}, "", route);
        dispatchEvent(new PopStateEvent("popstate"));
      }, route);
      await page.waitForURL(base + route);
      await settle();
    };
    page.on("pageerror", (e) => errors.push(String(e)));
    await page.goto(base + "/admin/login");
    await page.getByLabel("账号", { exact: true }).fill(local.admin_username);
    await page.getByLabel("密码", { exact: true }).fill(local.admin_password);
    await page.getByRole("button", { name: "登录", exact: true }).click();
    await page.waitForURL(base + "/admin");
    await page.waitForLoadState("networkidle");
    await page
      .locator(".admin-workspace")
      .evaluate((el) => (el.__layoutIdentity = "shared-instance"));
    const entries = [
      ["控制台", "/admin"],
      ["内容发布管理", "/admin/modules/content"],
      ["用户管理", "/admin/users"],
      ["交易管理", "/admin/modules/products"],
      ["悬赏任务", "/admin/modules/tasks"],
      ["订单管理", "/admin/modules/orders"],
      ["通知管理", "/admin/modules/notices"],
      ["数据分析", "/admin/modules/analytics"],
      ["权限管理", "/admin/modules/permissions"],
    ];
    for (const [width, height] of [
      [1918, 944],
      [1280, 800],
      [2560, 1440],
    ]) {
      await page.setViewportSize({ width, height });
      for (const [label, route] of entries) {
        await page
          .locator(".side-menu")
          .getByRole("button", { name: label, exact: true })
          .click();
        await page.waitForURL(base + route);
        await settle();
        const metrics = await page.evaluate(() => {
          const root = document.querySelector(".admin-workspace"),
            sidebar = root.querySelector(".sidebar"),
            header = root.querySelector(".app-header");
          const firstCell = root.querySelector("tbody td"),
            pager = root.querySelector(".admin-pagination");
          return {
            sidebar: sidebar.getBoundingClientRect().width,
            header: header.getBoundingClientRect().height,
            navFont: getComputedStyle(root.querySelector(".side-menu > button"))
              .fontSize,
            titleFont: getComputedStyle(
              root.querySelector(".word-admin-page-title"),
            ).fontSize,
            cellFont: firstCell && getComputedStyle(firstCell).fontSize,
            actionHeight: root
              .querySelector(".admin-section-title button")
              ?.getBoundingClientRect().height,
            filterHeight: root
              .querySelector(
                ".admin-filters input, .admin-filters select, .word-user-filters input",
              )
              ?.getBoundingClientRect().height,
            previousDisabled: !pager || pager.querySelector("button").disabled,
            overflow: document.documentElement.scrollWidth > innerWidth,
            singleShell:
              document.querySelectorAll(".sidebar").length === 1 &&
              document.querySelectorAll(".app-header").length === 1,
            reused: root.__layoutIdentity === "shared-instance",
          };
        });
        ok(
          metrics.sidebar === 240 &&
            metrics.header === 64 &&
            metrics.navFont === "14px" &&
            metrics.titleFont === "20px",
          `${width}x${height} ${label}: stable workspace dimensions`,
        );
        ok(
          !metrics.overflow && metrics.singleShell && metrics.reused,
          `${width}x${height} ${label}: same layout instance, one navigation/header, no page overflow`,
        );
        ok(
          (!metrics.cellFont || metrics.cellFont === "14px") &&
            metrics.previousDisabled,
          `${width}x${height} ${label}: readable table and disabled first-page previous button`,
        );
        ok(
          (!metrics.actionHeight || metrics.actionHeight === 36) &&
            (!metrics.filterHeight || metrics.filterHeight === 36),
          `${width}x${height} ${label}: shared 36px actions and filter controls`,
        );
        if (
          width === 1918 &&
          ["悬赏任务", "数据分析", "用户管理"].includes(label)
        ) {
          await page.mouse.move(0, 0);
          await page.screenshot({
            path: path.join(out, label + "-1918x944.png"),
            fullPage: true,
            animations: "disabled",
          });
        }
      }
    }
    await page
      .locator(".side-menu")
      .getByRole("button", { name: "用户管理", exact: true })
      .click();
    await page.waitForURL(base + "/admin/users");
    await settle();
    await page.getByLabel("每页显示条数").selectOption("20");
    await settle();
    ok(
      (await page.locator("tbody tr").count()) === 20,
      "shared page-size selector reloads twenty actual users",
    );
    await page.getByRole("button", { name: "下一页", exact: true }).click();
    await page.waitForFunction(
      () =>
        document.querySelector(".admin-current-page")?.textContent.trim() ===
        "2",
    );
    await settle();
    ok(
      await page
        .locator(".admin-pagination")
        .innerText()
        .then((text) => text.includes("显示21至40条")),
      "shared next button loads the second actual user page",
    );
    await page.getByRole("button", { name: "上一页", exact: true }).click();
    await page.waitForFunction(
      () =>
        document.querySelector(".admin-current-page")?.textContent.trim() ===
        "1",
    );
    await settle();
    ok(
      await page
        .getByRole("button", { name: "上一页", exact: true })
        .isDisabled(),
      "shared previous button returns to first page and becomes disabled",
    );
    // Same width, different height must not change typography or sidebar dimensions.
    for (const height of [720, 1080]) {
      await page.setViewportSize({ width: 1920, height });
      ok(
        await page
          .locator(".sidebar")
          .evaluate(
            (el) =>
              el.clientWidth === 239 &&
              getComputedStyle(document.querySelector(".word-admin-page-title"))
                .fontSize === "20px",
          ),
        `1920x${height}: changing height does not rescale workspace`,
      );
    }
    for (const route of [
      "categories",
      "audit",
      "verifications",
      "reports",
      "settings",
    ]) {
      await historyEntry("/admin/modules/" + route);
      ok(
        (await page
          .locator(".sidebar")
          .evaluate((el) => el.getBoundingClientRect().width === 240)) &&
          (await page
            .locator(".app-header")
            .evaluate((el) => el.getBoundingClientRect().height === 64)),
        `${route}: secondary entry uses shared workspace`,
      );
    }
    for (const width of [768, 390, 320]) {
      await page.setViewportSize({ width, height: 844 });
      await historyEntry("/admin/modules/tasks");
      const toggle = page.getByRole("button", {
        name: "管理导航",
        exact: true,
      });
      ok(
        await page
          .locator(".sidebar")
          .evaluate(
            (el) => el.inert && getComputedStyle(el).visibility === "hidden",
          ),
        `${width}: closed navigation is inaccessible to keyboard focus`,
      );
      await toggle.click();
      ok(
        (await toggle.getAttribute("aria-expanded")) === "true" &&
          (await page.locator(".sidebar").isVisible()),
        `${width}: shared navigation opens from header`,
      );
      await page
        .locator(".side-menu")
        .getByRole("button", { name: "数据分析", exact: true })
        .click();
      await page.waitForURL(base + "/admin/modules/analytics");
      await page.waitForLoadState("networkidle");
      ok(
        (await toggle.getAttribute("aria-expanded")) === "false" &&
          (await page.locator(".sidebar").evaluate((el) => el.inert)),
        `${width}: choosing a route closes shared navigation`,
      );
      ok(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth,
        ),
        `${width}: analytics fits page width without shrinking the workspace`,
      );
      await toggle.click();
      await page.keyboard.press("Escape");
      ok(
        (await toggle.getAttribute("aria-expanded")) === "false" &&
          (await toggle.evaluate((el) => el === document.activeElement)),
        `${width}: Escape closes menu and restores focus`,
      );
      await page.mouse.move(0, 0);
      await page.screenshot({
        path: path.join(out, `analytics-${width}.png`),
        fullPage: true,
        animations: "disabled",
      });
    }
    ok(errors.length === 0, "no browser JavaScript errors");
    fs.writeFileSync(
      path.join(out, "acceptance.json"),
      JSON.stringify(
        {
          status: "PASS",
          checked_at: new Date().toISOString(),
          checks,
          errors,
        },
        null,
        2,
      ),
    );
    console.log(JSON.stringify({ status: "PASS", checks: checks.length }));
  } finally {
    await browser.close();
  }
})().catch((error) => {
  console.error(error.message);
  process.exit(1);
});
