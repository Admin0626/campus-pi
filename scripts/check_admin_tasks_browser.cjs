// Run check_admin_tasks_http.py first. This drives the packaged app and actual APIs.
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || "playwright");
const fs = require("node:fs"),
  path = require("node:path"),
  assert = require("node:assert/strict");
const root = path.resolve(__dirname, ".."),
  out = path.join(root, ".local/auth-dev/admin-tasks");
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
  page.on("dialog", async (dialog) => {
    errors.push("Unexpected script dialog");
    await dialog.dismiss();
  });
  const check = (value, name) => {
    assert.ok(value, name);
    checks.push({ name, passed: true });
  };
  const ready = () =>
    page.locator(".admin-content-panel[aria-busy=false]").waitFor();
  const screenshot = (name) =>
    page.screenshot({ path: path.join(out, name + ".png"), fullPage: true });
  async function row(id) {
    const locator = page.locator(`[data-admin-records] tr[data-row-id="${id}"]`);
    for (let i = 0; i < 100; i++) {
      await ready();
      if (await locator.count()) return locator;
      const next = page.getByRole("button", { name: "下一页", exact: true });
      if (await next.isDisabled()) throw Error("Fixture missing");
      await next.click();
    }
    throw Error("Fixture search limit exceeded");
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
    check(response.status() === 200, "Real mutation returns success " + route);
    const value = (await response.json()).data;
    await dialog.waitFor({ state: "hidden" });
    await ready();
    return value;
  }
  try {
    await page.goto(base + "/admin/login", { waitUntil: "networkidle" });
    await page.getByLabel("账号", { exact: true }).fill(local.admin_username);
    await page.getByLabel("密码", { exact: true }).fill(local.admin_password);
    await page.getByRole("button", { name: "登录", exact: true }).click();
    await page.waitForURL("**/admin");
    await page
      .locator(".side-menu")
      .getByRole("button", { name: "悬赏任务", exact: true })
      .click();
    await page
      .getByRole("heading", { name: "悬赏任务管理", exact: true })
      .waitFor();
    await ready();
    let item = await row(f.review_task);
    check(
      (await item.innerText()).includes("浏览器发布_" + f.tag),
      "Task list shows real fixture title",
    );
    await item.getByRole("button", { name: "通过", exact: true }).click();
    await page
      .getByRole("dialog")
      .getByRole("button", { name: "确认操作", exact: true })
      .click();
    check(
      await page
        .getByRole("dialog")
        .getByLabel("操作原因")
        .evaluate((el) => !el.validity.valid),
      "Empty review reason is blocked in the browser",
    );
    let value = await confirm(
      "浏览器真实发布审核",
      "/admin/tasks/" + f.review_task + "/publication-review",
    );
    check(
      value.task_status === "待接取" &&
        (await item.getByText("待接取", { exact: true }).count()) === 1,
      "Publication result updates task list",
    );
    await screenshot("01-task-management");
    item = await row(f.assignment_task);
    await item.getByRole("button", { name: "查看", exact: true }).click();
    let dialog = page.getByRole("dialog");
    await dialog.getByText("浏览器分配_" + f.tag, { exact: true }).waitFor();
    check(
      (await dialog.getByText("尚未创建", { exact: true }).count()) === 1,
      "Task detail reads real not-yet-created order state",
    );
    await dialog.getByRole("button", { name: "接取申请", exact: true }).click();
    await ready();
    check(
      (await page.getByLabel("任务编号", { exact: true }).inputValue()) ===
        f.assignment_task,
      "Detail navigates to applications scoped to this task",
    );
    item = await row(f.application_id);
    await item.getByRole("button", { name: "通过", exact: true }).click();
    value = await confirm(
      "浏览器真实接取分配",
      "/admin/task-applications/" + f.application_id + "/review",
    );
    check(
      /^ORDER[0-9]{14}$/.test(value.order_id) &&
        value.task.task_status === "进行中",
      "Assignment returns a real durable order identifier",
    );
    check(
      (await item.getByText("已通过", { exact: true }).count()) === 1,
      "Application list reflects successful assignment",
    );
    await screenshot("02-assignment");
    await item.getByRole("button", { name: "查看任务", exact: true }).click();
    dialog = page.getByRole("dialog");
    await dialog.getByText(value.order_id, { exact: true }).waitFor();
    check(true, "Assigned task detail displays its actual order relation");
    await dialog.getByRole("button", { name: "凭证版本", exact: true }).click();
    await ready();
    check(
      (await page.getByText("暂无符合条件的记录", { exact: true }).count()) ===
        1,
      "Assigned task with no proof displays an empty state",
    );
    await page.getByLabel("任务编号", { exact: true }).fill(f.acceptance_task);
    await page.getByRole("button", { name: "查询", exact: true }).click();
    await ready();
    item = await row(f.proof_id);
    await item.getByRole("button", { name: "查看材料", exact: true }).click();
    dialog = page.getByRole("dialog");
    await dialog
      .getByText("<script>not executed</script> 合成完成凭证", { exact: true })
      .waitFor();
    await page.waitForFunction(() => {
      const image = document.querySelector('[aria-label="文件材料预览"] img');
      return image?.complete && image.naturalWidth > 0;
    });
    check(
      (await dialog.locator("script").count()) === 0,
      "Proof text is escaped and actual private image bytes render",
    );
    await screenshot("03-proof-preview");
    await dialog.getByRole("button", { name: "关闭对话框" }).click();
    await item.getByRole("button", { name: "通过", exact: true }).click();
    value = await confirm(
      "浏览器真实验收结算",
      "/admin/task-proofs/" + f.proof_id + "/review",
    );
    check(
      value.task_status === "已完成" &&
        (await item.getByText("已通过", { exact: true }).count()) === 1,
      "Browser acceptance settles and updates the proof state",
    );
    await item.getByRole("button", { name: "查看任务", exact: true }).click();
    dialog = page.getByRole("dialog");
    await dialog.getByText("已完成", { exact: true }).waitFor();
    await dialog.getByRole("button", { name: "审核记录", exact: true }).click();
    await ready();
    check(
      (await page
        .locator("tbody")
        .getByText("浏览器真实验收结算", { exact: true })
        .count()) === 1,
      "Audit-record tab displays persisted acceptance reason",
    );
    await screenshot("04-task-audit");
    await page.getByRole("button", { name: "任务管理", exact: true }).click();
    await page.getByRole("button", { name: "任务列表", exact: true }).click();
    await ready();
    item = await row(f.close_task);
    await item.getByRole("button", { name: "关闭", exact: true }).click();
    value = await confirm(
      "浏览器关闭并释放奖励",
      "/admin/tasks/" + f.close_task + "/close",
    );
    check(
      value.task_status === "已取消" &&
        (await item.getByText("已取消", { exact: true }).count()) === 1,
      "Browser closure persists canceled task state",
    );
    item = await row(f.assignment_task);
    check(
      (await item
        .getByRole("button", { name: "关闭", exact: true })
        .count()) === 0,
      "Ongoing assigned tasks have no direct-close button",
    );
    await page
      .getByLabel("任务筛选状态", { exact: true })
      .selectOption("已取消");
    await page.getByRole("button", { name: "查询", exact: true }).click();
    await ready();
    check(
      (await page
        .locator(`[data-admin-records] tr[data-row-id="${f.review_task}"]`)
        .count()) === 0,
      "State filter excludes tasks in other states",
    );
    check(
      errors.length === 0,
      "No Vue errors or script dialogs across task/application/proof/log views",
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
    await screenshot("failure").catch(() => {});
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
