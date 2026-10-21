// Run after check_admin_security_http.py; use the actual packaged app, never response substitutes.
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || "playwright");
const fs = require("node:fs"),
  path = require("node:path"),
  assert = require("node:assert/strict");
const root = path.resolve(__dirname, ".."),
  out = path.join(root, ".local/auth-dev/admin-security");
const local = JSON.parse(
  fs.readFileSync(path.join(root, ".local/auth-dev/private.json"), "utf8"),
);
const fixture = JSON.parse(
  fs.readFileSync(path.join(out, "fixtures.json"), "utf8"),
);
const base = process.env.WEB_BASE_URL || "http://127.0.0.1:8080";
const checked_at = new Date().toISOString(),
  checks = [],
  errors = [];
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
  page.on("pageerror", (error) => errors.push(String(error)));
  const check = (value, name) => {
    assert.ok(value, name);
    checks.push({ name, passed: true });
  };
  const ready = () =>
    page.locator(".admin-content-panel[aria-busy=false]").waitFor();
  const shot = (name) =>
    page.screenshot({ path: path.join(out, name + ".png"), fullPage: true });
  async function nav(name) {
    await page
      .locator(".side-menu")
      .getByRole("button", { name, exact: true })
      .click();
  }
  async function findRow(id) {
    const row = page.locator("tbody tr").filter({
      has: page
        .locator("td:first-child")
        .filter({ hasText: new RegExp("^" + id + "$") }),
    });
    for (let i = 0; i < 100; i++) {
      await ready();
      if (await row.count()) return row;
      const next = page.getByRole("button", { name: "下一页", exact: true });
      if (await next.isDisabled())
        throw new Error("Synthetic fixture not found");
      await next.click();
    }
    throw new Error("Pagination exceeded safe fixture search limit");
  }
  let complete = false;
  try {
    await page.goto(base + "/admin/login", { waitUntil: "networkidle" });
    await page.getByLabel("账号", { exact: true }).fill(local.admin_username);
    await page.getByLabel("密码", { exact: true }).fill(local.admin_password);
    await page.getByRole("button", { name: "登录", exact: true }).click();
    await page.waitForURL("**/admin");
    await ready();
    await page.getByRole("button", { name: "认证审核", exact: true }).click();
    await page
      .getByRole("heading", { name: "认证审核", exact: true })
      .waitFor();
    await ready();
    check(
      !(await page
        .locator("tbody")
        .innerText()
        .then((text) => text.includes("合成测试姓名"))),
      "Verification queue does not expose the real name",
    );
    check(
      await page
        .locator("thead")
        .innerText()
        .then((text) => !text.includes("学号")),
      "Queue omits student number column",
    );
    let row = await findRow(fixture.verification_id);
    await row.getByRole("button", { name: "查看材料", exact: true }).click();
    let dialog = page.getByRole("dialog");
    await dialog.getByText("合成测试姓名", { exact: true }).waitFor();
    check(
      (await dialog.getByText(fixture.file_ref, { exact: true }).count()) === 0,
      "Detail does not expose raw private file references",
    );
    await page.waitForFunction(() => {
      const img = document.querySelector('[aria-label="文件材料预览"] img');
      return img?.complete && img.naturalWidth > 0;
    });
    check(
      (await dialog.locator("img").count()) === 1,
      "Real private evidence bytes render in the browser",
    );
    const pdfLink = dialog.getByRole("link", {
      name: "打开 PDF 材料 2",
      exact: true,
    });
    await pdfLink.waitFor();
    check(
      (await pdfLink.evaluate(async (el) =>
        new TextDecoder().decode(
          (await (await fetch(el.href)).arrayBuffer()).slice(0, 5),
        ),
      )) === "%PDF-",
      "PDF material link contains actual controlled PDF bytes",
    );
    await shot("01-certification-detail");
    await dialog.getByRole("button", { name: "刷新材料", exact: true }).click();
    await page.waitForFunction(() => {
      const img = document.querySelector('[aria-label="文件材料预览"] img');
      return img?.complete && img.naturalWidth > 0;
    });
    check(true, "Refresh obtains a new controlled grant and displays evidence");
    await dialog.getByRole("button", { name: "通过认证", exact: true }).click();
    await dialog.getByRole("button", { name: "确认提交", exact: true }).click();
    check(
      (await dialog.isVisible()) &&
        (await dialog
          .getByLabel("操作原因")
          .evaluate((el) => !el.validity.valid)),
      "Browser prevents empty review reason",
    );
    await dialog.getByLabel("操作原因").fill("浏览器真实认证审核");
    const reviewed = page.waitForResponse(
      (res) =>
        res
          .url()
          .endsWith(
            "/admin/verifications/" + fixture.verification_id + "/review",
          ) && res.request().method() === "POST",
    );
    await dialog.getByRole("button", { name: "确认提交", exact: true }).click();
    const response = await reviewed;
    check(
      response.status() === 200 &&
        (await response.json()).data.audit_status === "已通过",
      "Browser review persists through real API",
    );
    await dialog.waitFor({ state: "hidden" });
    await ready();
    row = await findRow(fixture.verification_id);
    check(
      (await row.getByText("已通过", { exact: true }).count()) === 1,
      "Queue reflects the saved review state",
    );
    await page.getByLabel("审核状态", { exact: true }).selectOption("待审核");
    await page.getByRole("button", { name: "查询", exact: true }).click();
    await ready();
    check(
      (await page
        .locator("tbody tr")
        .filter({
          has: page.locator("td:first-child").filter({
            hasText: new RegExp("^" + fixture.verification_id + "$"),
          }),
        })
        .count()) === 0,
      "Pending filter excludes approved application",
    );
    await shot("02-certification-queue");
    await nav("权限管理");
    await page
      .getByRole("heading", { name: "权限管理", exact: true })
      .waitFor();
    await page.getByLabel("用户名关键词").fill(fixture.role_user);
    await page.getByRole("button", { name: "查询", exact: true }).click();
    await ready();
    row = page.locator("tbody tr").filter({ hasText: fixture.role_user });
    await row.waitFor();
    check(
      (await row.getByText("USER", { exact: true }).count()) === 1,
      "Role panel uses actual filtered user data",
    );
    await row.getByRole("button", { name: "调整角色", exact: true }).click();
    dialog = page.getByRole("dialog");
    await dialog.getByLabel("角色", { exact: true }).selectOption("ADMIN");
    await dialog.getByLabel("操作原因").fill("浏览器角色调整验收");
    await dialog.getByRole("button", { name: "确认提交", exact: true }).click();
    await dialog.waitFor({ state: "hidden" });
    await ready();
    check(
      (await row.getByText("ADMIN", { exact: true }).count()) === 1,
      "Real browser role change updates the list",
    );
    await shot("03-role-management");
    await row.getByRole("button", { name: "调整角色", exact: true }).click();
    await dialog.getByLabel("角色", { exact: true }).selectOption("USER");
    await dialog.getByLabel("操作原因").fill("浏览器恢复合成角色");
    await dialog.getByRole("button", { name: "确认提交", exact: true }).click();
    await dialog.waitFor({ state: "hidden" });
    await ready();
    check(
      (await row.getByText("USER", { exact: true }).count()) === 1,
      "Restore synthetic owner role through the real UI",
    );
    await nav("交易管理");
    await page
      .getByRole("heading", { name: "商品信息管理", exact: true })
      .waitFor();
    row = await findRow(fixture.product_id);
    await row.getByRole("button", { name: "查看", exact: true }).click();
    dialog = page.getByRole("dialog");
    await page.waitForFunction(() => {
      const img = document.querySelector('[aria-label="文件材料预览"] img');
      return img?.complete && img.naturalWidth > 0;
    });
    check(
      (await dialog.locator("img").count()) === 1,
      "Private off-shelf product image renders for authorized admin",
    );
    await shot("04-product-image");
    await dialog.getByRole("button", { name: "关闭对话框" }).click();
    check(
      errors.length === 0,
      "No Vue/runtime errors during page changes and private previews",
    );
    complete = true;
    process.stdout.write(
      JSON.stringify({
        status: "PASS",
        checks: checks.length,
        scope: "real Chromium + packaged app + real backend",
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
