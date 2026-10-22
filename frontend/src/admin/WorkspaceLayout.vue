<script setup>
import { ref, onMounted, onUnmounted } from "vue";
import Icon from "../Icon.vue";
import AdminNotificationEntry from "./AdminNotificationEntry.vue";
import { adminMenu } from "./navigation.js";
defineProps({
  admin: Boolean,
  user: { type: Object, required: true },
  path: { type: String, required: true },
  pageTitle: String,
  contentPanel: Boolean,
});
const emit = defineEmits(["navigate", "logout", "notice", "open-inbox"]);
const menuOpen = ref(false),
  compact = ref(false),
  menuButton = ref(null);
let media;
const updateCompact = () => {
  compact.value = media.matches;
  if (!compact.value) menuOpen.value = false;
};
onMounted(() => {
  media = matchMedia("(max-width: 900px)");
  updateCompact();
  media.addEventListener("change", updateCompact);
});
onUnmounted(() => media?.removeEventListener("change", updateCompact));
const closeMenu = () => {
  menuOpen.value = false;
  menuButton.value?.focus();
};
const navigate = (destination) => {
  if (menuOpen.value) closeMenu();
  emit("navigate", destination);
};
const logout = () => emit("logout");
const notify = (message) => emit("notice", message);
const openAdminInbox = () => emit("open-inbox");
const initials = (value) => value?.username?.slice(0, 1).toUpperCase() || "Π";
</script>
<template>
  <div
    class="app-shell live-app"
    @keydown.esc="menuOpen && closeMenu()"
    :class="{
      'admin-shell': admin,
      'admin-workspace': admin,
      'word-admin-shell': admin,
      'word-admin-users': path === '/admin/users',
      'word-admin-content': contentPanel,
      'word-analytics-shell': path === '/admin/modules/analytics',
    }"
  >
    <button
      v-if="admin && compact && menuOpen"
      class="admin-nav-backdrop"
      aria-label="收起管理导航"
      @click="closeMenu"
    />
    <aside
      id="workspace-sidebar"
      class="sidebar"
      :class="{ 'dark-sidebar': admin, 'is-open': menuOpen }"
      :inert="admin && compact && !menuOpen"
      @keydown.esc="closeMenu"
    >
      <div class="brand">
        <Icon v-if="admin" name="campus" :size="25" />
        <span v-else class="brand-symbol">Π</span>
        <div v-if="admin">校园信息管理平台</div>
        <div v-else>
          校园Π<span class="brand-under">{{
            admin ? "管理工作台" : "校园生活服务平台"
          }}</span>
        </div>
      </div>
      <div class="sidebar-section">{{ admin ? "主导航" : "个人空间" }}</div>
      <nav class="side-menu">
        <template v-if="admin"
          ><template v-for="item in adminMenu" :key="item[1]">
            <div
              v-if="item[1] === '/admin/modules/settings'"
              class="sidebar-section word-system-section"
            >
              系统设置
            </div>
            <button
              :class="{
                selected:
                  path === item[1] ||
                  (item[1] === '/admin/users' &&
                    path.startsWith('/admin/users/')),
              }"
              @click="navigate(item[1])"
            >
              <Icon :name="item[2]" :size="20" />{{ item[0] }}
            </button></template
          ></template
        ><template v-else
          ><button
            :class="{ selected: path === '/home' }"
            @click="navigate('/home')"
          >
            <Icon name="grid" />校园生活</button
          ><button
            :class="{ selected: path === '/me' }"
            @click="navigate('/me')"
          >
            <Icon name="user" />我的账号</button
          ><button
            :class="{ selected: path === '/me/profile' }"
            @click="navigate('/me/profile')"
          >
            <Icon name="edit" />资料与隐私</button
          ><button
            :class="{ selected: path === '/me/password' }"
            @click="navigate('/me/password')"
          >
            <Icon name="shield" />账号安全
          </button></template
        >
      </nav>
      <div class="sidebar-bottom">
        <button
          v-if="admin"
          class="word-admin-user-return"
          @click="navigate('/home')"
        >
          用户端
        </button>
        <button class="sidebar-user" @click="logout">
          <span class="avatar tiny blue">{{ initials(user) }}</span
          ><span
            ><strong :title="user.username">{{ user.username }}</strong
            ><small>{{ "退出登录" }}</small></span
          ><Icon name="exit" :size="18" />
        </button>
      </div>
    </aside>
    <div class="app-body">
      <header class="app-header">
        <button
          v-if="admin"
          ref="menuButton"
          class="admin-menu-toggle"
          aria-label="管理导航"
          :aria-expanded="menuOpen"
          aria-controls="workspace-sidebar"
          @click="menuOpen = !menuOpen"
        >
          <Icon name="grid" :size="20" />
        </button>
        <div v-if="admin" class="word-admin-page-title">{{ pageTitle }}</div>
        <div v-else class="breadcrumb">
          <span>{{ admin ? "平台管理" : "个人空间" }}</span
          ><Icon name="chevron" :size="13" /><strong>{{ pageTitle }}</strong>
        </div>
        <div v-if="admin" class="word-admin-header-tools">
          <div class="word-global-search">
            <Icon name="search" /><input
              aria-label="全局搜索（开发中）"
              placeholder="全局搜索…"
              readonly
              @click="notify('全局搜索还在开发')"
            />
          </div>
          <AdminNotificationEntry :refresh-key="path" @open="openAdminInbox" />
          <button aria-label="帮助（开发中）" @click="notify('帮助还在开发')">
            ●<span>?</span>
          </button>
        </div>
        <div v-else class="header-right">
          <button
            v-if="user.role === 'ADMIN'"
            class="text-link"
            @click="navigate(admin ? '/home' : '/admin')"
          >
            {{ admin ? "用户端" : "管理端" }}</button
          ><span class="avatar tiny blue">{{ initials(user) }}</span
          ><span class="header-name">{{ user.username }}</span
          ><button
            class="icon-button mobile-exit"
            aria-label="退出登录"
            @click="logout"
          >
            <Icon name="exit" :size="18" />
          </button>
        </div>
      </header>
      <main class="content"><slot /></main>
      <footer class="app-footer">
        <span>校园Π · {{ admin ? "管理工作台" : "校园生活服务平台" }}</span
        ><span>ACCOUNT & ACCESS</span>
      </footer>
    </div>
    <nav v-if="!admin" class="mobile-nav">
      <button @click="navigate('/home')"><Icon name="grid" />校园生活</button
      ><button @click="navigate('/me')"><Icon name="user" />我的账号</button
      ><button @click="navigate('/me/profile')">
        <Icon name="edit" />资料隐私</button
      ><button @click="navigate('/me/password')">
        <Icon name="shield" />账号安全
      </button>
    </nav>
  </div>
</template>
