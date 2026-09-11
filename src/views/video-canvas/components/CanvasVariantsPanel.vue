<script setup lang="ts">
import type { VariantSummary } from '../../../types/video-canvas'

/**
 * 任务书 #100 C100-15：独立方案面板——列表/创建/摘要比较。
 *
 * 与「分组与分支」页签（镜头集合/序列视图）明确分开：独立方案是新草稿/分镜/镜头的
 * 完整副本，展示父版本与来源版本；比较只列明确字段差异，不伪造视觉评分。
 */
const props = defineProps<{
  variants: VariantSummary[]
  currentStoryboardId: string
  loading: boolean
  creating: boolean
  error: string
  hasPendingCreation: boolean
}>()

const emit = defineEmits<{
  (e: 'switch', target: { storyboardId: string; draftId?: string }): void
  (e: 'create'): void
  (e: 'retry-pending'): void
}>()

function lineageLabel(variant: VariantSummary): string {
  return variant.parentStoryboardId == null
    ? '根方案'
    : `派生自 ${(variant.parentStoryboardId.slice(0, 8))}…（来源 v${variant.sourceEditVersion ?? '?'}）`
}
</script>

<template>
  <section class="variants-panel gl-zone" data-test="canvas-variants-panel" aria-label="独立方案">
    <header class="variants-head">
      <h3 class="panel-title">独立方案</h3>
      <button type="button" class="gl-btn-primary" :disabled="creating"
        data-test="canvas-variant-create" @click="emit('create')">
        {{ creating ? '创建中…' : '创建独立方案' }}
      </button>
    </header>
    <p class="field-note">
      独立内容方案 = 新草稿/分镜/镜头的完整副本（可独立制作成片）；「分组与分支」页签只是
      镜头集合/序列视图，不是方案。
    </p>
    <p v-if="hasPendingCreation" class="field-note" role="status" data-test="canvas-variant-pending">
      上次创建结果未确认——用原操作键重试，不会生成第二份。
      <button type="button" class="gl-btn-ghost" data-test="canvas-variant-retry"
        @click="emit('retry-pending')">原键重试</button>
    </p>
    <p v-if="loading" class="panel-empty">方案列表读取中…</p>
    <p v-else-if="!variants.length" class="panel-empty" data-test="canvas-variants-empty">
      尚无方案——从当前版本派生一个独立方案
    </p>
    <p v-if="error" class="field-note runbar-error" role="alert" data-test="canvas-variants-error">
      {{ error }}
    </p>
    <ul v-else class="variant-list">
      <li v-for="variant in variants" :key="variant.storyboardId" class="variant-item"
        :data-test="`canvas-variant-${variant.storyboardId}`">
        <div class="variant-meta">
          <span class="variant-title">{{ variant.title }}</span>
          <span class="field-note">{{ lineageLabel(variant) }}</span>
          <span v-if="variant.storyboardId === currentStoryboardId" class="badge badge-accent"
            :data-test="`canvas-variant-current-${variant.storyboardId}`">当前</span>
        </div>
        <button v-if="variant.storyboardId !== currentStoryboardId" type="button"
          class="gl-btn-ghost" :data-test="`canvas-variant-switch-${variant.storyboardId}`"
          @click="emit('switch', { storyboardId: variant.storyboardId })">切换到此方案</button>
      </li>
    </ul>
  </section>
</template>
