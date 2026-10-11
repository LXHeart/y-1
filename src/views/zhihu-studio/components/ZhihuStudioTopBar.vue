<template>
  <header class="zhihu-topbar">
    <button type="button" class="zhihu-topbar-back" data-test="zhihu-topbar-back" @click="emit('back')">
      <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true">
        <path d="M10 3.5 5.5 8l4.5 4.5" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/>
      </svg>
      返回创作中心
    </button>

    <div class="zhihu-topbar-title">
      <span class="badge badge-accent">知乎</span>
      <h1>知乎创作台</h1>
    </div>

    <!-- 内容形态（回答/文章）：横切状态，切换确认语义在 context.requestContentMode（ZhihuModeToggle 同款） -->
    <div
      class="zhihu-mode-toggle"
      role="radiogroup"
      aria-label="知乎内容形态"
      data-test="zhihu-mode-toggle"
    >
      <button
        type="button"
        class="zhihu-mode-btn"
        :class="{ 'zhihu-mode-btn-active': contentMode === 'answer' }"
        :aria-checked="contentMode === 'answer'"
        role="radio"
        data-test="zhihu-mode-answer"
        :disabled="taskQuestionLocked"
        :title="taskQuestionLocked ? '任务指定回答形态，不可更改' : undefined"
        @click="emit('set-mode', 'answer')"
      >写回答</button>
      <button
        type="button"
        class="zhihu-mode-btn"
        :class="{ 'zhihu-mode-btn-active': contentMode === 'article' }"
        :aria-checked="contentMode === 'article'"
        role="radio"
        data-test="zhihu-mode-article"
        :disabled="taskQuestionLocked"
        :title="taskQuestionLocked ? '任务指定回答形态，不可更改' : undefined"
        @click="emit('set-mode', 'article')"
      >写文章</button>
    </div>

    <div class="zhihu-topbar-status">
      <span v-if="draftTitle" class="zhihu-topbar-draft" :title="draftTitle">{{ draftTitle }}</span>
      <WorkspaceSaveBadge
        :state="saveState"
        :conflict="conflict"
        :readonly="readonly"
        @retry="emit('retry')"
        @reload="emit('reload')"
      />
      <button type="button" class="gl-btn-secondary zhihu-topbar-reset" data-test="zhihu-topbar-reset" @click="emit('reset')">重新开始</button>
      <button type="button" class="gl-btn-primary zhihu-topbar-save" data-test="zhihu-topbar-save" @click="emit('saveDraft')">保存草稿</button>
    </div>
  </header>
</template>

<script setup lang="ts">
/**
 * 知乎创作台顶栏：返回创作中心 + 平台徽章 + 回答/文章形态切换（engine.contentMode
 * 真实状态；confirm 在 context.requestContentMode）+ 保存徽标 + 重新开始/手动保存。
 * 任务锁定形态时两档禁用（ZhihuModeToggle 语义，激活档保留渐变可辨识）。
 */
import WorkspaceSaveBadge from '../../ai-center/creation/WorkspaceSaveBadge.vue'

defineProps<{
  saveState: 'idle' | 'pending' | 'saving' | 'saved' | 'conflict' | 'error'
  conflict?: string
  readonly?: boolean
  draftTitle?: string
  contentMode: 'answer' | 'article'
  /** 任务锁定回答形态（taskQuestionLocked）时两档禁用。 */
  taskQuestionLocked?: boolean
}>()

const emit = defineEmits<{
  back: []
  saveDraft: []
  reset: []
  retry: []
  reload: []
  'set-mode': [mode: 'answer' | 'article']
}>()
</script>

<style scoped>
.zhihu-topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: var(--space-sm);
}

.zhihu-topbar-back {
  display: inline-flex;
  align-items: center;
  gap: var(--space-xxs);
  min-height: var(--control-height);
  padding: 0 var(--space-sm);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--surface-card);
  color: var(--color-text-secondary);
  cursor: pointer;
}

.zhihu-topbar-back:hover:not(:disabled) {
  border-color: var(--color-border-hover);
  background: var(--color-surface-hover);
}

.zhihu-topbar-title {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
}

.zhihu-topbar-title h1 {
  margin: 0;
  font-family: var(--font-display);
  font-size: var(--type-card-title);
  font-weight: var(--weight-heading);
  line-height: var(--leading-card-title);
}

/* 形态切换：token 口径沿用 stage-shared 的 mode-toggle（ZhihuModeToggle 同款） */
.zhihu-mode-toggle {
  display: inline-flex;
  gap: var(--space-xxs);
  padding: var(--space-xxs);
  border-radius: var(--radius-pill);
  background: var(--surface-page);
  border: 1px solid var(--color-border);
}

.zhihu-mode-btn {
  min-height: var(--control-height);
  padding: 0 var(--space-md);
  border-radius: var(--radius-pill);
  border: 1px solid transparent;
  background: transparent;
  color: var(--color-text-secondary);
  font-size: var(--type-caption);
  font-weight: var(--weight-heading);
  cursor: pointer;
  transition: background var(--duration-fast) var(--ease-out), color var(--duration-fast) var(--ease-out);
}

.zhihu-mode-btn:hover:not(:disabled) {
  color: var(--color-text);
}

.zhihu-mode-btn-active {
  background: var(--gradient-accent);
  color: var(--color-on-accent);
}

.zhihu-mode-btn:disabled {
  cursor: not-allowed;
}

.zhihu-topbar-status {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-sm);
}

.zhihu-topbar-draft {
  max-width: 16em;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--color-text-muted);
  font-size: var(--type-caption);
}

.zhihu-topbar-reset,
.zhihu-topbar-save { min-height: var(--control-height); }
</style>
