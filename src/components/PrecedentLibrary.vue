<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { request } from '../composables/grassland-http'
import type { PrecedentCase } from '../types/grassland'

/** 解析 JSON 数组字段（畸形/非数组按空数组处理，不阻断列表）。 */
function parseJsonArray<T>(raw: string | null): T[] {
  if (!raw) return []
  try {
    const parsed: unknown = JSON.parse(raw)
    return Array.isArray(parsed) ? (parsed as T[]) : []
  } catch {
    return []
  }
}

/**
 * 视图模型：后端行 + 解析后的终局轮投票分布与理由列表——voteSummary/rationaleDigest 在
 * 后端是 JSON 字符串（各轮分布数组 / 理由摘要数组），此处解析一次供模板直读。
 */
interface PrecedentView extends PrecedentCase {
  forMerchant: number
  forRecommender: number
  abstain: number
  rationales: string[]
}

interface RoundSummary {
  forMerchant?: number
  forRecommender?: number
  abstain?: number
}

function toView(c: PrecedentCase): PrecedentView {
  const rounds = parseJsonArray<RoundSummary>(c.voteSummary)
  const last = rounds.length > 0 ? rounds[rounds.length - 1] : {}
  const rationales = parseJsonArray<string>(c.rationaleDigest)
  return {
    ...c,
    forMerchant: last.forMerchant || 0,
    forRecommender: last.forRecommender || 0,
    abstain: last.abstain || 0,
    rationales,
  }
}

const PAGE_SIZE = 20

const cases = ref<PrecedentView[]>([])
const loading = ref(false)
const loadError = ref('')
const hasMore = ref(true)
const page = ref(1)
const selectedCase = ref<PrecedentView | null>(null)
const showDetail = ref(false)

// 筛选器（平台 + 争议类型；task_type v1 无事实源，不提供筛选项）
const selectedPlatform = ref<string>('')
const selectedKind = ref<string>('')

const platformOptions = [
  { value: '', label: '全部平台' },
  { value: 'xiaohongshu', label: '小红书' },
  { value: 'douyin', label: '抖音' },
  { value: 'zhihu', label: '知乎' },
  { value: 'bilibili', label: 'B站' },
  { value: 'weixin', label: '微信公众号' },
  { value: 'taobao', label: '淘宝' },
  { value: 'dianping', label: '大众点评' }
]

/** 争议类型与后端 dispute_kind 对齐（standard / merchant_rejection）。 */
const kindOptions = [
  { value: '', label: '全部争议' },
  { value: 'standard', label: '履约争议' },
  { value: 'merchant_rejection', label: '商家履约异议' }
]

async function loadCases(reset = false) {
  if (loading.value || (!hasMore.value && !reset)) return

  if (reset) {
    cases.value = []
    page.value = 1
    hasMore.value = true
    loadError.value = ''
  }

  loading.value = true
  try {
    const params = new URLSearchParams()
    if (selectedPlatform.value) params.set('platform', selectedPlatform.value)
    if (selectedKind.value) params.set('kind', selectedKind.value)
    params.set('page', String(page.value))
    params.set('pageSize', String(PAGE_SIZE))
    // request 统一解 {success,data} 信封（后端契约：items/page/pageSize/total/hasMore）
    const data = await request<{ items: PrecedentCase[]; hasMore: boolean }>(
      `/api/trust/precedents?${params.toString()}`)
    const views = (data.items || []).map(toView)
    cases.value = reset ? views : [...cases.value, ...views]
    hasMore.value = data.hasMore
    page.value += 1
  } catch (err) {
    console.error('加载判例失败:', err)
    loadError.value = err instanceof Error ? err.message : '加载判例失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

function openDetail(c: PrecedentView) {
  selectedCase.value = c
  showDetail.value = true
}

function closeDetail() {
  showDetail.value = false
  selectedCase.value = null
}

function getPlatformLabel(platform: string | null): string {
  return platformOptions.find(p => p.value === platform)?.label || platform || '未记录平台'
}

function getKindLabel(kind: string | null): string {
  return kindOptions.find(k => k.value === kind)?.label || kind || '履约争议'
}

function getDecisionLabel(decision: string | null): string {
  const labels: Record<string, string> = {
    for_merchant: '商家胜诉',
    for_recommender: '推荐官胜诉'
  }
  return labels[decision || ''] || '待裁决'
}

/** 终局经由与后端 final_via 对齐：panel / cs / retrial。 */
function getFinalViaLabel(via: string | null): string {
  const labels: Record<string, string> = {
    panel: '七官面板裁决',
    cs: '客服终审',
    retrial: '发回重审后终局'
  }
  return labels[via || ''] || '面板裁决'
}

function getVotePercentage(count: number, total: number): number {
  return total === 0 ? 0 : Math.round((count / total) * 100)
}

const totalVotes = computed(() => {
  if (!selectedCase.value) return 0
  return selectedCase.value.forMerchant + selectedCase.value.forRecommender + selectedCase.value.abstain
})

onMounted(() => {
  loadCases(true)
})

function applyFilters() {
  loadCases(true)
}
</script>


<template>
  <div class="precedent-library">
    <div class="library-header">
      <h1>判例库</h1>
      <p class="subtitle">往期争议裁决案例，供参考学习</p>
    </div>

    <!-- 筛选器 -->
    <div class="filters glass-card">
      <div class="filter-group">
        <label>平台</label>
        <select v-model="selectedPlatform" @change="applyFilters">
          <option v-for="opt in platformOptions" :key="opt.value" :value="opt.value">
            {{ opt.label }}
          </option>
        </select>
      </div>

      <div class="filter-group">
        <label>争议类型</label>
        <select v-model="selectedKind" @change="applyFilters">
          <option v-for="opt in kindOptions" :key="opt.value" :value="opt.value">
            {{ opt.label }}
          </option>
        </select>
      </div>
    </div>

    <!-- 判例列表 -->
    <div v-if="loadError" class="empty-state glass-card">
      <p>{{ loadError }}</p>
      <button type="button" class="load-more-btn" @click="loadCases(true)">重试</button>
    </div>

    <div v-else-if="cases.length === 0 && !loading" class="empty-state glass-card">
      <p>暂无符合条件的判例</p>
    </div>

    <div class="cases-grid">
      <div
        v-for="c in cases"
        :key="c.id"
        class="case-card glass-card"
        @click="openDetail(c)"
      >
        <div class="case-header">
          <div class="badges">
            <span class="badge platform">{{ getPlatformLabel(c.taskPlatform) }}</span>
            <span class="badge kind">{{ getKindLabel(c.disputeKind) }}</span>
          </div>
          <span class="decision-badge" :class="c.decision">
            {{ getDecisionLabel(c.decision) }}
          </span>
        </div>

        <h3 class="focus">{{ c.focus }}</h3>
        <p class="claims-preview">{{ (c.claimsSummary || '').slice(0, 120) }}{{ (c.claimsSummary || '').length > 120 ? '...' : '' }}</p>

        <div class="vote-bar">
          <div
            class="vote-segment merchant"
            :style="{ width: getVotePercentage(c.forMerchant, c.forMerchant + c.forRecommender + c.abstain) + '%' }"
          ></div>
          <div
            class="vote-segment recommender"
            :style="{ width: getVotePercentage(c.forRecommender, c.forMerchant + c.forRecommender + c.abstain) + '%' }"
          ></div>
          <div
            class="vote-segment abstain"
            :style="{ width: getVotePercentage(c.abstain, c.forMerchant + c.forRecommender + c.abstain) + '%' }"
          ></div>
        </div>

        <div class="vote-counts">
          <span class="merchant-count">商家 {{ c.forMerchant }}</span>
          <span class="recommender-count">推荐官 {{ c.forRecommender }}</span>
          <span class="abstain-count">弃权 {{ c.abstain }}</span>
        </div>

        <div class="case-footer">
          <span class="final-via">{{ getFinalViaLabel(c.finalVia) }}</span>
          <span class="date">{{ c.createdAt ? new Date(c.createdAt).toLocaleDateString('zh-CN') : '-' }}</span>
        </div>
      </div>
    </div>

    <!-- 加载更多 -->
    <div v-if="hasMore" class="load-more-container">
      <button
        class="load-more-btn"
        :disabled="loading"
        @click="loadCases(false)"
      >
        {{ loading ? '加载中...' : '加载更多' }}
      </button>
    </div>

    <!-- 详情抽屉 -->
    <div v-if="showDetail" class="detail-overlay" @click="closeDetail">
      <div class="detail-drawer glass-card" @click.stop>
        <div class="drawer-header">
          <h2>判例详情</h2>
          <button class="close-btn" @click="closeDetail">✕</button>
        </div>

        <div v-if="selectedCase" class="drawer-content">
          <div class="detail-section">
            <div class="badges">
              <span class="badge platform">{{ getPlatformLabel(selectedCase.taskPlatform) }}</span>
              <span class="badge kind">{{ getKindLabel(selectedCase.disputeKind) }}</span>
            </div>
            <h3 class="focus">{{ selectedCase.focus }}</h3>
          </div>

          <div class="detail-section">
            <h4>争议焦点</h4>
            <p>{{ selectedCase.claimsSummary }}</p>
          </div>

          <div class="detail-section">
            <h4>裁决结果</h4>
            <div class="decision-info">
              <span class="decision-badge large" :class="selectedCase.decision">
                {{ getDecisionLabel(selectedCase.decision) }}
              </span>
              <span class="final-via-info">{{ getFinalViaLabel(selectedCase.finalVia) }}</span>
            </div>
          </div>

          <div class="detail-section">
            <h4>投票分布</h4>
            <div class="vote-bar large">
              <div
                class="vote-segment merchant"
                :style="{ width: getVotePercentage(selectedCase.forMerchant, totalVotes) + '%' }"
              >
                <span v-if="getVotePercentage(selectedCase.forMerchant, totalVotes) > 15">
                  {{ selectedCase.forMerchant }}
                </span>
              </div>
              <div
                class="vote-segment recommender"
                :style="{ width: getVotePercentage(selectedCase.forRecommender, totalVotes) + '%' }"
              >
                <span v-if="getVotePercentage(selectedCase.forRecommender, totalVotes) > 15">
                  {{ selectedCase.forRecommender }}
                </span>
              </div>
              <div
                class="vote-segment abstain"
                :style="{ width: getVotePercentage(selectedCase.abstain, totalVotes) + '%' }"
              >
                <span v-if="getVotePercentage(selectedCase.abstain, totalVotes) > 15">
                  {{ selectedCase.abstain }}
                </span>
              </div>
            </div>
            <div class="vote-legend">
              <div class="legend-item">
                <span class="legend-color merchant"></span>
                <span>支持商家: {{ selectedCase.forMerchant }}票</span>
              </div>
              <div class="legend-item">
                <span class="legend-color recommender"></span>
                <span>支持推荐官: {{ selectedCase.forRecommender }}票</span>
              </div>
              <div class="legend-item">
                <span class="legend-color abstain"></span>
                <span>弃权: {{ selectedCase.abstain }}票</span>
              </div>
            </div>
          </div>

          <div class="detail-section">
            <h4>投票理由摘要</h4>

            <div v-if="selectedCase.rationales.length > 0" class="rationale-group">
              <ul class="rationale-list">
                <li v-for="(r, idx) in selectedCase.rationales" :key="idx">{{ r }}</li>
              </ul>
            </div>

            <p v-else class="claims-preview">该判例无投票理由摘要（客服直裁案件不产生投票记录）</p>
          </div>

          <div class="detail-footer">
            <span class="dispute-id">争议编号: {{ selectedCase.disputeId }}</span>
            <span class="date">{{ selectedCase.createdAt ? new Date(selectedCase.createdAt).toLocaleDateString('zh-CN', { year: 'numeric', month: 'long', day: 'numeric' }) : '-' }}</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.precedent-library {
  max-width: 1200px;
  margin: 0 auto;
  padding: var(--space-xl);
}

.library-header {
  margin-bottom: var(--space-xl);
}

.library-header h1 {
  font-size: clamp(1.75rem, 4vw, 2.5rem);
  font-weight: 600;
  margin-bottom: var(--space-xs);
  color: var(--color-text);
}

.subtitle {
  font-size: clamp(0.875rem, 2vw, 1rem);
  color: var(--color-text-secondary);
}

.filters {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: var(--space-lg);
  padding: var(--space-lg);
  margin-bottom: var(--space-xl);
}

.filter-group {
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
}

.filter-group label {
  font-size: 0.875rem;
  font-weight: 500;
  color: var(--color-text-secondary);
}

.filter-group select {
  padding: var(--space-sm) var(--space-md);
  background: var(--surface-hover);
  border: 1px solid var(--surface-elevated);
  border-radius: var(--radius-md);
  color: var(--color-text);
  font-size: 0.9375rem;
  cursor: pointer;
  transition: all 0.2s;
}

.filter-group select:hover {
  border-color: var(--color-accent);
}

.filter-group select:focus {
  outline: none;
  border-color: var(--color-accent);
  box-shadow: 0 0 0 3px rgba(83, 58, 253, 0.1);
}

.empty-state {
  padding: var(--space-xl);
  text-align: center;
  color: var(--color-text-secondary);
}

.cases-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
  gap: var(--space-lg);
  margin-bottom: var(--space-xl);
}

.case-card {
  padding: var(--space-lg);
  cursor: pointer;
  transition: all 0.3s;
}

.case-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.15);
}

.case-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: var(--space-md);
}

.badges {
  display: flex;
  gap: var(--space-xs);
  flex-wrap: wrap;
}

.badge {
  padding: var(--space-xs) var(--space-sm);
  border-radius: 999px;
  font-size: 0.75rem;
  font-weight: 500;
}

.badge.platform {
  background: var(--surface-elevated);
  color: var(--color-text-secondary);
}

.badge.kind {
  background: var(--surface-elevated);
  color: var(--color-text);
}

.decision-badge {
  padding: var(--space-xs) var(--space-sm);
  border-radius: 999px;
  font-size: 0.75rem;
  font-weight: 600;
  white-space: nowrap;
}

.decision-badge.for_merchant {
  background: rgba(16, 185, 129, 0.15);
  color: var(--color-success);
}

.decision-badge.for_recommender {
  background: rgba(59, 130, 246, 0.15);
  color: var(--color-info);
}

.decision-badge.merchant_partial,
.decision-badge.recommender_partial {
  background: rgba(245, 158, 11, 0.15);
  color: var(--color-warning);
}

.focus {
  font-size: 1.125rem;
  font-weight: 600;
  margin-bottom: var(--space-sm);
  color: var(--color-text);
  line-height: 1.4;
}

.claims-preview {
  font-size: 0.875rem;
  color: var(--color-text-secondary);
  line-height: 1.6;
  margin-bottom: var(--space-md);
}

.vote-bar {
  display: flex;
  height: 8px;
  border-radius: 999px;
  overflow: hidden;
  background: var(--surface-hover);
  margin-bottom: var(--space-sm);
}

.vote-bar.large {
  height: 32px;
  margin-bottom: var(--space-md);
}

.vote-segment {
  transition: width 0.3s;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 0.75rem;
  font-weight: 600;
  color: var(--color-on-accent);
}

.vote-segment.merchant {
  background: var(--color-success);
}

.vote-segment.recommender {
  background: var(--color-info);
}

.vote-segment.abstain {
  background: var(--color-text-muted);
}

.vote-counts {
  display: flex;
  gap: var(--space-md);
  font-size: 0.75rem;
  margin-bottom: var(--space-md);
}

.vote-counts span {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
}

.merchant-count {
  color: var(--color-success);
}

.recommender-count {
  color: var(--color-info);
}

.abstain-count {
  color: var(--color-text-muted);
}

.case-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-top: var(--space-md);
  border-top: 1px solid var(--surface-elevated);
  font-size: 0.8125rem;
}

.final-via {
  color: var(--color-text-secondary);
  font-weight: 500;
}

.date {
  color: var(--color-text-muted);
}

.load-more-container {
  display: flex;
  justify-content: center;
  padding: var(--space-xl) 0;
}

.load-more-btn {
  padding: var(--space-md) var(--space-xl);
  background: var(--color-accent);
  color: var(--color-on-accent);
  border: none;
  border-radius: 999px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
}

.load-more-btn:hover:not(:disabled) {
  background: var(--color-accent);
  transform: translateY(-1px);
}

.load-more-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.detail-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.6);
  backdrop-filter: blur(4px);
  z-index: 1000;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  animation: fadeIn 0.2s;
}

@keyframes fadeIn {
  from {
    opacity: 0;
  }
  to {
    opacity: 1;
  }
}

.detail-drawer {
  width: 600px;
  max-width: 90vw;
  height: 100vh;
  overflow-y: auto;
  animation: slideIn 0.3s;
}

@keyframes slideIn {
  from {
    transform: translateX(100%);
  }
  to {
    transform: translateX(0);
  }
}

.drawer-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: var(--space-xl);
  border-bottom: 1px solid var(--surface-elevated);
  position: sticky;
  top: 0;
  background: var(--surface-card);
  z-index: 10;
}

.drawer-header h2 {
  font-size: 1.25rem;
  font-weight: 600;
  color: var(--color-text);
}

.close-btn {
  width: 32px;
  height: 32px;
  border-radius: 999px;
  border: none;
  background: var(--surface-elevated);
  color: var(--color-text);
  font-size: 1.25rem;
  cursor: pointer;
  transition: all 0.2s;
  display: flex;
  align-items: center;
  justify-content: center;
}

.close-btn:hover {
  background: var(--surface-elevated);
}

.drawer-content {
  padding: var(--space-xl);
}

.detail-section {
  margin-bottom: var(--space-xl);
}

.detail-section h4 {
  font-size: 1rem;
  font-weight: 600;
  margin-bottom: var(--space-md);
  color: var(--color-text);
}

.detail-section h5 {
  font-size: 0.875rem;
  font-weight: 600;
  margin-bottom: var(--space-sm);
  color: var(--color-text-secondary);
}

.detail-section p {
  font-size: 0.9375rem;
  line-height: 1.7;
  color: var(--color-text-secondary);
}

.decision-info {
  display: flex;
  align-items: center;
  gap: var(--space-md);
}

.decision-badge.large {
  padding: var(--space-sm) var(--space-lg);
  font-size: 0.9375rem;
}

.final-via-info {
  font-size: 0.875rem;
  color: var(--color-text-secondary);
  padding: var(--space-xs) var(--space-md);
  background: var(--surface-hover);
  border-radius: 999px;
}

.vote-legend {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
}

.legend-item {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  font-size: 0.875rem;
  color: var(--color-text-secondary);
}

.legend-color {
  width: 16px;
  height: 16px;
  border-radius: 4px;
}

.legend-color.merchant {
  background: var(--color-success);
}

.legend-color.recommender {
  background: var(--color-info);
}

.legend-color.abstain {
  background: var(--color-text-muted);
}

.rationale-group {
  margin-bottom: var(--space-lg);
}

.rationale-list {
  list-style: none;
  padding: 0;
}

.rationale-list li {
  padding: var(--space-sm) var(--space-md);
  margin-bottom: var(--space-xs);
  background: var(--surface-hover);
  border-left: 3px solid var(--color-accent);
  border-radius: var(--radius-sm);
  font-size: 0.875rem;
  line-height: 1.6;
  color: var(--color-text-secondary);
}

.detail-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-top: var(--space-lg);
  border-top: 1px solid var(--surface-elevated);
  font-size: 0.8125rem;
}

.dispute-id {
  color: var(--color-text-muted);
  font-family: monospace;
}

@media (max-width: 768px) {
  .precedent-library {
    padding: var(--space-lg);
  }

  .filters {
    grid-template-columns: 1fr;
  }

  .cases-grid {
    grid-template-columns: 1fr;
  }

  .detail-drawer {
    width: 100vw;
  }
}
</style>