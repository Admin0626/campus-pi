<script setup>
import { ref, reactive, computed, onMounted } from "vue";
import { api } from "../api.js";
import { useOperation, usePaged, date } from "./useMember.js";
import MemberState from "./MemberState.vue";
import MemberPager from "./MemberPager.vue";
import MemberForm from "./MemberForm.vue";
const props = defineProps({ user: Object });
const scope = ref("all"),
  keyword = ref(""),
  query = ref(""),
  detail = ref(null),
  editing = ref(false),
  editId = ref(null),
  categories = ref([]),
  model = reactive({});
const { state, run, mutate } = useOperation();
const { items, page, total, loading, error, load, turn } = usePaged(() =>
  scope.value === "mine"
    ? "/me/posts"
    : scope.value === "collections"
      ? "/me/post-collections"
      : "/posts?keyword=" + encodeURIComponent(query.value),
);
const fields = computed(() => [
  {
    key: "category_id",
    label: "分类",
    options: categories.value.map((c) => ({
      value: c.category_id,
      label: c.category_name,
    })),
  },
  { key: "post_title", label: "标题", max: 200 },
  {
    key: "post_content",
    label: "内容（纯文本）",
    type: "textarea",
    max: 10000,
  },
  { key: "is_anonymous", label: "匿名发布", type: "checkbox", required: false },
]);
onMounted(() =>
  run(async () => {
    const result = await api("/categories");
    categories.value = result.items || result;
  }),
);
function start(row) {
  editId.value = row?.post_id || null;
  Object.assign(model, {
    category_id: row?.category_id || categories.value[0]?.category_id,
    post_title: row?.post_title || "",
    post_content: row?.post_content || "",
    is_anonymous: row?.is_anonymous || false,
  });
  editing.value = true;
}
async function open(row) {
  await run(async () => {
    detail.value = await api("/posts/" + row.post_id);
    editing.value = false;
  });
}
async function save() {
  await run(async () => {
    detail.value = await mutate(
      "/posts" + (editId.value ? "/" + editId.value : ""),
      { ...model, category_id: Number(model.category_id) },
      editId.value ? "PUT" : "POST",
    );
    editing.value = false;
    await load();
  }, "帖子已进入审核队列");
}
async function collect() {
  await run(async () => {
    const next = !detail.value.is_collected;
    await api("/posts/" + detail.value.post_id + "/collection", {
      method: next ? "PUT" : "DELETE",
    });
    detail.value.is_collected = next;
    await load();
  }, "收藏已更新");
}
async function remove() {
  if (!window.confirm("确认删除此帖子？收藏入口会同时失效。")) return;
  await run(async () => {
    await api("/posts/" + detail.value.post_id, { method: "DELETE" });
    detail.value = null;
    await load();
  }, "帖子已删除");
}
</script>
<template>
  <MemberState :state="state" :loading="loading" :error="error" />
  <div class="member-toolbar">
    <button
      v-for="[key, label] in [
        ['all', '校园广场'],
        ['mine', '我的发布'],
        ['collections', '我的收藏'],
      ]"
      :key="key"
      :class="{ active: scope === key }"
      :disabled="state.busy"
      @click="
        scope = key;
        detail = null;
        editing = false;
      "
    >
      {{ label }}</button
    ><button class="member-primary" :disabled="state.busy" @click="start()">
      发布帖子
    </button>
  </div>
  <form
    v-if="scope === 'all' && !detail && !editing"
    class="member-toolbar"
    @submit.prevent="
      query = keyword;
      page = 1;
      load();
    "
  >
    <input
      v-model="keyword"
      placeholder="搜索校园信息"
      maxlength="100"
    /><button>搜索</button>
  </form>
  <MemberForm
    v-if="editing" class="member-editor-form"
    :model="model"
    :fields="fields"
    :busy="state.busy"
    submit-label="提交审核"
    @submit="save"
    @cancel="editing = false"
  />
  <article v-else-if="detail" class="member-card">
    <button @click="detail = null">返回列表</button>
    <h2>{{ detail.post_title }}</h2>
    <p class="member-meta">
      {{ detail.author_label }} · {{ date(detail.publish_time) }} ·
      {{ detail.post_status }}<br />阅读 {{ detail.view_count }} · 收藏
      {{ detail.collection_count }}
    </p>
    <p>{{ detail.post_content }}</p>
    <div class="member-actions">
      <template v-if="scope === 'mine'"
        ><button :disabled="state.busy" @click="start(detail)">
          编辑并重新审核</button
        ><button :disabled="state.busy" @click="remove">删除</button></template
      ><button v-else :disabled="state.busy" @click="collect">
        {{ detail.is_collected ? "取消收藏" : "收藏" }}
      </button>
    </div>
  </article>
  <template v-else
    ><p v-if="!loading && !items.length && !error">暂无帖子</p>
    <article v-for="row in items" :key="row.post_id" class="member-card">
      <header class="member-feed-author"><span class="member-avatar">{{ row.author_label?.slice(0,1) || 'Π' }}</span><span>{{ row.author_label }}<small>{{ date(row.publish_time) }}</small></span></header>
      <h3>{{ row.post_title }}</h3>
      <p class="member-meta">
        {{ row.author_label }} · {{ date(row.publish_time) }} ·
        {{ row.post_status }}
      </p>
      <p>{{ row.post_content.slice(0, 120) }}</p>
      <p class="member-meta">阅读 {{ row.view_count }} · 收藏 {{ row.collection_count }}</p>
      <button :disabled="state.busy" @click="open(row)">阅读全文</button>
    </article>
    <MemberPager :page="page" :total="total" :loading="loading" @turn="turn"
  /></template>
</template>
