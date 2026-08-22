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
          <button type="button" class="hero-cta-secondary" @click="go('commerce')">到店消费</button>
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

    <!-- 共享能力：三类角色都可用 -->
    <section class="gl-zone" aria-label="共享能力">
      <div class="gl-zone-head">
        <h3 class="gl-zone-title">共享能力</h3>
        <p class="gl-zone-note">商家与推荐官都可使用；消费者场景对所有用户开放</p>
      </div>
      <div class="gl-zone-body">
        <button type="button" class="gl-tile gl-tile-button gl-tile-button-primary" @click="go('ai-center')">
          <span class="eyebrow">AI 内容创作中心</span>
          <h3>按发布平台创作图文与视频</h3>
          <p class="tile-copy">选平台、定形式，从独立创作、任务、门店或热点出发；热点、参考视频与图片生成都是创作手段。</p>
        </button>
        <button type="button" class="gl-tile gl-tile-button" @click="go('commerce')">
          <span class="eyebrow">消费者场景</span>
          <h3>到店消费</h3>
          <p class="tile-copy">扫码下单、核销码与消费订单——任何注册用户都可使用，不需要单独开通身份。</p>
        </button>
        <button v-if="isAuthenticated" type="button" class="gl-tile gl-tile-button" @click="go('complaints')">
          <span class="eyebrow">平台治理</span>
          <h3>举报投诉</h3>
          <p class="tile-copy">对任务、内容、订单或用户提交投诉，客服会在处置台受理。</p>
        </button>
      </div>
    </section>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAuth } from '../../composables/useAuth'
import { useActiveIdentity } from '../../composables/useActiveIdentity'
import type { AppView } from '../../types/navigation'

const emit = defineEmits<{ 'request-login': [] }>()

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

function go(view: AppView): void {
  void router.push({ name: view })
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
  font-size: clamp(1.7rem, 2.6vw, 2.6rem);
  line-height: 1.15;
  letter-spacing: -0.03em;
  font-weight: 800;
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

.hero-cta-secondary {
  min-height: 44px;
  padding: 0 22px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--surface-card);
  color: var(--color-text-secondary);
  font-size: 0.92rem;
  cursor: pointer;
  transition: border-color var(--duration-fast) var(--ease-out), color var(--duration-fast) var(--ease-out);
}

.hero-cta-secondary:hover {
  border-color: var(--color-border-hover);
  color: var(--color-text);
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

@media (max-width: 900px) {
  .hero {
    grid-template-columns: 1fr;
  }
}
</style>
