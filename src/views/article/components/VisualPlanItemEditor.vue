<script setup lang="ts">
import { computed, ref } from 'vue'
import type { SourceBlock, VisualPlanItem, TargetAspect } from '../../../types/creation-studio'
import { CARD_SERIES_LAYOUTS } from '../../../constants/card-series-templates'

/**
 * 任务书 #101 C101-06：视觉计划单条编辑器。
 *
 * 来源片段可定位（点击展开原文依据全文）、关键文字逐字核对提示；上移/下移按钮保证键盘
 * 可完成排序（§8.2：拖拽只是增强）。身份（itemId/cardId）不随顺序变化。
 */
const props = defineProps<{
  item: VisualPlanItem
  index: number
  total: number
  sourceBlocks: Record<string, SourceBlock>
  allowedBlockIds?: string[]
  disabled?: boolean
}>()

const emit = defineEmits<{
  (e: 'move', index: number, direction: -1 | 1): void
  (e: 'remove', index: number): void
  (e: 'promote', index: number): void
  (e: 'update', index: number, patch: Partial<VisualPlanItem>): void
}>()

/** 来源块内联展开（§8.1 每页来源可定位——点击查看原文依据全文）。 */
const expandedBlockId = ref<string | null>(null)
const aspects: TargetAspect[] = ['3:4', '9:16', '1:1', '16:9', '2.35:1']
const selectableBlocks = computed(() => (props.allowedBlockIds ?? Object.keys(props.sourceBlocks))
  .map(id => props.sourceBlocks[id]).filter(Boolean))
const bulletError = computed(() => props.item.bullets.length > 5 || props.item.bullets.some(text => [...text].length > 80))
const textErrors = computed(() => ([['title', '标题', 60], ['purpose', '本页目的', 200],
  ['illustration', '插图说明', 1000], ['caption', '配文', 500]] as const)
  .filter(([field, , limit]) => [...props.item[field]].length > limit).map(([, label, limit]) => label + '超过 ' + limit + ' 字'))
function onBullets(event: Event): void {
  onField('bullets', (event.target as HTMLTextAreaElement).value.split('\n').filter(text => text.trim()))
}
function onAspect(event: Event): void { onField('targetAspect', (event.target as HTMLSelectElement).value as TargetAspect) }
function onPlacement(event: Event): void { onField('placement', { afterBlockId: (event.target as HTMLSelectElement).value }) }
function toggleSource(id: string, event: Event): void {
  const checked = (event.target as HTMLInputElement).checked
  onField('sourceBlockIds', checked ? [...new Set([...props.item.sourceBlockIds, id])] : props.item.sourceBlockIds.filter(value => value !== id))
}

function toggleBlock(id: string): void {
  expandedBlockId.value = expandedBlockId.value === id ? null : id
}

/** 字段编辑统一走 update 事件（父层文档是唯一可变副本——子组件不改 props）。 */
function onField<K extends keyof VisualPlanItem>(field: K, value: VisualPlanItem[K]): void {
  emit('update', props.index, { [field]: value } as Partial<VisualPlanItem>)
}

function onTextInput(field: 'title' | 'purpose' | 'illustration' | 'caption', event: Event): void {
  onField(field, (event.target as HTMLInputElement | HTMLTextAreaElement).value)
}

function onLayoutChange(event: Event): void {
  onField('layoutId', (event.target as HTMLSelectElement).value)
}

function onCriticalInput(event: Event): void {
  onField('criticalText', (event.target as HTMLTextAreaElement).value
    .split('\n').map((line) => line.trim()).filter(Boolean))
}

const roleLabel = computed(() => {
  if (props.item.role === 'cover') return '封面'
  if (props.item.role === 'summary') return '总结'
  if (props.item.role === 'illustration') return '插图'
  return '内容'
})

/** 关联来源块定位摘要（缺失块显示警告，§8.1 每页来源可定位）。 */
const locatedBlocks = computed(() => props.item.sourceBlockIds
  .map((id) => ({ id, block: props.sourceBlocks[id] })))

/** 关键文字必须逐字出现在关联来源块中（§5.4）——前端只提示，服务端最终校验。 */
const criticalIssues = computed(() => {
  return props.item.criticalText.filter(text => text && !props.item.sourceBlockIds.some(id => props.sourceBlocks[id]?.text.includes(text)))
})

function blockKindLabel(kind: SourceBlock['kind']): string {
  const labels: Record<SourceBlock['kind'], string> = {
    heading: '标题', paragraph: '段落', list: '列表', quote: '引用', table: '表格', code: '代码',
  }
  return labels[kind]
}
</script>

<template>
  <article class="plan-item gl-field" :data-test="`plan-item-${index}`">
    <header class="plan-item-head">
      <span class="badge">第 {{ index + 1 }} 页 · {{ roleLabel }}</span>
      <div class="plan-item-actions">
        <button
          type="button"
          class="secondary"
          :data-test="`plan-item-up-${index}`"
          :disabled="disabled || index === 0"
          :aria-label="`上移第 ${index + 1} 页`"
          @click="emit('move', index, -1)"
        >上移</button>
        <button
          type="button"
          class="secondary"
          :data-test="`plan-item-down-${index}`"
          :disabled="disabled || index === total - 1"
          :aria-label="`下移第 ${index + 1} 页`"
          @click="emit('move', index, 1)"
        >下移</button>
        <button
          v-if="item.role !== 'cover'"
          type="button"
          class="secondary"
          :data-test="`plan-item-cover-${index}`"
          :disabled="disabled"
          @click="emit('promote', index)"
        >设为封面</button>
        <button
          type="button"
          class="secondary"
          :data-test="`plan-item-remove-${index}`"
          :disabled="disabled"
          @click="emit('remove', index)"
        >删除</button>
      </div>
    </header>

    <div class="form-field">
      <label :for="`plan-title-${index}`">标题（≤60 字）</label>
      <input
        :id="`plan-title-${index}`"
        :value="item.title"
        :data-test="`plan-title-${index}`"
        maxlength="120"
        :disabled="disabled"
        @input="onTextInput('title', $event)"
      >
    </div>

    <div class="form-field">
      <label :for="`plan-purpose-${index}`">本页目的（≤200 字）</label>
      <input
        :id="`plan-purpose-${index}`"
        :value="item.purpose"
        :data-test="`plan-purpose-${index}`"
        maxlength="400"
        :disabled="disabled"
        @input="onTextInput('purpose', $event)"
      >
    </div>

    <div class="form-field">
      <label :for="`plan-illustration-${index}`">插图说明（≤1,000 字）</label>
      <textarea
        :id="`plan-illustration-${index}`"
        :value="item.illustration"
        :data-test="`plan-illustration-${index}`"
        rows="3"
        maxlength="2000"
        :disabled="disabled"
        @input="onTextInput('illustration', $event)"
      />
    </div>

    <div class="form-field">
      <label :for="`plan-caption-${index}`">配文（不进图，≤500 字）</label>
      <textarea
        :id="`plan-caption-${index}`"
        :value="item.caption"
        :data-test="`plan-caption-${index}`"
        rows="2"
        maxlength="1000"
        :disabled="disabled"
        @input="onTextInput('caption', $event)"
      />
    </div>

    <div class="form-field">
      <label :for="`plan-layout-${index}`">布局</label>
      <select
        :id="`plan-layout-${index}`"
        :value="item.layoutId"
        :data-test="`plan-layout-${index}`"
        :disabled="disabled"
        @change="onLayoutChange"
      >
        <option v-for="layout in CARD_SERIES_LAYOUTS" :key="layout.id" :value="layout.id">
          {{ layout.label }}
        </option>
      </select>
    </div>

    <div class="form-field">
      <label :for="`plan-critical-${index}`">关键文字（每行一条，必须逐字来自原文）</label>
      <textarea
        :id="`plan-critical-${index}`"
        :value="item.criticalText.join('\n')"
        :data-test="`plan-critical-${index}`"
        rows="3"
        :disabled="disabled"
        @input="onCriticalInput"
      />
      <p v-if="criticalIssues.length" class="error" data-test="plan-critical-mismatch" role="alert">
        关键文字未逐字出现在关联来源块：{{ criticalIssues.join('；') }}。请修正文字或补充关联来源。
      </p>
    </div>

    <div class="form-field">
      <label :for="`plan-bullets-${index}`">要点（最多 5 条，每条 80 字）</label>
      <textarea :id="`plan-bullets-${index}`" :data-test="`plan-bullets-${index}`" rows="3"
        :value="item.bullets.join('\n')" :disabled="disabled" :aria-invalid="bulletError" @input="onBullets" />
      <p v-if="bulletError" class="error" role="alert">请保留最多 5 条要点，每条不超过 80 字。</p>
    </div>
    <div class="form-field">
      <label :for="`plan-aspect-${index}`">交付画幅</label>
      <select :id="`plan-aspect-${index}`" :data-test="`plan-aspect-${index}`" :value="item.targetAspect" :disabled="disabled" @change="onAspect">
        <option v-for="aspect in aspects" :key="aspect" :value="aspect">{{ aspect }}</option>
      </select>
    </div>
    <div v-if="item.role === 'illustration'" class="form-field">
      <label :for="`plan-placement-${index}`">放在此段之后</label>
      <select :id="`plan-placement-${index}`" :data-test="`plan-placement-${index}`" :value="item.placement?.afterBlockId" :disabled="disabled" @change="onPlacement">
        <option value="" disabled>选择原文段落</option>
        <option v-for="block in selectableBlocks" :key="block.id" :value="block.id">{{ block.position }} · {{ block.text.slice(0, 40) }}</option>
      </select>
    </div>

    <p v-if="textErrors.length" class="error source-selection" role="alert">{{ textErrors.join('；') }}，请调整后保存。</p>
    <details class="source-selection">
      <summary>调整原文依据（已选 {{ item.sourceBlockIds.length }} 段）</summary>
      <label v-for="block in selectableBlocks" :key="block.id">
        <input type="checkbox" :checked="item.sourceBlockIds.includes(block.id)" :disabled="disabled" @change="toggleSource(block.id, $event)">
        {{ block.position }} · {{ block.text.slice(0, 80) }}
      </label>
    </details>

    <div v-if="locatedBlocks.length" class="source-links" data-test="plan-source-links">
      <span class="source-label">来源：</span>
      <button
        v-for="entry in locatedBlocks"
        :key="entry.id"
        type="button"
        class="source-chip"
        :data-test="`plan-locate-${entry.id}`"
        :aria-expanded="expandedBlockId === entry.id"
        :disabled="disabled || !entry.block"
        @click="toggleBlock(entry.id)"
      >
        {{ entry.block ? `${blockKindLabel(entry.block.kind)}·${entry.block.text.slice(0, 24)}` : `缺失块 ${entry.id.slice(0, 8)}` }}…
      </button>
      <blockquote v-if="expandedBlockId && sourceBlocks[expandedBlockId]" class="source-quote" data-test="plan-source-quote">
        {{ sourceBlocks[expandedBlockId].text }}
      </blockquote>
    </div>
  </article>
</template>

<style scoped>
.plan-item { border: var(--border-width) solid var(--color-border); border-radius: var(--radius-md); padding: var(--space-md); display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--space-sm); background: var(--color-surface); }
.plan-item-head, .source-links, .source-selection { grid-column: 1 / -1; }
.source-selection summary { cursor: pointer; min-height: var(--control-height); color: var(--color-text-secondary); }
.source-selection label { display: flex; align-items: center; gap: var(--space-xs); min-height: var(--touch-target); }
.source-selection { max-height: var(--layout-rail); overflow-y: auto; }
@media (width < 768px) { .plan-item { grid-template-columns: minmax(0, 1fr); } }
.plan-item-head { display: flex; justify-content: space-between; align-items: center; gap: var(--space-xs); flex-wrap: wrap; }
.plan-item-actions { display: flex; flex-wrap: wrap; gap: var(--space-xs); }
.source-links { display: flex; align-items: flex-start; flex-wrap: wrap; gap: var(--space-xs); }
.source-label { color: var(--color-text-muted); font-size: var(--type-body); }
.source-chip { border: var(--border-width) solid var(--color-border); border-radius: var(--radius-pill); padding: var(--space-micro) var(--space-sm); background: var(--color-surface-hover); font-size: var(--type-body); cursor: pointer; }
.source-quote { flex-basis: 100%; margin: var(--space-xxs) 0 0; padding: var(--space-xs) var(--space-sm); border-left: var(--space-xxs) solid var(--color-border-accent); background: var(--color-surface-hover); border-radius: var(--radius-sm); white-space: pre-wrap; font-size: var(--type-body); }
.error { color: var(--color-danger); font-size: var(--type-body); margin: 0; }
</style>
