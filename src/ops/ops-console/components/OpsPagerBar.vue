<template>
  <div class="ops-pager">
    <button type="button" class="ops-quiet" :disabled="atFirstPage" @click="prev">上一页</button>
    <span class="ops-pager-info">第 {{ currentPage }} / {{ totalPages }} 页 · 共 {{ total }} 条</span>
    <button type="button" class="ops-quiet" :disabled="atLastPage" @click="next">下一页</button>
    <label class="ops-pager-size">每页
      <select :value="limit" @change="changeLimit">
        <option v-if="!PAGE_SIZE_OPTIONS.includes(limit)" :value="limit">{{ limit }}</option>
        <option v-for="size in PAGE_SIZE_OPTIONS" :key="size" :value="size">{{ size }}</option>
      </select>
      条
    </label>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

/**
 * 运营队列分页条（任务书 #94 C94-04）。交互对齐治理台 OpsPagination（上一页 | 第 x / y 页 · 共 N 条
 * | 下一页 | 每页 N 条），页大小档位按 D94-09 为 50/100/200。
 *
 * 契约同 OpsPagination：父组件持 offset/limit 真源，本组件只发 change(offset) 与
 * change-limit(limit)，不自行翻页、不取数。total=0 时由父级决定是否渲染（空态口径）。
 */
const props = defineProps<{
  total: number
  limit: number
  offset: number
}>()

const emit = defineEmits<{
  change: [offset: number]
  'change-limit': [limit: number]
}>()

const PAGE_SIZE_OPTIONS: readonly number[] = [50, 100, 200]

const totalPages = computed(() => Math.max(1, Math.ceil(props.total / props.limit)))
/** 展示页码夹在 [1, totalPages]；真源收敛（删行漂移）由父级刷新后重置 offset 处理。 */
const currentPage = computed(() =>
  Math.min(Math.max(1, Math.floor(props.offset / props.limit) + 1), totalPages.value))
const atFirstPage = computed(() => currentPage.value <= 1)
const atLastPage = computed(() => currentPage.value >= totalPages.value)

function prev(): void {
  if (!atFirstPage.value) emit('change', (currentPage.value - 2) * props.limit)
}

function next(): void {
  if (!atLastPage.value) emit('change', currentPage.value * props.limit)
}

function changeLimit(event: Event): void {
  const value = Number((event.target as HTMLSelectElement).value)
  if (Number.isFinite(value) && value > 0 && value !== props.limit) emit('change-limit', value)
}
</script>

<style scoped>
.ops-pager { display: flex; align-items: center; justify-content: center; gap: var(--space-sm); flex-wrap: wrap; }
.ops-pager-info { font-size: var(--text-xs); color: var(--color-text-secondary); }
.ops-pager-size {
  display: inline-flex; align-items: center; gap: 6px;
  margin-left: var(--space-xs); font-size: var(--text-xs); color: var(--color-text-secondary);
}
.ops-pager-size select {
  min-height: 30px; padding: 0 var(--space-xs);
  border: 1px solid var(--color-border); background: transparent; color: var(--color-text);
  border-radius: var(--radius-sm); font-size: var(--text-sm); cursor: pointer;
}
.ops-pager-size select:focus-visible { outline: none; border-color: var(--color-accent); }
</style>
