<template>
  <div class="ops-shell gl-field">
    <header class="ops-header">
      <div class="ops-brand">
        <h1 class="ops-title">草场 · 治理台</h1>
        <p class="ops-subtitle">平台运营与治理专用入口，与用户端（商家 / 推荐官 / 消费者）分离部署</p>
      </div>

      <div class="ops-actions">
        <button class="theme-toggle" type="button" :title="themeToggleTitle" @click="cycleTheme">
          <svg v-if="themeMode === 'light'" width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <circle cx="8" cy="8" r="3.5" stroke="currentColor" stroke-width="1.3"/>
            <path d="M8 1.5v1.5M8 13v1.5M1.5 8H3M13 8h1.5M3.4 3.4l1.1 1.1M11.5 11.5l1.1 1.1M3.4 12.6l1.1-1.1M11.5 4.5l1.1-1.1" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/>
          </svg>
          <svg v-else-if="themeMode === 'dark'" width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <path d="M13.5 9.2A6 6 0 016.8 2.5 6 6 0 108 14a6 6 0 005.5-4.8z" stroke="currentColor" stroke-width="1.3" stroke-linejoin="round"/>
          </svg>
          <svg v-else width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <rect x="2.5" y="3" width="11" height="10" rx="2" stroke="currentColor" stroke-width="1.3"/>
            <path d="M8 3v10" stroke="currentColor" stroke-width="1.3"/>
            <path d="M8 3c2.5 0 4.5 2.2 4.5 5s-2 5-4.5 5" stroke="currentColor" stroke-width="1.1" stroke-linecap="round"/>
          </svg>
        </button>

        <div v-if="isAuthenticated && currentUser" class="ops-user" aria-live="polite">
          <strong>{{ currentUser.displayName || currentUser.email }}</strong>
          <span class="ops-user-roles">{{ roleSummary }}</span>
        </div>

        <button
          v-if="isAuthenticated"
          class="ops-button"
          type="button"
          :disabled="loggingOut"
          @click="handleLogout"
        >
          {{ loggingOut ? '退出中…' : '退出登录' }}
        </button>
        <button v-else class="ops-button ops-button-primary" type="button" @click="openLoginModal">
          登录
        </button>
      </div>
    </header>

    <p v-if="bannerMessage" class="ops-banner" role="status">{{ bannerMessage }}</p>

    <nav v-if="isAuthenticated" class="ops-nav" aria-label="治理台模块">
      <button
        v-for="item in visibleNavItems"
        :key="item.view"
        type="button"
        :class="{ 'ops-nav-active': currentViewName === item.view }"
        :aria-current="currentViewName === item.view ? 'page' : undefined"
        @click="navigateTo(item.view)"
      >
        {{ item.label }}
      </button>
    </nav>

    <main class="ops-view">
      <!-- 门禁为 UX 分层：真正的权限校验在后端 backend_role（requireRole）。 -->
      <section v-if="routeDenied" class="ops-empty" role="alert">
        <h2>无访问权限</h2>
        <p>当前账号没有治理台权限（需要 platform_admin / content_reviewer / customer_service 之一）。</p>
        <p class="ops-empty-hint">如需开通请联系平台管理员；权限校验以服务端为准。</p>
      </section>
      <router-view v-else />
    </main>

    <LoginModal
      v-if="loginModalMounted"
      :visible="showLoginModal"
      :submitting="loggingIn"
      :error="loginError || sendCodeError"
      :message="loginModalMessage"
      hide-register
      @close="closeLoginModal"
      @submit="handleLogin"
      @send-code="handleSendCode"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import LoginModal from '../components/LoginModal.vue'
import { useAuth } from '../composables/useAuth'
import { useTheme, type ThemeMode } from '../composables/useTheme'
import type { LoginFormValues } from '../types/auth'

type OpsView = 'admin' | 'ops-console'

interface NavItem {
  view: OpsView
  label: string
  /** 与用户端主导航相同的后端角色口径（DefaultLayout 旧门禁迁来）。 */
  roles: readonly string[]
}

const NAV_ITEMS: readonly NavItem[] = [
  { view: 'admin', label: '管理后台', roles: ['platform_admin', 'content_reviewer'] },
  { view: 'ops-console', label: '运营处置', roles: ['platform_admin', 'customer_service'] },
]

const route = useRoute()
const router = useRouter()

const {
  currentUser, isAuthenticated, hasBackendRole,
  loggingIn, loggingOut, loginError, sendCodeError,
  clearLoginError, clearSendCodeError, clearLogoutError, logoutError,
  sendVerificationCode, loadCurrentUser, login, logout,
} = useAuth()

const { mode: themeMode, setMode: setThemeMode } = useTheme()

const showLoginModal = ref(false)
const loginModalMounted = ref(false)
const loginModalMessage = ref('')
const bannerMessage = ref('')

const currentViewName = computed<OpsView>(() => (route.name as OpsView) || 'admin')

const visibleNavItems = computed(() => NAV_ITEMS.filter((item) =>
  item.roles.some((role) => hasBackendRole(role))))

const roleSummary = computed(() => {
  const roles = ['platform_admin', 'content_reviewer', 'customer_service', 'finance']
    .filter((role) => hasBackendRole(role))
  if (roles.length > 0) return roles.join(' · ')
  return '无治理角色'
})

/** 当前路由对账号不可见（例如 customer_service 直接落 /admin）：显示无权限态而不是空白页。 */
const routeDenied = computed(() => {
  if (!isAuthenticated.value) return false
  const item = NAV_ITEMS.find((entry) => entry.view === currentViewName.value)
  if (!item) return false
  return !item.roles.some((role) => hasBackendRole(role))
})

function navigateTo(view: OpsView): void {
  router.push({ name: view })
}

function cycleTheme(): void {
  const order: ThemeMode[] = ['light', 'dark', 'system']
  const currentIndex = order.indexOf(themeMode.value)
  setThemeMode(order[(currentIndex + 1) % order.length])
}

const themeToggleTitle = computed(() => {
  if (themeMode.value === 'light') return '浅色模式 — 点击切换'
  if (themeMode.value === 'dark') return '深色模式 — 点击切换'
  return '跟随系统 — 点击切换'
})

function openLoginModal(): void {
  loginModalMounted.value = true
  clearLoginError(); clearLogoutError()
  loginModalMessage.value = ''
  showLoginModal.value = true
}

function closeLoginModal(): void {
  clearLoginError(); clearSendCodeError()
  showLoginModal.value = false
  loginModalMessage.value = ''
}

async function handleLogin(values: LoginFormValues): Promise<void> {
  const ok = await login(values)
  if (!ok) return
  closeLoginModal()
  bannerMessage.value = ''
}

async function handleSendCode(email: string, captchaCode: string): Promise<void> {
  clearSendCodeError()
  await sendVerificationCode(email, captchaCode)
}

async function handleLogout(): Promise<void> {
  clearLogoutError()
  const ok = await logout()
  if (!ok) {
    bannerMessage.value = logoutError.value || '退出登录失败，请稍后重试。'
    return
  }
  showLoginModal.value = false
  router.push({ name: 'admin' })
  bannerMessage.value = '你已退出登录。'
}

onMounted(() => {
  void loadCurrentUser()
})

watch(() => isAuthenticated.value, (authed) => {
  if (!authed) bannerMessage.value = ''
})
</script>

<style scoped>
.ops-shell {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  gap: var(--space-lg);
  width: min(1400px, calc(100% - 40px));
  margin: 0 auto;
  padding: calc(clamp(24px, 4vw, 40px) + env(safe-area-inset-top, 0px)) 0 80px;
}

.ops-header { display: flex; align-items: flex-end; justify-content: space-between; gap: var(--space-md); flex-wrap: wrap; }
.ops-title { margin: 0; font-size: var(--text-xl); font-weight: 800; letter-spacing: -0.03em; }
.ops-subtitle { margin: 4px 0 0; font-size: var(--text-sm); color: var(--color-text-muted); }
.ops-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.ops-user { display: inline-flex; flex-direction: column; align-items: flex-end; gap: 2px; }
.ops-user strong { font-size: 0.86rem; color: var(--color-text); }
.ops-user-roles { font-size: 0.72rem; color: var(--color-text-muted); letter-spacing: 0.03em; }

.theme-toggle, .ops-button {
  display: inline-flex; align-items: center; justify-content: center;
  min-height: 38px; padding: 0 14px; border-radius: 12px;
  border: 1px solid var(--color-border); background: var(--surface-card);
  color: var(--color-text-secondary); cursor: pointer; font-size: 0.84rem; font-weight: 500;
  transition: background var(--duration-fast) var(--ease-out), border-color var(--duration-fast) var(--ease-out), color var(--duration-fast) var(--ease-out);
}
.theme-toggle { width: 38px; padding: 0; }
.theme-toggle:hover, .ops-button:hover:not(:disabled) {
  background: var(--color-surface-hover); border-color: var(--color-border-hover); color: var(--color-text);
}
.ops-button-primary { background: var(--gradient-accent); border: none; color: #fff; font-weight: 600; }
.ops-button-primary:hover { color: #fff; }

.ops-banner {
  margin: 0; padding: 12px 16px; border: 1px solid var(--color-border-accent); border-radius: 14px;
  background: linear-gradient(135deg, rgba(139, 92, 246, 0.06), rgba(99, 102, 241, 0.04));
  color: var(--color-text-secondary); font-size: 0.86rem;
}

.ops-nav { display: flex; gap: 4px; padding: 5px; border-radius: 14px; border: 1px solid var(--color-border); width: fit-content; }
.ops-nav button {
  min-height: 38px; padding: 0 18px; border: none; border-radius: 10px; background: transparent;
  color: var(--color-text-muted); cursor: pointer; font-size: 0.88rem; font-weight: 500;
  transition: background var(--duration-fast) var(--ease-out), color var(--duration-fast) var(--ease-out);
}
.ops-nav button:hover { color: var(--color-text-secondary); background: rgba(139, 92, 246, 0.06); }
.ops-nav-active { background: var(--gradient-accent) !important; color: #fff !important; font-weight: 600; }

.ops-view { flex: 1; display: flex; flex-direction: column; }
.ops-empty { display: grid; gap: 8px; padding: var(--space-xl) var(--space-lg); border: 1px dashed var(--color-border); border-radius: var(--radius-md); align-self: start; }
.ops-empty h2 { margin: 0; font-size: var(--text-lg); }
.ops-empty p { margin: 0; color: var(--color-text-secondary); font-size: var(--text-sm); }
.ops-empty-hint { color: var(--color-text-muted) !important; font-size: var(--text-xs) !important; }

@media (max-width: 720px) {
  .ops-header { flex-direction: column; align-items: flex-start; }
  .ops-nav { width: 100%; overflow-x: auto; }
}
</style>
