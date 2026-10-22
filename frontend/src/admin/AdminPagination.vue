<script setup>
import { computed } from "vue";
const props = defineProps({
  page: { type: Number, required: true },
  total: { type: String, required: true },
  pageSize: { type: Number, default: 20 },
  disabled: Boolean,
  pageSizes: Array,
});
const emit = defineEmits(["turn", "size"]);
const next = computed(
  () => BigInt(props.page) * BigInt(props.pageSize) < BigInt(props.total),
);
const first = computed(() =>
  props.total === "0"
    ? "0"
    : String(BigInt(props.page - 1) * BigInt(props.pageSize) + 1n),
);
const last = computed(() =>
  String(
    BigInt(props.page) * BigInt(props.pageSize) < BigInt(props.total)
      ? BigInt(props.page) * BigInt(props.pageSize)
      : BigInt(props.total),
  ),
);
function turn(delta) {
  if (!props.disabled && (delta < 0 ? props.page > 1 : next.value))
    emit("turn", delta);
}
</script>
<template>
  <footer class="admin-pagination" aria-label="列表分页">
    <span v-if="pageSizes">显示{{ first }}至{{ last }}条，共{{ total }}条</span>
    <span v-else>第 {{ page }} 页 · 共 {{ total }} 条</span>
    <div class="admin-pagination-actions">
      <button type="button" :disabled="disabled || page <= 1" @click="turn(-1)">
        上一页
      </button>
      <span v-if="pageSizes" class="admin-current-page" aria-current="page">{{
        page
      }}</span>
      <button type="button" :disabled="disabled || !next" @click="turn(1)">
        下一页
      </button>
    </div>
    <label v-if="pageSizes"
      >每页显示：<select
        :value="pageSize"
        :disabled="disabled"
        aria-label="每页显示条数"
        @change="emit('size', Number($event.target.value))"
      >
        <option v-for="value in pageSizes" :key="value" :value="value">
          {{ value }}条
        </option>
      </select></label
    >
  </footer>
</template>
