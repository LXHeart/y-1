<template>
  <section class="grassland-home gl-field" aria-labelledby="grassland-home-title">
    <!-- Hero：平台主张（PRD §一：草场 = 撮合平台，AI 中心是内置能力而非门面） -->
    <section class="gl-zone hero">
      <div class="hero-copy">
        <p class="eyebrow">草场</p>
        <h2 id="grassland-home-title" class="hero-title">商家 × 推荐官 的种草推广任务撮合平台</h2>
        <p class="hero-note">
          商家发布推广任务，推荐官接单创作并发布到各大社交平台；平台以资金托管与自动核实保障双方权益，
          内置 AI 内容创作工具降低创作门槛。
        </p>
        <div v-if="!isAuthenticated" class="hero-actions">
          <button type="button" class="gl-btn-primary hero-cta" @click="emit('request-login')">登录 / 注册</button>
        </div>
        <p v-else class="hero-identity">
          当前身份：<strong>{{ activeIdentityLabel }}</strong>（换身份请退出后重新登录）
        </p>
      </div>
      <div class="hero-meta" aria-label="平台运转三步">
        <div class="meta-step">
          <span class="meta-index gl-num">01</span>
          <strong>商家发布任务</strong>
          <p>主体、门店、素材与佣金规则一次配齐</p>
        </div>
        <div class="meta-step">
          <span class="meta-index gl-num">02</span>
          <strong>推荐官创作发布</strong>
          <p>任务大厅报名，AI 中心辅助完成图文与视频</p>
        </div>
        <div class="meta-step">
          <span class="meta-index gl-num">03</span>
          <strong>平台托管结算</strong>
          <p>自动核实、商家确认，达标即结算</p>
        </div>
      </div>
    </section>

    <!-- 未登录：平台介绍 + 创作体验入口 -->
    <section v-if="!isAuthenticated" class="gl-zone" aria-label="平台介绍">
      <div class="gl-zone-head">
        <h3 class="gl-zone-title">两种身份，一个账号</h3>
        <p class="gl-zone-note">注册后可同时开通商家与推荐官身份，随时切换</p>
      </div>
      <div class="gl-zone-body">
        <article class="gl-tile">
          <h3>商家身份</h3>
          <p class="tile-copy">创建商家主体与门店、发布推广任务、设置要求、管理资金、筛选推荐官、查看营收。</p>
        </article>
        <article class="gl-tile">
          <h3>推荐官身份</h3>
          <p class="tile-copy">浏览和报名任务、创作发布内容、提交凭证、获得任务收益，按等级解锁更多权益。</p>
        </article>
        <article class="gl-tile">
          <h3>AI 内容创作中心</h3>
          <p class="tile-copy">商家和推荐官共享的公共能力：按发布平台组织图文与视频创作，游客可有限体验。</p>
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
        <ol class="hot-list">
          <li v-for="item in activeHotItems" :key="`${item.rank}-${item.title}`" class="hot-item">
            <span class="hot-rank gl-num">{{ item.rank }}</span>
            <div class="hot-main">
              <a v-if="item.url" class="hot-title-link" :href="item.url" target="_blank" rel="noreferrer">{{ item.title }}</a>
              <p v-else class="hot-title">{{ item.title }}</p>
              <div class="hot-meta-row">
                <span v-if="item.hotValue" class="hot-value gl-num">热度 {{ item.hotValue }}</span>
                <span v-if="item.sourceLabel" class="hot-source">{{ item.sourceLabel }}</span>
                <span v-if="hotRange !== 'live' && item.validUntil" class="hot-source">失效 {{ new Date(item.validUntil).toLocaleDateString() }}</span>
              </div>
            </div>
            <button type="button" class="hot-create-btn" @click="createFromHotTopic(item.title)">去创作</button>
          </li>
        </ol>
      </template>
    </section>

    <!-- 共享能力：三类角色都可用 -->
    <section class="gl-zone" aria-label="共享能力">
      <div class="gl-zone-head">
        <h3 class="gl-zone-title">共享能力</h3>
        <p class="gl-zone-note">商家与推荐官都可使用</p>
      </div>
      <div class="gl-zone-body">
        <button type="button" class="gl-tile gl-tile-button gl-tile-button-primary" @click="go('ai-center')">
          <span class="eyebrow">AI 内容创作中心</span>
          <h3>按发布平台创作图文与视频</h3>
          <p class="tile-copy">选平台、定形式，从独立创作、任务、门店或热点出发；热点、参考视频与图片生成都是创作手段。</p>
        </button>
        <!-- 任务书 #74：一级页签撤除后「平台治理」卡改道工作台并自动打开个人设置弹窗 -->
        <button v-if="isAuthenticated" type="button" class="gl-tile gl-tile-button" @click="goComplaints">
          <span class="eyebrow">平台治理</span>
          <h3>举报投诉</h3>
          <p class="tile-copy">在任务、交付物或报名处直接举报；也可在此查看我的投诉与提交补充举报。</p>
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

async function switchHotRange(range: HotRange): Promise<void> {
  if (hotRange.value === range || hotLoading.value) return
  hotRange.value = range
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
  display: grid;
  gap: var(--space-lg);
  grid-template-columns: minmax(0, 1.6fr) minmax(260px, 1fr);
  align-items: stretch;
}

.hero-copy {
  display: grid;
  gap: var(--space-sm);
  align-content: start;
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
  font-size: clamp(1.7rem, 2.6vw, 2.6rem);
  line-height: 1.15;
  letter-spacing: -0.03em;
  font-weight: 700;
  color: var(--color-text);
}

.hero-note {
  margin: 0;
  max-width: 58ch;
  font-size: 0.95rem;
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
  min-height: 44px;
  padding: 0 26px;
  font-size: 0.95rem;
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

.hero-meta {
  display: grid;
  gap: var(--space-sm);
  align-content: start;
}

.meta-step {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 4px 12px;
  align-items: baseline;
  padding: var(--space-sm) var(--space-md);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--surface-muted);
}

.meta-step .meta-index {
  grid-row: span 2;
  font-size: 1.3rem;
  font-weight: 700;
  color: var(--color-accent-2);
  opacity: 0.85;
}

.meta-step strong {
  font-size: var(--text-sm);
  color: var(--color-text);
}

.meta-step p {
  margin: 0;
  font-size: var(--text-xs);
  color: var(--color-text-muted);
  line-height: 1.5;
}

.tile-copy {
  margin: 6px 0 0;
  font-size: var(--text-sm);
  color: var(--color-text-secondary);
  line-height: 1.6;
}

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

@media (max-width: 900px) {
  .hero {
    grid-template-columns: 1fr;
  }
}
</style>
