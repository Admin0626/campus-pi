<script setup>
import { computed, ref, onMounted, onBeforeUnmount, nextTick } from "vue";
import Icon from "../Icon.vue";
import ReportGlyph from "./ReportGlyph.vue";
import TrendChart from "./TrendChart.vue";
import CategoryChart from "./CategoryChart.vue";
import BarChart from "./BarChart.vue";
defineProps({ dashboard: Boolean });
import {
  loadAnalytics,
  periodView,
  trendView,
  monthlyTrades,
  changePercent,
  countFormat,
  moneyFormat,
  sumTrade,
  sumContent,
  analyticsCsv,
  PERIOD_KEYS,
} from "./data.js";
const data = ref(null),
  loading = ref(false),
  error = ref(""),
  period = ref("week"),
  range = ref("week"),
  showDetails = ref(false),
  details = ref(null),
  downloadNotice = ref("");
let controller,
  timeout,
  loadVersion = 0,
  disposed = false;
const downloads = new Set();
const view = computed(() =>
  data.value ? periodView(data.value, period.value) : null,
);
const trend = computed(() =>
  data.value ? trendView(data.value, range.value) : [],
);
const monthly = computed(() => (data.value ? monthlyTrades(data.value) : []));
const comparison = computed(
  () =>
    ({ week: "较上周同期", month: "较上月同期", quarter: "较上季度同期" })[
      period.value
    ],
);
const metrics = computed(() =>
  view.value
    ? [
        {
          id: "totalUsers",
          label: "总用户数",
          icon: "users",
          color: "#6253ff",
          hint: "截至统计区间末的累计用户数",
        },
        {
          id: "activeUsers",
          label: "活跃用户数",
          icon: "user",
          color: "#ff941c",
          hint: "统计区间内去重活跃用户，不等于日活相加",
        },
        {
          id: "tradeAmountFen",
          label: "交易总额",
          icon: "box",
          color: "#21bd4b",
          hint: "统计区间内模拟成交金额，货币单位为人民币",
        },
        {
          id: "contentTotal",
          label: "内容总量",
          icon: "task",
          color: "#ab65ff",
          hint: "统计区间内发布内容数量，与下方分类合计一致",
        },
      ].map((item) => ({
        ...item,
        value: view.value.current[item.id],
        delta: changePercent(
          view.value.current[item.id],
          view.value.previous[item.id],
        ),
      }))
    : [],
);
async function reload() {
  const version = ++loadVersion;
  controller?.abort();
  clearTimeout(timeout);
  controller = new AbortController();
  loading.value = true;
  error.value = "";
  timeout = setTimeout(() => controller.abort(), 8000);
  try {
    const result = await loadAnalytics({ signal: controller.signal });
    if (version === loadVersion && !disposed) data.value = result;
  } catch (e) {
    if (version === loadVersion && !disposed)
      error.value =
        e.name === "AbortError" ? "读取模拟数据超时，请重试" : e.message;
  } finally {
    if (version === loadVersion && !disposed) {
      clearTimeout(timeout);
      loading.value = false;
    }
  }
}
function download() {
  const blob = new Blob([analyticsCsv(data.value, view.value)], {
    type: "text/csv;charset=utf-8",
  });
  const url = URL.createObjectURL(blob);
  downloads.add(url);
  const link = document.createElement("a");
  link.href = url;
  link.download = `campus-pi-analytics-mock-${period.value}-${data.value.asOf}.csv`;
  link.click();
  setTimeout(() => {
    URL.revokeObjectURL(url);
    downloads.delete(url);
  }, 1000);
  downloadNotice.value = "已导出所选区间的模拟数据CSV，文件内已标注数据来源。";
}
async function openDetails() {
  showDetails.value = true;
  await nextTick();
  details.value?.scrollIntoView({
    behavior: matchMedia("(prefers-reduced-motion: reduce)").matches
      ? "instant"
      : "smooth",
    block: "start",
  });
}
onMounted(reload);
onBeforeUnmount(() => {
  disposed = true;
  controller?.abort();
  clearTimeout(timeout);
  downloads.forEach((url) => URL.revokeObjectURL(url));
});
</script>
<template>
  <section
    class="analytics-page"
    :class="{ 'is-dashboard': dashboard }"
    aria-label="数据分析页面"
    :aria-busy="loading"
  >
    <div class="analytics-toolbar">
      <div class="analytics-period-tabs" role="group" aria-label="指标统计周期">
        <button
          v-for="key in PERIOD_KEYS"
          :key="key"
          :aria-pressed="period === key"
          :class="{ selected: period === key }"
          :disabled="!data || loading"
          @click="
            period = key;
            downloadNotice = '';
          "
        >
          {{ { week: "本周", month: "本月", quarter: "本季度" }[key] }}
        </button>
      </div>
    </div>
    <div class="analytics-source-line sr-only">
      <span>仅用于页面演示，不代表真实运营统计</span
      ><span v-if="view"
        >{{ view.start }} 至 {{ view.end }} · 截至 {{ data.asOf }}</span
      >
    </div>
    <div v-if="loading && !data" class="analytics-loading" role="status">
      <div class="analytics-loading-cards"><i v-for="i in 4" :key="i"></i></div>
      <div class="analytics-loading-chart"></div>
      <p>正在读取模拟JSON数据…</p>
    </div>
    <div v-else-if="error" class="analytics-load-error" role="alert">
      <Icon name="info" :size="30" />
      <h2>模拟数据暂不可用</h2>
      <p>{{ error }}</p>
      <button @click="reload" :disabled="loading">重新加载</button>
    </div>
    <template v-else-if="view">
      <div class="analytics-metrics">
        <article
          v-for="item in metrics"
          :key="item.id"
          class="analytics-metric"
          :style="{ '--metric-color': item.color }"
          :aria-label="item.label"
          :title="item.hint"
        >
          <div>
            <h2>{{ item.label }}</h2>
            <strong :data-metric="item.id">{{
              item.id === "tradeAmountFen"
                ? moneyFormat(item.value, item.value % 100 ? 2 : 0)
                : countFormat(item.value)
            }}</strong>
            <p
              :class="{
                decrease: item.delta < 0,
                neutral: item.delta === 0 || item.delta === null,
              }"
            >
              <template v-if="item.delta !== null && item.delta !== 0"
                ><span>{{ item.delta > 0 ? "↑" : "↓" }}</span>
                {{ Math.abs(item.delta).toFixed(1) }}%<small>{{
                  comparison
                }}</small></template
              ><template v-else>{{
                item.delta === null ? "同期为零，暂无可比值" : "与同期持平"
              }}</template>
            </p>
          </div>
          <span class="analytics-metric-icon"
            ><ReportGlyph
              :name="
                {
                  totalUsers: 'metricUsers',
                  activeUsers: 'metricActive',
                  tradeAmountFen: 'metricTrade',
                  contentTotal: 'metricContent',
                }[item.id]
              "
          /></span>
        </article>
      </div>
      <section class="analytics-card analytics-growth">
        <header class="analytics-card-header">
          <div>
            <h2>用户增长趋势</h2>
            <p class="sr-only">
              {{
                range === "year"
                  ? "近12个月 · 新增用户与月活跃用户（按月去重）"
                  : range === "month"
                    ? "近30天 · 新增用户与日活跃用户"
                    : "近7天 · 新增用户与日活跃用户"
              }}
            </p>
          </div>
          <div
            class="analytics-range-tabs"
            role="group"
            aria-label="用户趋势时间范围"
          >
            <button
              v-for="[key, label] in [
                ['week', '周'],
                ['month', '月'],
                ['year', '年'],
              ]"
              :key="key"
              :aria-pressed="range === key"
              :class="{ selected: range === key }"
              @click="range = key"
            >
              {{ label }}
            </button>
          </div>
        </header>
        <TrendChart :points="trend" :range="range" />
      </section>
      <div class="analytics-bottom-grid">
        <section class="analytics-card analytics-pie-card">
          <header class="analytics-card-header">
            <h2>交易类型占比</h2>
            <details class="analytics-tools-menu">
              <summary aria-label="数据分析操作">⋮</summary>
              <div class="analytics-menu-panel">
                <div class="analytics-toolbar-actions">
                  <span class="analytics-mock-badge"><i></i>JSON 模拟数据</span
                  ><button
                    class="analytics-refresh"
                    aria-label="刷新模拟数据"
                    :disabled="loading"
                    @click="reload"
                  >
                    <Icon name="rotate" :size="15" />{{
                      loading ? "读取中" : "刷新"
                    }}</button
                  ><button
                    class="analytics-export"
                    :disabled="!data || !!error || loading"
                    @click="download"
                  >
                    <Icon name="upload" :size="15" />导出CSV
                  </button>
                </div>
                <p>仅用于页面演示，不代表真实运营统计</p>
                <p v-if="view">{{ view.start }} 至 {{ view.end }}</p>
                <button @click="openDetails" aria-label="查看交易分类明细">
                  查看模拟数据明细
                </button>
              </div>
            </details>
          </header>
          <CategoryChart
            :items="view.trade"
            type="pie"
            unit="money"
            label="交易类型占比"
          />
        </section>
        <section class="analytics-card analytics-monthly-card">
          <header class="analytics-card-header">
            <h2>月度交易趋势</h2>
            <button
              class="analytics-card-more"
              aria-label="查看交易明细"
              @click="openDetails"
            >
              ⋮
            </button>
          </header>
          <BarChart :items="monthly" />
          <p class="analytics-chart-footnote sr-only">
            图表月份 {{ monthly[0]?.date }} 至
            {{ monthly.at(-1)?.date }}，数据为JSON模拟
          </p>
        </section>
        <section class="analytics-card analytics-content-card">
          <header class="analytics-card-header">
            <h2>内容分类占比</h2>
            <button
              class="analytics-card-more"
              aria-label="查看内容分类明细"
              @click="openDetails"
            >
              ⋮
            </button>
          </header>
          <CategoryChart
            :items="view.content"
            type="donut"
            label="内容分类占比"
          />
        </section>
      </div>
      <div class="analytics-bottom-notes" v-if="showDetails">
        <p>
          活跃用户按区间去重；本月与本季度在季度首月可能覆盖相同日期。同期区间：{{
            view.comparisonStart
          }}
          至 {{ view.comparisonEnd }}。
        </p>
        <button
          :aria-expanded="showDetails"
          aria-controls="analytics-details"
          @click="showDetails = !showDetails"
        >
          {{ showDetails ? "收起" : "查看" }}模拟数据明细
          <Icon name="chevron" :size="13" />
        </button>
      </div>
      <section
        v-if="showDetails"
        id="analytics-details"
        ref="details"
        class="analytics-card analytics-details"
      >
        <header class="analytics-card-header">
          <div>
            <h2>模拟数据明细</h2>
            <p>{{ view.label }} · 全部来自本地JSON</p>
          </div>
          <button class="analytics-export" @click="download">
            <Icon name="upload" :size="15" />导出CSV
          </button>
        </header>
        <div class="analytics-table-scroll">
          <table class="analytics-data-table">
            <caption class="sr-only">
              所选区间的逐日模拟运营数据
            </caption>
            <thead>
              <tr>
                <th>日期</th>
                <th>新增用户</th>
                <th>日活跃用户</th>
                <th>交易额（元）</th>
                <th>发布内容</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in view.rows" :key="row.date">
                <td>{{ row.date }}</td>
                <td>{{ countFormat(row.newUsers) }}</td>
                <td>{{ countFormat(row.activeUsers) }}</td>
                <td>{{ moneyFormat(sumTrade(row)) }}</td>
                <td>{{ countFormat(sumContent(row)) }}</td>
              </tr>
            </tbody>
            <tfoot>
              <tr>
                <th scope="row">期间合计</th>
                <td>{{ countFormat(view.current.newUsers) }}</td>
                <td>去重 {{ countFormat(view.current.activeUsers) }}</td>
                <td>{{ moneyFormat(view.current.tradeAmountFen) }}</td>
                <td>{{ countFormat(view.current.contentTotal) }}</td>
              </tr>
            </tfoot>
          </table>
        </div>
        <div class="analytics-details-categories">
          <div>
            <h3>交易分类明细</h3>
            <dl>
              <div v-for="item in view.trade" :key="item.id">
                <dt>{{ item.name }}</dt>
                <dd>{{ moneyFormat(item.value) }}</dd>
              </div>
            </dl>
          </div>
          <div>
            <h3>内容分类明细</h3>
            <dl>
              <div v-for="item in view.content" :key="item.id">
                <dt>{{ item.name }}</dt>
                <dd>{{ countFormat(item.value) }} 条</dd>
              </div>
            </dl>
          </div>
        </div>
      </section>
      <p v-if="downloadNotice" class="analytics-download-notice" role="status">
        {{ downloadNotice }}
      </p>
    </template>
  </section>
</template>
<style src="./analytics.css"></style>
