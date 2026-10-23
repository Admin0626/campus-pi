<script setup>
import { reactive, ref } from "vue";
import { useOperation, usePaged, date } from "./useMember.js";
import MemberForm from "./MemberForm.vue";
import MemberFiles from "./MemberFiles.vue";
import MemberState from "./MemberState.vue";
import MemberPager from "./MemberPager.vue";
const { state, run, mutate } = useOperation();
const { items, page, total, loading, error, load, turn } = usePaged(
  () => "/me/verifications",
);
const open = ref(false),
  files = ref([]),
  model = reactive({
    verification_type: "实名",
    real_name: "",
    campus: "",
    student_no: "",
  });
const fields = [
  { key: "verification_type", label: "认证类型", options: ["实名", "校园"] },
  { key: "real_name", label: "真实姓名", max: 50 },
  {
    key: "campus",
    label: "校区",
    max: 50,
    when: (m) => m.verification_type === "校园",
    hint: "必须填写服务端已开放的正式校区名称；先通过实名认证。",
  },
  {
    key: "student_no",
    label: "学号",
    max: 30,
    when: (m) => m.verification_type === "校园",
  },
];
async function submit() {
  await run(async () => {
    if (!files.value.length) throw new Error("请先上传认证材料");
    const body = { ...model, evidence_refs: files.value };
    if (model.verification_type === "实名") {
      delete body.campus;
      delete body.student_no;
    }
    await mutate("/me/verifications", body);
    open.value = false;
    files.value = [];
    await load();
  }, "认证申请已提交，审核结果将在通知中心显示。");
}
</script>
<template>
  <MemberState :state="state" :loading="loading" :error="error" />
  <p class="member-meta">
    实名认证通过后才能提交校园认证。认证材料仅本人和具备职责的审核员可读取。
  </p>
  <button class="member-primary" :disabled="state.busy" @click="open = true">
    提交认证</button
  ><section class="member-verification" v-if="open" :class="{ 'campus-verification': model.verification_type === '校园' }"><header><span class="member-avatar">Π</span><h2>{{ model.verification_type === '校园' ? '校区认证' : '实名认证' }}</h2><p>提交真实材料，审核通过后解锁对应权限</p></header><MemberForm
    v-if="open"
    :model="model"
    :fields="fields"
    :busy="state.busy"
    @submit="submit"
    @cancel="open = false"
    ><MemberFiles v-model="files" purpose="VERIFICATION" :disabled="state.busy"
  /></MemberForm></section>
  <article v-for="row in items" :key="row.verification_id" class="member-card">
    <h3>{{ row.verification_type }}认证 · {{ row.audit_status }}</h3>
    <p class="member-meta">{{ row.campus }} · {{ date(row.submitted_at) }}</p>
    <p>{{ row.audit_reason || "等待审核" }}</p>
  </article>
  <p v-if="!loading && !items.length && !error">暂无认证记录</p>
  <MemberPager :page="page" :total="total" :loading="loading" @turn="turn" />
</template>
