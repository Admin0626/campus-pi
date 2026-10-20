<script setup>
import { computed, ref, watch } from "vue";
import { countFormat, moneyFormat, percent } from "./data.js";
const props = defineProps({
  items: { type: Array, required: true },
  type: { type: String, default: "donut" },
  unit: { type: String, default: "count" },
  label: String,
});
const hovered = ref(null),
  selected = ref(null);
watch(
  () => props.items,
  () => {
    hovered.value = null;
    selected.value = null;
  },
);
const total = computed(() => props.items.reduce((n, c) => n + c.value, 0));
const current = computed(() => hovered.value ?? selected.value);
const activeItem = computed(() => props.items[current.value] || null);
const format = (value) =>
  props.unit === "money" ? moneyFormat(value) : countFormat(value) + "条";
const point = (angle) => [
  120 + 100 * Math.cos(angle),
  120 + 100 * Math.sin(angle),
];
const sectors = computed(() => {
  let angle = -Math.PI / 2;
  return props.items.map((item) => {
    const start = point(angle),
      delta = total.value ? (item.value / total.value) * Math.PI * 2 : 0;
    angle += delta;
    const end = point(angle);
    const full = item.value === total.value && total.value > 0;
    return {
      ...item,
      path: full
        ? "M120,20 A100,100 0 1 1 120,220 A100,100 0 1 1 120,20 Z"
        : `M120,120 L${start} A100,100 0 ${delta > Math.PI ? 1 : 0} 1 ${end} Z`,
    };
  });
});
function select(index) {
  selected.value = selected.value === index ? null : index;
}
</script>
<template>
  <div class="analytics-category-chart" :class="type">
    <div class="analytics-pie-drawing">
      <svg viewBox="0 0 240 240" role="group" :aria-label="label">
        <circle cx="120" cy="120" r="100" fill="#f0f2f7" />
        <path
          v-for="(item, index) in sectors"
          v-show="item.value > 0"
          :key="item.id"
          :d="item.path"
          :fill="item.color"
          :opacity="current === null || current === index ? 1 : 0.45"
          :stroke="current === index ? '#fff' : 'none'"
          stroke-width="3"
          tabindex="0"
          role="button"
          :aria-label="
            item.name +
            '，' +
            format(item.value) +
            '，' +
            percent(item.value, total)
          "
          :aria-pressed="selected === index"
          @mouseenter="hovered = index"
          @mouseleave="hovered = null"
          @focus="hovered = index"
          @blur="hovered = null"
          @click="select(index)"
          @keydown.enter.prevent="select(index)"
          @keydown.space.prevent="select(index)"
          @keydown.esc="
            selected = null;
            hovered = null;
          "
        />
        <circle
          v-if="type === 'donut'"
          cx="120"
          cy="120"
          r="66"
          fill="white"
          pointer-events="none"
        />
        <text
          v-if="!total"
          x="120"
          y="125"
          text-anchor="middle"
          class="analytics-axis-label"
        >
          暂无数据
        </text>
      </svg>
    </div>
    <div class="analytics-category-legend" :aria-label="label + '图例'">
      <button
        v-for="(item, index) in items"
        :key="item.id"
        :aria-pressed="selected === index"
        :title="
          item.name +
          ' ' +
          format(item.value) +
          ' ' +
          percent(item.value, total)
        "
        @click="select(index)"
      >
        <i :style="{ background: item.color }"></i><span>{{ item.name }}</span
        ><small>{{ percent(item.value, total) }}</small>
      </button>
    </div>
    <div
      class="analytics-category-readout"
      :class="{ 'is-active': activeItem }"
      role="status"
    >
      <template v-if="activeItem"
        ><i :style="{ background: activeItem.color }"></i>{{ activeItem.name }}
        <b>{{ format(activeItem.value) }}</b
        ><span>{{ percent(activeItem.value, total) }}</span></template
      ><template v-else>{{
        unit === "money" ? "按成交金额统计" : "按发布内容数量统计"
      }}</template>
    </div>
  </div>
</template>
