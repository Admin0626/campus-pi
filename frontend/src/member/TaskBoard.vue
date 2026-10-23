<script setup>
import { ref, reactive, onMounted } from "vue";
import { api } from "../api.js";
import { useOperation, usePaged, date } from "./useMember.js";
import MemberState from "./MemberState.vue";
import MemberPager from "./MemberPager.vue";
import MemberForm from "./MemberForm.vue";
import MemberFiles from "./MemberFiles.vue";
import FilePreview from "../admin/FilePreview.vue";
const props = defineProps({ user: Object, records: Boolean });
const scope = ref(props.records ? "published" : "all"),
  detail = ref(null),
  form = ref(""),
  target = ref(null),
  balances = ref([]),
  history = ref("proofs"),
  files = ref([]),
  keyword = ref(""),
  query = ref("");
const { state, run, mutate } = useOperation();
const list = usePaged(
  () =>
    ({
      all: "/tasks?keyword=" + encodeURIComponent(query.value),
      published: "/me/tasks?side=published",
      accepted: "/me/tasks?side=accepted",
      applications: "/me/task-applications",
      ledger: "/me/reward-ledger",
    })[scope.value],
);
const records = usePaged(() =>
  detail.value && related()
    ? `/tasks/${detail.value.reward_task_id}/${history.value}`
    : null,
);
const model = reactive({
  task_name: "",
  task_description: "",
  campus: props.user.campus || "",
  reward_type: "积分",
  reward_amount: "1.00",
  reason: "",
  proof_text: "",
});
const publishFields = [
  { key: "task_name", label: "任务名称", max: 100 },
  { key: "task_description", label: "任务说明", type: "textarea", max: 5000 },
  { key: "campus", label: "认证校区", readonly: true },
  {
    key: "reward_type",
    label: "奖励类型",
    options: ["积分"],
    hint: "发布时冻结余额；现金通道尚未启用。",
  },
  {
    key: "reward_amount",
    label: "奖励积分",
    hint: "正整数积分，固定两位小数，如 10.00",
  },
];
function related() {
  return (
    detail.value &&
    (detail.value.publisher.user_id === props.user.user_id ||
      detail.value.taker?.user_id === props.user.user_id)
  );
}
async function refreshBalances() {
  balances.value = (await api("/me/reward-accounts")).items;
}
onMounted(() => run(refreshBalances));
async function open(row) {
  await run(async () => {
    detail.value = await api("/tasks/" + row.reward_task_id);
    form.value = "";
    records.page.value = 1;
    await records.load();
  });
}
async function submit() {
  await run(async () => {
    if (form.value === "publish") {
      const body = Object.fromEntries(
        publishFields.map((f) => [f.key, model[f.key]]),
      );
      detail.value = await mutate("/tasks", body);
    } else if (form.value === "proof") {
      if (!files.value.length) throw new Error("请上传完成凭证");
      await mutate(`/tasks/${detail.value.reward_task_id}/proofs`, {
        proof_text: model.proof_text,
        evidence_refs: files.value,
      });
      detail.value = await api("/tasks/" + detail.value.reward_task_id);
      files.value = [];
    } else if (form.value === "cancel") {
      detail.value = await mutate(
        `/tasks/${detail.value.reward_task_id}/cancel`,
        { reason: model.reason },
      );
    } else {
      await mutate(`/task-applications/${target.value.application_id}/cancel`, {
        reason: model.reason,
      });
    }
    form.value = "";
    await list.load();
    await records.load();
    await refreshBalances();
  }, "操作成功，状态已更新");
}
async function apply() {
  if (
    !window.confirm(
      "确认申请该任务？申请需要审核，同一任务取消后也不能重复申请。",
    )
  )
    return;
  await run(async () => {
    await mutate(`/tasks/${detail.value.reward_task_id}/applications`);
    await list.load();
  }, "申请已提交，请等待分配审核");
}
function reason(mode, row) {
  model.reason = "";
  target.value = row;
  form.value = mode;
}
</script>
<template>
  <MemberState
    :state="state"
    :loading="list.loading.value"
    :error="list.error.value"
  />
  <div class="member-toolbar">
    <button
      v-for="[key, label] in [
        ['all', '任务广场'],
        ['published', '我发布的'],
        ['accepted', '我接取的'],
        ['applications', '申请记录'],
        ['ledger', '奖励流水'],
      ]"
      :key="key"
      :class="{ active: scope === key }"
      :disabled="state.busy"
      @click="
        scope = key;
        detail = null;
        form = '';
      "
    >
      {{ label }}</button
    ><button
      class="member-primary"
      @click="
        form = 'publish';
        detail = null;
      "
      :disabled="state.busy"
    >
      发布任务
    </button>
  </div>
  <p v-for="balance in balances" :key="balance.reward_type" class="member-meta">
    {{ balance.reward_type }}：可用 {{ balance.available_amount }} · 冻结
    {{ balance.frozen_amount }}
  </p>
  <p class="member-meta">新账号余额为零；平台未开放自行充值或领取积分。</p>
  <form
    v-if="scope === 'all' && !detail && !form"
    class="member-toolbar"
    @submit.prevent="
      query = keyword;
      list.page.value = 1;
      list.load();
    "
  >
    <input v-model="keyword" placeholder="搜索任务" maxlength="100" /><button>
      搜索
    </button>
  </form>
  <MemberForm
    v-if="form"
    :model="model"
    :fields="
      form === 'publish'
        ? publishFields
        : form === 'proof'
          ? [
              {
                key: 'proof_text',
                label: '完成说明',
                type: 'textarea',
                max: 1000,
              },
            ]
          : [{ key: 'reason', label: '取消原因', type: 'textarea', max: 500 }]
    "
    :busy="state.busy"
    @submit="submit"
    @cancel="form = ''"
    ><MemberFiles
      v-if="form === 'proof'"
      v-model="files"
      purpose="TASK_PROOF"
      :disabled="state.busy"
  /></MemberForm>
  <article v-else-if="detail" class="member-card">
    <button @click="detail = null">返回列表</button>
    <span class="member-status-chip">{{ detail.task_status }}</span>
    <h2>{{ detail.task_name }}</h2>
    <p>{{ detail.task_description }}</p>
    <p class="member-meta">
      {{ detail.campus }} · {{ detail.task_status }}<br />发布者
      {{ detail.publisher.username }} · 接取者
      {{ detail.taker?.username || "未分配" }}
    </p>
    <strong class="member-price">{{ detail.reward_amount }} {{ detail.reward_type }}</strong>
    <dl class="member-task-facts"><dt>发布校区</dt><dd>{{ detail.campus }}</dd><dt>发布者</dt><dd>{{ detail.publisher.username }}</dd><dt>实际接取者</dt><dd>{{ detail.taker?.username || '待审核分配' }}</dd></dl>
    <div class="member-actions">
      <button
        v-if="
          detail.publisher.user_id !== user.user_id &&
          detail.task_status === '待接取'
        "
        :disabled="state.busy"
        @click="apply"
      >
        申请接取</button
      ><button
        v-if="
          detail.publisher.user_id === user.user_id &&
          ['待审核', '待接取'].includes(detail.task_status)
        "
        @click="reason('cancel')"
      >
        取消任务并解冻</button
      ><button
        v-if="
          detail.taker?.user_id === user.user_id &&
          detail.task_status === '进行中'
        "
        @click="form = 'proof'"
      >
        提交完成凭证
      </button>
    </div>
    <template v-if="related()"
      ><div class="member-toolbar">
        <button @click="history = 'proofs'">凭证版本</button
        ><button @click="history = 'logs'">流转记录</button>
      </div>
      <MemberState
        :loading="records.loading.value"
        :error="records.error.value" />
      <article
        v-for="row in records.items.value"
        :key="row.proof_id || row.log_id"
        class="member-card"
      >
        <h3>
          {{
            row.proof_id
              ? "第 " + row.submission_no + " 次凭证 · " + row.audit_status
              : row.action_name
          }}
        </h3>
        <p>{{ row.proof_text || row.reason }}</p>
        <p>{{ row.audit_reason }}</p>
        <FilePreview v-if="row.evidence_refs" :files="row.evidence_refs" />
        <p class="member-meta">
          {{ date(row.submitted_at || row.operated_at) }}
        </p>
      </article>
      <MemberPager
        :page="records.page.value"
        :total="records.total.value"
        :loading="records.loading.value"
        @turn="records.turn"
    /></template>
  </article>
  <template v-else
    ><p
      v-if="
        !list.loading.value && !list.items.value.length && !list.error.value
      "
    >
      暂无记录
    </p>
    <article
      v-for="row in list.items.value"
      :key="
        row.reward_task_id +
        '-' +
        (row.application_id || row.ledger_id || 'task')
      "
      class="member-card"
    >
      <template v-if="scope === 'ledger'"
        ><h3>{{ row.event_type }} · {{ row.reward_type }}</h3>
        <p>
          可用变动 {{ row.available_delta }} · 冻结变动 {{ row.frozen_delta }}
        </p>
        <p class="member-meta">
          {{ date(row.created_at) }} · 任务 {{ row.reward_task_id }}
        </p></template
      ><template v-else
        ><h3>{{ row.task_name || "任务 " + row.reward_task_id }}</h3>
        <p class="member-task-summary"><span class="member-status-chip">{{ row.task_status || row.application_status }}</span><strong v-if="row.reward_amount" class="member-price">{{ row.reward_amount }} {{ row.reward_type || '' }}</strong></p>
        <p v-if="row.audit_reason">{{ row.audit_reason }}</p>
        <div class="member-actions">
          <button :disabled="state.busy" @click="open(row)">任务详情</button
          ><button
            v-if="row.application_status === '待审核'"
            @click="reason('cancel-application', row)"
          >
            取消申请
          </button>
        </div></template
      >
    </article>
    <MemberPager
      :page="list.page.value"
      :total="list.total.value"
      :loading="list.loading.value"
      @turn="list.turn"
  /></template>
</template>
