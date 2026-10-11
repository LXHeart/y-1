<template>
  <section class="xhs-series" aria-label="配图（信息卡片系列）">
    <header class="xhs-series-head">
      <h3>配图（信息卡片系列）</h3>
      <p class="xhs-series-spec">
        生成尺寸默认 1024×1792 竖版（可在面板内调整模板与尺寸）；发布建议：首图 3:4 占屏最佳，9:16 与 1:1 亦可（平台规范）。
      </p>
    </header>

    <CardSeriesPanel
      v-if="contentReady"
      platform="xiaohongshu"
      :content="content"
      :series="series"
      :disabled="disabled"
      @open-lightbox="onZoom"
    />
    <p v-else class="xhs-series-empty">正文 ≥50 字后可基于当前内容拆卡生成配图。</p>
  </section>
</template>

<script setup lang="ts">
/**
 * 配图生成区块（方案 §4.3 工程师B / §3 gen-images reuse）：CardSeriesPanel 复用包装。
 * - 只透传 platform/content/series/disabled，不传 plan/job——走 legacy v1 真实链
 *   （plan SSE → 逐卡 generate → persist 入素材库）；张数/风格/布局/配色/尺寸由面板内
 *   真实参数控件承担。不改 CardSeriesPanel 源文件。
 * - 【F4】生成尺寸与发布建议分开陈述：CARD_SIZE_BY_ASPECT 仅 9:16/1:1/16:9 三档，
 *   小红书 3:4 不在生图白名单、默认回落 1024×1792 竖版（useCardSeries.ts:63-72）；
 *   「首图 3:4 占屏最佳」是契约发布建议（platform-format-rules.json note），不作生成规格承诺。
 * - 挂载阈值与旧视图一致（正文 ≥50 字，ArticleCreationView.vue:269）；【F3】生成步不因
 *   引擎 stage='check' 消失（steps.go('generate') 已协同置回 'content'）。
 * - 数据经 useXhsStudioContext() 只读自取（F7）；props 仅作最小测试覆写口。
 */
import { computed } from 'vue'
import CardSeriesPanel from '../../article/components/CardSeriesPanel.vue'
import { useXhsStudioContext } from '../composables/useXhsStudioContext'
import type { useCardSeries } from '../../../composables/useCardSeries'

/** 拆卡正文阈值（与旧视图挂载条件同款）。 */
const CONTENT_MIN_CHARS = 50

const props = defineProps<{
  /** 测试覆写口：缺省用 context.engine.content。 */
  content?: string
  /** 测试覆写口：缺省用 context.cards。 */
  series?: ReturnType<typeof useCardSeries>
}>()

const emit = defineEmits<{
  /** 成功卡放大——视图层 ArticleLightbox 承载。 */
  'open-lightbox': [url: string]
}>()

const context = useXhsStudioContext()
const content = computed(() => props.content ?? context.engine.content.value)
const series = computed(() => props.series ?? context.cards)
// disabled 不设 prop 覆写口：boolean prop 未传会被 Vue cast 为 false（组件内无法与显式
// false 区分，「未传=用 context」语义不可实现），一律从 context 自取。
const disabled = computed(() => context.autosave.readonly.value)
/** 正文不足阈值不挂面板（真实空态说明，不渲染假入口）。 */
const contentReady = computed(() => content.value.trim().length >= CONTENT_MIN_CHARS)

function onZoom(url: string): void {
  emit('open-lightbox', url)
}
</script>

<style scoped>
.xhs-series {
  display: grid;
  gap: var(--space-sm);
}

.xhs-series-head {
  display: grid;
  gap: var(--space-xs);
}

.xhs-series-head h3 {
  margin: 0;
  font-family: var(--font-display);
  font-size: var(--type-card-title);
  font-weight: var(--weight-heading);
  line-height: var(--leading-card-title);
}

.xhs-series-spec {
  margin: 0;
  color: var(--color-text-secondary);
  font-size: var(--type-caption);
  line-height: var(--leading-caption);
}

.xhs-series-empty {
  margin: 0;
  padding: var(--space-md);
  border: var(--border-width) dashed var(--color-border);
  border-radius: var(--radius-md);
  color: var(--color-text-muted);
  font-size: var(--type-body-sm);
}
</style>
