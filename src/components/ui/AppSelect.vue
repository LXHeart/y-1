<template>
  <label class="app-select-field" :class="{ 'app-select-field--error': !!error, 'app-select-field--disabled': disabled }">
    <span v-if="label" class="app-select-field__label">{{ label }}</span>
    <div class="app-select-field__wrap">
      <select
        class="app-select-field__select"
        :value="modelValue"
        :disabled="disabled"
        @change="$emit('update:modelValue', ($event.target as HTMLSelectElement).value)"
      >
        <option v-if="placeholder" value="" disabled>{{ placeholder }}</option>
        <slot>
          <option v-for="opt in options" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
        </slot>
      </select>
      <svg class="app-select-field__chevron" width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
        <path d="M4 6l4 4 4-4" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
      </svg>
    </div>
    <span v-if="error" class="app-select-field__error">{{ error }}</span>
    <span v-if="hint && !error" class="app-select-field__hint">{{ hint }}</span>
  </label>
</template>

<script setup lang="ts">
export interface AppSelectOption {
  label: string
  value: string
}

defineProps<{
  modelValue?: string
  label?: string
  placeholder?: string
  options?: AppSelectOption[]
  disabled?: boolean
  error?: string
  hint?: string
}>()

defineEmits<{ 'update:modelValue': [value: string] }>()
</script>

<style scoped>
.app-select-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.app-select-field__label {
  font-size: 0.82rem;
  font-weight: 600;
  color: var(--color-text-secondary);
  letter-spacing: 0.01em;
}

.app-select-field__wrap {
  position: relative;
  display: flex;
  align-items: center;
}

.app-select-field__select {
  width: 100%;
  min-height: 38px;
  padding: 0 36px 0 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--surface-card);
  color: var(--color-text);
  font-size: 0.88rem;
  appearance: none;
  cursor: pointer;
  transition:
    border-color var(--duration-fast) var(--ease-out),
    box-shadow var(--duration-fast) var(--ease-out);
}

.app-select-field__select:focus {
  outline: none;
  border-color: var(--color-accent);
  box-shadow: var(--focus-ring);
}

.app-select-field__chevron {
  position: absolute;
  right: 12px;
  color: var(--color-text-muted);
  pointer-events: none;
}

.app-select-field--error .app-select-field__select {
  border-color: var(--color-danger);
}

.app-select-field--disabled {
  opacity: 0.6;
  pointer-events: none;
}

.app-select-field__error {
  font-size: 0.76rem;
  color: var(--color-danger);
}

.app-select-field__hint {
  font-size: 0.76rem;
  color: var(--color-text-muted);
}
</style>
