// Native component methods + REAL HTTP through an explicit wx host adapter.
// This is not the WeChat renderer, navigation engine, domain verification, or a device test.
const fs = require("node:fs"),
  path = require("node:path"),
  crypto = require("node:crypto"),
  assert = require("node:assert/strict");
const root = path.resolve(__dirname, ".."),
  out = path.join(root, ".local/remaining"),
  fixtures = JSON.parse(
    fs.readFileSync(path.join(out, "ui-fixtures.json"), "utf8"),
  ),
  base = "http://127.0.0.1:8080";
const app = { globalData: { apiBaseUrl: base, token: "", user: null } },
  checks = [],
  vms = [];
let nav = "";
global.getApp = () => app;
global.getCurrentPages = () => [{ route: "pages/business/index" }];
global.wx = {
  request(o) {
    fetch(o.url, {
      method: o.method || "GET",
      headers: o.header,
      body: o.data === undefined ? undefined : JSON.stringify(o.data),
    })
      .then(async (r) =>
        o.success({
          statusCode: r.status,
          data: o.method === "HEAD" ? null : await r.json(),
          header: Object.fromEntries(r.headers),
        }),
      )
      .catch((e) => o.fail(e));
  },
  getRandomValues(o) {
    const bytes = crypto.randomBytes(o.length);
    o.success({
      randomValues: bytes.buffer.slice(
        bytes.byteOffset,
        bytes.byteOffset + bytes.length,
      ),
    });
  },
  uploadFile(o) {
    const body = new FormData();
    body.append("purpose", o.formData.purpose);
    body.append(
      "file",
      new Blob([fs.readFileSync(o.filePath)]),
      "evidence.png",
    );
    fetch(o.url, { method: "POST", headers: o.header, body })
      .then(async (r) =>
        o.success({ statusCode: r.status, data: await r.text() }),
      )
      .catch((e) => o.fail(e));
  },
  chooseMessageFile(o) {
    o.success({ tempFiles: [{ path: path.join(out, "evidence.png") }] });
  },
  chooseMedia(o) {
    o.success({
      tempFiles: [{ tempFilePath: path.join(out, "evidence.png") }],
    });
  },
  showModal(o) {
    o.success({ confirm: true });
  },
  showToast() {},
  navigateTo(o) {
    nav = o.url;
    o.success({});
  },
  reLaunch(o) {
    nav = o.url;
    o.success({});
  },
};
const { request } = require("../miniprogram/utils/request"),
  b = require("../miniprogram/services/business");
let definition;
global.Component = (value) => {
  definition = value;
};
require("../miniprogram/components/business-surface/index");
function vm(module) {
  const value = {
    ...definition.methods,
    properties: { module },
    data: structuredClone(definition.data),
    _active: true,
    _mutation: b.mutator(),
    setData(patch) {
      for (const [key, val] of Object.entries(patch)) {
        const keys = key.replace(/\[(\d+)\]/g, ".$1").split(".");
        let target = this.data;
        for (const k of keys.slice(0, -1)) target = target[k];
        target[keys.at(-1)] = val;
      }
    },
  };
  vms.push(value);
  return value;
}
function check(value, name) {
  assert.ok(value, name);
  checks.push({ name, passed: true });
}
const event = (dataset, value) => ({
  currentTarget: { dataset },
  detail: { value },
});
function field(vm, key, value) {
  const i = vm.data.fields.findIndex((f) => f.key === key);
  assert.ok(i >= 0, key);
  vm.field(event({ index: i }, value));
}
function healthy(vm) {
  assert.equal(vm.data.error, "");
}
async function as(who) {
  app.globalData.token = who.token;
  app.globalData.user = await request("/me");
}
async function admin(route, body) {
  const response = await fetch(base + "/api/v1" + route, {
    method: "POST",
    headers: {
      Authorization: "Bearer " + fixtures.admin_token,
      "Content-Type": "application/json",
      "Idempotency-Key": crypto.randomUUID(),
    },
    body: JSON.stringify(body),
  });
  const result = await response.json();
  assert.ok(response.ok, result.message);
  return result.data;
}
(async () => {
  let finished = false;
  try {
    check(
      b.card("G", { order_id: "order-a", product_id: "1" }).id !==
        b.card("G", { order_id: "order-b", product_id: "1" }).id,
      "Native order rows use order IDs even when product is shared",
    );
    await as(fixtures.seller);
    const products = vm("C");
    await products.start();
    healthy(products);
    products.edit();
    field(products, "product_name", "原生组件适配器商品");
    field(products, "discount_price", "5.00");
    field(products, "stock_quantity", "2");
    await products.choose();
    healthy(products);
    check(
      products.data.files.length === 1,
      "Native upload adapter reaches real Defender-backed file endpoint",
    );
    await products.submit();
    healthy(products);
    check(
      products.data.detail.audit_status === "待审核",
      "Native product form reaches pending review",
    );
    const product = products.data.detail.product_id;
    await admin("/admin/products/" + product + "/review", {
      decision: "通过",
      reason: "组件适配器验收",
    });
    await as(fixtures.buyer);
    const browse = vm("C");
    await browse.start();
    await browse.openRaw({ product_id: product });
    await browse.action(event({ action: "favorite" }));
    healthy(browse);
    check(
      browse.data.detail.is_favorited,
      "Native favorite updates real relationship",
    );
    await browse.action(event({ action: "buy" }));
    field(browse, "shipping_address", "合成自提位置");
    await browse.submit();
    healthy(browse);
    check(
      nav.endsWith("module=G"),
      "Native order flow requests the real order page",
    );
    const orders = vm("G");
    await orders.start();
    const order = orders.data.rows.find(
      (r) => r.raw.product_id === product,
    ).raw;
    await orders.openRaw(order);
    await orders.action(event({ action: "cancel-order" }));
    healthy(orders);
    check(
      orders.data.detail.order_status === "已取消" &&
        (await request("/products/" + product)).stock_quantity === 2,
      "Native cancellation restores real stock",
    );
    await as(fixtures.seller);
    const posts = vm("D");
    await posts.start();
    posts.edit();
    field(posts, "post_title", "原生组件匿名帖子");
    field(posts, "post_content", "纯文本原生适配器验收");
    field(posts, "is_anonymous", true);
    await posts.submit();
    healthy(posts);
    const post = posts.data.detail.post_id;
    await admin("/admin/posts/" + post + "/review", {
      decision: "通过",
      reason: "组件适配器匿名验收",
    });
    await as(fixtures.buyer);
    const reader = vm("D");
    await reader.start();
    await reader.openRaw({ post_id: post });
    await reader.action(event({ action: "collect" }));
    healthy(reader);
    check(
      reader.data.detail.author === null && reader.data.detail.is_collected,
      "Native anonymous post and collection respect server privacy",
    );
    await as(fixtures.seller);
    const tasks = vm("E");
    await tasks.start();
    tasks.edit();
    field(tasks, "task_name", "原生组件任务");
    field(tasks, "task_description", "真实接口任务凭证与结算");
    field(tasks, "reward_amount", "5.00");
    await tasks.submit();
    healthy(tasks);
    const task = tasks.data.detail.reward_task_id;
    await admin("/admin/tasks/" + task + "/publication-review", {
      decision: "通过",
      reason: "组件适配器任务验收",
    });
    await as(fixtures.buyer);
    const taker = vm("F");
    await taker.start();
    await taker.openRaw({ reward_task_id: task });
    await taker.action(event({ action: "apply" }));
    healthy(taker);
    const application = (await request("/me/task-applications")).items.find(
      (r) => r.reward_task_id === task,
    );
    await admin(
      "/admin/task-applications/" + application.application_id + "/review",
      { decision: "通过", reason: "组件适配器分配验收" },
    );
    await taker.openRaw({ reward_task_id: task });
    await taker.action(event({ action: "proof" }));
    field(taker, "proof_text", "组件适配器完成证据");
    await taker.choose();
    await taker.submit();
    healthy(taker);
    const proof = (await request("/tasks/" + task + "/proofs")).items[0];
    await admin("/admin/task-proofs/" + proof.proof_id + "/review", {
      decision: "通过",
      reason: "组件适配器结算验收",
    });
    check(
      (await request("/tasks/" + task)).task_status === "已完成",
      "Native task form, application, real file proof and admin settlement complete",
    );
    const chats = vm("H");
    await chats.start();
    chats.createChat();
    field(chats, "peer_user_id", fixtures.seller.id);
    await chats.submit();
    healthy(chats);
    const chatText =
      "原生组件真实私信 " + crypto.randomBytes(6).toString("hex");
    chats.setData({ messageText: chatText });
    await chats.send();
    healthy(chats);
    const msg = chats.data.messages.find((r) => r.content === chatText);
    check(!!msg, "Native text send reaches real recipient");
    await chats.sendImage();
    healthy(chats);
    check(
      chats.data.messages.some((r) => r.content_type === "图片"),
      "Native image upload and message binding complete",
    );
    await as(fixtures.seller);
    const cases = vm("G");
    await cases.start();
    const taskOrder = (await request("/tasks/" + task)).order_id;
    await cases.openRaw({ order_id: taskOrder });
    await cases.action(event({ action: "apply-aftersale" }));
    field(cases, "apply_reason", "原生组件任务零金额争议验收");
    await cases.submit();
    healthy(cases);
    check(
      cases.data.detail.apply_amount === "0.00" &&
        cases.data.detail.aftersale_status === "待审核",
      "Native task dispute creates a real zero-money case",
    );
    await cases.action(event({ action: "cancel-aftersale" }));
    healthy(cases);
    check(
      cases.data.detail.aftersale_status === "已取消" &&
        (await request("/tasks/" + task)).task_status === "已完成",
      "Native dispute withdrawal preserves settled task",
    );
    const receiver = vm("H");
    await receiver.start();
    await receiver.openRaw(chats.data.detail);
    await receiver.messageAction(event({ action: "read", id: msg.msg_id }));
    healthy(receiver);
    check(
      receiver.data.messages.find((r) => r.msg_id === msg.msg_id).read_at,
      "Native receiver-only read is persisted",
    );
    await receiver.messageAction(event({ action: "report", id: msg.msg_id }));
    field(receiver, "reason", "仅合成私信举报");
    await receiver.submit();
    healthy(receiver);
    check(
      (await request("/me/message-reports")).items.some(
        (r) => r.msg_id === msg.msg_id,
      ),
      "Native report is visible to reporter",
    );
    await receiver.messageAction(event({ action: "delete", id: msg.msg_id }));
    healthy(receiver);
    check(
      !receiver.data.messages.some((r) => r.msg_id === msg.msg_id),
      "Native side-specific deletion refreshes message view",
    );
    const profile = vm("A");
    await profile.start();
    check(
      profile.data.stage === "detail",
      "Native profile initially presents current data",
    );
    profile.edit();
    field(profile, "personal_bio", "Native PUT profile acceptance");
    field(profile, "show_personal_bio", true);
    await profile.choose();
    healthy(profile);
    await profile.submit();
    healthy(profile);
    const savedProfile = await request("/me");
    check(
      savedProfile.personal_bio === "Native PUT profile acceptance" &&
        savedProfile.privacy_settings.show_personal_bio &&
        savedProfile.avatar_url &&
        profile.data.stage === "detail",
      "Native PUT saves profile, privacy and an authorized uploaded avatar through the real API",
    );
    const inbox = vm("I");
    await inbox.start();
    await inbox.preferences();
    field(inbox, "in_app_enabled", false);
    await inbox.submit();
    healthy(inbox);
    check(
      !(await request("/me/notification-preferences")).in_app_enabled,
      "Native notification preference persists confirmed opt-out rule",
    );
    await inbox.preferences();
    field(inbox, "in_app_enabled", true);
    await inbox.submit();
    healthy(inbox);
    finished = true;
  } finally {
    for (const value of vms) {
      value._active = false;
      clearInterval(value._timer);
    }
    fs.writeFileSync(
      path.join(out, "member-native-adapter.json"),
      JSON.stringify(
        {
          status: finished ? "PASS" : "INCOMPLETE",
          checked_at: new Date().toISOString(),
          checks,
          scope: "Component methods + real HTTP via explicit wx adapter",
          native_renderer_verified: false,
          device_run: false,
        },
        null,
        2,
      ),
    );
  }
})().catch((e) => {
  console.error(e.message);
  process.exitCode = 1;
});
