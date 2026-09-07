<script setup lang="ts">
/**
 * 创作步骤条（任务书 #91 R1 自 ArticleCreationView.vue 模板 41–54 + steps-bar 样式迁入，
 * 纯搬运）。stepIndex 辅助随迁（仅本组件使用）。
 */
const props = defineProps<{
  steps: ReadonlyArray<{ key: string; label: string }>
  stage: string
  completed: boolean
}>()

function stepIndex(s: string): number {
  return props.steps.findIndex((step) => step.key === s)
}
</script>

<template>
  <nav class="steps-bar" aria-label="创作步骤">
    <div
      v-for="(s, i) in steps"
      :key="s.key"
      class="step-dot"
      :class="{
        'step-active': !completed && stage === s.key,
        'step-done': completed || stepIndex(stage) > i,
      }"
    >
      <span class="step-num">{{ i + 1 }}</span>
      <span class="step-label">{{ s.label }}</span>
    </div>
  </nav>
</template>

<style scoped>
.steps-bar {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 4px;
  padding: 4px;
  border-radius: var(--radius-pill);
  background: var(--surface-page);
  border: 1px solid var(--color-border);
}

.step-dot {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-height: 38px;
  padding: 0 14px;
  border-radius: var(--radius-pill);
  color: var(--color-text-muted);
  transition: background var(--duration-fast) var(--ease-out), color var(--duration-fast) var(--ease-out), border-color var(--duration-fast) var(--ease-out);
}

.step-active {
  background: var(--gradient-accent);
  border: 1px solid transparent;
  color: var(--color-on-accent);
}

.step-done {
  color: var(--color-text-secondary);
}

.step-num {
  width: 20px;
  height: 20px;
  display: grid;
  place-items: center;
  border-radius: var(--radius-pill);
  border: 1px solid var(--color-border);
  background: var(--surface-card);
  font-size: 0.74rem;
  font-weight: 700;
}

.step-active .step-num {
  background: var(--color-on-accent);
  border-color: transparent;
  color: var(--color-accent);
}

.step-done .step-num {
  color: var(--color-text-secondary);
}

.step-label {
  font-size: 0.83rem;
  font-weight: 600;
}
</style>
