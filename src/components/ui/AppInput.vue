<template>
  <label class="app-input-field" :class="{ 'app-input-field--error': !!error, 'app-input-field--disabled': disabled }">
    <span v-if="label" class="app-input-field__label">{{ label }}</span>
    <div class="app-input-field__wrap">
      <svg v-if="$slots.icon" class="app-input-field__icon" width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true">
        <slot name="icon" />
      </svg>
      <input
        ref="inputRef"
        class="app-input-field__input"
        :class="{ 'app-input-field__input--has-icon': !!$slots.icon }"
        :type="type"
        :value="modelValue"
        :placeholder="placeholder"
        :disabled="disabled"
        :readonly="readonly"
        :maxlength="maxlength"
        :min="min"
        :max="max"
        @input="$emit('update:modelValue', ($event.target as HTMLInputElement).value)"
        @blur="$emit('blur', $event)"
        @focus="$emit('focus', $event)"
      />
    </div>
    <span v-if="error" class="app-input-field__error">{{ error }}</span>
    <span v-if="hint && !error" class="app-input-field__hint">{{ hint }}</span>
  </label>
</template>

<script setup lang="ts">
import { ref } from 'vue'

defineProps<{
  modelValue?: string | number
  label?: string
  type?: string
  placeholder?: string
  disabled?: boolean
  readonly?: boolean
  error?: string
  hint?: string
  maxlength?: number | string
  min?: number | string
  max?: number | string
}>()

defineEmits<{
  'update:modelValue': [value: string]
  blur: [event: FocusEvent]
  focus: [event: FocusEvent]
}>()

const inputRef = ref<HTMLInputElement>()

function focus(): void {
  inputRef.value?.focus()
}

defineExpose({ focus })
</script>

<style scoped>
.app-input-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.app-input-field__label {
  font-size: 0.82rem;
  font-weight: 600;
  color: var(--color-text-secondary);
  letter-spacing: 0.01em;
}

.app-input-field__wrap {
  position: relative;
  display: flex;
  align-items: center;
}

.app-input-field__icon {
  position: absolute;
  left: 12px;
  color: var(--color-text-muted);
  pointer-events: none;
}

.app-input-field__input {
  width: 100%;
  min-height: 38px;
  padding: 0 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--surface-card);
  color: var(--color-text);
  font-size: 0.88rem;
  transition:
    border-color var(--duration-fast) var(--ease-out),
    box-shadow var(--duration-fast) var(--ease-out);
}

.app-input-field__input--has-icon {
  padding-left: 36px;
}

.app-input-field__input::placeholder {
  color: var(--color-text-muted);
}

.app-input-field__input:focus {
  outline: none;
  border-color: var(--color-accent);
  box-shadow: var(--focus-ring);
}

.app-input-field--error .app-input-field__input {
  border-color: var(--color-danger);
}

.app-input-field--error .app-input-field__input:focus {
  box-shadow: 0 0 0 3px rgba(239, 107, 107, 0.2);
}

.app-input-field--disabled {
  opacity: 0.6;
  pointer-events: none;
}

.app-input-field__error {
  font-size: 0.76rem;
  color: var(--color-danger);
}

.app-input-field__hint {
  font-size: 0.76rem;
  color: var(--color-text-muted);
}
</style>
