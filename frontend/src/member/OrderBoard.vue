<script setup>
import { ref, onMounted } from "vue";
import { api } from "../api.js";
import { useOperation, usePaged, date } from "./useMember.js";
import MemberState from "./MemberState.vue";
import MemberPager from "./MemberPager.vue";
import AftersaleBoard from "./AftersaleBoard.vue";
defineProps({ user: Object });
const side = ref("all"),
  type = ref(""),
  detail = ref(null);
const aftersales = ref(false),
  disputeOrder = ref(null);
const createdOrder = ref(null);
const { state, run, mutate } = useOperation();
const { items, page, total, loading, error, load, turn } = usePaged(
  () =>
    "/me/orders?" +
    new URLSearchParams({ side: side.value, order_type: type.value }),
);
onMounted(() => {
  const id = sessionStorage.getItem('campus-created-order');
  sessionStorage.removeItem('campus-created-order');
  if (id) run(async () => { createdOrder.value = await api('/orders/' + encodeURIComponent(id)); });
});
async function open(row) {
  await run(async () => {
    detail.value = await api("/orders/" + row.order_id);
  });
}
async function cancel() {
  if (!window.confirm("确认取消此待支付订单并释放库存？")) return;
  await run(async () => {
    detail.value = await mutate("/orders/" + detail.value.order_id + "/cancel");
    await load();
  }, "订单已取消，库存已释放");
}
</script>
<template>
  <AftersaleBoard
    v-if="aftersales"
    :order-id="disputeOrder"
    @back="aftersales = false"
  />
  <template v-else>
    <section v-if="createdOrder" class="member-created-order" role="status"><h2>订单已创建</h2><p>订单状态：{{ createdOrder.order_status }} · 应付 ¥{{ createdOrder.total_amount }}</p><p>订单创建不代表已支付，真实支付通道尚未接入。</p><div class="member-actions"><button @click="detail = createdOrder; createdOrder = null">查看新订单</button><button @click="createdOrder = null">关闭提示</button></div></section>
    <button
      @click="
        disputeOrder = null;
        aftersales = true;
      "
    >
      售后与争议记录
    </button>
    <MemberState :state="state" :loading="loading" :error="error" />
    <div class="member-toolbar">
      <button
        v-for="[value, label] in [
          ['all', '全部'],
          ['buyer', '买入 / 发布任务'],
          ['seller', '卖出 / 接取任务'],
        ]"
        :key="value"
        :class="{ active: side === value }"
        @click="
          side = value;
          detail = null;
        "
      >
        {{ label }}</button
      ><select v-model="type" aria-label="订单类型" @change="detail = null">
        <option value="">所有类型</option>
        <option>商品</option>
        <option>任务</option>
      </select>
    </div>
    <article v-if="detail" class="member-card member-detail">
      <button @click="detail = null">返回列表</button>
      <h2>{{ detail.item_name }}</h2>
      <dl>
        <template
          v-for="[key, label] in [
            ['order_id', '订单编号'],
            ['order_type', '订单类型'],
            ['order_status', '订单状态'],
            ['quantity', '数量'],
            ['unit_price', '单价'],
            ['total_amount', '总额'],
            ['actual_amount', '实付金额'],
            ['shipping_address', '交接地点'],
          ]"
          :key="key"
          ><dt>{{ label }}</dt>
          <dd>{{ detail[key] ?? "—" }}</dd></template
        >
      </dl>
      <p class="member-meta">{{ date(detail.order_time) }}</p>
      <button
        v-if="
          detail.order_type === '商品' &&
          detail.order_status === '待支付' &&
          detail.buyer_id === user.user_id
        "
        :disabled="state.busy"
        @click="cancel"
      >
        取消订单
      </button>
      <p v-if="detail.order_type === '商品'" class="member-meta">
        真实支付、发货、收货及商品退款通道待接入。当前订单只支持创建和取消，不会模拟支付成功。
      </p>
      <button
        v-if="detail.order_type === '任务' && detail.buyer_id === user.user_id"
        :disabled="state.busy"
        @click="
          disputeOrder = detail.order_id;
          aftersales = true;
        "
      >
        申请任务争议
      </button>
    </article>
    <template v-else
      ><p v-if="!loading && !items.length && !error">暂无订单</p>
      <article v-for="row in items" :key="row.order_id" class="member-card">
        <header class="member-order-heading"><span>{{ row.order_type }}订单</span><span class="member-status-chip">{{ row.order_status }}</span></header>
        <span class="member-order-thumbnail" aria-hidden="true">{{ row.order_type === '商品' ? '▧' : 'Π' }}</span>
        <h3>{{ row.item_name }}</h3>
        <p>
          {{ row.order_type }} · {{ row.order_status }} · ¥{{
            row.total_amount
          }}
        </p>
        <p class="member-meta">{{ date(row.order_time) }}</p>
        <button :disabled="state.busy" @click="open(row)">订单详情</button>
      </article>
      <MemberPager :page="page" :total="total" :loading="loading" @turn="turn"
    /></template>
  </template>
</template>
