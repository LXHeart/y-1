<template>
  <div class="app-shell">
    <header class="page-header">
      <div class="header-row">
        <div class="brand">
          <div class="brand-logo" aria-hidden="true">
            <svg width="36" height="36" viewBox="0 0 36 36" fill="none" xmlns="http://www.w3.org/2000/svg">
              <defs>
                <linearGradient id="logo-grad" x1="0" y1="0" x2="36" y2="36" gradientUnits="userSpaceOnUse">
                  <stop stop-color="#8b5cf6"/>
                  <stop offset="0.5" stop-color="#6366f1"/>
                  <stop offset="1" stop-color="#4f46e5"/>
                </linearGradient>
                <linearGradient id="logo-accent" x1="0" y1="0" x2="36" y2="36" gradientUnits="userSpaceOnUse">
                  <stop stop-color="#a78bfa"/>
                  <stop offset="1" stop-color="#818cf8"/>
                </linearGradient>
              </defs>
              <rect width="36" height="36" rx="10" fill="url(#logo-grad)"/>
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

          <button v-if="isAuthenticated" class="settings-trigger" type="button" @click="handleOpenSettings">
            <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true">
              <path d="M6.5 1.5l.7 2.1a4.5 4.5 0 012.6 0l.7-2.1M3.2 3.2l1.8 1.3a4.5 4.5 0 010 2.6L3.2 8.8M6.5 14.5l.7-2.1a4.5 4.5 0 002.6 0l.7 2.1M12.8 8.8l-1.8-1.3a4.5 4.5 0 000-2.6l1.8-1.3" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/>
              <circle cx="8" cy="8" r="2" stroke="currentColor" stroke-width="1.3"/>
            </svg>
            <span>设置</span>
          </button>

          <!-- 账号菜单（PRD §一：已开通身份在账号菜单中切换，导航随活动身份变化） -->
          <div v-if="isAuthenticated && currentUser" class="account-menu" ref="accountMenuRef">
            <button
              type="button"
              class="account-trigger"
              :aria-expanded="accountMenuOpen"
              aria-haspopup="menu"
              @click="accountMenuOpen = !accountMenuOpen"
            >
              <span class="account-side-badge" :class="{ 'account-side-badge-rec': activeSide === 'recommender' }">
                {{ activeSideBadge }}
              </span>
              <strong class="account-name">{{ currentUser.displayName || currentUser.email }}</strong>
              <svg width="12" height="12" viewBox="0 0 16 16" fill="none" aria-hidden="true"
                :style="{ transform: accountMenuOpen ? 'rotate(180deg)' : 'none' }">
                <path d="M4 6l4 4 4-4" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
            </button>
            <div v-if="accountMenuOpen" class="account-dropdown" role="menu" aria-label="账号菜单">
              <p class="account-menu-label">切换活动身份</p>
              <button
                v-for="option in identityOptions"
                :key="option.side"
                type="button"
                role="menuitemradio"
                class="account-menu-item"
                :aria-checked="activeSide === option.side"
                :disabled="!option.opened || identitySwitching"
                @click="switchIdentityFromMenu(option.side)"
              >
                <span class="account-menu-item-dot" :class="{ on: activeSide === option.side }" aria-hidden="true"></span>
                {{ option.label }}
                <span v-if="!option.opened" class="account-menu-item-note">未开通</span>
                <span v-else-if="activeSide === option.side" class="account-menu-item-note">当前</span>
              </button>
              <p v-if="identitySwitchNotice" class="account-menu-notice" role="status">{{ identitySwitchNotice }}</p>
              <div class="account-menu-divider" aria-hidden="true"></div>
              <button
                type="button"
                role="menuitem"
                class="account-menu-item"
                :disabled="loggingOut"
                @click="handleLogout"
              >
                {{ loggingOut ? '退出中…' : '退出登录' }}
              </button>
            </div>
          </div>
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
          class="nav-tab"
          :class="{ 'nav-tab-active': currentViewName === 'commerce' }"
          :aria-current="currentViewName === 'commerce' ? 'page' : undefined"
          type="button"
          @click="navigateTo('commerce')"
        >
          <svg width="15" height="15" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <path d="M2 5.5h12l-1 8H3l-1-8zM4.5 5.5V4a3.5 3.5 0 017 0v1.5" stroke="currentColor" stroke-width="1.2" stroke-linejoin="round"/>
            <path d="M6 9h4" stroke="currentColor" stroke-width="1.2" stroke-linecap="round"/>
          </svg>
          到店消费
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
        <button
          v-if="isAuthenticated && (hasBackendRole('platform_admin') || hasBackendRole('customer_service'))"
          class="nav-tab"
          :class="{ 'nav-tab-active': currentViewName === 'ops' }"
          :aria-current="currentViewName === 'ops' ? 'page' : undefined"
          type="button"
          @click="navigateTo('ops')"
        >
          <svg width="15" height="15" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <path d="M2.5 3.5h11M2.5 8h11M2.5 12.5h7" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/>
            <circle cx="12.5" cy="12.5" r="1.6" stroke="currentColor" stroke-width="1.2"/>
          </svg>
          运营处置
        </button>
        <button
          v-if="isAuthenticated && (hasBackendRole('platform_admin') || hasBackendRole('content_reviewer'))"
          class="nav-tab"
          :class="{ 'nav-tab-active': currentViewName === 'admin' }"
          :aria-current="currentViewName === 'admin' ? 'page' : undefined"
          type="button"
          @click="navigateTo('admin')"
        >
          <svg width="15" height="15" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <path d="M8 1.5a6.5 6.5 0 100 13 6.5 6.5 0 000-13z" stroke="currentColor" stroke-width="1.3"/>
            <path d="M5.5 8.5h5M8 6v5" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/>
          </svg>
          管理
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

    <AnalysisSettingsModal
      v-if="settingsModalMounted"
      :visible="showSettingsModal"
      :settings="analysisSettings"
      :saving="settingsSaving"
      :error="showSettingsModal ? (settingsSaveError || settingsLoadError) : ''"
      :feature-model-states="featureModelStates"
      :homepage-settings="homepageSettings"
      :homepage-saving="homepageSaving"
      :homepage-error="showSettingsModal ? (homepageSaveError || homepageLoadError) : ''"
      @close="showSettingsModal = false"
      @save="handleSaveSettings"
      @fetch-models="handleFetchModels"
      @verify-model="handleVerifyModel"
    />

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
      @close="closeLoginModal"
      @submit="handleLogin"
      @register="handleRegister"
      @send-code="handleSendCode"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, defineAsyncComponent, nextTick, onBeforeUnmount, onMounted, provide, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import NotificationBell from '../components/NotificationBell.vue'

const AnalysisSettingsModal = defineAsyncComponent(() => import('../components/AnalysisSettingsModal.vue'))
const CreditsPackagesModal = defineAsyncComponent(() => import('../components/CreditsPackagesModal.vue'))
const LoginModal = defineAsyncComponent(() => import('../components/LoginModal.vue'))

import { useActiveIdentity, type IdentitySide } from '../composables/useActiveIdentity'
import { useAnalysisSettings } from '../composables/useAnalysisSettings'
import { useAuth } from '../composables/useAuth'
import { useCredits } from '../composables/useCredits'
import { useGrassland } from '../composables/useGrassland'
import { useHomepageSettings } from '../composables/useHomepageSettings'
import { useTheme, type ThemeMode } from '../composables/useTheme'
import type { LoginFormValues, RegisterFormValues } from '../types/auth'
import type { CreationEntry, CreationHandoff } from '../types/ai-creation'
import type { NotificationLinkTarget } from '../types/notification'
import type { AppView } from '../types/navigation'
import type { AnalysisFeature, AnalysisProvider, AnalysisSettings, HomepageSettings } from '../types/settings'

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
const showSettingsModal = ref(false)
const showCreditsModal = ref(false)
const showLoginModal = ref(false)
const settingsModalMounted = ref(false)
const loginModalMounted = ref(false)
const loginModalMessage = ref('')
const authBannerMessage = ref('')

const {
  currentUser, isAuthenticated, hasBackendRole,
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
  loadAccountIdentity, activateIdentitySide, reset: resetActiveIdentity,
} = useActiveIdentity()

const accountMenuOpen = ref(false)
const accountMenuRef = ref<HTMLElement | null>(null)
const identitySwitching = ref(false)
const identitySwitchNotice = ref('')

const identityOptions = computed<ReadonlyArray<{ side: IdentitySide; label: string; opened: boolean }>>(() => [
  { side: 'merchant', label: '商家身份', opened: hasMerchantIdentity.value },
  { side: 'recommender', label: '推荐官身份', opened: hasRecommenderIdentity.value },
])

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

const {
  settings: analysisSettings, loaded: settingsLoaded,
  saving: settingsSaving, error: settingsLoadError, saveError: settingsSaveError,
  loadSettings, saveSettings, featureModelStates,
  fetchModels: fetchModelsAction, verifyModel: verifyModelAction, clearModelState,
} = useAnalysisSettings()

const {
  settings: homepageSettings, loaded: homepageSettingsLoaded,
  saving: homepageSaving, error: homepageLoadError, saveError: homepageSaveError,
  loadSettings: loadHomepageSettingsAction, saveSettings: saveHomepageSettingsAction,
} = useHomepageSettings()

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

watch(showSettingsModal, (visible) => {
  if (!visible) { clearModelState(); return }
  if (!settingsLoaded.value) void loadSettings()
  if (!homepageSettingsLoaded.value) void loadHomepageSettingsAction()
}, { immediate: true })

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
  // 邀请直达深链：邮件/口头转发的站内链接（不携带邀请 id——被邀请人登录后按自己邮箱看到全部待接受邀请，
  // 带裸 id 反而会成为存在性探测面）。落到草场工作台的「我的邀请」卡片。
  if (query.has('invite')) {
    router.push({ name: 'grassland' })
    grasslandAnchor.value = 'gl-invitations'
  }
  void loadCurrentUser().then(() => {
    sessionBootstrapped.value = true
    if (isAuthenticated.value) void loadCreditBalance()
  })
  document.addEventListener('pointerdown', onDocumentPointerDown)
})

onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', onDocumentPointerDown)
})

function onDocumentPointerDown(event: PointerEvent): void {
  if (!accountMenuOpen.value) return
  if (accountMenuRef.value?.contains(event.target as Node)) return
  accountMenuOpen.value = false
  identitySwitchNotice.value = ''
}

watch(isAuthenticated, (authed) => {
  if (authed) void loadCreditBalance()
})

/**
 * 账号变化（整页加载、登录、换账号、登出）：重置并按新账号装载活动身份。
 * immediate：整页加载时布局挂载可能晚于 loadCurrentUser 完成（currentUser 已就位、
 * 不再有 null→id 翻转），不 immediate 会漏装载、导航拿不到身份。
 * 主导航标签、账号菜单与草场主页的角色入口都读这组全局状态。
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
    accountMenuOpen.value = false
    identitySwitchNotice.value = ''
    if (accountId) void loadAccountIdentity(grassland)
  }
}, { immediate: true })

watch(currentViewName, () => {
  accountMenuOpen.value = false
  identitySwitchNotice.value = ''
})

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

/** 账号菜单切换活动身份：仅已开通身份；失败展示后端错误，成功即关菜单（导航标签随之更新）。 */
async function switchIdentityFromMenu(side: IdentitySide): Promise<void> {
  if (identitySwitching.value || activeSide.value === side) {
    accountMenuOpen.value = false
    return
  }
  identitySwitching.value = true
  identitySwitchNotice.value = ''
  const result = await activateIdentitySide(side, grassland)
  identitySwitching.value = false
  if (result === 'ok') {
    accountMenuOpen.value = false
    return
  }
  identitySwitchNotice.value = result === 'not-opened'
    ? '该身份尚未开通：进入工作台完成开通后再切换。'
    : (grassland.error.value || '切换失败，请稍后重试。')
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

function handleSaveSettings(newSettings: AnalysisSettings, newHomepageSettings: HomepageSettings): void {
  Promise.all([saveSettings(newSettings), saveHomepageSettingsAction(newHomepageSettings)]).then(([a, b]) => {
    if (a && b) showSettingsModal.value = false
  })
}

function handleFetchModels(feature: AnalysisFeature, provider: AnalysisProvider | undefined, settings: AnalysisSettings): void {
  void fetchModelsAction(feature, provider, settings)
}

function handleVerifyModel(feature: AnalysisFeature, provider: AnalysisProvider | undefined, model: string, settings: AnalysisSettings): void {
  void verifyModelAction(feature, provider, model, settings)
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
  const ok = await login(values)
  if (!ok) return
  closeLoginModal()
  authBannerMessage.value = '已登录，现在可以打开设置管理你的专属配置。'
}

async function handleRegister(values: RegisterFormValues): Promise<void> {
  const ok = await register(values)
  if (!ok) return
  closeLoginModal()
  await nextTick()
  router.push({ name: 'grassland' })
  authBannerMessage.value = values.initialIdentity === 'merchant'
    ? '注册成功，请先创建商家主体并完善入驻资料。'
    : '注册成功，请先完善推荐官主页资料。'
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
  showSettingsModal.value = false
  creationEntry.value = null
  creationHandoff.value = null
  accountMenuOpen.value = false
  router.push({ name: 'home' })
  authBannerMessage.value = '你已退出登录。'
}

function handleCreditsRefreshed(): void {
  void loadCreditBalance()
}

async function handleOpenSettings(): Promise<void> {
  const authOk = await loadCurrentUser(true)
  if (!authOk && authLoadError.value) {
    authBannerMessage.value = authLoadError.value
    return
  }
  if (!isAuthenticated.value) {
    openLoginModal('设置、模型和密钥已改为按账号隔离保存，请先登录。')
    return
  }
  authBannerMessage.value = ''
  await Promise.all([
    settingsLoaded.value ? Promise.resolve() : loadSettings(),
    homepageSettingsLoaded.value ? Promise.resolve() : loadHomepageSettingsAction(),
  ])
  settingsModalMounted.value = true
  showSettingsModal.value = true
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
.brand-logo { width: 36px; height: 36px; flex-shrink: 0; filter: drop-shadow(0 0 12px rgba(139, 92, 246, 0.4)); transition: filter 0.3s var(--ease-out); }
.brand-logo:hover { filter: drop-shadow(0 0 20px rgba(139, 92, 246, 0.6)); }
.brand-copy { display: grid; gap: 2px; }
.brand-title { margin: 0; font-size: var(--text-display); font-weight: 800; letter-spacing: -0.04em; color: var(--color-text); line-height: 1.1; }
.brand-subtitle { margin: 0; color: var(--color-text-muted); font-size: 0.84rem; line-height: 1.4; letter-spacing: 0.01em; }
.header-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; justify-content: flex-end; }
.credits-badge { display: inline-flex; align-items: center; gap: 6px; padding: 6px 12px; border-radius: 999px; border: none; background: linear-gradient(135deg, rgba(139, 92, 246, 0.15), rgba(99, 102, 241, 0.1)); color: var(--color-accent-2); font-size: 0.78rem; font-weight: 700; letter-spacing: 0.02em; cursor: pointer; transition: background var(--duration-fast) var(--ease-out), box-shadow var(--duration-fast) var(--ease-out), transform var(--duration-fast) var(--ease-out); }
.credits-badge:hover { background: linear-gradient(135deg, rgba(139, 92, 246, 0.25), rgba(99, 102, 241, 0.18)); box-shadow: 0 0 24px rgba(139, 92, 246, 0.2); transform: translateY(-1px); }
.settings-trigger, .auth-trigger, .theme-toggle { display: inline-flex; align-items: center; justify-content: center; gap: var(--space-xs); min-height: 38px; padding: 0 14px; border-radius: 12px; border: 1px solid var(--color-border); background: var(--surface-card); backdrop-filter: blur(12px); -webkit-backdrop-filter: blur(12px); color: var(--color-text-secondary); cursor: pointer; font-size: 0.84rem; font-weight: 500; letter-spacing: 0.01em; transition: background var(--duration-fast) var(--ease-out), border-color var(--duration-fast) var(--ease-out), color var(--duration-fast) var(--ease-out), transform var(--duration-fast) var(--ease-out), box-shadow var(--duration-fast) var(--ease-out); }
.theme-toggle { width: 38px; padding: 0; }
.settings-trigger:hover, .auth-trigger:hover, .theme-toggle:hover { background: var(--color-surface-hover); border-color: var(--color-border-hover); color: var(--color-text); transform: translateY(-1px); box-shadow: var(--shadow-glow); }
.auth-trigger-primary { background: var(--gradient-accent); border: none; color: #ffffff; font-weight: 600; box-shadow: var(--shadow-glow); }
.auth-trigger-primary:hover { box-shadow: var(--shadow-glow-strong); transform: translateY(-2px) scale(1.02); color: #ffffff; }
.auth-banner { margin: 0; padding: 12px 16px; border: 1px solid var(--color-border-accent); border-radius: 14px; background: linear-gradient(135deg, rgba(139, 92, 246, 0.06), rgba(99, 102, 241, 0.04)); color: var(--color-text-secondary); font-size: 0.86rem; animation: fade-in var(--duration-normal) var(--ease-out); }

/* 账号菜单（身份切换 + 退出登录） */
.account-menu { position: relative; }
.account-trigger { display: inline-flex; align-items: center; gap: 8px; min-height: 38px; max-width: 260px; padding: 0 12px; border-radius: 999px; border: 1px solid var(--color-border); background: var(--surface-card); backdrop-filter: blur(12px); -webkit-backdrop-filter: blur(12px); color: var(--color-text); cursor: pointer; transition: border-color var(--duration-fast) var(--ease-out), background var(--duration-fast) var(--ease-out); }
.account-trigger:hover { border-color: var(--color-border-hover); background: var(--color-surface-hover); }
.account-side-badge { flex-shrink: 0; display: inline-flex; align-items: center; justify-content: center; width: 22px; height: 22px; border-radius: 50%; background: rgba(139, 92, 246, 0.16); color: var(--color-accent-2); font-size: 0.72rem; font-weight: 700; }
.account-side-badge-rec { background: rgba(16, 185, 129, 0.16); color: #059669; }
.account-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 0.84rem; font-weight: 600; }
.account-dropdown { position: absolute; z-index: 30; top: calc(100% + 8px); right: 0; display: grid; gap: 2px; min-width: 240px; padding: 8px; border: 1px solid var(--color-border); border-radius: 14px; background: var(--color-surface); box-shadow: var(--shadow-elevated); }
.account-menu-label { margin: 2px 8px 6px; font-size: 0.72rem; font-weight: 600; text-transform: uppercase; letter-spacing: 0.05em; color: var(--color-text-muted); }
.account-menu-item { display: flex; align-items: center; gap: 10px; width: 100%; min-height: 38px; padding: 0 10px; border: none; border-radius: 10px; background: transparent; color: var(--color-text); font-size: 0.86rem; text-align: left; cursor: pointer; transition: background var(--duration-fast) var(--ease-out); }
.account-menu-item:hover:not(:disabled) { background: var(--color-surface-hover); }
.account-menu-item:disabled { opacity: 0.45; cursor: not-allowed; }
.account-menu-item-dot { width: 8px; height: 8px; border-radius: 50%; border: 1.5px solid var(--color-text-muted); flex-shrink: 0; }
.account-menu-item-dot.on { border-color: var(--color-accent-2); background: var(--color-accent-2); }
.account-menu-item-note { margin-left: auto; font-size: 0.72rem; color: var(--color-text-muted); }
.account-menu-notice { margin: 4px 8px; padding: 8px 10px; border-radius: 8px; background: color-mix(in srgb, var(--color-danger) 8%, transparent); color: var(--color-danger); font-size: 0.78rem; line-height: 1.5; }
.account-menu-divider { height: 1px; margin: 6px 4px; background: var(--color-border); }

.nav-tabs { position: relative; display: flex; gap: 4px; padding: 5px; border-radius: 16px; background: rgba(255, 255, 255, 0.03); backdrop-filter: blur(24px); -webkit-backdrop-filter: blur(24px); border: 1px solid var(--color-border); width: fit-content; }
.nav-tab { display: inline-flex; align-items: center; gap: 7px; min-height: 40px; padding: 0 16px; border: none; border-radius: 12px; background: transparent; color: var(--color-text-muted); cursor: pointer; font-size: 0.86rem; font-weight: 500; white-space: nowrap; position: relative; transition: background var(--duration-fast) var(--ease-out), color var(--duration-fast) var(--ease-out); }
.nav-tab:hover { color: var(--color-text-secondary); background: rgba(139, 92, 246, 0.06); }
.nav-tab-active { background: var(--gradient-accent); color: #ffffff; font-weight: 600; box-shadow: 0 4px 16px rgba(139, 92, 246, 0.3); }
.nav-tab-active:hover { background: var(--gradient-accent); color: #ffffff; }
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
