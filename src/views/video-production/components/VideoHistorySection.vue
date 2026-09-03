<script setup lang="ts">
import { ref } from 'vue'
import type { HistoryItem } from '../../../composables/useVideoProduction'

interface Props {
  history: { items: HistoryItem[]; total: number; page: number }
  historyLoading: boolean
  historyError: string
}

const props = defineProps<Props>()

const emit = defineEmits<{
  /** 首次展开或点「刷新」时请求第一页（父级调 loadHistory(1)）。 */
  refresh: []
}>()

const historyExpanded = ref(false)

function toggleHistory(): void {
  historyExpanded.value = !historyExpanded.value
  if (historyExpanded.value && props.history.items.length === 0) {
    emit('refresh')
  }
}

function phaseLabel(phase: string): string {
  return { queued: '排队', generating: '生成中', voicing: '配音中', composing: '合成中',
    succeeded: '已完成', failed: '失败', cancelled: '已取消' }[phase] || phase
}

function formatHistoryTime(value: string): string {
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString('zh-CN')
}
</script>

<template>
  <!-- 历史任务（任务书 #64 卡9，参考 VideoRecreationPanel 手风琴；任务书 #68 卡 E 抽取） -->
  <section class="history-section gl-zone" aria-labelledby="history-heading">
    <div class="card-head-row">
      <div>
        <p class="eyebrow">历史任务</p>
        <h3 id="history-heading" class="card-title">生成记录</h3>
      </div>
      <div class="action-row">
        <button type="button" class="btn-secondary btn-sm" :disabled="historyLoading" data-test="history-toggle" @click="toggleHistory">
          {{ historyExpanded ? '收起' : '展开' }}
        </button>
        <button v-if="historyExpanded" type="button" class="btn-secondary btn-sm" :disabled="historyLoading" @click="emit('refresh')">
          刷新
        </button>
      </div>
    </div>

    <template v-if="historyExpanded">
      <p v-if="historyError" class="error-hint">{{ historyError }}</p>
      <p v-else-if="historyLoading && history.items.length === 0" class="field-note">正在加载历史任务…</p>
      <p v-else-if="history.items.length === 0" class="field-note">还没有成片任务。</p>
      <div v-else class="history-list">
        <article v-for="item in history.items" :key="item.id" class="history-item" data-test="history-item">
          <div class="history-row">
            <span class="shot-badge">{{ item.mode === 'slideshow' ? '图文' : '视频' }}</span>
            <strong>{{ phaseLabel(item.phase) }}</strong>
            <span class="field-note">
              {{ item.targetDurationSeconds }} 秒档
              <template v-if="item.actualDurationSeconds"> · 实际 {{ item.actualDurationSeconds }} 秒</template>
              <template v-if="item.createdAt"> · {{ formatHistoryTime(item.createdAt) }}</template>
            </span>
          </div>
          <p v-if="item.errorMessage" class="field-note">{{ item.errorMessage }}</p>
        </article>
      </div>
      <p class="field-note">共 {{ history.total }} 条</p>
    </template>
  </section>
</template>

<style scoped>
.history-section {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
  padding: var(--space-md);
  border-radius: var(--radius-lg);
}

.history-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
}

.history-item {
  padding: var(--space-xs) var(--space-sm);
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border);
  background: var(--color-surface);
}

.history-row {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  flex-wrap: wrap;
}

/* 父级 scoped 共享类复制（scoped 不穿透子组件，样式须随迁） */
.card-head-row {
  display: flex;
  align-items: center;
  gap: var(--space-md);
  margin-bottom: var(--space-sm);
}

.eyebrow {
  font-size: 12px;
  color: var(--color-accent);
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.card-title {
  font-size: 18px;
  font-weight: 600;
  margin-bottom: 4px;
}

.action-row {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
}

.field-note {
  font-size: 13px;
  color: var(--color-text-muted);
}

.error-hint {
  color: var(--color-danger);
  font-size: 13px;
  margin-bottom: var(--space-sm);
}

.btn-secondary {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 38px;
  padding: 0 var(--space-md);
  border-radius: var(--radius-sm);
  font-size: var(--text-sm);
  text-decoration: none;
}

.btn-sm {
  font-size: var(--text-xs);
  padding: 4px 10px;
}

.shot-badge {
  display: inline-flex;
  align-items: center;
  padding: 2px 10px;
  border-radius: var(--radius-pill);
  font-size: var(--text-xs);
  font-weight: 600;
  background: color-mix(in srgb, var(--color-accent) 16%, transparent);
  color: var(--color-accent);
}
</style>
