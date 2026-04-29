<template>
  <div class="app-shell">
    <header class="page-header">
      <div class="header-row">
        <div class="brand">
          <span class="brand-dot" aria-hidden="true"></span>
          <div class="brand-copy">
            <h1 class="brand-title">Extractor</h1>
            <p class="brand-subtitle">视频提取、图片评价与文章创作工作台</p>
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

      <nav class="nav-tabs" role="tablist" aria-label="功能选择">
        <button
          class="nav-tab"
          :class="{ 'nav-tab-active': currentView === 'home' }"
          :aria-selected="currentView === 'home'"
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
          :aria-selected="currentView === 'video'"
          type="button"
          @click="currentView = 'video'"
        >
          <svg width="15" height="15" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <rect x="1.5" y="3" width="13" height="10" rx="2" stroke="currentColor" stroke-width="1.3"/>
            <path d="M6.5 6.5l3.5 1.5-3.5 1.5V6.5z" fill="currentColor"/>
          </svg>
          视频提取分析
        </button>
        <button
          class="nav-tab"
          :class="{ 'nav-tab-active': currentView === 'image' }"
          :aria-selected="currentView === 'image'"
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
          :aria-selected="currentView === 'article'"
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
          :aria-selected="currentView === 'image-gen'"
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
          :aria-selected="currentView === 'comedy'"
          type="button"
          @click="currentView = 'comedy'"
        >
          <svg width="15" height="15" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <path d="M8 1.5a4.5 4.5 0 00-1 8.9V12a1 1 0 001 0V10.4a4.5 4.5 0 00-0-8.9z" stroke="currentColor" stroke-width="1.2"/>
            <path d="M5 13.5h6M6.5 15h3" stroke="currentColor" stroke-width="1.2" stroke-linecap="round"/>
          </svg>
          脱口秀创作
        </button>
      </nav>
    </header>

    <main class="view-area">
      <KeepAlive>
        <component :is="currentViewComponent" @open-view="handleOpenView" @create-article="handleCreateArticleFromTopic" @create-comedy="handleCreateComedyFromTopic" />
      </KeepAlive>
    </main>

    <AnalysisSettingsModal
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
import { computed, onMounted, provide, ref, watch, type Component } from 'vue'
import AnalysisSettingsModal from './components/AnalysisSettingsModal.vue'
import ArticleCreationView from './components/ArticleCreationView.vue'
import ComedyWritingView from './components/ComedyWritingView.vue'
import HomeView from './components/HomeView.vue'
import ImageAnalysisView from './components/ImageAnalysisView.vue'
import ImageGenerationView from './components/ImageGenerationView.vue'
import LoginModal from './components/LoginModal.vue'
import VideoAnalysisView from './components/VideoAnalysisView.vue'
import { useAnalysisSettings } from './composables/useAnalysisSettings'
import { useAuth } from './composables/useAuth'
import { useHomepageSettings } from './composables/useHomepageSettings'
import { useTheme, type ThemeMode } from './composables/useTheme'
import type { LoginFormValues, RegisterFormValues } from './types/auth'
import type { AnalysisFeature, AnalysisProvider, AnalysisSettings, HomepageSettings } from './types/settings'

type AppView = 'home' | 'video' | 'image' | 'article' | 'image-gen' | 'comedy'
type HomeFeatureView = Exclude<AppView, 'home'>

const currentView = ref<AppView>('home')
const articleInitialTopic = ref('')
const comedyInitialTopic = ref('')
const showSettingsModal = ref(false)
const showLoginModal = ref(false)
const loginModalMessage = ref('')
const authBannerMessage = ref('')

const {
  currentUser,
  isAuthenticated,
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
  void loadCurrentUser()
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
  home: HomeView,
  video: VideoAnalysisView,
  image: ImageAnalysisView,
  article: ArticleCreationView,
  'image-gen': ImageGenerationView,
  comedy: ComedyWritingView,
}

const currentViewComponent = computed(() => viewComponentMap[currentView.value])

const themeToggleTitle = computed(() => {
  if (themeMode.value === 'light') return '浅色模式 — 点击切换'
  if (themeMode.value === 'dark') return '深色模式 — 点击切换'
  return '跟随系统 — 点击切换'
})

function handleOpenView(view: HomeFeatureView): void {
  currentView.value = view
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

function handleVerifyModel(
  feature: AnalysisFeature,
  provider: AnalysisProvider | undefined,
  model: string,
  settings: AnalysisSettings,
): void {
  void verifyModelAction(feature, provider, model, settings)
}

function openLoginModal(message = ''): void {
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
  authBannerMessage.value = '注册成功，现在可以打开设置管理你的专属配置。'
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

  showSettingsModal.value = true
}
</script>

<style scoped>
.app-shell {
  position: relative;
  z-index: 1;
  width: min(1160px, calc(100% - 32px));
  margin: 0 auto;
  padding: clamp(20px, 3vw, 32px) 0 72px;
}

.page-header {
  display: grid;
  gap: var(--space-md);
  margin-bottom: var(--space-lg);
}

.header-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-lg);
}

.brand {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  min-width: 0;
}

.brand-dot {
  width: 12px;
  height: 12px;
  margin-top: 8px;
  border-radius: 999px;
  background: var(--color-accent);
  flex-shrink: 0;
}

.brand-copy {
  display: grid;
  gap: 4px;
}

.brand-title {
  margin: 0;
  font-size: 1.15rem;
  font-weight: 700;
  letter-spacing: -0.02em;
  color: var(--color-text);
}

.brand-subtitle {
  margin: 0;
  color: var(--color-text-muted);
  font-size: 0.86rem;
  line-height: 1.45;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.auth-pill {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-height: 40px;
  padding: 0 12px;
  border-radius: 999px;
  border: 1px solid var(--color-border);
  background: var(--surface-page);
}

.auth-pill-label {
  color: var(--color-text-muted);
  font-size: 0.74rem;
  font-weight: 600;
}

.auth-pill-name {
  color: var(--color-text);
  font-size: 0.84rem;
}

.settings-trigger,
.auth-trigger,
.theme-toggle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-xs);
  min-height: 40px;
  padding: 0 14px;
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border);
  background: var(--surface-card);
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
  width: 40px;
  padding: 0;
}

.settings-trigger:hover,
.auth-trigger:hover,
.theme-toggle:hover {
  background: var(--color-surface-hover);
  border-color: var(--color-border-hover);
  color: var(--color-text);
  transform: translateY(-1px);
}

.auth-trigger-primary {
  background: var(--color-accent);
  border-color: transparent;
  color: #ffffff;
}

.auth-trigger-primary:hover {
  background: var(--color-accent-2);
  color: #ffffff;
}

.auth-banner {
  margin: 0;
  padding: 12px 14px;
  border: 1px solid var(--color-border-accent);
  border-radius: var(--radius-md);
  background: var(--color-surface-highlight);
  color: var(--color-text-secondary);
  font-size: 0.88rem;
  animation: fade-in var(--duration-normal) var(--ease-out);
}

.nav-tabs {
  display: flex;
  gap: 6px;
  padding: 6px;
  border-radius: var(--radius-lg);
  background: var(--surface-page);
  border: 1px solid var(--color-border);
  width: fit-content;
}

.nav-tab {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-height: 42px;
  padding: 0 16px;
  border: none;
  border-radius: calc(var(--radius-lg) - 6px);
  background: transparent;
  color: var(--color-text-muted);
  cursor: pointer;
  font-size: 0.88rem;
  font-weight: 500;
  white-space: nowrap;
  transition:
    background var(--duration-fast) var(--ease-out),
    color var(--duration-fast) var(--ease-out),
    border-color var(--duration-fast) var(--ease-out);
}

.nav-tab:hover {
  color: var(--color-text-secondary);
  background: var(--surface-hover);
}

.nav-tab-active {
  background: var(--surface-card);
  color: var(--color-text);
  font-weight: 600;
  border: 1px solid var(--color-border);
}

.view-area {
  animation: fade-in var(--duration-normal) var(--ease-out);
}

@media (max-width: 900px) {
  .header-row {
    flex-direction: column;
    gap: var(--space-md);
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

  .nav-tabs::-webkit-scrollbar {
    display: none;
  }
}

@media (max-width: 560px) {
  .app-shell {
    width: min(100%, calc(100% - 24px));
  }

  .brand-subtitle {
    font-size: 0.82rem;
  }

  .auth-pill {
    width: 100%;
    justify-content: space-between;
  }
}
</style>