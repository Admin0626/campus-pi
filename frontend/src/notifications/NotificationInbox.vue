<script setup>
import { ref, reactive, onMounted, onUnmounted } from "vue";
import { api } from "../api";
import Icon from "../Icon.vue";
const props = defineProps({
  backTarget: { type: String, default: "/me" },
  backLabel: { type: String, default: "返回个人页" },
});
const emit = defineEmits(["navigate"]);
const rows = ref([]),
  page = ref(1),
  total = ref("0"),
  type = ref(""),
  unread = ref(false),
  loading = ref(false),
  busy = ref(false),
  error = ref(""),
  notice = ref(""),
  detail = ref(null),
  deleting = ref(null),
  preferencesOpen = ref(false),
  preferencesReady = ref(false);
const preferences = reactive({
  in_app_enabled: true,
  email_enabled: false,
  sms_enabled: false,
  dnd_start: "",
  dnd_end: "",
});
const tabs = [
  ["全部通知", ""],
  ["系统通知", "系统"],
  ["交易通知", "交易"],
  ["互动通知", "互动"],
  ["审核通知", "审核"],
  ["安全通知", "安全"],
];
let generation = 0,
  controller;
function time(value) {
  return value
    ? new Intl.DateTimeFormat("zh-CN", {
        timeZone: "Asia/Shanghai",
        dateStyle: "short",
        timeStyle: "short",
        hour12: false,
      }).format(new Date(value))
    : "—";
}
function icon(row) {
  return (
    { 系统: "bell", 交易: "box", 互动: "users", 审核: "shield", 安全: "lock" }[
      row.notification_type
    ] || "bell"
  );
}
async function load() {
  const serial = ++generation;
  controller?.abort();
  controller = new AbortController();
  loading.value = true;
  error.value = "";
  try {
    const query = new URLSearchParams({
      page: String(page.value),
      page_size: "20",
      unread_only: String(unread.value),
    });
    if (type.value) query.set("notification_type", type.value);
    const data = await api(`/me/notifications?${query}`, {
      signal: controller.signal,
    });
    if (serial === generation) {
      rows.value = data.items;
      total.value = data.total;
    }
  } catch (e) {
    if (serial === generation) {
      rows.value = [];
      total.value = "0";
      error.value = e.message;
    }
  } finally {
    if (serial === generation) loading.value = false;
  }
}
function filter(value) {
  type.value = value;
  page.value = 1;
  detail.value = null;
  load();
}
function turn(delta) {
  page.value += delta;
  load();
}
async function show(row) {
  busy.value = true;
  error.value = "";
  try {
    detail.value = await api(
      `/me/notifications/${encodeURIComponent(row.notification_id)}`,
    );
  } catch (e) {
    error.value = e.message;
  } finally {
    busy.value = false;
  }
}
async function mutate(action, row = detail.value) {
  busy.value = true;
  error.value = "";
  try {
    const data = await api(
      `/me/notifications/${encodeURIComponent(row.notification_id)}${action === "delete" ? "" : "/" + action}`,
      { method: action === "delete" ? "DELETE" : "PUT" },
    );
    if (action === "delete") {
      deleting.value = null;
      detail.value = null;
      notice.value = "已删除本人通知，其他接收者不受影响";
    } else {
      detail.value = data;
      notice.value =
        action === "read" ? "已标为已读" : "已记录本次点击，首次时间保持不变";
    }
    await load();
  } catch (e) {
    error.value = e.message;
  } finally {
    busy.value = false;
  }
}
async function openPreferences() {
  preferencesOpen.value = true;
  preferencesReady.value = false;
  busy.value = true;
  error.value = "";
  try {
    const data = await api("/me/notification-preferences");
    Object.assign(preferences, {
      ...data,
      dnd_start: data.dnd_start || "",
      dnd_end: data.dnd_end || "",
    });
    preferencesReady.value = true;
  } catch (e) {
    error.value = e.message;
  } finally {
    busy.value = false;
  }
}
async function savePreferences() {
  const start = preferences.dnd_start,
    end = preferences.dnd_end;
  if (Boolean(start) !== Boolean(end) || (start && start === end)) {
    error.value = "起止时间需同时填写且不能相同";
    return;
  }
  busy.value = true;
  error.value = "";
  try {
    const format = (value) =>
      value ? (value.length === 5 ? value + ":00" : value) : null;
    await api("/me/notification-preferences", {
      method: "PUT",
      body: {
        in_app_enabled: preferences.in_app_enabled,
        email_enabled: false,
        sms_enabled: false,
        dnd_start: format(start),
        dnd_end: format(end),
      },
    });
    preferencesOpen.value = false;
    notice.value = "通知偏好已保存；安全通知也遵守此设置";
    await load();
  } catch (e) {
    error.value = e.message;
  } finally {
    busy.value = false;
  }
}
onMounted(load);
onUnmounted(() => {
  generation++;
  controller?.abort();
});
</script>
<template>
  <main class="word-mobile-stage">
    <section class="word-mobile-screen inbox-screen" :aria-busy="loading">
      <header class="inbox-header">
        <button
          :aria-label="props.backLabel"
          :disabled="busy"
          @click="emit('navigate', props.backTarget)"
        >
          <Icon name="back" />
        </button>
        <h1>通知</h1>
        <button :disabled="busy" @click="openPreferences">设置</button>
      </header>
      <nav class="inbox-tabs" aria-label="通知类型">
        <button
          v-for="[label, value] in tabs"
          :key="value"
          :class="{ selected: type === value }"
          :aria-pressed="type === value"
          :disabled="busy"
          @click="filter(value)"
        >
          {{ label }}
        </button>
      </nav>
      <div class="inbox-tools">
        <label
          ><input
            v-model="unread"
            type="checkbox"
            :disabled="busy"
            @change="
              page = 1;
              load();
            "
          />只看未读</label
        ><button :disabled="busy || loading" @click="load">刷新通知</button>
      </div>
      <p v-if="error" class="inbox-feedback failure" role="alert">
        {{ error }}
      </p>
      <p v-if="notice" class="inbox-feedback" role="status">{{ notice }}</p>
      <p v-if="loading" class="inbox-empty" role="status">正在加载…</p>
      <div v-if="!detail" class="inbox-list">
        <article
          v-for="row in rows"
          :key="row.notification_id"
          :class="{ unread: !row.read_at }"
          :data-notification-id="row.notification_id"
        >
          <button class="inbox-message" :disabled="busy" @click="show(row)">
            <span class="inbox-kind" :class="'kind-' + row.notification_type"
              ><Icon :name="icon(row)" /></span
            ><span class="inbox-copy"
              ><strong>{{ row.notification_title }}</strong
              ><span>{{ row.notification_content }}</span
              ><small
                >{{ time(row.publish_time) }} ·
                {{ row.read_at ? "已读" : "未读" }}</small
              ></span
            >
          </button>
          <div class="inbox-row-actions">
            <button :disabled="busy" @click="show(row)">查看详情</button
            ><button :disabled="busy" @click="deleting = row">删除</button>
          </div>
        </article>
        <p v-if="!loading && !rows.length" class="inbox-empty">
          {{ error ? "加载失败，请重试" : "暂无通知" }}
        </p>
      </div>
      <article v-else class="inbox-detail">
        <button :disabled="busy" @click="detail = null">返回列表</button>
        <h2>{{ detail.notification_title }}</h2>
        <p>{{ detail.notification_type }} · {{ time(detail.publish_time) }}</p>
        <div class="inbox-body">{{ detail.notification_content }}</div>
        <dl>
          <dt>已读时间</dt>
          <dd>{{ time(detail.read_at) }}</dd>
          <dt>点击时间</dt>
          <dd>{{ time(detail.clicked_at) }}</dd>
        </dl>
        <p class="inbox-help">查看详情不会自动标记已读或记录点击。</p>
        <div class="inbox-detail-actions">
          <button :disabled="busy" @click="mutate('read')">标为已读</button
          ><button :disabled="busy" @click="mutate('click')">记录点击</button
          ><button :disabled="busy" @click="deleting = detail">删除通知</button>
        </div>
      </article>
      <footer class="inbox-pagination">
        <span>第 {{ page }} 页 · 共 {{ total }} 条</span
        ><button :disabled="busy || loading || page <= 1" @click="turn(-1)">
          上一页</button
        ><button
          :disabled="busy || loading || BigInt(page) * 20n >= BigInt(total)"
          @click="turn(1)"
        >
          下一页
        </button>
      </footer>
      <div v-if="deleting" class="inbox-backdrop">
        <section
          class="inbox-dialog"
          role="dialog"
          aria-modal="true"
          aria-labelledby="inbox-delete-title"
        >
          <h2 id="inbox-delete-title">删除本人通知</h2>
          <p>{{ deleting.notification_title }}</p>
          <p>只删除你的接收记录，不影响其他接收者。</p>
          <p v-if="error" role="alert" class="failure">{{ error }}</p>
          <div>
            <button :disabled="busy" @click="deleting = null">取消</button
            ><button :disabled="busy" @click="mutate('delete', deleting)">
              确认删除
            </button>
          </div>
        </section>
      </div>
      <div v-if="preferencesOpen" class="inbox-backdrop">
        <section
          class="inbox-dialog"
          role="dialog"
          aria-modal="true"
          aria-labelledby="inbox-pref-title"
        >
          <h2 id="inbox-pref-title">通知偏好</h2>
          <p>
            关闭则不接收新的站内通知；安全通知也遵守偏好。免打扰按北京时间计算，开始晚于结束表示跨夜。
          </p>
          <form @submit.prevent="savePreferences">
            <label class="inbox-check"
              ><input
                v-model="preferences.in_app_enabled"
                type="checkbox"
                :disabled="busy || !preferencesReady"
              />接收站内通知</label
            ><label
              >免打扰开始<input
                v-model="preferences.dnd_start"
                type="time"
                step="1"
                :disabled="busy || !preferencesReady" /></label
            ><label
              >免打扰结束<input
                v-model="preferences.dnd_end"
                type="time"
                step="1"
                :disabled="busy || !preferencesReady"
            /></label>
            <p class="inbox-help">
              留空为不启用；结束后才显示新投递的通知。已收到的历史通知保留。
            </p>
            <p>短信与邮件渠道尚未接入，保持关闭。</p>
            <p v-if="error" role="alert" class="failure">{{ error }}</p>
            <div>
              <button
                type="button"
                :disabled="busy"
                @click="preferencesOpen = false"
              >
                取消</button
              ><button :disabled="busy || !preferencesReady">保存偏好</button>
            </div>
          </form>
        </section>
      </div>
    </section>
  </main>
</template>
<style scoped>
.inbox-screen {
  min-height: 844px;
  background: #f7f8fa;
  color: #303341;
  padding-bottom: 28px;
}
.inbox-header {
  display: grid;
  grid-template-columns: 56px 1fr 56px;
  align-items: center;
  background: white;
  padding: 12px 8px;
}
.inbox-header h1 {
  font-size: 17px;
  text-align: center;
  font-weight: 500;
  margin: 0;
}
.inbox-header button {
  border: 0;
  background: none;
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 32px;
  color: #6d5ce7;
}
.inbox-tabs {
  display: flex;
  overflow: auto;
  gap: 0;
  background: #fff;
  border-bottom: 1px solid #edf0f5;
  scrollbar-width: thin;
}
.inbox-tabs button {
  position: relative;
  white-space: nowrap;
  border: 0;
  background: none;
  padding: 14px 12px;
  color: #777;
  font-size: 13px;
}
.inbox-tabs button.selected {
  color: #7961f5;
}
.inbox-tabs button.selected:after {
  content: "";
  position: absolute;
  height: 3px;
  background: #7961f5;
  width: 23px;
  bottom: 0;
  left: calc(50% - 11.5px);
}
.inbox-tools {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  font-size: 12px;
}
.inbox-tools label {
  display: flex;
  gap: 6px;
  align-items: center;
}
.inbox-tools button,
.inbox-row-actions button,
.inbox-detail button,
.inbox-pagination button {
  border: 0;
  background: none;
  color: #7961f5;
  font-size: 12px;
  padding: 6px;
}
.inbox-list {
  padding: 0 14px;
}
.inbox-list article {
  background: #fff;
  margin-bottom: 10px;
  padding: 12px;
}
.inbox-list article.unread {
  background: #f0f3ff;
}
.inbox-message {
  display: flex;
  gap: 12px;
  width: 100%;
  padding: 0;
  border: 0;
  background: none;
  text-align: left;
  color: inherit;
}
.inbox-kind {
  display: flex;
  width: 36px;
  height: 36px;
  border-radius: 50%;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  background: #e3f7ff;
  color: #249be8;
}
.kind-交易 {
  color: #77cb2c;
  background: #effde1;
}
.kind-互动 {
  color: #fa9820;
  background: #fff4e3;
}
.kind-安全 {
  color: #745ae6;
  background: #eeeaff;
}
.inbox-copy {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
  flex: 1;
}
.inbox-copy strong {
  font-size: 13px;
  font-weight: 500;
  overflow-wrap: anywhere;
}
.inbox-copy > span {
  font-size: 12px;
  color: #888;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.inbox-copy small {
  font-size: 10px;
  color: #a2a6b0;
  margin-top: 8px;
}
.inbox-row-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 5px;
}
.inbox-empty {
  text-align: center;
  padding: 40px 12px;
  color: #8b91a0;
  font-size: 13px;
}
.inbox-feedback {
  font-size: 12px;
  line-height: 1.6;
  margin: 10px 16px;
  color: #286f54;
  overflow-wrap: anywhere;
}
.failure {
  color: #b53a54;
}
.inbox-pagination {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  font-size: 11px;
  gap: 8px;
  padding: 12px 16px;
  color: #88909f;
}
.inbox-pagination span {
  margin-right: auto;
}
.inbox-detail {
  margin: 12px 14px;
  padding: 18px;
  background: white;
  font-size: 13px;
  line-height: 1.8;
}
.inbox-detail h2 {
  font-size: 18px;
  overflow-wrap: anywhere;
}
.inbox-detail p,
.inbox-help {
  color: #89909e;
  font-size: 12px;
}
.inbox-body {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  margin: 22px 0;
}
.inbox-detail dl {
  font-size: 12px;
  display: grid;
  grid-template-columns: 72px 1fr;
  gap: 8px;
}
.inbox-detail dd {
  margin: 0;
}
.inbox-detail-actions {
  display: flex;
  flex-wrap: wrap;
}
.inbox-backdrop {
  position: fixed;
  inset: 0;
  z-index: 300;
  background: #17203380;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 18px;
}
.inbox-dialog {
  box-sizing: border-box;
  background: white;
  border-radius: 8px;
  padding: 22px;
  width: 360px;
  max-width: 100%;
  max-height: 90dvh;
  overflow: auto;
  font-size: 13px;
  line-height: 1.8;
}
.inbox-dialog h2 {
  margin: 0 0 12px;
  font-size: 18px;
}
.inbox-dialog form > label {
  display: grid;
  gap: 6px;
  margin: 14px 0;
}
.inbox-dialog label.inbox-check {
  display: flex;
  align-items: center;
}
.inbox-dialog input[type="time"] {
  font: inherit;
  padding: 8px;
  border: 1px solid #dfe3ed;
  border-radius: 4px;
  min-width: 0;
  width: 100%;
  box-sizing: border-box;
}
.inbox-dialog button {
  padding: 8px 12px;
  margin-right: 10px;
  background: white;
  color: #7961f5;
  border: 1px solid #e0dcef;
  border-radius: 4px;
}
button:disabled {
  opacity: 0.45;
  cursor: default;
}
@media (max-width: 440px) {
  .word-mobile-stage {
    padding: 0;
  }
  .inbox-screen {
    min-height: 100dvh;
  }
}
</style>

<style scoped>
.inbox-screen input[type="checkbox"],
.inbox-dialog input[type="checkbox"] {
  appearance: auto;
  width: 16px;
  height: 16px;
  min-height: 16px;
  min-width: 16px;
  max-width: 16px;
  margin: 0;
  padding: 0;
  flex: 0 0 16px;
}
.inbox-tools label,
.inbox-dialog label.inbox-check {
  white-space: nowrap;
  gap: 8px;
  justify-content: flex-start;
}
</style>
