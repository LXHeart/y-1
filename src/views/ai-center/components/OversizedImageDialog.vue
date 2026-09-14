<template>
  <Teleport to="body">
    <div v-if="show" class="oversized-overlay" role="dialog" aria-modal="true">
      <div class="oversized-modal glass-card">
        <header class="oversized-head">
          <p class="oversized-kicker">图片过大</p>
          <h3 class="oversized-title">以下素材超过 5 MB 限制</h3>
        </header>

        <ul class="oversized-list">
          <li v-for="file in files" :key="file.name" class="oversized-item">
            <span class="oversized-name">{{ file.name }}</span>
            <span class="oversized-size">{{ formatFileSize(file.size) }}</span>
          </li>
        </ul>

        <div class="oversized-actions">
          <button class="oversized-btn-primary" :disabled="compressing" @click="$emit('compress')">
            <span v-if="compressing" class="spinner-sm" />
            {{ compressing ? '压缩中…' : '自动压缩' }}
          </button>
          <button class="oversized-btn-secondary" :disabled="compressing" @click="$emit('skip')">跳过这些图片</button>
          <button class="oversized-btn-secondary" :disabled="compressing" @click="$emit('cancel')">取消</button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
defineProps<{
  show: boolean
  files: File[]
  compressing: boolean
}>()

defineEmits<{
  compress: []
  skip: []
  cancel: []
}>()

function formatFileSize(bytes: number): string {
  if (bytes >= 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
  return (bytes / 1024).toFixed(0) + ' KB'
}
</script>

<style scoped>
.oversized-overlay {
  position: fixed; inset: 0; z-index: 30;
  display: flex; align-items: center; justify-content: center;
  padding: var(--space-lg); background: var(--color-overlay);
  backdrop-filter: none; -webkit-backdrop-filter: none;
}
.oversized-modal {
  width: min(100%, 420px); display: grid; gap: var(--space-md);
  padding: var(--space-lg); animation: fade-in var(--duration-normal) var(--ease-out);
}
.oversized-head { display: grid; gap: var(--space-xs); }
.oversized-kicker { margin: 0; font-size: var(--type-caption); font-weight: var(--weight-heading); letter-spacing: 0; text-transform: uppercase; color: var(--color-text-muted); }
.oversized-title { margin: 0; font-size: var(--type-body); font-weight: var(--weight-heading); color: var(--color-text); }
.oversized-list { list-style: none; margin: 0; padding: 0; display: grid; gap: var(--space-xs); }
.oversized-item { display: flex; align-items: center; justify-content: space-between; gap: var(--space-sm); padding: var(--space-xs) var(--space-sm); border-radius: var(--radius-sm); background: var(--surface-muted); border: 1px solid var(--color-border); }
.oversized-name { font-size: var(--type-caption); color: var(--color-text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; min-width: 0; }
.oversized-size { font-size: var(--type-caption); color: var(--color-danger); font-weight: var(--weight-heading); flex-shrink: 0; }
.oversized-actions { display: flex; gap: var(--space-xs); flex-wrap: wrap; }
.oversized-btn-primary, .oversized-btn-secondary {
  min-height: var(--control-height); padding: 0 var(--space-md); border-radius: var(--radius-sm);
  font-size: var(--type-body-sm); font-weight: var(--weight-heading); cursor: pointer;
  display: inline-flex; align-items: center; justify-content: center; gap: var(--space-xs);
  transition: background var(--duration-fast) var(--ease-out), border-color var(--duration-fast) var(--ease-out), opacity var(--duration-fast) var(--ease-out);
}
.oversized-btn-primary { background: var(--gradient-accent); color: var(--color-on-accent); border: none; }
.oversized-btn-primary:hover:not(:disabled) { opacity: 0.9; }
.oversized-btn-secondary { background: var(--surface-card); color: var(--color-text-secondary); border: 1px solid var(--color-border); }
.oversized-btn-secondary:hover:not(:disabled) { background: var(--surface-hover); border-color: var(--color-border-hover); }
.oversized-btn-primary:disabled, .oversized-btn-secondary:disabled { opacity: 0.5; cursor: not-allowed; }
.spinner-sm { width: 14px; height: 14px; border: 2px solid var(--color-border-control); border-top-color: var(--color-on-accent); border-radius: 50%; animation: spin 0.6s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
</style>
