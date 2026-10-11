<template>
  <section class="xhs-title-candidates" aria-label="标题候选">
    <header class="xhs-block-head">
      <div>
        <h2>标题候选</h2>
        <p class="gl-hint">点选一个标题后自动生成大纲与正文（真实生成链，逐字流式）。</p>
      </div>
      <button
        type="button"
        class="xhs-title-refresh"
        :disabled="loading"
        @click="onRefresh"
      >{{ loading ? '生成中…' : '⟳ 换一批' }}</button>
    </header>

    <div v-if="loading" class="xhs-title-skeleton" aria-hidden="true">
      <div v-for="index in 3" :key="index" class="xhs-title-skeleton-item"></div>
    </div>

    <p v-else-if="titles.length === 0" class="gl-empty">暂无候选标题，点「⟳ 换一批」重新生成。</p>

    <ul v-else class="xhs-title-list" role="radiogroup" aria-label="候选标题">
      <li v-for="(option, index) in titles" :key="`${option.title}-${index}`">
        <button
          type="button"
          class="xhs-title-card"
          role="radio"
          :aria-checked="selectedTitle === option.title"
          :class="{ 'xhs-title-card-active': selectedTitle === option.title }"
          :disabled="flowing"
          @click="onSelect(option.title)"
        >
          <span class="xhs-title-text">{{ option.title }}</span>
          <span v-if="option.hook" class="xhs-title-hook">{{ option.hook }}</span>
          <span
            class="xhs-title-count"
            :class="{ 'xhs-title-count-over': option.title.length > maxTitleChars }"
          >{{ option.title.length }}/{{ maxTitleChars }} 字</span>
        </button>
      </li>
    </ul>

    <p v-if="safetyNote" class="xhs-title-safety" role="status">{{ safetyNote }}</p>
  </section>
</template>

<script setup lang="ts">
/**
 * 标题候选（方案 §4.2 工程师 A，featureId=gen-titles reuse）：/api/article-generation/titles
 * 真实多候选 radio 单选卡（title+hook+字数）；「换一批」= fetchTitles 重新拉取真实候选。
 *
 * - 选用写 engine.selectTitle；首次选定（大纲/正文均空）自动串联 streamOutline→streamContent
 *   （方案 §2.3），大纲流式文本由正文编辑区折叠面板展示。
 * - 合规徽标由 safetyReport 真实派生：标题期报告仅在正文为空时展示（正文生成后报告语境
 *   切换为正文，交合规区/校对步），不预置通过。
 * - 爆款分/类型标签无评分模型，不渲染（defer）。
 */
import { computed } from 'vue'
import { useXhsStudioContext } from '../composables/useXhsStudioContext'
import type { ArticleTitleOption } from '../../../types/article-creation'

/** props 仅作最小测试注入口（F7）：缺省全部 inject 自取真实引擎值。 */
const props = withDefaults(defineProps<{
  titles?: ArticleTitleOption[]
  selectedTitle?: string
  loading?: boolean
}>(), {
  titles: undefined,
  selectedTitle: undefined,
  // boolean prop 未传缺省 false（Vue 3 cast），显式 undefined 才能让 ?? 回落引擎值。
  loading: undefined,
})

const emit = defineEmits<{
  select: [title: string]
  refresh: []
}>()

const ctx = useXhsStudioContext()

const titles = computed(() => props.titles ?? ctx.engine.titles.value)
const selectedTitle = computed(() => props.selectedTitle ?? ctx.engine.selectedTitle.value)
const loading = computed(() => props.loading ?? ctx.engine.titlesLoading.value)
/** 大纲/正文流进行中禁用换选，避免中途换标题把流打断。 */
const flowing = computed(() => ctx.engine.outlineLoading.value || ctx.engine.contentLoading.value)

const maxTitleChars = computed(() => ctx.format.formatRule.value?.maxTitleChars ?? 20)

/** 标题期安全状态（正文出现后报告属于正文，不再挂标题区）。 */
const safetyNote = computed(() => {
  if (ctx.engine.content.value.trim()) return ''
  const report = ctx.engine.safetyReport.value
  if (!report) return ''
  return report.findings.length === 0
    ? '标题合规检测：通过'
    : `标题合规检测：${report.findings.length} 项提醒（详见校对步）`
})

async function onSelect(title: string): Promise<void> {
  if (selectedTitle.value === title) return
  ctx.engine.selectTitle(title)
  emit('select', title)
  // 选定标题后自动串联大纲→正文（方案 §2.3）；已有正文时由正文区「重新生成」显式重流。
  // 串联条件只看正文为空（F-05）：上次流失败/取消后 outline 残留、正文为空时换选标题
  // 仍重流（streamOutline 覆盖旧大纲），不留只能退回选题步的死路。
  if (!ctx.engine.content.value.trim()
    && !ctx.engine.outlineLoading.value && !ctx.engine.contentLoading.value) {
    await ctx.engine.streamOutline()
    if (!ctx.engine.error.value) await ctx.engine.streamContent()
  }
}

function onRefresh(): void {
  emit('refresh')
  void ctx.engine.fetchTitles()
}
</script>

<style scoped>
.xhs-title-candidates {
  display: grid;
  gap: var(--space-sm);
}

.xhs-block-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-sm);
}

.xhs-block-head h2 {
  margin: 0;
  font-family: var(--font-display);
  font-size: var(--type-card-title);
  font-weight: var(--weight-heading);
  line-height: var(--leading-card-title);
}

.xhs-block-head .gl-hint {
  margin: var(--space-xxs) 0 0;
}

.xhs-title-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: var(--space-xs);
}

.xhs-title-card {
  width: 100%;
  display: grid;
  gap: var(--space-xxs);
  padding: var(--space-sm) var(--space-md);
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border);
  background: var(--color-surface);
  text-align: left;
  cursor: pointer;
}

.xhs-title-card-active {
  border-color: color-mix(in srgb, var(--color-accent) 45%, transparent);
  background: var(--color-surface-highlight);
  box-shadow: var(--focus-ring);
}

.xhs-title-text {
  color: var(--color-text);
  font-size: var(--type-body);
  font-weight: var(--weight-heading);
  line-height: 1.45;
}

.xhs-title-hook {
  color: var(--color-text-muted);
  font-size: var(--type-caption);
  line-height: var(--leading-caption);
}

.xhs-title-count {
  color: var(--color-text-muted);
  font-size: var(--type-caption);
  font-variant-numeric: tabular-nums;
}

.xhs-title-count-over {
  color: var(--color-danger);
  font-weight: var(--weight-heading);
}

.xhs-title-safety {
  margin: 0;
  color: var(--color-text-secondary);
  font-size: var(--type-caption);
}

.xhs-title-skeleton {
  display: grid;
  gap: var(--space-xs);
}

.xhs-title-skeleton-item {
  height: 64px;
  border-radius: var(--radius-md);
  background: var(--surface-muted);
  animation: xhs-title-pulse 1.2s ease-in-out infinite;
}

@keyframes xhs-title-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.55; }
}
</style>
