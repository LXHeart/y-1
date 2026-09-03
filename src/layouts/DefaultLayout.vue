<template>
  <div class="app-shell">
    <header class="page-header">
      <div class="header-row">
        <div class="brand">
          <div class="brand-logo" aria-hidden="true">
            <svg width="36" height="36" viewBox="0 0 36 36" fill="none" xmlns="http://www.w3.org/2000/svg">
              <defs>
                <linearGradient id="logo-grad" x1="0" y1="0" x2="36" y2="36" gradientUnits="userSpaceOnUse">
                  <stop style="stop-color: var(--color-primary)"/>
                  <stop offset="0.5" style="stop-color: var(--color-primary)"/>
                  <stop offset="1" style="stop-color: var(--color-primary)"/>
                </linearGradient>
                <linearGradient id="logo-accent" x1="0" y1="0" x2="36" y2="36" gradientUnits="userSpaceOnUse">
                  <stop style="stop-color: var(--color-primary)" opacity="0.7"/>
                  <stop offset="1" style="stop-color: var(--color-primary)"/>
                </linearGradient>
              </defs>
              <rect width="36" height="36" rx="8" fill="url(#logo-grad)"/>
              <path d="M11 10.5C11 9.67 11.67 9 12.5 9C12.9 9 13.27 9.16 13.53 9.43L23.53 18.43C24.15 19 24.15 19.97 23.53 20.54C23.27 20.78 22.93 20.91 22.57 20.91H12.5C11.67 20.91 11 20.24 11 19.41V10.5Z" fill="rgba(255,255,255,0.95)"/>
              <rect x="11" y="23" width="14" height="1.8" rx="0.9" fill="rgba(255,255,255,0.5)"/>
              <rect x="11" y="26.2" width="9" height="1.8" rx="0.9" fill="rgba(255,255,255,0.35)"/>
              <circle cx="27.5" cy="11.5" r="2.8" fill="url(#logo-accent)" opacity="0.7"/>
            </svg>
          </div>
          <div class="brand-copy">
            <h1 class="brand-title">草场</h1>
            <p class="brand-subtitle">商家 × 推荐官 的种草推广任务撮合平台</p>
          </div>
        </div>

        <div class="header-actions">
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

          <NotificationBell v-if="isAuthenticated" @navigate="handleNotificationNavigate" />

          <button v-if="isAuthenticated" type="button" class="credits-badge" title="积分与套餐"
            @click="showCreditsModal = true">
            <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
              <circle cx="8" cy="8" r="6.5" stroke="currentColor" stroke-width="1.3"/>
              <path d="M8 4.5v7M5.5 7.5h5" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/>
            </svg>
            {{ currentBalance }} 次
          </button>

          <!-- 账号区：身份在登录时选定（会话内不切换；换身份=退出后重新登录选择） -->
          <div v-if="isAuthenticated && currentUser" class="auth-pill" aria-live="polite" data-testid="auth-pill">
            <span class="auth-pill-label">已登录</span>
            <span class="account-side-badge" :class="{ 'account-side-badge-rec': activeSide === 'recommender' }">
              {{ activeSideBadge }}
            </span>
            <strong class="auth-pill-name">{{ currentUser.displayName || currentUser.email }}</strong>
          </div>

          <button
            v-if="isAuthenticated"
            class="auth-trigger auth-trigger-secondary"
            type="button"
            :disabled="loggingOut"
            @click="handleLogout"
          >
            {{ loggingOut ? '退出中…' : '退出登录' }}
          </button>
          <button v-else class="auth-trigger auth-trigger-primary" type="button" @click="openLoginModal()">
            登录
          </button>
        </div>
      </div>

      <p v-if="authBannerMessage" class="auth-banner">{{ authBannerMessage }}</p>

      <nav class="nav-tabs" aria-label="功能选择">
        <button
          class="nav-tab"
          :class="{ 'nav-tab-active': currentViewName === 'home' }"
          :aria-current="currentViewName === 'home' ? 'page' : undefined"
          type="button"
          @click="navigateTo('home')"
        >
          <svg width="15" height="15" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <path d="M2 7.2L8 2l6 5.2v6.1a1.2 1.2 0 01-1.2 1.2H9.7V10H6.3v4.5H3.2A1.2 1.2 0 012 13.3V7.2z" stroke="currentColor" stroke-width="1.3" stroke-linejoin="round"/>
          </svg>
          主页
        </button>
        <button
          v-if="isAuthenticated"
          class="nav-tab"
          :class="{ 'nav-tab-active': currentViewName === 'grassland' }"
          :aria-current="currentViewName === 'grassland' ? 'page' : undefined"
          type="button"
          data-testid="nav-workbench"
          @click="navigateTo('grassland')"
        >
          <svg width="15" height="15" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <path d="M2 13V6.5l6-4 6 4V13" stroke="currentColor" stroke-width="1.3" stroke-linejoin="round"/>
            <path d="M6 13V9h4v4" stroke="currentColor" stroke-width="1.2" stroke-linejoin="round"/>
          </svg>
          {{ workbenchTabLabel }}
        </button>
        <button
          class="nav-tab"
          :class="{ 'nav-tab-active': currentViewName === 'ai-center' }"
          :aria-current="currentViewName === 'ai-center' ? 'page' : undefined"
          type="button"
          @click="navigateTo('ai-center')"
        >
          <svg width="15" height="15" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <path d="M8 1.5l1.2 3.3L12.5 6 9.2 7.2 8 10.5 6.8 7.2 3.5 6l3.3-1.2L8 1.5z" stroke="currentColor" stroke-width="1.2" stroke-linejoin="round"/>
            <path d="M12.5 10l.6 1.6 1.4.5-1.4.6-.6 1.8-.6-1.8-1.4-.6 1.4-.5.6-1.6z" fill="currentColor"/>
          </svg>
          AI 内容创作中心
        </button>
        <button
          v-if="isAuthenticated"
          class="nav-tab"
          :class="{ 'nav-tab-active': currentViewName === 'complaints' }"
          :aria-current="currentViewName === 'complaints' ? 'page' : undefined"
          type="button"
          @click="navigateTo('complaints')"
        >
          <svg width="15" height="15" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <path d="M8 2a4.5 4.5 0 00-4.5 4.5c0 1.4.6 2.6 1.6 3.4V12l1.7-1h2.4l1.7 1V9.9a4.5 4.5 0 00-.9-7.9A4.5 4.5 0 008 2z" stroke="currentColor" stroke-width="1.2" stroke-linejoin="round"/>
            <path d="M6.2 6.5h3.6" stroke="currentColor" stroke-width="1.2" stroke-linecap="round"/>
          </svg>
          举报投诉
        </button>
      </nav>
    </header>

    <main class="view-area">
      <router-view v-slot="{ Component }">
        <KeepAlive :key="creationContextEpoch">
          <component
            :is="Component"
            v-bind="currentViewProps"
            @open-view="handleOpenView"
            @open-creation="handleOpenCreation"
            @start-workflow="handleStartWorkflow"
            @request-login="openLoginModal('请先登录后继续。')"
            @open-grassland="handleOpenGrassland"
            @open-dispute="handleOpenDispute"
          />
        </KeepAlive>
      </router-view>
    </main>

    <CreditsPackagesModal
      :open="showCreditsModal"
      :balance="currentBalance"
      @close="showCreditsModal = false"
      @balance-refreshed="handleCreditsRefreshed"
    />

    <LoginModal
      v-if="loginModalMounted"
      :visible="showLoginModal"
      :submitting="loggingIn || registering"
      :error="loginError || registerError || sendCodeError"
      :message="loginModalMessage"
      with-identity-choice
      @close="closeLoginModal"
      @submit="handleLogin"
      @register="handleRegister"
      @send-code="handleSendCode"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, defineAsyncComponent, nextTick, onMounted, provide, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import NotificationBell from '../components/NotificationBell.vue'

const CreditsPackagesModal = defineAsyncComponent(() => import('../components/CreditsPackagesModal.vue'))
const LoginModal = defineAsyncComponent(() => import('../components/LoginModal.vue'))

import { useActiveIdentity } from '../composables/useActiveIdentity'
import { useAuth } from '../composables/useAuth'
import { useCredits } from '../composables/useCredits'
import { useGrassland } from '../composables/useGrassland'
import { useTheme, type ThemeMode } from '../composables/useTheme'
import type { LoginFormValues, LoginIdentity, RegisterFormValues } from '../types/auth'
import type { CreationEntry, CreationHandoff } from '../types/ai-creation'
import type { NotificationLinkTarget } from '../types/notification'
import type { AppView } from '../types/navigation'

type HomeFeatureView = Exclude<AppView, 'home'>
const route = useRoute()
const router = useRouter()

const currentViewName = computed<AppView>(() => (route.name as AppView) || 'home')
const creationContextEpoch = ref(0)
/**
 * 整页加载（刷新/收藏深链）时 loadCurrentUser 会让 currentUser 走一遍 null→id，
 * 与会话内「登录/换账号」无法只凭前后值区分——不区分就会把任何深链拉回主页。
 * 首次引导完成后才允许账号变化触发的回落（视觉审查 ⑱）。
 */
const sessionBootstrapped = ref(false)
const creationEntry = ref<CreationEntry | null>(null)
const creationHandoff = ref<CreationHandoff | null>(null)
let creationRevision = Date.now()
const grasslandAnchor = ref('')
const grasslandNavigationTarget = ref<NotificationLinkTarget | null>(null)
const showCreditsModal = ref(false)
const showLoginModal = ref(false)
const loginModalMounted = ref(false)
const loginModalMessage = ref('')
const authBannerMessage = ref('')

const {
  currentUser, isAuthenticated,
  loggingIn, registering, loggingOut,
  loginError, registerError, logoutError, loadError: authLoadError,
  clearLoginError, clearRegisterError, clearLogoutError,
  sendVerificationCode, clearSendCodeError, sendCodeError,
  loadCurrentUser, login, register, logout,
} = useAuth()

/** 草场域请求封装：账号菜单身份切换走它（激活经后端校验）。 */
const grassland = useGrassland()
const {
  activeSide, hasMerchantIdentity, hasRecommenderIdentity, identitiesLoaded,
  loadAccountIdentity, activateIdentitySide,
  claimActivation, releaseActivationClaim, initialActivationFinalize,
  reset: resetActiveIdentity,
} = useActiveIdentity()

/** 工作台导航标签随活动身份变化（PRD：导航、工作台与默认数据范围随活动身份切换）。 */
const workbenchTabLabel = computed(() => {
  if (hasMerchantIdentity.value && hasRecommenderIdentity.value) {
    return activeSide.value === 'merchant' ? '商家工作台' : '推荐官工作台'
  }
  if (hasRecommenderIdentity.value) return '推荐官工作台'
  return '工作台'
})

const activeSideBadge = computed(() => {
  if (!identitiesLoaded.value) return '·'
  if (hasRecommenderIdentity.value && !hasMerchantIdentity.value) return '荐'
  return activeSide.value === 'recommender' ? '荐' : '商'
})

const { currentBalance, loadBalance: loadCreditBalance } = useCredits()

const { mode: themeMode, setMode: setThemeMode } = useTheme()

function cycleTheme(): void {
  const order: ThemeMode[] = ['light', 'dark', 'system']
  const currentIndex = order.indexOf(themeMode.value)
  setThemeMode(order[(currentIndex + 1) % order.length])
}

const currentViewProps = computed<Record<string, unknown>>(() => {
  if (currentViewName.value === 'ai-center') {
    return { authenticated: isAuthenticated.value, entry: creationEntry.value }
  }
  if (creationHandoff.value?.targetView === currentViewName.value) {
    return { creationHandoff: creationHandoff.value }
  }
  return {}
})

watch(authLoadError, (message) => {
  if (message) authBannerMessage.value = message
})

onMounted(() => {
  const query = new URLSearchParams(window.location.search)
  if (query.get('view') === 'commerce' || query.has('package')) {
    // 兜底 path 判断必须覆盖 `/` 与 `/ai-center`：router 的默认路由守卫先于本 onMounted
    // 执行（e2e 实测深链曾因此静默落在草场主页/AI 中心）。
    // query 原样带上——ConsumerCommerceView 从 URL 读 package/recommender（归因参数），丢了即断链。
    if (['/', '', '/ai-center', '/home'].includes(route.path)) {
      router.replace({ name: 'commerce', query: { ...route.query } })
    }
  }
  // 任务书 #49：invite 深链已随邀请流下线移除（存量链接自然落到首页）。
  void loadCurrentUser().then(() => {
    sessionBootstrapped.value = true
    if (isAuthenticated.value) void loadCreditBalance()
  })
})

watch(isAuthenticated, (authed) => {
  if (authed) void loadCreditBalance()
})

/**
 * 账号变化（整页加载、登录、换账号、登出）：重置并按新账号装载活动身份。
 * immediate：整页加载时布局挂载可能晚于 loadCurrentUser 完成（currentUser 已就位、
 * 不再有 null→id 翻转），不 immediate 会漏装载、导航拿不到身份。
 * 主导航标签、账号区身份徽标与草场主页的角色入口都读这组全局状态。
 */
watch(() => currentUser.value?.id ?? null, (accountId, previousAccountId) => {
  if (accountId !== previousAccountId) {
    creationEntry.value = null
    creationHandoff.value = null
    if (sessionBootstrapped.value && currentViewName.value !== 'commerce') {
      router.push('/')
    }
    creationContextEpoch.value += 1
    resetActiveIdentity()
    if (accountId) void loadAccountIdentity(grassland)
  }
}, { immediate: true })



const themeToggleTitle = computed(() => {
  if (themeMode.value === 'light') return '浅色模式 — 点击切换'
  if (themeMode.value === 'dark') return '深色模式 — 点击切换'
  return '跟随系统 — 点击切换'
})

function navigateTo(view: AppView): void {
  router.push({ name: view })
}

function nextCreationRevision(): number {
  creationRevision = Math.max(creationRevision + 1, Date.now())
  return creationRevision
}

function handleOpenView(view: HomeFeatureView): void {
  router.push({ name: view })
}

function handleOpenCreation(entry: CreationEntry): void {
  creationEntry.value = { ...entry, revision: nextCreationRevision() }
  creationHandoff.value = null
  router.push({ name: 'ai-center' })
}

function handleStartWorkflow(handoff: CreationHandoff): void {
  creationEntry.value = {
    revision: handoff.revision, platformId: handoff.platformId,
    contentFormId: handoff.contentFormId,
    source: { ...handoff.source },
    prefill: handoff.prefill ? { ...handoff.prefill } : undefined,
    taskContext: handoff.taskContext,
    contextSnapshotId: handoff.contextSnapshotId,
    materialIds: handoff.materialIds ? [...handoff.materialIds] : undefined,
  }
  creationHandoff.value = {
    ...handoff, source: { ...handoff.source },
    prefill: handoff.prefill ? { ...handoff.prefill } : undefined,
  }
  router.push({ name: handoff.targetView })
}

function handleOpenGrassland(): void {
  if (!isAuthenticated.value) {
    openLoginModal('从任务创作需要先登录。')
    return
  }
  router.push({ name: 'grassland' })
}

provide('grasslandAnchor', grasslandAnchor)
provide('grasslandNavigationTarget', grasslandNavigationTarget)

/**
 * 登录时选定的进入身份（PRD：登录时区分身份，登录后不再引导选择）：
 * 未开通则先开通（商家可无组织开通，组织与资料在工作台内完善），再激活为当前活动身份。
 * 开通/激活失败不阻断登录——保留装载默认视角，用户可在账号菜单或工作台内重试。
 */
async function ensureLoginIdentity(choice: LoginIdentity): Promise<void> {
  const boot = await loadAccountIdentity(grassland)
  if (boot === null) return
  const opened = choice === 'merchant' ? hasMerchantIdentity.value : hasRecommenderIdentity.value
  if (!opened) {
    await grassland.openIdentity(choice)
    grassland.clearError()
    await loadAccountIdentity(grassland)
  }
  // 本函数是登录路径激活的唯一写入者（claimActivation 已拦下布局的默认激活）：
  // 显式激活后固化「本账号已激活」，后续任何装载不再写会话。
  const result = await activateIdentitySide(choice, grassland)
  if (result !== 'not-opened') initialActivationFinalize()
}

function handleNotificationNavigate(target: NotificationLinkTarget): void {
  router.push({ name: target.view })
  grasslandAnchor.value = target.anchor
  grasslandNavigationTarget.value = target.taskId || target.disputeId ? target : null
}

function handleOpenDispute(disputeId: string): void {
  router.push({ name: 'grassland' })
  grasslandAnchor.value = 'gl-disputes'
  grasslandNavigationTarget.value = { view: 'grassland', anchor: 'gl-disputes', disputeId }
}

function openLoginModal(message = ''): void {
  loginModalMounted.value = true
  clearLoginError(); clearRegisterError(); clearLogoutError()
  loginModalMessage.value = message
  showLoginModal.value = true
}

function closeLoginModal(): void {
  clearLoginError(); clearRegisterError()
  showLoginModal.value = false
  loginModalMessage.value = ''
}

async function handleLogin(values: LoginFormValues): Promise<void> {
  if (values.identity) claimActivation() // 须先于 login()：currentUser 一变布局 watch 即装载
  const ok = await login(values)
  if (!ok) {
    if (values.identity) releaseActivationClaim()
    return
  }
  if (values.identity) await ensureLoginIdentity(values.identity)
  closeLoginModal()
  authBannerMessage.value = values.identity
    ? `已进入${values.identity === 'merchant' ? '商家' : '推荐官'}身份；换身份请退出后重新登录。`
    : '已登录，现在可以打开设置管理你的专属配置。'
}

async function handleRegister(values: RegisterFormValues): Promise<void> {
  if (values.identity) claimActivation()
  const ok = await register(values)
  if (!ok) {
    if (values.identity) releaseActivationClaim()
    return
  }
  if (values.identity) await ensureLoginIdentity(values.identity)
  closeLoginModal()
  await nextTick()
  router.push({ name: 'grassland' })
  authBannerMessage.value = values.identity === 'merchant'
    ? '注册成功，已进入商家身份——请创建商家主体，开始发布推广任务。'
    : values.identity === 'recommender'
      ? '注册成功，已进入推荐官身份——请完善推荐官资料，到任务大厅开始接单。'
      : '注册成功。'
}

async function handleSendCode(email: string, captchaCode: string): Promise<void> {
  clearSendCodeError()
  await sendVerificationCode(email, captchaCode)
}

async function handleLogout(): Promise<void> {
  clearLogoutError()
  const ok = await logout()
  if (!ok) {
    authBannerMessage.value = logoutError.value || '退出登录失败，请稍后重试。'
    return
  }
  creationEntry.value = null
  creationHandoff.value = null
  router.push({ name: 'home' })
  authBannerMessage.value = '你已退出登录。'
}

function handleCreditsRefreshed(): void {
  void loadCreditBalance()
}

</script>

<style scoped>
.app-shell {
  position: relative;
  z-index: 1;
  width: min(1160px, calc(100% - 40px));
  margin: 0 auto;
  padding: calc(clamp(24px, 4vw, 40px) + env(safe-area-inset-top, 0px)) 0 80px;
}
.page-header { position: relative; z-index: 10; display: grid; gap: var(--space-lg); margin-bottom: var(--space-xl); }
.header-row { display: flex; align-items: center; justify-content: space-between; gap: var(--space-lg); }
.brand { display: flex; align-items: center; gap: 14px; min-width: 0; }
.brand-logo { width: 36px; height: 36px; flex-shrink: 0; filter: drop-shadow(0 0 12px color-mix(in srgb, var(--color-accent) 35%, transparent)); transition: filter 0.3s var(--ease-out); }
.brand-logo:hover { filter: drop-shadow(0 0 20px color-mix(in srgb, var(--color-accent) 55%, transparent)); }
.brand-copy { display: grid; gap: 2px; }
.brand-title { margin: 0; font-family: var(--font-display); font-size: var(--text-display); font-weight: 700; letter-spacing: -0.04em; color: var(--color-text); line-height: 1.1; }
.brand-subtitle { margin: 0; color: var(--color-text-muted); font-size: 0.84rem; line-height: 1.4; letter-spacing: 0.01em; }
.header-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; justify-content: flex-end; }
.auth-pill { display: inline-flex; align-items: center; gap: 8px; min-height: 38px; padding: 0 12px; border-radius: var(--radius-pill); border: 1px solid var(--color-border); background: var(--surface-card); }
.auth-pill-label { color: var(--color-text-muted); font-size: 0.72rem; font-weight: 600; text-transform: uppercase; letter-spacing: 0.04em; }
.auth-pill-name { color: var(--color-text); font-size: 0.84rem; font-weight: 500; }
.credits-badge { display: inline-flex; align-items: center; gap: 6px; padding: 6px 12px; border-radius: var(--radius-pill); border: none; background: linear-gradient(135deg, color-mix(in srgb, var(--color-accent) 15%, transparent), color-mix(in srgb, var(--color-accent) 10%, transparent)); color: var(--color-accent-2); font-size: 0.78rem; font-weight: 700; letter-spacing: 0.02em; cursor: pointer; transition: background var(--duration-fast) var(--ease-out), box-shadow var(--duration-fast) var(--ease-out), transform var(--duration-fast) var(--ease-out); }
.credits-badge:hover { background: linear-gradient(135deg, color-mix(in srgb, var(--color-accent) 25%, transparent), color-mix(in srgb, var(--color-accent) 18%, transparent)); box-shadow: 0 0 24px color-mix(in srgb, var(--color-accent) 20%, transparent); transform: translateY(-1px); }
.settings-trigger, .auth-trigger, .theme-toggle { display: inline-flex; align-items: center; justify-content: center; gap: var(--space-xs); min-height: 38px; padding: 0 14px; border-radius: var(--radius-md); border: 1px solid var(--color-border); background: var(--surface-card); backdrop-filter: blur(12px); -webkit-backdrop-filter: blur(12px); color: var(--color-text-secondary); cursor: pointer; font-size: 0.84rem; font-weight: 500; letter-spacing: 0.01em; transition: background var(--duration-fast) var(--ease-out), border-color var(--duration-fast) var(--ease-out), color var(--duration-fast) var(--ease-out), transform var(--duration-fast) var(--ease-out), box-shadow var(--duration-fast) var(--ease-out); }
.theme-toggle { width: 38px; padding: 0; }
.settings-trigger:hover, .auth-trigger:hover, .theme-toggle:hover { background: var(--color-surface-hover); border-color: var(--color-border-hover); color: var(--color-text); transform: translateY(-1px); box-shadow: var(--shadow-glow); }
.auth-trigger-primary { background: var(--gradient-accent); border: none; color: var(--color-on-accent); font-weight: 600; box-shadow: var(--shadow-glow); border-radius: var(--radius-pill); padding: 0 18px; }
.auth-trigger-primary:hover { box-shadow: var(--shadow-glow-strong); transform: translateY(-2px) scale(1.02); color: var(--color-on-accent); }
.auth-banner { margin: 0; padding: 12px 16px; border: 1px solid var(--color-border-accent); border-radius: var(--radius-md); background: linear-gradient(135deg, color-mix(in srgb, var(--color-accent) 6%, transparent), color-mix(in srgb, var(--color-accent) 4%, transparent)); color: var(--color-text-secondary); font-size: 0.86rem; animation: fade-in var(--duration-normal) var(--ease-out); }


.nav-tabs { position: relative; display: flex; gap: 4px; padding: 5px; border-radius: var(--radius-lg); background: rgba(255, 255, 255, 0.03); backdrop-filter: blur(24px); -webkit-backdrop-filter: blur(24px); border: 1px solid var(--color-border); width: fit-content; }
.nav-tab { display: inline-flex; align-items: center; gap: 7px; min-height: 40px; padding: 0 16px; border: none; border-radius: var(--radius-md); background: transparent; color: var(--color-text-muted); cursor: pointer; font-size: 0.86rem; font-weight: 500; white-space: nowrap; position: relative; transition: background var(--duration-fast) var(--ease-out), color var(--duration-fast) var(--ease-out); }
.nav-tab:hover { color: var(--color-text-secondary); background: color-mix(in srgb, var(--color-accent) 7%, transparent); }
.nav-tab-active { background: var(--gradient-accent); color: var(--color-on-accent); font-weight: 600; box-shadow: 0 4px 16px color-mix(in srgb, var(--color-accent) 30%, transparent); }
.nav-tab-active:hover { background: var(--gradient-accent); color: var(--color-on-accent); }
.view-area { animation: slide-up var(--duration-dramatic) var(--ease-out); }
@media (max-width: 900px) {
  .header-row { flex-direction: column; gap: var(--space-md); align-items: flex-start; }
  .header-actions { width: 100%; justify-content: flex-start; }
  .nav-tabs { width: 100%; overflow-x: auto; scrollbar-width: none; }
  .nav-tabs::-webkit-scrollbar { display: none; }
}
@media (max-width: 560px) {
  .app-shell { width: min(100%, calc(100% - 20px)); }
  .brand-logo { width: 28px; height: 28px; }
  .brand-title { font-size: 1.2rem; }
  .brand-subtitle { font-size: 0.8rem; }
  .nav-tab { padding: 0 12px; min-height: 36px; font-size: 0.82rem; }
}
</style>
