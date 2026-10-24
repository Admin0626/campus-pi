const test = require("node:test"),
  assert = require("node:assert/strict");
let definition;
const app = {
  globalData: {
    token: "identity",
    apiBaseUrl: "http://adapter.invalid",
    user: { user_id: "new" },
  },
};
global.Component = (value) => {
  definition = value;
};
global.getApp = () => app;
global.getCurrentPages = () => [];
require("../miniprogram/components/business-surface/index");
test("WB-M01 switching conversation clears old messages before network completion", async () => {
  let pending;
  global.wx = {
    request: (o) => {
      pending = o;
    },
  };
  const vm = {
    ...definition.methods,
    properties: { module: "H" },
    _active: true,
    data: {
      ...structuredClone(definition.data),
      user: { user_id: "1" },
      messages: [{ msg_id: "9", content: "previous conversation only" }],
      messageText: "old draft",
    },
    setData(value) {
      Object.assign(this.data, value);
    },
  };
  const work = vm.openRaw({ session_id: "2", peer: { username: "new peer" } });
  try {
    assert.deepEqual(vm.data.messages, []);
    assert.equal(vm.data.messageText, "");
  } finally {
    pending.success({
      statusCode: 200,
      data: { code: "OK", data: { items: [], has_more: false } },
    });
    await work;
  }
});
test("WB-M02 detached initialization cannot restore old identity or start polling", async () => {
  let pending;
  global.wx = {
    request: (o) => {
      pending = o;
    },
  };
  const vm = {
    ...definition.methods,
    properties: { module: "H" },
    _active: true,
    data: structuredClone(definition.data),
    setData(value) {
      Object.assign(this.data, value);
    },
  };
  const work = vm.start();
  vm._active = false;
  app.globalData.token = "new-token";
  pending.success({
    statusCode: 200,
    data: { code: "OK", data: { user_id: "old" } },
  });
  await work;
  assert.equal(app.globalData.user.user_id, "new");
  assert.equal(vm._timer, undefined);
});

test("WB-M03 offline order controls reflect role, channel and current stage", () => {
  const base = { order_id: "ORDER26012400000001", order_type: "商品", trade_channel: "OFFLINE", buyer_id: "1", seller_id: "2", order_status: "待线下交易" };
  const vm = { ...definition.methods, properties: { module: "G" }, _active: true,
    data: { ...structuredClone(definition.data), user: { user_id: "1" }, detail: base },
    setData(value) { Object.assign(this.data, value); } };
  const controls = (user, state, channel = "OFFLINE") => {
    vm.data.user = { user_id: user };
    vm.data.detail = { ...base, order_status: state, trade_channel: channel };
    vm.describe(); return vm.data.actions.map(a => a.value).filter(a => a !== "contact-order");
  };
  assert.deepEqual(controls("1", "待线下交易"), ["cancel-order"]);
  assert.deepEqual(controls("2", "待线下交易"), ["offline-handover"]);
  assert.deepEqual(controls("1", "待确认收货"), ["offline-receipt"]);
  assert.deepEqual(controls("2", "待确认收货"), []);
  assert.deepEqual(controls("1", "已完成"), []);
  assert.deepEqual(controls("9", "待线下交易"), []);
  assert.deepEqual(controls("1", "待支付", null), []);
});

test("WB-M04 offline acknowledgement requires modal confirmation and uses the existing mutation adapter", async () => {
  const calls = [];
  let accepted = false;
  global.wx = { showModal(options) { options.success({ confirm: accepted }); } };
  const vm = { ...definition.methods, properties: { module: "G" }, _active: true,
    data: { ...structuredClone(definition.data), user: { user_id: "2" }, detail: { order_id: "ORDER26012400000001" } },
    setData(value) { Object.assign(this.data, value); },
    async work(fn) { await fn(); }, async load() {}, describe() {},
    async _mutation(path) { calls.push(path); return { order_id: "ORDER26012400000001" }; } };
  const event = action => ({ currentTarget: { dataset: { action } } });
  await vm.action(event("offline-handover")); assert.deepEqual(calls, []);
  accepted = true;
  await vm.action(event("offline-handover"));
  await vm.action(event("offline-receipt"));
  assert.deepEqual(calls, ["/orders/ORDER26012400000001/shipping", "/orders/ORDER26012400000001/receipt"]);
});
