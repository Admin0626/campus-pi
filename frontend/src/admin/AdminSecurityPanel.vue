<script setup>
import AdminPageHeader from "./AdminPageHeader.vue";
import AdminPagination from "./AdminPagination.vue";
import { computed, ref, watch, onUnmounted } from "vue";
import { api, requestKey } from "../api";
import FilePreview from "./FilePreview.vue";
const props = defineProps({ module: String });
const emit = defineEmits(["navigate"]);
const verification = computed(() => props.module === "verifications");
const rows = ref([]),
  total = ref("0"),
  page = ref(1),
  loading = ref(false),
  busy = ref(false),
  error = ref(""),
  notice = ref("");
const state = ref(""),
  type = ref(""),
  keyword = ref(""),
  detail = ref(null),
  action = ref(null),
  reason = ref(""),
  role = ref("USER");
const title = computed(() => (verification.value ? "认证审核" : "权限管理"));
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
async function load() {
  const serial = ++generation;
  controller?.abort();
  controller = new AbortController();
  loading.value = true;
  error.value = "";
  try {
    const q = new URLSearchParams({
      page: String(page.value),
      page_size: "20",
    });
    if (verification.value) {
      if (state.value) q.set("audit_status", state.value);
      if (type.value) q.set("verification_type", type.value);
    } else if (keyword.value.trim()) q.set("keyword", keyword.value.trim());
    const data = await api(
      `/admin/${verification.value ? "verifications" : "users"}?${q}`,
      { signal: controller.signal },
    );
    if (serial === generation) {
      rows.value = data.items;
      total.value = data.total;
    }
  } catch (e) {
    if (serial === generation) error.value = e.message;
  } finally {
    if (serial === generation) loading.value = false;
  }
}
watch(
  () => props.module,
  () => {
    detail.value = null;
    action.value = null;
    rows.value = [];
    total.value = "0";
    page.value = 1;
    state.value = "";
    type.value = "";
    keyword.value = "";
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
function turn(delta) {
  page.value += delta;
  load();
}
async function show(row) {
  busy.value = true;
  error.value = "";
  const module = props.module;
  try {
    const value = await api(`/admin/verifications/${row.verification_id}`);
    if (props.module === module) detail.value = value;
  } catch (e) {
    error.value = e.message;
  } finally {
    busy.value = false;
  }
}
function prepare(row, decision) {
  action.value = { row, decision };
  reason.value = "";
  role.value = row.role === "ADMIN" ? "USER" : "ADMIN";
  mutationKey = requestKey();
  mutationBody = "";
  error.value = "";
}
async function save() {
  if (!reason.value.trim()) {
    error.value = "请填写操作原因";
    return;
  }
  const { row, decision } = action.value;
  const body = verification.value
    ? { decision, reason: reason.value.trim() }
    : { role: role.value, reason: reason.value.trim() };
  const serialized = JSON.stringify(body);
  if (mutationBody && serialized !== mutationBody) mutationKey = requestKey();
  mutationBody = serialized;
  busy.value = true;
  error.value = "";
  try {
    await api(
      verification.value
        ? `/admin/verifications/${row.verification_id}/review`
        : `/admin/users/${row.user_id}/role`,
      { method: "POST", body, key: mutationKey },
    );
    action.value = null;
    detail.value = null;
    notice.value = "操作已保存";
    await load();
  } catch (e) {
    error.value = e.message;
  } finally {
    busy.value = false;
  }
}
const fields = computed(() =>
  detail.value
    ? [
        ["申请编号", detail.value.verification_id],
        ["用户编号", detail.value.user_id],
        ["认证类型", detail.value.verification_type],
        ["姓名", detail.value.real_name],
        ["校区", detail.value.campus],
        ["学号", detail.value.student_no],
        ["审核状态", detail.value.audit_status],
        ["审核原因", detail.value.audit_reason],
        ["提交时间", time(detail.value.submitted_at)],
        ["审核时间", time(detail.value.reviewed_at)],
      ]
    : [],
);
</script>
<template>
  <section class="admin-content-panel" :aria-busy="loading">
    <AdminPageHeader :title="title" :subtitle="`共 ${total} 条记录`"
      ><template #actions
        ><button :disabled="loading || busy" @click="load">刷新</button
        ><button @click="emit('navigate', '/admin')">
          返回控制台
        </button></template
      ></AdminPageHeader
    >
    <p class="admin-source-note">
      {{
        verification
          ? "列表隐藏姓名、学号和材料；打开详情后记录敏感资料访问日志。"
          : "调整角色需要服务端特权授权；调整后目标账号的全部会话将失效，必须保留至少一名启用管理员。"
      }}
    </p>
    <form class="admin-filters" @submit.prevent="search">
      <template v-if="verification"
        ><label
          >审核状态<select v-model="state" aria-label="审核状态">
            <option value="">全部</option>
            <option v-for="v in ['待审核', '已通过', '已驳回']" :key="v">
              {{ v }}
            </option>
          </select></label
        ><label
          >认证类型<select v-model="type" aria-label="认证类型">
            <option value="">全部</option>
            <option>实名</option>
            <option>校园</option>
          </select></label
        ></template
      >
      <label v-else
        >用户名关键词<input v-model="keyword" maxlength="50"
      /></label>
      <button class="admin-purple" :disabled="loading || busy">查询</button>
    </form>
    <p v-if="error" role="alert" class="admin-message failure">{{ error }}</p>
    <p v-if="notice" role="status" class="admin-message success">
      {{ notice }}
    </p>
    <div class="admin-table-scroll">
      <table>
        <thead>
          <tr v-if="verification">
            <th>申请编号</th>
            <th>用户编号</th>
            <th>认证类型</th>
            <th>校区</th>
            <th>审核状态</th>
            <th>提交时间</th>
            <th>操作</th>
          </tr>
          <tr v-else>
            <th>用户编号</th>
            <th>用户名</th>
            <th>角色</th>
            <th>账号状态</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="row in rows"
            :key="verification ? row.verification_id : row.user_id"
          >
            <template v-if="verification"
              ><td>{{ row.verification_id }}</td>
              <td>{{ row.user_id }}</td>
              <td>{{ row.verification_type }}</td>
              <td>{{ row.campus || "—" }}</td>
              <td>{{ row.audit_status }}</td>
              <td>{{ time(row.submitted_at) }}</td>
              <td class="admin-row-actions">
                <button :disabled="busy || loading" @click="show(row)">
                  查看材料
                </button>
              </td></template
            >
            <template v-else
              ><td>{{ row.user_id }}</td>
              <td>{{ row.username }}</td>
              <td>{{ row.role }}</td>
              <td>{{ row.account_status }}</td>
              <td class="admin-row-actions">
                <button :disabled="busy || loading" @click="prepare(row)">
                  调整角色
                </button>
              </td></template
            >
          </tr>
          <tr v-if="!rows.length">
            <td colspan="7" class="admin-empty">
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
    <div v-if="detail || action" class="admin-modal-backdrop">
      <section
        class="admin-dialog"
        role="dialog"
        aria-modal="true"
        :aria-label="
          action ? (verification ? '审核认证' : '调整角色') : '认证材料详情'
        "
      >
        <header>
          <h3>
            {{
              action ? (verification ? "审核认证" : "调整角色") : "认证材料详情"
            }}
          </h3>
          <button
            aria-label="关闭对话框"
            :disabled="busy"
            @click="
              detail = null;
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
            {{
              verification
                ? action.decision + "认证申请 " + action.row.verification_id
                : "调整用户 " + action.row.username + " 的角色"
            }}
          </p>
          <label v-if="!verification"
            >角色<select v-model="role" aria-label="角色" :disabled="busy">
              <option>USER</option>
              <option>ADMIN</option>
            </select></label
          ><label
            >操作原因<textarea
              v-model="reason"
              required
              maxlength="500"
              :disabled="busy"
            ></textarea></label
          ><button class="admin-purple" :disabled="busy">
            {{ busy ? "保存中…" : "确认提交" }}
          </button>
        </form>
        <template v-else
          ><dl>
            <template v-for="[label, value] in fields" :key="label"
              ><dt>{{ label }}</dt>
              <dd>{{ value || "—" }}</dd></template
            >
          </dl>
          <FilePreview :files="detail.evidence_refs" />
          <div
            v-if="detail.audit_status === '待审核'"
            class="admin-inline-actions"
          >
            <button
              class="admin-purple"
              :disabled="busy"
              @click="prepare(detail, '通过')"
            >
              通过认证</button
            ><button
              class="danger"
              :disabled="busy"
              @click="prepare(detail, '驳回')"
            >
              驳回认证
            </button>
          </div></template
        >
      </section>
    </div>
  </section>
</template>
