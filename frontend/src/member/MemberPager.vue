<script setup>
defineProps({ page: Number, total: [String, Number], loading: Boolean });
const emit = defineEmits(["turn"]);
</script>
<template>
  <div class="member-pager">
    <button :disabled="loading || page <= 1" @click="emit('turn', page - 1)">
      上一页</button
    ><span>第 {{ page }} 页 · 共 {{ total }} 条</span
    ><button
      :disabled="loading || BigInt(page) * 10n >= BigInt(total)"
      @click="emit('turn', page + 1)"
    >
      下一页
    </button>
  </div>
</template>
