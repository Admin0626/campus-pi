<script setup>
import { computed, ref, watch, onUnmounted } from "vue";
import { api, requestKey } from "../api";
const props = defineProps({ kind: { type: String, required: true }, ids: { type: Array, required: true }, disabled: Boolean });
const emit = defineEmits(["completed"]);
const job = ref(null), recent = ref([]), error = ref(""), form = ref(null), reason = ref(""), choice = ref(""), busy = ref(false);
const label = computed(() => ({ users: "用户状态", products: "商品审核", posts: "帖子审核", tasks: "任务发布审核" })[props.kind]);
const choices = computed(() => props.kind === "users" ? [["ACTIVE", "启用"], ["DISABLED", "禁用"]] : [["通过", "通过"], ["驳回", "驳回"]]);
const pending = computed(() => job.value?.status === "PENDING");
const failures = computed(() => job.value?.items.filter(item => item.item_status === "FAILED") || []);
const stateLabel = value => ({ PENDING: "待执行", SUCCEEDED: "成功", FAILED: "失败", COMPLETED: "已完成" })[value] || value;
const time = value => new Date(value).toLocaleString("zh-CN", { timeZone: "Asia/Shanghai", hour12: false });
let timer, controller, generation = 0, notified = new Set();
function clearPoll() { clearTimeout(timer); controller?.abort(); }
function accept(value, serial = generation) {
  if (serial !== generation) return;
  job.value = value;
  recent.value = [value, ...recent.value.filter(item => item.batch_id !== value.batch_id)].slice(0, 10);
  if (value.status === "COMPLETED") {
    if (!notified.has(value.batch_id)) { notified.add(value.batch_id); emit("completed"); }
  } else timer = setTimeout(() => refreshJob(value.batch_id), 1000);
}
async function refreshJob(id) {
  clearPoll(); const serial = generation; const request = new AbortController(); controller = request;
  try { const value = await api(`/admin/batch-operations/${id}`, { signal: request.signal }); if (serial === generation && !request.signal.aborted) { error.value = ""; accept(value, serial); } }
  catch (e) { if (serial === generation && !request.signal.aborted) error.value = e.message + "；可点击更新批次结果继续查询。"; }
}
async function history() {
  const serial = generation;
  try {
    const value = await api(`/admin/batch-operations?kind=${props.kind}`);
    if (serial !== generation) return;
    recent.value = value.items;
    if (!job.value && value.items.length) { clearPoll(); accept(value.items[0], serial); }
  } catch (e) { if (serial === generation) error.value = e.message; }
}
function open(ids = props.ids) {
  form.value = { ids: [...ids], key: requestKey(), body: null };
  reason.value = job.value?.action.reason || "";
  choice.value = props.kind === "users" ? (job.value?.action.account_status || "DISABLED") : (job.value?.action.decision || "通过");
  error.value = "";
}
async function submit() {
  if (busy.value) return;
  if (!reason.value.trim()) { error.value = "请填写操作原因"; return; }
  if (!form.value.body) form.value.body = { ids: form.value.ids, [props.kind === "users" ? "account_status" : "decision"]: choice.value, reason: reason.value.trim() };
  busy.value = true; error.value = ""; const serial = generation;
  try {
    const value = await api(`/admin/batch-operations/${props.kind}`, { method: "POST", key: form.value.key, body: form.value.body });
    if (serial === generation) { clearPoll(); form.value = null; accept(value, serial); }
  } catch (e) {
    if (serial === generation) {
      error.value = e.message;
      // A rejected request cannot have created a batch; transport/5xx failures remain uncertain.
      if (e.status && e.status < 500) form.value.body = null;
    }
  } finally { if (serial === generation) busy.value = false; }
}
watch(() => props.kind, () => {
  generation++; clearPoll(); job.value = null; recent.value = []; form.value = null; error.value = ""; busy.value = false; history();
}, { immediate: true });
onUnmounted(() => { generation++; clearPoll(); });
</script>
<template>
  <section class="admin-batch" :aria-label="'批量' + label">
    <div class="admin-batch-toolbar">
      <span>已选 {{ ids.length }} 条（仅当前页）</span>
      <button class="admin-purple" :disabled="disabled || !ids.length || pending || busy" @click="open()">批量{{ label }}</button>
      <button :disabled="busy" @click="history">查询最近批次</button>
      <label v-if="recent.length">最近批次
        <select :value="job?.batch_id" :disabled="busy" @change="refreshJob($event.target.value)">
          <option v-for="item in recent" :key="item.batch_id" :value="item.batch_id">{{ time(item.created_at) }} · {{ item.total }} 条 · {{ stateLabel(item.status) }}</option>
        </select>
      </label>
    </div>
    <p v-if="error && !form" class="admin-message failure" role="alert">{{ error }}</p>
    <details v-if="job" class="admin-batch-results">
      <summary>批次{{ stateLabel(job.status) }}：成功 {{ job.succeeded }}，失败 {{ job.failed }}，待执行 {{ job.pending }}</summary>
      <p class="admin-batch-id">批次编号：{{ job.batch_id }}</p>
      <div class="admin-inline-actions">
        <button :disabled="busy" @click="refreshJob(job.batch_id)">更新批次结果</button>
        <button v-if="!pending && failures.length" :disabled="busy || disabled" @click="open(failures.map(item => item.target_id))">只重试失败项</button>
      </div>
      <div class="admin-table-scroll"><table aria-label="批量操作结果"><thead><tr><th>记录编号</th><th>结果</th><th>说明</th></tr></thead>
        <tbody><tr v-for="item in job.items" :key="item.target_id"><td>{{ item.target_id }}</td><td>{{ stateLabel(item.item_status) }}</td><td>{{ item.result_message || '等待处理' }}<small v-if="item.error_code">（{{ item.error_code }}）</small></td></tr></tbody>
      </table></div>
    </details>
    <div v-if="form" class="admin-modal-backdrop">
      <section class="admin-dialog" role="dialog" aria-modal="true" aria-label="批量操作确认">
        <header><h3>批量{{ label }}确认</h3><button aria-label="关闭批量确认" :disabled="busy || !!form.body" @click="form = null">×</button></header>
        <p>将独立处理 {{ form.ids.length }} 条记录。成功项保留，失败项会列出原因。</p>
        <p class="admin-batch-id">记录编号：{{ form.ids.join('、') }}</p>
        <p v-if="kind === 'tasks'">驳回任务时，将按原有规则释放尚未结算的奖励；现金任务仍受现有开关限制。</p>
        <p v-if="error" class="admin-message failure" role="alert">{{ error }}</p>
        <p v-if="form.body && !busy">提交结果尚未确认，请用下方按钮恢复同一请求，避免重复提交。</p>
        <form @submit.prevent="submit">
          <label>操作<select v-model="choice" :disabled="busy || !!form.body"><option v-for="[value, text] in choices" :key="value" :value="value">{{ text }}</option></select></label>
          <label>操作原因<textarea v-model="reason" required maxlength="500" :disabled="busy || !!form.body"></textarea></label>
          <button class="admin-purple" :disabled="busy">{{ busy ? '提交中…' : form.body ? '恢复同一请求' : '确认批量操作' }}</button>
        </form>
      </section>
    </div>
  </section>
</template>
<style scoped>
.admin-batch { margin: 16px 0; font-size: var(--admin-font-size, 14px); }
.admin-batch-toolbar { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; }
.admin-batch-toolbar label { display: flex; align-items: center; gap: 8px; white-space: nowrap; }
.admin-batch-toolbar select { max-width: 320px; }
.admin-batch-results { margin-top: 12px; padding: 12px; background: #f6f7fc; border: 1px solid #e2e6f1; border-radius: 6px; }
.admin-batch-results summary { cursor: pointer; font-weight: 600; }
.admin-batch-id { overflow-wrap: anywhere; color: #64748b; }
.admin-batch-results .admin-table-scroll { min-height: 0; max-height: 280px; overflow: auto; }
</style>
