<template>
  <div class="ai-shell">
    <header class="page-header">
      <div class="header-row">
        <div class="brand">
          <div class="brand-logo" aria-hidden="true">
            <svg width="36" height="36" viewBox="0 0 36 36" fill="none" xmlns="http://www.w3.org/2000/svg">
              <defs>
                <linearGradient id="ai-logo-grad" x1="0" y1="0" x2="36" y2="36" gradientUnits="userSpaceOnUse">
                  <stop style="stop-color: var(--color-primary)"/>
                  <stop offset="1" style="stop-color: var(--color-primary)"/>
                </linearGradient>
              </defs>
              <rect width="36" height="36" rx="8" fill="url(#ai-logo-grad)"/>
              <path d="M18 8l2.1 5.9L26 16l-5.9 2.1L18 24l-2.1-5.9L10 16l5.9-2.1L18 8z" fill="rgba(255,255,255,0.95)"/>
              <circle cx="26.5" cy="26.5" r="2.6" fill="rgba(255,255,255,0.6)"/>
            </svg>
          </div>
          <div class="brand-copy">
            <h1 class="brand-title">草场 · AI 创作中心</h1>
            <p class="brand-subtitle">独立的个人内容创作应用 —— 无身份概念，登录即用，游客可试用</p>
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

          <!-- 任务书 #76 D6：纯个人心智——无组织/门店选择器、无身份徽标；积分与充值是唯一的额度入口之一 -->
          <button v-if="isAuthenticated" type="button" class="credits-badge" title="积分与套餐"
            @click="showCreditsModal = true">
            <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
              <circle cx="8" cy="8" r="6.5" stroke="currentColor" stroke-width="1.3"/>
              <path d="M8 4.5v7M5.5 7.5h5" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/>
            </svg>
            {{ currentBalance }} 次
          </button>

          <div v-if="isAuthenticated && currentUser" class="auth-pill" aria-live="polite" data-testid="auth-pill">
            <span class="auth-pill-label">已登录</span>
            <strong class="auth-pill-name">{{ currentUser.displayName || currentUser.email }}</strong>
          </div>

          <button v-if="isAuthenticated" class="auth-trigger auth-trigger-secondary" type="button"
            :disabled="loggingOut" @click="handleLogout">
            {{ loggingOut ? '退出中…' : '退出登录' }}
          </button>
          <button v-else class="auth-trigger auth-trigger-primary" type="button" @click="openLoginModal()">
            登录 / 注册
          </button>

          <!-- 跨应用回跳：已登录自动免登（卡 A） -->
          <button class="grassland-link" type="button" :disabled="jumpingOut" @click="openGrassland">
            {{ jumpingOut ? '正在跳转…' : '打开草场' }}
          </button>
        </div>
      </div>

      <p v-if="bannerMessage" class="auth-banner" role="status">{{ bannerMessage }}</p>
      <p v-else-if="mustChangePassword" class="auth-banner" role="status">
        当前账号首次登录须先修改密码，创作功能暂不可用——请回草场完成改密后再来。
      </p>
    </header>

    <main class="view-area">
      <router-view v-slot="{ Component }">
        <KeepAlive :key="creationContextEpoch">
          <component
            :is="Component"
            v-bind="currentViewProps"
            @open-view="handleOpenView"
            @start-workflow="handleStartWorkflow"
            @request-login="openLoginModal('请先登录后继续。')"
            @open-grassland="openGrassland"
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
      @close="closeLoginModal"
      @submit="handleLogin"
      @register="handleRegister"
      @send-code="handleSendCode"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, defineAsyncComponent, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { normalizePlatformId } from '../config/ai-platform-capabilities'
import { consumeCrossAppTokenFromUrl, stripUrlParams, useCrossAppJump } from '../composables/useCrossAppToken'
import { useAuth } from '../composables/useAuth'
import { useCredits } from '../composables/useCredits'
import { useLoginModalController } from '../composables/useLoginModalController'
import { useTheme, type ThemeMode } from '../composables/useTheme'
import type { CreationEntry, CreationHandoff } from '../types/ai-creation'

const CreditsPackagesModal = defineAsyncComponent(() => import('../components/CreditsPackagesModal.vue'))
const LoginModal = defineAsyncComponent(() => import('../components/LoginModal.vue'))

const route = useRoute()
const router = useRouter()

const {
  currentUser, isAuthenticated, mustChangePassword,
  loggingOut, logoutError, clearLogoutError,
  loadCurrentUser, logout,
} = useAuth()

const bannerMessage = ref('')
const jumpingOut = ref(false)
const showCreditsModal = ref(false)
const creationContextEpoch = ref(0)
const creationEntry = ref<CreationEntry | null>(null)
const creationHandoff = ref<CreationHandoff | null>(null)
let creationRevision = Date.now()

const {
  showLoginModal, loginModalMounted, loginModalMessage,
  loggingIn, registering,
  loginError, registerError, sendCodeError,
  openLoginModal, closeLoginModal, handleLogin, handleRegister, handleSendCode,
} = useLoginModalController({
  onLoginSuccess: () => { bannerMessage.value = '' },
  onRegisterSuccess: () => { bannerMessage.value = '注册成功，已自动登录——现在可以开始创作了。' },
})

const { currentBalance, loadBalance: loadCreditBalance } = useCredits()
const { mode: themeMode, setMode: setThemeMode } = useTheme()
const { jumpToGrassland } = useCrossAppJump()

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

const currentViewName = computed(() => (route.name as string) || 'create')

const currentViewProps = computed<Record<string, unknown>>(() => {
  if (currentViewName.value === 'create') {
    return { authenticated: isAuthenticated.value, entry: creationEntry.value, mode: 'personal' }
  }
  if (creationHandoff.value?.targetView === currentViewName.value) {
    return { creationHandoff: creationHandoff.value }
  }
  return {}
})

onMounted(async () => {
  // URL 带 xat 时优先核销换会话（卡 A）；成功/失败都清参，失败落游客态由登录入口接住。
  await consumeCrossAppTokenFromUrl()
  await loadCurrentUser()
  if (isAuthenticated.value) void loadCreditBalance()

  // 门店深链（任务书 #76 卡 C）：?entry=store&org=&store= → 组装 store 源 entry，
  // hydrateStoreContext 在创作面内做可及性校验（不可达 → contextError，不静默放行）。
  // 直读 location.search（整页深链是本场景的主形态，且不依赖路由导航时序）。
  const entry = buildEntryFromQuery(new URLSearchParams(window.location.search))
  if (entry) {
    creationEntry.value = { ...entry, revision: nextCreationRevision() }
    stripUrlParams(['entry', 'org', 'store', 'title', 'platform'])
    if (entry.source.type === 'store' && !isAuthenticated.value) {
      openLoginModal('门店创作需要先登录——登录后将自动带入门店上下文。')
    }
  }
})

/** 账号变化（登录/换账号/登出/整页加载）：重置创作上下文并强制重建 KeepAlive 缓存。 */
watch(() => currentUser.value?.id ?? null, (accountId, previousAccountId) => {
  if (accountId !== previousAccountId) {
    creationEntry.value = null
    creationHandoff.value = null
    creationContextEpoch.value += 1
  }
}, { immediate: true })

watch(isAuthenticated, (authed) => {
  if (authed) void loadCreditBalance()
})

function nextCreationRevision(): number {
  creationRevision = Math.max(creationRevision + 1, Date.now())
  return creationRevision
}

/**
 * 深链 → CreationEntry（任务书 #76：参数形态定死）。
 * - `entry=store&org={organizationId}&store={storeId}`：门店锁定态（商家工作台入口）；
 * - `entry=hot&title={热点标题}[&platform={平台}]`：草场首页热点的预填带入。
 */
function buildEntryFromQuery(query: URLSearchParams): CreationEntry | null {
  const kind = query.get('entry') ?? ''
  if (kind === 'store') {
    const organizationId = (query.get('org') ?? '').trim()
    const storeId = (query.get('store') ?? '').trim()
    if (!organizationId || !storeId) return null
    return {
      revision: Date.now(), platformId: null, contentFormId: null,
      source: { type: 'store', organizationId, storeId },
    }
  }
  if (kind === 'hot') {
    const title = (query.get('title') ?? '').trim().slice(0, 200)
    if (!title) return null
    const platform = (query.get('platform') ?? '').trim()
    return {
      revision: Date.now(),
      platformId: platform ? normalizePlatformId(platform) : null,
      contentFormId: null,
      source: { type: 'hot-topic', title },
      prefill: { topic: title },
    }
  }
  return null
}

/** 工具视图「返回创作中心」等跨视图事件：共享视图只发 open-view，由壳映射到本应用创作面。 */
function handleOpenView(view: string): void {
  void router.push({ name: view === 'ai-center' ? 'create' : view })
}

/** 与草场 DefaultLayout 同机制（D4：勿发明新状态机）：handoff 存壳层，KeepAlive 保状态。 */
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
  void router.push({ name: handoff.targetView })
}

async function openGrassland(): Promise<void> {
  jumpingOut.value = true
  await jumpToGrassland('/')
  // 跳转失败（如被浏览器拦截）时复位按钮；正常跳转后页面卸载，无需复位
  jumpingOut.value = false
}

async function handleLogout(): Promise<void> {
  clearLogoutError()
  const ok = await logout()
  if (!ok) {
    bannerMessage.value = logoutError.value || '退出登录失败，请稍后重试。'
    return
  }
  creationEntry.value = null
  creationHandoff.value = null
  void router.push({ name: 'create' })
  bannerMessage.value = '你已退出登录——游客仍可试用部分创作能力。'
}

function handleCreditsRefreshed(): void {
  void loadCreditBalance()
}
</script>

<style scoped>
.ai-shell {
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
.brand-copy { display: grid; gap: 2px; }
.brand-title { margin: 0; font-family: var(--font-display); font-size: var(--text-display); font-weight: 700; letter-spacing: -0.04em; color: var(--color-text); line-height: 1.1; }
.brand-subtitle { margin: 0; color: var(--color-text-muted); font-size: 0.84rem; line-height: 1.4; letter-spacing: 0.01em; }
.header-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; justify-content: flex-end; }
.auth-pill { display: inline-flex; align-items: center; gap: 8px; min-height: 38px; padding: 0 12px; border-radius: var(--radius-pill); border: 1px solid var(--color-border); background: var(--surface-card); }
.auth-pill-label { color: var(--color-text-muted); font-size: 0.72rem; font-weight: 600; text-transform: uppercase; letter-spacing: 0.04em; }
.auth-pill-name { color: var(--color-text); font-size: 0.84rem; font-weight: 500; }
.credits-badge { display: inline-flex; align-items: center; gap: 6px; padding: 6px 12px; border-radius: var(--radius-pill); border: none; background: linear-gradient(135deg, color-mix(in srgb, var(--color-accent) 15%, transparent), color-mix(in srgb, var(--color-accent) 10%, transparent)); color: var(--color-accent-2); font-size: 0.78rem; font-weight: 700; letter-spacing: 0.02em; cursor: pointer; transition: background var(--duration-fast) var(--ease-out), box-shadow var(--duration-fast) var(--ease-out), transform var(--duration-fast) var(--ease-out); }
.credits-badge:hover { background: linear-gradient(135deg, color-mix(in srgb, var(--color-accent) 25%, transparent), color-mix(in srgb, var(--color-accent) 18%, transparent)); box-shadow: 0 0 24px color-mix(in srgb, var(--color-accent) 20%, transparent); transform: translateY(-1px); }
.auth-trigger, .theme-toggle, .grassland-link { display: inline-flex; align-items: center; justify-content: center; gap: var(--space-xs); min-height: 38px; padding: 0 14px; border-radius: var(--radius-md); border: 1px solid var(--color-border); background: var(--surface-card); backdrop-filter: blur(12px); -webkit-backdrop-filter: blur(12px); color: var(--color-text-secondary); cursor: pointer; font-size: 0.84rem; font-weight: 500; letter-spacing: 0.01em; transition: background var(--duration-fast) var(--ease-out), border-color var(--duration-fast) var(--ease-out), color var(--duration-fast) var(--ease-out), transform var(--duration-fast) var(--ease-out), box-shadow var(--duration-fast) var(--ease-out); }
.theme-toggle { width: 38px; padding: 0; }
.auth-trigger:hover, .theme-toggle:hover, .grassland-link:hover { background: var(--color-surface-hover); border-color: var(--color-border-hover); color: var(--color-text); transform: translateY(-1px); box-shadow: var(--shadow-glow); }
.auth-trigger-primary { background: var(--gradient-accent); border: none; color: var(--color-on-accent); font-weight: 600; box-shadow: var(--shadow-glow); border-radius: var(--radius-pill); padding: 0 18px; }
.auth-trigger-primary:hover { box-shadow: var(--shadow-glow-strong); transform: translateY(-2px) scale(1.02); color: var(--color-on-accent); }
.auth-banner { margin: 0; padding: 12px 16px; border: 1px solid var(--color-border-accent); border-radius: var(--radius-md); background: linear-gradient(135deg, color-mix(in srgb, var(--color-accent) 6%, transparent), color-mix(in srgb, var(--color-accent) 4%, transparent)); color: var(--color-text-secondary); font-size: 0.86rem; animation: fade-in var(--duration-normal) var(--ease-out); }
.view-area { animation: slide-up var(--duration-dramatic) var(--ease-out); }
@media (max-width: 900px) {
  .header-row { flex-direction: column; gap: var(--space-md); align-items: flex-start; }
  .header-actions { width: 100%; justify-content: flex-start; }
}
@media (max-width: 560px) {
  .ai-shell { width: min(100%, calc(100% - 20px)); }
  .brand-logo { width: 28px; height: 28px; }
  .brand-title { font-size: 1.2rem; }
  .brand-subtitle { font-size: 0.8rem; }
}
</style>
