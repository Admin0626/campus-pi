<script setup>
import { ref, watch, onUnmounted } from "vue";
import { api } from "../api";
const props = defineProps({ files: { type: Array, default: () => [] }, mode: { type: String, default: 'materials', validator: value => ['materials','thumbnail','gallery','message'].includes(value) } });
const entries = ref([]);
let generation = 0,
  controller;
const transport = /^\/file-content\/([0-9a-f-]{36})\/[A-Za-z0-9_-]{43}$/i;
function idOf(value) {
  if (typeof value !== "string") return null;
  if (
    /^file:\/\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(
      value,
    )
  )
    return value.slice(7);
  try {
    return (
      new URL(value, location.origin).pathname.match(transport)?.[1] || null
    );
  } catch {
    return null;
  }
}
function dispose() {
  controller?.abort();
  for (const item of entries.value) if (item.src) URL.revokeObjectURL(item.src);
}
async function load() {
  const serial = ++generation;
  dispose();
  controller = new AbortController();
  const activeController = controller;
  const signal = activeController.signal;
  entries.value = props.files.map((value, i) => ({
    id: idOf(value),
    label: `${props.mode === 'materials' ? '材料' : '图片'} ${i + 1}`,
    src: "",
    mime: "",
    loading: true,
    error: "",
  }));
  // Sequential requests avoid flooding the controlled preview endpoint.
  for (const entry of entries.value) {
    if (serial !== generation) return;
    let timer;
    try {
      if (!entry.id) throw new Error("文件引用无效，无法预览");
      const grant = await api(`/files/${entry.id}/access`, { signal });
      if (serial !== generation) return;
      const path = new URL(grant.url, location.origin).pathname;
      if (
        !transport.test(path) ||
        path.split("/")[2].toLowerCase() !== entry.id.toLowerCase()
      )
        throw new Error("文件访问地址无效");
      // Always use this application's origin (including the Vite proxy), never arbitrary external URLs.
      timer = setTimeout(() => activeController.abort(), 15000);
      const response = await fetch(path, {
        signal,
        cache: "no-store",
        referrerPolicy: "no-referrer",
      });
      if (!response.ok) throw new Error("文件读取失败，请刷新材料或重新登录");
      const mime = response.headers.get("Content-Type")?.split(";")[0];
      if (
        !["image/png", "image/jpeg", "image/webp", "application/pdf"].includes(
          mime,
        )
      )
        throw new Error("不支持预览此文件类型");
      const blob = await response.blob();
      if (serial !== generation) return;
      entry.mime = mime;
      entry.src = URL.createObjectURL(blob);
    } catch (error) {
      if (serial === generation)
        entry.error =
          error.name === "AbortError"
            ? "材料读取超时或已取消，请刷新重试"
            : error.message;
    } finally {
      clearTimeout(timer);
      entry.loading = false;
    }
  }
}
watch(() => props.files, load, { immediate: true });
onUnmounted(() => {
  generation++;
  dispose();
});
</script>
<template>
  <section class="admin-file-preview" :class="'file-preview-' + mode" :aria-label="mode === 'materials' ? '文件材料预览' : '图片预览'">
    <header v-if="mode === 'materials'">
      <h4>文件材料</h4>
      <button type="button" @click="load">刷新材料</button>
    </header>
    <p v-if="!entries.length" class="file-preview-empty">{{ mode === 'materials' ? '暂无文件材料' : '暂无图片' }}</p>
    <figure v-for="(entry, index) in entries" :key="index">
      <figcaption v-if="mode === 'materials'">{{ entry.label }}</figcaption>
      <p v-if="entry.loading" role="status">正在读取材料…</p>
      <p v-else-if="entry.error" role="alert">{{ entry.error }} <button type="button" @click="load">重新加载图片</button></p>
      <img
        v-else-if="entry.mime.startsWith('image/')"
        :src="entry.src"
        :alt="entry.label"
      />
      <a
        v-else-if="entry.src"
        :href="entry.src"
        target="_blank"
        rel="noopener noreferrer"
        >打开 PDF 材料 {{ index + 1 }}</a
      >
    </figure>
  </section>
</template>
