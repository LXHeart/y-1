<template>
  <nav class="app-tabs-bar" role="tablist" :aria-label="ariaLabel">
    <button
      v-for="tab in tabs"
      :key="tab.value"
      type="button"
      role="tab"
      :aria-selected="modelValue === tab.value"
      class="app-tab"
      :class="{ 'app-tab--active': modelValue === tab.value }"
      @click="$emit('update:modelValue', tab.value)"
    >
      {{ tab.label }}
      <span v-if="tab.count !== undefined && tab.count > 0" class="app-tab__badge">{{ tab.count }}</span>
    </button>
  </nav>
</template>

<script setup lang="ts">
export interface AppTabItem {
  label: string
  value: string
  count?: number
}

defineProps<{
  tabs: AppTabItem[]
  modelValue: string
  ariaLabel?: string
}>()

defineEmits<{ 'update:modelValue': [value: string] }>()
</script>

<style scoped>
.app-tab-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  padding: 4px;
  border-radius: var(--radius-md);
  background: var(--surface-page);
  border: 1px solid var(--color-border);
}

.app-tab {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 36px;
  padding: 0 14px;
  border: 0;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--color-text-muted);
  cursor: pointer;
  font-size: 0.84rem;
  font-weight: 500;
  transition:
    background var(--duration-fast) var(--ease-out),
    color var(--duration-fast) var(--ease-out),
    box-shadow var(--duration-fast) var(--ease-out);
}

.app-tab:hover {
  color: var(--color-text-secondary);
  background: var(--color-surface-hover);
}

.app-tab--active {
  background: var(--gradient-accent);
  color: #ffffff;
  font-weight: 600;
  box-shadow: 0 4px 16px rgba(139, 92, 246, 0.3);
}

.app-tab--active:hover {
  color: #ffffff;
}

.app-tab__badge {
  display: inline-flex;
  min-width: 20px;
  height: 20px;
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.18);
  font-size: 0.72rem;
  font-weight: 600;
  padding: 0 6px;
}

.app-tab--active .app-tab__badge {
  background: rgba(255, 255, 255, 0.25);
}
</style>
