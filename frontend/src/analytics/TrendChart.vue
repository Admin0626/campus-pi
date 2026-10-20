<script setup>
import { computed, ref, watch, onMounted, onBeforeUnmount } from "vue";
import { countFormat, niceAxis, SERIES } from "./data.js";
const props = defineProps({
  points: { type: Array, required: true },
  range: String,
});
const visible = ref({ newUsers: true, activeUsers: true }),
  active = ref(null);
watch(
  () => props.range,
  () => (active.value = null),
);
const series = computed(() => SERIES.filter((s) => visible.value[s.id]));
const axis = computed(() =>
  niceAxis(
    Math.max(
      0,
      ...props.points.flatMap((p) => series.value.map((s) => p[s.id])),
    ),
  ),
);
const canvas = ref(null),
  canvasWidth = ref(1050);
let observer;
onMounted(() => {
  const measure = () => {
    canvasWidth.value = Math.max(
      240,
      Math.round(canvas.value?.clientWidth || 1050),
    );
  };
  measure();
  observer = new ResizeObserver(measure);
  observer.observe(canvas.value);
});
onBeforeUnmount(() => observer?.disconnect());
const chartHeight = computed(() =>
  canvasWidth.value < 600
    ? 235
    : Math.min(330, Math.round(canvasWidth.value * 0.28)),
);
const plot = computed(() => ({
  left: canvasWidth.value < 600 ? 45 : 60,
  top: 18,
  width: canvasWidth.value - (canvasWidth.value < 600 ? 65 : 80),
  height: chartHeight.value - 50,
}));
const x = (index) =>
  plot.value.left +
  (index * plot.value.width) / Math.max(1, props.points.length - 1);
const y = (value) =>
  plot.value.top + plot.value.height * (1 - value / axis.value.max);
function line(key) {
  return props.points
    .map((p, i) =>
      i === 0
        ? `M${x(i)},${y(p[key])}`
        : `C${x(i - 1) + (x(i) - x(i - 1)) * 0.45},${y(props.points[i - 1][key])} ${x(i) - (x(i) - x(i - 1)) * 0.45},${y(p[key])} ${x(i)},${y(p[key])}`,
    )
    .join(" ");
}
const area = (key) =>
  line(key) + ` L${x(props.points.length - 1)},${y(0)} L${x(0)},${y(0)} Z`;
const shownPoint = computed(() =>
  active.value === null ? null : props.points[active.value],
);
const showLabel = (index) => {
  const maxLabels =
    canvasWidth.value < 500 ? 4 : canvasWidth.value < 800 ? 7 : 12;
  const step = Math.max(
    1,
    Math.ceil((props.points.length - 1) / (maxLabels - 1)),
  );
  return index === 0 || index === props.points.length - 1 || index % step === 0;
};
function move(event, index, delta) {
  event.preventDefault();
  const next = Math.max(0, Math.min(props.points.length - 1, index + delta));
  event.currentTarget.ownerSVGElement
    .querySelectorAll(".trend-hit")
    [next]?.focus();
}
</script>
<template>
  <div class="analytics-series-legend" aria-label="显示用户趋势序列">
    <button
      v-for="item in SERIES"
      :key="item.id"
      :aria-pressed="visible[item.id]"
      :class="{ muted: !visible[item.id] }"
      @click="visible[item.id] = !visible[item.id]"
    >
      <i :style="{ background: item.color }"></i>{{ item.name }}
    </button>
  </div>
  <div ref="canvas" class="analytics-chart-canvas analytics-trend-canvas">
    <svg
      :viewBox="`0 0 ${canvasWidth} ${chartHeight}`"
      role="group"
      aria-label="用户增长趋势图，可聚焦数据点并用左右键查看"
    >
      <g v-for="tick in axis.ticks" :key="tick" class="analytics-grid">
        <line
          :x1="plot.left"
          :x2="plot.left + plot.width"
          :y1="y(tick)"
          :y2="y(tick)"
        />
        <text :x="plot.left - 13" :y="y(tick) + 4" text-anchor="end">
          {{ countFormat(tick) }}
        </text>
      </g>
      <g v-for="(point, index) in points" :key="point.date">
        <line
          v-if="points.length <= 12"
          class="analytics-grid-line vertical"
          :x1="x(index)"
          :x2="x(index)"
          :y1="plot.top"
          :y2="y(0)"
        />
        <text
          v-if="showLabel(index)"
          class="analytics-axis-label"
          :x="x(index)"
          :y="chartHeight - 8"
          text-anchor="middle"
        >
          {{ point.label }}
        </text>
      </g>
      <g v-for="item in series" :key="item.id">
        <path :d="area(item.id)" :fill="item.color" fill-opacity=".11" />
        <path
          :d="line(item.id)"
          fill="none"
          :stroke="item.color"
          stroke-width="2.8"
        />
        <circle
          v-for="(point, index) in points"
          :key="point.date"
          :cx="x(index)"
          :cy="y(point[item.id])"
          :r="active === index ? 5 : 3"
          :fill="item.color"
        />
      </g>
      <line
        v-if="shownPoint"
        class="analytics-crosshair"
        :x1="x(active)"
        :x2="x(active)"
        :y1="plot.top"
        :y2="y(0)"
      />
      <rect
        v-for="(point, index) in points"
        :key="point.date"
        class="trend-hit"
        role="button"
        tabindex="0"
        :aria-label="
          point.date +
          '，' +
          series.map((s) => s.name + countFormat(point[s.id]) + '人').join('，')
        "
        :x="x(index) - plot.width / Math.max(1, points.length - 1) / 2"
        :y="plot.top"
        :width="plot.width / Math.max(1, points.length - 1)"
        :height="plot.height"
        fill="transparent"
        @mouseenter="active = index"
        @mouseleave="active = null"
        @focus="active = index"
        @blur="active = null"
        @click="active = index"
        @keydown.right="move($event, index, 1)"
        @keydown.left="move($event, index, -1)"
        @keydown.esc="active = null"
      />
    </svg>
    <div
      v-if="shownPoint && series.length"
      class="analytics-chart-tooltip"
      role="status"
      :style="{
        left: Math.max(90, Math.min(canvasWidth - 90, x(active))) + 'px',
      }"
    >
      <strong>{{ shownPoint.date }}</strong
      ><span v-for="item in series" :key="item.id"
        ><i :style="{ background: item.color }"></i>{{ item.name }}
        <b>{{ countFormat(shownPoint[item.id]) }}</b> 人</span
      >
    </div>
    <div v-if="!series.length" class="analytics-chart-empty">
      点击上方图例，选择需要展示的数据
    </div>
  </div>
</template>
