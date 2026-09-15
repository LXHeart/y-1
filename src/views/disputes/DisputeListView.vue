<script setup lang="ts">
import { computed, onActivated, onDeactivated, onMounted, onUnmounted } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { useDisputeListSession } from './composables/useDisputeListSession'
import type { DisputeCase, DisputeStatus, DisputeChannel } from '../../types/grassland/dispute'

/** 匿名态走既有登录引导（布局 LoginModal 经 request-login 接线），本页不自建登录入口。 */
const emit = defineEmits<{ 'request-login': [] }>()

const router = useRouter()
// C103-11：取数/等待/错误进域 composable——身份恢复中等待、错误本地呈现不再跳首页、
// 换目标立即清旧数据（账号 epoch + 激活代次闸），视图只保留 UI 组合。
const session = useDisputeListSession()
const disputes = computed(() => session.items.value)
const authPending = computed(() => session.state.value === 'auth_pending')
const loading = computed(() => session.state.value === 'loading' || authPending.value)
const anonymous = computed(() => session.state.value === 'anonymous')
const loadError = computed(() => session.state.value === 'error' ? session.error.value : '')

onMounted(session.activate)
onActivated(session.activate)
onDeactivated(session.deactivate)
onUnmounted(session.deactivate)

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

const statusBadges: Record<DisputeStatus, string> = {
  open: 'badge-info', evidence: 'badge-info', voting: 'badge-info',
  decided: 'badge-success', appealed: 'badge-warning', final: 'badge-neutral',
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

function getDeadlineText(dispute: DisputeCase): string {
  if (dispute.status === 'evidence' && dispute.evidenceDeadline) {
    const deadline = new Date(dispute.evidenceDeadline)
    const now = new Date()
    const hoursRemaining = Math.max(0, Math.floor((deadline.getTime() - now.getTime()) / (1000 * 60 * 60)))

    if (hoursRemaining <= 0) return '质证期已结束'
    if (hoursRemaining < 24) return `质证期剩余 ${hoursRemaining} 小时`
    return `质证期剩余 ${Math.floor(hoursRemaining / 24)} 天`
  }

  if (dispute.channel === 'cs_direct' && dispute.csDueAt && dispute.status !== 'final') {
    const deadline = new Date(dispute.csDueAt)
    const now = new Date()
    const hoursRemaining = Math.max(0, Math.floor((deadline.getTime() - now.getTime()) / (1000 * 60 * 60)))

    if (hoursRemaining <= 0) return '已超客服 SLA'
    if (hoursRemaining < 24) return `客服处理剩余 ${hoursRemaining} 小时`
    return `客服处理剩余 ${Math.floor(hoursRemaining / 24)} 天`
  }

  return ''
}

const activeDisputes = computed(() => disputes.value.filter(d => d.status !== 'final'))
const finalDisputes = computed(() => disputes.value.filter(d => d.status === 'final'))
</script>

<template>
  <div class="dispute-list-page">
    <header class="page-header">
      <div class="header-content">
        <button class="back-btn" type="button" aria-label="返回工作台" @click="router.push('/grassland')">
          <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <path d="M10 12L6 8l4-4" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
        </button>
        <div class="header-text">
          <h1>我的争议</h1>
          <p class="subtitle">查看与管理您的争议案件</p>
        </div>
      </div>
    </header>

    <div class="page-content">
      <div v-if="loading" class="loading-state">
        <div class="spinner"></div>
        <p>{{ authPending ? '正在确认登录状态...' : '加载中...' }}</p>
      </div>

      <div v-else-if="anonymous" class="empty-state">
        <svg width="64" height="64" viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <path d="M12 3L4 7v5c0 4.4 3.4 8.4 8 9 4.6-.6 8-4.6 8-9V7l-8-4z" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
          <path d="M9.5 12l1.8 1.8 3.5-3.5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
        </svg>
        <p>登录后即可查看您的争议案件</p>
        <button class="retry-btn" type="button" @click="emit('request-login')">去登录</button>
      </div>

      <div v-else-if="loadError" class="error-state">
        <p>{{ loadError }}</p>
        <button class="retry-btn" type="button" @click="session.refresh()">重试</button>
      </div>

      <div v-else-if="disputes.length === 0" class="empty-state">
        <svg width="64" height="64" viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <path d="M9 12h6M9 16h6M13 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V9l-7-7z" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
        </svg>
        <p>暂无争议案件</p>
      </div>

      <div v-else class="disputes-container">
        <section v-if="activeDisputes.length > 0" class="dispute-section">
          <h2 class="section-title">进行中（{{ activeDisputes.length }}）</h2>
          <div class="dispute-grid">
            <RouterLink
              v-for="dispute in activeDisputes"
              :key="dispute.id"
              class="dispute-card"
              :to="`/me/disputes/${dispute.id}`"
            >
              <div class="card-header">
                <div class="status-row">
                  <span class="status-badge badge" :class="statusBadges[dispute.status]">
                    {{ statusLabels[dispute.status] }}
                  </span>
                  <span class="channel-badge">{{ channelLabels[dispute.channel] }}</span>
                </div>
                <time class="card-date">{{ formatDate(dispute.createdAt) }}</time>
              </div>

              <div class="card-body">
                <p class="dispute-ref">案件 {{ dispute.id.slice(0, 8) }}</p>
                <p v-if="dispute.reason" class="dispute-reason">{{ dispute.reason }}</p>

                <div v-if="getDeadlineText(dispute)" class="deadline-alert">
                  <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                    <circle cx="8" cy="8" r="6.5" stroke="currentColor" stroke-width="1.3"/>
                    <path d="M8 5v3l2 2" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/>
                  </svg>
                  {{ getDeadlineText(dispute) }}
                </div>
              </div>

              <div class="card-footer">
                <span class="action-hint">查看详情 →</span>
              </div>
            </RouterLink>
          </div>
        </section>

        <section v-if="finalDisputes.length > 0" class="dispute-section">
          <h2 class="section-title">已终局（{{ finalDisputes.length }}）</h2>
          <div class="dispute-grid">
            <RouterLink
              v-for="dispute in finalDisputes"
              :key="dispute.id"
              class="dispute-card dispute-card-final"
              :to="`/me/disputes/${dispute.id}`"
            >
              <div class="card-header">
                <div class="status-row">
                  <span class="status-badge badge" :class="statusBadges[dispute.status]">
                    {{ statusLabels[dispute.status] }}
                  </span>
                  <span class="channel-badge">{{ channelLabels[dispute.channel] }}</span>
                </div>
                <time class="card-date">{{ formatDate(dispute.createdAt) }}</time>
              </div>

              <div class="card-body">
                <p class="dispute-ref">案件 {{ dispute.id.slice(0, 8) }}</p>
                <p v-if="dispute.finalDecision" class="final-decision">
                  最终裁决：{{ dispute.finalDecision === 'for_merchant' ? '商家胜诉' : '推荐官胜诉' }}
                </p>
              </div>

              <div class="card-footer">
                <span class="action-hint">查看详情 →</span>
              </div>
            </RouterLink>
          </div>
        </section>
      </div>
    </div>
  </div>
</template>

<style scoped>
.dispute-list-page {
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
  max-width: 1200px;
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
}

.page-content {
  max-width: 1200px;
  margin: 0 auto;
  padding: clamp(1.5rem, 4vw, 2.5rem) clamp(1rem, 5vw, 2rem);
}

.loading-state,
.error-state,
.empty-state {
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

.empty-state svg {
  margin: 0 auto 1.5rem;
  color: var(--color-text-muted);
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

.dispute-section {
  margin-bottom: 3rem;
}

.section-title {
  font-size: var(--type-section-title);
  font-weight: var(--weight-heading);
  margin: 0 0 1.5rem;
  color: var(--color-text);
}

.dispute-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(min(100%, 320px), 1fr));
  gap: 1.5rem;
}

.dispute-card {
  display: block;
  text-decoration: none;
  background: var(--surface-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: 1.25rem;
  cursor: pointer;
  transition: background-color var(--duration-fast) var(--ease-out), border-color var(--duration-fast) var(--ease-out), color var(--duration-fast) var(--ease-out), opacity var(--duration-fast) var(--ease-out), transform var(--duration-fast) var(--ease-out), box-shadow var(--duration-fast) var(--ease-out);
}

.dispute-card:hover {
  background: var(--surface-hover);
  border-color: var(--color-accent);
  box-shadow: none;
}

.dispute-card-final {
  opacity: 0.75;
}

.dispute-card-final:hover {
  opacity: 1;
}

.card-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 1rem;
  margin-bottom: 1rem;
}

.status-row {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  flex-wrap: wrap;
}

.status-badge {
  padding: 0.25rem 0.75rem;
  border-radius: var(--radius-xl);
  font-size: var(--type-caption);
  font-weight: var(--weight-label);
}

.channel-badge {
  padding: 0.25rem 0.75rem;
  border-radius: var(--radius-xl);
  font-size: var(--type-caption);
  font-weight: var(--weight-label);
  background: var(--surface-elevated);
  color: var(--color-text-secondary);
  border: 1px solid var(--color-border);
}

.card-date {
  font-size: var(--type-caption);
  color: var(--color-text-muted);
  white-space: nowrap;
}

.card-body {
  margin-bottom: 1rem;
}

.dispute-ref {
  font-size: var(--type-body-sm);
  font-weight: var(--weight-label);
  color: var(--color-text);
  margin: 0 0 0.5rem;
  font-family: var(--font-mono);
}

.dispute-reason {
  font-size: var(--type-body-sm);
  color: var(--color-text-secondary);
  margin: 0 0 0.75rem;
  line-height: 1.5;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.final-decision {
  font-size: var(--type-body-sm);
  color: var(--color-text-secondary);
  margin: 0;
}

.deadline-alert {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.5rem 0.75rem;
  background: color-mix(in srgb, var(--color-warning) 10%, transparent);
  border: 1px solid color-mix(in srgb, var(--color-warning) 30%, transparent);
  border-radius: var(--radius-sm);
  font-size: var(--type-caption);
  color: var(--color-warning);
  font-weight: var(--weight-label);
}

.card-footer {
  padding-top: 0.75rem;
  border-top: 1px solid var(--color-border);
}

.action-hint {
  font-size: var(--type-body-sm);
  color: var(--color-accent-2);
  font-weight: var(--weight-label);
}

@media (max-width: 640px) {
  .dispute-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .header-content {
    gap: 0.75rem;
  }

  .back-btn {
    width: 36px;
    height: 36px;
  }
}
</style>
