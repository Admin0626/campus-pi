<script setup>
defineProps({
  model: Object,
  fields: Array,
  busy: Boolean,
  submitLabel: { type: String, default: "提交" },
});
const emit = defineEmits(["submit", "cancel"]);
</script>
<template>
  <form class="member-form" @submit.prevent="emit('submit')">
    <slot name="before" />
    <template v-for="field in fields" :key="field.key"
      ><label v-if="!field.when || field.when(model)" :class="{ 'member-check-field': field.type === 'checkbox' }"
        ><span>{{ field.label }}</span
        ><select
          :aria-label="field.label"
          v-if="field.options"
          v-model="model[field.key]"
          :disabled="busy"
          :required="field.required !== false"
        >
          <option
            v-for="option in field.options"
            :key="option.value ?? option"
            :value="option.value ?? option"
          >
            {{ option.label ?? option }}
          </option></select
        ><textarea
          :aria-label="field.label"
          v-else-if="field.type === 'textarea'"
          v-model="model[field.key]"
          rows="4"
          :maxlength="field.max"
          :required="field.required !== false"
          :disabled="busy"
        /><input
          :aria-label="field.label"
          v-else-if="field.type === 'checkbox'"
          type="checkbox"
          v-model="model[field.key]"
          :disabled="busy"
        /><input
          :aria-label="field.label"
          v-else
          v-model="model[field.key]"
          :type="field.type || 'text'"
          :min="field.min"
          :max="field.maxValue"
          :step="field.step"
          :maxlength="field.max"
          :required="field.required !== false"
          :readonly="field.readonly"
          :disabled="busy"
        /><small v-if="field.hint">{{ field.hint }}</small></label
      ></template
    ><slot />
    <div class="member-actions">
      <button class="member-primary" :disabled="busy">
        {{ busy ? "提交中…" : submitLabel }}</button
      ><button type="button" :disabled="busy" @click="emit('cancel')">
        取消
      </button>
    </div>
  </form>
</template>
