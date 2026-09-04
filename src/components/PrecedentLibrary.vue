<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useAuth } from '../composables/useAuth'

interface VoteDistribution {
  for_merchant: number
  for_recommender: number
  abstain: number
}

interface RationaleDigest {
  for_merchant: string[]
  for_recommender: string[]
  abstain: string[]
}

interface PrecedentCase {
  id: string
  disputeId: string
  taskPlatform: string
  disputeKind: string
  focus: string
  claimsSummary: string
  decision: string
  finalVia: string
  voteDistribution: VoteDistribution
  rationaleDigest: RationaleDigest
  createdAt: string
}

interface PrecedentResponse {
  cases: PrecedentCase[]
  hasMore: boolean
  nextCursor: string | null
}

const { isAuthenticated } = useAuth()

const cases = ref<PrecedentCase[]>([])
const loading = ref(false)
const hasMore = ref(true)
const cursor = ref<string | null>(null)
const selectedCase = ref<PrecedentCase | null>(null)
const showDetail = ref(false)

// 筛选器
const selectedPlatform = ref<string>('')
const selectedTaskType = ref<string>('')
const selectedKind = ref<string>('')

const platformOptions = [
  { value: '', label: '全部平台' },
  { value: 'taobao', label: '淘宝' },
  { value: 'dianping', label: '大众点评' },
  { value: 'douyin', label: '抖音' },
  { value: 'xiaohongshu', label: '小红书' },
  { value: 'zhihu', label: '知乎' },
  { value: 'weixin', label: '微信公众号' }
]

const taskTypeOptions = [
  { value: '', label: '全部类型' },
  { value: 'image-text', label: '图文' },
  { value: 'video', label: '视频' },
  { value: 'article', label: '文章' }
]

const kindOptions = [
  { value: '', label: '全部争议' },
  { value: 'quality', label: '质量争议' },
  { value: 'delivery', label: '交付争议' },
  { value: 'content', label: '内容争议' },
  { value: 'other', label: '其他' }
]

const apiUrl = computed(() => {
  const params = new URLSearchParams()
  if (selectedPlatform.value) params.set('platform', selectedPlatform.value)
  if (selectedTaskType.value) params.set('taskType', selectedTaskType.value)
  if (selectedKind.value) params.set('kind', selectedKind.value)
  if (cursor.value) params.set('cursor', cursor.value)
  params.set('limit', '20')
  return `/api/trust/precedents?${params.toString()}`
})

async function loadCases(reset = false) {
  if (loading.value || (!hasMore.value && !reset)) return

  if (reset) {
    cases.value = []
    cursor.value = null
    hasMore.value = true
  }

  loading.value = true
  try {
    const res = await fetch(apiUrl.value, {
      credentials: 'include',
      headers: {
        'Content-Type': 'application/json'
      }
    })

    if (!res.ok) {
      throw new Error(`加载失败: ${res.status}`)
    }

    const data: PrecedentResponse = await res.json()
    cases.value = reset ? data.cases : [...cases.value, ...data.cases]
    hasMore.value = data.hasMore
    cursor.value = data.nextCursor
  } catch (err) {
    console.error('加载判例失败:', err)
    alert('加载判例失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

function openDetail(c: PrecedentCase) {
  selectedCase.value = c
  showDetail.value = true
}

function closeDetail() {
  showDetail.value = false
  selectedCase.value = null
}

function getPlatformLabel(platform: string): string {
  return platformOptions.find(p => p.value === platform)?.label || platform
}

function getTaskTypeLabel(type: string): string {
  return taskTypeOptions.find(t => t.value === type)?.label || type
}

function getKindLabel(kind: string): string {
  return kindOptions.find(k => k.value === kind)?.label || kind
}

function getDecisionLabel(decision: string): string {
  const labels: Record<string, string> = {
    for_merchant: '商家胜诉',
    for_recommender: '推荐官胜诉',
    merchant_partial: '商家部分胜诉',
    recommender_partial: '推荐官部分胜诉'
  }
  return labels[decision] || decision
}

function getFinalViaLabel(via: string): string {
  const labels: Record<string, string> = {
    voting: '七官投票',
    cs_final: '客服终审',
    merchant_concede: '商家让步',
    recommender_concede: '推荐官让步'
  }
  return labels[via] || via
}

function getVotePercentage(count: number, total: number): number {
  return total === 0 ? 0 : Math.round((count / total) * 100)
}

const totalVotes = computed(() => {
  if (!selectedCase.value) return 0
  const v = selectedCase.value.voteDistribution
  return v.for_merchant + v.for_recommender + v.abstain
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
        <label>任务类型</label>
        <select v-model="selectedTaskType" @change="applyFilters">
          <option v-for="opt in taskTypeOptions" :key="opt.value" :value="opt.value">
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
    <div v-if="cases.length === 0 && !loading" class="empty-state glass-card">
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
        <p class="claims-preview">{{ c.claimsSummary.slice(0, 120) }}{{ c.claimsSummary.length > 120 ? '...' : '' }}</p>

        <div class="vote-bar">
          <div
            class="vote-segment merchant"
            :style="{ width: getVotePercentage(c.voteDistribution.for_merchant, c.voteDistribution.for_merchant + c.voteDistribution.for_recommender + c.voteDistribution.abstain) + '%' }"
          ></div>
          <div
            class="vote-segment recommender"
            :style="{ width: getVotePercentage(c.voteDistribution.for_recommender, c.voteDistribution.for_merchant + c.voteDistribution.for_recommender + c.voteDistribution.abstain) + '%' }"
          ></div>
          <div
            class="vote-segment abstain"
            :style="{ width: getVotePercentage(c.voteDistribution.abstain, c.voteDistribution.for_merchant + c.voteDistribution.for_recommender + c.voteDistribution.abstain) + '%' }"
          ></div>
        </div>

        <div class="vote-counts">
          <span class="merchant-count">商家 {{ c.voteDistribution.for_merchant }}</span>
          <span class="recommender-count">推荐官 {{ c.voteDistribution.for_recommender }}</span>
          <span class="abstain-count">弃权 {{ c.voteDistribution.abstain }}</span>
        </div>

        <div class="case-footer">
          <span class="final-via">{{ getFinalViaLabel(c.finalVia) }}</span>
          <span class="date">{{ new Date(c.createdAt).toLocaleDateString('zh-CN') }}</span>
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
                :style="{ width: getVotePercentage(selectedCase.voteDistribution.for_merchant, totalVotes) + '%' }"
              >
                <span v-if="getVotePercentage(selectedCase.voteDistribution.for_merchant, totalVotes) > 15">
                  {{ selectedCase.voteDistribution.for_merchant }}
                </span>
              </div>
              <div
                class="vote-segment recommender"
                :style="{ width: getVotePercentage(selectedCase.voteDistribution.for_recommender, totalVotes) + '%' }"
              >
                <span v-if="getVotePercentage(selectedCase.voteDistribution.for_recommender, totalVotes) > 15">
                  {{ selectedCase.voteDistribution.for_recommender }}
                </span>
              </div>
              <div
                class="vote-segment abstain"
                :style="{ width: getVotePercentage(selectedCase.voteDistribution.abstain, totalVotes) + '%' }"
              >
                <span v-if="getVotePercentage(selectedCase.voteDistribution.abstain, totalVotes) > 15">
                  {{ selectedCase.voteDistribution.abstain }}
                </span>
              </div>
            </div>
            <div class="vote-legend">
              <div class="legend-item">
                <span class="legend-color merchant"></span>
                <span>支持商家: {{ selectedCase.voteDistribution.for_merchant }}票</span>
              </div>
              <div class="legend-item">
                <span class="legend-color recommender"></span>
                <span>支持推荐官: {{ selectedCase.voteDistribution.for_recommender }}票</span>
              </div>
              <div class="legend-item">
                <span class="legend-color abstain"></span>
                <span>弃权: {{ selectedCase.voteDistribution.abstain }}票</span>
              </div>
            </div>
          </div>

          <div class="detail-section">
            <h4>投票理由摘要</h4>

            <div v-if="selectedCase.rationaleDigest.for_merchant.length > 0" class="rationale-group">
              <h5>支持商家方</h5>
              <ul class="rationale-list">
                <li v-for="(r, idx) in selectedCase.rationaleDigest.for_merchant" :key="idx">
                  {{ r }}
                </li>
              </ul>
            </div>

            <div v-if="selectedCase.rationaleDigest.for_recommender.length > 0" class="rationale-group">
              <h5>支持推荐官方</h5>
              <ul class="rationale-list">
                <li v-for="(r, idx) in selectedCase.rationaleDigest.for_recommender" :key="idx">
                  {{ r }}
                </li>
              </ul>
            </div>

            <div v-if="selectedCase.rationaleDigest.abstain.length > 0" class="rationale-group">
              <h5>弃权理由</h5>
              <ul class="rationale-list">
                <li v-for="(r, idx) in selectedCase.rationaleDigest.abstain" :key="idx">
                  {{ r }}
                </li>
              </ul>
            </div>
          </div>

          <div class="detail-footer">
            <span class="dispute-id">争议编号: {{ selectedCase.disputeId }}</span>
            <span class="date">{{ new Date(selectedCase.createdAt).toLocaleDateString('zh-CN', { year: 'numeric', month: 'long', day: 'numeric' }) }}</span>
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
  color: var(--text-primary);
}

.subtitle {
  font-size: clamp(0.875rem, 2vw, 1rem);
  color: var(--text-secondary);
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
  color: var(--text-secondary);
}

.filter-group select {
  padding: var(--space-sm) var(--space-md);
  background: var(--surface-2);
  border: 1px solid var(--surface-3);
  border-radius: var(--radius-md);
  color: var(--text-primary);
  font-size: 0.9375rem;
  cursor: pointer;
  transition: all 0.2s;
}

.filter-group select:hover {
  border-color: var(--accent);
}

.filter-group select:focus {
  outline: none;
  border-color: var(--accent);
  box-shadow: 0 0 0 3px rgba(83, 58, 253, 0.1);
}

.empty-state {
  padding: var(--space-2xl);
  text-align: center;
  color: var(--text-secondary);
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
  padding: var(--space-2xs) var(--space-sm);
  border-radius: 999px;
  font-size: 0.75rem;
  font-weight: 500;
}

.badge.platform {
  background: var(--surface-3);
  color: var(--text-secondary);
}

.badge.kind {
  background: var(--surface-4);
  color: var(--text-primary);
}

.decision-badge {
  padding: var(--space-2xs) var(--space-sm);
  border-radius: 999px;
  font-size: 0.75rem;
  font-weight: 600;
  white-space: nowrap;
}

.decision-badge.for_merchant {
  background: rgba(16, 185, 129, 0.15);
  color: #10b981;
}

.decision-badge.for_recommender {
  background: rgba(59, 130, 246, 0.15);
  color: #3b82f6;
}

.decision-badge.merchant_partial,
.decision-badge.recommender_partial {
  background: rgba(245, 158, 11, 0.15);
  color: #f59e0b;
}

.focus {
  font-size: 1.125rem;
  font-weight: 600;
  margin-bottom: var(--space-sm);
  color: var(--text-primary);
  line-height: 1.4;
}

.claims-preview {
  font-size: 0.875rem;
  color: var(--text-secondary);
  line-height: 1.6;
  margin-bottom: var(--space-md);
}

.vote-bar {
  display: flex;
  height: 8px;
  border-radius: 999px;
  overflow: hidden;
  background: var(--surface-2);
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
  color: white;
}

.vote-segment.merchant {
  background: #10b981;
}

.vote-segment.recommender {
  background: #3b82f6;
}

.vote-segment.abstain {
  background: #6b7280;
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
  gap: var(--space-2xs);
}

.merchant-count {
  color: #10b981;
}

.recommender-count {
  color: #3b82f6;
}

.abstain-count {
  color: #6b7280;
}

.case-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-top: var(--space-md);
  border-top: 1px solid var(--surface-3);
  font-size: 0.8125rem;
}

.final-via {
  color: var(--text-secondary);
  font-weight: 500;
}

.date {
  color: var(--text-tertiary);
}

.load-more-container {
  display: flex;
  justify-content: center;
  padding: var(--space-xl) 0;
}

.load-more-btn {
  padding: var(--space-md) var(--space-2xl);
  background: var(--accent);
  color: white;
  border: none;
  border-radius: 999px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
}

.load-more-btn:hover:not(:disabled) {
  background: var(--accent-hover);
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
  border-bottom: 1px solid var(--surface-3);
  position: sticky;
  top: 0;
  background: var(--surface-1);
  z-index: 10;
}

.drawer-header h2 {
  font-size: 1.25rem;
  font-weight: 600;
  color: var(--text-primary);
}

.close-btn {
  width: 32px;
  height: 32px;
  border-radius: 999px;
  border: none;
  background: var(--surface-3);
  color: var(--text-primary);
  font-size: 1.25rem;
  cursor: pointer;
  transition: all 0.2s;
  display: flex;
  align-items: center;
  justify-content: center;
}

.close-btn:hover {
  background: var(--surface-4);
}

.drawer-content {
  padding: var(--space-xl);
}

.detail-section {
  margin-bottom: var(--space-2xl);
}

.detail-section h4 {
  font-size: 1rem;
  font-weight: 600;
  margin-bottom: var(--space-md);
  color: var(--text-primary);
}

.detail-section h5 {
  font-size: 0.875rem;
  font-weight: 600;
  margin-bottom: var(--space-sm);
  color: var(--text-secondary);
}

.detail-section p {
  font-size: 0.9375rem;
  line-height: 1.7;
  color: var(--text-secondary);
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
  color: var(--text-secondary);
  padding: var(--space-xs) var(--space-md);
  background: var(--surface-2);
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
  color: var(--text-secondary);
}

.legend-color {
  width: 16px;
  height: 16px;
  border-radius: 4px;
}

.legend-color.merchant {
  background: #10b981;
}

.legend-color.recommender {
  background: #3b82f6;
}

.legend-color.abstain {
  background: #6b7280;
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
  background: var(--surface-2);
  border-left: 3px solid var(--accent);
  border-radius: var(--radius-sm);
  font-size: 0.875rem;
  line-height: 1.6;
  color: var(--text-secondary);
}

.detail-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-top: var(--space-lg);
  border-top: 1px solid var(--surface-3);
  font-size: 0.8125rem;
}

.dispute-id {
  color: var(--text-tertiary);
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