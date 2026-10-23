import { reactive, ref, watch, onUnmounted } from "vue";
import { api, requestKey } from "../api.js";
export function useOperation() {
  const state = reactive({ busy: false, error: "", notice: "" });
  const keys = new Map();
  async function run(work, notice = "") {
    if (state.busy) return;
    state.busy = true;
    state.error = "";
    state.notice = "";
    try {
      const value = await work();
      state.notice = notice;
      return value;
    } catch (e) {
      state.error = e.message + (e.requestId ? `（请求 ${e.requestId}）` : "");
      return undefined;
    } finally {
      state.busy = false;
    }
  }
  async function mutate(path, body, method = "POST") {
    const signature = JSON.stringify([method, path, body]);
    if (!keys.has(signature)) keys.set(signature, requestKey());
    const value = await api(path, { method, body, key: keys.get(signature) });
    keys.delete(signature);
    return value;
  }
  return { state, run, mutate };
}
export function usePaged(endpoint) {
  const items = ref([]),
    page = ref(1),
    total = ref("0"),
    loading = ref(false),
    error = ref("");
  let serial = 0,
    controller;
  async function load() {
    const version = ++serial;
    controller?.abort();
    controller = new AbortController();
    loading.value = true;
    error.value = "";
    try {
      const path = endpoint();
      if (!path) {
        items.value = [];
        total.value = "0";
        return;
      }
      const result = await api(
        path +
          (path.includes("?") ? "&" : "?") +
          new URLSearchParams({ page: page.value, page_size: 10 }),
        { signal: controller.signal },
      );
      if (version === serial) {
        items.value = result.items;
        total.value = result.total;
      }
    } catch (e) {
      if (version === serial) {
        items.value = [];
        total.value = "0";
        error.value = e.message;
      }
    } finally {
      if (version === serial) loading.value = false;
    }
  }
  watch(
    endpoint,
    () => {
      page.value = 1;
      load();
    },
    { immediate: true },
  );
  onUnmounted(() => {
    serial++;
    controller?.abort();
  });
  return {
    items,
    page,
    total,
    loading,
    error,
    load,
    async turn(next) {
      page.value = next;
      await load();
    },
  };
}
export const date = (value) =>
  value
    ? new Date(value).toLocaleString("zh-CN", {
        timeZone: "Asia/Shanghai",
        hour12: false,
      })
    : "—";
