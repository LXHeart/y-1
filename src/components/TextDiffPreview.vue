<script setup lang="ts">
import { computed } from 'vue'

/**
 * 修复 diff 预览（任务书 #63 卡3 / P3 拍板：先预览 diff 再应用）。段级 LCS（按空行切段），
 * 纯前端无新依赖；应用前原文不动——「应用修复」只是 emit，回写由父级完成。
 */

const props = defineProps<{
  original: string
  revised: string
}>()

const emit = defineEmits<{ apply: []; discard: [] }>()

type DiffEntry = { type: 'unchanged' | 'added' | 'removed'; text: string }

/** 按空行切段（与创作流正文的段落语义一致）；空白段过滤。 */
function splitParagraphs(text: string): string[] {
  return text
    .split(/\n\n+/)
    .map((paragraph) => paragraph.trim())
    .filter((paragraph) => paragraph.length > 0)
}

/** 经典 LCS：先填后缀 DP 表，再线性回溯产出按位置有序的 diff 序列。 */
function diffParagraphs(a: string[], b: string[]): DiffEntry[] {
  const n = a.length
  const m = b.length
  const table: number[][] = Array.from({ length: n + 1 }, () => new Array<number>(m + 1).fill(0))
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      table[i][j] = a[i] === b[j] ? table[i + 1][j + 1] + 1 : Math.max(table[i + 1][j], table[i][j + 1])
    }
  }
  const entries: DiffEntry[] = []
  let i = 0
  let j = 0
  while (i < n && j < m) {
    if (a[i] === b[j]) {
      entries.push({ type: 'unchanged', text: a[i] })
      i += 1
      j += 1
    } else if (table[i + 1][j] >= table[i][j + 1]) {
      entries.push({ type: 'removed', text: a[i] })
      i += 1
    } else {
      entries.push({ type: 'added', text: b[j] })
      j += 1
    }
  }
  while (i < n) {
    entries.push({ type: 'removed', text: a[i] })
    i += 1
  }
  while (j < m) {
    entries.push({ type: 'added', text: b[j] })
    j += 1
  }
  return entries
}

const entries = computed(() => diffParagraphs(splitParagraphs(props.original), splitParagraphs(props.revised)))

const changedCount = computed(() =>
  entries.value.filter((entry) => entry.type !== 'unchanged').length)
</script>

<template>
  <section class="tdp" data-test="text-diff-preview">
    <p class="tdp-legend">
      <span class="tdp-key tdp-key-added">绿</span> = 修复后新增段落，
      <span class="tdp-key tdp-key-removed">红</span> = 原删除段落，未标注 = 保持不变
    </p>
    <ol class="tdp-list" data-test="tdp-entries">
      <li
        v-for="(entry, i) in entries"
        :key="i"
        class="tdp-item"
        :class="{ 'tdp-item-added': entry.type === 'added', 'tdp-item-removed': entry.type === 'removed' }"
        :data-test="`tdp-${entry.type}`"
      >{{ entry.text }}</li>
    </ol>
    <footer class="tdp-foot">
      <span class="tdp-count">共 {{ changedCount }} 个段落有变化</span>
      <div class="tdp-actions">
        <button type="button" class="tdp-btn" data-test="tdp-discard" @click="emit('discard')">放弃</button>
        <button type="button" class="tdp-btn tdp-btn-apply" data-test="tdp-apply" @click="emit('apply')">应用修复</button>
      </div>
    </footer>
  </section>
</template>

<style scoped>
.tdp { display: flex; flex-direction: column; gap: 10px; }
.tdp-legend { margin: 0; font-size: 12px; color: var(--color-text-secondary); }
.tdp-key { display: inline-block; min-width: 14px; text-align: center; border-radius: var(--radius-xs); font-size: 11px; }
.tdp-key-added { background: color-mix(in srgb, var(--color-success) 18%, transparent); color: var(--color-success); }
.tdp-key-removed { background: color-mix(in srgb, var(--color-danger) 18%, transparent); color: var(--color-danger); }
.tdp-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 6px; max-height: 46vh; overflow-y: auto; }
.tdp-item { padding: 8px 12px; border-radius: var(--radius-sm); border: 1px solid var(--color-border); background: var(--color-surface); font-size: 13px; line-height: 1.7; white-space: pre-wrap; word-break: break-word; color: var(--color-text); }
.tdp-item-added { background: color-mix(in srgb, var(--color-success) 14%, transparent); border-color: color-mix(in srgb, var(--color-success) 30%, transparent); }
.tdp-item-removed { background: color-mix(in srgb, var(--color-danger) 14%, transparent); border-color: color-mix(in srgb, var(--color-danger) 30%, transparent); text-decoration: line-through; text-decoration-color: color-mix(in srgb, var(--color-danger) 55%, transparent); }
.tdp-foot { display: flex; align-items: center; justify-content: space-between; gap: 8px; flex-wrap: wrap; }
.tdp-count { font-size: 12px; color: var(--color-text-muted); }
.tdp-actions { display: flex; gap: 8px; }
.tdp-btn { padding: 6px 16px; font-size: 12px; font-weight: 600; border: 1px solid var(--color-border); border-radius: var(--radius-pill); background: transparent; color: var(--color-text-secondary); cursor: pointer; }
.tdp-btn:hover { border-color: var(--color-border-hover); background: var(--color-surface-hover); }
.tdp-btn-apply { border-color: transparent; background: var(--gradient-accent); color: var(--color-on-accent); }
.tdp-btn-apply:hover { background: var(--gradient-accent); filter: brightness(1.05); }
</style>
