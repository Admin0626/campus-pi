<script setup>
import AdminPageHeader from "./AdminPageHeader.vue";
import AdminPagination from "./AdminPagination.vue";
import { computed, ref, reactive, watch, onUnmounted } from "vue";
import { api, requestKey } from "../api";
import FilePreview from "./FilePreview.vue";
import AdminBatchActions from "./AdminBatchActions.vue";
import { useAdminSelection } from "./useAdminSelection";
const props = defineProps({ module: String, auditContext: Object });
const emit = defineEmits(["navigate"]);
const tab = ref("products"),
  rows = ref([]),
  total = ref("0"),
  page = ref(1),
  loading = ref(false),
  busy = ref(false),
  error = ref(""),
  notice = ref(""),
  detail = ref(null),
  action = ref(null),
  reason = ref(""),
  formOpen = ref(false),
  editing = ref(null),
  summary = ref(null);
const filter = reactive({
  state: "",
  shelf: "",
  keyword: "",
  campus: "",
  object_type: "",
  object_id: "",
});
const form = reactive({
  category_name: "",
  parent_category_id: "",
  category_description: "",
});
const view = computed(() =>
  props.module === "content" ? tab.value : props.module,
);
const title = computed(
  () =>
    ({
      content: "内容审核",
      products: "商品信息管理",
      categories: "分类管理",
      audit: "操作日志",
      dashboard: "控制台",
    })[props.module] || "管理后台",
);
const states = computed(() =>
  view.value === "products"
    ? ["待审核", "已通过", "已驳回"]
    : ["待审核", "已发布", "已驳回", "已下架", "已删除"],
);
let controller,
  version = 0,
  mutationKey = "",
  mutationBody = "";
const time = (v) =>
  v
    ? new Date(v).toLocaleString("zh-CN", {
        timeZone: "Asia/Shanghai",
        hour12: false,
      })
    : "—";
const id = (row) =>
  row.product_id || row.post_id || row.category_id || row.audit_id;
const status = (row) =>
  row.audit_status || row.post_status || row.category_level || "";
const batchView = computed(() => ["products", "posts"].includes(view.value));
const { selected, available, allSelected, toggle, toggleAll } = useAdminSelection(rows, id, row => status(row) === "待审核", loading);
const stateClass = (value) =>
  ["已通过", "已发布", "在售"].includes(value)
    ? "good"
    : ["已驳回", "已下架", "已删除"].includes(value)
      ? "bad"
      : "neutral";
async function load() {
  const serial = ++version;
  controller?.abort();
  controller = new AbortController();
  loading.value = true;
  error.value = "";
  try {
    let url;
    if (view.value === "dashboard") url = "/admin/dashboard";
    else if (view.value === "categories") url = "/categories";
    else {
      const q = new URLSearchParams({
        page: String(page.value),
        page_size: "20",
      });
      if (view.value === "products") {
        if (filter.state) q.set("audit_status", filter.state);
        if (filter.shelf) q.set("shelf_status", filter.shelf);
        if (filter.campus) q.set("campus", filter.campus);
      } else if (view.value === "posts") {
        if (filter.state) q.set("post_status", filter.state);
        if (filter.keyword) q.set("keyword", filter.keyword);
      } else {
        if (filter.object_type) q.set("object_type", filter.object_type);
        if (filter.object_id) q.set("object_id", filter.object_id);
      }
      url = `/admin/${view.value === "audit" ? "audit-logs" : view.value}?${q}`;
    }
    const data = await api(url, { signal: controller.signal });
    if (serial !== version) return;
    if (view.value === "dashboard") summary.value = data;
    else {
      rows.value = data.items;
      total.value = data.total || String(data.items.length);
    }
  } catch (e) {
    if (serial === version) error.value = e.message;
  } finally {
    if (serial === version) loading.value = false;
  }
}
watch(
  () => props.module,
  () => {
    tab.value = "products";
  },
);
watch(
  view,
  () => {
    page.value = 1;
    // A new view has a different row schema. Never render old products as categories.
    rows.value = [];
    total.value = "0";
    summary.value = null;
    detail.value = null;
    action.value = null;
    formOpen.value = false;
    notice.value = "";
    Object.keys(filter).forEach((k) => (filter[k] = ""));
    if (view.value === "audit" && props.auditContext) {
      filter.object_type = props.auditContext.object_type;
      filter.object_id = props.auditContext.object_id;
    }
    load();
  },
  { immediate: true },
);
onUnmounted(() => {
  version++;
  controller?.abort();
});
function search() {
  page.value = 1;
  load();
}
function reset() {
  Object.keys(filter).forEach((k) => (filter[k] = ""));
  search();
}
function turn(delta) {
  page.value += delta;
  load();
}
function openCategory(row = null) {
  editing.value = row;
  Object.assign(form, {
    category_name: row?.category_name || "",
    parent_category_id: row?.parent_category_id || "",
    category_description: row?.category_description || "",
  });
  error.value = "";
  formOpen.value = true;
  mutationKey = requestKey();
  mutationBody = "";
}
async function saveCategory() {
  if (busy.value) return;
  if (!form.category_name.trim()) {
    error.value = "请填写分类名称";
    return;
  }
  const body = {
    category_name: form.category_name.trim(),
    parent_category_id: form.parent_category_id
      ? Number(form.parent_category_id)
      : null,
    category_description: form.category_description,
  };
  const serialized = JSON.stringify(body);
  if (mutationBody && mutationBody !== serialized) mutationKey = requestKey();
  mutationBody = serialized;
  busy.value = true;
  error.value = "";
  try {
    await api(
      "/admin/categories" +
        (editing.value ? `/${editing.value.category_id}` : ""),
      {
        method: editing.value ? "PUT" : "POST",
        body,
        key: editing.value ? undefined : mutationKey,
      },
    );
    formOpen.value = false;
    notice.value = "分类已保存";
    await load();
  } catch (e) {
    error.value = e.message;
  } finally {
    busy.value = false;
  }
}
function confirm(row, operation, label) {
  action.value = { row, operation, label };
  reason.value = "";
  error.value = "";
  mutationKey = requestKey();
  mutationBody = "";
}
async function submitAction() {
  if (busy.value) return;
  if (!reason.value.trim()) {
    error.value = "请填写操作原因";
    return;
  }
  const { row, operation } = action.value;
  let url,
    method = "POST",
    body;
  if (view.value === "categories") {
    url = `/admin/categories/${row.category_id}`;
    method = "DELETE";
  } else if (view.value === "products") {
    const review = operation === "通过" || operation === "驳回";
    url = `/admin/products/${row.product_id}/${review ? "review" : "shelf"}`;
    body = review
      ? { decision: operation, reason: reason.value.trim() }
      : { shelf_status: operation, reason: reason.value.trim() };
  } else {
    const review = operation !== "下架";
    url = `/admin/posts/${row.post_id}/${review ? "review" : "withdraw"}`;
    body = review
      ? { decision: operation, reason: reason.value.trim() }
      : { reason: reason.value.trim() };
  }
  const serialized = JSON.stringify(body || { reason: reason.value.trim() });
  if (mutationBody && mutationBody !== serialized) mutationKey = requestKey();
  mutationBody = serialized;
  busy.value = true;
  error.value = "";
  try {
    await api(url, {
      method,
      body,
      key: method === "DELETE" ? undefined : mutationKey,
      reason: method === "DELETE" ? reason.value.trim() : undefined,
    });
    action.value = null;
    notice.value = "操作已保存";
    await load();
  } catch (e) {
    error.value = e.message;
  } finally {
    busy.value = false;
  }
}
const detailFields = computed(() => {
  const r = detail.value;
  if (!r) return [];
  if (view.value === "products")
    return [
      ["商品编号", r.product_id],
      ["商品名称", r.product_name],
      ["描述", r.product_desc || "—"],
      ["卖方", r.seller?.username],
      ["校区", r.campus],
      ["交易方式", r.trade_mode],
      ["原价", r.original_price],
      ["现价", r.discount_price],
      ["库存", r.stock_quantity],
      ["审核状态", r.audit_status],
      ["上下架", r.shelf_status],
    ];
  return [
    ["帖子编号", r.post_id],
    ["标题", r.post_title],
    ["正文", r.post_content],
    ["作者", r.author_label],
    ["状态", r.post_status],
    ["浏览数", r.view_count],
    ["有效收藏", r.collection_count],
    ["发布时间", time(r.publish_time)],
  ];
});
const dashboardCards = computed(() =>
  summary.value
    ? [
        ["全部用户", summary.value.user_count],
        ["已发布帖子", summary.value.published_post_count],
        ["在售商品", summary.value.onsale_product_count],
        ["已完成任务", summary.value.completed_task_count],
        ["已完成商品成交额", "¥ " + summary.value.completed_product_amount],
      ]
    : [],
);
</script>
<template>
  <section class="admin-content-panel" :aria-busy="loading">
    <AdminPageHeader
      :title="title"
      :subtitle="view === 'dashboard' ? undefined : `共 ${total} 条记录`"
      ><template #actions
        ><button
          v-if="view === 'dashboard'"
          @click="emit('navigate', '/admin/modules/reports')"
        >
          举报处理
        </button>
        <button
          v-if="view === 'dashboard'"
          @click="emit('navigate', '/admin/modules/verifications')"
        >
          认证审核
        </button>
        <button
          v-if="view === 'categories'"
          class="admin-purple"
          @click="openCategory()"
        >
          ＋ 新增分类</button
        ><button :disabled="loading || busy" @click="load">
          刷新
        </button></template
      ></AdminPageHeader
    >
    <div
      v-if="
        props.module === 'content' ||
        props.module === 'products' ||
        props.module === 'categories'
      "
      class="admin-tabs"
    >
      <template v-if="props.module === 'content'"
        ><button
          :class="{ active: tab === 'products' }"
          @click="tab = 'products'"
        >
          商品信息审核</button
        ><button @click="emit('navigate', '/admin/modules/tasks')">
          悬赏任务审核</button
        ><button :class="{ active: tab === 'posts' }" @click="tab = 'posts'">
          帖子信息审核
        </button></template
      >
      <template v-else
        ><button
          :class="{ active: view === 'products' }"
          @click="emit('navigate', '/admin/modules/products')"
        >
          商品信息管理</button
        ><button
          :class="{ active: view === 'categories' }"
          @click="emit('navigate', '/admin/modules/categories')"
        >
          分类管理
        </button></template
      >
    </div>
    <p v-if="notice" role="status" class="admin-message success">
      {{ notice }}
    </p>
    <p v-if="error" role="alert" class="admin-message failure">
      {{ error }}
      <button :disabled="busy || loading" @click="load">重新加载</button>
    </p>
    <template v-if="view === 'dashboard'">
      <div class="admin-real-metrics">
        <article v-for="[label, value] in dashboardCards" :key="label">
          <span>{{ label }}</span
          ><strong>{{ value }}</strong>
        </article>
      </div>
      <p v-if="summary" class="admin-source-note">
        数据来自当前数据库同一查询快照 ·
        {{ time(summary.as_of) }}。成交额仅含已完成商品订单，不含任务奖励。
      </p>
      <div class="admin-dashboard-links">
        <article>
          <h3>业务管理</h3>
          <button @click="emit('navigate', '/admin/modules/content')">
            内容审核 →</button
          ><button @click="emit('navigate', '/admin/users')">
            进入用户管理</button
          ><button @click="emit('navigate', '/admin/modules/categories')">
            分类管理 →</button
          ><button @click="emit('navigate', '/admin/modules/audit')">
            操作日志 →
          </button>
        </article>
        <article>
          <h3>数据分析</h3>
          <p>
            现有图表使用 JSON
            模拟数据。活跃度与趋势尚未接入真实统计，不能作为经营数据。
          </p>
          <button @click="emit('navigate', '/admin/modules/analytics')">
            查看模拟可视化 →
          </button>
        </article>
      </div>
    </template>
    <template v-else-if="view === 'categories'">
      <div class="admin-category-tree">
        <div
          class="admin-category-group"
          v-for="root in rows.filter((r) => !r.parent_category_id)"
          :key="root.category_id"
        >
          <div class="admin-category-root" :data-category-id="root.category_id">
            <strong>⌄ {{ root.category_name }}</strong
            ><span>{{ root.category_description }}</span
            ><button @click="openCategory(root)">编辑</button
            ><button class="danger" @click="confirm(root, '删除', '删除分类')">
              删除
            </button>
          </div>
          <div
            v-for="child in rows.filter(
              (r) => r.parent_category_id === root.category_id,
            )"
            :key="child.category_id"
            class="admin-category-child"
          >
            <strong>└ {{ child.category_name }}</strong
            ><span>{{ child.category_description }}</span
            ><button @click="openCategory(child)">编辑</button
            ><button class="danger" @click="confirm(child, '删除', '删除分类')">
              删除
            </button>
          </div>
        </div>
        <p v-if="!loading && !rows.length" class="admin-empty">
          暂无分类，可新增一级或二级分类。
        </p>
      </div>
    </template>
    <template v-else>
      <form class="admin-filters" @submit.prevent="search">
        <template v-if="view === 'products' || view === 'posts'"
          ><label
            >状态<select v-model="filter.state">
              <option value="">全部状态</option>
              <option v-for="s in states" :key="s">{{ s }}</option>
            </select></label
          ><template v-if="view === 'products'"
            ><label
              >上下架<select v-model="filter.shelf">
                <option value="">全部</option>
                <option>在售</option>
                <option>已下架</option>
                <option>已售出</option>
              </select></label
            ><label
              >校区<input
                v-model="filter.campus"
                maxlength="50"
                placeholder="按校区筛选" /></label></template
          ><label v-else
            >标题<input
              v-model="filter.keyword"
              maxlength="100"
              placeholder="搜索帖子标题" /></label
        ></template>
        <template v-else
          ><label
            >对象类型<input
              v-model="filter.object_type"
              maxlength="30"
              placeholder="如商品、帖子、分类" /></label
          ><label
            >对象编号<input v-model="filter.object_id" maxlength="50" /></label
        ></template>
        <button class="admin-purple" :disabled="loading">筛选</button
        ><button type="button" :disabled="loading" @click="reset">重置</button>
      </form>
      <AdminBatchActions v-if="batchView" :key="view" :kind="view" :ids="selected" :disabled="loading || busy" @completed="load" />
      <div class="admin-table-scroll" data-admin-records>
        <table>
          <thead>
            <tr>
              <th v-if="batchView"><input type="checkbox" aria-label="全选当前页待审核记录" :checked="allSelected" :indeterminate="selected.length > 0 && !allSelected" :disabled="loading || !available.length" @change="toggleAll" /></th>
              <th>编号</th>
              <template v-if="view === 'audit'"
                ><th>对象</th>
                <th>操作者</th>
                <th>动作</th>
                <th>原因</th>
                <th>时间</th></template
              ><template v-else
                ><th>标题 / 名称</th>
                <th>发布者</th>
                <th>状态</th>
                <th v-if="view === 'products'">价格 / 库存</th>
                <th>发布时间</th>
                <th>操作</th></template
              >
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in rows" :key="id(row)" :data-row-id="id(row)">
              <td v-if="batchView"><input type="checkbox" :aria-label="'选择记录' + id(row)" :checked="selected.includes(String(id(row)))" :disabled="loading || status(row) !== '待审核'" @change="toggle(row)" /></td>
              <td>{{ id(row) }}</td>
              <template v-if="view === 'audit'"
                ><td>{{ row.object_type }} / {{ row.object_id }}</td>
                <td>{{ row.operator_id }}</td>
                <td>{{ row.action_name }}</td>
                <td>{{ row.reason }}</td>
                <td>{{ time(row.created_at) }}</td></template
              ><template v-else
                ><td>{{ row.product_name || row.post_title }}</td>
                <td>{{ row.author_label || row.seller?.username }}</td>
                <td>
                  <span class="admin-state" :class="stateClass(status(row))">{{
                    status(row)
                  }}</span
                  ><small v-if="row.shelf_status">{{ row.shelf_status }}</small>
                </td>
                <td v-if="view === 'products'">
                  ¥{{ row.discount_price }} / {{ row.stock_quantity }}
                </td>
                <td>{{ time(row.create_time || row.publish_time) }}</td>
                <td class="admin-row-actions">
                  <button @click="detail = row">查看</button
                  ><template v-if="status(row) === '待审核'"
                    ><button
                      class="positive"
                      @click="confirm(row, '通过', '审核通过')"
                    >
                      通过</button
                    ><button
                      class="danger"
                      @click="confirm(row, '驳回', '审核驳回')"
                    >
                      驳回
                    </button></template
                  ><template v-if="view === 'products'"
                    ><button
                      v-if="row.shelf_status === '在售'"
                      class="danger"
                      @click="confirm(row, '已下架', '下架商品')"
                    >
                      下架</button
                    ><button
                      v-else-if="
                        row.audit_status === '已通过' && row.stock_quantity > 0
                      "
                      class="positive"
                      @click="confirm(row, '在售', '上架商品')"
                    >
                      上架
                    </button></template
                  ><button
                    v-else-if="row.post_status === '已发布'"
                    class="danger"
                    @click="confirm(row, '下架', '下架帖子')"
                  >
                    下架
                  </button>
                </td></template
              >
            </tr>
            <tr v-if="!rows.length">
              <td :colspan="batchView ? (view === 'products' ? 8 : 7) : 6" class="admin-empty">
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
    </template>
    <div v-if="detail || action || formOpen" class="admin-modal-backdrop">
      <section
        class="admin-dialog"
        role="dialog"
        aria-modal="true"
        :aria-label="formOpen ? '分类编辑' : action ? action.label : '内容详情'"
      >
        <header>
          <h3>
            {{ formOpen ? "分类编辑" : action ? action.label : "内容详情" }}
          </h3>
          <button
            :disabled="busy"
            aria-label="关闭对话框"
            @click="
              detail = null;
              action = null;
              formOpen = false;
            "
          >
            ×
          </button>
        </header>
        <template v-if="detail"
          ><dl>
            <template v-for="[label, value] in detailFields" :key="label"
              ><dt>{{ label }}</dt>
              <dd>{{ value }}</dd></template
            >
          </dl>
          <FilePreview v-if="view === 'products'" :files="detail.image_urls"
        /></template>
        <form v-else-if="formOpen" @submit.prevent="saveCategory">
          <label
            >分类名称<input
              v-model="form.category_name"
              required
              maxlength="50"
              :disabled="busy" /></label
          ><label
            >父分类<select
              v-model="form.parent_category_id"
              :disabled="!!editing || busy"
            >
              <option value="">无（一级分类）</option>
              <option
                v-for="r in rows.filter((r) => !r.parent_category_id)"
                :key="r.category_id"
                :value="r.category_id"
              >
                {{ r.category_name }}
              </option>
            </select></label
          ><label
            >分类描述<textarea
              v-model="form.category_description"
              maxlength="500"
              :disabled="busy"
            ></textarea>
          </label>
          <p v-if="error" role="alert" class="admin-message failure">
            {{ error }}
          </p>
          <button class="admin-purple" :disabled="busy">
            {{ busy ? "保存中…" : "保存分类" }}
          </button>
        </form>
        <form v-else @submit.prevent="submitAction">
          <p>
            即将对
            {{
              action.row.product_name ||
              action.row.post_title ||
              action.row.category_name
            }}
            执行“{{ action.label }}”。
          </p>
          <label
            >操作原因<textarea
              v-model="reason"
              required
              maxlength="500"
              :disabled="busy"
              placeholder="请填写可供审计的具体原因"
            ></textarea>
          </label>
          <p v-if="error" role="alert" class="admin-message failure">
            {{ error }}
          </p>
          <button class="admin-purple" :disabled="busy">
            {{ busy ? "提交中…" : "确认操作" }}
          </button>
        </form>
      </section>
    </div>
  </section>
</template>
