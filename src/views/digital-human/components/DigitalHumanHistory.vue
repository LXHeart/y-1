<template>
  <section class="dh-history" aria-labelledby="dh-history-title" data-testid="dh-history">
    <header class="dh-history-head">
      <div>
        <h3 id="dh-history-title" class="dh-history-title">历史会话</h3>
        <p class="dh-hint">只显示你自己的会话；时间窗为 UTC 日期（半开区间，跨度 ≤90 天）。</p>
      </div>
    </header>

    <!-- 筛选：变更即重新拉首页（取消旧读、清游标；迟到回包不覆盖新列表）。 -->
    <div class="dh-history-filters" data-testid="dh-history-filters">
      <div class="gl-form-field">
        <label class="field-label" for="dh-history-state">状态</label>
        <select id="dh-history-state" data-testid="dh-history-state" :value="filters.state"
          @change="emit('filter', { state: ($event.target as HTMLSelectElement).value as HistoryFilters['state'] })">
          <option value="">全部状态</option>
          <option v-for="option in STATE_OPTIONS" :key="option" :value="option">{{ stateLabel(option) }}</option>
        </select>
      </div>
      <div class="gl-form-field">
        <label class="field-label" for="dh-history-from">开始日期（UTC）</label>
        <input id="dh-history-from" data-testid="dh-history-from" type="date" :value="filters.fromDate"
          @change="emit('filter', { fromDate: ($event.target as HTMLInputElement).value })" />
      </div>
      <div class="gl-form-field">
        <label class="field-label" for="dh-history-to">结束日期（UTC，不含当天）</label>
        <input id="dh-history-to" data-testid="dh-history-to" type="date" :value="filters.toDate"
          @change="emit('filter', { toDate: ($event.target as HTMLInputElement).value })" />
      </div>
    </div>

    <p v-if="error" class="gl-alert gl-alert-error" role="alert" data-testid="dh-history-error">
      {{ error }}
      <button v-if="items.length > 0" type="button" class="gl-link" @click="emit('retry')">重试</button>
    </p>
    <p v-if="loading" class="dh-hint" role="status" data-testid="dh-history-loading">正在加载历史…</p>

    <EmptyState
      v-else-if="items.length === 0"
      card
      kicker="暂无记录"
      title="没有符合条件的历史会话"
      description="调整筛选条件，或完成一场会话后再来查看。"
    />

    <ul v-else class="dh-history-list" data-testid="dh-history-list">
      <li v-for="item in items" :key="item.id" class="dh-history-item" :data-testid="`dh-history-${item.id.slice(0, 8)}`">
        <div class="dh-history-item-main">
          <header class="dh-history-item-head">
            <span class="dh-history-name">{{ item.profileNameAtCreation }}</span>
            <span class="badge" :class="stateBadgeClass(item.state)">{{ stateLabel(item.state) }}</span>
            <span v-if="item.billing.pendingCount > 0" class="badge badge-warning" data-testid="dh-history-pending">
              待核对 {{ item.billing.pendingCount }} 笔
            </span>
          </header>
          <p class="dh-history-meta">
            <span class="gl-num">{{ formatUtc(item.createdAt) }}</span> 开始
            <template v-if="item.endedAt">· {{ formatUtc(item.endedAt) }} 结束</template>
            <template v-if="item.recordingCount > 0">· 录制 {{ item.recordingCount }} 段</template>
            <template v-if="item.savedAssetCount > 0">· 已存素材 {{ item.savedAssetCount }} 个</template>
          </p>
          <p v-if="outcomeOf(item.id)" class="dh-history-outcome" :data-state="outcomeOf(item.id)?.state" role="status">
            {{ outcomeOf(item.id)?.message }}
          </p>
        </div>
        <div class="gl-actions">
          <button
            type="button"
            class="gl-btn-secondary"
            :disabled="deletingIds.has(item.id)"
            :data-testid="`dh-history-delete-${item.id.slice(0, 8)}`"
            @click="pendingDelete = item"
          >
            {{ deletingIds.has(item.id) ? '删除中…' : '删除' }}
          </button>
        </div>
      </li>
    </ul>

    <div v-if="items.length > 0" class="gl-actions">
      <button
        v-if="hasMore"
        type="button"
        class="gl-btn-secondary"
        :disabled="loading"
        data-testid="dh-history-more"
        @click="emit('load-more')"
      >
        {{ loading ? '加载中…' : '加载更多' }}
      </button>
      <p v-else class="dh-hint">已到末尾。</p>
    </div>

    <!-- 删除确认：明确列出保留与删除内容（资产独立归属，K09/G-01 步骤3）。 -->
    <GlModal v-if="pendingDelete" title="删除这场会话" :persistent="deletingIds.has(pendingDelete.id)"
      @close="pendingDelete = null">
      <div class="dh-history-confirm" data-testid="dh-history-confirm">
        <p>将删除「{{ pendingDelete.profileNameAtCreation }}」（{{ formatUtc(pendingDelete.createdAt) }}）的会话记录：</p>
        <ul class="dh-history-rules">
          <li>删除：本场会话条目、已保存的字幕文本与未保存的临时产物（立即不可读）。</li>
          <li>保留：已保存到素材库的视频、附属字幕文件与账务记录——这些需在素材库另行删除。</li>
          <li>删除在后台完成；若清理中断，可稍后重试，不会提前宣称全部物理删除。</li>
        </ul>
        <div class="gl-actions">
          <button type="button" class="gl-btn-secondary" @click="pendingDelete = null">取消</button>
          <button
            type="button"
            class="gl-btn-primary"
            :disabled="deletingIds.has(pendingDelete.id)"
            data-testid="dh-history-confirm-delete"
            @click="confirmDelete(pendingDelete.id)"
          >
            {{ deletingIds.has(pendingDelete.id) ? '删除中…' : '确认删除' }}
          </button>
        </div>
      </div>
    </GlModal>
  </section>
</template>

<script setup lang="ts">
// 纯 props/emits（K11 DigitalHumanHistory(page,loading,filters)）；数据与请求代次在 composable。
import { ref } from 'vue'
import EmptyState from '../../../components/shared/EmptyState.vue'
import GlModal from '../../../components/GlModal.vue'
import type { DeleteOutcome, HistoryFilters } from '../composables/useDigitalHumanHistory'
import type { SessionState, SessionSummary } from '../../../types/digital-human'

const props = defineProps<{
  items: SessionSummary[]
  loading: boolean
  error: string | null
  filters: HistoryFilters
  hasMore: boolean
  deletingIds: ReadonlySet<string>
  deleteOutcomes: Readonly<Record<string, DeleteOutcome>>
}>()

const emit = defineEmits<{
  (e: 'filter', next: Partial<HistoryFilters>): void
  (e: 'load-more'): void
  (e: 'delete', sessionId: string): void
  (e: 'retry'): void
}>()

const STATE_OPTIONS: ReadonlyArray<SessionState> = ['ended', 'failed']

const STATE_LABELS: Readonly<Record<SessionState, string>> = {
  preparing: '准备中', queued: '排队中', connecting: '连接中', ready: '进行中', listening: '聆听中',
  responding: '回复中', paused: '已暂停', reconnecting: '重连中', ending: '结束中', ended: '已结束', failed: '失败',
}

function stateLabel(state: SessionState): string {
  return STATE_LABELS[state] ?? state
}

function stateBadgeClass(state: SessionState): string {
  if (state === 'ended') return 'badge-neutral'
  if (state === 'failed') return 'badge-danger'
  return 'badge-info'
}

function formatUtc(iso: string): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return iso
  return date.toISOString().replace('T', ' ').replace(/\.\d+Z$/, 'Z')
}

const pendingDelete = ref<SessionSummary | null>(null)

function confirmDelete(sessionId: string): void {
  emit('delete', sessionId)
}

function outcomeOf(sessionId: string): DeleteOutcome | undefined {
  return props.deleteOutcomes[sessionId]
}
</script>
