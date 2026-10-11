<template>
  <nav class="zhihu-steps-bar" aria-label="创作步骤">
    <button
      v-for="(s, i) in steps"
      :key="s.key"
      type="button"
      class="zhihu-step-dot"
      :class="{
        'zhihu-step-active': current === s.key,
        'zhihu-step-done': !isDisabled(s.key) && stepIndex(current) > i,
      }"
      :disabled="isDisabled(s.key)"
      :aria-disabled="isDisabled(s.key)"
      :aria-current="current === s.key ? 'step' : undefined"
      :data-test="`zhihu-step-${s.key}`"
      @click="onSelect(s.key)"
    >
      <span class="zhihu-step-num" aria-hidden="true">{{ i + 1 }}</span>
      <span class="zhihu-step-label">{{ s.key === 'pick' ? pickLabel : s.label }}</span>
    </button>
  </nav>
</template>

<script setup lang="ts">
/**
 * 知乎创作台四步条（对位 xhs-studio StepsBar）：current 高亮、超 reached 步 disabled、
 * 浅于 current 的步可点回看（emit select → steps.go）。首步文案按形态分叉：
 * 回答=「问题」、文章=「选题」（与左栏选题台口径一致）。
 */
import type { ZhihuStudioStep } from '../types'

const props = defineProps<{
  steps: ReadonlyArray<{ key: ZhihuStudioStep; label: string }>
  current: ZhihuStudioStep
  reached: ZhihuStudioStep
  /** 回答模式首步文案分叉（question/topic）。 */
  pickLabel: string
}>()

const emit = defineEmits<{ select: [step: ZhihuStudioStep] }>()

function stepIndex(step: ZhihuStudioStep): number {
  return props.steps.findIndex((s) => s.key === step)
}

/** 超过 reached 的步不可进入（与 useZhihuStudioSteps.canGo 同判据，视图层不再重复守卫）。 */
function isDisabled(step: ZhihuStudioStep): boolean {
  return stepIndex(step) > stepIndex(props.reached)
}

function onSelect(step: ZhihuStudioStep): void {
  if (isDisabled(step)) return
  emit('select', step)
}
</script>

<style scoped>
.zhihu-steps-bar {
  display: inline-flex;
  flex-wrap: wrap;
  gap: var(--space-xxs);
  padding: var(--space-xxs);
  border-radius: var(--radius-pill);
  background: var(--surface-page);
  border: 1px solid var(--color-border);
}

.zhihu-step-dot {
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

.zhihu-step-dot:hover:not(:disabled) {
  border-color: var(--color-border);
  background: var(--color-surface-hover);
}

/* DESIGN.md disabled 口径：中性表面+文字保持可读，不叠 opacity 降对比 */
.zhihu-step-dot:disabled {
  background: var(--surface-muted);
  cursor: not-allowed;
}

.zhihu-step-active {
  background: var(--gradient-accent);
  border: 1px solid transparent;
  color: var(--color-on-accent);
}

.zhihu-step-done {
  color: var(--color-text-secondary);
}

.zhihu-step-num {
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

.zhihu-step-active .zhihu-step-num {
  background: var(--color-on-accent);
  border-color: transparent;
  color: var(--color-primary-active);
}

.zhihu-step-done .zhihu-step-num {
  color: var(--color-text-secondary);
}

.zhihu-step-label {
  font-size: var(--type-caption);
  font-weight: var(--weight-heading);
}
</style>
