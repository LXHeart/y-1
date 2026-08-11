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
              <!-- 主背景圆角矩形 -->
              <rect width="36" height="36" rx="10" fill="url(#logo-grad)"/>
              <!-- 视频播放三角 -->
              <path d="M11 10.5C11 9.67 11.67 9 12.5 9C12.9 9 13.27 9.16 13.53 9.43L23.53 18.43C24.15 19 24.15 19.97 23.53 20.54C23.27 20.78 22.93 20.91 22.57 20.91H12.5C11.67 20.91 11 20.24 11 19.41V10.5Z" fill="rgba(255,255,255,0.95)"/>
              <!-- 文字/文章图标 -->
              <rect x="11" y="23" width="14" height="1.8" rx="0.9" fill="rgba(255,255,255,0.5)"/>
              <rect x="11" y="26.2" width="9" height="1.8" rx="0.9" fill="rgba(255,255,255,0.35)"/>
              <!-- 装饰圆点 -->
              <circle cx="27.5" cy="11.5" r="2.8" fill="url(#logo-accent)" opacity="0.7"/>
            </svg>
          </div>
          <div class="brand-copy">
            <h1 class="brand-title">AI 内容创作中心</h1>
            <p class="brand-subtitle">按发布平台组织任务、门店与热点创作</p>
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

          <div v-if="isAuthenticated && currentUser" class="auth-pill" aria-live="polite">
            <span class="auth-pill-label">已登录</span>
            <strong class="auth-pill-name">{{ currentUser.displayName || currentUser.email }}</strong>
          </div>

          <NotificationBell v-if="isAuthenticated" @navigate="handleNotificationNavigate" />

          <span
            v-if="isAuthenticated"
            class="credits-badge"
          >
            <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
              <circle cx="8" cy="8" r="6.5" stroke="currentColor" stroke-width="1.3"/>
              <path d="M8 4.5v7M5.5 7.5h5" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/>
            </svg>
            {{ currentBalance }} 次
          </span>

          <button v-if="isAuthenticated" class="settings-trigger" type="button" @click="handleOpenSettings">
            <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true">
              <path d="M6.5 1.5l.7 2.1a4.5 4.5 0 012.6 0l.7-2.1M3.2 3.2l1.8 1.3a4.5 4.5 0 010 2.6L3.2 8.8M6.5 14.5l.7-2.1a4.5 4.5 0 002.6 0l.7 2.1M12.8 8.8l-1.8-1.3a4.5 4.5 0 000-2.6l1.8-1.3" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/>
              <circle cx="8" cy="8" r="2" stroke="currentColor" stroke-width="1.3"/>
            </svg>
            <span>设置</span>
          </button>

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
          :class="{ 'nav-tab-active': currentView === 'ai-center' }"
          :aria-current="currentView === 'ai-center' ? 'page' : undefined"
          type="button"
          @click="currentView = 'ai-center'"
        >
          <svg width="15" height="15" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <path d="M8 1.5l1.2 3.3L12.5 6 9.2 7.2 8 10.5 6.8 7.2 3.5 6l3.3-1.2L8 1.5z" stroke="currentColor" stroke-width="1.2" stroke-linejoin="round"/>
            <path d="M12.5 10l.6 1.6 1.4.5-1.4.6-.6 1.8-.6-1.8-1.4-.6 1.4-.5.6-1.6z" fill="currentColor"/>
          </svg>
          AI 内容创作中心
        </button>
        <button
          class="nav-tab"
          :class="{ 'nav-tab-active': legacyViews.includes(currentView) }"
          type="button"
          :aria-expanded="showLegacyTools"
          aria-controls="legacy-tools-panel"
          @click="showLegacyTools = !showLegacyTools"
        >
          <svg width="15" height="15" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <path d="M3 3h4v4H3V3zm6 0h4v4H9V3zM3 9h4v4H3V9zm6 0h4v4H9V9z" stroke="currentColor" stroke-width="1.2"/>
          </svg>
          更多工具
        </button>
        <div v-if="showLegacyTools" id="legacy-tools-panel" class="legacy-tools-menu" role="group" aria-label="独立工具">
        <button
          class="nav-tab"
          :class="{ 'nav-tab-active': currentView === 'home' }"
          :aria-current="currentView === 'home' ? 'page' : undefined"
          type="button"
          @click="currentView = 'home'"
        >
          <svg width="15" height="15" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <path d="M2 7.2L8 2l6 5.2v6.1a1.2 1.2 0 01-1.2 1.2H9.7V10H6.3v4.5H3.2A1.2 1.2 0 012 13.3V7.2z" stroke="currentColor" stroke-width="1.3" stroke-linejoin="round"/>
          </svg>
          首页
        </button>
        <button
          class="nav-tab"
          :class="{ 'nav-tab-active': currentView === 'video' }"
          :aria-current="currentView === 'video' ? 'page' : undefined"
          type="button"
          @click="currentView = 'video'"
        >
          <svg width="15" height="15" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <rect x="1.5" y="3" width="13" height="10" rx="2" stroke="currentColor" stroke-width="1.3"/>
            <path d="M6.5 6.5l3.5 1.5-3.5 1.5V6.5z" fill="currentColor"/>
          </svg>
          视频参考提取
        </button>
        <button
          class="nav-tab"
          :class="{ 'nav-tab-active': currentView === 'image' }"
          :aria-current="currentView === 'image' ? 'page' : undefined"
          type="button"
          @click="currentView = 'image'"
        >
          <svg width="15" height="15" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <rect x="2" y="2.5" width="12" height="11" rx="2" stroke="currentColor" stroke-width="1.3"/>
            <circle cx="5.5" cy="6" r="1.5" stroke="currentColor" stroke-width="1.2"/>
            <path d="M2 11l3-3 2 2 2.5-2.5L14 11" stroke="currentColor" stroke-width="1.2" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
          图片评价文案
        </button>
        <button
          class="nav-tab"
          :class="{ 'nav-tab-active': currentView === 'article' }"
          :aria-current="currentView === 'article' ? 'page' : undefined"
          type="button"
          @click="currentView = 'article'"
        >
          <svg width="15" height="15" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <path d="M2.5 2h7.5l3 3v8.5a1.5 1.5 0 01-1.5 1.5h-9A1.5 1.5 0 011 13.5v-10A1.5 1.5 0 012.5 2z" stroke="currentColor" stroke-width="1.3"/>
            <path d="M5 7.5h6M5 10h4" stroke="currentColor" stroke-width="1.2" stroke-linecap="round"/>
          </svg>
          爆款文章
        </button>
        <button
          class="nav-tab"
          :class="{ 'nav-tab-active': currentView === 'image-gen' }"
          :aria-current="currentView === 'image-gen' ? 'page' : undefined"
          type="button"
          @click="currentView = 'image-gen'"
        >
          <svg width="15" height="15" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <path d="M5.5 2h5a1 1 0 011 1v1.5H13a1 1 0 011 1V13a1 1 0 01-1 1H3a1 1 0 01-1-1V5.5a1 1 0 011-1h1.5V3a1 1 0 011-1z" stroke="currentColor" stroke-width="1.3" stroke-linejoin="round"/>
            <circle cx="8" cy="8.5" r="2" stroke="currentColor" stroke-width="1.2"/>
            <path d="M5.5 2v2.5M10.5 2v2.5" stroke="currentColor" stroke-width="1.2" stroke-linecap="round"/>
          </svg>
          图片生成
        </button>
        <button
          class="nav-tab"
          :class="{ 'nav-tab-active': currentView === 'comedy' }"
          :aria-current="currentView === 'comedy' ? 'page' : undefined"
          type="button"
          @click="currentView = 'comedy'"
        >
          <svg width="15" height="15" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <path d="M8 1.5a4.5 4.5 0 00-1 8.9V12a1 1 0 001 0V10.4a4.5 4.5 0 00-0-8.9z" stroke="currentColor" stroke-width="1.2"/>
            <path d="M5 13.5h6M6.5 15h3" stroke="currentColor" stroke-width="1.2" stroke-linecap="round"/>
          </svg>
          脱口秀创作
        </button>
        <button
          class="nav-tab"
          :class="{ 'nav-tab-active': currentView === 'video-production' }"
          :aria-current="currentView === 'video-production' ? 'page' : undefined"
          type="button"
          @click="currentView = 'video-production'"
        >
          <svg width="15" height="15" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <path d="M2 4.5a2 2 0 012-2h8a2 2 0 012 2v5a2 2 0 01-2 2H4a2 2 0 01-2-2v-5z" stroke="currentColor" stroke-width="1.2"/>
            <path d="M6 14h4M8 11.5V14" stroke="currentColor" stroke-width="1.2" stroke-linecap="round"/>
            <circle cx="8" cy="7" r="1.5" stroke="currentColor" stroke-width="1"/>
          </svg>
          视频制作
        </button>
        </div>
        <button
          class="nav-tab"
          :class="{ 'nav-tab-active': currentView === 'commerce' }"
          :aria-current="currentView === 'commerce' ? 'page' : undefined"
          type="button"
          @click="currentView = 'commerce'"
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
          :class="{ 'nav-tab-active': currentView === 'grassland' }"
          :aria-current="currentView === 'grassland' ? 'page' : undefined"
          type="button"
          @click="currentView = 'grassland'"
        >
          <svg width="15" height="15" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <path d="M2 13V6.5l6-4 6 4V13" stroke="currentColor" stroke-width="1.3" stroke-linejoin="round"/>
            <path d="M6 13V9h4v4" stroke="currentColor" stroke-width="1.2" stroke-linejoin="round"/>
          </svg>
          草场
        </button>
        <button
          v-if="isAuthenticated && (hasBackendRole('platform_admin') || hasBackendRole('customer_service'))"
          class="nav-tab"
          :class="{ 'nav-tab-active': currentView === 'ops' }"
          :aria-current="currentView === 'ops' ? 'page' : undefined"
          type="button"
          @click="currentView = 'ops'"
        >
          <svg width="15" height="15" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <path d="M2.5 3.5h11M2.5 8h11M2.5 12.5h7" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/>
            <circle cx="12.5" cy="12.5" r="1.6" stroke="currentColor" stroke-width="1.2"/>
          </svg>
          运营处置
        </button>
        <button
          v-if="isAuthenticated && hasBackendRole('platform_admin')"
          class="nav-tab"
          :class="{ 'nav-tab-active': currentView === 'admin' }"
          :aria-current="currentView === 'admin' ? 'page' : undefined"
          type="button"
          @click="currentView = 'admin'"
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
      <KeepAlive :key="creationContextEpoch">
        <component
          :is="currentViewComponent"
          v-bind="currentViewProps"
          @open-view="handleOpenView"
          @create-article="handleCreateArticleFromTopic"
          @create-comedy="handleCreateComedyFromTopic"
          @open-creation="handleOpenCreation"
          @start-workflow="handleStartWorkflow"
          @request-login="openLoginModal('任务和门店资料按账号隔离，请先登录。')"
          @open-grassland="handleOpenGrassland"
        />
      </KeepAlive>
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
import { computed, defineAsyncComponent, nextTick, onMounted, provide, ref, watch, type Component } from 'vue'
import AiCreationCenter from './components/AiCreationCenter.vue'
import NotificationBell from './components/NotificationBell.vue'

// 非首屏视图与弹窗按需加载（代码分割），默认视图 AiCreationCenter 保留在主 chunk 避免首屏延迟
const AnalysisSettingsModal = defineAsyncComponent(() => import('./components/AnalysisSettingsModal.vue'))
const ArticleCreationView = defineAsyncComponent(() => import('./components/ArticleCreationView.vue'))
const ComedyWritingView = defineAsyncComponent(() => import('./components/ComedyWritingView.vue'))
const AdminView = defineAsyncComponent(() => import('./components/AdminView.vue'))
const OpsConsole = defineAsyncComponent(() => import('./components/OpsConsole.vue'))
const GrasslandWorkbench = defineAsyncComponent(() => import('./components/GrasslandWorkbench.vue'))
const ConsumerCommerceView = defineAsyncComponent(() => import('./components/ConsumerCommerceView.vue'))
const HomeView = defineAsyncComponent(() => import('./components/HomeView.vue'))
const ImageAnalysisView = defineAsyncComponent(() => import('./components/ImageAnalysisView.vue'))
const ImageGenerationView = defineAsyncComponent(() => import('./components/ImageGenerationView.vue'))
const LoginModal = defineAsyncComponent(() => import('./components/LoginModal.vue'))
const VideoAnalysisView = defineAsyncComponent(() => import('./components/VideoAnalysisView.vue'))
const VideoProductionView = defineAsyncComponent(() => import('./components/VideoProductionView.vue'))
import { useAnalysisSettings } from './composables/useAnalysisSettings'
import { useAuth } from './composables/useAuth'
import { useHomepageSettings } from './composables/useHomepageSettings'
import { useTheme, type ThemeMode } from './composables/useTheme'
import { useCredits } from './composables/useCredits'
import type { LoginFormValues, RegisterFormValues } from './types/auth'
import type { CreationEntry, CreationHandoff } from './types/ai-creation'
import type { NotificationLinkTarget } from './types/notification'
import type { AnalysisFeature, AnalysisProvider, AnalysisSettings, HomepageSettings } from './types/settings'

type AppView = 'ai-center' | 'home' | 'video' | 'image' | 'article' | 'image-gen' | 'comedy' | 'video-production' | 'commerce' | 'grassland' | 'ops' | 'admin'
type HomeFeatureView = Exclude<AppView, 'home'>

const initialQuery = new URLSearchParams(window.location.search)
const currentView = ref<AppView>(
  initialQuery.get('view') === 'commerce' || initialQuery.has('package') ? 'commerce' : 'ai-center')
const creationContextEpoch = ref(0)
const showLegacyTools = ref(false)
const legacyViews: readonly AppView[] = ['home', 'video', 'image', 'article', 'image-gen', 'comedy', 'video-production']
const creationEntry = ref<CreationEntry | null>(null)
const creationHandoff = ref<CreationHandoff | null>(null)
let creationRevision = Date.now()
/** 通知点击后要滚到的草场卡片锚点；工作台消费后置空（见 handleNotificationNavigate）。 */
const grasslandAnchor = ref('')
const articleInitialTopic = ref('')
const comedyInitialTopic = ref('')
const showSettingsModal = ref(false)
const showLoginModal = ref(false)
// 弹窗按需挂载（配合 defineAsyncComponent 分包）；首次打开后保持挂载以保留内部状态
const settingsModalMounted = ref(false)
const loginModalMounted = ref(false)
const loginModalMessage = ref('')
const authBannerMessage = ref('')

const {
  currentUser,
  isAuthenticated,
  hasBackendRole,
  loaded: authLoaded,
  loggingIn,
  registering,
  loggingOut,
  loginError,
  registerError,
  logoutError,
  loadError: authLoadError,
  clearLoginError,
  clearRegisterError,
  clearLogoutError,
  sendVerificationCode,
  clearSendCodeError,
  sendCodeError,
  loadCurrentUser,
  login,
  register,
  logout,
} = useAuth()

const {
  currentBalance,
  loadBalance: loadCreditBalance,
} = useCredits()

const {
  settings: analysisSettings,
  loaded: settingsLoaded,
  saving: settingsSaving,
  error: settingsLoadError,
  saveError: settingsSaveError,
  loadSettings,
  saveSettings,
  featureModelStates,
  fetchModels: fetchModelsAction,
  verifyModel: verifyModelAction,
  clearModelState,
} = useAnalysisSettings()

const {
  settings: homepageSettings,
  loaded: homepageSettingsLoaded,
  saving: homepageSaving,
  error: homepageLoadError,
  saveError: homepageSaveError,
  loadSettings: loadHomepageSettingsAction,
  saveSettings: saveHomepageSettingsAction,
} = useHomepageSettings()

const { mode: themeMode, resolvedTheme, setMode: setThemeMode } = useTheme()

function cycleTheme(): void {
  const order: ThemeMode[] = ['light', 'dark', 'system']
  const currentIndex = order.indexOf(themeMode.value)
  setThemeMode(order[(currentIndex + 1) % order.length])
}

watch(showSettingsModal, (visible) => {
  if (!visible) {
    clearModelState()
    return
  }

  if (!settingsLoaded.value) {
    void loadSettings()
  }

  if (!homepageSettingsLoaded.value) {
    void loadHomepageSettingsAction()
  }
}, { immediate: true })

watch(authLoadError, (message) => {
  if (!message) {
    return
  }

  authBannerMessage.value = message
})

onMounted(() => {
  void loadCurrentUser().then(() => {
    if (isAuthenticated.value) void loadCreditBalance()
  })
})

watch(isAuthenticated, (authed) => {
  if (authed) void loadCreditBalance()
})

watch(() => currentUser.value?.id ?? null, (accountId, previousAccountId) => {
  if (accountId !== previousAccountId) {
    creationEntry.value = null
    creationHandoff.value = null
    if (!(initialQuery.get('view') === 'commerce' || initialQuery.has('package'))) {
      currentView.value = 'ai-center'
    }
    creationContextEpoch.value += 1
  }
})

async function handleSaveSettings(newSettings: AnalysisSettings, newHomepageSettings: HomepageSettings): Promise<void> {
  const [analysisOk, homepageOk] = await Promise.all([
    saveSettings(newSettings),
    saveHomepageSettingsAction(newHomepageSettings),
  ])

  if (analysisOk && homepageOk) {
    showSettingsModal.value = false
  }
}

function handleFetchModels(feature: AnalysisFeature, provider: AnalysisProvider | undefined, settings: AnalysisSettings): void {
  void fetchModelsAction(feature, provider, settings)
}

const viewComponentMap: Record<AppView, Component> = {
  'ai-center': AiCreationCenter,
  home: HomeView,
  video: VideoAnalysisView,
  image: ImageAnalysisView,
  article: ArticleCreationView,
  'image-gen': ImageGenerationView,
  comedy: ComedyWritingView,
  'video-production': VideoProductionView,
  commerce: ConsumerCommerceView,
  grassland: GrasslandWorkbench,
  ops: OpsConsole,
  admin: AdminView,
}

const currentViewComponent = computed(() => viewComponentMap[currentView.value])
const currentViewProps = computed<Record<string, unknown>>(() => {
  if (currentView.value === 'ai-center') {
    return { authenticated: isAuthenticated.value, entry: creationEntry.value }
  }
  if (creationHandoff.value?.targetView === currentView.value) {
    return { creationHandoff: creationHandoff.value }
  }
  return {}
})

watch(currentView, () => {
  showLegacyTools.value = false
})

const themeToggleTitle = computed(() => {
  if (themeMode.value === 'light') return '浅色模式 — 点击切换'
  if (themeMode.value === 'dark') return '深色模式 — 点击切换'
  return '跟随系统 — 点击切换'
})

function handleOpenView(view: HomeFeatureView): void {
  currentView.value = view
}

function nextCreationRevision(): number {
  creationRevision = Math.max(creationRevision + 1, Date.now())
  return creationRevision
}

function handleOpenCreation(entry: CreationEntry): void {
  creationEntry.value = { ...entry, revision: nextCreationRevision() }
  creationHandoff.value = null
  currentView.value = 'ai-center'
}

function handleStartWorkflow(handoff: CreationHandoff): void {
  creationEntry.value = {
    revision: handoff.revision,
    platformId: handoff.platformId,
    contentFormId: handoff.contentFormId,
    source: { ...handoff.source },
    prefill: handoff.prefill ? { ...handoff.prefill } : undefined,
  }
  creationHandoff.value = {
    ...handoff,
    source: { ...handoff.source },
    prefill: handoff.prefill ? { ...handoff.prefill } : undefined,
  }
  currentView.value = handoff.targetView
}

function handleOpenGrassland(): void {
  if (!isAuthenticated.value) {
    openLoginModal('从任务创作需要先登录。')
    return
  }
  currentView.value = 'grassland'
}

function handleCreateArticleFromTopic(topic: string): void {
  articleInitialTopic.value = topic
  currentView.value = 'article'
}

function handleCreateComedyFromTopic(topic: string): void {
  comedyInitialTopic.value = topic
  currentView.value = 'comedy'
}

provide('articleInitialTopic', articleInitialTopic)
provide('comedyInitialTopic', comedyInitialTopic)
provide('grasslandAnchor', grasslandAnchor)

/**
 * 通知点击落点（草场 Slice 12 Stage 4）。
 *
 * 本应用无 vue-router，故后端的 `linkPath` 由前端映射成「视图 + 卡片锚点」（见
 * `types/notification.ts`）。这里只切视图并把锚点交给工作台——**刻意不替用户切换
 * 商家/推荐官视角**，`switchSide()` 会重置组织/任务/争议选择，等于清掉他手上的活。
 * 锚点用 `provide` 而非 prop：`<component :is>` 在 KeepAlive 下对所有视图共用一组属性，
 * 挂 prop 会给其它 8 个视图落成无效 DOM 属性。
 */
function handleNotificationNavigate(target: NotificationLinkTarget): void {
  currentView.value = target.view
  grasslandAnchor.value = target.anchor
}

function handleVerifyModel(
  feature: AnalysisFeature,
  provider: AnalysisProvider | undefined,
  model: string,
  settings: AnalysisSettings,
): void {
  void verifyModelAction(feature, provider, model, settings)
}

function openLoginModal(message = ''): void {
  loginModalMounted.value = true
  clearLoginError()
  clearRegisterError()
  clearLogoutError()
  loginModalMessage.value = message
  showLoginModal.value = true
}

function closeLoginModal(): void {
  clearLoginError()
  clearRegisterError()
  showLoginModal.value = false
  loginModalMessage.value = ''
}

async function handleLogin(values: LoginFormValues): Promise<void> {
  const ok = await login(values)
  if (!ok) {
    return
  }

  closeLoginModal()
  authBannerMessage.value = '已登录，现在可以打开设置管理你的专属配置。'
}

async function handleRegister(values: RegisterFormValues): Promise<void> {
  const ok = await register(values)
  if (!ok) {
    return
  }

  closeLoginModal()
  // currentUser 变更会触发账号级缓存清理并重置到 AI 首页；先等该 watcher 完成，
  // 再进入初始身份工作台，避免首次资料完善落点被异步覆盖。
  await nextTick()
  currentView.value = 'grassland'
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
  currentView.value = 'ai-center'
  authBannerMessage.value = '你已退出登录。'
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

.page-header {
  display: grid;
  gap: var(--space-lg);
  margin-bottom: var(--space-xl);
}

.header-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-lg);
}

.brand {
  display: flex;
  align-items: center;
  gap: 14px;
  min-width: 0;
}

.brand-logo {
  width: 36px;
  height: 36px;
  flex-shrink: 0;
  filter: drop-shadow(0 0 12px rgba(139, 92, 246, 0.4));
  transition: filter 0.3s var(--ease-out);
}

.brand-logo:hover {
  filter: drop-shadow(0 0 20px rgba(139, 92, 246, 0.6));
}

.brand-copy {
  display: grid;
  gap: 2px;
}

.brand-title {
  margin: 0;
  font-size: var(--text-display);
  font-weight: 800;
  letter-spacing: -0.04em;
  color: var(--color-text);
  line-height: 1.1;
}

.brand-subtitle {
  margin: 0;
  color: var(--color-text-muted);
  font-size: 0.84rem;
  line-height: 1.4;
  letter-spacing: 0.01em;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.auth-pill {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-height: 38px;
  padding: 0 14px;
  border-radius: 999px;
  border: 1px solid var(--color-border);
  background: var(--surface-page);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
}

.auth-pill-label {
  color: var(--color-text-muted);
  font-size: 0.72rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.auth-pill-name {
  color: var(--color-text);
  font-size: 0.84rem;
  font-weight: 500;
}

.credits-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  border-radius: 999px;
  border: none;
  background: linear-gradient(135deg, rgba(139, 92, 246, 0.15), rgba(99, 102, 241, 0.1));
  color: var(--color-accent-2);
  font-size: 0.78rem;
  font-weight: 700;
  letter-spacing: 0.02em;
  cursor: pointer;
  transition:
    background var(--duration-fast) var(--ease-out),
    box-shadow var(--duration-fast) var(--ease-out),
    transform var(--duration-fast) var(--ease-out);
}

.credits-badge:hover {
  background: linear-gradient(135deg, rgba(139, 92, 246, 0.25), rgba(99, 102, 241, 0.18));
  box-shadow: 0 0 24px rgba(139, 92, 246, 0.2);
  transform: translateY(-1px);
}

.settings-trigger,
.auth-trigger,
.theme-toggle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-xs);
  min-height: 38px;
  padding: 0 14px;
  border-radius: 12px;
  border: 1px solid var(--color-border);
  background: var(--surface-card);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  color: var(--color-text-secondary);
  cursor: pointer;
  font-size: 0.84rem;
  font-weight: 500;
  letter-spacing: 0.01em;
  transition:
    background var(--duration-fast) var(--ease-out),
    border-color var(--duration-fast) var(--ease-out),
    color var(--duration-fast) var(--ease-out),
    transform var(--duration-fast) var(--ease-out),
    box-shadow var(--duration-fast) var(--ease-out);
}

.theme-toggle {
  width: 38px;
  padding: 0;
}

.settings-trigger:hover,
.auth-trigger:hover,
.theme-toggle:hover {
  background: var(--color-surface-hover);
  border-color: var(--color-border-hover);
  color: var(--color-text);
  transform: translateY(-1px);
  box-shadow: var(--shadow-glow);
}

.auth-trigger-primary {
  background: var(--gradient-accent);
  border: none;
  color: #ffffff;
  font-weight: 600;
  box-shadow: var(--shadow-glow);
}

.auth-trigger-primary:hover {
  box-shadow: var(--shadow-glow-strong);
  transform: translateY(-2px) scale(1.02);
  color: #ffffff;
}

.auth-banner {
  margin: 0;
  padding: 12px 16px;
  border: 1px solid var(--color-border-accent);
  border-radius: 14px;
  background: linear-gradient(135deg, rgba(139, 92, 246, 0.06), rgba(99, 102, 241, 0.04));
  color: var(--color-text-secondary);
  font-size: 0.86rem;
  animation: fade-in var(--duration-normal) var(--ease-out);
}

.nav-tabs {
  position: relative;
  display: flex;
  gap: 4px;
  padding: 5px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.03);
  backdrop-filter: blur(24px);
  -webkit-backdrop-filter: blur(24px);
  border: 1px solid var(--color-border);
  width: fit-content;
}

.legacy-tools-menu {
  position: absolute;
  z-index: 20;
  top: calc(100% + 8px);
  left: 0;
  display: grid;
  grid-template-columns: repeat(3, minmax(150px, 1fr));
  gap: 4px;
  width: min(620px, calc(100vw - 40px));
  padding: 6px;
  border: 1px solid var(--color-border);
  border-radius: 12px;
  background: var(--color-surface);
  box-shadow: var(--shadow-elevated);
}

.nav-tab {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  min-height: 40px;
  padding: 0 16px;
  border: none;
  border-radius: 12px;
  background: transparent;
  color: var(--color-text-muted);
  cursor: pointer;
  font-size: 0.86rem;
  font-weight: 500;
  white-space: nowrap;
  position: relative;
  transition:
    background var(--duration-fast) var(--ease-out),
    color var(--duration-fast) var(--ease-out);
}

.nav-tab:hover {
  color: var(--color-text-secondary);
  background: rgba(139, 92, 246, 0.06);
}

.nav-tab-active {
  background: var(--gradient-accent);
  color: #ffffff;
  font-weight: 600;
  box-shadow: 0 4px 16px rgba(139, 92, 246, 0.3);
}

.nav-tab-active:hover {
  background: var(--gradient-accent);
  color: #ffffff;
}

.view-area {
  animation: slide-up var(--duration-dramatic) var(--ease-out);
}

@media (max-width: 900px) {
  .header-row {
    flex-direction: column;
    gap: var(--space-md);
    align-items: flex-start;
  }

  .header-actions {
    width: 100%;
    justify-content: flex-start;
  }

  .nav-tabs {
    width: 100%;
    overflow-x: auto;
    scrollbar-width: none;
  }

  .legacy-tools-menu {
    position: fixed;
    top: auto;
    right: 10px;
    bottom: 10px;
    left: 10px;
    width: auto;
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .nav-tabs::-webkit-scrollbar {
    display: none;
  }
}

@media (max-width: 560px) {
  .app-shell {
    width: min(100%, calc(100% - 20px));
  }

  .brand-logo {
    width: 28px;
    height: 28px;
  }

  .brand-title {
    font-size: 1.2rem;
  }

  .brand-subtitle {
    font-size: 0.8rem;
  }

  .auth-pill {
    width: 100%;
    justify-content: space-between;
  }

  .nav-tab {
    padding: 0 12px;
    min-height: 36px;
    font-size: 0.82rem;
  }
}
</style>
