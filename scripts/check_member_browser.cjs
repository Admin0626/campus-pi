// Actual packaged Vue UI and real local backend. Requires newly prepared synthetic fixtures.
// Credentials and captured evidence remain in ignored .local; never a production/device acceptance.
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || "playwright");
const fs = require("node:fs"),
  path = require("node:path"),
  assert = require("node:assert/strict"),
  crypto = require("node:crypto");
const root = path.resolve(__dirname, ".."),
  out = path.join(root, ".local/remaining"),
  fixture = JSON.parse(
    fs.readFileSync(path.join(out, "ui-fixtures.json"), "utf8"),
  );
const base = "http://127.0.0.1:8080",
  checks = [],
  errors = [];
const chatText="真实浏览器私信验收 "+crypto.randomBytes(6).toString("hex");
let browser,
  finished = false;
function check(value, name) {
  assert.ok(value, name);
  checks.push({ name, passed: true });
}
async function api(route, method = "GET", body, token = fixture.admin_token) {
  const response = await fetch(base + "/api/v1" + route, {
    method,
    headers: {
      Accept: "application/json",
      Authorization: "Bearer " + token,
      "Idempotency-Key": crypto.randomUUID(),
      ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const value = await response.json();
  assert.ok(response.ok, `${response.status}: ${value.message}`);
  return value.data;
}
async function login(page, who) {
  await page.goto(base + "/login");
  await page.getByLabel("学号/用户名", { exact: true }).fill(who.name);
  await page.getByLabel("密码", { exact: true }).fill(who.password);
  await page.getByRole("button", { name: "登录", exact: true }).click();
  await page.waitForURL("**/home");
}
async function module(page, name) {
  await page.getByRole("button", { name: "个人", exact: true }).click();
  await page.waitForURL("**/me");
  await page.getByRole("button", { name, exact: true }).click();
  await page.locator(".member-header").waitFor();
}
const member = (page) => page.locator(".member-content");
async function fill(page, values) {
  for (const [label, value] of Object.entries(values))
    await member(page).getByLabel(label, { exact: true }).fill(String(value));
}
async function selectCategory(page) {
  await member(page)
    .getByLabel("分类", { exact: true })
    .selectOption(String(fixture.category));
}
async function upload(page) {
  await member(page)
    .locator("input[type=file]")
    .setInputFiles(path.join(out, "evidence.png"));
  await member(page)
    .getByText("正在上传与安全扫描…", { exact: true })
    .waitFor({ state: "hidden" });
  await member(page)
    .getByRole("button", { name: "移除", exact: true })
    .waitFor();
}
async function action(page, name) {
  await member(page).getByRole("button", { name, exact: true }).click();
}
async function noError(page) {
  await page.waitForFunction(
    () =>
      !document.querySelector(".member-content button.member-primary:disabled"),
  );
  check(
    (await member(page).locator("[role=alert]").count()) === 0,
    "Page action completed without visible error",
  );
}
(async () => {
  try {
    browser = await chromium.launch({
      executablePath: "C:/Program Files/Google/Chrome/Application/chrome.exe",
      headless: true,
    });
    const seller = await browser.newPage({
        viewport: { width: 1440, height: 1000 },
      }),
      buyer = await browser.newPage({ viewport: { width: 390, height: 844 } });
    [seller, buyer].forEach((p) => {
      p.on("pageerror", (e) => errors.push(String(e)));
      p.on("dialog", (d) => d.accept());
    });
    await login(seller, fixture.seller);
    await login(buyer, fixture.buyer);
    await module(seller, "我的收藏");
    await action(seller, "发布商品");
    await selectCategory(seller);
    await fill(seller, {
      商品名称: "页面验收二手教材",
      商品描述: "真实上传与审核的浏览器验收商品",
      "原价（元）": "20.00",
      "售价（元）": "10.00",
      库存: "2",
    });
    await upload(seller);
    await action(seller, "提交审核");
    await member(seller)
      .getByText("商品已提交审核，通过后可公开浏览。", { exact: true })
      .waitFor();
    let product = (
      await api("/me/products", "GET", undefined, fixture.seller.token)
    ).items[0];
    check(
      product.audit_status === "待审核",
      "UI product publishing enters real pending review",
    );
    await api("/admin/products/" + product.product_id + "/review", "POST", {
      decision: "通过",
      reason: "真实浏览器验收商品",
    });
    await module(buyer, "我的收藏");
    await member(buyer)
      .getByPlaceholder("搜索商品名称")
      .fill("页面验收二手教材");
    await action(buyer, "搜索");
    await member(buyer)
      .getByRole("button", { name: "查看商品", exact: true })
      .first()
      .click();
    await action(buyer, "收藏");
    await member(buyer)
      .getByRole("button", { name: "取消收藏", exact: true })
      .waitFor();
    check(true, "Buyer favorites real product");
    await action(buyer, "购买");
    await fill(buyer, { 数量: "1", 自提交接地点: "合成验收交接地点" });
    await action(buyer, "确认创建待支付订单");
    await buyer.waitForURL("**/modules/G");
    await member(buyer)
      .getByRole("button", { name: "订单详情", exact: true })
      .first()
      .click();
    await member(buyer)
      .getByRole("button", { name: "取消订单", exact: true })
      .waitFor();
    await action(buyer, "取消订单");
    await member(buyer)
      .getByText("订单已取消，库存已释放", { exact: true })
      .waitFor();
    check(
      (
        await api(
          "/products/" + product.product_id,
          "GET",
          undefined,
          fixture.buyer.token,
        )
      ).stock_quantity === 2,
      "UI cancellation restores stock once",
    );
    await module(seller, "我的发布");
    await action(seller, "发布帖子");
    await selectCategory(seller);
    await fill(seller, {
      标题: "页面验收匿名帖子",
      "内容（纯文本）": "匿名内容 <script>literal</script>",
    });
    await member(seller).getByLabel("匿名发布", { exact: true }).check();
    await action(seller, "提交审核");
    await member(seller)
      .getByText("帖子已进入审核队列", { exact: true })
      .waitFor();
    const post = (
      await api("/me/posts", "GET", undefined, fixture.seller.token)
    ).items[0];
    await api("/admin/posts/" + post.post_id + "/review", "POST", {
      decision: "通过",
      reason: "真实浏览器验收匿名帖子",
    });
    await module(buyer, "我的发布");
    await member(buyer)
      .getByPlaceholder("搜索校园信息")
      .fill("页面验收匿名帖子");
    await action(buyer, "搜索");
    await member(buyer)
      .getByRole("button", { name: "阅读全文", exact: true })
      .first()
      .click();
    await member(buyer).getByRole('heading',{name:'页面验收匿名帖子',exact:true,level:2}).waitFor();
    await member(buyer)
      .getByText("匿名内容 <script>literal</script>", { exact: true })
      .waitFor();
    check(
      (
        await api(
          "/posts/" + post.post_id,
          "GET",
          undefined,
          fixture.buyer.token,
        )
      ).author === null,
      "Anonymous author remains absent and text is escaped",
    );
    await action(buyer, "收藏");
    await member(buyer)
      .getByRole("button", { name: "取消收藏", exact: true })
      .waitFor();
    await module(seller, "任务记录");
    await action(seller, "发布任务");
    await fill(seller, {
      任务名称: "页面验收悬赏",
      任务说明: "通过真实页面提交完成凭证",
      奖励积分: "10.00",
    });
    await action(seller, "提交");
    await member(seller)
      .getByText("操作成功，状态已更新", { exact: true })
      .waitFor();
    const task = (
      await api(
        "/me/tasks?side=published",
        "GET",
        undefined,
        fixture.seller.token,
      )
    ).items[0];
    await api(
      "/admin/tasks/" + task.reward_task_id + "/publication-review",
      "POST",
      { decision: "通过", reason: "真实浏览器任务验收" },
    );
    await module(buyer, "任务记录");
    await action(buyer, "任务广场");
    await member(buyer).getByPlaceholder("搜索任务").fill("页面验收悬赏");
    await action(buyer, "搜索");
    await member(buyer)
      .getByRole("button", { name: "任务详情", exact: true })
      .first()
      .click();
    await action(buyer, "申请接取");
    await member(buyer)
      .getByText("申请已提交，请等待分配审核", { exact: true })
      .waitFor();
    const application = (
      await api("/me/task-applications", "GET", undefined, fixture.buyer.token)
    ).items[0];
    await api(
      "/admin/task-applications/" + application.application_id + "/review",
      "POST",
      { decision: "通过", reason: "真实浏览器分配验收" },
    );
    await action(buyer, "返回列表");
    await action(buyer, "我接取的");
    await member(buyer)
      .getByRole("button", { name: "任务详情", exact: true })
      .first()
      .click();
    await action(buyer, "提交完成凭证");
    await fill(buyer, { 完成说明: "真实页面上传完成凭证" });
    await upload(buyer);
    await action(buyer, "提交");
    await member(buyer)
      .getByText("操作成功，状态已更新", { exact: true })
      .waitFor();
    const proof = (
      await api(
        "/tasks/" + task.reward_task_id + "/proofs",
        "GET",
        undefined,
        fixture.buyer.token,
      )
    ).items[0];
    await api("/admin/task-proofs/" + proof.proof_id + "/review", "POST", {
      decision: "通过",
      reason: "真实浏览器结算验收",
    });
    check(
      (
        await api(
          "/tasks/" + task.reward_task_id,
          "GET",
          undefined,
          fixture.buyer.token,
        )
      ).task_status === "已完成",
      "Real UI task publish/apply/proof and admin settlement chain completes",
    );
    await module(seller, "订单管理");
    await member(seller)
      .getByRole("button", { name: "订单详情", exact: true })
      .first()
      .click();
    await action(seller, "申请任务争议");
    await fill(seller, { 争议原因: "真实浏览器任务争议验收" });
    await action(seller, "提交");
    await member(seller)
      .getByText("争议已提交，审核不会改变任务状态或奖励账本。", {
        exact: true,
      })
      .waitFor();
    await action(seller, "撤回争议");
    await member(seller).getByText("争议已撤回", { exact: true }).waitFor();
    check(
      (
        await api(
          "/tasks/" + task.reward_task_id,
          "GET",
          undefined,
          fixture.seller.token,
        )
      ).task_status === "已完成",
      "UI zero-money dispute creation and withdrawal preserve task settlement",
    );
    await buyer.getByRole("button", { name: "私信", exact: true }).click();
    await buyer.waitForURL("**/modules/H");
    await member(buyer)
      .getByPlaceholder("对方用户编号")
      .fill(fixture.seller.id);
    await action(buyer, "创建 / 打开会话");
    await member(buyer).getByPlaceholder("输入消息").fill(chatText);
    await action(buyer, "发送");
    await member(buyer).getByText("消息已发送", { exact: true }).waitFor();
    await seller.getByRole("button", { name: "私信", exact: true }).click();
    await seller.waitForURL("**/modules/H");
    await member(seller)
      .getByRole("button", { name: "进入会话", exact: true })
      .first()
      .click();
    await member(seller)
      .getByText(chatText, { exact: true })
      .waitFor();
    const currentMessage=member(seller).locator(".member-bubble").filter({hasText:chatText});
    await currentMessage.getByRole("button",{name:"标记已读",exact:true}).click();
    await currentMessage.getByRole("button",{name:"举报",exact:true}).click();
    await fill(seller, { 举报原因: "仅真实浏览器合成私信举报验收" });
    await action(seller, "提交");
    await member(seller)
      .getByText("举报已提交，可在举报记录查看处理结果", { exact: true })
      .waitFor();
    check(
      true,
      "Real conversation, send, receiver read and report work through UI",
    );
    for (const page of [seller, buyer])
      check(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth,
        ),
        "Responsive page has no horizontal document overflow",
      );
    await buyer.screenshot({
      path: path.join(out, "member-mobile.png"),
      fullPage: true,
    });
    await seller.screenshot({
      path: path.join(out, "member-desktop.png"),
      fullPage: true,
    });
    check(errors.length === 0, "No uncaught Vue/browser exceptions");
    finished = true;
  } finally {
    fs.writeFileSync(
      path.join(out, "member-browser.json"),
      JSON.stringify(
        {
          status: finished ? "PASS" : "INCOMPLETE",
          checked_at: new Date().toISOString(),
          checks,
          uncaught_errors: errors,
          scope:
            "Real packaged Vue and HTTP; synthetic users/reward funding; not native device",
        },
        null,
        2,
      ),
    );
    await browser?.close();
  }
})().catch((e) => {
  console.error(e.message);
  process.exitCode = 1;
});
