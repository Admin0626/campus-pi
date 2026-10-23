<script setup>
import { ref } from "vue";
import { api, requestKey } from "../api.js";
import FilePreview from "../admin/FilePreview.vue";
const props = defineProps({
  modelValue: { type: Array, default: () => [] },
  purpose: String,
  max: { type: Number, default: 9 },
  disabled: Boolean,
});
const emit = defineEmits(["update:modelValue"]);
const busy = ref(false),
  error = ref("");
async function upload(event) {
  const input = event.target;
  const file = input.files?.[0];
  if (!file) return;
  busy.value = true;
  error.value = "";
  try {
    if (props.modelValue.length >= props.max)
      throw new Error(`最多上传 ${props.max} 个文件`);
    const body = new FormData();
    body.append("purpose", props.purpose);
    body.append("file", file);
    const result = await api("/files", {
      method: "POST",
      body,
      key: requestKey(),
    });
    emit("update:modelValue", [...props.modelValue, result.file_ref]);
  } catch (e) {
    error.value = e.message;
  } finally {
    busy.value = false;
    input.value = "";
  }
}
</script>
<template>
  <fieldset class="member-files" :disabled="disabled || busy">
    <legend>上传{{ purpose === "VERIFICATION" ? "认证材料" : "图片" }}</legend>
    <input
      type="file"
      :accept="
        purpose === 'VERIFICATION'
          ? 'image/png,image/jpeg,image/webp,application/pdf'
          : 'image/png,image/jpeg,image/webp'
      "
      @change="upload"
      :disabled="modelValue.length >= max"
    />
    <p v-if="busy" role="status">正在上传与安全扫描…</p>
    <p v-if="error" role="alert" class="member-error">{{ error }}</p>
    <div v-for="(ref, index) in modelValue" :key="ref" class="member-file">
      <FilePreview :files="[ref]" /><button
        type="button"
        @click="
          emit(
            'update:modelValue',
            modelValue.filter((_, i) => i !== index),
          )
        "
      >
        移除
      </button>
    </div>
    <small>只接受实际上传并通过安全扫描的文件；图片最多 {{ max }} 个。</small>
  </fieldset>
</template>
