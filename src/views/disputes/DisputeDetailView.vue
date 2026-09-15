<script setup lang="ts">
import { computed, onActivated, onDeactivated, onMounted, onUnmounted, ref, watch, defineAsyncComponent } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useDisputeCaseSession } from './composables/useDisputeCaseSession'
import type { DisputeActionContext } from './composables/useDisputeCaseSession'
import { useDisputeEvidenceActions } from './composables/useDisputeEvidenceActions'
import type { DisputeEvidenceItemInput, DisputeWriteOutcome } from './composables/useDisputeEvidenceActions'
import DisputeEvidenceForm from './components/DisputeEvidenceForm.vue'
import {
  buildDisputeTimeline,
  buildDisputeVoteSegments,
  disputeChannelLabels,
  disputeStatusLabels,
  formatDisputeDate,
} from './dispute-presentation'
import type { DisputeStatus } from '../../types/grassland/dispute'

const AdjudicationPanel = defineAsyncComponent(() => import('../../components/AdjudicationPanel.vue'))

/** 匿名态走既有登录引导（布局 LoginModal 经 request-login 接线），本页不自建登录入口。 */
const emit = defineEmits<{ 'request-login': [] }>()

const router = useRouter()
const route = useRoute()

// C103-11：读取生命周期进域 composable；C103-12：写动作经 captureAction 上下文闸。
const session = useDisputeCaseSession({ routeCaseId: () => (route.params.id as string) || null })
const actions = useDisputeEvidenceActions(session)

const dispute = computed(() => session.dispute.value)
const adjudication = computed(() => session.adjudication.value)
const authPending = computed(() => session.state.value === 'auth_pending')
const loading = computed(() => session.state.value === 'loading_case' || authPending.value)
const anonymous = computed(() => session.state.value === 'anonymous')
const forbidden = computed(() => session.state.value === 'forbidden')
const notFound = computed(() => session.state.value === 'not_found')
const loadError = computed(() => session.state.value === 'error' ? session.error.value : '')

onMounted(session.activate)
onActivated(session.activate)
onDeactivated(session.deactivate)
onUnmounted(session.deactivate)

// ---------- 证据表单（打开即冻结写上下文；切案/换号即清） ----------

const formOpen = ref(false)
const formPhase = ref<'answer' | 'rebuttal'>('answer')
/** 打开表单时捕获的不可变上下文：提交/回包全程只认它，失效即本地拒绝。 */
let capturedContext: DisputeActionContext | null = null
/** 待核实提示：只对原案显示，切案后不串（B 不显示 A 的任何写结果）。 */
const unverifiedNotice = ref<{ disputeId: string; label: string } | null>(null)

const caseLabel = computed(() => session.caseId.value?.slice(0, 8) ?? '')
/** 当前视图目标上的提示才显示（旧案错误/待核实不带到新案）。 */
const activeNotice = computed(() => {
  if (unverifiedNotice.value && unverifiedNotice.value.disputeId === session.caseId.value) {
    return { kind: 'unverified' as const, text: `「${unverifiedNotice.value.label}」结果待核实——请稍后刷新本案确认，系统不会自动重发。` }
  }
  if (actions.error.value && actions.lastTargetDisputeId.value === session.caseId.value) {
    return { kind: 'failed' as const, text: actions.error.value }
  }
  return null
})

watch(() => session.caseId.value, () => {
  // 切换 ID/换号：立即清表单与捕获上下文（§4.4）。
  formOpen.value = false
  capturedContext = null
  actions.clearError()
})

/** 当事方角色来自服务端派生（viewerRole）——脱敏红线不回 openedByAccountId，前端不得自判。 */
const isClaimant = computed(() => dispute.value?.viewerRole === 'claimant')
const isRespondent = computed(() => dispute.value?.viewerRole === 'respondent')

/** 质证期判定：court 通道的 evidence 态 + 存量 open 案件（读取时视同 evidence）。 */
const inEvidencePhase = computed(() =>
  dispute.value !== null &&
  dispute.value.channel === 'court' &&
  (dispute.value.status === 'evidence' || dispute.value.status === 'open'))

const canSubmitAnswer = computed(() =>
  inEvidencePhase.value && isRespondent.value && dispute.value !== null && !dispute.value.respondentAnswered)

const canSubmitRebuttal = computed(() =>
  inEvidencePhase.value && isClaimant.value && dispute.value !== null
  && dispute.value.respondentAnswered && !dispute.value.claimantDoneAt)

const canMarkDone = computed(() => {
  if (!inEvidencePhase.value || dispute.value === null) return false
  if (isClaimant.value && dispute.value.claimantDoneAt) return false
  if (isRespondent.value && dispute.value.respondentDoneAt) return false
  return true
})

function openEvidenceForm(phase: 'answer' | 'rebuttal'): void {
  const context = session.captureAction()
  if (!context) return // 未就绪/失效目标：不开表单、不发请求
  capturedContext = context
  formPhase.value = phase
  formOpen.value = true
}

function closeEvidenceForm(): void {
  formOpen.value = false
  capturedContext = null
}

async function submitEvidence(items: DisputeEvidenceItemInput[]): Promise<void> {
  const context = capturedContext
  if (!context) {
    closeEvidenceForm()
    return
  }
  if (actions.status.value === 'submitting') return // 连点防重复：一次只发一个写
  const label = formPhase.value === 'answer' ? '提交答辩' : '补充质证'
  const outcome = await actions.submitEvidence(context, formPhase.value, items)
  applyWriteOutcome(outcome, label, context.disputeId)
}

async function markEvidenceDone(): Promise<void> {
  if (actions.status.value === 'submitting') return
  const context = session.captureAction()
  if (!context) return
  const confirmed = confirm('确认质证完毕？提交后双方均完成质证时将自动开庭。')
  if (!confirmed) return
  const outcome = await actions.markEvidenceDone(context)
  applyWriteOutcome(outcome, '质证完毕', context.disputeId)
}

async function startAdjudication(): Promise<void> {
  if (actions.status.value === 'submitting') return
  const context = session.captureAction()
  if (!context) return
  const confirmed = confirm('确认启动审判？将抽选 7 名审判官组成面板。')
  if (!confirmed) return
  const outcome = await actions.startAdjudication(context)
  applyWriteOutcome(outcome, '启动审判', context.disputeId)
}

function applyWriteOutcome(outcome: DisputeWriteOutcome, label: string, disputeId: string): void {
  unverifiedNotice.value = null
  if (outcome === 'accepted') {
    if (formOpen.value) closeEvidenceForm()
    actions.clearError()
    return
  }
  if (outcome === 'unverified') {
    // 结果未知：保留原案待核实提示（不自动重发）；表单保留可恢复输入由 failed 分支外的此分支关闭。
    if (formOpen.value) closeEvidenceForm()
    unverifiedNotice.value = { disputeId, label }
    return
  }
  // failed / rejected_local：failed 保留表单（同案可恢复输入）；rejected_local 关闭表单。
  if (outcome === 'rejected_local' && formOpen.value) closeEvidenceForm()
}

const statusLabel = computed(() => dispute.value ? disputeStatusLabels[dispute.value.status as DisputeStatus] : '')
const channelLabel = computed(() => dispute.value ? disputeChannelLabels[dispute.value.channel] : '')
const timeline = computed(() => dispute.value ? buildDisputeTimeline(dispute.value, adjudication.value) : [])
const voteSegments = computed(() => adjudication.value ? buildDisputeVoteSegments(adjudication.value) : [])

</script>

<template>
  <div class="dispute-detail-page">
    <header class="page-header">
      <div class="header-content">
        <button class="back-btn" type="button" aria-label="返回争议列表" @click="router.push('/me/disputes')">
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
        <p>{{ authPending ? '正在确认登录状态...' : '加载中...' }}</p>
      </div>

      <div v-else-if="anonymous" class="error-state">
        <p>登录后即可查看该争议案件</p>
        <button class="retry-btn" type="button" @click="emit('request-login')">去登录</button>
      </div>

      <div v-else-if="forbidden" class="error-state">
        <p>您没有权限查看该案件</p>
        <button class="retry-btn" type="button" @click="session.refresh()">重新查看</button>
      </div>

      <div v-else-if="notFound" class="error-state">
        <p>案件不存在或不可用</p>
        <button class="retry-btn" type="button" @click="session.refresh()">重试</button>
      </div>

      <div v-else-if="loadError" class="error-state">
        <p>{{ loadError }}</p>
        <button class="retry-btn" type="button" @click="session.refresh()">重试</button>
      </div>

      <div v-else-if="dispute" class="detail-container">
        <!-- Status Timeline -->
        <section class="card timeline-card">
          <h2 class="card-title">案件进度</h2>
          <div class="timeline">
            <div
              v-for="item in timeline"
              :key="item.key"
              class="timeline-item"
              :class="{ active: item.active }"
            >
              <div class="timeline-marker"></div>
              <div class="timeline-content">
                <div class="timeline-label">{{ item.label }}</div>
                <div v-if="item.time" class="timeline-time">
                  {{ item.time }}
                  <span v-if="item.remaining" class="time-remaining">{{ item.remaining }}</span>
                </div>
                <div v-if="item.detail" class="timeline-detail">{{ item.detail }}</div>
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
              <dd>{{ statusLabel }}</dd>
            </div>
            <div class="info-item">
              <dt>处理通道</dt>
              <dd>{{ channelLabel }}</dd>
            </div>
            <div class="info-item">
              <dt>开启时间</dt>
              <dd>{{ formatDisputeDate(dispute.createdAt) }}</dd>
            </div>
            <div v-if="dispute.reason" class="info-item info-item-full">
              <dt>争议原因</dt>
              <dd>{{ dispute.reason }}</dd>
            </div>
          </dl>
        </section>

        <!-- 写结果提示区：提交中/失败/待核实（只显示当前案的提示） -->
        <div v-if="activeNotice" class="write-notice" :class="activeNotice.kind === 'unverified' ? 'write-notice-unverified' : 'write-notice-failed'" role="status" data-testid="dispute-write-notice">
          {{ activeNotice.text }}
          <button
            v-if="activeNotice.kind === 'unverified'"
            class="retry-btn"
            type="button"
            @click="session.refresh()"
          >重新核实</button>
        </div>
        <div v-if="actions.status.value === 'verifying'" class="write-notice" role="status">正在核实刚才的操作结果…</div>

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
              class="gl-btn-primary"
              type="button"
              :disabled="actions.status.value === 'submitting'"
              @click="openEvidenceForm('answer')"
            >
              提交答辩
            </button>

            <button
              v-if="canSubmitRebuttal"
              class="gl-btn-primary"
              type="button"
              :disabled="actions.status.value === 'submitting'"
              @click="openEvidenceForm('rebuttal')"
            >
              补充质证
            </button>

            <button
              v-if="canMarkDone"
              class="gl-btn-secondary"
              type="button"
              :disabled="actions.status.value === 'submitting'"
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
          <button
            class="gl-btn-primary"
            type="button"
            :disabled="actions.status.value === 'submitting'"
            @click="startAdjudication"
          >
            启动审判
          </button>
        </section>

        <!-- Adjudication Results -->
        <section v-if="adjudication" class="card results-card">
          <h2 class="card-title">审判结果</h2>

          <div class="vote-summary">
            <div class="vote-bar">
              <div
                v-for="segment in voteSegments"
                :key="segment.key"
                class="vote-segment"
                :class="segment.cls"
                :style="{ width: segment.width }"
              >
                {{ segment.count }}
              </div>
            </div>
            <div class="vote-legend">
              <div v-for="segment in voteSegments" :key="segment.key" class="legend-item">
                <span class="legend-color" :class="segment.cls"></span>
                {{ segment.label }} {{ segment.count }} 票
              </div>
            </div>
          </div>

          <div v-if="adjudication.matchedPlatformCount != null" class="meta-info">
            <p>垂类熟手: {{ adjudication.matchedPlatformCount }}/{{ adjudication.panel.size }}</p>
            <p v-if="adjudication.probationCount">见习审判官: {{ adjudication.probationCount }}</p>
          </div>
        </section>

        <!-- 审判看板（2026-09-04 反馈 5：原工作台底部治理区迁入）——审判官投票/入池/考试、
             当事方上诉、客服终审的工作站；embedded 模式隐藏与上方原生当事方卡重复的质证块。 -->
        <section class="card adjudication-card">
          <AdjudicationPanel :dispute-id="dispute.id" embedded />
        </section>
      </div>
    </div>

    <!-- 证据表单（C103-12 拆出：打开冻结上下文；提交经 actions 票据协议） -->
    <DisputeEvidenceForm
      v-if="formOpen && capturedContext"
      :phase="formPhase"
      :case-key="capturedContext.disputeId"
      :case-label="caseLabel"
      :submitting="actions.status.value === 'submitting'"
      :context-current="capturedContext.isCurrent()"
      @submit="submitEvidence"
      @cancel="closeEvidenceForm"
    />
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
  border-radius: var(--radius-xl);
  background: var(--surface-hover);
  border: 1px solid var(--color-border);
  color: var(--color-text);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: background-color var(--duration-fast) var(--ease-out), border-color var(--duration-fast) var(--ease-out), color var(--duration-fast) var(--ease-out), opacity var(--duration-fast) var(--ease-out), transform var(--duration-fast) var(--ease-out), box-shadow var(--duration-fast) var(--ease-out);
}

.back-btn:hover {
  background: var(--surface-elevated);
  transform: translateX(-2px);
}

.header-text h1 {
  font-size: var(--type-page-title);
  font-weight: var(--weight-heading);
  margin: 0 0 0.25rem;
  letter-spacing: 0;
}

.subtitle {
  font-size: var(--type-body-sm);
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
  border-radius: var(--radius-xl);
  font-size: var(--type-body-sm);
  font-weight: var(--weight-label);
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
  border-radius: var(--radius-lg);
  padding: 1.5rem;
}

/* 审判看板自带边框卡片（.adj），外层卡片只做间距归零避免双边框 */
.adjudication-card { padding: var(--space-xs); }

.card-title {
  font-size: var(--type-section-title);
  font-weight: var(--weight-heading);
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
  padding-top: var(--space-micro);
}

.timeline-label {
  font-weight: var(--weight-label);
  margin-bottom: 0.25rem;
}

.timeline-time,
.timeline-detail {
  font-size: var(--type-body-sm);
  color: var(--color-text-secondary);
}

.time-remaining {
  margin-left: 0.5rem;
  color: var(--color-accent-2);
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
  font-size: var(--type-body-sm);
  color: var(--color-text-secondary);
  font-weight: var(--weight-label);
}

.info-item dd {
  margin: 0;
  font-size: var(--type-body);
  color: var(--color-text);
}

/* Write notices（提交中/失败/待核实统一提示区） */
.write-notice {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.875rem 1rem;
  border-radius: var(--radius-md);
  font-size: var(--type-body-sm);
  flex-wrap: wrap;
}

.write-notice-failed {
  background: color-mix(in srgb, var(--color-danger) 10%, transparent);
  border: 1px solid color-mix(in srgb, var(--color-danger) 30%, transparent);
  color: var(--color-danger);
}

.write-notice-unverified {
  background: color-mix(in srgb, var(--color-warning) 10%, transparent);
  border: 1px solid color-mix(in srgb, var(--color-warning) 30%, transparent);
  color: var(--color-warning);
}

/* Status Notices */
.status-notice {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 1rem;
  border-radius: var(--radius-md);
  font-size: var(--type-body-sm);
  margin-bottom: 1rem;
}

.status-notice-info {
  background: var(--surface-info);
  border: 1px solid var(--color-info);
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
  font-size: var(--type-body-sm);
  color: var(--color-text-secondary);
  margin: 0 0 1rem;
  line-height: 1.6;
}

.done-status {
  margin-top: 1rem;
  padding: 1rem;
  background: var(--surface-hover);
  border-radius: var(--radius-md);
  font-size: var(--type-body-sm);
  color: var(--color-text-secondary);
}

.done-status p {
  margin: 0 0 0.5rem;
}

.done-status p:last-child {
  margin-bottom: 0;
}

.both-done {
  color: var(--color-accent-2);
  font-weight: var(--weight-label);
}

/* Vote Summary */
.vote-summary {
  margin-bottom: 1.5rem;
}

.vote-bar {
  display: flex;
  height: 40px;
  border-radius: var(--radius-md);
  overflow: hidden;
  margin-bottom: 1rem;
  background: var(--surface-hover);
}

.vote-segment {
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--color-on-accent);
  font-weight: var(--weight-heading);
  font-size: var(--type-body-sm);
  transition: background-color var(--duration-fast) var(--ease-out), border-color var(--duration-fast) var(--ease-out), color var(--duration-fast) var(--ease-out), opacity var(--duration-fast) var(--ease-out), transform var(--duration-fast) var(--ease-out), box-shadow var(--duration-fast) var(--ease-out);
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
  font-size: var(--type-body-sm);
}

.legend-item {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.legend-color {
  width: 12px;
  height: 12px;
  border-radius: var(--radius-xs);
}

.meta-info {
  padding-top: 1rem;
  border-top: 1px solid var(--color-border);
  font-size: var(--type-body-sm);
  color: var(--color-text-secondary);
}

.meta-info p {
  margin: 0 0 0.5rem;
}

.meta-info p:last-child {
  margin-bottom: 0;
}

@media (max-width: 640px) {
  .info-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .action-buttons {
    flex-direction: column;
  }

  .action-buttons .gl-btn-primary,
  .action-buttons .gl-btn-secondary {
    width: 100%;
  }

  .vote-legend {
    flex-direction: column;
    gap: 0.75rem;
  }
}
</style>
