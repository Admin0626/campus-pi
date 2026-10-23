<script setup>
import { reactive, ref } from "vue";
import { api } from "../api.js";
import { useOperation, usePaged, date } from "./useMember.js";
import MemberForm from "./MemberForm.vue";
import MemberFiles from "./MemberFiles.vue";
import MemberState from "./MemberState.vue";
import MemberPager from "./MemberPager.vue";
import FilePreview from "../admin/FilePreview.vue";
const props = defineProps({ orderId: String });
const emit = defineEmits(["back"]);
const { state, run, mutate } = useOperation();
const { items, page, total, loading, error, load, turn } = usePaged(
  () => "/me/aftersales",
);
const detail = ref(null),
  creating = ref(!!props.orderId),
  files = ref([]),
  model = reactive({ apply_reason: "" });
async function submit() {
  await run(async () => {
    detail.value = await mutate("/aftersales", {
      order_id: props.orderId,
      apply_amount: "0.00",
      apply_reason: model.apply_reason,
      evidence_refs: files.value,
    });
    creating.value = false;
    files.value = [];
    await load();
  }, "争议已提交，审核不会改变任务状态或奖励账本。");
}
async function open(row) {
  await run(async () => {
    detail.value = await api("/aftersales/" + row.aftersale_id);
  });
}
async function cancel() {
  if (!window.confirm("确认撤回此待审核争议？任务和奖励状态不会因此改变。"))
    return;
  await run(async () => {
    detail.value = await mutate(
      "/aftersales/" + detail.value.aftersale_id + "/cancel",
    );
    await load();
  }, "争议已撤回");
}
</script>
<template>
  <button @click="emit('back')">返回订单</button
  ><MemberState :state="state" :loading="loading" :error="error" />
  <h2>售后与任务争议</h2>
  <p class="member-meta">
    当前仅开放任务发布者的 0.00
    金额争议，不改变任务、订单或奖励状态。商品真实退款仍待通道接入。
  </p>
  <MemberForm
    v-if="creating"
    :model="model"
    :fields="[
      { key: 'apply_reason', label: '争议原因', type: 'textarea', max: 500 },
    ]"
    :busy="state.busy"
    @submit="submit"
    @cancel="creating = false"
    ><MemberFiles
      v-model="files"
      purpose="AFTERSALE"
      :disabled="state.busy"
    /><small>证据选填；申请金额固定为 0.00。</small></MemberForm
  >
  <article v-else-if="detail" class="member-card">
    <button @click="detail = null">返回争议列表</button>
    <h3>{{ detail.aftersale_id }} · {{ detail.aftersale_status }}</h3>
    <p>{{ detail.apply_reason }}</p>
    <p>{{ detail.audit_reason || "等待处理" }}</p>
    <p class="member-meta">
      订单 {{ detail.order_id }} · 申请金额 {{ detail.apply_amount }} ·
      {{ date(detail.apply_time) }}
    </p>
    <FilePreview :files="detail.evidence_refs" /><button
      v-if="detail.aftersale_status === '待审核'"
      :disabled="state.busy"
      @click="cancel"
    >
      撤回争议
    </button>
  </article>
  <template v-else
    ><p v-if="!loading && !items.length && !error">暂无争议记录</p>
    <article v-for="row in items" :key="row.aftersale_id" class="member-card">
      <h3>{{ row.aftersale_status }} · {{ row.aftersale_id }}</h3>
      <p>{{ row.apply_reason }}</p>
      <button :disabled="state.busy" @click="open(row)">查看争议</button>
    </article>
    <MemberPager :page="page" :total="total" :loading="loading" @turn="turn"
  /></template>
</template>
