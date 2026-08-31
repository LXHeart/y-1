<template>
  <section class="stage-card gl-zone fade-in">
    <header class="card-head">
      <h2 class="card-title">{{ answerMode ? '回答已完成' : '文章已完成' }}</h2>
      <p class="field-note" :class="{ 'completed-opening': answerMode }">{{ selectedTitle }}</p>
    </header>

    <!-- 任务书 #62：发布前提示条（知乎两模式文案不同；仅提示，不改正文） -->
    <ul v-if="publishHints.length > 0" class="publish-hints" data-testid="publish-hints" role="note">
      <li v-for="hint in publishHints" :key="hint">{{ hint }}</li>
    </ul>

    <div v-if="formatRule" class="format-rule-bar" :class="{ 'format-rule-bar-warn': formatIssues.length > 0 }" role="note">
      <p class="format-rule-summary">{{ formatRuleSummary }}</p>
      <ul v-if="formatIssues.length > 0" class="format-rule-warnings">
        <li v-for="issue in formatIssues" :key="issue">{{ issue }}</li>
      </ul>
    </div>

    <div class="completed-preview">
      <div class="completed-body" v-html="renderedMarkdown"></div>
    </div>

    <div class="action-row">
      <button class="btn-primary gl-btn-primary" @click="$emit('copy')">复制正文</button>
      <button class="btn-secondary" @click="$emit('reset')">{{ answerMode ? '新建回答' : '新建文章' }}</button>
    </div>
  </section>
</template>

<script setup lang="ts">
import type { PlatformFormatRule } from '../../../config/platform-format-rules'
import { renderSafeMarkdown } from '../../../lib/safe-markdown'

const props = defineProps<{
  selectedTitle: string
  contentWithImages: string
  formatRule: PlatformFormatRule | null | undefined
  formatRuleSummary: string
  formatIssues: string[]
  /** 任务书 #62：知乎回答模式（问题即标题，无话题标签）。 */
  answerMode?: boolean
  /** 发布前提示（平台规范/声明要求），逐条渲染。 */
  publishHints?: string[]
}>()

defineEmits<{
  copy: []
  reset: []
}>()

const renderedMarkdown = computed(() => renderSafeMarkdown(props.contentWithImages))
const publishHints = computed(() => props.publishHints ?? [])
</script>

<script lang="ts">
import { computed } from 'vue'
</script>

<style scoped>
.stage-card,
.card-head {
  display: grid;
  gap: 14px;
}

/* 开头段是整段文本（60-120 字），不能按单行标题截断 */
.completed-opening {
  white-space: pre-wrap;
}

.publish-hints {
  display: grid;
  gap: 6px;
  margin: 0;
  padding: 12px 14px 12px 30px;
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border);
  background: var(--surface-page);
  color: var(--color-text-secondary);
  font-size: 0.82rem;
  line-height: 1.6;
}

.card-title {
  margin: 0;
  font-size: 1.14rem;
  font-weight: 600;
  line-height: 1.25;
  color: var(--color-text);
}

.field-note {
  margin: 0;
  color: var(--color-text-secondary);
  font-size: 0.85rem;
  line-height: 1.6;
}

.action-row {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.btn-primary,
.btn-secondary {
  min-height: 38px;
  padding: 0 var(--space-md);
  border-radius: var(--radius-sm);
}

.format-rule-bar {
  display: grid;
  gap: 6px;
  padding: 12px 14px;
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border);
  background: var(--surface-page);
}

.format-rule-summary {
  margin: 0;
  color: var(--color-text-secondary);
  font-size: 0.84rem;
  line-height: 1.55;
}

.format-rule-warnings {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 4px;
}

.format-rule-warnings li {
  margin: 0;
  color: var(--color-warning);
  font-size: 0.82rem;
  line-height: 1.5;
}

.format-rule-bar-warn {
  border-color: color-mix(in srgb, var(--color-danger) 28%, transparent);
  background: color-mix(in srgb, var(--color-danger) 6%, transparent);
}

.completed-preview {
  margin-top: 16px;
  background: var(--color-surface);
  border-radius: var(--radius-md);
  padding: 24px;
  border: 1px solid var(--color-border);
}

.completed-body {
  line-height: 1.75;
  color: var(--color-text);
}

.completed-body :deep(:is(h1, h2, h3)) {
  margin: 1em 0 0.5em;
  font-weight: 600;
}

.completed-body :deep(h2) {
  font-size: 1.15em;
}

.completed-body :deep(h3) {
  font-size: 1.05em;
}

.completed-body :deep(p) {
  margin: 0.5em 0;
}

.completed-body :deep(img) {
  display: block;
  width: 100%;
  max-height: 320px;
  object-fit: cover;
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border);
  margin: 1.2em 0;
}

@media (max-width: 720px) {
  .btn-primary,
  .btn-secondary {
    width: 100%;
  }
}
</style>
