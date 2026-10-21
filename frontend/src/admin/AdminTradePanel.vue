<script setup>
import { computed, ref, watch, onUnmounted } from "vue";
import { api, requestKey } from "../api";
import FilePreview from "./FilePreview.vue";
const props = defineProps({ module: String });
const tab = ref("orders"),
  state = ref(""),
  type = ref(""),
  orderId = ref(""),
  page = ref(1),
  rows = ref([]),
  total = ref("0");
const loading = ref(false),
  busy = ref(false),
  error = ref(""),
  notice = ref(""),
  detail = ref(null),
  action = ref(null),
  reason = ref(""),
  relatedTask = ref(null),
  aftersaleOrder = ref(null);
const taskDispute = computed(
  () =>
    detail.value?.kind === "aftersales" &&
    aftersaleOrder.value?.order_type === "任务" &&
    detail.value.value.apply_amount === "0.00",
);
const view = computed(() =>
  props.module === "reports" ? "reports" : tab.value,
);
const title = computed(() =>
  props.module === "reports" ? "举报处理" : "订单管理",
);
const states = computed(
  () =>
    ({
      orders: [
        "待支付",
        "已支付",
        "已发货",
        "待确认收货",
        "已完成",
        "已取消",
        "售后中",
        "已退款",
        "进行中",
        "待验收",
        "未完成",
      ],
      aftersales: ["待审核", "已驳回", "退款中", "已完成", "已取消"],
      reports: ["待审核", "成立", "不成立"],
    })[view.value],
);
const next = computed(() => BigInt(page.value) * 20n < BigInt(total.value));
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
function id(row) {
  return row.aftersale_id || row.report_id || row.order_id;
}
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
    if (state.value)
      query.set(
        {
          orders: "order_status",
          aftersales: "aftersale_status",
          reports: "audit_status",
        }[view.value],
        state.value,
      );
    if (view.value === "orders") {
      if (type.value) query.set("order_type", type.value);
      if (orderId.value.trim()) query.set("order_id", orderId.value.trim());
    }
    const value = await api(
      `/admin/${view.value === "reports" ? "message-reports" : view.value}?${query}`,
      { signal: controller.signal },
    );
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
    type.value = "";
    orderId.value = "";
    detail.value = null;
    action.value = null;
    relatedTask.value = null;
    aftersaleOrder.value = null;
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
  type.value = "";
  orderId.value = "";
  search();
}
function turn(delta) {
  page.value += delta;
  load();
}
function orderState(value) {
  const wasOrders = tab.value === "orders";
  tab.value = "orders";
  if (wasOrders) {
    state.value = value;
    search();
  } else if (value) {
    queueMicrotask(() => {
      state.value = value;
      search();
    });
  }
}
async function show(row) {
  if (view.value === "aftersales") {
    busy.value = true;
    aftersaleOrder.value = null;
    error.value = "";
    const serial = generation;
    try {
      const orders = await api(
        `/admin/orders?order_id=${encodeURIComponent(row.order_id)}&page_size=1`,
      );
      if (serial === generation) {
        aftersaleOrder.value = orders.items[0] || null;
        detail.value = { kind: "aftersales", value: row };
      }
    } catch (e) {
      if (serial === generation) error.value = e.message;
    } finally {
      busy.value = false;
    }
    return;
  }
  busy.value = true;
  error.value = "";
  const serial = generation,
    kind = view.value;
  try {
    const data = await api(
      kind === "reports"
        ? `/admin/message-reports/${row.report_id}`
        : `/admin/orders/${row.order_id}`,
    );
    if (serial === generation) {
      relatedTask.value = null;
      detail.value = { kind, value: data };
    }
  } catch (e) {
    if (serial === generation) error.value = e.message;
  } finally {
    busy.value = false;
  }
}
async function showOrder(row) {
  busy.value = true;
  error.value = "";
  const serial = generation;
  try {
    const data = await api(`/admin/orders/${row.order_id}`);
    if (serial === generation) {
      relatedTask.value = null;
      detail.value = { kind: "orders", value: data };
    }
  } catch (e) {
    error.value = e.message;
  } finally {
    busy.value = false;
  }
}
async function showTask() {
  busy.value = true;
  error.value = "";
  const serial = generation;
  try {
    const data = await api(`/tasks/${detail.value.value.reward_task_id}`);
    if (serial === generation) relatedTask.value = data;
  } catch (e) {
    error.value = e.message;
  } finally {
    busy.value = false;
  }
}
function prepare(row, decision) {
  action.value = { row, decision, kind: view.value };
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
  const { row, decision, kind } = action.value,
    body =
      kind === "aftersales"
        ? {
            decision,
            reason: reason.value.trim(),
            ...(decision === "通过" ? { approve_amount: "0.00" } : {}),
          }
        : kind === "reports"
          ? { decision, reason: reason.value.trim() }
          : { reason: reason.value.trim() };
  const serialized = JSON.stringify(body);
  if (mutationBody && mutationBody !== serialized) mutationKey = requestKey();
  mutationBody = serialized;
  busy.value = true;
  error.value = "";
  try {
    await api(
      kind === "aftersales"
        ? `/admin/aftersales/${row.aftersale_id}/review`
        : kind === "reports"
          ? `/admin/message-reports/${row.report_id}/review`
          : `/admin/orders/${row.order_id}/cancel`,
      { method: "POST", body, key: mutationKey },
    );
    action.value = null;
    detail.value = null;
    relatedTask.value = null;
    notice.value = "操作已保存";
    await load();
  } catch (e) {
    error.value = e.message;
  } finally {
    busy.value = false;
  }
}
const fields = computed(() => {
  if (!detail.value || detail.value.kind === "reports") return [];
  const r = detail.value.value;
  return detail.value.kind === "orders"
    ? [
        ["订单编号", r.order_id],
        ["类型", r.order_type],
        ["商品或任务快照", r.item_name],
        ["买方/发布者编号", r.buyer_id],
        ["卖方/接取者编号", r.seller_id],
        ["商品编号", r.product_id || "—"],
        ["任务编号", r.reward_task_id || "—"],
        ["数量", r.quantity],
        ["单价", r.unit_price],
        ["订单金额", r.total_amount],
        ["运费", r.shipping_fee],
        ["实付或已结算金额", r.actual_amount],
        ["状态", r.order_status],
        ["交付地址", r.shipping_address || "—"],
        ["创建时间", time(r.order_time)],
        ["支付时间", time(r.payment_time)],
        ["交付时间", time(r.shipping_time)],
        ["完成时间", time(r.completion_time)],
      ]
    : [
        ["售后编号", r.aftersale_id],
        ["关联订单", r.order_id],
        ["申请人编号", r.user_id],
        ["状态", r.aftersale_status],
        ["申请金额", r.apply_amount],
        ["批准金额", r.approve_amount || "—"],
        ["申请原因", r.apply_reason],
        ["审核原因", r.audit_reason || "—"],
        ["申请时间", time(r.apply_time)],
        ["审核时间", time(r.audit_time)],
        ["完成时间", time(r.complete_time)],
      ];
});
</script>
<template>
  <section class="admin-content-panel" :aria-busy="loading">
    <div class="admin-section-title">
      <div>
        <h2>{{ title }}</h2>
        <p>共 {{ total }} 条记录</p>
      </div>
      <button :disabled="loading || busy" @click="load">刷新</button>
    </div>
    <div v-if="props.module === 'orders'" class="admin-tabs trade-tabs">
      <button
        v-for="[value, label] in [
          ['', '全部订单'],
          ['待支付', '待支付'],
          ['已支付', '已支付'],
          ['已完成', '已完成'],
          ['已取消', '已取消'],
          ['售后中', '售后中'],
        ]"
        :key="value"
        :class="{ active: view === 'orders' && state === value }"
        @click="orderState(value)"
      >
        {{ label }}</button
      ><button
        :class="{ active: view === 'aftersales' }"
        @click="tab = 'aftersales'"
      >
        售后工单
      </button>
    </div>
    <form class="admin-filters" @submit.prevent="search">
      <label
        >状态<select v-model="state" aria-label="订单或举报状态">
          <option value="">全部</option>
          <option v-for="value in states" :key="value">{{ value }}</option>
        </select></label
      ><template v-if="view === 'orders'"
        ><label
          >类型<select v-model="type" aria-label="订单类型">
            <option value="">全部</option>
            <option>商品</option>
            <option>任务</option>
          </select></label
        ><label
          >订单编号<input
            v-model="orderId"
            pattern="ORDER[0-9]{14}"
            maxlength="19"
            placeholder="请输入完整订单编号" /></label></template
      ><button class="admin-purple" :disabled="loading || busy">查询</button
      ><button type="button" :disabled="loading || busy" @click="reset">
        重置
      </button>
    </form>
    <p v-if="view === 'orders'" class="admin-source-note">
      列表隐藏交付地址；查看详情需要订单处理权限。
    </p>
    <p v-else-if="view === 'aftersales'" class="admin-source-note">
      任务零金额争议可审核结案，不改变任务或奖励；商品退款暂未启用。
    </p>
    <p v-else class="admin-source-note">
      仅处理举报案件关联的消息；判定成立不会自动停用账号。
    </p>
    <p v-if="error" role="alert" class="admin-message failure">{{ error }}</p>
    <p v-if="notice" role="status" class="admin-message success">
      {{ notice }}
    </p>
    <div class="admin-table-scroll">
      <table>
        <thead>
          <tr v-if="view === 'orders'">
            <th>订单ID</th>
            <th>商品/任务名称</th>
            <th>类型</th>
            <th>买方/发布者</th>
            <th>卖方/接取者</th>
            <th>订单金额</th>
            <th>下单时间</th>
            <th>订单状态</th>
            <th>操作</th>
          </tr>
          <tr v-else-if="view === 'aftersales'">
            <th>售后ID</th>
            <th>订单ID</th>
            <th>申请人</th>
            <th>申请金额</th>
            <th>状态</th>
            <th>申请时间</th>
            <th>操作</th>
          </tr>
          <tr v-else>
            <th>举报ID</th>
            <th>消息ID</th>
            <th>举报人</th>
            <th>原因</th>
            <th>状态</th>
            <th>举报时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in rows" :key="id(row)">
            <template v-if="view === 'orders'"
              ><td>{{ row.order_id }}</td>
              <td>{{ row.item_name }}</td>
              <td>{{ row.order_type }}</td>
              <td>{{ row.buyer_id }}</td>
              <td>{{ row.seller_id }}</td>
              <td>{{ row.total_amount }}</td>
              <td>{{ time(row.order_time) }}</td>
              <td>{{ row.order_status }}</td>
              <td class="admin-row-actions">
                <button :disabled="busy || loading" @click="show(row)">
                  查看</button
                ><button
                  v-if="
                    row.order_type === '商品' && row.order_status === '待支付'
                  "
                  class="danger"
                  :disabled="busy || loading"
                  @click="prepare(row)"
                >
                  取消订单
                </button>
              </td></template
            >
            <template v-else-if="view === 'aftersales'"
              ><td>{{ row.aftersale_id }}</td>
              <td>{{ row.order_id }}</td>
              <td>{{ row.user_id }}</td>
              <td>{{ row.apply_amount }}</td>
              <td>{{ row.aftersale_status }}</td>
              <td>{{ time(row.apply_time) }}</td>
              <td class="admin-row-actions">
                <button :disabled="busy || loading" @click="show(row)">
                  查看工单
                </button>
              </td></template
            >
            <template v-else
              ><td>{{ row.report_id }}</td>
              <td>{{ row.msg_id }}</td>
              <td>{{ row.reporter_id }}</td>
              <td>{{ row.reason }}</td>
              <td>{{ row.audit_status }}</td>
              <td>{{ time(row.report_time) }}</td>
              <td class="admin-row-actions">
                <button :disabled="busy || loading" @click="show(row)">
                  查看证据
                </button>
              </td></template
            >
          </tr>
          <tr v-if="!rows.length">
            <td colspan="9" class="admin-empty">
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
    <footer class="admin-pagination">
      <span>第 {{ page }} 页 · 共 {{ total }} 条</span
      ><button :disabled="page === 1 || loading || busy" @click="turn(-1)">
        上一页</button
      ><button :disabled="!next || loading || busy" @click="turn(1)">
        下一页
      </button>
    </footer>
    <div v-if="detail || action" class="admin-modal-backdrop">
      <section
        class="admin-dialog"
        role="dialog"
        aria-modal="true"
        :aria-label="
          action
            ? '处理确认'
            : detail.kind === 'reports'
              ? '举报关联证据'
              : detail.kind === 'orders'
                ? '订单详情'
                : '售后工单详情'
        "
      >
        <header>
          <h3>
            {{
              action
                ? "处理确认"
                : detail.kind === "reports"
                  ? "举报关联证据"
                  : detail.kind === "orders"
                    ? "订单详情"
                    : "售后工单详情"
            }}
          </h3>
          <button
            aria-label="关闭对话框"
            :disabled="busy"
            @click="
              detail = null;
              action = null;
              relatedTask = null;
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
              action.kind === "aftersales"
                ? "将任务争议 " +
                  action.row.aftersale_id +
                  " 审核为" +
                  action.decision
                : action.kind === "reports"
                  ? "将举报 " +
                    action.row.report_id +
                    " 判定为" +
                    action.decision
                  : "取消订单 " + action.row.order_id
            }}
          </p>
          <p v-if="action.kind === 'orders'">取消后恢复该订单预留库存。</p>
          <p v-if="action.kind === 'aftersales'">
            争议金额为0.00，仅记录审核结论；任务状态、积分和现金均不改变。
          </p>
          <label
            >操作原因<textarea
              v-model="reason"
              required
              maxlength="500"
              :disabled="busy"
            ></textarea></label
          ><button class="admin-purple" :disabled="busy">
            {{ busy ? "保存中…" : "确认操作" }}
          </button>
        </form>
        <template v-else-if="detail.kind === 'reports'"
          ><dl>
            <dt>举报编号</dt>
            <dd>{{ detail.value.report.report_id }}</dd>
            <dt>举报原因</dt>
            <dd>{{ detail.value.report.reason }}</dd>
            <dt>处理状态</dt>
            <dd>{{ detail.value.report.audit_status }}</dd>
            <dt>处理原因</dt>
            <dd>{{ detail.value.report.audit_reason || "—" }}</dd>
            <dt>关联消息编号</dt>
            <dd>{{ detail.value.message.msg_id }}</dd>
            <dt>发送者编号</dt>
            <dd>{{ detail.value.message.sender_id }}</dd>
            <dt>接收者编号</dt>
            <dd>{{ detail.value.message.receiver_id }}</dd>
            <dt>发送时间</dt>
            <dd>{{ time(detail.value.message.send_time) }}</dd>
          </dl>
          <FilePreview
            v-if="detail.value.message.content_type === '图片'"
            :files="[detail.value.message.content]"
          />
          <div v-else class="trade-message-evidence">
            {{ detail.value.message.content }}
          </div>
          <div
            v-if="detail.value.report.audit_status === '待审核'"
            class="admin-inline-actions"
          >
            <button
              class="admin-purple"
              @click="prepare(detail.value.report, '成立')"
            >
              判定成立</button
            ><button @click="prepare(detail.value.report, '不成立')">
              判定不成立
            </button>
          </div></template
        >
        <template v-else
          ><dl>
            <template v-for="[label, value] in fields" :key="label"
              ><dt>{{ label }}</dt>
              <dd>{{ value }}</dd></template
            >
          </dl>
          <template v-if="detail.kind === 'aftersales'"
            ><FilePreview :files="detail.value.evidence_refs" />
            <p class="admin-source-note">
              {{
                taskDispute
                  ? "任务零金额争议，仅记录审核结论，不退款或变更任务。"
                  : "商品退款暂未启用；异常任务工单需核对原始记录。"
              }}
            </p>
            <div
              v-if="taskDispute && detail.value.aftersale_status === '待审核'"
              class="admin-inline-actions"
            >
              <button
                class="admin-purple"
                :disabled="busy"
                @click="prepare(detail.value, '通过')"
              >
                通过争议
              </button>
              <button :disabled="busy" @click="prepare(detail.value, '驳回')">
                驳回争议
              </button>
            </div>
            <button :disabled="busy" @click="showOrder(detail.value)">
              查看关联订单
            </button></template
          ><template v-else-if="detail.value.order_type === '任务'"
            ><button :disabled="busy" @click="showTask">查看关联任务</button>
            <dl v-if="relatedTask">
              <dt>任务名称</dt>
              <dd>{{ relatedTask.task_name }}</dd>
              <dt>任务状态</dt>
              <dd>{{ relatedTask.task_status }}</dd>
              <dt>约定奖励</dt>
              <dd>
                {{ relatedTask.reward_amount }} {{ relatedTask.reward_type }}
              </dd>
            </dl></template
          ></template
        >
      </section>
    </div>
  </section>
</template>
