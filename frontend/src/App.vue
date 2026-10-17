<script setup>
const modules = [
  ['A', '账号与权限'], ['B', '认证、文件与审计'], ['C', '商品与分类'],
  ['D', '帖子与管理概览'], ['E', '任务发布与接取'], ['F', '任务验收与结算'],
  ['G', '订单与售后'], ['H', '私信与举报'], ['I', '通知与投递'],
]
</script>

<template>
  <main>
    <p class="eyebrow">CAMPUS Π</p>
    <h1>校园Π开发起始页</h1>
    <p>前端基础工程已启动。九位成员各负责一个模块的前端、后端与联调测试。</p>
    <p class="status">业务页面和112项接口仍待开发。模块范围、接口规则和交接说明见仓库 README。</p>
    <h2>模块归属</h2>
    <ul>
      <li v-for="[code, name] in modules" :key="code">
        <strong>{{ code }}</strong><span>{{ name }}</span><span class="pending">待开发</span>
      </li>
    </ul>
  </main>
</template>
