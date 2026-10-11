<template>
  <section class="xhs-image-order" aria-label="配图顺序">
    <div class="xhs-order-head">
      <h3>配图顺序</h3>
      <p class="xhs-order-hint">上移/下移调整发布顺序；顺序随草稿保存，导出交付包按此排列。</p>
    </div>

    <p v-if="cards.length === 0" class="xhs-order-empty" data-test="xhs-order-empty">
      尚无图卡计划；可在创作步按正文拆卡生成后再来排序。
    </p>

    <ul v-else class="xhs-order-list">
      <li
        v-for="(card, index) in cards"
        :key="card.cardId ?? index"
        class="xhs-order-row"
        :data-test="`xhs-order-row-${index}`"
      >
        <span class="xhs-order-pos" aria-hidden="true">{{ card.position ?? index + 1 }}</span>
        <span class="badge badge-neutral">{{ roleLabel(card.role) }}</span>
        <button
          type="button"
          class="xhs-order-thumb"
          :disabled="!thumbUrl(card)"
          :aria-label="thumbUrl(card) ? `放大查看第 ${index + 1} 张图卡` : `第 ${index + 1} 张图卡未生成`"
          :data-test="`xhs-order-thumb-${index}`"
          @click="onOpen(card)"
        >
          <img v-if="thumbUrl(card)" :src="thumbUrl(card)" :alt="card.title || `第 ${index + 1} 张图卡`" loading="lazy">
          <span v-else class="xhs-order-placeholder">未生成</span>
        </button>
        <span class="xhs-order-title">{{ card.title || '（未命名卡片）' }}</span>
        <div class="xhs-order-actions">
          <button
            type="button"
            class="gl-btn-secondary"
            :disabled="disabled || index === 0"
            :aria-label="`上移第 ${index + 1} 张图卡`"
            :data-test="`xhs-order-up-${index}`"
            @click="emit('move', index, -1)"
          >↑ 上移</button>
          <button
            type="button"
            class="gl-btn-secondary"
            :disabled="disabled || index === cards.length - 1"
            :aria-label="`下移第 ${index + 1} 张图卡`"
            :data-test="`xhs-order-down-${index}`"
            @click="emit('move', index, 1)"
          >↓ 下移</button>
        </div>
      </li>
    </ul>
  </section>
</template>

<script setup lang="ts">
/**
 * 配图顺序列表（方案 §3 pub-image-order / §4.4）：真实前端状态展示——序号取卡片
 * position、角色取真实 role、缩略取 results 按 cardId 命中的成功卡 url（未生成显示
 * 文字占位，不放假图）；上移/下移 emit 交由 useXhsImageOrder 重排。
 * 「+ 添加图片」与本地上传 defer（无上传端点核实，不造假入口）。
 */
import type { GeneratedCard, PlannedCard } from '../../../composables/useCardSeries'

const props = defineProps<{
  cards: PlannedCard[]
  results: GeneratedCard[]
  disabled?: boolean
}>()

const emit = defineEmits<{
  move: [index: number, direction: -1 | 1]
  open: [url: string]
}>()

function thumbUrl(card: PlannedCard): string {
  if (!card.cardId) return ''
  const matched = props.results.find((result) => result.cardId === card.cardId)
  return matched?.ok ? matched.url ?? '' : ''
}

function onOpen(card: PlannedCard): void {
  const url = thumbUrl(card)
  if (url) emit('open', url)
}

function roleLabel(role: PlannedCard['role']): string {
  if (role === 'cover') return '封面'
  if (role === 'content') return '内容'
  if (role === 'summary') return '总结'
  return '—'
}
</script>

<style scoped>
.xhs-image-order {
  display: grid;
  gap: var(--space-xs);
}

.xhs-order-head h3 {
  margin: 0;
  font-family: var(--font-display);
  font-size: var(--type-card-title);
  font-weight: var(--weight-heading);
  line-height: var(--leading-card-title);
}

.xhs-order-hint {
  margin: 0;
  color: var(--color-text-muted);
  font-size: var(--type-caption);
  line-height: var(--leading-caption);
}

.xhs-order-empty {
  margin: 0;
  padding: var(--space-md);
  border: var(--border-width) dashed var(--color-border);
  border-radius: var(--radius-md);
  color: var(--color-text-muted);
  font-size: var(--type-body-sm);
  text-align: center;
}

.xhs-order-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: var(--space-xs);
}

.xhs-order-row {
  display: grid;
  grid-template-columns: auto auto minmax(72px, 96px) minmax(0, 1fr) auto;
  align-items: center;
  gap: var(--space-sm);
  padding: var(--space-xs) var(--space-sm);
  border: var(--border-width) solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface);
}

.xhs-order-pos {
  display: grid;
  place-items: center;
  min-width: 24px;
  height: 24px;
  border-radius: var(--radius-pill);
  border: var(--border-width) solid var(--color-border);
  background: var(--surface-card);
  color: var(--color-text-secondary);
  font-size: var(--type-caption);
  font-weight: var(--weight-heading);
}

.xhs-order-thumb {
  padding: 0;
  border: var(--border-width) solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--surface-muted);
  overflow: hidden;
  aspect-ratio: 3 / 4;
  cursor: zoom-in;
}

.xhs-order-thumb:disabled {
  cursor: default;
}

.xhs-order-thumb img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.xhs-order-placeholder {
  display: grid;
  place-items: center;
  width: 100%;
  height: 100%;
  color: var(--color-text-muted);
  font-size: var(--type-caption);
}

.xhs-order-title {
  color: var(--color-text);
  font-size: var(--type-body-sm);
  overflow-wrap: anywhere;
}

.xhs-order-actions {
  display: flex;
  gap: var(--space-xs);
}

@media (max-width: 767px) {
  .xhs-order-row {
    grid-template-columns: auto auto minmax(64px, 1fr);
    grid-template-areas:
      'pos role title'
      'thumb thumb actions';
  }
  .xhs-order-pos { grid-area: pos; }
  .xhs-order-row > .badge { grid-area: role; }
  .xhs-order-thumb { grid-area: thumb; }
  .xhs-order-title { grid-area: title; }
  .xhs-order-actions { grid-area: actions; justify-content: flex-end; }
}
</style>
