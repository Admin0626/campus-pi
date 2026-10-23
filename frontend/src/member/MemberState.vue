<script setup>
defineProps({ state: Object, loading: Boolean, error: String });
</script>
<template>
  <p v-if="loading" role="status">正在加载…</p>
  <p v-if="error || state?.error" class="member-error" role="alert">
    {{ error || state.error }}
  </p>
  <p v-if="state?.notice" role="status" class="member-notice">
    {{ state.notice }}
  </p>
</template>
