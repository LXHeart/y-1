<template>
  <section class="stage-card glass-card fade-in">
    <header class="card-head">
      <h2 class="card-title">文章已完成</h2>
      <p class="field-note">{{ selectedTitle }}</p>
    </header>

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
      <button class="btn-primary" @click="$emit('copy')">复制正文</button>
      <button class="btn-secondary" @click="$emit('reset')">新建文章</button>
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
}>()

defineEmits<{
  copy: []
  reset: []
}>()

const renderedMarkdown = computed(() => renderSafeMarkdown(props.contentWithImages))
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
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border-radius: var(--radius-md);
  cursor: pointer;
  font-size: 0.84rem;
  font-weight: 600;
  min-height: 40px;
  padding: 0 16px;
  transition: transform var(--duration-fast) var(--ease-out), background var(--duration-fast) var(--ease-out), border-color var(--duration-fast) var(--ease-out), opacity var(--duration-fast) var(--ease-out);
}

.btn-primary {
  background: var(--color-accent);
  color: white;
  border: none;
}

.btn-primary:hover:not(:disabled) {
  background: var(--color-accent-2);
  transform: translateY(-1px);
}

.btn-secondary {
  background: var(--surface-card);
  border: 1px solid var(--color-border);
  color: var(--color-text-secondary);
}

.btn-secondary:hover:not(:disabled) {
  background: var(--color-surface-hover);
  border-color: var(--color-border-hover);
  color: var(--color-text);
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
  color: var(--color-danger, #d97706);
  font-size: 0.82rem;
  line-height: 1.5;
}

.format-rule-bar-warn {
  border-color: rgba(239, 107, 107, 0.28);
  background: rgba(239, 107, 107, 0.06);
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
