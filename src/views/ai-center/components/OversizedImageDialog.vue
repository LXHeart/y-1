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
  padding: 20px; background: var(--color-overlay);
  backdrop-filter: blur(10px); -webkit-backdrop-filter: blur(10px);
}
.oversized-modal {
  width: min(100%, 420px); display: grid; gap: var(--space-md);
  padding: var(--space-lg); animation: fade-in var(--duration-normal) var(--ease-out);
}
.oversized-head { display: grid; gap: 6px; }
.oversized-kicker { margin: 0; font-size: 0.74rem; font-weight: 700; letter-spacing: 0.14em; text-transform: uppercase; color: var(--color-text-muted); }
.oversized-title { margin: 0; font-size: 1.1rem; font-weight: 700; color: var(--color-text); }
.oversized-list { list-style: none; margin: 0; padding: 0; display: grid; gap: 6px; }
.oversized-item { display: flex; align-items: center; justify-content: space-between; gap: var(--space-sm); padding: 8px 12px; border-radius: var(--radius-sm); background: var(--surface-muted); border: 1px solid var(--color-border); }
.oversized-name { font-size: 0.84rem; color: var(--color-text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; min-width: 0; }
.oversized-size { font-size: 0.78rem; color: var(--color-danger); font-weight: 600; flex-shrink: 0; }
.oversized-actions { display: flex; gap: 8px; flex-wrap: wrap; }
.oversized-btn-primary, .oversized-btn-secondary {
  min-height: 40px; padding: 0 16px; border-radius: var(--radius-sm);
  font-size: 0.86rem; font-weight: 600; cursor: pointer;
  display: inline-flex; align-items: center; justify-content: center; gap: 6px;
  transition: background var(--duration-fast) var(--ease-out), border-color var(--duration-fast) var(--ease-out), opacity var(--duration-fast) var(--ease-out);
}
.oversized-btn-primary { background: var(--gradient-accent); color: #fff; border: none; }
.oversized-btn-primary:hover:not(:disabled) { opacity: 0.9; }
.oversized-btn-secondary { background: var(--surface-card); color: var(--color-text-secondary); border: 1px solid var(--color-border); }
.oversized-btn-secondary:hover:not(:disabled) { background: var(--surface-hover); border-color: var(--color-border-hover); }
.oversized-btn-primary:disabled, .oversized-btn-secondary:disabled { opacity: 0.5; cursor: not-allowed; }
.spinner-sm { width: 14px; height: 14px; border: 2px solid rgba(255,255,255,0.3); border-top-color: #fff; border-radius: 50%; animation: spin 0.6s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
</style>
