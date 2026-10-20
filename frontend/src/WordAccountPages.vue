<script setup>
import { computed, ref } from "vue";
import Icon from "./Icon.vue";
const props = defineProps({
  path: String,
  login: Object,
  registration: Object,
  errors: Object,
  busy: Boolean,
  error: String,
  notice: String,
});
const emit = defineEmits([
  "login",
  "register",
  "navigate",
  "unavailable",
  "dismiss",
]);
const isRegister = computed(() => props.path === "/register");
const isAdmin = computed(() => props.path === "/admin/login");
const revealed = ref(false);
const unavailable = (name) =>
  emit("unavailable", `${name}还在开发，当前使用账号和密码登录。`);
</script>
<template>
  <main v-if="isAdmin" class="word-admin-login">
    <header class="word-admin-hero">
      <h1 class="sr-only">校园Π–校园信息交流平台登录</h1>
    </header>
    <section class="word-admin-login-card">
      <div class="word-computer-photo" aria-hidden="true">
        <img src="/word-reference/admin-login.png" alt="" />
      </div>
      <form
        class="word-admin-login-form"
        @submit.prevent="emit('login')"
        novalidate
      >
        <div>
          <label for="admin-account">账号:</label
          ><input
            id="admin-account"
            v-model="login.account"
            aria-label="账号"
            autocomplete="username"
            :disabled="busy"
            :aria-invalid="!!errors.account"
          />
        </div>
        <div>
          <label for="admin-password">密码:</label
          ><input
            id="admin-password"
            v-model="login.password"
            aria-label="密码"
            type="password"
            autocomplete="current-password"
            :disabled="busy"
            :aria-invalid="!!errors.password"
          />
        </div>
        <button
          class="word-admin-remember"
          type="button"
          role="checkbox"
          aria-checked="false"
          aria-disabled="true"
          @click="unavailable('记住密码')"
        >
          <span></span>记住密码
        </button>
        <button class="word-admin-submit" :disabled="busy">
          {{ busy ? "登录中…" : "登录" }}
        </button>
      </form>
    </section>
    <div
      v-if="error || notice"
      class="word-admin-login-feedback"
      :role="error ? 'alert' : 'status'"
    >
      {{ error || notice
      }}<button aria-label="关闭提示" @click="emit('dismiss')">×</button>
    </div>
    <button class="word-admin-user-link" @click="emit('navigate', '/login')">
      用户端登录
    </button>
  </main>
  <main v-else class="word-mobile-stage">
    <div class="word-mobile-screen">
      <section
        v-if="path !== '/forgot-password'"
        class="word-auth-page"
        :class="isRegister ? 'word-register' : 'word-login'"
      >
        <button
          v-if="isRegister"
          class="word-back"
          @click="emit('navigate', '/login')"
        >
          <span>◇</span>返回登录
        </button>
        <div class="word-campus-logo" aria-hidden="true">
          <img src="/word-reference/login.png" alt="" />
        </div>
        <h1>{{ isRegister ? "立即注册" : "欢迎回来" }}</h1>
        <p v-if="!isRegister" class="word-login-subtitle">登录您的校园账户</p>
        <form
          @submit.prevent="emit(isRegister ? 'register' : 'login')"
          novalidate
        >
          <div class="word-auth-field word-username">
            <label :for="isRegister ? 'reg-username' : 'account'">{{
              isRegister ? "用户名" : "学号/用户名"
            }}</label>
            <div class="word-auth-input">
              <span class="word-glyph glyph-user" aria-hidden="true"></span
              ><input
                v-if="isRegister"
                id="reg-username"
                v-model="registration.username"
                autocomplete="username"
                placeholder="请输入学号或用户名"
                :disabled="busy"
                :aria-invalid="!!errors.username"
              /><input
                v-else
                id="account"
                title="目前支持用户名、手机号或邮箱；学号登录待确认"
                v-model="login.account"
                autocomplete="username"
                placeholder="请输入学号或用户名"
                :disabled="busy"
                :aria-invalid="!!errors.account"
              />
            </div>
          </div>
          <div v-if="isRegister" class="word-auth-field word-email">
            <label for="reg-email">邮箱</label>
            <div class="word-auth-input">
              <span class="word-glyph glyph-mail" aria-hidden="true"></span
              ><input
                id="reg-email"
                v-model="registration.email"
                type="email"
                autocomplete="email"
                placeholder="请输入邮箱"
                :disabled="busy"
                :aria-invalid="!!errors.email"
              />
            </div>
          </div>
          <div class="word-auth-field word-password">
            <label :for="isRegister ? 'reg-password' : 'login-password'"
              >密码</label
            >
            <div class="word-auth-input">
              <span class="word-glyph glyph-lock" aria-hidden="true"></span
              ><input
                v-if="isRegister"
                id="reg-password"
                v-model="registration.password"
                :type="revealed ? 'text' : 'password'"
                autocomplete="new-password"
                placeholder="请输入密码"
                :disabled="busy"
                :aria-invalid="!!errors.password"
              /><input
                v-else
                id="login-password"
                v-model="login.password"
                :type="revealed ? 'text' : 'password'"
                autocomplete="current-password"
                placeholder="请输入密码"
                :disabled="busy"
                :aria-invalid="!!errors.password"
              /><button
                type="button"
                :aria-label="revealed ? '隐藏密码' : '显示密码'"
                @click="revealed = !revealed"
              >
                <span class="word-glyph glyph-eye" aria-hidden="true"></span>
              </button>
            </div>
          </div>
          <div class="word-auth-field word-phone">
            <label :for="isRegister ? 'reg-phone' : 'login-phone'"
              >手机号码</label
            >
            <div class="word-auth-input">
              <span class="word-glyph glyph-phone" aria-hidden="true"></span
              ><input
                v-if="isRegister"
                id="reg-phone"
                v-model="registration.phone"
                type="tel"
                autocomplete="tel"
                placeholder="请输入手机号"
                title="使用国际格式，例如 +8613800000000；邮箱或手机号至少一项"
                :disabled="busy"
                :aria-invalid="!!errors.phone"
              /><input
                v-else
                id="login-phone"
                type="tel"
                placeholder="请输入手机号"
                readonly
                aria-label="手机号码（验证码登录开发中）"
                @click="unavailable('手机验证码登录')"
              />
            </div>
          </div>
          <div class="word-auth-field word-code">
            <label for="word-code">验证码</label>
            <div class="word-code-row">
              <div class="word-auth-input">
                <span class="word-glyph glyph-shield" aria-hidden="true"></span
                ><input
                  id="word-code"
                  placeholder="请输入验证码"
                  readonly
                  aria-label="验证码（开发中）"
                  @click="unavailable('验证码')"
                />
              </div>
              <button
                type="button"
                class="word-code-button"
                @click="unavailable('获取验证码')"
              >
                获取验证码
              </button>
            </div>
          </div>
          <div class="word-agreement-row">
            <button
              type="button"
              class="word-check"
              role="checkbox"
              aria-checked="false"
              aria-disabled="true"
              @click="unavailable(isRegister ? '用户协议' : '记住登录状态')"
            >
              <span></span
              >{{ isRegister ? "同意协议" : "记住登录状态" }}</button
            ><button
              v-if="isRegister"
              type="button"
              class="word-underlined"
              @click="unavailable('用户协议正文')"
            >
              同意协议</button
            ><button
              v-else
              type="button"
              class="word-underlined"
              @click="emit('navigate', '/forgot-password')"
            >
              忘记密码?
            </button>
          </div>
          <button class="word-main-button" :disabled="busy">
            <Icon v-if="!isRegister" name="exit" /><span>{{
              busy
                ? isRegister
                  ? "注册中…"
                  : "登录中…"
                : isRegister
                  ? "提交注册"
                  : "登录"
            }}</span>
          </button>
        </form>
        <template v-if="!isRegister"
          ><div class="word-other-login">
            <span></span>或使用第三方登录<span></span>
          </div>
          <div class="word-social-row">
            <button
              v-for="(name, index) in ['微信', 'QQ', '支付宝']"
              :key="name"
              :class="'word-social social-' + index"
              :aria-label="name + '登录（开发中）'"
              @click="unavailable(name + '登录')"
            >
              <img src="/word-reference/login.png" alt="" />
            </button>
          </div>
          <p class="word-register-link">
            还没有账户？<button
              class="word-underlined"
              @click="emit('navigate', '/register')"
            >
              立即注册
            </button>
          </p>
          <div class="word-verification-links">
            <button
              @click="
                emit('unavailable', '实名认证还在开发，登录后可查看认证入口。')
              "
            >
              <span class="word-glyph glyph-user" aria-hidden="true"></span
              >实名认证</button
            ><button
              @click="
                emit('unavailable', '校区认证还在开发，登录后可查看认证入口。')
              "
            >
              <Icon name="campus" />校区认证
            </button>
          </div></template
        >
      </section>
      <section v-else class="word-recovery">
        <div class="word-campus-logo" aria-hidden="true">
          <img src="/word-reference/login.png" alt="" />
        </div>
        <h1>密码找回还在开发</h1>
        <p>发送通道与联系方式验证尚未接入。</p>
        <button class="word-main-button" @click="emit('navigate', '/login')">
          返回登录
        </button>
      </section>
      <div
        v-if="error || notice"
        class="word-auth-feedback"
        :class="{ 'is-error': error }"
        :role="error ? 'alert' : 'status'"
      >
        <span>{{ error || notice }}</span
        ><button aria-label="关闭提示" @click="emit('dismiss')">×</button>
      </div>
    </div>
  </main>
</template>
