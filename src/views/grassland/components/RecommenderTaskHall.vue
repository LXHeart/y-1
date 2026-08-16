<template>
  <article id="gl-task-hall" class="gl-card gl-card-wide">
    <h3>任务大厅</h3>
    <div class="gl-row">
      <input :value="feedFilters.platform" placeholder="平台筛选（可选）" @input="$emit('update:feedFilter', 'platform', ($event.target as HTMLInputElement).value)" />
      <input :value="feedFilters.contentForm" placeholder="内容形式筛选（可选）" @input="$emit('update:feedFilter', 'contentForm', ($event.target as HTMLInputElement).value)" />
      <label>最低赏金 ¥<input :value="feedFilters.minBountyYuan" type="number" min="0" @input="$emit('update:feedFilter', 'minBountyYuan', Number(($event.target as HTMLInputElement).value))" /></label>
      <label>距离
        <select :value="feedFilters.maxDistanceKm" @change="$emit('update:feedFilter', 'maxDistanceKm', Number(($event.target as HTMLSelectElement).value))">
          <option :value="0">不限</option><option :value="1">1 公里内</option><option :value="3">3 公里内</option>
          <option :value="5">5 公里内</option><option :value="10">10 公里内</option><option :value="30">30 公里内</option>
        </select>
      </label>
      <button type="button" :disabled="locating" @click="$emit('use-location')">
        {{ locating ? '定位中...' : feedFilters.latitude == null ? '使用当前位置' : '更新位置' }}
      </button>
      <button type="button" :disabled="feedLoading || loading" @click="$emit('load-feed', true)">查询</button>
    </div>
    <div class="gl-row">
      <input :value="applyNote" placeholder="报名留言（可选）" @input="$emit('update:applyNote', ($event.target as HTMLInputElement).value)" />
    </div>
    <p class="gl-hint">大厅只显示已发布且未截止的任务；报名截止后不再接受新报名。</p>
    <p v-if="feedItems.length === 0" class="gl-empty">暂无可报名任务</p>
    <table v-else class="gl-table">
      <thead><tr><th>任务</th><th>门店</th><th>平台</th><th>赏金</th><th>距离</th><th>截止</th><th>操作</th></tr></thead>
      <tbody>
        <tr v-for="t in feedItems" :key="t.id">
          <td>
            <button type="button" class="gl-link" :class="{ active: selectedTaskId === t.id }"
                    @click="$emit('select-task', t.id)">{{ t.title }}</button>
          </td>
          <td>{{ t.store ? [t.store.storeName, t.store.city].filter(Boolean).join(' · ') : '—' }}</td>
          <td>{{ t.platform || '—' }}</td>
          <td>{{ t.bountyCents ? `¥${(t.bountyCents / 100).toFixed(2)}` : '无' }}</td>
          <td>{{ t.distanceKm == null ? '—' : `${t.distanceKm.toFixed(1)} km` }}</td>
          <td>{{ t.applicationDeadline ? new Date(t.applicationDeadline).toLocaleString() : '不限' }}</td>
          <td>
            <button type="button" :disabled="loading" @click="$emit('apply', t.id)">报名</button>
          </td>
        </tr>
      </tbody>
    </table>
    <button v-if="feedHasMore" type="button" :disabled="feedLoading" @click="$emit('load-feed', false)">加载更多</button>
  </article>
</template>

<script setup lang="ts">
import type { Task } from '../../../types/grassland'

defineProps<{
  feedItems: Task[]
  feedHasMore: boolean
  feedLoading: boolean
  feedFilters: {
    platform: string; contentForm: string; minBountyYuan: number; maxDistanceKm: number
    latitude: number | null; longitude: number | null
  }
  applyNote: string
  selectedTaskId: string
  loading: boolean
  locating: boolean
}>()

defineEmits<{
  'update:feedFilter': [field: string, value: string | number]
  'load-feed': [reset: boolean]
  'update:applyNote': [value: string]
  'select-task': [taskId: string]
  apply: [taskId: string]
  'use-location': []
}>()
</script>

<style scoped>
select {
  min-height: 34px;
  border: 1px solid var(--color-border);
  border-radius: 6px;
  background: var(--color-surface);
  color: var(--color-text);
  padding: 0 8px;
  font: inherit;
  letter-spacing: 0;
}
</style>
