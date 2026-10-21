const { chromium } = require(process.env.PLAYWRIGHT_MODULE || "playwright");
const fs = require("node:fs"),
  path = require("node:path");
const root = path.resolve(__dirname, ".."),
  out = path.join(root, ".local/auth-dev/browser"),
  base = process.env.WEB_BASE_URL || "http://127.0.0.1:8080";
fs.mkdirSync(out, { recursive: true });
const local = JSON.parse(
  fs.readFileSync(path.join(root, ".local/auth-dev/private.json"), "utf8"),
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
  const errors = [],
    checks = [];
  page.on("pageerror", (e) => errors.push(String(e)));
  function ok(v, name) {
    checks.push({ name, passed: !!v });
    if (!v) throw Error(name);
  }
  const shot = async (name) =>
    page.screenshot({ path: path.join(out, name + ".png"), fullPage: true });
  async function signIn(account, password) {
    await page.locator("#account,#admin-account").fill(account);
    await page.locator("#login-password,#admin-password").fill(password);
    await page.getByRole("button", { name: "登录", exact: true }).click();
  }
  await page.goto(base + "/login", { waitUntil: "networkidle" });
  await shot("01-真实登录页");
  await page.getByRole("button", { name: "立即注册", exact: true }).click();
  await page.getByRole("button", { name: "提交注册", exact: true }).click();
  ok(
    (await page.locator("[aria-invalid=true]").count()) === 3,
    "registration validates all required inputs",
  );
  const username = "browser_" + Date.now().toString(36),
    password = "BrowserCampus2026!";
  await page.getByLabel("用户名", { exact: true }).fill(username);
  await page
    .getByLabel("邮箱", { exact: true })
    .fill(username + "@example.invalid");
  await page.getByLabel("密码", { exact: true }).fill(password);
  await shot("02-真实注册页");
  await page.getByRole("button", { name: "提交注册", exact: true }).click();
  await page.waitForURL("**/login");
  ok(
    (await page.locator("#account,#admin-account").inputValue()) === username,
    "register returns to login without automatic sign in",
  );
  await signIn(username, "WrongPassword2026!");
  await page.getByRole("alert").filter({ hasText: "账号或密码错误" }).waitFor();
  ok(true, "wrong password real backend feedback");
  await signIn(username, password);
  await page.waitForURL("**/home");
  ok(
    await page
      .getByRole("button", { name: "我的账号", exact: true })
      .isVisible(),
    "registered account logs into real backend",
  );
  await page.getByRole("button", { name: "二手交易", exact: true }).click();
  await page
    .getByRole("heading", { name: "商品与分类还在开发", exact: true })
    .waitFor();
  ok(true, "other module entry reaches development placeholder");
  await page
    .getByRole("button", { name: "我的账号", exact: true })
    .first()
    .click();
  await page.getByRole("heading", { name: username, exact: true }).waitFor();
  ok(true, "me displays persisted account");
  await shot("03-真实个人中心");
  await page.getByRole("button", { name: "账号设置", exact: true }).click();
  await page
    .getByLabel("个人介绍", { exact: true })
    .fill("浏览器真实接口联调资料");
  await page.getByRole("button", { name: "保存资料", exact: true }).click();
  await page.waitForURL("**/me");
  await page.getByRole("button", { name: "账号设置", exact: true }).click();
  ok(
    (await page.getByLabel("个人介绍", { exact: true }).inputValue()) ===
      "浏览器真实接口联调资料",
    "profile save reaches database",
  );
  await page
    .getByRole("button", { name: "账号安全", exact: true })
    .first()
    .click();
  await page.getByLabel("当前密码", { exact: true }).fill(password);
  await page.getByLabel("新密码", { exact: true }).fill(password + "New");
  await page.getByLabel("确认新密码", { exact: true }).fill(password + "New");
  await page.getByRole("button", { name: "确认修改密码", exact: true }).click();
  await page.waitForURL("**/login");
  ok(true, "changing password returns to login");
  await page.goto(base + "/admin/login", { waitUntil: "networkidle" });
  await signIn(username, password + "New");
  await page
    .getByRole("alert")
    .filter({ hasText: "该账号不是管理员" })
    .waitFor();
  ok(
    page.url().endsWith("/admin/login"),
    "ordinary account cannot enter Word admin login",
  );
  await signIn(local.admin_username, local.admin_password);
  await page.waitForURL("**/admin");
  await page.getByRole("button", { name: "进入用户管理", exact: true }).click();
  await page.getByLabel("用户名", { exact: true }).fill(username);
  await page.getByRole("button", { name: "搜索", exact: true }).click();
  const row = page.locator("tbody tr").filter({ hasText: username });
  await row.waitFor();
  ok(
    !(await row.innerText()).includes(username + "@example.invalid"),
    "admin UI contact masked",
  );
  await shot("04-真实用户管理");
  await row.getByRole("button", { name: "查看", exact: true }).click();
  await page.getByRole("heading", { name: username, exact: true }).waitFor();
  ok(
    await page
      .getByRole("button", { name: "前往角色管理", exact: false })
      .isEnabled(),
    "role management entry is available; sensitive authority is checked by server",
  );
  await page.getByRole("button", { name: "停用账号", exact: true }).click();
  await page.getByRole("button", { name: "确认操作", exact: true }).click();
  await page.getByText("请填写 1–500 字的操作原因", { exact: true }).waitFor();
  ok(true, "management reason required in UI");
  await page.getByLabel("操作原因", { exact: true }).fill("浏览器联调停用");
  await page.getByRole("button", { name: "确认操作", exact: true }).click();
  await page.getByRole("dialog").waitFor({ state: "hidden" });
  // Closing the dialog precedes the shared list/detail loading state settling.
  await page.getByRole("button", { name: "启用账号", exact: true }).waitFor();
  ok(
    await page
      .getByRole("button", { name: "启用账号", exact: true })
      .isVisible(),
    "real status update reflected",
  );
  await page.getByRole("button", { name: "启用账号", exact: true }).click();
  await page.getByLabel("操作原因", { exact: true }).fill("浏览器联调恢复");
  await page.getByRole("button", { name: "确认操作", exact: true }).click();
  await page.getByRole("dialog").waitFor({ state: "hidden" });
  await page.getByRole("button", { name: "内容发布管理", exact: true }).click();
  await page.getByRole("heading", { name: "内容审核", exact: true }).waitFor();
  ok(true, "content management now reaches its real review page");
  await shot("05-后台内容审核");
  await page
    .getByRole("button", {
      name: new RegExp(local.admin_username + " 退出登录"),
    })
    .click();
  await page.waitForURL("**/login");
  await signIn(username, password + "New");
  await page.waitForURL("**/home");
  ok(true, "new password usable after admin status changes");
  for (const width of [390, 320]) {
    await page.setViewportSize({ width, height: 844 });
    if (page.url().endsWith("/home"))
      await page.getByRole("button", { name: "我的账号", exact: true }).click();
    const fit = await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    );
    ok(fit, `${width}px authenticated user page does not overflow`);
    if (width === 390) await shot("06-手机Web兼容视图-非微信小程序验收");
  }
  await page.reload({ waitUntil: "networkidle" });
  await page.waitForURL("**/login");
  ok(true, "refresh requires sign in under memory-only token policy");
  ok(errors.length === 0, "no browser JavaScript errors");
  fs.writeFileSync(
    path.join(out, "browser-acceptance.json"),
    JSON.stringify({ status: "PASS", checks, errors }, null, 2),
  );
  console.log(JSON.stringify({ status: "PASS", checks: checks.length }));
  await browser.close();
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
