<script setup>
defineProps({ name: String, size: { type: Number, default: 20 } });
const paths = {
  arrow: "M5 12h14m-5-5 5 5-5 5",
  back: "m14 6-6 6 6 6",
  chevron: "m9 6 6 6-6 6",
  user: "M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2M16 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0",
  users:
    "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2M13 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0m7-3a4 4 0 0 1 0 8m6 9v-2a4 4 0 0 0-3-3.87",
  shield: "M12 22s8-4 8-11V5l-8-3-8 3v6c0 7 8 11 8 11m-4-11 3 3 5-5",
  lock: "M5 11h14v10H5zM8 11V7a4 4 0 0 1 8 0v4m-4 4v2",
  eye: "M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12m13 0a3 3 0 1 1-6 0 3 3 0 0 1 6 0",
  check: "m5 12 4 4L19 6",
  edit: "m16 3 5 5-12 12H4v-5L16 3m-2 2 5 5",
  exit: "M9 21H3V3h6m6 14 5-5-5-5m-7 5h12",
  info: "M12 16v-4m0-4v.01M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0",
  search: "M21 21l-5-5m2-6a8 8 0 1 1-16 0 8 8 0 0 1 16 0",
  close: "m6 6 12 12M6 18 18 6",
  mail: "M3 5h18v14H3zM3 5l9 8 9-8",
  phone: "M7 2h10v20H7zM11 18h2",
  pin: "M20 10c0 6-8 12-8 12S4 16 4 10a8 8 0 1 1 16 0m-5 0a3 3 0 1 1-6 0 3 3 0 0 1 6 0",
  grid: "M3 3h7v7H3zM14 3h7v7h-7zM3 14h7v7H3zM14 14h7v7h-7z",
  file: "M14 2H4v20h16V8l-6-6m0 0v6h6M8 13h8m-8 4h6",
  box: "m12 2 9 5v10l-9 5-9-5V7l9-5m-9 5 9 5 9-5m-9 5v10",
  task: "M9 4H5v18h14V4h-4M9 2h6v5H9zM8 12h8m-8 5h5",
  bell: "M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9m-11 3h0m3 9a2 2 0 0 0 4 0",
  chart: "M3 3v18h18M8 16v-5m5 5V7m5 9V4",
  campus: "m2 10 10-7 10 7M4 10h16v12H4zM9 22v-7h6v7M7 11h.01M17 11h.01",
  upload: "M12 16V3m-5 5 5-5 5 5M4 15v6h16v-6",
  rotate: "M3 3v6h6M3 9a9 9 0 1 1 1 9",
  clock: "M12 6v6l4 2M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0",
  link: "m10 13 4-2m-7 5-2 1a4 4 0 0 1-4-7l5-3a4 4 0 0 1 6 3m5-2 2-1a4 4 0 0 1 4 7l-5 3a4 4 0 0 1-6-3",
};
</script>
<template>
  <svg
    :width="size"
    :height="size"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.65"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <path :d="paths[name] || paths.info" />
  </svg>
</template>
