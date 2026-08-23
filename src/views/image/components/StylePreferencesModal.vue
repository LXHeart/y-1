<template>
  <Teleport to="body">
    <div v-if="visible" class="preferences-overlay" role="dialog" aria-modal="true" @click="$emit('toggle')">
      <div class="preferences-modal glass-card" @click.stop>
        <header class="preferences-modal-head">
          <div>
            <p class="section-kicker">风格偏好</p>
            <h3 class="preferences-modal-title">我的风格偏好</h3>
          </div>
          <button class="preferences-modal-close" type="button" @click="$emit('toggle')">&times;</button>
        </header>
        <div v-if="loading" class="style-preferences-loading">加载中…</div>
        <div v-else-if="preferences.length === 0" class="style-preferences-empty">
          <p>暂无保存的风格偏好。编辑评价后选择"记忆风格并保存"即可积累个人风格。</p>
        </div>
        <template v-else>
          <!-- Optimize preview mode -->
          <template v-if="optimizedPreferences">
            <div class="optimize-diff-header">
              <p class="optimize-diff-title">优化预览（{{ preferences.length }} 条 → {{ optimizedPreferences.length }} 条）</p>
            </div>
            <div class="optimize-diff-section">
              <p class="optimize-diff-label optimize-diff-removed">将被替换的旧规则</p>
              <ul class="optimize-diff-list">
                <li v-for="(rule, i) in preferences" :key="'old-' + i" class="optimize-diff-item optimize-diff-item-old">
                  <span class="optimize-diff-marker">-</span>
                  <span>{{ rule }}</span>
                </li>
              </ul>
            </div>
            <div class="optimize-diff-section">
              <p class="optimize-diff-label optimize-diff-added">优化后的新规则</p>
              <ul class="optimize-diff-list">
                <li v-for="(rule, i) in optimizedPreferences" :key="'new-' + i" class="optimize-diff-item optimize-diff-item-new">
                  <span class="optimize-diff-marker">+</span>
                  <span>{{ rule }}</span>
                </li>
              </ul>
            </div>
            <div class="optimize-diff-actions">
              <button class="btn-primary" :disabled="saving" @click="$emit('confirm-optimize')">
                <svg v-if="saving" class="spin-icon" width="14" height="14" viewBox="0 0 16 16" fill="none"><circle cx="8" cy="8" r="6" stroke="currentColor" stroke-width="2" stroke-dasharray="28" stroke-dashoffset="10" stroke-linecap="round"/></svg>
                {{ saving ? '保存中…' : '确认替换' }}
              </button>
              <button class="btn-secondary" :disabled="saving" @click="$emit('cancel-optimize')">取消</button>
            </div>
          </template>

          <!-- Normal list mode -->
          <template v-else>
            <div class="optimize-actions">
              <button class="btn-secondary" :disabled="optimizing" @click="$emit('optimize')">
                <svg v-if="optimizing" class="spin-icon" width="14" height="14" viewBox="0 0 16 16" fill="none"><circle cx="8" cy="8" r="6" stroke="currentColor" stroke-width="2" stroke-dasharray="28" stroke-dashoffset="10" stroke-linecap="round"/></svg>
                {{ optimizing ? '优化中…' : '自动优化' }}
              </button>
              <span class="preference-count-hint">{{ preferences.length }} / 100</span>
            </div>
            <p v-if="optimizeError" class="error-text">{{ optimizeError }}</p>
            <ul class="style-preferences-list">
              <li v-for="(rule, i) in paginatedPreferences" :key="paginatedStartIndex + i" class="style-preference-item">
                <template v-if="editingIndex === paginatedStartIndex + i">
                  <input
                    :value="editingValue"
                    class="field-input-sm preference-edit-input"
                    :disabled="saving"
                    @input="$emit('update:editing-value', ($event.target as HTMLInputElement).value)"
                    @keydown.enter.prevent="$emit('confirm-edit')"
                  >
                  <button class="btn-secondary btn-sm" :disabled="saving" @click="$emit('confirm-edit')">确认</button>
                  <button class="btn-secondary btn-sm" @click="$emit('cancel-edit')">取消</button>
                </template>
                <template v-else>
                  <span class="preference-text">{{ rule }}</span>
                  <div class="preference-actions">
                    <button class="preference-action-btn" type="button" :disabled="optimizing" @click="$emit('start-edit', paginatedStartIndex + i)" aria-label="编辑">
                      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg>
                    </button>
                    <button class="preference-action-btn preference-delete-btn" type="button" :disabled="optimizing" @click="$emit('delete', paginatedStartIndex + i)" aria-label="删除">
                      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
                    </button>
                  </div>
                </template>
              </li>
            </ul>
            <div v-if="totalPages > 1" class="preference-pagination">
              <button class="btn-secondary btn-sm" :disabled="page <= 1" @click="$emit('update:page', Math.max(1, page - 1))">上一页</button>
              <span class="preference-page-info">{{ page }} / {{ totalPages }}</span>
              <button class="btn-secondary btn-sm" :disabled="page >= totalPages" @click="$emit('update:page', Math.min(totalPages, page + 1))">下一页</button>
            </div>
          </template>
        </template>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
defineProps<{
  visible: boolean
  loading: boolean
  preferences: string[]
  saving: boolean
  optimizing: boolean
  optimizedPreferences: string[] | null
  optimizeError: string
  page: number
  totalPages: number
  paginatedPreferences: string[]
  paginatedStartIndex: number
  editingIndex: number | null
  editingValue: string
}>()

defineEmits<{
  toggle: []
  optimize: []
  'confirm-optimize': []
  'cancel-optimize': []
  'delete': [index: number]
  'start-edit': [index: number]
  'confirm-edit': []
  'cancel-edit': []
  'update:page': [page: number]
  'update:editing-value': [value: string]
}>()
</script>

<style scoped>
.preferences-overlay {
  position: fixed;
  inset: 0;
  z-index: 1000;
  display: grid;
  place-items: center;
  background: var(--color-overlay);
  padding: var(--space-md);
}

.preferences-modal {
  width: min(560px, 100%);
  max-height: 85vh;
  display: grid;
  gap: 16px;
  padding: 24px;
  overflow-y: auto;
}

.preferences-modal-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.section-kicker {
  margin: 0;
  font-size: 0.75rem;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--color-text-muted);
  font-weight: 600;
}

.preferences-modal-title {
  margin: 0;
  color: var(--color-text);
  font-size: 1rem;
}

.preferences-modal-close {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  border: none;
  border-radius: var(--radius-pill);
  background: transparent;
  color: var(--color-text-muted);
  font-size: 20px;
  line-height: 1;
  cursor: pointer;
  flex-shrink: 0;
  transition: color var(--duration-fast) var(--ease-out), background var(--duration-fast) var(--ease-out);
}

.preferences-modal-close:hover {
  color: var(--color-text);
  background: var(--surface-card);
}

.style-preferences-loading,
.style-preferences-empty p {
  margin: 0;
  color: var(--color-text-secondary);
  font-size: 0.85rem;
  line-height: 1.5;
}

.style-preferences-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 6px;
}

.style-preference-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border);
  background: var(--surface-page);
  font-size: 0.84rem;
  line-height: 1.5;
}

.preference-text { flex: 1; min-width: 0; color: var(--color-text); }

.preference-actions { display: flex; gap: 4px; flex-shrink: 0; }

.preference-action-btn {
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  border: none;
  border-radius: var(--radius-md);
  background: transparent;
  color: var(--color-text-muted);
  cursor: pointer;
  transition: color var(--duration-fast) var(--ease-out), background var(--duration-fast) var(--ease-out);
}

.preference-action-btn:hover { color: var(--color-text); background: var(--surface-card); }
.preference-delete-btn:hover { color: var(--color-danger); background: color-mix(in srgb, var(--color-danger) 8%, transparent); }

.preference-edit-input { flex: 1; min-width: 0; }

.optimize-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.preference-count-hint { color: var(--color-text-muted); font-size: 0.78rem; font-weight: 600; }

.error-text { margin: 0; color: var(--color-danger); font-size: 0.85rem; }

.preference-pagination {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  margin-top: 8px;
}

.preference-page-info { color: var(--color-text-muted); font-size: 0.78rem; font-weight: 600; }

/* Optimize diff */
.optimize-diff-header { display: grid; gap: 6px; }
.optimize-diff-title { margin: 0; color: var(--color-text); font-size: 0.9rem; font-weight: 600; }
.optimize-diff-section { display: grid; gap: 6px; }

.optimize-diff-label {
  margin: 0;
  font-size: 0.78rem;
  font-weight: 600;
  letter-spacing: 0.04em;
  text-transform: uppercase;
}

.optimize-diff-removed { color: var(--color-danger); }
.optimize-diff-added { color: var(--color-success); }

.optimize-diff-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 4px;
  max-height: 200px;
  overflow-y: auto;
}

.optimize-diff-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 6px 10px;
  border-radius: var(--radius-md);
  font-size: 0.84rem;
  line-height: 1.5;
}

.optimize-diff-item-old { background: color-mix(in srgb, var(--color-danger) 8%, transparent); color: var(--color-text-secondary); text-decoration: line-through; }
.optimize-diff-item-new { background: color-mix(in srgb, var(--color-success) 8%, transparent); color: var(--color-text); }

.optimize-diff-marker { flex-shrink: 0; font-weight: 700; width: 14px; text-align: center; }
.optimize-diff-item-old .optimize-diff-marker { color: var(--color-danger); }
.optimize-diff-item-new .optimize-diff-marker { color: var(--color-success); }

.optimize-diff-actions { display: flex; gap: 10px; flex-wrap: wrap; }

/* Shared button styles */
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

.btn-sm { min-height: 28px; padding: 0 10px; font-size: 0.78rem; }

.field-input-sm {
  border: 1px solid var(--color-border);
  background: var(--surface-muted);
  color: var(--color-text);
  font: inherit;
  min-height: 38px;
  padding: 0 10px;
  border-radius: var(--radius-md);
  transition: border-color var(--duration-fast) var(--ease-out), background var(--duration-fast) var(--ease-out), box-shadow var(--duration-fast) var(--ease-out);
}

.field-input-sm:focus {
  outline: none;
  border-color: var(--color-border-accent);
  background: var(--surface-card);
  box-shadow: var(--focus-ring);
}

.spin-icon { animation: spin 1s linear infinite; }
@keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
</style>
