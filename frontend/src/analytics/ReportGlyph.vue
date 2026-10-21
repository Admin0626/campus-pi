<script setup>
import { computed } from "vue";
const props = defineProps({ name: String });
// Decorative pixels from the original embedded Word figure; controls remain native Vue elements.
const regions = {
  campus: [9, 15, 12, 12],
  grid: [10, 63, 11, 11],
  shield: [10, 88, 11, 11],
  users: [10, 112, 11, 11],
  box: [10, 137, 11, 11],
  task: [10, 162, 11, 11],
  file: [10, 186, 11, 11],
  bell: [10, 210, 11, 11],
  chart: [10, 234, 11, 11],
  settings: [10, 282, 11, 11],
  lock: [10, 307, 11, 11],
  metricUsers: [242, 92, 26, 26],
  metricActive: [388, 92, 26, 26],
  metricTrade: [536, 92, 26, 26],
  metricContent: [683, 92, 26, 26],
};
const region = computed(() => regions[props.name] || regions.grid);
const style = computed(() => ({
  "--glyph-x": region.value[0],
  "--glyph-y": region.value[1],
  "--glyph-w": region.value[2],
  "--glyph-h": region.value[3],
}));
</script>
<template>
  <span class="report-glyph" :style="style" aria-hidden="true"
    ><img src="/word-reference/analytics.png" alt=""
  /></span>
</template>
<style>
.report-glyph {
  position: relative;
  display: inline-block;
  overflow: hidden;
  flex-shrink: 0;
  width: calc(var(--glyph-w) * var(--report-u));
  height: calc(var(--glyph-h) * var(--report-u));
  vertical-align: middle;
}
.report-glyph img {
  position: absolute;
  max-width: none;
  width: calc(737 * var(--report-u));
  height: calc(557 * var(--report-u));
  left: calc(-1 * var(--glyph-x) * var(--report-u));
  top: calc(-1 * var(--glyph-y) * var(--report-u));
}
</style>
