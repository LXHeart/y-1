<script setup lang="ts">
import type { ImageAnalysisProgressEvent, ImageAnalysisProgressStage } from '../../../types/image-analysis'

/**
 * 生成步骤/进度列表（任务书 #91 I2：原三处重复的 progress-list 抽为一份，纯搬运；
 * drafting 进度卡（非可点）/ 步审卡 / 结果卡共用）。clickable=步审与结果视图（可点回看步骤产物）。
 */
defineProps<{
  items: ReadonlyArray<ImageAnalysisProgressEvent>
  keyPrefix: string
  clickable: boolean
  stepResults: Record<string, unknown>
  getStageLabel: (stage: ImageAnalysisProgressStage) => string
  getEventDurationLabel: (event: ImageAnalysisProgressEvent) => string
  selectStepResult: (stage: ImageAnalysisProgressStage) => void
}>()
</script>

<template>
  <ol class="progress-list">
    <li
      v-for="(item, index) in items"
      :key="`${keyPrefix}${item.stage}-${item.attempt ?? index}-${index}`"
      class="progress-item"
      :class="clickable && stepResults[item.stage] ? { 'progress-item-clickable': true } : undefined"
      @click="clickable && stepResults[item.stage] && selectStepResult(item.stage)"
    >
      <span class="progress-dot" aria-hidden="true"></span>
      <div class="progress-copy">
        <div class="progress-line">
          <p class="progress-title">{{ getStageLabel(item.stage) }}</p>
          <span v-if="getEventDurationLabel(item)" class="progress-duration">{{ getEventDurationLabel(item) }}</span>
        </div>
        <p class="progress-text">{{ item.message }}</p>
      </div>
    </li>
  </ol>
</template>

<style scoped>
/* ===== 自 ImageAnalysisView.vue 逐字随迁 ===== */
.progress-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: var(--space-sm);
}

.progress-item {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: var(--space-sm);
  align-items: start;
  padding: var(--space-sm) var(--space-md);
  border-radius: var(--radius-lg);
  border: 1px solid var(--color-border);
  background: var(--surface-page);
}

.progress-dot {
  width: 9px;
  height: 9px;
  margin-top: var(--space-xs);
  border-radius: var(--radius-pill);
  background: var(--color-accent);
  box-shadow: 0 0 0 6px color-mix(in srgb, var(--color-accent) 12%, transparent);
}

.progress-copy {
  display: grid;
  gap: var(--space-xxs);
}

.progress-line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-sm);
  flex-wrap: wrap;
}

.progress-title,
.progress-text {
  margin: 0;
}

.progress-duration,
.result-steps-run-id {
  color: var(--color-text-muted);
  font-size: var(--type-caption);
  line-height: 1.4;
}

.progress-title {
  color: var(--color-text);
  font-size: var(--type-body-sm);
  font-weight: var(--weight-heading);
}

.progress-text {
  color: var(--color-text-secondary);
  font-size: var(--type-caption);
  line-height: 1.55;
}

.progress-item-clickable {
  cursor: pointer;
  transition: border-color var(--duration-fast) var(--ease-out), background var(--duration-fast) var(--ease-out);
}

.progress-item-clickable:hover {
  border-color: var(--color-border-accent);
  background: var(--surface-card);
}
</style>
