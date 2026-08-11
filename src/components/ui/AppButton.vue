<template>
  <button
    class="app-btn"
    :class="[`app-btn--${variant}`, `app-btn--${size}`, { 'app-btn--loading': loading }]"
    :disabled="disabled || loading"
    :type="type"
  >
    <svg v-if="loading" class="app-btn__spinner" width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
      <circle cx="8" cy="8" r="6" stroke="currentColor" stroke-width="2"
        stroke-dasharray="28" stroke-dashoffset="10" stroke-linecap="round" />
    </svg>
    <slot />
  </button>
</template>

<script setup lang="ts">
defineProps<{
  variant?: 'primary' | 'secondary' | 'ghost' | 'danger'
  size?: 'sm' | 'md' | 'lg'
  loading?: boolean
  disabled?: boolean
  type?: 'button' | 'submit' | 'reset'
}>()
</script>

<style scoped>
.app-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-xs);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--surface-card);
  color: var(--color-text-secondary);
  cursor: pointer;
  font-weight: 500;
  letter-spacing: 0.01em;
  white-space: nowrap;
  transition:
    background var(--duration-fast) var(--ease-out),
    border-color var(--duration-fast) var(--ease-out),
    color var(--duration-fast) var(--ease-out),
    transform var(--duration-fast) var(--ease-out),
    box-shadow var(--duration-fast) var(--ease-out);
}

.app-btn:hover:not(:disabled) {
  background: var(--color-surface-hover);
  border-color: var(--color-border-hover);
  color: var(--color-text);
  transform: translateY(-1px);
  box-shadow: var(--shadow-glow);
}

.app-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* Sizes */
.app-btn--sm { min-height: 32px; padding: 0 12px; font-size: 0.78rem; border-radius: 8px; }
.app-btn--md { min-height: 38px; padding: 0 16px; font-size: 0.84rem; }
.app-btn--lg { min-height: 44px; padding: 0 24px; font-size: 0.92rem; border-radius: var(--radius-md); }

/* Primary */
.app-btn--primary {
  background: var(--gradient-accent);
  border: none;
  color: #ffffff;
  font-weight: 600;
  box-shadow: var(--shadow-glow);
}
.app-btn--primary:hover:not(:disabled) {
  box-shadow: var(--shadow-glow-strong);
  transform: translateY(-2px) scale(1.02);
  color: #ffffff;
}

/* Secondary — same as default, explicit for clarity */
.app-btn--secondary {
  background: var(--surface-card);
  border: 1px solid var(--color-border);
  color: var(--color-text-secondary);
}

/* Ghost */
.app-btn--ghost {
  background: transparent;
  border: 1px solid transparent;
  color: var(--color-text-muted);
}
.app-btn--ghost:hover:not(:disabled) {
  background: rgba(139, 92, 246, 0.06);
  border-color: var(--color-border);
  color: var(--color-text-secondary);
  box-shadow: none;
}

/* Danger */
.app-btn--danger {
  background: rgba(239, 107, 107, 0.1);
  border-color: rgba(239, 107, 107, 0.25);
  color: var(--color-danger);
}
.app-btn--danger:hover:not(:disabled) {
  background: rgba(239, 107, 107, 0.18);
  border-color: rgba(239, 107, 107, 0.4);
  color: var(--color-danger);
  box-shadow: 0 0 24px rgba(239, 107, 107, 0.15);
}

/* Loading */
.app-btn--loading { cursor: wait; }
.app-btn__spinner { animation: spin 0.8s linear infinite; }

@keyframes spin {
  to { transform: rotate(360deg); }
}
</style>
