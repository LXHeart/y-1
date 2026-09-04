<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuth } from '../../composables/useAuth'
import { useGrassland } from '../../composables/useGrassland'
import type { DisputeCase, DisputeStatus, DisputeChannel } from '../../types/grassland/dispute'

const router = useRouter()
const { isAuthenticated } = useAuth()
const grassland = useGrassland()

const disputes = ref<DisputeCase[]>([])
const loading = ref(false)

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

const statusColors: Record<DisputeStatus, string> = {
  open: 'var(--accent)',
  evidence: 'var(--accent)',
  voting: '#6366f1',
  decided: '#10b981',
  appealed: '#f59e0b',
  final: 'var(--text-secondary)',
}

async function loadDisputes(): Promise<void> {
  if (!isAuthenticated.value) {
    router.push('/')
    return
  }

  loading.value = true
  try {
    const res = await fetch('/api/trust/disputes/me', {
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
    })

    if (!res.ok) {
      if (res.status === 401) {
        router.push('/')
        return
      }
      throw new Error(`加载失败: ${res.status}`)
    }

    const data = await res.json()
    disputes.value = data.items || []
  } catch (error: unknown) {
    console.error('加载争议列表失败:', error)
    grassland.error.value = error instanceof Error ? error.message : '加载失败'
  } finally {
    loading.value = false
  }
}

function viewDispute(dispute: DisputeCase): void {
  router.push(`/me/disputes/${dispute.id}`)
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

onMounted(loadDisputes)
</script>

<template>
  <div class="dispute-list-page">
    <header class="page-header">
      <div class="header-content">
        <button class="back-btn" type="button" @click="router.push('/grassland')">
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
        <p>加载中...</p>
      </div>

      <div v-else-if="grassland.error.value" class="error-state">
        <p>{{ grassland.error.value }}</p>
        <button class="retry-btn" type="button" @click="loadDisputes">重试</button>
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
            <article
              v-for="dispute in activeDisputes"
              :key="dispute.id"
              class="dispute-card"
              @click="viewDispute(dispute)"
            >
              <div class="card-header">
                <div class="status-row">
                  <span class="status-badge" :style="{ backgroundColor: statusColors[dispute.status] }">
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
            </article>
          </div>
        </section>

        <section v-if="finalDisputes.length > 0" class="dispute-section">
          <h2 class="section-title">已终局（{{ finalDisputes.length }}）</h2>
          <div class="dispute-grid">
            <article
              v-for="dispute in finalDisputes"
              :key="dispute.id"
              class="dispute-card dispute-card-final"
              @click="viewDispute(dispute)"
            >
              <div class="card-header">
                <div class="status-row">
                  <span class="status-badge" :style="{ backgroundColor: statusColors[dispute.status] }">
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
            </article>
          </div>
        </section>
      </div>
    </div>
  </div>
</template>

<style scoped>
.dispute-list-page {
  min-height: 100vh;
  background: var(--surface-0);
  color: var(--text);
}

.page-header {
  background: var(--surface-1);
  border-bottom: 1px solid var(--border);
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
  border-radius: 999px;
  background: var(--surface-2);
  border: 1px solid var(--border);
  color: var(--text);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.2s;
}

.back-btn:hover {
  background: var(--surface-3);
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
  color: var(--text-secondary);
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
  color: var(--text-secondary);
}

.spinner {
  width: 40px;
  height: 40px;
  margin: 0 auto 1rem;
  border: 3px solid var(--border);
  border-top-color: var(--accent);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.empty-state svg {
  margin: 0 auto 1.5rem;
  color: var(--text-tertiary);
}

.retry-btn {
  margin-top: 1rem;
  padding: 0.625rem 1.25rem;
  background: var(--accent);
  color: white;
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

.dispute-section {
  margin-bottom: 3rem;
}

.section-title {
  font-size: 1.125rem;
  font-weight: 600;
  margin: 0 0 1.5rem;
  color: var(--text);
}

.dispute-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(min(100%, 320px), 1fr));
  gap: 1.5rem;
}

.dispute-card {
  background: var(--surface-1);
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 1.25rem;
  cursor: pointer;
  transition: all 0.2s;
}

.dispute-card:hover {
  background: var(--surface-2);
  border-color: var(--accent);
  transform: translateY(-2px);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.12);
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
  border-radius: 999px;
  font-size: 0.75rem;
  font-weight: 500;
  color: white;
}

.channel-badge {
  padding: 0.25rem 0.75rem;
  border-radius: 999px;
  font-size: 0.75rem;
  font-weight: 500;
  background: var(--surface-3);
  color: var(--text-secondary);
  border: 1px solid var(--border);
}

.card-date {
  font-size: 0.75rem;
  color: var(--text-tertiary);
  white-space: nowrap;
}

.card-body {
  margin-bottom: 1rem;
}

.dispute-ref {
  font-size: 0.875rem;
  font-weight: 500;
  color: var(--text);
  margin: 0 0 0.5rem;
  font-family: 'SF Mono', 'Consolas', monospace;
}

.dispute-reason {
  font-size: 0.875rem;
  color: var(--text-secondary);
  margin: 0 0 0.75rem;
  line-height: 1.5;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.final-decision {
  font-size: 0.875rem;
  color: var(--text-secondary);
  margin: 0;
}

.deadline-alert {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.5rem 0.75rem;
  background: rgba(245, 158, 11, 0.1);
  border: 1px solid rgba(245, 158, 11, 0.3);
  border-radius: 6px;
  font-size: 0.75rem;
  color: #f59e0b;
  font-weight: 500;
}

.card-footer {
  padding-top: 0.75rem;
  border-top: 1px solid var(--border);
}

.action-hint {
  font-size: 0.875rem;
  color: var(--accent);
  font-weight: 500;
}

@media (max-width: 640px) {
  .dispute-grid {
    grid-template-columns: 1fr;
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
