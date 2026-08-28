<template>
  <div class="ops-pagination">
    <button type="button" class="ops-page-btn" :disabled="atFirstPage" @click="prev">上一页</button>
    <span class="ops-page-info gl-num">第 {{ currentPage }} / {{ totalPages }} 页 · 共 {{ total }} 条</span>
    <button type="button" class="ops-page-btn" :disabled="atLastPage" @click="next">下一页</button>
    <label class="ops-page-size">每页
      <select class="ops-page-size-select" :value="limit" @change="changeLimit">
        <!-- limit 不在标准档位时兜底显示当前值，避免下拉空白 -->
        <option v-if="!PAGE_SIZE_OPTIONS.includes(limit)" :value="limit">{{ limit }}</option>
        <option v-for="size in PAGE_SIZE_OPTIONS" :key="size" :value="size">{{ size }}</option>
      </select>
      条
    </label>
  </div>
</template>

<script setup lang="ts">
/**
 * 治理台分页器（任务 #3 分页基础设施）。
 *
 * 契约：父组件持 `offset`/`limit` 真源，本组件只发 `change(offset)` 与
 * `change-limit(limit)`，不自行翻页。文案对齐用户端先例 `MySessionsCard` 的
 * sess-pager（上一页 | 第 x / y 页 · 共 N 条 | 下一页），页大小默认 10、
 * 可选 10/20/50/100（档位与后端 PageEnvelope 钳制上限 200 之内）。
 *
 * 越界收敛：删行致页码漂移（当前 offset 超出末页）时主动发出 `change` 收敛到末页首行，
 * 避免面板停在空页。样式只用 `src/style.css` 既有 token，明暗主题自动继承。
 */
import { computed, watchEffect } from 'vue'

const props = defineProps<{
  total: number
  limit: number
  offset: number
}>()

const emit = defineEmits<{
  change: [offset: number]
  'change-limit': [limit: number]
}>()

const PAGE_SIZE_OPTIONS: readonly number[] = [10, 20, 50, 100]

const totalPages = computed(() => Math.max(1, Math.ceil(props.total / props.limit)))
/** 展示页码夹在 [1, totalPages]：越界时显示末页而非 0/负页，收敛由下方 watchEffect 负责。 */
const currentPage = computed(() =>
  Math.min(Math.max(1, Math.floor(props.offset / props.limit) + 1), totalPages.value))
const atFirstPage = computed(() => currentPage.value <= 1)
const atLastPage = computed(() => currentPage.value >= totalPages.value)

watchEffect(() => {
  if (props.total > 0 && props.offset >= totalPages.value * props.limit) {
    emit('change', (totalPages.value - 1) * props.limit)
  }
})

function prev(): void {
  if (!atFirstPage.value) emit('change', (currentPage.value - 2) * props.limit)
}

function next(): void {
  if (!atLastPage.value) emit('change', currentPage.value * props.limit)
}

/** 切页大小：真源在父级（归零 offset 由父级处理器完成），重复选当前档不发事件。 */
function changeLimit(event: Event): void {
  const value = Number((event.target as HTMLSelectElement).value)
  if (Number.isFinite(value) && value > 0 && value !== props.limit) emit('change-limit', value)
}
</script>

<style scoped>
/* 按钮自包含（不依赖 `.gl-field button` 作用域——部分面板根节点未挂 .gl-field），
   形状取自全局按钮规范；颜色/圆角/间距全走 token，明暗主题随 :root/[data-theme] 继承。 */
.ops-pagination { display: flex; align-items: center; justify-content: center; gap: var(--space-sm); flex-wrap: wrap; }
.ops-page-btn {
  min-height: 34px; padding: 0 var(--space-sm);
  border: 1px solid var(--color-border); background: transparent; color: var(--color-text);
  border-radius: var(--radius-sm); font-size: var(--text-sm); cursor: pointer;
  transition: border-color var(--duration-fast) var(--ease-out),
    background var(--duration-fast) var(--ease-out);
}
.ops-page-btn:hover:not(:disabled) { border-color: var(--color-border-hover); background: var(--color-surface-hover); }
.ops-page-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.ops-page-info { font-size: var(--text-xs); color: var(--color-text-secondary); }

.ops-page-size {
  display: inline-flex; align-items: center; gap: 6px;
  margin-left: var(--space-xs);
  font-size: var(--text-xs); color: var(--color-text-secondary);
}
.ops-page-size-select {
  min-height: 34px; padding: 0 var(--space-xs);
  border: 1px solid var(--color-border); background: transparent; color: var(--color-text);
  border-radius: var(--radius-sm); font-size: var(--text-sm); cursor: pointer;
}
.ops-page-size-select:focus-visible { outline: none; border-color: var(--color-accent); }
</style>
