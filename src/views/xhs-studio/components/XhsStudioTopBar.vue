<template>
  <header class="xhs-topbar">
    <button type="button" class="xhs-topbar-back" @click="emit('back')">
      <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true">
        <path d="M10 3.5 5.5 8l4.5 4.5" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/>
      </svg>
      返回创作中心
    </button>
    <div class="xhs-topbar-title">
      <span class="badge badge-accent">{{ platformLabel }}</span>
      <h1>种草图文</h1>
    </div>
    <div class="xhs-topbar-status">
      <span v-if="draftTitle" class="xhs-topbar-draft" :title="draftTitle">{{ draftTitle }}</span>
      <WorkspaceSaveBadge
        :state="saveState"
        :conflict="conflict"
        :readonly="readonly"
        @retry="emit('retry')"
        @reload="emit('reload')"
      />
      <button type="button" class="gl-btn-primary xhs-topbar-save" @click="emit('saveDraft')">保存草稿</button>
    </div>
  </header>
</template>

<script setup lang="ts">
/**
 * 小红书创作台顶栏（阶段 0 骨架）：返回创作中心 + 平台徽章 + 保存徽标 + 手动保存。
 * 保存/重试动作经 emit 上抛，由视图接到 autosave（flush/retry/reloadRemote）。
 */
import WorkspaceSaveBadge from '../../ai-center/creation/WorkspaceSaveBadge.vue'

withDefaults(defineProps<{
  saveState: 'idle' | 'pending' | 'saving' | 'saved' | 'conflict' | 'error'
  conflict?: string
  readonly?: boolean
  draftTitle?: string
  platformLabel?: string
}>(), {
  platformLabel: '小红书',
})

const emit = defineEmits<{
  back: []
  saveDraft: []
  retry: []
  reload: []
}>()
</script>

<style scoped>
.xhs-topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: var(--space-sm);
}

.xhs-topbar-back {
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

.xhs-topbar-back:hover:not(:disabled) {
  border-color: var(--color-border-hover);
  background: var(--color-surface-hover);
}

.xhs-topbar-title {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
}

.xhs-topbar-title h1 {
  margin: 0;
  font-family: var(--font-display);
  font-size: var(--type-card-title);
  font-weight: var(--weight-heading);
  line-height: var(--leading-card-title);
}

.xhs-topbar-status {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-sm);
}

.xhs-topbar-draft {
  max-width: 16em;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--color-text-muted);
  font-size: var(--type-caption);
}

.xhs-topbar-save { min-height: var(--control-height); }
</style>
