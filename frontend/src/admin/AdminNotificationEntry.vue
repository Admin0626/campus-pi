<script setup>
import { ref, watch, onUnmounted } from "vue";
import { api } from "../api";
import Icon from "../Icon.vue";
const props = defineProps({ refreshKey: String });
const emit = defineEmits(["open"]);
const count = ref(null),
  loading = ref(false);
let generation = 0,
  controller;
watch(
  () => props.refreshKey,
  async () => {
    const serial = ++generation;
    controller?.abort();
    controller = new AbortController();
    count.value = null;
    loading.value = true;
    try {
      const data = await api("/me/notifications?unread_only=true&page_size=1", {
        signal: controller.signal,
      });
      if (serial === generation) count.value = data.total;
    } catch {
      /* An unavailable count must not be rendered as zero. Inbox retains its own retry UI. */
    } finally {
      if (serial === generation) loading.value = false;
    }
  },
  { immediate: true },
);
onUnmounted(() => {
  generation++;
  controller?.abort();
});
</script>
<template>
  <button
    class="word-notice-entry"
    aria-label="我的通知"
    :title="
      loading
        ? '正在读取未读数'
        : count === null
          ? '打开我的通知，未读数暂不可用'
          : `未读通知 ${count} 条`
    "
    @click="emit('open')"
  >
    <Icon name="bell" />
    <span
      v-if="count !== null && count !== '0'"
      class="report-notice-count"
      aria-hidden="true"
      >{{ BigInt(count) > 99n ? "99+" : count }}</span
    >
  </button>
</template>
