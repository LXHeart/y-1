<template>
  <Teleport to="body">
    <Transition name="modal">
      <div v-if="visible" class="app-modal-overlay" role="dialog" aria-modal="true" @click.self="handleOverlayClick">
        <div class="app-modal glass-card" :class="`app-modal--${width}`">
          <header v-if="$slots.header || title || kicker" class="app-modal__header">
            <slot name="header">
              <div>
                <p v-if="kicker" class="app-modal__kicker">{{ kicker }}</p>
                <h2 v-if="title" class="app-modal__title">{{ title }}</h2>
              </div>
            </slot>
            <button v-if="closable" class="app-modal__close" type="button" aria-label="关闭" @click="$emit('close')">
              <svg width="18" height="18" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                <path d="M4 4l8 8M12 4l-8 8" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
              </svg>
            </button>
          </header>
          <div class="app-modal__body">
            <slot />
          </div>
          <footer v-if="$slots.footer" class="app-modal__footer">
            <slot name="footer" />
          </footer>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
withDefaults(defineProps<{
  visible: boolean
  title?: string
  kicker?: string
  width?: 'sm' | 'md' | 'lg' | 'xl'
  closable?: boolean
  closeOnOverlay?: boolean
}>(), {
  width: 'md',
  closable: true,
  closeOnOverlay: true,
})

const emit = defineEmits<{ close: [] }>()

function handleOverlayClick(): void {
  // closeOnOverlay is checked by parent
  emit('close')
}
</script>

<style scoped>
.app-modal-overlay {
  position: fixed;
  inset: 0;
  z-index: 1000;
  display: grid;
  place-items: center;
  background: var(--color-overlay);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
  padding: var(--space-lg);
}

.app-modal {
  position: relative;
  width: 100%;
  max-height: calc(100vh - 48px);
  overflow-y: auto;
  scrollbar-width: thin;
}

.app-modal--sm { max-width: 440px; }
.app-modal--md { max-width: 600px; }
.app-modal--lg { max-width: 760px; }
.app-modal--xl { max-width: 960px; }

.app-modal__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-md);
  margin-bottom: var(--space-lg);
}

.app-modal__kicker {
  margin: 0 0 4px;
  font-size: 0.72rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  color: var(--color-accent-2);
}

.app-modal__title {
  margin: 0;
  font-size: 1.3rem;
  font-weight: 700;
  color: var(--color-text);
  letter-spacing: -0.02em;
  line-height: 1.2;
}

.app-modal__close {
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  border-radius: 10px;
  border: 1px solid var(--color-border);
  background: var(--surface-page);
  color: var(--color-text-muted);
  cursor: pointer;
  flex-shrink: 0;
  transition:
    background var(--duration-fast) var(--ease-out),
    color var(--duration-fast) var(--ease-out);
}

.app-modal__close:hover {
  background: var(--color-surface-hover);
  color: var(--color-text);
}

.app-modal__body {
  /* no default styles — consumers control their own layout */
}

.app-modal__footer {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--space-sm);
  margin-top: var(--space-lg);
  padding-top: var(--space-md);
  border-top: 1px solid var(--color-border);
}

/* Transition */
.modal-enter-active,
.modal-leave-active {
  transition: opacity var(--duration-normal) var(--ease-out);
}
.modal-enter-active .app-modal,
.modal-leave-active .app-modal {
  transition: transform var(--duration-normal) var(--ease-out);
}
.modal-enter-from,
.modal-leave-to {
  opacity: 0;
}
.modal-enter-from .app-modal {
  transform: translateY(16px) scale(0.97);
}
.modal-leave-to .app-modal {
  transform: translateY(8px) scale(0.98);
}
</style>
