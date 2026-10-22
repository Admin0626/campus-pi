<script setup>
defineProps({ title: { type: String, required: true }, subtitle: String });
</script>
<template>
  <div class="admin-section-title">
    <div class="admin-section-heading">
      <h2>{{ title }}</h2>
      <p v-if="subtitle">{{ subtitle }}</p>
    </div>
    <div v-if="$slots.actions" class="admin-inline-actions">
      <slot name="actions" />
    </div>
  </div>
</template>
