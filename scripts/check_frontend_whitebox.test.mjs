import test from "node:test";
import assert from "node:assert/strict";
import {
  createRenderer,
  h,
  nextTick,
  ref,
} from "../frontend/node_modules/vue/index.mjs";
import { api, setToken, setExpiredHandler } from "../frontend/src/api.js";
import { useOperation, usePaged } from "../frontend/src/member/useMember.js";
const originalFetch = globalThis.fetch;
const reply = (data, status = 200) =>
  new Response(
    JSON.stringify({
      code: status === 200 ? "OK" : "DEPENDENCY_UNAVAILABLE",
      data,
      message: "controlled test response",
    }),
    { status, headers: { "Content-Type": "application/json" } },
  );
const deferred = () => {
  let resolve, reject;
  const promise = new Promise((a, b) => {
    resolve = a;
    reject = b;
  });
  return { promise, resolve, reject };
};
const flush = async () => {
  await nextTick();
  await new Promise((r) => setImmediate(r));
};
function mountPaged(endpoint) {
  let state;
  const renderer = createRenderer({
    createElement: () => ({}),
    insert() {},
    remove() {},
    createText: () => ({}),
    createComment: () => ({}),
    setText() {},
    setElementText() {},
    parentNode: () => null,
    nextSibling: () => null,
    patchProp() {},
  });
  const app = renderer.createApp({
    setup() {
      state = usePaged(endpoint);
      return () => h("div");
    },
  });
  app.mount({});
  return { state, close: () => app.unmount() };
}
test.afterEach(() => {
  globalThis.fetch = originalFetch;
  setToken("");
  setExpiredHandler(() => {});
});
test("WB-U01 late 401 cannot erase a newer identity", async () => {
  const gate = deferred();
  globalThis.fetch = () => gate.promise;
  setToken("old");
  let expired = 0;
  setExpiredHandler(() => expired++);
  const result = api("/me").catch((e) => e);
  setToken("new");
  gate.resolve(reply(null, 401));
  assert.equal((await result).status, 401);
  assert.equal(expired, 0);
  let auth;
  globalThis.fetch = async (_, o) => {
    auth = o.headers.Authorization;
    return reply({});
  };
  await api("/me");
  assert.equal(auth, "Bearer new");
});
test("WB-U02 upload preserves multipart encoding and authorization", async () => {
  const data = new FormData();
  data.append("purpose", "AVATAR");
  let options;
  globalThis.fetch = async (_, o) => {
    options = o;
    return reply({ file_ref: "controlled" });
  };
  setToken("identity");
  await api("/files", { method: "POST", body: data, key: "key" });
  assert.equal(options.body, data);
  assert.equal(options.headers["Content-Type"], undefined);
  assert.equal(options.headers.Authorization, "Bearer identity");
});
test("WB-U03 failed mutation retry keeps idempotency key and success releases it", async () => {
  const keys = [];
  let count = 0;
  globalThis.fetch = async (_, o) => {
    keys.push(o.headers["Idempotency-Key"]);
    if (++count === 1) throw new Error("network lost");
    return reply({});
  };
  const op = useOperation();
  await assert.rejects(op.mutate("/posts", { x: 1 }));
  await op.mutate("/posts", { x: 1 });
  await op.mutate("/posts", { x: 1 });
  assert.equal(keys[0], keys[1]);
  assert.notEqual(keys[1], keys[2]);
});
test("WB-U04 operation lock releases after exception and refuses duplicate work", async () => {
  const op = useOperation(),
    gate = deferred();
  let count = 0;
  const running = op.run(async () => {
    count++;
    await gate.promise;
    throw new Error("expected");
  });
  await op.run(() => count++);
  assert.equal(count, 1);
  gate.resolve();
  await running;
  assert.equal(op.state.busy, false);
  assert.equal(op.state.error, "expected");
});
test("WB-U05 old list completion cannot overwrite a newer filter", async () => {
  const gates = [];
  globalThis.fetch = () => {
    const d = deferred();
    gates.push(d);
    return d.promise;
  };
  const path = ref("/products"),
    mounted = mountPaged(() => path.value);
  path.value = "/posts";
  await nextTick();
  gates[1].resolve(reply({ items: [{ id: "new" }], total: "1" }));
  await flush();
  gates[0].resolve(reply({ items: [{ id: "old" }], total: "9" }));
  await flush();
  assert.equal(mounted.state.items.value[0].id, "new");
  mounted.close();
});
test("WB-U06 failed page clears old totals and data together", async () => {
  globalThis.fetch = async () => reply({ items: [{ id: "old" }], total: "42" });
  const mounted = mountPaged(() => "/products");
  await flush();
  assert.equal(mounted.state.total.value, "42");
  globalThis.fetch = async () => reply(null, 503);
  await mounted.state.load();
  assert.deepEqual(mounted.state.items.value, []);
  assert.equal(mounted.state.total.value, "0");
  assert.ok(mounted.state.error.value);
  mounted.close();
});
test("WB-U07 unmounted page ignores a late response", async () => {
  const gate = deferred();
  globalThis.fetch = () => gate.promise;
  const mounted = mountPaged(() => "/products");
  mounted.close();
  gate.resolve(reply({ items: [{ id: "late" }], total: "7" }));
  await flush();
  assert.deepEqual(mounted.state.items.value, []);
  assert.equal(mounted.state.total.value, "0");
});
