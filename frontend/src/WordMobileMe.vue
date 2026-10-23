<script setup>
import { ref, onMounted } from "vue";
import Icon from "./Icon.vue";
import MobileTabs from "./member/MobileTabs.vue";
import {api} from "./api.js";
defineProps({ user: Object, error: String });
const emit = defineEmits(["navigate", "unavailable", "logout", "dismiss"]);
const feedback = ref("");const counts=ref(['—','—','—']);
onMounted(async()=>{const results=await Promise.allSettled(['/me/products','/me/posts','/me/tasks'].map(p=>api(p+'?page_size=1')));counts.value=results.map(r=>r.status==='fulfilled'?r.value.total:'—');});
</script>
<template>
  <main class="word-mobile-stage word-personal-stage">
    <div class="word-mobile-screen">
      <section class="word-me-page">
        <div
          v-if="feedback || error"
          class="word-auth-feedback"
          :role="error ? 'alert' : 'status'"
        >
          <span>{{ error || feedback }}</span
          ><button
            aria-label="关闭提示"
            @click="
              feedback = '';
              emit('dismiss');
            "
          >
            ×
          </button>
        </div>
        <header class="word-me-header">
          <div class="word-me-identity">
            <div class="word-me-avatar"><img v-if="user.avatar_url" :src="user.avatar_url" alt="我的头像" style="width:100%;height:100%;object-fit:cover;border-radius:50%"/><Icon v-else name="users" :size="32" /></div>
            <div>
              <h1>{{ user.username }}</h1>
              <p title="本人接口未提供学号">学号：—</p>
              <p>
                {{
                  user.identity_verification === "已通过" ? "已认证" : "未认证"
                }}<span
                  >✓
                  {{
                    user.campus_verification === "已通过"
                      ? user.campus
                      : "未校园认证"
                  }}</span
                >
              </p>
            </div>
          </div>
          <div class="word-me-statistics">
            <button
              v-for="(label, index) in ['发布商品', '发布帖子', '发布任务']"
              :key="index"
              @click="emit('navigate', ['/modules/C','/modules/D','/modules/E'][index])"
            >
              <strong>{{counts[index]}}</strong><span>{{ label }}</span>
            </button>
          </div>
        </header>
        <section class="word-me-card">
          <h2>我的服务</h2>
          <div class="word-me-services">
            <button @click="emit('navigate', '/modules/D')">
              <Icon name="link" />我的发布</button
            ><button @click="emit('navigate', '/modules/C')">我的收藏</button
            ><button @click="emit('navigate', '/modules/G')">订单管理</button
            ><button @click="emit('navigate', '/modules/F')">任务记录</button>
          </div>
        </section>
        <section class="word-me-card word-me-settings">
          <h2>设置</h2>
          <button @click="emit('navigate','/modules/B')">实名与校园认证<Icon name="chevron"/></button>
          <button @click="emit('navigate', '/me/profile')">
            账号设置<Icon name="chevron" /></button
          ><button @click="emit('navigate', '/modules/I')">
            通知中心<Icon name="chevron" /></button
          ><button @click="emit('navigate', '/me/password')">
            隐私安全<Icon name="chevron" />
          </button>
        </section>
        <button class="word-me-logout" @click="emit('logout')">退出登录</button>
      </section>
      <MobileTabs active="/me" @navigate="emit('navigate',$event)"/>
    </div>
  </main>
</template>
