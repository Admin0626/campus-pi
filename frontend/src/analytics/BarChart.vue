<script setup>
import { computed, ref } from "vue";
import { moneyFormat, countFormat } from "./data.js";
const props = defineProps({ items: { type: Array, required: true } });
const active = ref(null);
const axis = computed(() => {
  const max = Math.max(0, ...props.items.map((p) => p.value / 100));
  const step = max > 100000 ? 100000 : 10000;
  const upper = Math.max(step, Math.ceil(max / step) * step);
  return {
    max: upper,
    ticks: Array.from({ length: upper / step + 1 }, (_, i) => i * step),
  };
});
const plot = { left: 21, top: 12, width: 162, height: 128 };
const y = (value) => plot.top + plot.height * (1 - value / axis.value.max);
const x = (index) =>
  plot.left + ((index + 0.5) * plot.width) / props.items.length;
</script>
<template>
  <div class="analytics-chart-canvas analytics-bar-canvas">
    <svg viewBox="0 0 192 160" role="group" aria-label="月度交易额柱状图">
      <g v-for="tick in axis.ticks" :key="tick" class="analytics-grid">
        <line
          :x1="plot.left"
          :x2="plot.left + plot.width"
          :y1="y(tick)"
          :y2="y(tick)"
        />
        <text :x="plot.left - 7" :y="y(tick) + 3" text-anchor="end">
          ¥{{ countFormat(tick) }}
        </text>
      </g>
      <g v-for="(item, index) in items" :key="item.date">
        <rect
          class="analytics-bar"
          role="button"
          tabindex="0"
          :aria-label="item.date + ' 模拟交易额 ' + moneyFormat(item.value)"
          :x="x(index) - (plot.width / items.length) * 0.33"
          :y="y(item.value / 100)"
          :width="(plot.width / items.length) * 0.66"
          :height="Math.max(item.value ? 2 : 0, y(0) - y(item.value / 100))"
          :fill="active === index ? '#8170ff' : '#4f46e5'"
          rx="3"
          @mouseenter="active = index"
          @mouseleave="active = null"
          @focus="active = index"
          @blur="active = null"
          @click="active = index"
          @keydown.esc="active = null"
        />
        <text
          class="analytics-axis-label"
          :x="x(index)"
          y="151"
          text-anchor="middle"
        >
          {{ item.label }}
        </text>
      </g>
    </svg>
    <div
      v-if="active !== null"
      class="analytics-chart-tooltip"
      role="status"
      :style="{
        left: Math.max(25, Math.min(70, (x(active) / 192) * 100)) + '%',
      }"
    >
      <strong>{{ items[active].date }}</strong
      ><span
        >模拟交易额 <b>{{ moneyFormat(items[active].value) }}</b></span
      >
    </div>
  </div>
</template>
