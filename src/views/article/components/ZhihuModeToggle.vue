<script setup lang="ts">
/**
 * 知乎双模式选择（任务书 #91 R1 自 ArticleCreationView.vue 模板 4–39 + mode-toggle 样式
 * 整段迁入，纯搬运；任务书 #62 原实现）。v-if（zhihuModeVisible && !completed）由父层挂在
 * 组件标签上；requestContentMode 的 window.confirm 原样保留在组件内。
 */
const props = defineProps<{
  contentMode: 'article' | 'answer'
  taskQuestionLocked: boolean
  /** 已有候选/大纲/正文任一即视为有产物——切模式必须确认（两套 prompt 产物不可混用）。 */
  hasProducts: boolean
}>()

const emit = defineEmits<{ 'set-mode': [mode: 'article' | 'answer'] }>()

/** 已有产物时切模式要确认——两套 prompt 产物不可混用，切换必然清空。 */
function requestContentMode(mode: 'article' | 'answer'): void {
  if (props.taskQuestionLocked || props.contentMode === mode) return
  if (props.hasProducts && !window.confirm('切换模式会清空已生成的候选、大纲和正文，确定切换？')) return
  emit('set-mode', mode)
}
</script>

<template>
  <div
    class="mode-toggle"
    role="radiogroup"
    aria-label="知乎内容形态"
    data-testid="zhihu-mode-toggle"
  >
    <button
      type="button"
      class="mode-btn"
      :class="{ 'mode-btn-active': contentMode === 'answer' }"
      :aria-checked="contentMode === 'answer'"
      role="radio"
      data-testid="zhihu-mode-answer"
      :disabled="taskQuestionLocked"
      @click="requestContentMode('answer')"
    >写回答</button>
    <button
      type="button"
      class="mode-btn"
      :class="{ 'mode-btn-active': contentMode === 'article' }"
      :aria-checked="contentMode === 'article'"
      role="radio"
      data-testid="zhihu-mode-article"
      :disabled="taskQuestionLocked"
      @click="requestContentMode('article')"
    >写文章</button>
    <p v-if="taskQuestionLocked" class="field-note mode-note" data-testid="task-mode-locked-note">
      任务指定回答形态，问题由商家给定，不可更改。
    </p>
    <p v-else class="field-note mode-note">
      {{ contentMode === 'answer'
        ? '回答挂在已有问题下，问题本身即标题，首屏前 100 字决定读者是否读完。'
        : '文章走搜索长尾，需要自己的标题。' }}
    </p>
  </div>
</template>

<style scoped>
/* 任务书 #62：知乎模式分段控件（步骤条上方）。token 全部复用，不新增。 */
.mode-toggle {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  padding: 6px;
  border-radius: var(--radius-pill);
  background: var(--surface-page);
  border: 1px solid var(--color-border);
}

.mode-btn {
  min-height: 34px;
  padding: 0 16px;
  border-radius: var(--radius-pill);
  border: 1px solid transparent;
  background: transparent;
  /* secondary 而非 muted：未选档也是可点控件，muted 在暗色下只有 4.03:1 */
  color: var(--color-text-secondary);
  font-size: 0.84rem;
  font-weight: 600;
  cursor: pointer;
  transition: background var(--duration-fast) var(--ease-out), color var(--duration-fast) var(--ease-out);
}

.mode-btn:hover:not(:disabled) {
  color: var(--color-text-primary);
}

.mode-btn-active {
  background: var(--gradient-accent);
  color: var(--color-on-accent);
}

/* 任务书 #62 卡7：任务锁定形态时两档都禁用——激活档仍要看得清（保留渐变），
   只去掉可点手势与 hover 反馈，避免「像坏了」。 */
.mode-btn:disabled {
  cursor: not-allowed;
}

.mode-note {
  flex: 1 1 240px;
  margin: 0;
  padding-left: 4px;
}
</style>
