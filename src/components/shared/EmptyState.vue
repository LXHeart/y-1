<template>
  <section class="app-empty-state" :class="{ 'app-empty-state--card': card }">
    <div v-if="$slots.icon" class="app-empty-state__icon" aria-hidden="true">
      <slot name="icon" />
    </div>
    <p v-if="kicker" class="app-empty-state__kicker">{{ kicker }}</p>
    <h3 v-if="title" class="app-empty-state__title">{{ title }}</h3>
    <p v-if="description" class="app-empty-state__desc">{{ description }}</p>
    <div v-if="$slots.actions" class="app-empty-state__actions">
      <slot name="actions" />
    </div>
  </section>
</template>

<script setup lang="ts">
defineProps<{
  title?: string
  description?: string
  kicker?: string
  card?: boolean
}>()
</script>

<style scoped>
.app-empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  gap: var(--space-sm);
  padding: var(--space-xl) var(--space-lg);
}

.app-empty-state--card {
  border-radius: var(--radius-xl);
  background: var(--surface-card);
  border: 1px solid var(--color-border);
  box-shadow: var(--shadow-card);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
}

.app-empty-state__icon {
  display: grid;
  place-items: center;
  width: 64px;
  height: 64px;
  border-radius: 50%;
  background: var(--color-surface-highlight);
  color: var(--color-accent-2);
  margin-bottom: var(--space-xs);
}

.app-empty-state__kicker {
  margin: 0;
  font-size: 0.72rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  color: var(--color-accent-2);
}

.app-empty-state__title {
  margin: 0;
  font-size: 1.1rem;
  font-weight: 700;
  color: var(--color-text);
  letter-spacing: -0.01em;
}

.app-empty-state__desc {
  margin: 0;
  max-width: 360px;
  font-size: 0.88rem;
  color: var(--color-text-muted);
  line-height: 1.5;
}

.app-empty-state__actions {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  margin-top: var(--space-sm);
}
</style>
