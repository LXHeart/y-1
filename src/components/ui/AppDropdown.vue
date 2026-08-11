<template>
  <div ref="rootRef" class="app-dropdown" @keydown.esc="close">
    <div ref="triggerRef" class="app-dropdown__trigger" @click="toggle">
      <slot name="trigger">
        <button type="button" class="app-dropdown__default-trigger" :class="{ 'app-dropdown__default-trigger--open': open }">
          {{ label }}
          <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <path d="M4 6l4 4 4-4" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
        </button>
      </slot>
    </div>
    <Transition name="dropdown">
      <div v-if="open" class="app-dropdown__menu" :class="`app-dropdown__menu--${align}`" role="menu">
        <slot>
          <button
            v-for="item in items"
            :key="item.value"
            type="button"
            role="menuitem"
            class="app-dropdown__item"
            :class="{ 'app-dropdown__item--active': item.value === modelValue, 'app-dropdown__item--danger': item.danger }"
            @click="select(item.value)"
          >
            {{ item.label }}
          </button>
        </slot>
      </div>
    </Transition>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount } from 'vue'

export interface AppDropdownItem {
  label: string
  value: string
  danger?: boolean
}

defineProps<{
  label?: string
  items?: AppDropdownItem[]
  modelValue?: string
  align?: 'left' | 'right'
}>()

defineEmits<{ 'update:modelValue': [value: string] }>()

const open = ref(false)
const rootRef = ref<HTMLElement>()

function toggle(): void {
  open.value = !open.value
}

function close(): void {
  open.value = false
}

function select(value: string): void {
  close()
}

function onClickOutside(e: MouseEvent): void {
  if (rootRef.value && !rootRef.value.contains(e.target as Node)) {
    close()
  }
}

onMounted(() => {
  document.addEventListener('click', onClickOutside, true)
})

onBeforeUnmount(() => {
  document.removeEventListener('click', onClickOutside, true)
})
</script>

<style scoped>
.app-dropdown {
  position: relative;
  display: inline-flex;
}

.app-dropdown__trigger {
  display: inline-flex;
}

.app-dropdown__default-trigger {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 38px;
  padding: 0 14px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--surface-card);
  color: var(--color-text-secondary);
  cursor: pointer;
  font-size: 0.84rem;
  font-weight: 500;
  transition:
    background var(--duration-fast) var(--ease-out),
    border-color var(--duration-fast) var(--ease-out);
}

.app-dropdown__default-trigger:hover,
.app-dropdown__default-trigger--open {
  background: var(--color-surface-hover);
  border-color: var(--color-border-hover);
  color: var(--color-text);
}

.app-dropdown__menu {
  position: absolute;
  top: calc(100% + 6px);
  z-index: 800;
  min-width: 160px;
  padding: 6px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--surface-elevated);
  box-shadow: var(--shadow-elevated);
}

.app-dropdown__menu--left {
  left: 0;
}

.app-dropdown__menu--right {
  right: 0;
}

.app-dropdown__item {
  display: flex;
  width: 100%;
  align-items: center;
  padding: 8px 12px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--color-text-secondary);
  cursor: pointer;
  font-size: 0.84rem;
  text-align: left;
  transition:
    background var(--duration-fast) var(--ease-out),
    color var(--duration-fast) var(--ease-out);
}

.app-dropdown__item:hover {
  background: var(--color-surface-hover);
  color: var(--color-text);
}

.app-dropdown__item--active {
  color: var(--color-accent-2);
  font-weight: 600;
}

.app-dropdown__item--danger {
  color: var(--color-danger);
}

.app-dropdown__item--danger:hover {
  background: rgba(239, 107, 107, 0.1);
  color: var(--color-danger);
}

/* Transition */
.dropdown-enter-active,
.dropdown-leave-active {
  transition: opacity var(--duration-fast) var(--ease-out), transform var(--duration-fast) var(--ease-out);
}
.dropdown-enter-from,
.dropdown-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}
</style>
