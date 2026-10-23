<script setup>
import Icon from "../Icon.vue";
defineProps({ active: String });
const emit = defineEmits(["navigate"]);
const tabs = [
  ["首页", "campus", "/home"],
  ["发现", "eye", "/modules/D"],
  ["发布", "plus", "/publish"],
  ["私信", "mail", "/modules/H"],
  ["个人", "user", "/me"],
];
</script>
<template>
  <nav class="word-mobile-tabs" aria-label="校园导航">
    <button
      v-for="[label, icon, route] in tabs"
      :key="route"
      :class="{
        selected: active === route,
        'word-tab-publish': route === '/publish',
      }"
      @click="emit('navigate', route)"
      :aria-label="label"
    >
      <template v-if="route === '/publish'">＋</template
      ><template v-else><Icon :name="icon" />{{ label }}</template>
    </button>
  </nav>
</template>
