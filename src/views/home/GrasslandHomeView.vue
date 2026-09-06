<template>
  <section class="grassland-home gl-field" aria-labelledby="grassland-home-title">
    <!-- Hero：平台主张（PRD §一：草场 = 撮合平台，AI 中心是内置能力而非门面） -->
    <section class="gl-zone hero">
      <div class="hero-mesh" aria-hidden="true">
        <svg class="hero-mesh-svg" viewBox="0 0 920 560" preserveAspectRatio="none" role="presentation">
          <defs>
            <filter id="hero-mesh-blur" x="-20%" y="-20%" width="140%" height="140%">
              <feGaussianBlur stdDeviation="34" />
            </filter>
          </defs>
          <g filter="url(#hero-mesh-blur)">
            <ellipse cx="95" cy="64" rx="190" ry="145" fill="var(--hero-mesh-cream)" />
            <ellipse cx="260" cy="112" rx="230" ry="170" fill="var(--hero-mesh-orange)" />
            <ellipse cx="500" cy="56" rx="245" ry="150" fill="var(--hero-mesh-lavender)" />
            <ellipse cx="724" cy="174" rx="255" ry="190" fill="var(--hero-mesh-indigo)" />
            <ellipse cx="864" cy="42" rx="145" ry="130" fill="var(--hero-mesh-ruby)" />
          </g>
          <path class="hero-grid-line" d="M0 176C180 136 300 218 480 178s278-4 440-52" />
          <path class="hero-grid-line" d="M0 220C160 184 320 260 478 218s288-12 442-62" />
        </svg>
      </div>
      <div class="hero-copy">
        <div class="hero-kicker"><span class="hero-kicker-dot"></span>GRASSLAND / CREATOR FIELD</div>
        <h2 id="grassland-home-title" class="hero-title">
          <span>商家 × 推荐官</span>
          <em>把好内容，种成生意</em>
        </h2>
        <p class="hero-note">
          从一条任务，到一次真实转化。商家发布推广任务，推荐官接单创作并发布到各大社交平台；
          草场用托管、核实与 AI 创作，把合作变成可以持续生长的内容资产。
        </p>
        <div v-if="!isAuthenticated" class="hero-actions">
          <button type="button" class="gl-btn-primary hero-cta" @click="emit('request-login')">登录 / 注册</button>
          <button type="button" class="hero-secondary-cta" @click="go('ai-center')">先去体验 AI 创作 <span aria-hidden="true">↗</span></button>
        </div>
        <p v-else class="hero-identity">
          当前身份：<strong>{{ activeIdentityLabel }}</strong>（换身份请退出后重新登录）
        </p>
        <div class="hero-proof" aria-label="平台能力">
          <span><strong>任务可追踪</strong></span>
          <span><strong>资金有托管</strong></span>
          <span><strong>内容可复用</strong></span>
        </div>
      </div>
      <div class="hero-meta" aria-label="平台运转三步">
        <div class="hero-meta-head">
          <span class="hero-meta-label">HOW IT GROWS</span>
          <span class="badge badge-accent">一条内容的旅程</span>
        </div>
        <div class="meta-step">
          <span class="meta-index gl-num">01</span>
          <div><strong>商家发布任务</strong><p>把目标、素材与佣金规则一次配齐</p></div>
        </div>
        <div class="meta-step">
          <span class="meta-index gl-num">02</span>
          <div><strong>推荐官创作发布</strong><p>从任务大厅出发，AI 辅助完成图文与视频</p></div>
        </div>
        <div class="meta-step">
          <span class="meta-index gl-num">03</span>
          <div><strong>平台托管结算</strong><p>自动核实、商家确认，达标即结算</p></div>
        </div>
        <div class="hero-meta-footer"><span class="hero-status-dot"></span> 内容正在草场里生长</div>
      </div>
    </section>

    <!-- 未登录：平台介绍 + 创作体验入口 -->
    <section v-if="!isAuthenticated" class="gl-zone" aria-label="平台介绍">
      <div class="gl-zone-head">
        <h3 class="gl-zone-title">两种身份，一个账号</h3>
        <p class="gl-zone-note">注册后可同时开通商家与推荐官身份，随时切换</p>
      </div>
      <div class="gl-zone-body">
        <article class="gl-tile role-tile role-tile-merchant">
          <span class="role-mark">商家 / 01</span>
          <h3>商家身份</h3>
          <p class="tile-copy">创建商家主体与门店、发布推广任务、设置要求、管理资金、筛选推荐官、查看营收。</p>
          <span class="tile-arrow" aria-hidden="true">↗</span>
        </article>
        <article class="gl-tile role-tile role-tile-recommender">
          <span class="role-mark">推荐官 / 02</span>
          <h3>推荐官身份</h3>
          <p class="tile-copy">浏览和报名任务、创作发布内容、提交凭证、获得任务收益，按等级解锁更多权益。</p>
          <span class="tile-arrow" aria-hidden="true">↗</span>
        </article>
        <article class="gl-tile role-tile role-tile-ai">
          <span class="role-mark">AI 创作 / 03</span>
          <h3>AI 内容创作中心</h3>
          <p class="tile-copy">任何注册账号登录即用的创作应用：按发布平台组织图文与视频创作，游客可试用。</p>
          <button type="button" class="tile-link" @click="go('ai-center')">游客体验入口</button>
        </article>
      </div>
    </section>

    <!-- 已登录：按身份组织的工作台入口 -->
    <section v-else class="gl-zone" aria-label="我的草场">
      <div class="gl-zone-head">
        <h3 class="gl-zone-title">我的草场</h3>
        <p class="gl-zone-note">入口随当前活动身份组织；平台治理人员请使用治理台</p>
      </div>
      <div class="gl-zone-body">
        <button
          v-if="showMerchantEntry"
          type="button"
          class="gl-tile gl-tile-button gl-tile-button-primary workbench-tile"
          data-testid="home-merchant-entry"
          @click="go('grassland')"
        >
          <span class="eyebrow">商家 · 播种</span>
          <h3>商家工作台</h3>
          <p class="tile-copy">主体与门店、商家素材库、任务发布与报名筛选、履约确认、资金账单与营收分析。</p>
        </button>
        <button
          v-if="showRecommenderEntry"
          type="button"
          class="gl-tile gl-tile-button gl-tile-button-primary workbench-tile"
          data-testid="home-recommender-entry"
          @click="go('grassland')"
        >
          <span class="eyebrow">推荐官 · 耕耘</span>
          <h3>推荐官工作台</h3>
          <p class="tile-copy">任务大厅与邀请、我的任务与履约交付、收益钱包与收入统计、等级与权益。</p>
        </button>
        <button
          v-if="!hasAnyIdentity"
          type="button"
          class="gl-tile gl-tile-button workbench-tile"
          data-testid="home-onboarding-entry"
          @click="go('grassland')"
        >
          <span class="eyebrow">开通身份</span>
          <h3>完善资料，开通你的第一个身份</h3>
          <p class="tile-copy">进入工作台选择商家或推荐官身份，完成资料后即可开始使用草场。</p>
        </button>
      </div>
    </section>

    <!-- 创作灵感：全网热点（选题材入口，创作在 AI 中心完成） -->
    <section class="gl-zone hot-zone" aria-label="创作灵感与热点">
      <div class="gl-zone-head">
        <h3 class="gl-zone-title">创作灵感</h3>
        <p class="gl-zone-note">选好题材后进入 AI 中心完成创作；热点是手段，不是终点</p>
        <div class="hot-refresh-group">
          <span v-if="hotFetchedNote" class="hot-fetched-note gl-num">{{ hotFetchedNote }}</span>
          <button type="button" :disabled="hotLoading" @click="hotRange === 'live' ? loadHotItems() : loadHistory(hotRange)">
            {{ hotLoading ? '刷新中…' : '刷新' }}
          </button>
        </div>
      </div>

      <div class="hot-range-switch" role="tablist" aria-label="热点时间范围">
        <button v-for="option in HOT_RANGE_OPTIONS" :key="option.value" type="button"
          class="hot-range-tab" :class="{ 'hot-range-tab-active': hotRange === option.value }"
          role="tab" :aria-selected="hotRange === option.value"
          :disabled="hotLoading" @click="switchHotRange(option.value)">
          {{ option.label }}
        </button>
      </div>
      <p v-if="hotRange !== 'live'" class="hot-range-note">
        {{ hotRange === 'today' ? '今日' : '最近 7 天' }}聚合自历史快照{{ snapshotCount > 0 ? `（${snapshotCount} 份）` : '，暂无归档' }}
      </p>

      <div v-if="hotLoading && !activeHotItems.length" class="hot-skeleton-list" aria-hidden="true">
        <div v-for="index in 5" :key="index" class="hot-skeleton"></div>
      </div>
      <p v-else-if="hotError" class="hot-empty">{{ hotError }}</p>
      <p v-else-if="!hasHotContent" class="hot-empty">暂无热点数据。</p>
      <template v-else>
        <div v-if="showHotTabs" class="hot-tabs" role="tablist">
          <button v-for="group in hotGroups" :key="group.platform" type="button"
            class="hot-tab" :class="{ 'hot-tab-active': activePlatform === group.platform }"
            role="tab" :aria-selected="activePlatform === group.platform"
            @click="activePlatform = group.platform">
            {{ group.label }} <span class="gl-num">{{ group.items.length }}</span>
          </button>
        </div>
        <ol id="homepage-hot-list" class="hot-list">
          <li v-for="item in visibleHotItems" :key="`${item.rank}-${item.title}`" class="hot-item">
            <span class="hot-rank gl-num">{{ item.rank }}</span>
            <div class="hot-main">
              <a v-if="item.url" class="hot-title-link" :href="item.url" target="_blank" rel="noreferrer">{{ item.title }}</a>
              <p v-else class="hot-title">{{ item.title }}</p>
              <div class="hot-meta-row">
                <span v-if="item.hotValue" class="hot-value gl-num">热度 {{ item.hotValue }}</span>
                <span v-if="item.sourceLabel" class="hot-source">{{ item.sourceLabel }}</span>
                <span v-if="hotRange !== 'live' && item.validUntil" class="hot-source">失效 {{ new Date(item.validUntil).toLocaleDateString('zh-CN') }}</span>
              </div>
            </div>
            <button type="button" class="hot-create-btn" @click="createFromHotTopic(item.title)">去创作</button>
          </li>
        </ol>
        <div v-if="activeHotItems.length > HOT_PREVIEW_COUNT" class="hot-list-footer">
          <span class="hot-count-note gl-num">{{ showAllHot ? activeHotItems.length : HOT_PREVIEW_COUNT }} / {{ activeHotItems.length }} 个选题</span>
          <button type="button" class="hot-expand-btn" aria-controls="homepage-hot-list" :aria-expanded="showAllHot" @click="showAllHot = !showAllHot">
            {{ showAllHot ? '收起选题' : `展开全部 ${activeHotItems.length} 个` }}
          </button>
        </div>
      </template>
    </section>

    <!-- 平台治理：仅登录后可见 -->
    <section v-if="isAuthenticated" class="gl-zone" aria-label="平台治理">
      <div class="gl-zone-head">
        <h3 class="gl-zone-title">安心合作</h3>
        <p class="gl-zone-note">合作中遇到问题，可随时查看记录或提交补充说明</p>
      </div>
      <div class="gl-zone-body">
        <!-- 任务书 #74：一级页签撤除后「平台治理」卡改道工作台并自动打开个人设置弹窗 -->
        <button type="button" class="gl-tile gl-tile-button capability-tile" @click="goComplaints">
          <span class="eyebrow">平台治理</span>
          <h3>举报投诉</h3>
          <p class="tile-copy">在任务、交付物或报名处直接举报；也可在此查看我的投诉与提交补充举报。</p>
          <span class="tile-arrow" aria-hidden="true">↗</span>
        </button>
      </div>
    </section>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useAuth } from '../../composables/useAuth'
import { useActiveIdentity } from '../../composables/useActiveIdentity'
import { useHomepageHotItems } from '../../composables/useHomepageHotItems'
import { normalizePlatformId } from '../../config/ai-platform-capabilities'
import type { CreationEntry } from '../../types/ai-creation'
import type { AppView } from '../../types/navigation'

const emit = defineEmits<{ 'request-login': []; 'open-creation': [entry: CreationEntry] }>()

const router = useRouter()
const { isAuthenticated } = useAuth()
const {
  activeSide, hasMerchantIdentity, hasRecommenderIdentity, identitiesLoaded,
} = useActiveIdentity()

const hasAnyIdentity = computed(() =>
  identitiesLoaded.value && (hasMerchantIdentity.value || hasRecommenderIdentity.value))

/** 身份登录时选定、会话内不切换：入口卡只随当前活动身份（双身份账号换身份=退出重登）。 */
const showMerchantEntry = computed(() =>
  hasMerchantIdentity.value && activeSide.value === 'merchant')
const showRecommenderEntry = computed(() =>
  hasRecommenderIdentity.value && activeSide.value === 'recommender')

const activeIdentityLabel = computed(() => {
  if (hasMerchantIdentity.value && hasRecommenderIdentity.value) {
    return activeSide.value === 'merchant' ? '商家' : '推荐官'
  }
  if (hasRecommenderIdentity.value && !hasMerchantIdentity.value) return '推荐官'
  return '商家'
})

// ---------- 创作灵感：全网热点（PRD §4.3 热点是创作手段，不作一级入口的独立模块） ----------
const { items: hotItems, groups: hotGroups, provider: hotProvider,
  fetchedAt: hotFetchedAt, loading: hotLoading, error: hotError,
  snapshotCount, loadHotItems, loadHistory } = useHomepageHotItems()

type HotRange = 'live' | 'today' | 'week'
const HOT_RANGE_OPTIONS: ReadonlyArray<{ value: HotRange; label: string }> = [
  { value: 'live', label: '实时' },
  { value: 'today', label: '今天' },
  { value: 'week', label: '本周' },
]
const hotRange = ref<HotRange>('live')
const activePlatform = ref('')
const showAllHot = ref(false)
const HOT_PREVIEW_COUNT = 6

async function switchHotRange(range: HotRange): Promise<void> {
  if (hotRange.value === range || hotLoading.value) return
  hotRange.value = range
  showAllHot.value = false
  if (range === 'live') { await loadHotItems(); return }
  await loadHistory(range)
}

const hotFetchedNote = computed(() => {
  if (!hotFetchedAt.value) return ''
  const t = new Date(hotFetchedAt.value)
  if (Number.isNaN(t.getTime())) return ''
  const pad = (n: number) => String(n).padStart(2, '0')
  return `抓取于 ${pad(t.getMonth() + 1)}-${pad(t.getDate())} ${pad(t.getHours())}:${pad(t.getMinutes())}`
})

/** 60s 实时榜与历史模式都按平台分组展示 tab；alapi 实时直接平铺。 */
const showHotTabs = computed(() =>
  hotGroups.value.length > 0 && (hotProvider.value === '60s' || hotRange.value !== 'live'))
const activeHotItems = computed(() => {
  if (showHotTabs.value) {
    return hotGroups.value.find((g) => g.platform === activePlatform.value)?.items ?? []
  }
  return hotItems.value
})
const hasHotContent = computed(() => showHotTabs.value
  ? hotGroups.value.some((g) => g.items.length > 0)
  : hotItems.value.length > 0)
const visibleHotItems = computed(() => showAllHot.value
  ? activeHotItems.value
  : activeHotItems.value.slice(0, HOT_PREVIEW_COUNT))

watch(hotGroups, (newGroups) => {
  if (newGroups.length > 0 && !newGroups.some((g) => g.platform === activePlatform.value)) {
    activePlatform.value = newGroups[0].platform
  }
}, { immediate: true })

/** 热点选题 → AI 中心（源=hot-topic，带入选题预填）。 */
function createFromHotTopic(title: string): void {
  emit('open-creation', {
    revision: Date.now(),
    platformId: normalizePlatformId(activePlatform.value),
    contentFormId: null,
    source: { type: 'hot-topic', title },
    prefill: { topic: title },
  })
}

onMounted(() => { void loadHotItems() })

function go(view: AppView): void {
  void router.push({ name: view })
}

/** 任务书 #74：「平台治理」卡改道——直达工作台个人设置弹窗的「举报与投诉」节。 */
function goComplaints(): void {
  void router.push({ path: '/grassland', query: { settings: 'complaints' } })
}
</script>

<style scoped>
.grassland-home {
  display: grid;
  gap: var(--space-lg);
}

/* Hero：田垄条带内两栏主张区 */
.hero {
  position: relative;
  isolation: isolate;
  overflow: hidden;
  display: grid;
  gap: var(--space-lg);
  grid-template-columns: minmax(0, 1.2fr) minmax(300px, 0.8fr);
  align-items: stretch;
  padding: var(--space-xl);
  border-color: var(--color-border-accent);
  background: var(--surface-card);
  box-shadow: var(--shadow-elevated);
}

.hero-mesh {
  position: absolute;
  z-index: -1;
  inset: 0;
  pointer-events: none;
  opacity: 0.95;
}

.hero-mesh::after {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(180deg, transparent 0%, var(--surface-card) 92%);
}

.hero-mesh-svg {
  width: 100%;
  height: 100%;
}

.hero-grid-line {
  fill: none;
  stroke: var(--hero-grid);
  stroke-width: 1;
}

.hero-copy {
  position: relative;
  z-index: 1;
  display: grid;
  gap: var(--space-sm);
  align-content: start;
}

.hero-kicker {
  display: inline-flex;
  align-items: center;
  gap: var(--space-xs);
  width: fit-content;
  color: var(--color-text-muted);
  font-size: var(--text-xs);
  font-weight: 600;
  letter-spacing: 0.1em;
}

.hero-kicker-dot,
.hero-status-dot {
  display: inline-block;
  width: var(--space-xs);
  height: var(--space-xs);
  border-radius: var(--radius-pill);
  background: var(--color-grass);
}

.eyebrow {
  margin: 0;
  font-size: var(--text-xs);
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--color-text-muted);
  font-weight: 600;
}

.hero-title {
  margin: 0;
  font-family: var(--font-display);
  font-size: var(--text-hero);
  line-height: 1.03;
  letter-spacing: -0.04em;
  font-weight: 300;
  color: var(--color-text);
}

.hero-title span,
.hero-title em {
  display: block;
}

.hero-title em {
  font-style: normal;
  color: var(--color-accent-2);
}

.hero-note {
  margin: 0;
  max-width: 56ch;
  font-size: var(--text-lg);
  color: var(--color-text-secondary);
  line-height: 1.7;
}

.hero-actions {
  display: flex;
  gap: var(--space-sm);
  flex-wrap: wrap;
  margin-top: var(--space-xs);
}

.hero-cta {
  min-height: calc(var(--space-xl) + var(--space-xs));
  padding: 0 var(--space-lg);
  font-size: var(--text-lg);
}

.hero-secondary-cta {
  display: inline-flex;
  align-items: center;
  gap: var(--space-xs);
  min-height: calc(var(--space-xl) + var(--space-xs));
  padding: 0 var(--space-md);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-pill);
  background: var(--surface-card);
  color: var(--color-text-secondary);
  font-size: var(--text-sm);
  cursor: pointer;
  transition: color var(--duration-fast) var(--ease-out), border-color var(--duration-fast) var(--ease-out), transform var(--duration-fast) var(--ease-out);
}

.hero-secondary-cta:hover {
  border-color: var(--color-border-accent);
  color: var(--color-text);
  transform: translateY(-1px);
}


.hero-identity {
  margin: 0;
  font-size: var(--text-sm);
  color: var(--color-text-muted);
}

.hero-identity strong {
  color: var(--color-accent-2);
  font-weight: 600;
}

.hero-proof {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-md);
  margin-top: var(--space-md);
  padding-top: var(--space-md);
  border-top: 1px solid var(--color-border);
  color: var(--color-text-muted);
  font-size: var(--text-xs);
}

.hero-proof span {
  display: inline-flex;
  align-items: center;
  gap: var(--space-xs);
}

.hero-proof strong {
  color: var(--color-accent-2);
  font-weight: 500;
}

.hero-meta {
  display: grid;
  gap: var(--space-sm);
  align-content: start;
  padding: var(--space-lg);
  border: 1px solid var(--hero-grid);
  border-radius: var(--radius-xl);
  background: var(--hero-panel);
  box-shadow: var(--shadow-card);
  backdrop-filter: blur(18px);
  -webkit-backdrop-filter: blur(18px);
}

.hero-meta-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-sm);
  padding-bottom: var(--space-sm);
  border-bottom: 1px solid var(--hero-grid);
}

.hero-meta-label,
.role-mark {
  color: var(--color-text-muted);
  font-size: var(--text-xs);
  font-weight: 600;
  letter-spacing: 0.08em;
}

.hero-meta .badge {
  color: var(--color-accent-2);
  background: color-mix(in srgb, var(--color-accent) 12%, transparent);
}

.meta-step {
  display: grid;
  grid-template-columns: var(--space-xl) 1fr;
  gap: var(--space-sm);
  align-items: start;
  padding: var(--space-sm) 0;
  border-bottom: 1px solid var(--hero-grid);
}

.meta-step .meta-index {
  font-size: var(--text-xl);
  font-weight: 300;
  color: var(--color-accent-2);
}

.meta-step strong {
  font-size: var(--text-sm);
  color: var(--color-text);
  font-weight: 600;
}

.meta-step p {
  margin: 0;
  font-size: var(--text-xs);
  color: var(--color-text-muted);
  line-height: 1.5;
}

.hero-meta-footer {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  padding-top: var(--space-xs);
  color: var(--color-text-muted);
  font-size: var(--text-xs);
}

.hero-status-dot {
  box-shadow: 0 0 0 var(--space-xs) var(--color-grass-dim);
}

.tile-copy {
  margin: 6px 0 0;
  font-size: var(--text-sm);
  color: var(--color-text-secondary);
  line-height: 1.6;
}

.role-tile,
.capability-tile {
  position: relative;
  min-height: calc(var(--space-xl) * 5);
  overflow: hidden;
  border: 1px solid transparent;
  transition: border-color var(--duration-fast) var(--ease-out), background var(--duration-fast) var(--ease-out), transform var(--duration-fast) var(--ease-out);
}

.role-tile:hover,
.capability-tile:hover {
  border-color: var(--color-border-accent);
  background: var(--color-surface-highlight);
  transform: translateY(-2px);
}

.role-tile::after,
.capability-tile::after {
  content: '';
  position: absolute;
  right: calc(var(--space-xl) * -1);
  bottom: calc(var(--space-xl) * -1);
  width: calc(var(--space-xl) * 4);
  height: calc(var(--space-xl) * 4);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-pill);
  opacity: 0.6;
}

.role-tile h3,
.capability-tile h3 {
  position: relative;
  z-index: 1;
  max-width: 18ch;
  font-family: var(--font-display);
  font-size: var(--text-xl);
  font-weight: 300;
  letter-spacing: -0.02em;
}

.role-tile .tile-copy,
.capability-tile .tile-copy {
  position: relative;
  z-index: 1;
  max-width: 42ch;
}

.tile-arrow {
  position: absolute;
  z-index: 1;
  right: var(--space-md);
  top: var(--space-md);
  color: var(--color-accent-2);
  font-size: var(--text-xl);
  line-height: 1;
}

.role-tile-merchant { background: color-mix(in srgb, var(--color-accent) 7%, var(--surface-furrow)); }
.role-tile-recommender { background: color-mix(in srgb, var(--color-grass) 7%, var(--surface-furrow)); }
.role-tile-ai { background: color-mix(in srgb, var(--color-accent-warm) 6%, var(--surface-furrow)); }

.role-mark { position: relative; z-index: 1; }

.workbench-tile {
  display: grid;
  gap: 4px;
  align-content: start;
  text-align: left;
}

.tile-link {
  justify-self: start;
  margin-top: var(--space-xs);
  padding: 4px 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--color-accent-2);
  font-size: var(--text-xs);
  cursor: pointer;
}

.hot-zone .gl-zone-head { flex-wrap: wrap; }
.hot-refresh-group { display: flex; align-items: center; gap: 8px; margin-left: auto; }
.hot-fetched-note { font-size: var(--text-xs); color: var(--color-text-muted); }
.hot-range-switch { display: flex; gap: 4px; padding: 3px; border: 1px solid var(--color-border); border-radius: var(--radius-md); width: fit-content; margin-bottom: 12px; }
.hot-range-tab { min-height: 30px; padding: 0 12px; border: none; border-radius: var(--radius-xs); background: transparent; color: var(--color-text-muted); font-size: var(--text-xs); font-weight: 600; cursor: pointer; }
.hot-range-tab-active { background: var(--gradient-accent); color: var(--color-on-accent); }
.hot-range-note { margin: 0 0 10px; font-size: var(--text-xs); color: var(--color-text-muted); }
.hot-tabs { display: flex; gap: 4px; flex-wrap: wrap; margin-bottom: 10px; }
.hot-tab { min-height: 30px; padding: 0 12px; border: 1px solid var(--color-border); border-radius: var(--radius-pill); background: transparent; color: var(--color-text-muted); font-size: var(--text-xs); font-weight: 600; cursor: pointer; }
.hot-tab-active { border-color: var(--color-border-accent); background: var(--color-surface-highlight); color: var(--color-accent-2); }
.hot-list { list-style: none; margin: 0; padding: 0; display: grid; gap: 4px; }
.hot-item { display: flex; align-items: center; gap: 12px; padding: 8px 10px; border: 1px solid var(--color-border); border-radius: var(--radius-sm); }
.hot-rank { flex-shrink: 0; width: 24px; text-align: center; font-size: 0.9rem; font-weight: 700; color: var(--color-accent-2); }
.hot-main { flex: 1; min-width: 0; display: grid; gap: 2px; }
.hot-title-link, .hot-title { margin: 0; font-size: var(--text-sm); font-weight: 600; color: var(--color-text); text-decoration: none; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.hot-title-link:hover { text-decoration: underline; }
.hot-meta-row { display: flex; gap: 10px; font-size: var(--text-xs); color: var(--color-text-muted); }
.hot-create-btn { flex-shrink: 0; min-height: 30px; padding: 0 14px; border: 1px solid var(--color-border-accent); border-radius: var(--radius-pill); background: var(--color-surface-highlight); color: var(--color-accent-2); font-size: var(--text-xs); font-weight: 600; cursor: pointer; }
.hot-create-btn:hover { border-color: var(--color-accent-2); }
.hot-skeleton-list { display: grid; gap: 6px; }
.hot-skeleton { height: 40px; border-radius: var(--radius-sm); background: var(--surface-muted); animation: hot-skeleton-pulse 1.4s ease-in-out infinite; }
.hot-skeleton:nth-child(2) { animation-delay: 0.15s; }
.hot-skeleton:nth-child(3) { animation-delay: 0.3s; }
.hot-skeleton:nth-child(4) { animation-delay: 0.45s; }
.hot-skeleton:nth-child(5) { animation-delay: 0.6s; }
@keyframes hot-skeleton-pulse { 0%, 100% { opacity: 0.5; } 50% { opacity: 1; } }
.hot-empty { margin: 0; padding: var(--space-sm) 0; font-size: var(--text-sm); color: var(--color-text-muted); }
.hot-list-footer { display: flex; align-items: center; justify-content: space-between; gap: var(--space-sm); padding-top: var(--space-sm); border-top: 1px solid var(--color-border); }
.hot-count-note { color: var(--color-text-muted); font-size: var(--text-xs); }
.hot-expand-btn { min-height: 30px; padding: 0 var(--space-sm); border: 1px solid var(--color-border-accent); border-radius: var(--radius-pill); background: var(--color-surface-highlight); color: var(--color-accent-2); font-size: var(--text-xs); font-weight: 600; cursor: pointer; }

@media (max-width: 900px) {
  .hero {
    grid-template-columns: 1fr;
  }

  .hero-meta {
    grid-template-columns: repeat(3, 1fr);
    align-items: start;
  }

  .hero-meta-head,
  .hero-meta-footer {
    grid-column: 1 / -1;
  }
}

@media (max-width: 560px) {
  .hero { padding: var(--space-lg); }
  .hero-title { font-size: var(--text-hero); }
  .hero-actions { align-items: stretch; flex-direction: column; }
  .hero-cta,
  .hero-secondary-cta { justify-content: center; width: 100%; }
  .hero-meta { grid-template-columns: 1fr; padding: var(--space-md); }
  .hero-meta-head,
  .hero-meta-footer { grid-column: auto; }
}
</style>
