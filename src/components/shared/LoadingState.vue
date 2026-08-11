<template>
  <div class="app-loading-state" :class="{ 'app-loading-state--card': card }" role="status" aria-live="polite">
    <svg class="app-loading-state__spinner" width="28" height="28" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="2"
        stroke-dasharray="42" stroke-dashoffset="14" stroke-linecap="round" />
    </svg>
    <p v-if="text" class="app-loading-state__text">{{ text }}</p>
    <slot />
  </div>
</template>

<script setup lang="ts">
defineProps<{
  text?: string
  card?: boolean
}>()
</script>

<style scoped>
.app-loading-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-sm);
  padding: var(--space-xl) 0;
  color: var(--color-text-muted);
}

.app-loading-state--card {
  padding: var(--space-xl);
  border-radius: var(--radius-xl);
  background: var(--surface-card);
  border: 1px solid var(--color-border);
}

.app-loading-state__spinner {
  animation: app-loading-spin 0.9s linear infinite;
}

.app-loading-state__text {
  margin: 0;
  font-size: 0.88rem;
}

@keyframes app-loading-spin {
  to { transform: rotate(360deg); }
}
</style>
