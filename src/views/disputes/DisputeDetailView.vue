<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAuth } from '../../composables/useAuth'
import { useGrassland } from '../../composables/useGrassland'
import { request, fetchApi, GrasslandHttpError } from '../../composables/grassland-http'
import type { DisputeCase, AdjudicationSnapshot, DisputeStatus, DisputeChannel } from '../../types/grassland/dispute'

const router = useRouter()
const route = useRoute()
const { isAuthenticated } = useAuth()
const grassland = useGrassland()

const disputeId = computed(() => route.params.id as string)
const dispute = ref<DisputeCase | null>(null)
const adjudication = ref<AdjudicationSnapshot | null>(null)
const loading = ref(false)
const submittingEvidence = ref(false)

// Evidence form state
const showEvidenceForm = ref(false)
const evidencePhase = ref<'answer' | 'rebuttal'>('answer')
const evidenceText = ref('')
const evidenceCaption = ref('')

const statusLabels: Record<DisputeStatus, string> = {
  open: '受理中',
  evidence: '举证质证期',
  voting: '评审中',
  decided: '已裁决',
  appealed: '上诉中',
  final: '已终局',
}

const channelLabels: Record<DisputeChannel, string> = {
  court: '小法庭',
  cs_direct: '客服直裁',
}

/** 当事方角色来自服务端派生（viewerRole）——脱敏红线不回 openedByAccountId，前端不得自判。 */
const isClaimant = computed(() => dispute.value?.viewerRole === 'claimant')

const isRespondent = computed(() => dispute.value?.viewerRole === 'respondent')

/** 质证期判定：court 通道的 evidence 态 + 存量 open 案件（读取时视同 evidence）。 */
const inEvidencePhase = computed(() =>
  dispute.value !== null &&
  dispute.value.channel === 'court' &&
  (dispute.value.status === 'evidence' || dispute.value.status === 'open'))

const canSubmitAnswer = computed(() => {
  return inEvidencePhase.value &&
    dispute.value !== null &&
    isRespondent.value &&
    !dispute.value.respondentAnswered
})

const canSubmitRebuttal = computed(() => {
  return inEvidencePhase.value &&
    dispute.value !== null &&
    isClaimant.value &&
    dispute.value.respondentAnswered &&
    !dispute.value.claimantDoneAt
})

const canMarkDone = computed(() => {
  if (!inEvidencePhase.value || dispute.value === null) return false
  if (isClaimant.value && dispute.value.claimantDoneAt) return false
  if (isRespondent.value && dispute.value.respondentDoneAt) return false
  return true
})

async function loadDispute(): Promise<void> {
  if (!isAuthenticated.value) {
    router.push('/')
    return
  }

  loading.value = true
  try {
    // request 统一解 {success,data} 信封；404（不存在）/403（非当事方）回列表页。
    dispute.value = await request<DisputeCase>(`/api/trust/disputes/${disputeId.value}`)
    // Load adjudication if voting/decided/appealed/final
    if (dispute.value && ['voting', 'decided', 'appealed', 'final'].includes(dispute.value.status)) {
      await loadAdjudication()
    }
  } catch (error: unknown) {
    if (error instanceof GrasslandHttpError) {
      if (error.status === 401) {
        router.push('/')
        return
      }
      if (error.status === 404 || error.status === 403) {
        router.push('/me/disputes')
        return
      }
      grassland.error.value = error.message
      return
    }
    console.error('加载争议详情失败:', error)
    grassland.error.value = error instanceof Error ? error.message : '加载失败'
  } finally {
    loading.value = false
  }
}

async function loadAdjudication(): Promise<void> {
  try {
    // 快照端点带脱敏证据与访问审计，正常 200；403（非本轮面板/非当事方）静默忽略即可。
    const res = await fetchApi(`/api/trust/disputes/${disputeId.value}/adjudication`)
    if (res.ok) {
      const body = await res.json() as { success: boolean; data?: AdjudicationSnapshot }
      if (body?.success && body.data) {
        adjudication.value = body.data
      }
    }
  } catch (error: unknown) {
    console.warn('加载审判快照失败:', error)
  }
}

function openEvidenceForm(phase: 'answer' | 'rebuttal'): void {
  evidencePhase.value = phase
  evidenceText.value = ''
  evidenceCaption.value = ''
  showEvidenceForm.value = true
}

function closeEvidenceForm(): void {
  showEvidenceForm.value = false
  evidenceText.value = ''
  evidenceCaption.value = ''
}

async function submitEvidence(): Promise<void> {
  if (!evidenceText.value.trim()) {
    grassland.error.value = '请输入证据内容'
    return
  }

  submittingEvidence.value = true
  try {
    const items = [{
      kind: 'text' as const,
      contentRef: evidenceText.value.trim(),
      caption: evidenceCaption.value.trim() || undefined,
    }]

    const result = evidencePhase.value === 'answer'
      ? await grassland.submitDisputeAnswer(disputeId.value, items)
      : await grassland.submitDisputeRebuttal(disputeId.value, items)

    if (result) {
      closeEvidenceForm()
      await loadDispute()
    }
  } finally {
    submittingEvidence.value = false
  }
}

async function markEvidenceDone(): Promise<void> {
  const confirmed = confirm('确认质证完毕？提交后双方均完成质证时将自动开庭。')
  if (!confirmed) return

  const result = await grassland.markEvidenceDone(disputeId.value)
  if (result) {
    await loadDispute()
  }
}

async function startAdjudication(): Promise<void> {
  const confirmed = confirm('确认启动审判？将抽选 7 名审判官组成面板。')
  if (!confirmed) return

  const result = await grassland.startAdjudication(disputeId.value)
  if (result) {
    await loadDispute()
    await loadAdjudication()
  }
}

function formatDate(dateString: string | null): string {
  if (!dateString) return '-'
  const date = new Date(dateString)
  return date.toLocaleDateString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

function getTimeRemaining(deadline: string | null): string {
  if (!deadline) return ''
  const now = new Date()
  const end = new Date(deadline)
  const hoursRemaining = Math.max(0, Math.floor((end.getTime() - now.getTime()) / (1000 * 60 * 60)))

  if (hoursRemaining <= 0) return '已截止'
  if (hoursRemaining < 24) return `剩余 ${hoursRemaining} 小时`
  return `剩余 ${Math.floor(hoursRemaining / 24)} 天`
}

onMounted(loadDispute)
</script>

<template>
  <div class="dispute-detail-page">
    <header class="page-header">
      <div class="header-content">
        <button class="back-btn" type="button" @click="router.push('/me/disputes')">
          <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <path d="M10 12L6 8l4-4" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
        </button>
        <div class="header-text">
          <h1>争议详情</h1>
          <p v-if="dispute" class="subtitle">案件编号：{{ dispute.id.slice(0, 8) }}</p>
        </div>
      </div>
    </header>

    <div class="page-content">
      <div v-if="loading" class="loading-state">
        <div class="spinner"></div>
        <p>加载中...</p>
      </div>

      <div v-else-if="grassland.error.value" class="error-state">
        <p>{{ grassland.error.value }}</p>
        <button class="retry-btn" type="button" @click="loadDispute">重试</button>
      </div>

      <div v-else-if="dispute" class="detail-container">
        <!-- Status Timeline -->
        <section class="card timeline-card">
          <h2 class="card-title">案件进度</h2>
          <div class="timeline">
            <div class="timeline-item" :class="{ active: dispute.status === 'open' || dispute.status === 'evidence' }">
              <div class="timeline-marker"></div>
              <div class="timeline-content">
                <div class="timeline-label">举证质证期</div>
                <div v-if="dispute.evidenceDeadline" class="timeline-time">
                  {{ formatDate(dispute.evidenceDeadline) }}
                  <span class="time-remaining">{{ getTimeRemaining(dispute.evidenceDeadline) }}</span>
                </div>
              </div>
            </div>

            <div class="timeline-item" :class="{ active: dispute.status === 'voting' }">
              <div class="timeline-marker"></div>
              <div class="timeline-content">
                <div class="timeline-label">评审中</div>
                <div v-if="adjudication" class="timeline-detail">
                  面板 {{ adjudication.panel.size }} 人，已投票 {{ adjudication.panel.voted }} 人
                </div>
              </div>
            </div>

            <div class="timeline-item" :class="{ active: dispute.status === 'decided' }">
              <div class="timeline-marker"></div>
              <div class="timeline-content">
                <div class="timeline-label">已裁决</div>
                <div v-if="dispute.decidedAt" class="timeline-time">{{ formatDate(dispute.decidedAt) }}</div>
              </div>
            </div>

            <div class="timeline-item" :class="{ active: dispute.status === 'appealed' }">
              <div class="timeline-marker"></div>
              <div class="timeline-content">
                <div class="timeline-label">上诉中</div>
              </div>
            </div>

            <div class="timeline-item" :class="{ active: dispute.status === 'final' }">
              <div class="timeline-marker"></div>
              <div class="timeline-content">
                <div class="timeline-label">已终局</div>
                <div v-if="dispute.finalDecision" class="timeline-detail">
                  {{ dispute.finalDecision === 'for_merchant' ? '商家胜诉' : '推荐官胜诉' }}
                </div>
              </div>
            </div>
          </div>
        </section>

        <!-- Basic Info -->
        <section class="card info-card">
          <h2 class="card-title">基本信息</h2>
          <dl class="info-grid">
            <div class="info-item">
              <dt>当前状态</dt>
              <dd>{{ statusLabels[dispute.status] }}</dd>
            </div>
            <div class="info-item">
              <dt>处理通道</dt>
              <dd>{{ channelLabels[dispute.channel] }}</dd>
            </div>
            <div class="info-item">
              <dt>开启时间</dt>
              <dd>{{ formatDate(dispute.createdAt) }}</dd>
            </div>
            <div v-if="dispute.reason" class="info-item info-item-full">
              <dt>争议原因</dt>
              <dd>{{ dispute.reason }}</dd>
            </div>
          </dl>
        </section>

        <!-- Evidence Phase Actions -->
        <section v-if="dispute.status === 'evidence'" class="card actions-card">
          <h2 class="card-title">举证质证</h2>

          <div v-if="dispute.respondentAnswered" class="status-notice status-notice-info">
            <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true">
              <circle cx="8" cy="8" r="6.5" stroke="currentColor" stroke-width="1.3"/>
              <path d="M8 7v4M8 5v.5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
            </svg>
            被诉方已提交答辩
          </div>

          <div v-if="!dispute.respondentAnswered && isRespondent" class="status-notice status-notice-warning">
            <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true">
              <path d="M8 1l7 12H1L8 1z" stroke="currentColor" stroke-width="1.3" stroke-linejoin="round"/>
              <path d="M8 6v3M8 10.5v.5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
            </svg>
            您需要在质证期内提交答辩
          </div>

          <div class="action-buttons">
            <button
              v-if="canSubmitAnswer"
              class="btn btn-primary"
              type="button"
              @click="openEvidenceForm('answer')"
            >
              提交答辩
            </button>

            <button
              v-if="canSubmitRebuttal"
              class="btn btn-primary"
              type="button"
              @click="openEvidenceForm('rebuttal')"
            >
              补充质证
            </button>

            <button
              v-if="canMarkDone"
              class="btn btn-secondary"
              type="button"
              @click="markEvidenceDone"
            >
              质证完毕
            </button>
          </div>

          <div v-if="dispute.claimantDoneAt || dispute.respondentDoneAt" class="done-status">
            <p v-if="dispute.claimantDoneAt">原告已标记质证完毕</p>
            <p v-if="dispute.respondentDoneAt">被告已标记质证完毕</p>
            <p v-if="dispute.claimantDoneAt && dispute.respondentDoneAt" class="both-done">
              双方均已完成质证，即将自动开庭
            </p>
          </div>
        </section>

        <!-- Open status - manual adjudicate -->
        <section v-if="dispute.status === 'open' && dispute.channel === 'court'" class="card actions-card">
          <h2 class="card-title">启动审判</h2>
          <p class="action-description">质证期结束后可以启动审判流程，抽选审判官面板进行裁决。</p>
          <button class="btn btn-primary" type="button" @click="startAdjudication">
            启动审判
          </button>
        </section>

        <!-- Adjudication Results -->
        <section v-if="adjudication" class="card results-card">
          <h2 class="card-title">审判结果</h2>

          <div class="vote-summary">
            <div class="vote-bar">
              <div
                class="vote-segment vote-merchant"
                :style="{ width: `${(adjudication.tallies.forMerchant / adjudication.tallies.panelSize) * 100}%` }"
              >
                {{ adjudication.tallies.forMerchant }}
              </div>
              <div
                class="vote-segment vote-recommender"
                :style="{ width: `${(adjudication.tallies.forRecommender / adjudication.tallies.panelSize) * 100}%` }"
              >
                {{ adjudication.tallies.forRecommender }}
              </div>
              <div
                class="vote-segment vote-abstain"
                :style="{ width: `${(adjudication.tallies.abstain / adjudication.tallies.panelSize) * 100}%` }"
              >
                {{ adjudication.tallies.abstain }}
              </div>
            </div>
            <div class="vote-legend">
              <div class="legend-item">
                <span class="legend-color legend-merchant"></span>
                支持商家 {{ adjudication.tallies.forMerchant }} 票
              </div>
              <div class="legend-item">
                <span class="legend-color legend-recommender"></span>
                支持推荐官 {{ adjudication.tallies.forRecommender }} 票
              </div>
              <div class="legend-item">
                <span class="legend-color legend-abstain"></span>
                弃权 {{ adjudication.tallies.abstain }} 票
              </div>
            </div>
          </div>

          <div v-if="adjudication.matchedPlatformCount != null" class="meta-info">
            <p>垂类熟手: {{ adjudication.matchedPlatformCount }}/{{ adjudication.panel.size }}</p>
            <p v-if="adjudication.probationCount">见习审判官: {{ adjudication.probationCount }}</p>
          </div>
        </section>
      </div>
    </div>

    <!-- Evidence Submission Modal -->
    <div v-if="showEvidenceForm" class="modal-overlay" @click.self="closeEvidenceForm">
      <div class="modal-card">
        <div class="modal-header">
          <h3>{{ evidencePhase === 'answer' ? '提交答辩' : '补充质证' }}</h3>
          <button class="close-btn" type="button" @click="closeEvidenceForm">
            <svg width="20" height="20" viewBox="0 0 16 16" fill="none" aria-hidden="true">
              <path d="M12 4L4 12M4 4l8 8" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
            </svg>
          </button>
        </div>

        <div class="modal-body">
          <div class="form-group">
            <label for="evidence-text">证据内容 <span class="required">*</span></label>
            <textarea
              id="evidence-text"
              v-model="evidenceText"
              class="form-textarea"
              rows="6"
              placeholder="请详细描述您的证据和理由..."
              :disabled="submittingEvidence"
            ></textarea>
          </div>

          <div class="form-group">
            <label for="evidence-caption">说明</label>
            <input
              id="evidence-caption"
              v-model="evidenceCaption"
              type="text"
              class="form-input"
              placeholder="可选：简短说明"
              :disabled="submittingEvidence"
            />
          </div>
        </div>

        <div class="modal-footer">
          <button
            class="btn btn-secondary"
            type="button"
            :disabled="submittingEvidence"
            @click="closeEvidenceForm"
          >
            取消
          </button>
          <button
            class="btn btn-primary"
            type="button"
            :disabled="submittingEvidence || !evidenceText.trim()"
            @click="submitEvidence"
          >
            {{ submittingEvidence ? '提交中...' : '提交' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.dispute-detail-page {
  min-height: 100vh;
  background: var(--color-bg);
  color: var(--color-text);
}

.page-header {
  background: var(--surface-card);
  border-bottom: 1px solid var(--color-border);
  padding: clamp(1rem, 3vw, 1.5rem) clamp(1rem, 5vw, 2rem);
}

.header-content {
  max-width: 900px;
  margin: 0 auto;
  display: flex;
  align-items: center;
  gap: 1rem;
}

.back-btn {
  width: 40px;
  height: 40px;
  border-radius: 999px;
  background: var(--surface-hover);
  border: 1px solid var(--color-border);
  color: var(--color-text);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.2s;
}

.back-btn:hover {
  background: var(--surface-elevated);
  transform: translateX(-2px);
}

.header-text h1 {
  font-size: clamp(1.5rem, 4vw, 2rem);
  font-weight: 600;
  margin: 0 0 0.25rem;
  letter-spacing: -0.02em;
}

.subtitle {
  font-size: 0.875rem;
  color: var(--color-text-secondary);
  margin: 0;
  font-family: var(--font-mono);
}

.page-content {
  max-width: 900px;
  margin: 0 auto;
  padding: clamp(1.5rem, 4vw, 2.5rem) clamp(1rem, 5vw, 2rem);
}

.loading-state,
.error-state {
  text-align: center;
  padding: 4rem 1rem;
  color: var(--color-text-secondary);
}

.spinner {
  width: 40px;
  height: 40px;
  margin: 0 auto 1rem;
  border: 3px solid var(--color-border);
  border-top-color: var(--color-accent);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.retry-btn {
  margin-top: 1rem;
  padding: 0.625rem 1.25rem;
  background: var(--color-accent);
  color: var(--color-on-accent);
  border: none;
  border-radius: 999px;
  font-size: 0.875rem;
  font-weight: 500;
  cursor: pointer;
  transition: opacity 0.2s;
}

.retry-btn:hover {
  opacity: 0.9;
}

.detail-container {
  display: flex;
  flex-direction: column;
  gap: 1.5rem;
}

.card {
  background: var(--surface-card);
  border: 1px solid var(--color-border);
  border-radius: 12px;
  padding: 1.5rem;
}

.card-title {
  font-size: 1.125rem;
  font-weight: 600;
  margin: 0 0 1.25rem;
  color: var(--color-text);
}

/* Timeline */
.timeline {
  display: flex;
  flex-direction: column;
  gap: 1.5rem;
}

.timeline-item {
  display: flex;
  gap: 1rem;
  position: relative;
  opacity: 0.5;
}

.timeline-item.active {
  opacity: 1;
}

.timeline-item::before {
  content: '';
  position: absolute;
  left: 11px;
  top: 24px;
  bottom: -24px;
  width: 2px;
  background: var(--color-border);
}

.timeline-item:last-child::before {
  display: none;
}

.timeline-marker {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  border: 2px solid var(--color-border);
  background: var(--surface-hover);
  flex-shrink: 0;
}

.timeline-item.active .timeline-marker {
  border-color: var(--color-accent);
  background: var(--color-accent);
}

.timeline-content {
  flex: 1;
  padding-top: 2px;
}

.timeline-label {
  font-weight: 500;
  margin-bottom: 0.25rem;
}

.timeline-time,
.timeline-detail {
  font-size: 0.875rem;
  color: var(--color-text-secondary);
}

.time-remaining {
  margin-left: 0.5rem;
  color: var(--color-accent);
}

/* Info Grid */
.info-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 1.5rem;
}

.info-item {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.info-item-full {
  grid-column: 1 / -1;
}

.info-item dt {
  font-size: 0.875rem;
  color: var(--color-text-secondary);
  font-weight: 500;
}

.info-item dd {
  margin: 0;
  font-size: 1rem;
  color: var(--color-text);
}

/* Status Notices */
.status-notice {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 1rem;
  border-radius: 8px;
  font-size: 0.875rem;
  margin-bottom: 1rem;
}

.status-notice-info {
  background: rgba(59, 130, 246, 0.1);
  border: 1px solid rgba(59, 130, 246, 0.3);
  color: var(--color-info);
}

.status-notice-warning {
  background: color-mix(in srgb, var(--color-warning) 10%, transparent);
  border: 1px solid color-mix(in srgb, var(--color-warning) 30%, transparent);
  color: var(--color-warning);
}

/* Action Buttons */
.action-buttons {
  display: flex;
  gap: 1rem;
  flex-wrap: wrap;
}

.action-description {
  font-size: 0.875rem;
  color: var(--color-text-secondary);
  margin: 0 0 1rem;
  line-height: 1.6;
}

.btn {
  padding: 0.75rem 1.5rem;
  border-radius: 999px;
  font-size: 0.875rem;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s;
  border: none;
}

.btn-primary {
  background: var(--color-accent);
  color: var(--color-on-accent);
}

.btn-primary:hover:not(:disabled) {
  opacity: 0.9;
  transform: translateY(-1px);
}

.btn-secondary {
  background: var(--surface-hover);
  color: var(--color-text);
  border: 1px solid var(--color-border);
}

.btn-secondary:hover:not(:disabled) {
  background: var(--surface-elevated);
}

.btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.done-status {
  margin-top: 1rem;
  padding: 1rem;
  background: var(--surface-hover);
  border-radius: 8px;
  font-size: 0.875rem;
  color: var(--color-text-secondary);
}

.done-status p {
  margin: 0 0 0.5rem;
}

.done-status p:last-child {
  margin-bottom: 0;
}

.both-done {
  color: var(--color-accent);
  font-weight: 500;
}

/* Vote Summary */
.vote-summary {
  margin-bottom: 1.5rem;
}

.vote-bar {
  display: flex;
  height: 40px;
  border-radius: 8px;
  overflow: hidden;
  margin-bottom: 1rem;
  background: var(--surface-hover);
}

.vote-segment {
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--color-on-accent);
  font-weight: 600;
  font-size: 0.875rem;
  transition: all 0.3s;
}

.vote-merchant {
  background: var(--color-success);
}

.vote-recommender {
  background: var(--color-info);
}

.vote-abstain {
  background: var(--color-text-muted);
}

.vote-legend {
  display: flex;
  gap: 1.5rem;
  flex-wrap: wrap;
  font-size: 0.875rem;
}

.legend-item {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.legend-color {
  width: 12px;
  height: 12px;
  border-radius: 2px;
}

.legend-merchant {
  background: var(--color-success);
}

.legend-recommender {
  background: var(--color-info);
}

.legend-abstain {
  background: var(--color-text-muted);
}

.meta-info {
  padding-top: 1rem;
  border-top: 1px solid var(--color-border);
  font-size: 0.875rem;
  color: var(--color-text-secondary);
}

.meta-info p {
  margin: 0 0 0.5rem;
}

.meta-info p:last-child {
  margin-bottom: 0;
}

/* Modal */
.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.7);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 1rem;
  z-index: 1000;
}

.modal-card {
  background: var(--surface-card);
  border-radius: 16px;
  border: 1px solid var(--color-border);
  max-width: 600px;
  width: 100%;
  max-height: 90vh;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 1.5rem;
  border-bottom: 1px solid var(--color-border);
}

.modal-header h3 {
  font-size: 1.25rem;
  font-weight: 600;
  margin: 0;
}

.close-btn {
  width: 32px;
  height: 32px;
  border-radius: 999px;
  background: transparent;
  border: none;
  color: var(--color-text-secondary);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s;
}

.close-btn:hover {
  background: var(--surface-hover);
  color: var(--color-text);
}

.modal-body {
  flex: 1;
  overflow-y: auto;
  padding: 1.5rem;
}

.form-group {
  margin-bottom: 1.25rem;
}

.form-group:last-child {
  margin-bottom: 0;
}

.form-group label {
  display: block;
  font-size: 0.875rem;
  font-weight: 500;
  margin-bottom: 0.5rem;
  color: var(--color-text);
}

.required {
  color: var(--color-danger);
}

.form-textarea,
.form-input {
  width: 100%;
  padding: 0.75rem;
  background: var(--surface-hover);
  border: 1px solid var(--color-border);
  border-radius: 8px;
  color: var(--color-text);
  font-size: 0.875rem;
  font-family: inherit;
  resize: vertical;
}

.form-textarea:focus,
.form-input:focus {
  outline: none;
  border-color: var(--color-accent);
}

.form-textarea:disabled,
.form-input:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.modal-footer {
  display: flex;
  gap: 1rem;
  justify-content: flex-end;
  padding: 1.5rem;
  border-top: 1px solid var(--color-border);
}

@media (max-width: 640px) {
  .info-grid {
    grid-template-columns: 1fr;
  }

  .action-buttons {
    flex-direction: column;
  }

  .btn {
    width: 100%;
  }

  .vote-legend {
    flex-direction: column;
    gap: 0.75rem;
  }
}
</style>
