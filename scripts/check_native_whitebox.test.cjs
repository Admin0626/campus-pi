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
