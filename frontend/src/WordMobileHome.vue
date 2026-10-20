<script setup>
import { ref } from "vue";
import Icon from "./Icon.vue";
defineProps({ user: Object });
const emit = defineEmits(["navigate"]);
const feedback = ref("");
</script>
<template>
  <main class="word-mobile-stage">
    <div class="word-mobile-screen word-home-screen">
      <section class="word-home-page">
        <header class="word-home-header">
          <button class="word-home-search" @click="feedback = '搜索还在开发'">
            <Icon name="search" />搜索信息、商品或任务..
          </button>
          <div class="word-home-shortcuts">
            <button
              v-for="[label, icon, route] in [
                ['发布信息', 'plus', 'D'],
                ['二手交易', 'users', 'C'],
                ['悬赏任务', 'task', 'E'],
                ['交友广场', 'users', 'D'],
              ]"
              :key="label"
              @click="emit('navigate', '/modules/' + route)"
            >
              <span><Icon :name="icon" /></span>{{ label }}
            </button>
          </div>
        </header>
        <div class="word-home-categories">
          <button class="selected" @click="emit('navigate', '/modules/D')">
            推荐</button
          ><button @click="emit('navigate', '/modules/D')">失物招领</button
          ><button @click="emit('navigate', '/modules/D')">活动宣传</button>
        </div>
        <section
          v-for="[label, route] in [
            ['热门信息', 'D'],
            ['二手交易', 'C'],
          ]"
          :key="label"
          class="word-home-panel"
        >
          <div>
            <h1>{{ label }}</h1>
            <button @click="emit('navigate', '/modules/' + route)">
              查看更多
            </button>
          </div>
          <button
            class="word-home-pending"
            @click="emit('navigate', '/modules/' + route)"
          >
            还在开发
          </button>
        </section>
      </section>
      <nav class="word-mobile-tabs">
        <button class="selected"><Icon name="campus" />首页</button
        ><button @click="emit('navigate', '/modules/D')">
          <Icon name="eye" />发现</button
        ><button
          class="word-tab-publish"
          aria-label="发布"
          @click="emit('navigate', '/modules/D')"
        >
          ＋</button
        ><button @click="emit('navigate', '/modules/H')">
          <Icon name="mail" />私信</button
        ><button aria-label="我的账号" @click="emit('navigate', '/me')">
          <Icon name="user" />个人
        </button>
      </nav>
      <div v-if="feedback" class="word-auth-feedback" role="status">
        <span>{{ feedback }}</span
        ><button aria-label="关闭提示" @click="feedback = ''">×</button>
      </div>
    </div>
  </main>
</template>
