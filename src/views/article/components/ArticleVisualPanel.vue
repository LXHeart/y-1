<script setup lang="ts">
import { computed } from 'vue'
import type { VisualPlanItem } from '../../../types/creation-studio'
import type { CreationResultRef } from '../../../types/creation'
import VisualPlanEditor from './VisualPlanEditor.vue'
import VisualProductionPanel from './VisualProductionPanel.vue'
import type { useVisualPlan, PreparePlanInput } from '../composables/useVisualPlan'
import type { useVisualJob } from '../composables/useVisualJob'

/**
 * 任务书 #101 C101-15（§8.1 文章配图区）：段落与图片计划联合视图。
 *
 * 封面与正文插图角色分列；每张插图显示 purpose 与绑定段落（afterBlockId → 源块预览）；
 * 正文改动令计划 stale——显示重核提示，不隐式重生成。候选用同一 VisualProductionPanel；
 * 已采用插图按段落引用回显（§6.5 段落绑定保留）。
 */
const props = defineProps<{
  plan: ReturnType<typeof useVisualPlan>
  job: ReturnType<typeof useVisualJob>
  /** 服务端已采用引用（含 placement.afterBlockId 的插图）。 */
  adoptedRefs?: CreationResultRef[]
  disabled?: boolean
  adopting?: boolean
  adoptError?: string
}>()

const emit = defineEmits<{
  (e: 'prepare-plan', input?: PreparePlanInput, fresh?: boolean): void
  (e: 'candidate-selected', selection: { itemId: string; artifactId: string }): void
  (e: 'adopt-requested', selection: { itemId: string; artifactId: string }): void
  (e: 'zoom', url: string): void
}>()

const items = computed<VisualPlanItem[]>(() => props.plan.document.value?.items ?? [])
const coverItem = computed(() => items.value.find((item) => item.role === 'cover') ?? null)
const illustrations = computed(() => items.value.filter((item) => item.role === 'illustration'))
const blocks = props.plan.sourceBlocks

/** 段落变更/未保存 → stale：位置必须重新核对（§6.5）。 */
const stale = computed(() => props.plan.current.value?.stale ?? false)

/** 已采用媒体（按 cardId）：面板「已采用」与段落回显共用。 */
const adoptedByCardId = computed(() => {
  const map = new Map<string, CreationResultRef>()
  for (const ref of props.adoptedRefs ?? []) {
    if (ref.cardId) map.set(ref.cardId, ref)
  }
  return map
})

function blockPreview(afterBlockId: string | null): string {
  if (!afterBlockId) return ''
  return blocks.value[afterBlockId]?.text.slice(0, 40) ?? '（段落已被删除，需重新核对位置）'
}
</script>

<template>
  <section class="gl-zone article-visual-panel studio-panel" data-test="article-visual-panel" aria-label="文章配图">
    <div class="panel-head">
      <h3>文章配图</h3>
      <span v-if="stale" class="badge warn" data-test="article-visual-stale">正文已变化——段落位置需重新核对</span>
    </div>

    <template v-if="!plan.current.value">
      <p class="hint">按标题与段落推荐封面与插图：每张插图注明用途并定位到真实段落（放在该段之后）。</p>
      <button
        type="button"
        class="primary gl-btn-primary"
        data-test="article-visual-launch"
        :disabled="plan.preparing.value || disabled"
        @click="emit('prepare-plan')"
      >{{ plan.preparing.value ? '正在策划…' : '发起配图策划' }}</button>
      <p v-if="plan.error.value" class="error" data-test="article-visual-error" role="alert">{{ plan.error.value }}</p>
    </template>

    <template v-else>
      <!-- 封面（恰好一张，与正文插图分列） -->
      <div v-if="coverItem" class="cover-row" data-test="article-visual-cover">
        <span class="badge">封面</span>
        <span class="item-title">{{ coverItem.title }}</span>
        <span v-if="adoptedByCardId.has(coverItem.cardId)" class="badge ok" data-test="article-visual-cover-adopted">已采用</span>
      </div>

      <!-- 计划编辑（保存/确认/风格在编辑器内） -->
      <VisualPlanEditor :plan="plan" :disabled="disabled"
        @prepare-requested="input => emit('prepare-plan', input, true)" />

      <!-- 插图 × 段落联合视图：用途 + 绑定段落预览 -->
      <div v-if="illustrations.length" class="illustration-list" data-test="article-visual-illustrations">
        <div
          v-for="item in illustrations"
          :key="item.itemId"
          class="illustration-row"
          :data-test="`article-visual-item-${item.position}`"
        >
          <span class="badge">插图</span>
          <div class="illustration-main">
            <span class="item-title">{{ item.title }}</span>
            <span class="purpose" data-test="article-visual-purpose">{{ item.purpose }}</span>
            <span class="bound-block" data-test="article-visual-placement">
              位置：{{ item.placement ? blockPreview(item.placement.afterBlockId) : '（未绑定段落）' }}
            </span>
          </div>
          <span v-if="adoptedByCardId.has(item.cardId)" class="badge ok">已采用</span>
        </div>
      </div>

      <!-- 候选制作：与图卡同一面板（进度/费用/重做/采用） -->
      <VisualProductionPanel
        :plan="plan"
        :job="job"
        :disabled="disabled"
        :adopting="adopting"
        :adopted-media-ids="(adoptedRefs ?? []).map((ref) => ref.id)"
        :adopt-error="adoptError"
        @candidate-selected="(selection) => emit('candidate-selected', selection)"
        @adopt-requested="(selection) => emit('adopt-requested', selection)"
        @zoom="(url) => emit('zoom', url)"
      />
    </template>
  </section>
</template>

<style scoped>
.article-visual-panel { display: grid; gap: var(--space-md); }
.panel-head { display: flex; justify-content: space-between; align-items: center; gap: var(--space-sm); }
.panel-head h3 { margin: 0; }
.badge.warn { border-color: var(--color-warning); color: var(--color-text-secondary); }
.badge.ok { background: var(--surface-success); color: var(--color-success); }
.hint { margin: 0; color: var(--color-text-muted); font-size: var(--text-base); }
.cover-row { display: flex; align-items: center; gap: var(--space-sm); padding: var(--space-xs) var(--space-sm); border: var(--border-width) solid var(--color-border); border-radius: var(--radius-md); }
.illustration-list { display: grid; gap: var(--space-xs); }
.illustration-row { display: flex; align-items: flex-start; gap: var(--space-sm); padding: var(--space-xs) var(--space-sm); border: var(--border-width) solid var(--color-border); border-radius: var(--radius-md); }
.illustration-main { flex: 1; min-width: 0; display: grid; gap: var(--space-xxs); }
.item-title { font-size: var(--text-base); font-weight: var(--weight-heading); }
.purpose { color: var(--color-text-secondary); font-size: var(--text-base); }
.bound-block { color: var(--color-text-muted); font-size: var(--text-base); }
.error { color: var(--color-danger); }
</style>
