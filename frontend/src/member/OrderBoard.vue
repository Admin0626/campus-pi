<script setup>
import { ref, onMounted } from "vue";
import { api } from "../api.js";
import { useOperation, usePaged, date } from "./useMember.js";
import MemberState from "./MemberState.vue";
import MemberPager from "./MemberPager.vue";
import AftersaleBoard from "./AftersaleBoard.vue";
const props = defineProps({ user: Object });
const emit = defineEmits(["navigate"]);
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
  if (!window.confirm("确认取消此尚未交付的线下订单并释放库存？")) return;
  await run(async () => {
    detail.value = await mutate("/orders/" + detail.value.order_id + "/cancel");
    await load();
  }, "订单已取消，库存已释放");
}
async function confirmOffline(receipt) {
  const text = receipt
    ? "请确认已当面验货、收货并与卖家完成线下结算。确认后订单完成，平台不会收款或退款。"
    : "请确认已将商品线下交付给买家。确认后不能直接取消订单，款项由双方自行结算。";
  if (!window.confirm(text)) return;
  await run(async () => {
    detail.value = await mutate("/orders/" + detail.value.order_id + (receipt ? "/receipt" : "/shipping"));
    await load();
  }, receipt ? "已确认线下收货，订单已完成" : "已确认线下交付，等待买家确认收货");
}
async function contact() {
  await run(async () => {
    const peer = detail.value.buyer_id === props.user.user_id ? detail.value.seller_id : detail.value.buyer_id;
    const chat = await api("/chat-sessions", { method: "POST", body: { peer_user_id: peer } });
    sessionStorage.setItem("campus-chat-session", chat.session_id);
    emit("navigate", "/modules/H");
  });
}
</script>
<template>
  <AftersaleBoard
    v-if="aftersales"
    :order-id="disputeOrder"
    @back="aftersales = false"
  />
  <template v-else>
    <section v-if="createdOrder" class="member-created-order" role="status"><h2>线下订单已创建</h2><p>订单状态：{{ createdOrder.order_status }} · 约定金额 ¥{{ createdOrder.total_amount }}</p><p>请通过私信约定自提时间和地点，当面验货并自行线下结算，平台不代收款。</p><div class="member-actions"><button @click="detail = createdOrder; createdOrder = null">查看新订单</button><button @click="createdOrder = null">关闭提示</button></div></section>
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
            ['shipping_address', '交接地点'],
          ]"
          :key="key"
          ><dt>{{ label }}</dt>
          <dd>{{ detail[key] ?? "—" }}</dd></template
        >
      </dl>
      <p>{{ detail.trade_channel === 'OFFLINE' ? '交易渠道：线下交易（校园自提）' : detail.order_type === '任务' ? '结算方式：内部奖励账户' : '历史订单：仅保留原始交易记录' }}</p>
      <p v-if="detail.order_type === '任务'">已结算奖励：{{ detail.actual_amount }}</p>
      <p v-if="detail.shipping_time">卖家确认交付：{{ date(detail.shipping_time) }}</p>
      <p v-if="detail.completion_time">订单完成：{{ date(detail.completion_time) }}</p>
      <p class="member-meta">{{ date(detail.order_time) }}</p>
      <button v-if="detail.trade_channel === 'OFFLINE'" :disabled="state.busy" @click="contact">{{ detail.buyer_id === user.user_id ? '联系卖家' : '联系买家' }}</button>
      <button
        v-if="
          detail.order_type === '商品' &&
          detail.trade_channel === 'OFFLINE' &&
          detail.order_status === '待线下交易' &&
          detail.buyer_id === user.user_id
        "
        :disabled="state.busy"
        @click="cancel"
      >
        取消订单
      </button>
      <button v-if="detail.trade_channel === 'OFFLINE' && detail.order_status === '待线下交易' && detail.seller_id === user.user_id" class="member-primary" :disabled="state.busy" @click="confirmOffline(false)">确认已线下交付</button>
      <button v-if="detail.trade_channel === 'OFFLINE' && detail.order_status === '待确认收货' && detail.buyer_id === user.user_id" class="member-primary" :disabled="state.busy" @click="confirmOffline(true)">确认已线下收货</button>
      <p v-if="detail.trade_channel === 'OFFLINE'" class="member-meta">
        先联系卖家，当面验货并自行结算；卖家确认交付后，买家再确认收货。平台只记录交易进度，退换或款项问题请双方线下协商。
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
