<template>
  <div class="ops-shell gl-field">
    <header class="ops-header">
      <a class="ops-brand" :href="router.resolve({ name: 'admin' }).href" @click.prevent="navigateTo('admin')">
        <img src="/favicon.svg" width="36" height="36" alt="" />
        <h1 class="ops-title">草场 <span>· 治理台</span></h1>
      </a>
      <nav v-if="isAuthenticated" class="ops-nav" aria-label="治理台模块">
        <button v-for="item in visibleNavItems" :key="item.view" type="button"
          :class="{ 'ops-nav-active': currentViewName === item.view }"
          :aria-current="currentViewName === item.view ? 'page' : undefined" @click="navigateTo(item.view)">
          <component :is="item.icon" :size="16" aria-hidden="true" />{{ item.label }}
        </button>
      </nav>
      <div class="ops-actions">
        <button class="ops-icon-button theme-toggle" type="button" :title="themeToggleTitle"
          :aria-label="themeToggleTitle" @click="cycleTheme">
          <component :is="themeIcon" :size="18" aria-hidden="true" />
        </button>
        <div v-if="isAuthenticated && currentUser" class="ops-user" aria-live="polite">
          <span class="ops-avatar" aria-hidden="true">{{ userInitial }}</span>
          <div class="ops-user-copy">
            <strong :title="currentUser.displayName || currentUser.email">{{ currentUser.displayName || currentUser.email }}</strong>
            <span class="ops-user-roles" :title="roleSummary">{{ roleSummary }}</span>
          </div>
        </div>
        <button v-if="isAuthenticated" class="ops-icon-button" type="button" title="退出登录"
          aria-label="退出登录" :disabled="loggingOut" @click="handleLogout">
          <LogOut :size="18" aria-hidden="true" />
        </button>
        <button v-else class="ops-button ops-button-primary" type="button" @click="openLoginModal">登录</button>
      </div>
    </header>
    <p v-if="bannerMessage" class="ops-banner" role="status">{{ bannerMessage }}</p>
    <main class="ops-view">
      <section v-if="authLoading" class="ops-empty" role="status" aria-busy="true">
        <LoaderCircle class="ops-spinning" :size="28" aria-hidden="true" />
        <h2>正在确认登录状态</h2>
      </section>
      <section v-else-if="!isAuthenticated" class="ops-empty">
        <ShieldCheck :size="36" aria-hidden="true" />
        <h2>{{ authLoadError ? '登录状态加载失败' : '登录治理台' }}</h2>
        <p v-if="authLoadError" role="alert">{{ authLoadError }}</p>
        <p v-else>请使用平台开通的管理账号登录。</p>
        <button v-if="authLoadError" class="ops-button" type="button" @click="loadCurrentUser(true)">
          <RefreshCw :size="16" aria-hidden="true" />重新加载
        </button>
        <button v-else class="ops-button" type="button" @click="openLoginModal">账号登录</button>
      </section>
      <section v-else-if="routeDenied" class="ops-empty" role="alert">
        <ShieldAlert :size="36" aria-hidden="true" />
        <h2>无访问权限</h2>
        <p>当前账号未获授此模块的管理权限，请联系平台管理员。</p>
      </section>
      <router-view v-else v-slot="{ Component }">
        <component :is="Component" :key="workspaceSessionKey" />
      </router-view>
    </main>
    <LoginModal v-if="loginModalMounted" :visible="showLoginModal" :submitting="loggingIn"
      :error="loginError || sendCodeError" :message="loginModalMessage" hide-register
      @close="closeLoginModal" @submit="handleLogin" @send-code="handleSendCode" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { LayoutGrid, Wrench, Sun, Moon, Monitor, LogOut, ShieldCheck, ShieldAlert, LoaderCircle, RefreshCw } from '@lucide/vue'
import LoginModal from '../components/LoginModal.vue'
import { useAuth } from '../composables/useAuth'
import { useTheme, type ThemeMode } from '../composables/useTheme'
import { useAccountSessionStore } from '../stores/account-session'
import { OPS_ROUTE_ROLES } from './router'
import type { LoginFormValues } from '../types/auth'

type OpsView = keyof typeof OPS_ROUTE_ROLES
const NAV_ITEMS = [
  { view: 'admin', label: '管理后台', icon: LayoutGrid },
  { view: 'ops-console', label: '运营处置', icon: Wrench },
] as const
const ROLE_LABELS: Record<string, string> = {
  platform_admin: '平台管理员', content_reviewer: '内容审核', customer_service: '客服运营',
  risk: '风控专员', finance: '财务专员', merchant_reviewer: '商户审核',
}
const route = useRoute()
const router = useRouter()
const {
  currentUser, isAuthenticated, hasBackendRole, backendRoles,
  loading: authLoading, loadError: authLoadError,
  loggingIn, loggingOut, loginError, sendCodeError,
  clearLoginError, clearSendCodeError, clearLogoutError, logoutError,
  sendVerificationCode, loadCurrentUser, login, logout,
} = useAuth()
const session = useAccountSessionStore()
const { mode: themeMode, setMode: setThemeMode } = useTheme()
const showLoginModal = ref(false)
const loginModalMounted = ref(false)
const loginModalMessage = ref('')
const bannerMessage = ref('')
const currentViewName = computed<OpsView>(() => (route.name as OpsView) || 'admin')
const visibleNavItems = computed(() => NAV_ITEMS.filter((item) =>
  OPS_ROUTE_ROLES[item.view].some((role) => hasBackendRole(role))))
const roleSummary = computed(() => backendRoles.value.map((role) => ROLE_LABELS[role] || role).join(' · ') || '无治理角色')
const userInitial = computed(() => (currentUser.value?.displayName || currentUser.value?.email || '').slice(0, 1).toUpperCase())
const routeDenied = computed(() => !visibleNavItems.value.some((item) => item.view === currentViewName.value))
// 账号或角色变化必须销毁整个工作区，包括 KeepAlive 面板和传送到 body 的私有弹窗。
const workspaceSessionKey = computed(() => `${session.epoch}:${[...backendRoles.value].sort().join(',')}:${currentViewName.value}`)

function navigateTo(view: OpsView): void {
  if (currentViewName.value !== view) void router.push({ name: view })
}
function cycleTheme(): void {
  const order: ThemeMode[] = ['light', 'dark', 'system']
  setThemeMode(order[(order.indexOf(themeMode.value) + 1) % order.length])
}
const themeIcon = computed(() => themeMode.value === 'light' ? Sun : themeMode.value === 'dark' ? Moon : Monitor)
const themeToggleTitle = computed(() => themeMode.value === 'light' ? '切换为深色主题' : themeMode.value === 'dark' ? '主题跟随系统' : '切换为浅色主题')
function openLoginModal(): void {
  loginModalMounted.value = true
  clearLoginError()
  clearSendCodeError()
  clearLogoutError()
  loginModalMessage.value = ''
  showLoginModal.value = true
}
function closeLoginModal(): void {
  clearLoginError()
  clearSendCodeError()
  showLoginModal.value = false
  loginModalMessage.value = ''
}
async function handleLogin(values: LoginFormValues): Promise<void> {
  if (!await login(values)) return
  closeLoginModal()
  bannerMessage.value = ''
}
async function handleSendCode(email: string, captchaCode: string): Promise<void> {
  clearSendCodeError()
  await sendVerificationCode(email, captchaCode)
}
async function handleLogout(): Promise<void> {
  clearLogoutError()
  if (!await logout()) {
    bannerMessage.value = logoutError.value || '退出登录失败，请稍后重试。'
    return
  }
  showLoginModal.value = false
  await router.push({ name: 'admin' })
  bannerMessage.value = '你已退出登录。'
}
onMounted(() => { void loadCurrentUser() })
watch(() => isAuthenticated.value, () => { bannerMessage.value = '' })
</script>
