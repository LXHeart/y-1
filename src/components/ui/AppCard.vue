<template>
  <section class="app-card glass-card" :class="{ 'app-card--hoverable': hoverable, 'app-card--flat': flat }">
    <header v-if="$slots.header || title" class="app-card__header">
      <slot name="header">
        <div>
          <p v-if="kicker" class="app-card__kicker">{{ kicker }}</p>
          <h3 v-if="title" class="app-card__title">{{ title }}</h3>
        </div>
      </slot>
      <div v-if="$slots.actions" class="app-card__actions">
        <slot name="actions" />
      </div>
    </header>
    <div class="app-card__body">
      <slot />
    </div>
    <footer v-if="$slots.footer" class="app-card__footer">
      <slot name="footer" />
    </footer>
  </section>
</template>

<script setup lang="ts">
defineProps<{
  title?: string
  kicker?: string
  hoverable?: boolean
  flat?: boolean
}>()
</script>

<style scoped>
.app-card {
  position: relative;
}

.app-card--flat {
  background: var(--surface-page);
  border: 1px solid var(--color-border);
  box-shadow: none;
  backdrop-filter: none;
  -webkit-backdrop-filter: none;
}

.app-card--hoverable {
  transition:
    transform var(--duration-normal) var(--ease-out),
    box-shadow var(--duration-normal) var(--ease-out),
    border-color var(--duration-normal) var(--ease-out);
}

.app-card--hoverable:hover {
  transform: translateY(-2px);
  box-shadow: var(--shadow-elevated);
  border-color: var(--color-border-accent);
}

.app-card__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-md);
  margin-bottom: var(--space-md);
}

.app-card__kicker {
  margin: 0 0 4px;
  font-size: 0.72rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  color: var(--color-accent-2);
}

.app-card__title {
  margin: 0;
  font-size: 1.1rem;
  font-weight: 700;
  color: var(--color-text);
  letter-spacing: -0.01em;
  line-height: 1.3;
}

.app-card__actions {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  flex-shrink: 0;
}

.app-card__body {
  /* no default styles — consumers control layout */
}

.app-card__footer {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  margin-top: var(--space-md);
  padding-top: var(--space-md);
  border-top: 1px solid var(--color-border);
}
</style>
