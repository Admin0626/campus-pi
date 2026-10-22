<script setup>
import { onMounted, onUnmounted, ref } from "vue";

const available = ref(false);
let interval,
  controller,
  stopped = false;
function assets(doc) {
  return Array.from(
    doc.querySelectorAll('script[src], link[rel="stylesheet"][href]'),
  )
    .map(
      (el) =>
        new URL(
          el.getAttribute("src") || el.getAttribute("href"),
          location.origin,
        ),
    )
    .filter(
      (url) =>
        url.origin === location.origin && url.pathname.startsWith("/assets/"),
    )
    .map((url) => url.pathname + url.search)
    .sort()
    .join("|");
}
const loaded = assets(document);
async function check() {
  if (
    stopped ||
    available.value ||
    controller ||
    document.visibilityState === "hidden" ||
    !loaded
  )
    return;
  controller = new AbortController();
  const timeout = setTimeout(() => controller?.abort(), 5000);
  try {
    const response = await fetch(
      "/index.html?__campus_version_check=" + Date.now(),
      { cache: "no-store", signal: controller.signal },
    );
    if (
      !response.ok ||
      !response.headers.get("content-type")?.includes("text/html")
    )
      return;
    const latest = assets(
      new DOMParser().parseFromString(await response.text(), "text/html"),
    );
    if (!stopped && latest && latest !== loaded) available.value = true;
  } catch {
    // Temporary offline/error responses do not imply a new release. Retry on focus or next interval.
  } finally {
    clearTimeout(timeout);
    controller = undefined;
  }
}
const refresh = () => location.reload();
onMounted(() => {
  window.addEventListener("focus", check);
  document.addEventListener("visibilitychange", check);
  interval = setInterval(check, 60000);
  check();
});
onUnmounted(() => {
  stopped = true;
  clearInterval(interval);
  controller?.abort();
  window.removeEventListener("focus", check);
  document.removeEventListener("visibilitychange", check);
});
</script>
<template>
  <aside
    v-if="available"
    class="app-update-notice"
    role="status"
    aria-label="页面版本更新"
  >
    <div>
      <strong>页面有新版本</strong>
      <p>请先保存当前编辑，再刷新使用最新界面。刷新后需要重新登录。</p>
    </div>
    <button type="button" @click="refresh">刷新并重新登录</button>
  </aside>
</template>
<style scoped>
.app-update-notice {
  position: fixed;
  right: 20px;
  bottom: 20px;
  z-index: 2000;
  display: flex;
  align-items: center;
  gap: 16px;
  width: min(600px, calc(100vw - 40px));
  padding: 16px;
  border: 1px solid #c7d2fe;
  border-radius: 8px;
  background: #fff;
  box-shadow: 0 6px 24px #1e293b26;
  color: #354052;
  font:
    14px/1.5 Arial,
    "Microsoft YaHei",
    sans-serif;
}
.app-update-notice p {
  margin: 4px 0 0;
}
.app-update-notice button {
  flex-shrink: 0;
  min-height: 36px;
  padding: 6px 12px;
  border: 0;
  border-radius: 4px;
  color: white;
  background: #4f46e5;
  font: inherit;
  cursor: pointer;
}
.app-update-notice button:focus-visible {
  outline: 2px solid #818cf8;
  outline-offset: 2px;
}
@media (max-width: 650px) {
  .app-update-notice {
    flex-wrap: wrap;
    right: 12px;
    bottom: 12px;
    width: calc(100vw - 24px);
  }
}
</style>
