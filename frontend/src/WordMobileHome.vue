<script setup>
import { ref, onMounted } from "vue";
import Icon from "./Icon.vue";
import MobileTabs from "./member/MobileTabs.vue";
import FilePreview from "./admin/FilePreview.vue";
import {api} from "./api.js";
defineProps({ user: Object });
const emit = defineEmits(["navigate"]);
const feedback = ref("");
const posts=ref([]),products=ref([]),loading=ref(true);
onMounted(async()=>{const result=await Promise.allSettled([api('/posts?page_size=3'),api('/products?page_size=3')]);if(result[0].status==='fulfilled')posts.value=result[0].value.items;if(result[1].status==='fulfilled')products.value=result[1].value.items;feedback.value=result.filter(r=>r.status==='rejected').map(r=>r.reason.message).join('；');loading.value=false;});
</script>
<template>
  <main class="word-mobile-stage">
    <div class="word-mobile-screen word-home-screen">
      <section class="word-home-page">
        <header class="word-home-header">
          <button class="word-home-search" @click="emit('navigate', '/modules/D')">
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
          <p v-if="loading">正在加载…</p><p v-else-if="!(route==='D'?posts:products).length">暂无公开内容</p>
          <div :class="route === 'C' ? 'home-product-grid' : 'home-post-stack'">
          <article v-for="row in (route==='D'?posts:products).slice(0,2)" :key="row.post_id||row.product_id" class="home-live-item" :class="{ 'home-live-product': route === 'C' }">
            <FilePreview v-if="route === 'C'" :files="(row.image_urls || []).slice(0,1)" mode="thumbnail" />
            <span v-else class="home-post-mark">Π</span>
            <button @click="emit('navigate','/modules/'+route)"><strong>{{row.post_title||row.product_name}}</strong><span>{{route==='D'?row.author_label:'¥'+row.discount_price}}</span></button>
          </article></div>
        </section>
      </section>
      <MobileTabs active="/home" @navigate="emit('navigate',$event)"/>
      <div v-if="feedback" class="word-auth-feedback" role="status">
        <span>{{ feedback }}</span
        ><button aria-label="关闭提示" @click="feedback = ''">×</button>
      </div>
    </div>
  </main>
</template>

<style scoped>
.home-product-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px}.home-post-stack{display:grid;gap:10px}.home-live-item{display:flex;align-items:center;gap:12px;min-width:0;border:1px solid #eee;border-radius:8px;overflow:hidden}.home-live-item>button{display:grid;gap:8px;text-align:left;width:100%;padding:12px;background:white;color:#354057;font:inherit;min-width:0}.home-live-item span{font-size:12px;color:#888}.home-live-item strong{font-size:14px;font-weight:500;overflow-wrap:anywhere}.home-live-product{display:block}.home-live-product>button>span{color:#ed6b64}.home-post-mark{display:grid;place-items:center;width:72px;align-self:stretch;flex:none;background:#8376e9;color:white!important;font-size:30px!important}.home-live-product :deep(.file-preview-thumbnail img){height:90px}
</style>
