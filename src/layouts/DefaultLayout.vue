<template>
  <div class="app-shell">
    <header class="page-header">
      <div class="header-row">
        <div class="brand">
          <div class="brand-logo" aria-hidden="true">
            <svg width="36" height="36" viewBox="0 0 36 36" fill="none" xmlns="http://www.w3.org/2000/svg">
              <defs>
                <linearGradient id="logo-grad" x1="0" y1="0" x2="36" y2="36" gradientUnits="userSpaceOnUse">
                  <stop style="stop-color: var(--color-accent)"/>
                  <stop offset="0.5" style="stop-color: var(--color-accent)"/>
                  <stop offset="1" style="stop-color: var(--color-accent)"/>
                </linearGradient>
                <linearGradient id="logo-accent" x1="0" y1="0" x2="36" y2="36" gradientUnits="userSpaceOnUse">
                  <stop style="stop-color: var(--color-accent)" opacity="0.7"/>
                  <stop offset="1" style="stop-color: var(--color-accent)"/>
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
          <!-- 任务书 #77 卡 E：「AI 内容创作中心」主导航页签撤除，入口挪到头部右侧「AI 创作」。
               点击行为不变：navigateTo('ai-center') → 外跳 ai.html + token 免登（未登录先弹登录由
               路由壳 AiCenterExternalRedirect 链路处理）。 -->
          <button type="button" class="nav-ai-trigger" data-testid="nav-ai-center" @click="navigateTo('ai-center')">
            <svg width="15" height="15" viewBox="0 0 16 16" fill="none" aria-hidden="true">
              <path d="M8 1.5l1.2 3.3L12.5 6 9.2 7.2 8 10.5 6.8 7.2 3.5 6l3.3-1.2L8 1.5z" stroke="currentColor" stroke-width="1.2" stroke-linejoin="round"/>
              <path d="M12.5 10l.6 1.6 1.4.5-1.4.6-.6 1.8-.6-1.8-1.4-.6 1.4-.5.6-1.6z" fill="currentColor"/>
            </svg>
            AI 创作
          </button>
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

          <!-- 任务书 #78 卡 A（D1）：草场全域移除积分展示——积分与套餐唯一入口在 AI 创作中心
               （AiAppLayout 头部徽标 + 弹窗）。商家资金账户/月度账单/到店套餐、推荐官钱包是现金经营账，不动。 -->

          <!-- 账号区：进入身份按账号已有档案自动判定（换身份=退出后重新登录） -->
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
        <!-- 任务书 #77 卡 E：「AI 内容创作中心」页签撤除（入口在头部右侧「AI 创作」按钮）；
             #74 D1「举报投诉」与反馈 7「判例库」页签撤除决策不变。 -->
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
import { computed, defineAsyncComponent, nextTick, onMounted, provide, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import NotificationBell from '../components/NotificationBell.vue'

const LoginModal = defineAsyncComponent(() => import('../components/LoginModal.vue'))

import { ensureAccountIdentity } from '../composables/useAccountBootstrap'
import { useActiveIdentity } from '../composables/useActiveIdentity'
import { useAuth } from '../composables/useAuth'
import { useGrassland } from '../composables/useGrassland'
import { consumeCrossAppTokenFromUrl, useCrossAppJump } from '../composables/useCrossAppToken'
import { useLoginModalController } from '../composables/useLoginModalController'
import { useTheme, type ThemeMode } from '../composables/useTheme'
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
const authBannerMessage = ref('')

const {
  currentUser, isAuthenticated,
  loggingOut,
  logoutError, loadError: authLoadError,
  clearLogoutError,
  loadCurrentUser, logout,
} = useAuth()

/** 登录/注册弹窗接线（任务书 #76 抽取为共享 composable，与 AI 应用壳同源）。
 * 登录/注册成功后不做身份编排：账号 watch（唯一装载入口）按账号已有档案自动落地
 * 活动身份（商家身份优先/服务端已激活侧优先），裸账号由装载链兜底补开推荐官。 */
const {
  showLoginModal, loginModalMounted, loginModalMessage,
  loggingIn, registering,
  loginError, registerError, sendCodeError,
  openLoginModal: openLoginModalViaController,
  closeLoginModal, handleLogin, handleRegister, handleSendCode,
} = useLoginModalController({
  onLoginSuccess: () => {
    authBannerMessage.value = '已登录，进入身份按账号自动判定；换身份请退出后重新登录。'
  },
  onRegisterSuccess: async () => {
    await nextTick()
    router.push({ name: 'grassland' })
    authBannerMessage.value = '注册成功，已进入推荐官身份——请完善推荐官资料，到任务大厅开始接单。'
  },
})

function openLoginModal(message = ''): void {
  // 本壳附加语义：打开登录弹窗时顺带清登出错误横幅（原行为）
  clearLogoutError()
  openLoginModalViaController(message)
}

/** 草场域请求封装：身份装载走它（激活经后端校验）。 */
const grassland = useGrassland()
const {
  activeSide, hasMerchantIdentity, hasRecommenderIdentity, identitiesLoaded,
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

/** 跨应用跳转（任务书 #76 卡 A）：主导航旧链外跳 + 首页热点带入外跳共用。 */
const { jumpToAiApp } = useCrossAppJump()

const { mode: themeMode, setMode: setThemeMode } = useTheme()

function cycleTheme(): void {
  const order: ThemeMode[] = ['light', 'dark', 'system']
  const currentIndex = order.indexOf(themeMode.value)
  setThemeMode(order[(currentIndex + 1) % order.length])
}

const currentViewProps = computed<Record<string, unknown>>(() => {
  // 任务书 #76：草场内嵌创作面 = /creation（AiCreationCenter mode="platform"，任务锁定+素材库）。
  if (currentViewName.value === 'creation') {
    return { authenticated: isAuthenticated.value, entry: creationEntry.value, mode: 'platform' }
  }
  if (creationHandoff.value?.targetView === currentViewName.value) {
    return { creationHandoff: creationHandoff.value }
  }
  return {}
})

watch(authLoadError, (message) => {
  if (message) authBannerMessage.value = message
})

onMounted(async () => {
  // 跨应用免登（任务书 #76 卡 A；任务书 #86 传目标应用 audience）：AI 应用「打开草场」等回跳带
  // ?xat= 时优先核销换会话（读到即先清参再请求，成功/失败都不残留），再走常规会话引导。
  await consumeCrossAppTokenFromUrl('grassland')
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
  })
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
    // 任务书 #79 C79-03：身份 I/O 唯一协调入口——工作台 initForAccount 等待同一份
    // pending/快照，布局与工作台双消费方只发一轮身份请求。
    if (accountId) void ensureAccountIdentity(grassland)
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
  // 共享工具视图的「返回创作中心」只发 open-view('ai-center')，由各壳映射到自己的创作面
  // （任务书 #76 D4：草场侧是 /creation；AI 应用侧是 create）。
  router.push({ name: view === 'ai-center' ? 'creation' : view })
}

function handleOpenCreation(entry: CreationEntry): void {
  // 任务创作留在草场（进程内 entry + /creation，领单链路不跨应用）；
  // 自由创作（首页热点带入）属 AI 独立应用——带参免登外跳（AI 应用组装 hot 源 entry）。
  if (entry.source.type === 'hot-topic') {
    const params: Record<string, string> = { entry: 'hot', title: entry.source.title }
    if (entry.platformId) params.platform = entry.platformId
    void jumpToAiApp('/', params)
    return
  }
  creationEntry.value = { ...entry, revision: nextCreationRevision() }
  creationHandoff.value = null
  router.push({ name: 'creation' })
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

function handleNotificationNavigate(target: NotificationLinkTarget): void {
  // 2026-09-04 反馈 5：争议通知直达案件详情页（工作台不再内嵌审判看板）
  if (target.disputeId) {
    router.push(`/me/disputes/${target.disputeId}`)
    return
  }
  router.push({ name: target.view })
  grasslandAnchor.value = target.anchor
  grasslandNavigationTarget.value = target.taskId ? target : null
}

function handleOpenDispute(disputeId: string): void {
  router.push(`/me/disputes/${disputeId}`)
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

</script>

<style scoped>
.app-shell {
  position: relative;
  z-index: 1;
  width: min(1240px, calc(100% - 48px));
  margin: 0 auto;
  padding: calc(clamp(24px, 4vw, 40px) + env(safe-area-inset-top, 0px)) 0 var(--space-section);
}
.page-header { position: relative; z-index: 10; display: grid; gap: var(--space-lg); margin-bottom: var(--space-xl); }
.page-header::after { content: ''; display: block; width: 100%; height: 1px; background: var(--color-border); }
.header-row { display: flex; align-items: center; justify-content: space-between; gap: var(--space-lg); }
.brand { display: flex; align-items: center; gap: 14px; min-width: 0; }
.brand-logo { width: 36px; height: 36px; flex-shrink: 0; filter: drop-shadow(0 0 12px color-mix(in srgb, var(--color-accent) 35%, transparent)); transition: filter 0.3s var(--ease-out); }
.brand-logo:hover { filter: drop-shadow(0 0 20px color-mix(in srgb, var(--color-accent) 55%, transparent)); }
.brand-copy { display: grid; gap: 2px; }
.brand-title { margin: 0; font-family: var(--font-display); font-size: var(--text-display); font-weight: 300; letter-spacing: -0.04em; color: var(--color-text); line-height: 1.1; }
.brand-subtitle { margin: 0; color: var(--color-text-muted); font-size: 0.84rem; line-height: 1.4; letter-spacing: 0.01em; }
.header-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; justify-content: flex-end; }
.auth-pill { display: inline-flex; align-items: center; gap: 8px; min-height: 38px; padding: 0 12px; border-radius: var(--radius-pill); border: 1px solid var(--color-border); background: var(--surface-card); }
.auth-pill-label { color: var(--color-text-muted); font-size: 0.72rem; font-weight: 600; text-transform: uppercase; letter-spacing: 0.04em; }
.auth-pill-name { color: var(--color-text); font-size: 0.84rem; font-weight: 500; }
.settings-trigger, .auth-trigger, .theme-toggle { display: inline-flex; align-items: center; justify-content: center; gap: var(--space-xs); min-height: 38px; padding: 0 14px; border-radius: var(--radius-md); border: 1px solid var(--color-border); background: var(--surface-card); backdrop-filter: blur(12px); -webkit-backdrop-filter: blur(12px); color: var(--color-text-secondary); cursor: pointer; font-size: 0.84rem; font-weight: 500; letter-spacing: 0.01em; transition: background var(--duration-fast) var(--ease-out), border-color var(--duration-fast) var(--ease-out), color var(--duration-fast) var(--ease-out), transform var(--duration-fast) var(--ease-out), box-shadow var(--duration-fast) var(--ease-out); }
.theme-toggle { width: 38px; padding: 0; }
/* 任务书 #77 卡 E：AI 创作入口（头部右侧）——强调淡染的胶囊，区别于中性工具钮 */
.nav-ai-trigger { display: inline-flex; align-items: center; gap: 6px; min-height: 38px; padding: 0 14px; border-radius: var(--radius-pill); border: 1px solid var(--color-border-accent); background: linear-gradient(135deg, color-mix(in srgb, var(--color-accent) 12%, transparent), color-mix(in srgb, var(--color-accent) 6%, transparent)); color: var(--color-accent-2); cursor: pointer; font-size: 0.84rem; font-weight: 600; letter-spacing: 0.01em; transition: background var(--duration-fast) var(--ease-out), box-shadow var(--duration-fast) var(--ease-out), transform var(--duration-fast) var(--ease-out); }
.nav-ai-trigger:hover { background: linear-gradient(135deg, color-mix(in srgb, var(--color-accent) 22%, transparent), color-mix(in srgb, var(--color-accent) 12%, transparent)); box-shadow: 0 0 24px color-mix(in srgb, var(--color-accent) 18%, transparent); transform: translateY(-1px); }
.settings-trigger:hover, .auth-trigger:hover, .theme-toggle:hover { background: var(--color-surface-hover); border-color: var(--color-border-hover); color: var(--color-text); transform: translateY(-1px); box-shadow: var(--shadow-glow); }
.auth-trigger-primary { background: var(--gradient-accent); border: none; color: var(--color-on-accent); font-weight: 600; box-shadow: var(--shadow-glow); border-radius: var(--radius-pill); padding: 0 18px; }
.auth-trigger-primary:hover { box-shadow: var(--shadow-glow-strong); transform: translateY(-2px) scale(1.02); color: var(--color-on-accent); }
.auth-banner { margin: 0; padding: 12px 16px; border: 1px solid var(--color-border-accent); border-radius: var(--radius-md); background: linear-gradient(135deg, color-mix(in srgb, var(--color-accent) 6%, transparent), color-mix(in srgb, var(--color-accent) 4%, transparent)); color: var(--color-text-secondary); font-size: 0.86rem; animation: fade-in var(--duration-normal) var(--ease-out); }


.nav-tabs { position: absolute; top: 0; left: 50%; display: flex; gap: 4px; padding: 5px; border-radius: var(--radius-pill); background: var(--surface-card); backdrop-filter: blur(24px); -webkit-backdrop-filter: blur(24px); border: 1px solid var(--color-border); width: fit-content; transform: translateX(-50%); }
.nav-tab { display: inline-flex; align-items: center; gap: 7px; min-height: 40px; padding: 0 16px; border: none; border-radius: var(--radius-md); background: transparent; color: var(--color-text-muted); cursor: pointer; font-size: 0.86rem; font-weight: 500; white-space: nowrap; position: relative; transition: background var(--duration-fast) var(--ease-out), color var(--duration-fast) var(--ease-out); }
.nav-tab:hover { color: var(--color-text-secondary); background: color-mix(in srgb, var(--color-accent) 7%, transparent); }
.nav-tab-active { background: var(--gradient-accent); color: var(--color-on-accent); font-weight: 600; box-shadow: 0 4px 16px color-mix(in srgb, var(--color-accent) 30%, transparent); }
.nav-tab-active:hover { background: var(--gradient-accent); color: var(--color-on-accent); }
.view-area { animation: slide-up var(--duration-dramatic) var(--ease-out); }
@media (max-width: 900px) {
  .header-row { flex-direction: column; gap: var(--space-md); align-items: flex-start; }
  .header-actions { width: 100%; justify-content: flex-start; }
  .nav-tabs { position: static; width: 100%; overflow-x: auto; scrollbar-width: none; transform: none; }
  .nav-tabs::-webkit-scrollbar { display: none; }
}
@media (max-width: 560px) {
  .app-shell { width: min(100%, calc(100% - 24px)); }
  .brand-logo { width: 28px; height: 28px; }
  .brand-title { font-size: 1.2rem; }
  .brand-subtitle { font-size: 0.8rem; }
  .nav-tab { padding: 0 12px; min-height: 36px; font-size: 0.82rem; }
}
</style>
