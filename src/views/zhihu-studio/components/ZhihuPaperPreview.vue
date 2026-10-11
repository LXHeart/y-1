<template>
  <aside class="zhihu-preview" aria-label="知乎页面预览">
    <div class="zhihu-preview-head">
      <h2>页面预览</h2>
      <p class="zhihu-preview-hint">按知乎页面版式渲染当前草稿。</p>
    </div>

    <!-- 纸面预览：固定浅色（--zhihu-paper-*，亮暗同值不随主题）；只渲染真实字段 -->
    <div class="zhihu-preview-sheet" data-test="zhihu-preview-sheet">
      <p v-if="answerMode" class="zhihu-preview-kicker" data-test="zhihu-preview-mode">回答</p>
      <p v-else class="zhihu-preview-kicker" data-test="zhihu-preview-mode">文章</p>

      <h3 v-if="answerMode && question" class="zhihu-preview-q" data-test="zhihu-preview-question">{{ question }}</h3>
      <h3 v-else-if="selectedTitle" class="zhihu-preview-title" data-test="zhihu-preview-title">{{ selectedTitle }}</h3>

      <div v-if="empty" class="zhihu-preview-empty" data-test="zhihu-preview-empty">
        {{ answerMode ? '填写目标问题并生成回答后，这里按知乎页面版式实时预览。' : '填写选题并生成文章后，这里按知乎页面版式实时预览。' }}
      </div>
      <template v-else>
        <div class="zhihu-preview-body" data-test="zhihu-preview-body">{{ bodyPreview }}</div>
        <p v-if="!answerMode && topics.length" class="zhihu-preview-topics" data-test="zhihu-preview-topics">
          <span v-for="topic in topics" :key="topic" class="zhihu-preview-topic">{{ topic }}</span>
        </p>
        <p class="zhihu-preview-foot" data-test="zhihu-preview-foot">
          {{ footStats }}
        </p>
      </template>
    </div>

    <!-- 规范体检：formatIssues 实时（真实规则结果；预估赞同/涨粉等无模型不渲染） -->
    <div class="zhihu-preview-checks" aria-label="规范体检">
      <h3>规范体检</h3>
      <p v-if="checkCount === 0" class="zhihu-preview-checks-ok" data-test="zhihu-checks-ok">当前无规范提醒。</p>
      <ul v-else class="zhihu-preview-checks-list" data-test="zhihu-checks-list">
        <li v-for="(issue, index) in formatIssues" :key="index">{{ issue }}</li>
      </ul>
    </div>
  </aside>
</template>

<script setup lang="ts">
/**
 * 右栏知乎页面预览 + 规范体检（原型右栏「稿纸预览/体检」的真身）：
 * - 预览只渲染真实草稿字段（回答=问题+正文；文章=标题+正文+话题）；互动数、
 *   徽章、赞同/评论等演示字段零渲染（宁缺毋假）。
 * - 纸面走 --zhihu-paper-* 固定浅色 token（媒体画布不随主题，与 --xhs-phone-* 同策略）。
 * - 规范体检=useArticleFormatRule 的 formatIssues 实时结果（字数/标题/必须词）；
 *   预估赞同数/读完率/涨粉等无预测模型，不渲染（defer）。
 */
import { computed } from 'vue'
import { useZhihuStudioContext } from '../composables/useZhihuStudioContext'

const ctx = useZhihuStudioContext()

const answerMode = computed(() => ctx.engine.contentMode.value === 'answer')
const question = computed(() => ctx.engine.question.value.trim())
const selectedTitle = computed(() => ctx.engine.selectedTitle.value.trim())
const content = computed(() => ctx.engine.content.value)
const topics = computed(() => ctx.autosave.deliveryValue.value.topics ?? [])
const formatIssues = computed(() => ctx.format.formatIssues.value)

const empty = computed(() => content.value.trim() === '')

/** 预览正文截断（预览画布有限；完整正文在稿纸编辑器）。 */
const bodyPreview = computed(() => {
  const text = content.value.trim()
  return text.length > 600 ? `${text.slice(0, 600)}…` : text
})

const charCount = computed(() => content.value.replace(/!\[[^\]]*\]\([^)]*\)/g, '').trim().length)
const footStats = computed(() => {
  const read = charCount.value === 0 ? 0 : Math.max(1, Math.ceil(charCount.value / 300))
  return `${charCount.value} 字 · 约 ${read} 分钟`
})

const checkCount = computed(() => formatIssues.value.length)
</script>

<style scoped>
.zhihu-preview {
  display: grid;
  gap: var(--space-sm);
}

.zhihu-preview-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--space-sm);
}

.zhihu-preview-head h2 {
  margin: 0;
  font-family: var(--font-display);
  font-size: var(--type-card-title);
  font-weight: var(--weight-heading);
  line-height: var(--leading-card-title);
}

.zhihu-preview-hint {
  margin: 0;
  color: var(--color-text-muted);
  font-size: var(--type-caption);
}

/* 纸面预览壳系：固定浅色（预览媒体画布不随主题），token 见 --zhihu-paper-*。 */
.zhihu-preview-sheet {
  display: grid;
  gap: var(--space-sm);
  padding: var(--space-lg) var(--space-md) var(--space-md);
  border-radius: var(--radius-md);
  background: var(--zhihu-paper-bg);
  border: 1px solid var(--zhihu-paper-border);
  box-shadow: var(--shadow-card);
}

.zhihu-preview-kicker {
  margin: 0;
  color: var(--zhihu-paper-accent);
  font-size: var(--type-caption);
  font-weight: var(--weight-heading);
  letter-spacing: 0.08em;
}

.zhihu-preview-q,
.zhihu-preview-title {
  margin: 0;
  font-family: var(--font-display);
  font-weight: var(--weight-heading);
  color: var(--zhihu-paper-text);
  line-height: 1.5;
}

.zhihu-preview-q { font-size: var(--type-section-title); }
.zhihu-preview-title { font-size: var(--type-section-title); }

.zhihu-preview-body {
  color: var(--zhihu-paper-text-secondary);
  font-size: var(--type-body-sm);
  line-height: 1.85;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  max-height: 420px;
  overflow-y: auto;
}

.zhihu-preview-topics {
  margin: 0;
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-xs);
}

.zhihu-preview-topic {
  color: var(--zhihu-paper-accent);
  font-size: var(--type-caption);
}

.zhihu-preview-foot {
  margin: 0;
  padding-top: var(--space-xs);
  border-top: 1px solid var(--zhihu-paper-border);
  color: var(--zhihu-paper-muted);
  font-size: var(--type-caption);
  font-variant-numeric: tabular-nums;
}

.zhihu-preview-empty {
  display: grid;
  place-items: center;
  min-height: 160px;
  background: var(--zhihu-paper-surface);
  border: 1px dashed var(--zhihu-paper-border);
  border-radius: var(--radius-sm);
  color: var(--zhihu-paper-muted);
  font-size: var(--type-caption);
  padding: var(--space-md);
  text-align: center;
}

.zhihu-preview-checks {
  display: grid;
  gap: var(--space-xs);
  padding: var(--space-sm) var(--space-md);
  border: var(--border-width) solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--surface-card);
}

.zhihu-preview-checks h3 {
  margin: 0;
  font-family: var(--font-display);
  font-size: var(--type-body-sm);
  font-weight: var(--weight-heading);
  color: var(--color-text);
}

.zhihu-preview-checks-ok {
  margin: 0;
  color: var(--color-text-muted);
  font-size: var(--type-caption);
}

.zhihu-preview-checks-list {
  margin: 0;
  padding-left: 1.2em;
  display: grid;
  gap: var(--space-xxs);
}

.zhihu-preview-checks-list li {
  color: var(--color-text-secondary);
  font-size: var(--type-caption);
  line-height: var(--leading-caption);
}
</style>
