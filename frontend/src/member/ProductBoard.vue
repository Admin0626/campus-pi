<script setup>
import { computed, ref, reactive, onMounted } from "vue";
import { api } from "../api.js";
import { useOperation, usePaged } from "./useMember.js";
import MemberState from "./MemberState.vue";
import MemberPager from "./MemberPager.vue";
import MemberForm from "./MemberForm.vue";
import MemberFiles from "./MemberFiles.vue";
import FilePreview from "../admin/FilePreview.vue";
const props = defineProps({ user: Object });
const emit = defineEmits(["navigate"]);
const scope = ref("all"),
  keyword = ref(""),
  query = ref(""),
  detail = ref(null),
  editing = ref(false),
  ordering = ref(false),
  categories = ref([]);
const categoryFilter = ref('');
const { state, run, mutate } = useOperation();
const { items, page, total, loading, error, load, turn } = usePaged(() =>
  scope.value === "mine"
    ? "/me/products"
    : scope.value === "favorites"
      ? "/me/product-favorites"
      : "/products?" + new URLSearchParams({ keyword: query.value, ...(categoryFilter.value ? { category_id: categoryFilter.value } : {}) }),
);
const model = reactive({}),
  order = reactive({ quantity: 1, shipping_address: props.user.address || "" }),
  images = ref([]),
  editId = ref(null);
const fields = computed(() => [
  {
    key: "category_id",
    label: "分类",
    options: categories.value.map((c) => ({
      value: c.category_id,
      label: c.category_name,
    })),
  },
  { key: "product_name", label: "商品名称", max: 255 },
  {
    key: "product_desc",
    label: "商品描述",
    type: "textarea",
    max: 5000,
    required: false,
  },
  { key: "campus", label: "认证校区", readonly: true },
  { key: "location_label", label: "自提位置", max: 100, required: false },
  {
    key: "trade_mode",
    label: "交易方式",
    options: ["校园自提"],
    hint: "线下交易，校园自提；平台不代收款。",
  },
  {
    key: "original_price",
    label: "原价（元）",
    hint: "固定两位小数，如 20.00",
  },
  { key: "discount_price", label: "售价（元）" },
  {
    key: "stock_quantity",
    label: "库存",
    type: "number",
    min: 0,
    maxValue: 100000,
    step: 1,
  },
]);
onMounted(() =>
  run(async () => {
    const c = await api("/categories");
    categories.value = c.items || c;
  }),
);
function start(row) {
  editId.value = row?.product_id || null;
  Object.keys(model).forEach((k) => delete model[k]);
  Object.assign(model, {
    category_id: row?.category_id || categories.value[0]?.category_id,
    product_name: row?.product_name || "",
    product_desc: row?.product_desc || "",
    campus: props.user.campus || "",
    location_label: row?.location_label || "",
    trade_mode: "校园自提",
    original_price: row?.original_price || "0.00",
    discount_price: row?.discount_price || "0.00",
    stock_quantity: row?.stock_quantity ?? 1,
  });
  images.value = [];
  editing.value = true;
  ordering.value = false;
}
async function open(row) {
  await run(async () => {
    detail.value = await api("/products/" + row.product_id);
    editing.value = false;
    ordering.value = false;
  });
}
async function save() {
  await run(async () => {
    if (!images.value.length)
      throw new Error("请上传商品图片；编辑时需重新选择完整图片集。");
    const value = {
      ...model,
      category_id: Number(model.category_id),
      stock_quantity: Number(model.stock_quantity),
      image_refs: images.value,
    };
    if (!value.location_label) delete value.location_label;
    detail.value = await mutate(
      "/products" + (editId.value ? "/" + editId.value : ""),
      value,
      editId.value ? "PUT" : "POST",
    );
    editing.value = false;
    await load();
  }, "商品已提交审核，通过后可公开浏览。");
}
async function favorite() {
  await run(async () => {
    const next = !detail.value.is_favorited;
    await api("/products/" + detail.value.product_id + "/favorite", {
      method: next ? "PUT" : "DELETE",
    });
    detail.value.is_favorited = next;
    await load();
  }, "收藏已更新");
}
async function shelf() {
  await run(async () => {
    detail.value = await api(
      "/products/" + detail.value.product_id + "/shelf",
      {
        method: "POST",
        body: {
          shelf_status:
            detail.value.shelf_status === "在售" ? "已下架" : "在售",
          reason: "用户主动调整商品上下架",
        },
      },
    );
    await load();
  });
}
async function buy() {
  await run(async () => {
    const created = await mutate("/orders/product", {
      product_id: detail.value.product_id,
      quantity: Number(order.quantity),
      shipping_address: order.shipping_address,
    });
    ordering.value = false;
    sessionStorage.setItem('campus-created-order', created.order_id);
    emit("navigate", "/modules/G");
  }, "订单已创建");
}
async function contact() {
  await run(async () => {
    const chat = await api("/chat-sessions", {
      method: "POST",
      body: { peer_user_id: detail.value.seller.user_id },
    });
    sessionStorage.setItem("campus-chat-session", chat.session_id);
    emit("navigate", "/modules/H");
  });
}
</script>
<template>
  <MemberState :state="state" :loading="loading" :error="error" />
  <div class="member-toolbar">
    <button
      v-for="[key, label] in [
        ['all', '在售商品'],
        ['mine', '我的发布'],
        ['favorites', '我的收藏'],
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
      发布商品
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
      placeholder="搜索商品名称"
      maxlength="100"
    /><button>搜索</button>
    <select v-model="categoryFilter" aria-label="商品分类筛选"><option value="">全部分类</option><option v-for="item in categories" :key="item.category_id" :value="item.category_id">{{ item.category_name }}</option></select>
  </form>
  <MemberForm
    v-if="editing"
    :model="model"
    :fields="fields"
    :busy="state.busy"
    submit-label="提交审核"
    @submit="save"
    @cancel="editing = false"
    ><template #before><MemberFiles
      v-model="images"
      purpose="PRODUCT_IMAGE"
      :disabled="state.busy"
  /></template></MemberForm>
  <article v-else-if="detail" class="member-card member-detail">
    <button
      @click="
        detail = null;
        ordering = false;
      "
    >
      返回列表
    </button>
    <h2>{{ detail.product_name }}</h2>
    <p class="member-seller"><span class="member-avatar">{{ detail.seller.username?.slice(0,1) }}</span>{{ detail.seller.username }} <small>{{ detail.campus }}</small></p>
    <FilePreview :files="detail.image_urls" mode="gallery" />
    <p class="member-price">¥{{ detail.discount_price }}</p>
    <p>{{ detail.product_desc }}</p>
    <p class="member-meta">
      {{ detail.campus }} · {{ detail.location_label }} · {{ detail.trade_mode
      }}<br />库存 {{ detail.stock_quantity }} · {{ detail.audit_status }} ·
      {{ detail.shelf_status }}
    </p>
    <div class="member-actions">
      <template v-if="detail.seller.user_id === user.user_id"
        ><button :disabled="state.busy" @click="start(detail)">
          编辑并重新审核</button
        ><button :disabled="state.busy" @click="shelf">
          {{ detail.shelf_status === "在售" ? "下架" : "上架" }}
        </button></template
      ><template v-else
        ><button :disabled="state.busy" @click="favorite">
          {{ detail.is_favorited ? "取消收藏" : "收藏" }}</button
        ><button :disabled="state.busy" @click="contact">联系卖家</button
        ><button
          class="member-primary"
          :disabled="state.busy || detail.shelf_status !== '在售'"
          @click="ordering = true"
        >
          预约线下交易
        </button></template
      >
    </div>
    <MemberForm
      v-if="ordering"
      :model="order"
      :fields="[
        {
          key: 'quantity',
          label: '数量',
          type: 'number',
          min: 1,
          maxValue: detail.stock_quantity,
          step: 1,
        },
        { key: 'shipping_address', label: '自提交接地点', max: 500 },
      ]"
      :busy="state.busy"
      submit-label="确认创建线下订单"
      @submit="buy"
      @cancel="ordering = false"
    ><template #before><p>平台将预留商品库存。请与卖家约定自提时间，当面验货后自行线下结算。</p></template></MemberForm>
  </article>
  <template v-else
    ><p v-if="!loading && !items.length && !error">暂无商品</p>
    <div class="member-product-grid">
    <article v-for="row in items" :key="row.product_id" class="member-card member-product-card">
      <FilePreview :files="(row.image_urls || []).slice(0,1)" mode="thumbnail" />
      <h3>{{ row.product_name }}</h3>
      <p class="member-price">¥{{ row.discount_price }}</p>
      <p class="member-meta">
        {{ row.campus }} · {{ row.audit_status }} · {{ row.shelf_status }}
      </p>
      <button :disabled="state.busy" @click="open(row)">查看商品</button>
    </article>
    </div>
    <MemberPager :page="page" :total="total" :loading="loading" @turn="turn"
  /></template>
</template>
