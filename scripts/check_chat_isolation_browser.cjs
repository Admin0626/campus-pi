// BB-H01: REAL conversation responses; only the response timing is delayed (no payload replacement).
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || "playwright"),
  fs = require("fs"),
  path = require("path"),
  crypto = require("crypto"),
  assert = require("assert/strict");
const root = path.resolve(__dirname, ".."),
  out = path.join(root, ".local/testing"),
  f = JSON.parse(
    fs.readFileSync(
      path.join(root, ".local/remaining/ui-fixtures.json"),
      "utf8",
    ),
  ),
  base = "http://127.0.0.1:8080";
async function api(route, body, token) {
  const r = await fetch(base + "/api/v1" + route, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "Idempotency-Key": crypto.randomUUID(),
      ...(token ? { Authorization: "Bearer " + token } : {}),
    },
    body: JSON.stringify(body),
  });
  const v = await r.json();
  assert.ok(r.ok, v.message);
  return v.data;
}
(async () => {
  let browser,
    release,
    passed = false;
  try {
    const tag = crypto.randomBytes(5).toString("hex"),
      name = "chat_iso_" + tag;
    const peer = await api("/auth/register", {
      username: name,
      email: name + "@example.invalid",
      password: crypto.randomUUID() + "Aa1!",
    });
    const first = await api(
        "/chat-sessions",
        { peer_user_id: f.buyer.id },
        f.seller.token,
      ),
      text = "only old context " + tag;
    await api(
      "/chat-sessions/" + first.session_id + "/messages",
      { content_type: "文本", content: text },
      f.seller.token,
    );
    const second = await api(
      "/chat-sessions",
      { peer_user_id: peer.user_id },
      f.seller.token,
    );
    browser = await chromium.launch({
      executablePath: "C:/Program Files/Google/Chrome/Application/chrome.exe",
      headless: true,
    });
    const page = await browser.newPage();
    await page.goto(base + "/login");
    await page.locator("#account").fill(f.seller.name);
    await page.locator("#login-password").fill(f.seller.password);
    await page.getByRole("button", { name: "登录", exact: true }).click();
    await page.waitForURL("**/home");
    await page.getByRole("button", { name: "私信", exact: true }).click();
    await page.getByPlaceholder("对方用户编号").fill(f.buyer.id);
    await page
      .getByRole("button", { name: "创建 / 打开会话", exact: true })
      .click();
    await page.locator(".member-bubble").filter({ hasText: text }).waitFor();
    await page.getByPlaceholder("输入消息").fill("draft for old peer");
    await page.getByRole("button", { name: "会话", exact: true }).click();
    let intercepted;
    const waiting = new Promise((r) => {
      intercepted = r;
    });
    await page.route(
      "**/chat-sessions/" + second.session_id + "/messages?**",
      async (route) => {
        await new Promise((r) => {
          release = r;
          intercepted();
        });
        await route.continue();
      },
    );
    await page.getByPlaceholder("对方用户编号").fill(peer.user_id);
    await page
      .getByRole("button", { name: "创建 / 打开会话", exact: true })
      .click();
    await waiting;
    assert.equal(
      await page.locator(".member-bubble").filter({ hasText: text }).count(),
      0,
      "Old messages must disappear as soon as new peer is selected",
    );
    assert.equal(
      await page.getByPlaceholder("输入消息").inputValue(),
      "",
      "Old draft must not move into another conversation",
    );
    release();
    release = null;
    await page.waitForFunction(
      () => !document.querySelector(".member-form .member-primary")?.disabled,
    );
    await page.unroute(
      "**/chat-sessions/" + second.session_id + "/messages?**",
    );
    // BB-H02: pending mount must not create a polling timer AFTER its component has unmounted.
    await page.getByRole("button", { name: "首页", exact: true }).click();
    await page.waitForURL("**/home");
    await page.clock.install();
    await page.evaluate(
      (id) => sessionStorage.setItem("campus-chat-session", id),
      first.session_id,
    );
    let nextIntercepted;
    const waitingAgain = new Promise((r) => (nextIntercepted = r));
    let afterUnmount = false,
      lateRequests = 0;
    page.on("request", (r) => {
      if (
        afterUnmount &&
        r.url().includes("/chat-sessions/" + first.session_id + "/messages")
      )
        lateRequests++;
    });
    await page.route(
      "**/chat-sessions/" + first.session_id + "/messages?**",
      async (route) => {
        await new Promise((r) => {
          release = r;
          nextIntercepted();
        });
        await route.continue();
      },
    );
    await page.getByRole("button", { name: "私信", exact: true }).click();
    await waitingAgain;
    await page.getByRole("button", { name: "首页", exact: true }).click();
    await page.waitForURL("**/home");
    afterUnmount = true;
    const response = page.waitForResponse((r) =>
      r.url().includes("/chat-sessions/" + first.session_id + "/messages"),
    );
    release();
    release = null;
    await response;
    await page.waitForTimeout(100);
    await page.clock.fastForward(12000);
    await page.waitForTimeout(100);
    assert.equal(
      lateRequests,
      0,
      "Unmounted chat must not leave a polling timer behind",
    );
    passed = true;
  } finally {
    release?.();
    fs.writeFileSync(
      path.join(out, "chat-isolation.json"),
      JSON.stringify({
        status: passed ? "PASS" : "FAIL",
        cases: ["BB-H01", "BB-H02"],
        network: "real response delayed, never replaced",
      }),
    );
    await browser?.close();
  }
})().catch((e) => {
  console.error(e.message);
  process.exitCode = 1;
});
