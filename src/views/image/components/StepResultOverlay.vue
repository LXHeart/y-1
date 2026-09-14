<template>
  <Teleport to="body">
    <div v-if="stepResult" class="step-result-overlay" role="dialog" aria-modal="true" @click="$emit('close')">
      <div class="step-result-modal glass-card" @click.stop>
        <header class="step-result-modal-head">
          <div>
            <p class="section-kicker">{{ stepResult ? getStageLabel(stepResult.stage as ImageAnalysisProgressStage) : '' }}</p>
            <h3 class="step-result-modal-title">该步骤生成的内容</h3>
          </div>
          <button class="step-result-close" type="button" @click="$emit('close')">&times;</button>
        </header>
        <div class="step-result-content result-block">
          <h4 v-if="stepResult.result.title" class="result-label">标题</h4>
          <p v-if="stepResult.result.title" class="result-text result-emphasis">{{ stepResult.result.title }}</p>
          <h4 class="result-label">评价内容</h4>
          <p class="result-text">{{ stepResult.result.review }}</p>
        </div>
        <div v-if="stepResult.result.tags?.length" class="result-tags-wrap">
          <h4 class="result-label">标签</h4>
          <div class="result-tags">
            <span v-for="tag in stepResult.result.tags" :key="tag" class="result-tag">{{ tag }}</span>
          </div>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import type { ImageAnalysisProgressStage } from '../../../types/image-analysis'

export interface StepResultData {
  stage: string
  result: {
    title?: string
    review: string
    tags?: string[]
  }
}

defineProps<{
  stepResult: StepResultData | null
  getStageLabel: (stage: ImageAnalysisProgressStage) => string
}>()

defineEmits<{ close: [] }>()
</script>

<style scoped>
.step-result-overlay {
  position: fixed;
  inset: 0;
  z-index: 1000;
  display: grid;
  place-items: center;
  background: var(--color-overlay);
  padding: var(--space-md);
}

.step-result-modal {
  width: min(560px, 100%);
  max-height: 85vh;
  display: grid;
  gap: var(--space-md);
  padding: var(--space-lg);
  overflow-y: auto;
}

.step-result-modal-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-sm);
}

.section-kicker {
  margin: 0;
  font-size: var(--type-caption);
  letter-spacing: 0;
  text-transform: uppercase;
  color: var(--color-text-muted);
  font-weight: var(--weight-heading);
}

.step-result-modal-title {
  margin: 0;
  color: var(--color-text);
  font-size: var(--type-body);
}

.step-result-close {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  border: none;
  border-radius: var(--radius-pill);
  background: transparent;
  color: var(--color-text-muted);
  font-size: var(--type-section-title);
  line-height: 1;
  cursor: pointer;
  flex-shrink: 0;
  transition: color var(--duration-fast) var(--ease-out), background var(--duration-fast) var(--ease-out);
}

.step-result-close:hover {
  color: var(--color-text);
  background: var(--surface-card);
}

.step-result-content {
  max-height: 50vh;
  overflow-y: auto;
}

.result-block {
  padding: var(--space-md);
  border-radius: var(--radius-lg);
  border: 1px solid var(--color-border);
  background: var(--surface-page);
  display: grid;
  gap: var(--space-sm);
}

.result-label {
  margin: 0;
  font-size: var(--type-caption);
  letter-spacing: 0;
  text-transform: uppercase;
  color: var(--color-text-muted);
  font-weight: var(--weight-heading);
}

.result-text {
  margin: 0;
  color: var(--color-text);
  line-height: 1.75;
  white-space: pre-wrap;
}

.result-emphasis { font-weight: var(--weight-heading); }

.result-tags-wrap { display: grid; gap: var(--space-sm); }

.result-tags { display: flex; flex-wrap: wrap; gap: var(--space-xs); }

.result-tag {
  display: inline-flex;
  align-items: center;
  padding: var(--space-xxs) var(--space-sm);
  border-radius: var(--radius-pill);
  border: 1px solid var(--color-border);
  background: var(--surface-page);
  color: var(--color-text-secondary);
  font-size: var(--type-caption);
}
</style>
