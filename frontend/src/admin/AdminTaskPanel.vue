<script setup>
import AdminPageHeader from "./AdminPageHeader.vue";
import AdminPagination from "./AdminPagination.vue";
import { computed, ref, watch, onUnmounted } from "vue";
import { api, requestKey } from "../api";
import FilePreview from "./FilePreview.vue";
import AdminBatchActions from "./AdminBatchActions.vue";
import { useAdminSelection } from "./useAdminSelection";
const section = ref("management"),
  kind = ref("tasks");
const rows = ref([]),
  total = ref("0"),
  page = ref(1),
  state = ref(""),
  campus = ref(""),
  taskId = ref("");
const loading = ref(false),
  busy = ref(false),
  error = ref(""),
  notice = ref(""),
  detail = ref(null),
  evidence = ref(null),
  action = ref(null),
  reason = ref("");
const view = computed(() => (section.value === "logs" ? "logs" : kind.value));
const states = computed(
  () =>
    ({
      tasks: [
        "待审核",
        "待接取",
        "进行中",
        "待验收",
        "已完成",
        "未完成",
        "已驳回",
        "已取消",
      ],
      applications: ["待审核", "已通过", "已驳回", "已取消"],
      proofs: ["待审核", "已通过", "补充凭证", "未通过"],
      logs: [],
    })[view.value],
);
const time = (value) =>
  value
    ? new Date(value).toLocaleString("zh-CN", {
        timeZone: "Asia/Shanghai",
        hour12: false,
      })
    : "—";
let controller,
  generation = 0,
  mutationKey = "",
  mutationBody = "";
const rowId = (row) =>
  row.application_id || row.proof_id || row.log_id || row.reward_task_id;
const { selected, available, allSelected, toggle, toggleAll } = useAdminSelection(rows, row => row.reward_task_id, row => row.task_status === "待审核", loading);
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
    });
    let url;
    if (view.value === "logs") {
      if (!/^[1-9][0-9]{0,18}$/.test(taskId.value)) {
        rows.value = [];
        total.value = "0";
        return;
      }
      url = `/tasks/${taskId.value}/logs?${query}`;
    } else {
      if (view.value === "tasks") {
        if (campus.value.trim()) query.set("campus", campus.value.trim());
        if (state.value) query.set("task_status", state.value);
      } else {
        if (taskId.value.trim())
          query.set("reward_task_id", taskId.value.trim());
        if (state.value)
          query.set(
            view.value === "applications"
              ? "application_status"
              : "audit_status",
            state.value,
          );
      }
      url = `/admin/${view.value === "tasks" ? "tasks" : "task-" + view.value}?${query}`;
    }
    const value = await api(url, { signal: controller.signal });
    if (serial === generation) {
      rows.value = value.items;
      total.value = value.total;
    }
  } catch (e) {
    if (serial === generation) error.value = e.message;
  } finally {
    if (serial === generation) loading.value = false;
  }
}
watch(
  view,
  () => {
    rows.value = [];
    total.value = "0";
    page.value = 1;
    state.value = "";
    detail.value = null;
    evidence.value = null;
    action.value = null;
    notice.value = "";
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
  campus.value = "";
  taskId.value = "";
  search();
}
function turn(delta) {
  page.value += delta;
  load();
}
function selectRelated(row, target) {
  const previousView = view.value;
  taskId.value = row.reward_task_id;
  detail.value = null;
  evidence.value = null;
  if (target === "logs") section.value = "logs";
  else {
    section.value = "management";
    kind.value = target;
  }
  page.value = 1;
  state.value = "";
  if (view.value === previousView) load();
}
async function showTask(row) {
  busy.value = true;
  error.value = "";
  const serial = generation;
  try {
    const value = await api(`/tasks/${row.reward_task_id}`);
    if (serial === generation) detail.value = value;
  } catch (e) {
    error.value = e.message;
  } finally {
    busy.value = false;
  }
}
function prepare(row, operation) {
  const category = view.value;
  action.value = { row, operation, category };
  reason.value = "";
  mutationKey = requestKey();
  mutationBody = "";
  error.value = "";
}
async function save() {
  if (!reason.value.trim()) {
    error.value = "请填写操作原因";
    return;
  }
  const { row, operation, category } = action.value;
  const close = operation === "关闭";
  const body = close
    ? { reason: reason.value.trim() }
    : { decision: operation, reason: reason.value.trim() };
  const serialized = JSON.stringify(body);
  if (mutationBody && mutationBody !== serialized) mutationKey = requestKey();
  mutationBody = serialized;
  const url =
    category === "tasks"
      ? `/admin/tasks/${row.reward_task_id}/${close ? "close" : "publication-review"}`
      : `/admin/task-${category}/${rowId(row)}/review`;
  busy.value = true;
  error.value = "";
  try {
    await api(url, { method: "POST", body, key: mutationKey });
    action.value = null;
    detail.value = null;
    evidence.value = null;
    notice.value = "操作已保存";
    await load();
  } catch (e) {
    error.value = e.message;
  } finally {
    busy.value = false;
  }
}
const detailFields = computed(() =>
  detail.value
    ? [
        ["任务编号", detail.value.reward_task_id],
        ["标题", detail.value.task_name],
        ["内容与验收标准", detail.value.task_description],
        ["发布者", detail.value.publisher.username],
        ["接取者", detail.value.taker?.username || "尚未分配"],
        ["校区", detail.value.campus],
        ["奖励", `${detail.value.reward_amount} ${detail.value.reward_type}`],
        ["状态", detail.value.task_status],
        ["版本", detail.value.version],
        ["任务订单", detail.value.order_id || "尚未创建"],
        ["发布时间", time(detail.value.publish_time)],
      ]
    : [],
);
</script>
<template>
  <section class="admin-content-panel" :aria-busy="loading">
    <AdminPageHeader title="悬赏任务管理" :subtitle="`共 ${total} 条记录`"
      ><template #actions
        ><button :disabled="loading || busy" @click="load">
          刷新
        </button></template
      ></AdminPageHeader
    >
    <div class="admin-tabs">
      <button
        :class="{ active: section === 'management' }"
        @click="section = 'management'"
      >
        任务管理</button
      ><button
        :class="{ active: section === 'logs' }"
        @click="section = 'logs'"
      >
        审核记录
      </button>
    </div>
    <div
      v-if="section === 'management'"
      class="admin-inline-actions task-subviews"
    >
      <button
        v-for="[code, label] in [
          ['tasks', '任务列表'],
          ['applications', '接取申请'],
          ['proofs', '凭证验收'],
        ]"
        :key="code"
        :class="{ 'admin-purple': kind === code }"
        @click="kind = code"
      >
        {{ label }}
      </button>
    </div>
    <form class="admin-filters" @submit.prevent="search">
      <label v-if="view !== 'logs'"
        >状态<select v-model="state" aria-label="任务筛选状态">
          <option value="">全部</option>
          <option v-for="value in states" :key="value">{{ value }}</option>
        </select></label
      >
      <label v-if="view === 'tasks'"
        >校区<input v-model="campus" maxlength="50"
      /></label>
      <label v-else
        >任务编号<input
          v-model="taskId"
          inputmode="numeric"
          pattern="[1-9][0-9]{0,18}"
          maxlength="19"
          :required="view === 'logs'"
      /></label>
      <button class="admin-purple" :disabled="loading || busy">查询</button
      ><button type="button" :disabled="loading || busy" @click="reset">
        重置
      </button>
    </form>
    <p v-if="view === 'logs' && !taskId" class="admin-source-note">
      请输入任务编号，或从任务详情打开审核记录。
    </p>
    <p v-if="error" role="alert" class="admin-message failure">{{ error }}</p>
    <p v-if="notice" role="status" class="admin-message success">
      {{ notice }}
    </p>
    <AdminBatchActions v-if="view === 'tasks'" kind="tasks" :ids="selected" :disabled="loading || busy" @completed="load" />
    <div class="admin-table-scroll" data-admin-records>
      <table>
        <thead>
          <tr v-if="view === 'tasks'">
            <th><input type="checkbox" aria-label="全选当前页待审核任务" :checked="allSelected" :indeterminate="selected.length > 0 && !allSelected" :disabled="loading || !available.length" @change="toggleAll" /></th>
            <th>任务ID</th>
            <th>标题</th>
            <th>发布者</th>
            <th>奖励</th>
            <th>状态</th>
            <th>发布时间</th>
            <th>操作</th>
          </tr>
          <tr v-else-if="view === 'applications'">
            <th>申请ID</th>
            <th>任务ID</th>
            <th>申请人ID</th>
            <th>状态</th>
            <th>申请时间</th>
            <th>审核原因</th>
            <th>操作</th>
          </tr>
          <tr v-else-if="view === 'proofs'">
            <th>凭证ID</th>
            <th>任务ID</th>
            <th>版本</th>
            <th>完成说明</th>
            <th>状态</th>
            <th>提交时间</th>
            <th>操作</th>
          </tr>
          <tr v-else>
            <th>记录ID</th>
            <th>任务ID</th>
            <th>操作人ID</th>
            <th>动作</th>
            <th>状态变化</th>
            <th>原因</th>
            <th>操作时间</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in rows" :key="rowId(row)" :data-row-id="rowId(row)">
            <template v-if="view === 'tasks'"
              ><td><input type="checkbox" :aria-label="'选择任务' + row.reward_task_id" :checked="selected.includes(row.reward_task_id)" :disabled="loading || row.task_status !== '待审核'" @change="toggle(row)" /></td>
              <td>{{ row.reward_task_id }}</td>
              <td>{{ row.task_name }}</td>
              <td>{{ row.publisher.username }}</td>
              <td>{{ row.reward_amount }} {{ row.reward_type }}</td>
              <td>
                <span
                  class="admin-state"
                  :class="
                    row.task_status === '已完成'
                      ? 'good'
                      : ['已驳回', '已取消', '未完成'].includes(row.task_status)
                        ? 'bad'
                        : 'neutral'
                  "
                  >{{ row.task_status }}</span
                >
              </td>
              <td>{{ time(row.publish_time) }}</td>
              <td class="admin-row-actions">
                <button :disabled="busy || loading" @click="showTask(row)">
                  查看</button
                ><template v-if="row.task_status === '待审核'"
                  ><button
                    class="positive"
                    :disabled="busy || loading"
                    @click="prepare(row, '通过')"
                  >
                    通过</button
                  ><button
                    class="danger"
                    :disabled="busy || loading"
                    @click="prepare(row, '驳回')"
                  >
                    驳回
                  </button></template
                ><button
                  v-if="
                    ['待审核', '待接取', '未完成'].includes(row.task_status)
                  "
                  class="danger"
                  :disabled="busy || loading"
                  @click="prepare(row, '关闭')"
                >
                  关闭
                </button>
              </td></template
            >
            <template v-else-if="view === 'applications'"
              ><td>{{ row.application_id }}</td>
              <td>{{ row.reward_task_id }}</td>
              <td>{{ row.applicant_id }}</td>
              <td>{{ row.application_status }}</td>
              <td>{{ time(row.applied_at) }}</td>
              <td>{{ row.audit_reason || "—" }}</td>
              <td class="admin-row-actions">
                <button :disabled="busy || loading" @click="showTask(row)">
                  查看任务</button
                ><template v-if="row.application_status === '待审核'"
                  ><button
                    class="positive"
                    :disabled="busy || loading"
                    @click="prepare(row, '通过')"
                  >
                    通过</button
                  ><button
                    class="danger"
                    :disabled="busy || loading"
                    @click="prepare(row, '驳回')"
                  >
                    驳回
                  </button></template
                >
              </td></template
            >
            <template v-else-if="view === 'proofs'"
              ><td>{{ row.proof_id }}</td>
              <td>{{ row.reward_task_id }}</td>
              <td>第 {{ row.submission_no }} 版</td>
              <td>{{ row.proof_text }}</td>
              <td>{{ row.audit_status }}</td>
              <td>{{ time(row.submitted_at) }}</td>
              <td class="admin-row-actions">
                <button :disabled="busy || loading" @click="evidence = row">
                  查看材料</button
                ><button :disabled="busy || loading" @click="showTask(row)">
                  查看任务</button
                ><template v-if="row.audit_status === '待审核'"
                  ><button
                    v-for="choice in ['通过', '补充凭证', '未通过']"
                    :key="choice"
                    :disabled="busy || loading"
                    @click="prepare(row, choice)"
                  >
                    {{ choice }}
                  </button></template
                >
              </td></template
            >
            <template v-else
              ><td>{{ row.log_id }}</td>
              <td>{{ row.reward_task_id }}</td>
              <td>{{ row.operator_id }}</td>
              <td>{{ row.action_name }}</td>
              <td>{{ row.from_status || "—" }} → {{ row.to_status }}</td>
              <td>{{ row.reason }}</td>
              <td>{{ time(row.operated_at) }}</td></template
            >
          </tr>
          <tr v-if="!rows.length">
            <td :colspan="view === 'tasks' ? 8 : 7" class="admin-empty">
              {{
                loading
                  ? "正在加载…"
                  : error
                    ? "未能加载数据"
                    : "暂无符合条件的记录"
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
    <div v-if="detail || evidence || action" class="admin-modal-backdrop">
      <section
        class="admin-dialog"
        role="dialog"
        aria-modal="true"
        :aria-label="
          action ? '任务操作确认' : evidence ? '任务凭证材料' : '任务详情'
        "
      >
        <header>
          <h3>
            {{
              action ? "任务操作确认" : evidence ? "任务凭证材料" : "任务详情"
            }}
          </h3>
          <button
            aria-label="关闭对话框"
            :disabled="busy"
            @click="
              detail = null;
              evidence = null;
              action = null;
            "
          >
            ×
          </button>
        </header>
        <p v-if="error" role="alert" class="admin-message failure">
          {{ error }}
        </p>
        <form v-if="action" @submit.prevent="save">
          <p>
            {{ action.operation
            }}{{
              action.category === "applications"
                ? "接取申请"
                : action.category === "proofs"
                  ? "任务凭证"
                  : "任务"
            }}
            {{ rowId(action.row) }}
          </p>
          <p v-if="action.category === 'proofs' && action.operation === '通过'">
            通过验收后按任务约定结算奖励。
          </p>
          <p
            v-if="
              action.operation === '关闭' ||
              (action.operation === '驳回' && action.category === 'tasks')
            "
          >
            本操作将释放尚未结算的任务奖励。
          </p>
          <label
            >操作原因<textarea
              v-model="reason"
              required
              maxlength="500"
              :disabled="busy"
            ></textarea></label
          ><button class="admin-purple" :disabled="busy">
            {{ busy ? "提交中…" : "确认操作" }}
          </button>
        </form>
        <template v-else-if="evidence"
          ><p>
            任务 {{ evidence.reward_task_id }} · 第
            {{ evidence.submission_no }} 版 · {{ evidence.audit_status }}
          </p>
          <p>{{ evidence.proof_text }}</p>
          <p v-if="evidence.audit_reason">
            审核原因：{{ evidence.audit_reason }}
          </p>
          <FilePreview :files="evidence.evidence_refs"
        /></template>
        <template v-else
          ><dl>
            <template v-for="[label, value] in detailFields" :key="label"
              ><dt>{{ label }}</dt>
              <dd>{{ value }}</dd></template
            >
          </dl>
          <div class="admin-inline-actions">
            <button @click="selectRelated(detail, 'applications')">
              接取申请</button
            ><button @click="selectRelated(detail, 'proofs')">凭证版本</button
            ><button @click="selectRelated(detail, 'logs')">审核记录</button>
          </div></template
        >
      </section>
    </div>
  </section>
</template>
