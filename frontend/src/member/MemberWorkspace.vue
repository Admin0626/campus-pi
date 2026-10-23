<script setup>
import MobileTabs from "./MobileTabs.vue";
import ProductBoard from "./ProductBoard.vue";
import PostBoard from "./PostBoard.vue";
import TaskBoard from "./TaskBoard.vue";
import OrderBoard from "./OrderBoard.vue";
import ChatBoard from "./ChatBoard.vue";
import VerificationBoard from "./VerificationBoard.vue";
import "./member.css";
const props = defineProps({ path: String, user: Object });
const emit = defineEmits(["navigate"]);
const names = {
  B: "实名与校园认证",
  C: "校园二手",
  D: "校园发现",
  E: "悬赏任务",
  F: "任务记录与奖励",
  G: "我的订单",
  H: "校园私信",
};
</script>
<template>
  <main class="word-mobile-stage">
    <div class="word-mobile-screen member-screen">
      <header class="member-header">
        <button @click="emit('navigate', '/home')" aria-label="返回首页">
          ‹
        </button>
        <h1>{{ names[path.split("/").at(-1)] || "发布信息" }}</h1>
        <button @click="emit('navigate', '/modules/I')">通知</button>
      </header>
      <section class="member-content" :class="{ 'member-chat-content': path === '/modules/H' }">
        <template v-if="path === '/publish'"
          ><p>选择要发布的内容</p>
          <div class="member-actions">
            <button
              v-for="[label, target] in [
                ['二手商品', 'C'],
                ['校园帖子', 'D'],
                ['悬赏任务', 'E'],
              ]"
              :key="target"
              @click="emit('navigate', '/modules/' + target)"
            >
              {{ label }}
            </button>
          </div></template
        ><VerificationBoard v-else-if="path === '/modules/B'" /><ProductBoard
          v-else-if="path === '/modules/C'"
          :user="user"
          @navigate="emit('navigate', $event)"
        /><PostBoard v-else-if="path === '/modules/D'" :user="user" /><TaskBoard
          v-else-if="['/modules/E', '/modules/F'].includes(path)"
          :user="user"
          :records="path === '/modules/F'"
        /><OrderBoard
          v-else-if="path === '/modules/G'"
          :user="user"
        /><ChatBoard v-else-if="path === '/modules/H'" :user="user" />
      </section>
      <MobileTabs :active="path" @navigate="emit('navigate', $event)" />
    </div>
  </main>
</template>
