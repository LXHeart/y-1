<template>
  <nav class="xhs-steps-bar" aria-label="创作步骤">
    <button
      v-for="(s, i) in steps"
      :key="s.key"
      type="button"
      class="xhs-step-dot"
      :class="{
        'xhs-step-active': current === s.key,
        'xhs-step-done': !isDisabled(s.key) && stepIndex(current) > i,
      }"
      :disabled="isDisabled(s.key)"
      :aria-disabled="isDisabled(s.key)"
      :aria-current="current === s.key ? 'step' : undefined"
      @click="onSelect(s.key)"
    >
      <span class="xhs-step-num" aria-hidden="true">{{ i + 1 }}</span>
      <span class="xhs-step-label">{{ s.label }}</span>
    </button>
  </nav>
</template>

<script setup lang="ts">
/**
 * 小红书创作台四步条（阶段 0 骨架）：current 高亮、超 reached 步 disabled（canGo=false）、
 * 浅于 current 的步可点回看（emit select → steps.go）。token 口径沿用 StepsBar.vue
 * （组件新建：旧件不可点击，本件是四步跳转入口之一）。
 */
import type { XhsStudioStep } from '../types'

const props = defineProps<{
  steps: ReadonlyArray<{ key: XhsStudioStep; label: string }>
  current: XhsStudioStep
  reached: XhsStudioStep
}>()

const emit = defineEmits<{ select: [step: XhsStudioStep] }>()

function stepIndex(step: XhsStudioStep): number {
  return props.steps.findIndex((s) => s.key === step)
}

/** 超过 reached 的步不可进入（与 useXhsStudioSteps.canGo 同判据，视图层不再重复守卫）。 */
function isDisabled(step: XhsStudioStep): boolean {
  return stepIndex(step) > stepIndex(props.reached)
}

function onSelect(step: XhsStudioStep): void {
  if (isDisabled(step)) return
  emit('select', step)
}
</script>

<style scoped>
.xhs-steps-bar {
  display: inline-flex;
  flex-wrap: wrap;
  gap: var(--space-xxs);
  padding: var(--space-xxs);
  border-radius: var(--radius-pill);
  background: var(--surface-page);
  border: 1px solid var(--color-border);
}

.xhs-step-dot {
  display: inline-flex;
  align-items: center;
  gap: var(--space-xs);
  min-height: 38px;
  padding: 0 var(--space-md);
  border-radius: var(--radius-pill);
  border: 1px solid transparent;
  color: var(--color-text-muted);
  cursor: pointer;
  transition: background var(--duration-fast) var(--ease-out), color var(--duration-fast) var(--ease-out), border-color var(--duration-fast) var(--ease-out);
}

.xhs-step-dot:hover:not(:disabled) {
  border-color: var(--color-border);
  background: var(--color-surface-hover);
}

/* DESIGN.md disabled 口径：中性表面+文字保持可读，不叠 opacity 降对比 */
.xhs-step-dot:disabled {
  background: var(--surface-muted);
  cursor: not-allowed;
}

.xhs-step-active {
  background: var(--gradient-accent);
  border: 1px solid transparent;
  color: var(--color-on-accent);
}

.xhs-step-done {
  color: var(--color-text-secondary);
}

.xhs-step-num {
  width: 20px;
  height: 20px;
  display: grid;
  place-items: center;
  border-radius: var(--radius-pill);
  border: 1px solid var(--color-border);
  background: var(--surface-card);
  font-size: var(--type-caption);
  font-weight: var(--weight-heading);
}

.xhs-step-active .xhs-step-num {
  background: var(--color-on-accent);
  border-color: transparent;
  color: var(--color-primary-active);
}

.xhs-step-done .xhs-step-num {
  color: var(--color-text-secondary);
}

.xhs-step-label {
  font-size: var(--type-caption);
  font-weight: var(--weight-heading);
}
</style>
