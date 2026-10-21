<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from "vue";
import Icon from "./Icon.vue";
import WordAccountPages from "./WordAccountPages.vue";
import WordUserManagement from "./WordUserManagement.vue";
import WordMobileMe from "./WordMobileMe.vue";
import WordMobileHome from "./WordMobileHome.vue";
import AnalyticsPage from "./analytics/AnalyticsPage.vue";
import ReportGlyph from "./analytics/ReportGlyph.vue";
import { api, requestKey, setToken, setExpiredHandler } from "./api.js";

const path = ref(location.pathname),
  user = ref(null),
  busy = ref(false),
  loading = ref(false),
  error = ref(""),
  notice = ref("");
const isAuth = computed(() =>
  ["/login", "/register", "/forgot-password", "/admin/login"].includes(
    path.value,
  ),
);
const admin = computed(() => path.value.startsWith("/admin"));
const login = reactive({ account: "", password: "" });
const form = reactive({
  username: "",
  email: "",
  phone: "",
  password: "",
});
const edit = reactive({ bio: "", address: "", campus: false, intro: false });
const password = reactive({ current: "", next: "", confirm: "" });
const errors = reactive({});
const users = ref([]),
  total = ref("0"),
  page = ref(1),
  size = ref(10),
  selected = ref(null);
const filter = reactive({
    keyword: "",
    account_status: "",
    role: "",
    campus: "",
  }),
  applied = reactive({ ...filter });
const statusTarget = ref(null),
  reason = ref(""),
  statusError = ref(""),
  statusKey = ref("");
let registrationKey = requestKey(),
  registrationBody = "",
  listVersion = 0,
  navigationVersion = 0;
const modules = [
  ["C", "商品与分类", "box"],
  ["D", "校园帖子", "file"],
  ["E", "悬赏任务", "task"],
  ["F", "任务验收与结算", "check"],
  ["G", "订单与售后", "box"],
  ["H", "私信与举报", "mail"],
  ["I", "通知与投递", "bell"],
];
const adminMenu = [
  ["控制台", "/admin", "grid"],
  ["内容发布管理", "/admin/modules/content", "shield"],
  ["用户管理", "/admin/users", "users"],
  ["交易管理", "/admin/modules/products", "box"],
  ["悬赏任务", "/admin/modules/tasks", "task"],
  ["订单管理", "/admin/modules/orders", "file"],
  ["通知管理", "/admin/modules/notices", "bell"],
  ["数据分析", "/admin/modules/analytics", "chart"],
  ["系统设置", "/admin/modules/settings", "grid"],
  ["权限管理", "/admin/modules/permissions", "lock"],
];
const pendingName = computed(
  () =>
    (path.value === "/modules/B" ? "实名与校园认证" : "") ||
    adminMenu.find((m) => m[1] === path.value)?.[0] ||
    modules.find((m) => `/modules/${m[0]}` === path.value)?.[1] ||
    "此功能",
);
const pageTitle = computed(() =>
  path.value === "/admin"
    ? "管理工作台"
    : path.value === "/admin/users"
      ? "用户管理"
      : /^\/admin\/users\//.test(path.value)
        ? "用户详情"
        : path.value === "/home"
          ? "校园生活"
          : path.value === "/me"
            ? "我的账号"
            : path.value === "/me/profile"
              ? "资料与隐私"
              : path.value === "/me/password"
                ? "账号安全"
                : pendingName.value,
);
function clear() {
  error.value = "";
  Object.keys(errors).forEach((k) => delete errors[k]);
}
function displayError(e) {
  error.value = e.message;
  for (const f of e.fields || []) errors[f.field] = f.message;
}
function notify(value) {
  notice.value = value;
}
function date(value) {
  return value
    ? new Intl.DateTimeFormat("zh-CN", {
        timeZone: "Asia/Shanghai",
        dateStyle: "medium",
        timeStyle: "short",
        hour12: false,
      }).format(new Date(value))
    : "—";
}
function initials(value) {
  return value?.username?.slice(0, 1).toUpperCase() || "Π";
}
async function navigate(destination, replace = false) {
  if (busy.value) return;
  const version = ++navigationVersion;
  clear();
  statusTarget.value = null;
  if (
    !["/login", "/register", "/forgot-password", "/admin/login"].includes(
      destination,
    ) &&
    !user.value
  )
    destination = destination.startsWith("/admin") ? "/admin/login" : "/login";
  if (
    !["/login", "/register", "/forgot-password", "/admin/login"].includes(
      destination,
    )
  ) {
    try {
      const latest = await api("/me");
      if (version !== navigationVersion) return;
      user.value = latest;
    } catch (e) {
      if (version === navigationVersion) displayError(e);
      return;
    }
  }
  if (
    destination.startsWith("/admin") &&
    destination !== "/admin/login" &&
    user.value?.role !== "ADMIN"
  ) {
    destination = "/home";
    notify("需要管理员权限");
  }
  path.value = destination;
  history[replace ? "replaceState" : "pushState"](null, "", destination);
  window.scrollTo(0, 0);
  if (destination === "/admin/users") await loadUsers();
  else if (/^\/admin\/users\/[1-9]\d*$/.test(destination))
    await loadDetail(destination.split("/").at(-1));
  else if (destination === "/me/profile")
    Object.assign(edit, {
      bio: user.value.personal_bio || "",
      address: user.value.address || "",
      campus: user.value.privacy_settings.show_campus,
      intro: user.value.privacy_settings.show_personal_bio,
    });
  else if (destination === "/me/password")
    Object.assign(password, { current: "", next: "", confirm: "" });
}
function focusError() {
  requestAnimationFrame(() =>
    document.querySelector("[aria-invalid=true]")?.focus(),
  );
}
function passwordValid(value) {
  const count = Array.from(value).length;
  return count >= 12 && count <= 64;
}
async function signIn() {
  if (busy.value) return;
  clear();
  if (!login.account.trim()) errors.account = "请输入账号";
  if (!passwordValid(login.password)) errors.password = "密码为 12–64 个字符";
  if (Object.keys(errors).length) {
    error.value = Object.values(errors)[0];
    return focusError();
  }
  const adminLogin = path.value === "/admin/login";
  busy.value = true;
  try {
    const result = await api("/auth/login", {
      method: "POST",
      body: { ...login, account: login.account.trim() },
    });
    setToken(result.access_token);
    if (adminLogin && result.user.role !== "ADMIN") {
      try {
        await api("/auth/logout", { method: "POST" });
      } finally {
        setToken("");
      }
      user.value = null;
      error.value = "该账号不是管理员，请使用管理员账号登录";
      return;
    }
    user.value = result.user;
    login.password = "";
    busy.value = false;
    await navigate(user.value.role === "ADMIN" ? "/admin" : "/home", true);
    notify("登录成功");
  } catch (e) {
    displayError(e);
  } finally {
    busy.value = false;
  }
}
async function register() {
  if (busy.value) return;
  clear();
  if (!/^[A-Za-z][A-Za-z0-9_]{2,24}$/.test(form.username))
    errors.username = "用户名以字母开头，3–25 位字母、数字或下划线";
  if (!form.email.trim() && !form.phone.trim())
    errors.email = "邮箱或手机号至少填写一项";
  if (
    form.email.trim() &&
    (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.email.trim()) ||
      form.email.trim().length > 100)
  )
    errors.email = "邮箱格式不正确";
  if (form.phone.trim() && !/^\+[1-9][0-9]{7,14}$/.test(form.phone.trim()))
    errors.phone = "手机号需带国家区号，如 +8613800000000";
  if (!passwordValid(form.password)) errors.password = "密码为 12–64 个字符";
  if (Object.keys(errors).length) {
    error.value = Object.values(errors)[0];
    return focusError();
  }
  const body = { username: form.username, password: form.password };
  if (form.email.trim()) body.email = form.email.trim();
  if (form.phone.trim()) body.phone = form.phone.trim();
  const text = JSON.stringify(body);
  if (registrationBody !== text) {
    registrationKey = requestKey();
    registrationBody = text;
  }
  busy.value = true;
  try {
    await api("/auth/register", { method: "POST", body, key: registrationKey });
    login.account = body.username;
    login.password = "";
    form.password = "";
    registrationBody = "";
    setToken("");
    user.value = null;
    busy.value = false;
    await navigate("/login", true);
    notify("注册成功，请使用新账号登录");
  } catch (e) {
    displayError(e);
  } finally {
    busy.value = false;
  }
}
async function logout() {
  if (busy.value) return;
  busy.value = true;
  try {
    await api("/auth/logout", { method: "POST" });
  } catch (e) {
    if (e.status !== 401) {
      displayError(e);
      busy.value = false;
      return;
    }
  }
  setToken("");
  user.value = null;
  busy.value = false;
  await navigate("/login", true);
  notify("已退出登录");
}
async function loadUsers() {
  const version = ++listVersion;
  loading.value = true;
  try {
    const query = new URLSearchParams({
      page: String(page.value),
      page_size: String(size.value),
    });
    for (const [k, v] of Object.entries(applied)) if (v) query.set(k, v);
    const result = await api(`/admin/users?${query}`);
    if (version === listVersion) {
      users.value = result.items;
      total.value = result.total;
    }
  } catch (e) {
    if (version === listVersion) displayError(e);
  } finally {
    if (version === listVersion) loading.value = false;
  }
}
async function search() {
  Object.assign(applied, filter);
  page.value = 1;
  await loadUsers();
}
async function reset() {
  Object.assign(filter, {
    keyword: "",
    account_status: "",
    role: "",
    campus: "",
  });
  await search();
}
async function loadDetail(id) {
  loading.value = true;
  selected.value = null;
  try {
    selected.value = await api(`/admin/users/${id}`);
  } catch (e) {
    displayError(e);
  } finally {
    loading.value = false;
  }
}
function statusDialog(value) {
  statusTarget.value = value;
  reason.value = "";
  statusError.value = "";
  statusKey.value = requestKey();
}
async function changeStatus() {
  if (busy.value) return;
  const why = reason.value.trim();
  if (!why || Array.from(why).length > 500) {
    statusError.value = "请填写 1–500 字的操作原因";
    return;
  }
  busy.value = true;
  try {
    const updated = await api(
      `/admin/users/${statusTarget.value.user_id}/status`,
      {
        method: "POST",
        key: statusKey.value,
        body: {
          account_status:
            statusTarget.value.account_status === "ACTIVE"
              ? "DISABLED"
              : "ACTIVE",
          reason: why,
        },
      },
    );
    statusTarget.value = null;
    if (selected.value?.user_id === updated.user_id) selected.value = updated;
    await loadUsers();
    notify("账号状态已更新");
  } catch (e) {
    statusError.value = e.message;
  } finally {
    busy.value = false;
  }
}
async function saveProfile() {
  if (busy.value) return;
  clear();
  if (
    Array.from(edit.bio).length > 500 ||
    Array.from(edit.address).length > 500
  ) {
    error.value = "个人介绍和地址最多 500 个字符";
    return;
  }
  busy.value = true;
  try {
    user.value = await api("/me", {
      method: "PATCH",
      body: {
        personal_bio: edit.bio,
        address: edit.address,
        privacy_settings: {
          show_campus: edit.campus,
          show_personal_bio: edit.intro,
        },
      },
    });
    busy.value = false;
    await navigate("/me");
    notify("个人资料已保存");
  } catch (e) {
    displayError(e);
  } finally {
    busy.value = false;
  }
}
async function changePassword() {
  if (busy.value) return;
  clear();
  if (
    !passwordValid(password.current) ||
    !passwordValid(password.next) ||
    password.next !== password.confirm
  ) {
    error.value = "请检查密码长度（12–64 字符）及两次新密码是否一致";
    return;
  }
  busy.value = true;
  try {
    await api("/me/password", {
      method: "POST",
      body: { current_password: password.current, new_password: password.next },
    });
    setToken("");
    login.account = user.value.username;
    user.value = null;
    busy.value = false;
    await navigate("/login", true);
    notify("密码修改成功，请重新登录");
  } catch (e) {
    displayError(e);
  } finally {
    busy.value = false;
  }
}
function expired() {
  setToken("");
  user.value = null;
  busy.value = false;
  navigate("/login", true);
  notify("登录状态已失效，请重新登录");
}
function pop() {
  navigate(location.pathname, true);
}
setExpiredHandler(expired);
onMounted(() => {
  window.addEventListener("popstate", pop);
  navigate(location.pathname === "/" ? "/login" : location.pathname, true);
});
onUnmounted(() => window.removeEventListener("popstate", pop));
</script>

<template>
  <WordAccountPages
    v-if="isAuth"
    :path="path"
    :login="login"
    :registration="form"
    :errors="errors"
    :busy="busy"
    :error="error"
    :notice="notice"
    @login="signIn"
    @register="register"
    @navigate="navigate"
    @unavailable="notify"
    @dismiss="
      error = '';
      notice = '';
    "
  />
  <WordMobileMe
    v-else-if="user && path === '/me'"
    :user="user"
    :error="error"
    @navigate="navigate"
    @unavailable="notify"
    @logout="logout"
    @dismiss="error = ''"
  />
  <WordMobileHome
    v-else-if="user && path === '/home'"
    :user="user"
    @navigate="navigate"
  />
  <div
    v-else-if="user"
    class="app-shell live-app"
    :class="{
      'admin-shell': admin,
      'word-admin-shell': admin,
      'word-admin-users': path === '/admin/users',
      'word-analytics-shell': path === '/admin/modules/analytics',
    }"
  >
    <aside class="sidebar" :class="{ 'dark-sidebar': admin }">
      <div class="brand">
        <ReportGlyph
          v-if="path === '/admin/modules/analytics'"
          name="campus"
        /><Icon v-else-if="admin" name="campus" :size="25" />
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
              <ReportGlyph
                v-if="path === '/admin/modules/analytics'"
                :name="
                  item[1] === '/admin/modules/settings' ? 'settings' : item[2]
                "
              /><Icon v-else :name="item[2]" :size="19" />{{ item[0] }}
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
          v-if="admin && path !== '/admin/modules/analytics'"
          class="word-admin-user-return"
          @click="navigate('/home')"
        >
          用户端
        </button>
        <button class="sidebar-user" @click="logout">
          <span class="avatar tiny blue">{{ initials(user) }}</span
          ><span
            ><strong :title="user.username">{{
              path === "/admin/modules/analytics" ? "系统管理员" : user.username
            }}</strong
            ><small>{{
              path === "/admin/modules/analytics"
                ? user.email || user.username
                : "退出登录"
            }}</small></span
          ><Icon name="exit" :size="18" />
        </button>
      </div>
    </aside>
    <div class="app-body">
      <header class="app-header">
        <div v-if="admin" class="word-admin-page-title">{{ pageTitle }}</div>
        <div v-else class="breadcrumb">
          <span>{{ admin ? "平台管理" : "个人空间" }}</span
          ><Icon name="chevron" :size="13" /><strong>{{ pageTitle }}</strong>
        </div>
        <div v-if="admin" class="word-admin-header-tools">
          <div class="word-global-search">
            <Icon name="search" /><input
              aria-label="全局搜索（开发中）"
              :placeholder="
                path === '/admin/modules/analytics' ? '搜索…' : '全局搜索....'
              "
              readonly
              @click="notify('全局搜索还在开发')"
            />
          </div>
          <button aria-label="通知（开发中）" @click="notify('通知还在开发')">
            <Icon name="bell" /><span
              v-if="path === '/admin/modules/analytics'"
              class="report-notice-count"
              title="原图示意数，通知功能开发中"
              >5</span
            ></button
          ><button aria-label="帮助（开发中）" @click="notify('帮助还在开发')">
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
      <main class="content">
        <div class="page-heading">
          <div>
            <div class="eyebrow">
              {{ admin ? "CAMPUS PI / ADMIN" : "CAMPUS PI / ACCOUNT" }}
            </div>
            <h1>{{ pageTitle }}</h1>
            <p>
              {{ admin ? "校园Π管理工作空间" : "让每一次校园连接都更安心。" }}
            </p>
          </div>
          <button
            v-if="path.startsWith('/admin/users/')"
            class="secondary"
            @click="navigate('/admin/users')"
          >
            返回列表
          </button>
        </div>
        <div
          v-if="
            notice &&
            !(path === '/admin/modules/analytics' && notice === '登录成功')
          "
          class="alert info-alert"
          role="status"
        >
          {{ notice
          }}<button
            class="icon-button"
            aria-label="关闭提示"
            @click="notice = ''"
          >
            <Icon name="close" :size="15" />
          </button>
        </div>
        <div v-if="error" class="alert error" role="alert">
          {{ error
          }}<button
            v-if="path === '/admin/users'"
            class="text-link"
            @click="loadUsers"
          >
            重试
          </button>
        </div>
        <AnalyticsPage v-if="path === '/admin/modules/analytics'" />
        <template v-else-if="path === '/home' || path === '/admin'"
          ><section class="panel welcome-panel">
            <span class="badge blue-badge">{{
              admin ? "管理工作台" : "欢迎回来"
            }}</span>
            <h2>{{ user.username }}，你好</h2>
            <p>
              {{
                admin
                  ? "账号管理已开放，可查看平台用户并管理账号状态。"
                  : "账号已登录。你可以查看个人资料、设置公开范围和管理账号安全。"
              }}
            </p>
            <button
              class="primary"
              @click="navigate(admin ? '/admin/users' : '/me')"
            >
              {{ admin ? "进入用户管理" : "进入个人中心"
              }}<Icon name="arrow" :size="16" />
            </button>
          </section>
          <div class="module-grid">
            <button
              v-for="item in admin
                ? adminMenu.filter(
                    (m) => m[1] !== '/admin' && m[1] !== '/admin/users',
                  )
                : modules"
              :key="item[0]"
              class="panel module-card"
              @click="navigate(admin ? item[1] : `/modules/${item[0]}`)"
            >
              <Icon :name="item[2]" :size="24" /><strong>{{
                admin ? item[0] : item[1]
              }}</strong
              ><span class="badge neutral">{{
                admin && item[1] === "/admin/modules/analytics"
                  ? "模拟数据"
                  : "还在开发"
              }}</span
              ><Icon name="arrow" :size="16" />
            </button></div
        ></template>
        <section v-else-if="path === '/me'" class="panel">
          <div class="detail-identity">
            <span class="avatar large blue">{{ initials(user) }}</span>
            <div>
              <h2>
                {{ user.username }}<span class="badge green">账号正常</span>
              </h2>
              <p class="subtle">
                ID {{ user.user_id }} ·
                {{ user.role === "ADMIN" ? "管理员" : "普通用户" }}
              </p>
            </div>
          </div>
          <dl class="info-list">
            <div
              v-for="[key, label] in [
                ['email', '绑定邮箱'],
                ['phone', '绑定手机号'],
                ['campus', '认证校区'],
                ['identity_verification', '实名认证'],
                ['campus_verification', '校园认证'],
                ['personal_bio', '个人介绍'],
                ['address', '默认地址'],
              ]"
              :key="key"
            >
              <dt>{{ label }}</dt>
              <dd>{{ user[key] || "尚未填写" }}</dd>
            </div>
            <div>
              <dt>注册时间</dt>
              <dd>{{ date(user.registration_time) }}</dd>
            </div>
          </dl>
          <div class="form-footer live-panel-footer">
            <button class="secondary" @click="navigate('/modules/B')">
              查看认证</button
            ><button class="primary" @click="navigate('/me/profile')">
              编辑资料
            </button>
          </div>
        </section>
        <form
          v-else-if="path === '/me/profile'"
          class="panel edit-panel live-form-panel"
          @submit.prevent="saveProfile"
        >
          <div class="panel-heading">
            <div>
              <Icon name="edit" />
              <h3>个人资料</h3>
            </div>
          </div>
          <div class="field">
            <label>用户名</label><input :value="user.username" disabled />
          </div>
          <div class="field">
            <label for="bio">个人介绍</label
            ><textarea
              id="bio"
              v-model="edit.bio"
              rows="3"
              :disabled="busy"
            ></textarea
            ><small>最多 500 字</small>
          </div>
          <div class="field">
            <label for="address">默认地址</label
            ><input
              id="address"
              v-model="edit.address"
              :disabled="busy"
            /><small>仅本人可见</small>
          </div>
          <div class="toggle-row">
            <div>
              <label for="campus-visible">公开所在校区</label>
              <p>认证校区按认证结果展示</p>
            </div>
            <button
              id="campus-visible"
              type="button"
              role="switch"
              class="switch"
              :aria-checked="edit.campus"
              :disabled="busy"
              @click="edit.campus = !edit.campus"
            >
              <span></span>
            </button>
          </div>
          <div class="toggle-row">
            <div>
              <label for="bio-visible">公开个人介绍</label>
              <p>允许其他用户查看个人介绍</p>
            </div>
            <button
              id="bio-visible"
              type="button"
              role="switch"
              class="switch"
              :aria-checked="edit.intro"
              :disabled="busy"
              @click="edit.intro = !edit.intro"
            >
              <span></span>
            </button>
          </div>
          <div class="form-footer">
            <button type="button" class="secondary" @click="navigate('/me')">
              取消</button
            ><button class="primary" :disabled="busy">
              {{ busy ? "保存中…" : "保存资料" }}
            </button>
          </div>
        </form>
        <form
          v-else-if="path === '/me/password'"
          class="panel password-panel live-form-panel"
          @submit.prevent="changePassword"
        >
          <div class="panel-heading">
            <div>
              <Icon name="lock" />
              <h3>修改密码</h3>
            </div>
          </div>
          <div class="alert info-alert">修改后所有会话失效，需要重新登录。</div>
          <div class="field">
            <label for="current-password">当前密码</label
            ><input
              id="current-password"
              v-model="password.current"
              type="password"
              autocomplete="current-password"
              :disabled="busy"
            />
          </div>
          <div class="field">
            <label for="new-password">新密码</label
            ><input
              id="new-password"
              v-model="password.next"
              type="password"
              autocomplete="new-password"
              :disabled="busy"
            /><small>12–64 个字符</small>
          </div>
          <div class="field">
            <label for="new-confirm">确认新密码</label
            ><input
              id="new-confirm"
              v-model="password.confirm"
              type="password"
              autocomplete="new-password"
              :disabled="busy"
            />
          </div>
          <div class="form-footer">
            <button type="button" class="secondary" @click="navigate('/me')">
              取消</button
            ><button class="primary" :disabled="busy">
              {{ busy ? "修改中…" : "确认修改密码" }}
            </button>
          </div>
        </form>
        <WordUserManagement
          v-else-if="path === '/admin/users'"
          :users="users"
          :total="total"
          :page="page"
          :page-size="size"
          :filters="filter"
          :current-id="user.user_id"
          :loading="loading"
          @search="search"
          @reset="reset"
          @detail="navigate('/admin/users/' + $event.user_id)"
          @status="statusDialog"
          @page="
            page = $event;
            loadUsers();
          "
          @size="
            size = $event;
            page = 1;
            loadUsers();
          "
          @unavailable="notify"
        />
        <template v-else-if="/^\/admin\/users\//.test(path)"
          ><div v-if="loading" class="loading-state">正在加载用户详情…</div>
          <section v-else-if="selected" class="panel">
            <div class="detail-identity">
              <span class="avatar large blue">{{ initials(selected) }}</span>
              <div>
                <h2>{{ selected.username }}</h2>
                <p class="subtle">ID {{ selected.user_id }} · 管理视图已脱敏</p>
              </div>
            </div>
            <dl class="info-list">
              <div
                v-for="[key, label] in [
                  ['email', '邮箱'],
                  ['phone', '手机号'],
                  ['role', '角色'],
                  ['account_status', '账号状态'],
                  ['identity_verification', '实名认证'],
                  ['campus_verification', '校园认证'],
                ]"
                :key="key"
              >
                <dt>{{ label }}</dt>
                <dd>{{ selected[key] || "—" }}</dd>
              </div>
            </dl>
            <div class="form-footer live-panel-footer">
              <button class="secondary" disabled>
                <Icon name="lock" :size="15" />角色调整未开放</button
              ><button
                class="primary"
                :disabled="selected.user_id === user.user_id"
                @click="statusDialog(selected)"
              >
                {{
                  selected.account_status === "ACTIVE" ? "停用账号" : "启用账号"
                }}
              </button>
            </div>
          </section></template
        >
        <section v-else class="panel pending-panel">
          <div class="recovery-icon"><Icon name="task" :size="32" /></div>
          <span class="eyebrow">COMING SOON</span>
          <h2>{{ pendingName }}还在开发</h2>
          <p>此页面已预留入口，功能完成后将在这里开放。</p>
          <button
            class="secondary"
            @click="navigate(admin ? '/admin' : '/home')"
          >
            返回{{ admin ? "工作台" : "校园生活"
            }}<Icon name="arrow" :size="16" />
          </button>
        </section>
      </main>
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
  <div
    v-if="statusTarget"
    class="modal-overlay"
    @click.self="!busy && (statusTarget = null)"
  >
    <section
      class="dialog"
      role="dialog"
      aria-modal="true"
      aria-labelledby="status-title"
    >
      <div class="dialog-heading">
        <h2 id="status-title">
          {{ statusTarget.account_status === "ACTIVE" ? "停用" : "启用" }}账号
        </h2>
        <button
          class="icon-button"
          :disabled="busy"
          @click="statusTarget = null"
          aria-label="关闭弹窗"
        >
          <Icon name="close" />
        </button>
      </div>
      <p class="dialog-copy">
        目标用户：{{
          statusTarget.username
        }}。状态变更会撤销该用户已有会话，并保留操作审计；历史订单不会自动取消。
      </p>
      <div class="field">
        <label for="reason">操作原因</label
        ><textarea
          id="reason"
          v-model="reason"
          rows="4"
          :disabled="busy"
        ></textarea
        ><small v-if="statusError" class="field-error" role="alert">{{
          statusError
        }}</small>
      </div>
      <div class="dialog-footer">
        <button class="secondary" :disabled="busy" @click="statusTarget = null">
          取消</button
        ><button class="primary" :disabled="busy" @click="changeStatus">
          {{ busy ? "提交中…" : "确认操作" }}
        </button>
      </div>
    </section>
  </div>
</template>
