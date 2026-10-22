<script setup>
import AdminPageHeader from "./AdminPageHeader.vue";
import AdminPagination from "./AdminPagination.vue";
import { ref, reactive, watch, onUnmounted } from "vue";
import { api, requestKey } from "../api";
const tab = ref("notices"),
  rows = ref([]),
  page = ref(1),
  total = ref("0"),
  state = ref(""),
  loading = ref(false),
  busy = ref(false),
  error = ref(""),
  notice = ref(""),
  detail = ref(null),
  editor = ref(false),
  editing = ref(null),
  selected = ref(null),
  deliveryState = ref(""),
  channel = ref("");
const reviewed = ref(false);
const action = ref(null),
  reason = ref("");
const form = reactive({
  notification_title: "",
  notification_content: "",
  notification_type: "系统",
  target_scope: "全体",
  campus: "",
  user_ids: "",
});
let controller,
  generation = 0,
  mutationKey = "",
  mutationBody = "";
function time(value) {
  return value
    ? new Intl.DateTimeFormat("zh-CN", {
        timeZone: "Asia/Shanghai",
        dateStyle: "short",
        timeStyle: "medium",
        hour12: false,
      }).format(new Date(value))
    : "—";
}
function target(row) {
  return row.target_scope === "校区"
    ? row.target_filter.campus
    : row.target_scope === "指定用户"
      ? `指定 ${row.target_filter.user_ids.length} 位用户`
      : "全体";
}
async function load() {
  const serial = ++generation;
  controller?.abort();
  controller = new AbortController();
  loading.value = true;
  error.value = "";
  try {
    if (tab.value === "deliveries" && !selected.value) {
      rows.value = [];
      total.value = "0";
      return;
    }
    const query = new URLSearchParams({
      page: String(page.value),
      page_size: "20",
    });
    if (tab.value === "notices" && state.value)
      query.set("publish_status", state.value);
    if (tab.value === "deliveries") {
      if (deliveryState.value)
        query.set("delivery_status", deliveryState.value);
      if (channel.value) query.set("channel", channel.value);
    }
    const value = await api(
      `/admin/notifications${tab.value === "deliveries" ? `/${selected.value.notification_id}/deliveries` : ""}?${query}`,
      { signal: controller.signal },
    );
    if (serial === generation) {
      rows.value = value.items;
      total.value = value.total;
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
watch(
  tab,
  () => {
    rows.value = [];
    total.value = "0";
    page.value = 1;
    load();
  },
  { immediate: true },
);
onUnmounted(() => {
  generation++;
  controller?.abort();
});
function search() {
  page.value = 1;
  load();
}
function reset() {
  state.value = "";
  deliveryState.value = "";
  channel.value = "";
  search();
}
function turn(delta) {
  page.value += delta;
  load();
}
async function show(row, edit = false) {
  busy.value = true;
  error.value = "";
  try {
    const data = await api(`/admin/notifications/${row.notification_id}`);
    if (edit) open(data);
    else detail.value = data;
  } catch (e) {
    error.value = e.message;
  } finally {
    busy.value = false;
  }
}
function open(row = null) {
  editing.value = row?.notification_id || null;
  Object.assign(form, {
    notification_title: row?.notification_title || "",
    notification_content: row?.notification_content || "",
    notification_type: row?.notification_type || "系统",
    target_scope: row?.target_scope || "全体",
    campus: row?.target_filter?.campus || "",
    user_ids: row?.target_filter?.user_ids?.join("\n") || "",
  });
  mutationKey = requestKey();
  mutationBody = "";
  editor.value = true;
  error.value = "";
}
function records(row) {
  selected.value = row;
  deliveryState.value = "";
  channel.value = "";
  if (tab.value === "deliveries") search();
  else tab.value = "deliveries";
}
async function save() {
  const ids = form.user_ids
    .trim()
    .split(/[\s,，]+/)
    .filter(Boolean);
  if (
    form.target_scope === "指定用户" &&
    (!ids.length ||
      ids.length > 1000 ||
      ids.some((id) => !/^[1-9][0-9]{0,18}$/.test(id)) ||
      new Set(ids).size !== ids.length)
  ) {
    error.value = "请输入1–1000个不重复的用户编号，以逗号或换行分隔";
    return;
  }
  const body = {
    notification_title: form.notification_title.trim(),
    notification_content: form.notification_content,
    notification_type: form.notification_type,
    target_scope: form.target_scope,
    target_filter:
      form.target_scope === "全体"
        ? {}
        : form.target_scope === "校区"
          ? { campus: form.campus.trim() }
          : { user_ids: ids },
  };
  const serialized = JSON.stringify(body);
  if (mutationBody && mutationBody !== serialized) mutationKey = requestKey();
  mutationBody = serialized;
  busy.value = true;
  error.value = "";
  try {
    await api(
      `/admin/notifications${editing.value ? `/${editing.value}` : ""}`,
      {
        method: editing.value ? "PUT" : "POST",
        body,
        key: editing.value ? undefined : mutationKey,
      },
    );
    editor.value = false;
    if (!editing.value) {
      page.value = 1;
      state.value = "";
      tab.value = "notices";
    }
    notice.value = editing.value ? "草稿已更新" : "草稿已创建，尚未发送";
    await load();
  } catch (e) {
    error.value = e.message;
  } finally {
    busy.value = false;
  }
}
async function prepare(row, kind) {
  if (kind === "publish") {
    busy.value = true;
    error.value = "";
    try {
      row = await api(`/admin/notifications/${row.notification_id}`);
    } catch (e) {
      error.value = e.message;
      return;
    } finally {
      busy.value = false;
    }
    if (row.publish_status !== "草稿") {
      error.value = "通知状态已改变，请刷新后重试";
      return;
    }
  }
  reviewed.value = false;
  action.value = { row, kind };
  reason.value = "";
  mutationKey = requestKey();
  mutationBody = "";
  error.value = "";
}
async function confirmAction() {
  if (action.value.kind === "publish" && !reviewed.value) {
    error.value = "请先核对通知内容和发送对象";
    return;
  }
  if (action.value.kind !== "publish" && !reason.value.trim()) {
    error.value = "请填写操作原因";
    return;
  }
  const body =
      action.value.kind === "publish"
        ? { channels: ["站内"] }
        : { reason: reason.value.trim() },
    serialized = JSON.stringify(body);
  if (mutationBody && mutationBody !== serialized) mutationKey = requestKey();
  mutationBody = serialized;
  const { row, kind } = action.value;
  busy.value = true;
  error.value = "";
  try {
    await api(
      kind === "publish"
        ? `/admin/notifications/${row.notification_id}/publish`
        : kind === "withdraw"
          ? `/admin/notifications/${row.notification_id}/withdraw`
          : `/admin/notification-deliveries/${row.delivery_id}/retry`,
      { method: "POST", body, key: mutationKey },
    );
    action.value = null;
    notice.value =
      kind === "publish"
        ? "通知已进入投递队列；关闭站内接收的用户已排除，其余按免打扰时间投递"
        : kind === "withdraw"
          ? "通知已撤回，尚未执行的投递已取消"
          : "已创建一次新的待发送尝试，请刷新查看结果";
    await load();
  } catch (e) {
    error.value = e.message;
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <section class="admin-content-panel notice-panel" :aria-busy="loading">
    <AdminPageHeader title="通知管理" :subtitle="`共 ${total} 条记录`"
      ><template #actions
        ><button
          class="admin-purple"
          :disabled="busy || loading"
          @click="open()"
        >
          ＋ 新建通知</button
        ><button :disabled="busy || loading" @click="load">
          刷新
        </button></template
      ></AdminPageHeader
    >
    <div class="admin-tabs">
      <button
        :class="{ active: tab === 'notices' }"
        :disabled="busy"
        @click="tab = 'notices'"
      >
        通知列表</button
      ><button
        :class="{ active: tab === 'deliveries' }"
        :disabled="busy"
        @click="tab = 'deliveries'"
      >
        发送记录
      </button>
    </div>
    <p v-if="notice" class="admin-message success" role="status">
      {{ notice }}
    </p>
    <p v-if="error" class="admin-message failure" role="alert">{{ error }}</p>
    <form class="admin-filters" @submit.prevent="search">
      <template v-if="tab === 'notices'"
        ><label
          >通知状态<select v-model="state" aria-label="通知状态">
            <option value="">所有状态</option>
            <option
              v-for="value in ['草稿', '待发送', '已发送', '已撤回']"
              :key="value"
            >
              {{ value }}
            </option>
          </select></label
        ></template
      >
      <template v-else
        ><span v-if="selected" class="notice-selection"
          >当前通知：{{ selected.notification_title
          }}<small>{{ selected.notification_id }}</small></span
        ><label
          >投递状态<select v-model="deliveryState" aria-label="投递状态">
            <option value="">所有状态</option>
            <option v-for="value in ['待发送', '成功', '失败']" :key="value">
              {{ value }}
            </option>
          </select></label
        ><label
          >投递渠道<select v-model="channel" aria-label="投递渠道">
            <option value="">所有渠道</option>
            <option v-for="value in ['站内', '短信', '邮件']" :key="value">
              {{ value }}
            </option>
          </select></label
        ></template
      >
      <button
        class="admin-query"
        :disabled="busy || loading || (tab === 'deliveries' && !selected)"
      >
        查询</button
      ><button type="button" :disabled="busy || loading" @click="reset">
        重置
      </button>
    </form>
    <p v-if="loading" class="admin-loading" role="status">正在加载…</p>
    <div class="admin-table-scroll">
      <table class="admin-data-table">
        <thead>
          <tr v-if="tab === 'notices'">
            <th>通知ID</th>
            <th>标题</th>
            <th>类型</th>
            <th>发送对象</th>
            <th>状态</th>
            <th>发布时间</th>
            <th>操作</th>
          </tr>
          <tr v-else>
            <th>尝试ID</th>
            <th>用户ID</th>
            <th>渠道</th>
            <th>状态</th>
            <th>次数</th>
            <th>送达时间</th>
            <th>失败信息</th>
          </tr>
        </thead>
        <tbody>
          <template v-if="tab === 'notices'"
            ><tr v-for="row in rows" :key="row.notification_id">
              <td>{{ row.notification_id }}</td>
              <td>{{ row.notification_title }}</td>
              <td>{{ row.notification_type }}</td>
              <td>{{ target(row) }}</td>
              <td>
                <span
                  class="admin-state"
                  :class="{ good: row.publish_status === '已发送' }"
                  >{{ row.publish_status }}</span
                >
              </td>
              <td>{{ time(row.publish_time) }}</td>
              <td>
                <div class="admin-row-actions">
                  <button :disabled="busy" @click="show(row)">查看</button
                  ><button
                    v-if="row.publish_status === '草稿'"
                    :disabled="busy"
                    @click="show(row, true)"
                  >
                    编辑</button
                  ><button :disabled="busy" @click="records(row)">
                    发送记录</button
                  ><button
                    v-if="row.publish_status === '草稿'"
                    :disabled="busy"
                    @click="prepare(row, 'publish')"
                  >
                    审核发布
                  </button>
                  <button
                    v-if="['待发送', '已发送'].includes(row.publish_status)"
                    class="danger"
                    :disabled="busy"
                    @click="prepare(row, 'withdraw')"
                  >
                    撤回
                  </button>
                </div>
              </td>
            </tr></template
          >
          <template v-else
            ><tr v-for="row in rows" :key="row.delivery_id">
              <td>{{ row.delivery_id }}</td>
              <td>{{ row.user_id }}</td>
              <td>{{ row.channel }}</td>
              <td>{{ row.delivery_status }}</td>
              <td>{{ row.attempt_no }}</td>
              <td>{{ time(row.sent_at) }}</td>
              <td>
                {{ row.failure_reason || "—"
                }}<button
                  v-if="
                    row.delivery_status === '失败' &&
                    selected?.publish_status !== '已撤回'
                  "
                  :disabled="busy"
                  @click="prepare(row, 'retry')"
                >
                  重试
                </button>
              </td>
            </tr></template
          >
          <tr v-if="!loading && !rows.length">
            <td colspan="7" class="admin-empty">
              {{
                tab === "deliveries" && !selected
                  ? "请在通知列表选择一条通知，查看其发送记录"
                  : error
                    ? "未能加载数据，请重试"
                    : "暂无记录"
              }}
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <AdminPagination
      :page="page"
      :total="total"
      :disabled="loading || busy"
      @turn="turn"
    />
    <p class="admin-source-note">
      草稿不会发送。发布状态表示接收者快照已建立，实际到达以投递记录为准；关闭则不接收，免打扰结束后投递，安全通知同样遵守偏好。
    </p>
    <div v-if="detail" class="admin-modal-backdrop">
      <section
        class="admin-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="notice-detail-title"
      >
        <header>
          <h3 id="notice-detail-title">通知详情</h3>
          <button :disabled="busy" aria-label="关闭详情" @click="detail = null">
            ×
          </button>
        </header>
        <dl>
          <dt>通知ID</dt>
          <dd>{{ detail.notification_id }}</dd>
          <dt>标题</dt>
          <dd>{{ detail.notification_title }}</dd>
          <dt>类型 / 状态</dt>
          <dd>{{ detail.notification_type }} / {{ detail.publish_status }}</dd>
          <dt>发送对象</dt>
          <dd>{{ target(detail) }}</dd>
          <dt v-if="detail.target_scope === '指定用户'">用户ID</dt>
          <dd v-if="detail.target_scope === '指定用户'">
            {{ detail.target_filter.user_ids.join("、") }}
          </dd>
          <dt>发布时间</dt>
          <dd>{{ time(detail.publish_time) }}</dd>
          <dt>正文</dt>
          <dd class="notice-body">{{ detail.notification_content }}</dd>
        </dl>
        <footer><button @click="detail = null">关闭</button></footer>
      </section>
    </div>
    <div v-if="editor" class="admin-modal-backdrop">
      <section
        class="admin-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="notice-editor-title"
      >
        <header>
          <h3 id="notice-editor-title">
            {{ editing ? "编辑通知草稿" : "新建通知草稿" }}
          </h3>
          <button
            :disabled="busy"
            aria-label="关闭编辑"
            @click="editor = false"
          >
            ×
          </button>
        </header>
        <form @submit.prevent="save">
          <label
            >通知标题<input
              v-model="form.notification_title"
              required
              maxlength="200"
              :disabled="busy" /></label
          ><label
            >通知类型<select
              v-model="form.notification_type"
              aria-label="通知类型"
              :disabled="busy"
            >
              <option
                v-for="value in ['系统', '互动', '交易', '审核', '安全']"
                :key="value"
              >
                {{ value }}
              </option>
            </select></label
          ><label
            >发送范围<select
              v-model="form.target_scope"
              aria-label="发送范围"
              :disabled="busy"
            >
              <option
                v-for="value in ['全体', '校区', '指定用户']"
                :key="value"
              >
                {{ value }}
              </option>
            </select></label
          ><label v-if="form.target_scope === '校区'"
            >目标校区<input
              v-model="form.campus"
              required
              maxlength="50"
              :disabled="busy" /></label
          ><label v-if="form.target_scope === '指定用户'"
            >用户编号<textarea
              v-model="form.user_ids"
              required
              :disabled="busy"
              placeholder="以逗号或换行分隔，最多1000人"
            /></label
          ><label
            >通知正文<textarea
              v-model="form.notification_content"
              required
              maxlength="10000"
              rows="7"
              :disabled="busy"
            />
          </label>
          <p v-if="error" class="admin-message failure" role="alert">
            {{ error }}
          </p>
          <footer>
            <button type="button" :disabled="busy" @click="editor = false">
              取消</button
            ><button class="admin-purple" :disabled="busy">
              {{ busy ? "保存中…" : "保存草稿" }}
            </button>
          </footer>
        </form>
      </section>
    </div>
    <div v-if="action" class="admin-modal-backdrop">
      <section
        class="admin-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="notice-action-title"
      >
        <header>
          <h3 id="notice-action-title">
            {{
              action.kind === "publish"
                ? "审核并发布通知"
                : action.kind === "withdraw"
                  ? "撤回通知"
                  : "重试投递"
            }}
          </h3>
        </header>
        <p>
          {{
            action.kind === "publish"
              ? "核对后将固定当前接收者快照并创建站内投递任务；后续新增用户不会自动补发。"
              : action.kind === "withdraw"
                ? "撤回后站内不可见，并取消尚未执行的投递。已完成的发送记录保留。"
                : "只允许重试最新的失败记录，每位用户每渠道最多5次，不会覆盖旧尝试。"
          }}
        </p>
        <form @submit.prevent="confirmAction">
          <template v-if="action.kind === 'publish'"
            ><dl>
              <dt>标题</dt>
              <dd>{{ action.row.notification_title }}</dd>
              <dt>发送对象</dt>
              <dd>
                {{ target(action.row)
                }}<template v-if="action.row.target_scope === '指定用户'">
                  · {{ action.row.target_filter.user_ids.join("、") }}</template
                >
              </dd>
              <dt>通知类型</dt>
              <dd>{{ action.row.notification_type }}</dd>
              <dt>正文</dt>
              <dd class="notice-body">{{ action.row.notification_content }}</dd>
            </dl>
            <label
              ><input
                v-model="reviewed"
                type="checkbox"
                required
                :disabled="busy"
              />我已核对内容及发送对象</label
            ></template
          >
          <label v-else
            >操作原因<textarea
              v-model="reason"
              required
              maxlength="500"
              :disabled="busy"
            />
          </label>
          <p v-if="error" role="alert" class="admin-message failure">
            {{ error }}
          </p>
          <footer>
            <button type="button" :disabled="busy" @click="action = null">
              取消</button
            ><button class="admin-purple" :disabled="busy">确认操作</button>
          </footer>
        </form>
      </section>
    </div>
  </section>
</template>
<style scoped>
.notice-body {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.notice-selection {
  max-width: 420px;
  overflow-wrap: anywhere;
  font-size: 13px;
}
.notice-selection small {
  display: block;
  color: #64748b;
}
.notice-panel .admin-dialog label {
  display: grid;
  gap: 6px;
  margin: 14px 0;
}
.notice-panel .admin-dialog input,
.notice-panel .admin-dialog select,
.notice-panel .admin-dialog textarea {
  width: 100%;
}
</style>

<style scoped>
.notice-panel .admin-dialog input[type="checkbox"] {
  width: auto;
  justify-self: start;
}
</style>
