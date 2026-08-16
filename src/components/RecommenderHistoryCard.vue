<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useAuth } from '../composables/useAuth'
import { useGrassland } from '../composables/useGrassland'
import { formatCents } from '../lib/money'
import type { MyApplication } from '../types/grassland'

/**
 * 推荐官历史任务（任务书 #29+#30 #29）：my-applications 分页表（任务/状态/赏金/报名时间）。
 * keyset 分页（nextCursor），与 feed 同形状。self-scoped，只看得到自己的报名。
 */
const grassland = useGrassland()
const { currentUser } = useAuth()

const PAGE_SIZE = 20

const items = ref<MyApplication[]>([])
const nextCursor = ref<string | null>(null)
const hasMore = ref(false)

const APPLICATION_STATUS_LABEL: Record<string, string> = {
  pending: '待审核',
  reserving: '预留中',
  accepted: '已接受',
  rejected: '已拒绝',
  withdrawn: '已撤销',
  refunded: '已退款',
}

const isEmpty = computed(() => items.value.length === 0)

function statusLabel(status: string): string {
  return APPLICATION_STATUS_LABEL[status] || status
}

async function loadFirst(): Promise<void> {
  items.value = []
  nextCursor.value = null
  hasMore.value = false
  const result = await grassland.listMyApplications(undefined, undefined, PAGE_SIZE)
  if (!result) return
  items.value = result.items ?? []
  nextCursor.value = result.nextCursor ?? null
  hasMore.value = result.hasMore ?? false
}

async function loadMore(): Promise<void> {
  if (!hasMore.value || !nextCursor.value) return
  const result = await grassland.listMyApplications(undefined, nextCursor.value, PAGE_SIZE)
  if (!result) return
  items.value = [...items.value, ...(result.items ?? [])]
  nextCursor.value = result.nextCursor ?? null
  hasMore.value = result.hasMore ?? false
}

watch(() => currentUser.value?.id, (accountId) => {
  items.value = []
  if (accountId) loadFirst()
}, { immediate: true })
</script>

<template>
  <article class="rhc">
    <header class="rhc-head">
      <h3>历史任务</h3>
      <button type="button" class="rhc-quiet" :disabled="grassland.loading.value" @click="loadFirst">刷新</button>
    </header>

    <p v-if="grassland.error.value" class="rhc-alert" role="alert">{{ grassland.error.value }}</p>

    <p v-if="isEmpty" class="rhc-hint">还没有报名记录。去任务大厅看看有什么合适的任务吧。</p>
    <table v-else class="rhc-table">
      <thead><tr><th>任务</th><th>报名状态</th><th>赏金</th><th>报名时间</th><th>结算时间</th></tr></thead>
      <tbody>
        <tr v-for="item in items" :key="item.applicationId">
          <td>{{ item.taskTitle || '（无标题任务）' }}</td>
          <td>{{ statusLabel(item.applicationStatus) }}</td>
          <td>{{ item.bountyCents > 0 ? '¥' + formatCents(item.bountyCents) : '非资金' }}</td>
          <td>{{ item.appliedAt ? item.appliedAt.slice(0, 10) : '—' }}</td>
          <td>{{ item.settledAt ? item.settledAt.slice(0, 10) : '—' }}</td>
        </tr>
      </tbody>
    </table>

    <button v-if="hasMore" type="button" :disabled="grassland.loading.value" @click="loadMore">加载更多</button>
  </article>
</template>

<style scoped>
.rhc { display: flex; flex-direction: column; gap: 10px; }
.rhc-head { display: flex; justify-content: space-between; align-items: center; }
.rhc-head h3 { margin: 0; font-size: 15px; }
.rhc-alert { margin: 0; padding: 7px 11px; border-radius: 6px; font-size: 13px;
  background: color-mix(in srgb, var(--color-danger) 14%, transparent); color: var(--color-danger); }
.rhc-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.rhc-table th, .rhc-table td { text-align: left; padding: 6px 8px; border-bottom: 1px solid var(--color-border); }
.rhc-hint { margin: 0; font-size: 12px; opacity: 0.62; }
.rhc-quiet { opacity: 0.75; font-size: 12px; padding: 4px 10px; }
button { padding: 6px 14px; border: 1px solid var(--color-border); background: transparent; color: var(--color-text); border-radius: 6px; cursor: pointer; font-size: 13px; }
button:hover:not(:disabled) { border-color: var(--color-border-hover); background: var(--color-surface-hover); }
button:disabled { opacity: 0.5; cursor: not-allowed; }
</style>
