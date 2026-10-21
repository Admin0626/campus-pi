const { chromium } = require(process.env.PLAYWRIGHT_MODULE || "playwright");
const fs = require("node:fs"),
  path = require("node:path"),
  assert = require("node:assert/strict");
const root = path.resolve(__dirname, ".."),
  out = path.join(root, ".local/auth-dev/admin-content");
const local = JSON.parse(
  fs.readFileSync(path.join(root, ".local/auth-dev/private.json"), "utf8"),
);
const fixture = JSON.parse(
  fs.readFileSync(path.join(out, "fixtures.json"), "utf8"),
);
const base = process.env.WEB_BASE_URL || "http://127.0.0.1:8080";
const checkedAt = new Date().toISOString();
fs.writeFileSync(
  path.join(out, "browser-acceptance.json"),
  JSON.stringify({ status: "RUNNING", checked_at: checkedAt }),
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
    }),
    checks = [],
    errors = [];
  page.on("pageerror", (e) => errors.push(String(e)));
  function check(value, name) {
    assert.ok(value, name);
    checks.push({ name, passed: true });
  }
  async function nav(name) {
    await page
      .locator(".side-menu")
      .getByRole("button", { name, exact: true })
      .click();
  }
  async function submitReason(reason) {
    const dialog = page.getByRole("dialog");
    await dialog.getByLabel("操作原因").fill(reason);
    await dialog.getByRole("button", { name: "确认操作", exact: true }).click();
    await dialog.waitFor({ state: "hidden" });
    await page.locator(".admin-content-panel[aria-busy=false]").waitFor();
  }
  try {
    await page.goto(base + "/admin/login", { waitUntil: "networkidle" });
    await page.getByLabel("账号", { exact: true }).fill(local.admin_username);
    await page.getByLabel("密码", { exact: true }).fill(local.admin_password);
    await page.getByRole("button", { name: "登录", exact: true }).click();
    await page.waitForURL("**/admin");
    await page.locator(".admin-real-metrics article").first().waitFor();
    check(
      (await page.locator(".admin-real-metrics article").count()) === 5,
      "Dashboard displays five real contract metrics",
    );
    await page.screenshot({
      path: path.join(out, "01-dashboard.png"),
      fullPage: true,
    });
    await nav("交易管理");
    await page
      .getByRole("heading", { name: "商品信息管理", exact: true })
      .waitFor();
    let row = page
      .locator("tbody tr")
      .filter({ hasText: "回滚测试" + fixture.suffix });
    await row.waitFor();
    await row.getByRole("button", { name: "查看", exact: true }).click();
    await page.getByRole("dialog").getByText("描述", { exact: true }).waitFor();
    check(
      (await page
        .getByRole("dialog")
        .getByText("待审核", { exact: true })
        .count()) === 1,
      "Product detail displays actual pending state",
    );
    await page.getByRole("button", { name: "关闭对话框" }).click();
    await row.getByRole("button", { name: "通过", exact: true }).click();
    await submitReason("浏览器商品审核");
    check(
      (await row.getByText("已通过", { exact: true }).count()) === 1,
      "Browser review updates real product state",
    );
    await row.getByRole("button", { name: "下架", exact: true }).click();
    await submitReason("浏览器下架");
    check(
      (await row.getByText("已下架", { exact: true }).count()) === 1,
      "Browser shelf mutation persists",
    );
    await page.screenshot({
      path: path.join(out, "02-products.png"),
      fullPage: true,
    });
    await page
      .locator(".admin-tabs")
      .getByRole("button", { name: "分类管理", exact: true })
      .click();
    await page
      .getByRole("heading", { name: "分类管理", exact: true, level: 2 })
      .waitFor();
    await page
      .getByRole("button", { name: "＋ 新增分类", exact: true })
      .click();
    let dialog = page.getByRole("dialog");
    const category = "浏览器分类" + Date.now().toString(36);
    await dialog.getByLabel("分类名称", { exact: true }).fill(category);
    await dialog.getByLabel("分类描述").fill("浏览器真实保存");
    await dialog.getByRole("button", { name: "保存分类" }).click();
    await dialog.waitFor({ state: "hidden" });
    let categoryRow = page
      .locator(".admin-category-root")
      .filter({ hasText: category });
    await categoryRow.waitFor();
    check(true, "Browser creates a persisted root category");
    await categoryRow
      .getByRole("button", { name: "编辑", exact: true })
      .click();
    dialog = page.getByRole("dialog");
    await dialog.getByLabel("分类描述").fill("已修改的分类描述");
    await dialog.getByRole("button", { name: "保存分类" }).click();
    await dialog.waitFor({ state: "hidden" });
    await categoryRow.getByText("已修改的分类描述").waitFor();
    check(true, "Category edit refreshes saved data");
    await categoryRow
      .getByRole("button", { name: "删除", exact: true })
      .click();
    await submitReason("浏览器分类清理");
    check(
      (await categoryRow.count()) === 0,
      "Browser deletes unreferenced category with Chinese reason",
    );
    const categoryData = (
      await (await page.request.get(base + "/api/v1/categories")).json()
    ).data.items;
    const rendered = await page
      .locator(".admin-category-root")
      .evaluateAll((nodes) => nodes.map((n) => n.dataset.categoryId));
    fs.writeFileSync(
      path.join(out, "tree-debug.json"),
      JSON.stringify({ server: categoryData, rendered }, null, 2),
    );
    check(
      rendered.length === new Set(rendered).size &&
        rendered.length ===
          categoryData.filter((x) => x.parent_category_id === null).length,
      "Category tree stays unique and matches server after create/edit/delete",
    );
    await page.screenshot({
      path: path.join(out, "03-categories.png"),
      fullPage: true,
    });
    await nav("内容发布管理");
    await page
      .getByRole("button", { name: "帖子信息审核", exact: true })
      .click();
    await page.getByLabel("标题", { exact: true }).fill(fixture.suffix);
    await page.getByRole("button", { name: "筛选", exact: true }).click();
    await page.locator(".admin-content-panel[aria-busy=false]").waitFor();
    row = page.locator("tbody tr").filter({ hasText: fixture.anonymous_post });
    await row.waitFor();
    check(
      (await row.getByText("校园同学", { exact: true }).count()) === 1,
      "Anonymous poster remains hidden in admin browser",
    );
    await row.getByRole("button", { name: "查看", exact: true }).click();
    check(
      (await page.getByRole("dialog").textContent()).includes(
        "<script>alert(1)</script>",
      ),
      "Post body is rendered as plain text",
    );
    await page.getByRole("button", { name: "关闭对话框" }).click();
    await row.getByRole("button", { name: "通过", exact: true }).click();
    await submitReason("浏览器帖子审核");
    await row.getByText("已发布", { exact: true }).waitFor();
    check(true, "Browser approves post via real backend");
    await row.getByRole("button", { name: "下架", exact: true }).click();
    await submitReason("浏览器帖子下架");
    await row.getByText("已下架", { exact: true }).waitFor();
    check(true, "Browser withdraws published post");
    await page.screenshot({
      path: path.join(out, "04-posts.png"),
      fullPage: true,
    });
    await nav("控制台");
    await page.getByRole("button", { name: "操作日志 →", exact: true }).click();
    await page
      .getByRole("heading", { name: "操作日志", exact: true, level: 2 })
      .waitFor();
    await page.locator(".admin-content-panel[aria-busy=false]").waitFor();
    check(
      (await page.locator("tbody").innerText()).includes("浏览器帖子下架"),
      "Audit browser displays real operation history",
    );
    await page.screenshot({
      path: path.join(out, "05-audit.png"),
      fullPage: true,
    });
    check(errors.length === 0, "No browser runtime errors");
    await page.setViewportSize({ width: 390, height: 844 });
    await page.screenshot({
      path: path.join(out, "06-mobile.png"),
      fullPage: true,
    });
    fs.writeFileSync(
      path.join(out, "browser-acceptance.json"),
      JSON.stringify(
        { status: "PASS", checked_at: checkedAt, checks, errors },
        null,
        2,
      ),
    );
    console.log(JSON.stringify({ status: "PASS", checks: checks.length }));
  } catch (e) {
    fs.writeFileSync(
      path.join(out, "browser-acceptance.json"),
      JSON.stringify(
        {
          status: "INCOMPLETE",
          checked_at: checkedAt,
          message: e.message,
          checks,
          errors,
        },
        null,
        2,
      ),
    );
    await page.screenshot({
      path: path.join(out, "failure.png"),
      fullPage: true,
    });
    fs.writeFileSync(
      path.join(out, "browser-failure.json"),
      JSON.stringify({ message: e.message, checks, errors }, null, 2),
    );
    throw e;
  } finally {
    await browser.close();
  }
})().catch((e) => {
  console.error(e.message);
  process.exitCode = 1;
});
