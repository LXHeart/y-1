<template>
  <Teleport to="body">
    <div v-if="visible" class="oversized-overlay" role="dialog" aria-modal="true">
      <div class="oversized-modal glass-card">
        <header class="oversized-head">
          <p class="section-kicker">图片过大</p>
          <h3 class="oversized-title">以下图片超过 5 MB 限制</h3>
        </header>

        <ul class="oversized-list">
          <li v-for="file in files" :key="file.name" class="oversized-item">
            <span class="oversized-name">{{ file.name }}</span>
            <span class="oversized-size">{{ formatSize(file.size) }}</span>
          </li>
        </ul>

        <div class="oversized-actions">
          <button class="btn-primary" :disabled="compressing" @click="$emit('compress')">
            <svg v-if="compressing" class="spin-icon" width="14" height="14" viewBox="0 0 16 16" fill="none"><circle cx="8" cy="8" r="6" stroke="currentColor" stroke-width="2" stroke-dasharray="28" stroke-dashoffset="10" stroke-linecap="round"/></svg>
            {{ compressing ? '压缩中…' : '自动压缩' }}
          </button>
          <button class="btn-secondary" :disabled="compressing" @click="$emit('skip')">跳过这些图片</button>
          <button class="btn-secondary" :disabled="compressing" @click="$emit('cancel')">取消</button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
defineProps<{
  visible: boolean
  files: { name: string; size: number }[]
  compressing: boolean
}>()

defineEmits<{ compress: []; skip: []; cancel: [] }>()

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}
</script>

<style scoped>
.oversized-overlay {
  position: fixed;
  inset: 0;
  z-index: 1000;
  display: grid;
  place-items: center;
  background: var(--color-overlay);
  padding: var(--space-md);
}

.oversized-modal {
  width: min(420px, 100%);
  padding: 24px;
  gap: 16px;
  max-height: 90vh;
  overflow-y: auto;
}

.oversized-head { display: grid; gap: 6px; }

.section-kicker {
  margin: 0;
  font-size: 0.75rem;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--color-text-muted);
  font-weight: 600;
}

.oversized-title { margin: 0; color: var(--color-text); font-size: 1rem; }

.oversized-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 6px;
  max-height: 200px;
  overflow-y: auto;
}

.oversized-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 12px;
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border);
  background: var(--surface-page);
}

.oversized-name {
  color: var(--color-text);
  font-size: 0.84rem;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 65%;
}

.oversized-size { color: var(--color-text-muted); font-size: 0.78rem; font-weight: 600; flex-shrink: 0; }

.oversized-actions { display: flex; gap: 10px; flex-wrap: wrap; }

.btn-primary, .btn-secondary {
  min-height: 40px;
  padding: 0 16px;
  border-radius: var(--radius-md);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  cursor: pointer;
  font-size: 0.84rem;
  font-weight: 600;
  transition: transform var(--duration-fast) var(--ease-out), background var(--duration-fast) var(--ease-out), border-color var(--duration-fast) var(--ease-out), opacity var(--duration-fast) var(--ease-out);
}

.btn-primary { background: var(--color-accent); color: white; border: none; }
.btn-primary:hover:not(:disabled) { background: var(--color-accent-2); transform: translateY(-1px); }
.btn-primary:disabled, .btn-secondary:disabled { opacity: 0.6; cursor: not-allowed; transform: none; }

.btn-secondary {
  background: var(--surface-card);
  border: 1px solid var(--color-border);
  color: var(--color-text-secondary);
}
.btn-secondary:hover:not(:disabled) {
  background: var(--color-surface-hover);
  border-color: var(--color-border-hover);
  color: var(--color-text);
}

.spin-icon { animation: spin 1s linear infinite; }
@keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
</style>
