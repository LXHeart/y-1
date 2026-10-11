<template>
  <section class="zhihu-title-candidates" aria-label="候选区">
    <header class="zhihu-block-head">
      <div>
        <h2>{{ answerMode ? '开头候选' : '标题候选' }}</h2>
        <p class="gl-hint">{{ answerMode ? '回答无独立标题：点选一个开头后自动生成大纲与正文（真实生成链，逐字流式）。' : '点选一个标题后自动生成大纲与正文（真实生成链，逐字流式）。' }}</p>
      </div>
      <button
        type="button"
        class="zhihu-title-refresh"
        data-test="zhihu-titles-refresh"
        :disabled="loading"
        @click="onRefresh"
      >{{ loading ? '生成中…' : '⟳ 换一批' }}</button>
    </header>

    <div v-if="loading" class="zhihu-title-skeleton" aria-hidden="true">
      <div v-for="index in 3" :key="index" class="zhihu-title-skeleton-item"></div>
    </div>

    <p v-else-if="titles.length === 0" class="gl-empty" data-test="zhihu-titles-empty">
      暂无候选{{ answerMode ? '开头' : '标题' }}，点「⟳ 换一批」重新生成。
    </p>

    <ul v-else class="zhihu-title-list" role="radiogroup" aria-label="候选列表">
      <li v-for="(option, index) in titles" :key="`${option.title}-${index}`">
        <button
          type="button"
          class="zhihu-title-card"
          role="radio"
          :aria-checked="selectedTitle === option.title"
          :class="{ 'zhihu-title-card-active': selectedTitle === option.title }"
          :disabled="flowing"
          :data-test="`zhihu-title-option-${index}`"
          @click="onSelect(option.title)"
        >
          <span class="zhihu-title-text">{{ option.title }}</span>
          <span v-if="option.hook" class="zhihu-title-hook">{{ option.hook }}</span>
          <span
            v-if="!answerMode"
            class="zhihu-title-count"
            :class="{ 'zhihu-title-count-over': option.title.length > maxTitleChars }"
          >{{ option.title.length }}/{{ maxTitleChars }} 字</span>
        </button>
      </li>
    </ul>

    <p v-if="safetyNote" class="zhihu-title-safety" role="status">{{ safetyNote }}</p>
  </section>
</template>

<script setup lang="ts">
/**
 * 开头/标题候选（对位 xhs-studio TitleCandidates，知乎分叉：回答模式候选=开头、
 * 不做字数上限对比（回答无独立标题，契约 maxTitleChars 只对文章生效））。
 * /api/article-generation/titles 真实多候选 radio 单选卡；「换一批」= fetchTitles
 * 重新拉取；选定后自动串联 streamOutline→streamContent（正文非空时由稿纸区显式重流）。
 * 爆款分/类型标签无评分模型，不渲染（defer）。
 */
import { computed } from 'vue'
import { useZhihuStudioContext } from '../composables/useZhihuStudioContext'
import type { ArticleTitleOption } from '../../../types/article-creation'

/** props 仅作最小测试注入口（F7 同款）：缺省全部 inject 自取真实引擎值。 */
const props = withDefaults(defineProps<{
  titles?: ArticleTitleOption[]
  selectedTitle?: string
  loading?: boolean
}>(), {
  titles: undefined,
  selectedTitle: undefined,
  loading: undefined,
})

const emit = defineEmits<{
  select: [title: string]
  refresh: []
}>()

const ctx = useZhihuStudioContext()

const titles = computed(() => props.titles ?? ctx.engine.titles.value)
const selectedTitle = computed(() => props.selectedTitle ?? ctx.engine.selectedTitle.value)
const loading = computed(() => props.loading ?? ctx.engine.titlesLoading.value)
const answerMode = computed(() => ctx.engine.contentMode.value === 'answer')
/** 大纲/正文流进行中禁用换选，避免中途换开头/标题把流打断。 */
const flowing = computed(() => ctx.engine.outlineLoading.value || ctx.engine.contentLoading.value)

const maxTitleChars = computed(() => ctx.format.formatRule.value?.maxTitleChars ?? 30)

/** 候选期安全状态（正文出现后报告属于正文，不再挂候选区）。 */
const safetyNote = computed(() => {
  if (ctx.engine.content.value.trim()) return ''
  const report = ctx.engine.safetyReport.value
  if (!report) return ''
  return report.findings.length === 0
    ? '候选合规检测：通过'
    : `候选合规检测：${report.findings.length} 项提醒（详见校对步）`
})

async function onSelect(title: string): Promise<void> {
  if (selectedTitle.value === title) return
  ctx.engine.selectTitle(title)
  emit('select', title)
  // 选定后自动串联大纲→正文；串联条件只看正文为空（换选重流，不留死路）。
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
.zhihu-title-candidates {
  display: grid;
  gap: var(--space-sm);
}

.zhihu-block-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-sm);
}

.zhihu-block-head h2 {
  margin: 0;
  font-family: var(--font-display);
  font-size: var(--type-card-title);
  font-weight: var(--weight-heading);
  line-height: var(--leading-card-title);
}

.zhihu-block-head .gl-hint {
  margin: var(--space-xxs) 0 0;
}

.zhihu-title-refresh {
  min-height: var(--control-height);
  padding: 0 var(--space-md);
  flex: none;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--surface-card);
  color: var(--color-text-secondary);
  cursor: pointer;
}

.zhihu-title-refresh:hover:not(:disabled) {
  border-color: var(--color-border-hover);
  background: var(--color-surface-hover);
  color: var(--color-text);
}

.zhihu-title-refresh:disabled {
  background: var(--surface-muted);
  cursor: not-allowed;
}

.zhihu-title-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: var(--space-xs);
}

.zhihu-title-card {
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

.zhihu-title-card-active {
  border-color: color-mix(in srgb, var(--color-accent) 45%, transparent);
  background: var(--color-surface-highlight);
  box-shadow: var(--focus-ring);
}

.zhihu-title-text {
  color: var(--color-text);
  font-size: var(--type-body);
  font-weight: var(--weight-heading);
  line-height: 1.45;
}

.zhihu-title-hook {
  color: var(--color-text-muted);
  font-size: var(--type-caption);
  line-height: var(--leading-caption);
}

.zhihu-title-count {
  color: var(--color-text-muted);
  font-size: var(--type-caption);
  font-variant-numeric: tabular-nums;
}

.zhihu-title-count-over {
  color: var(--color-danger);
  font-weight: var(--weight-heading);
}

.zhihu-title-safety {
  margin: 0;
  color: var(--color-text-secondary);
  font-size: var(--type-caption);
}

.zhihu-title-skeleton {
  display: grid;
  gap: var(--space-xs);
}

.zhihu-title-skeleton-item {
  height: 64px;
  border-radius: var(--radius-md);
  background: var(--surface-muted);
  animation: zhihu-title-pulse 1.2s ease-in-out infinite;
}

@keyframes zhihu-title-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.55; }
}
</style>
