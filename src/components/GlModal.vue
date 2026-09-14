<template>
  <Teleport to="body">
    <div class="modal-overlay" data-testid="gl-modal-overlay" @mousedown.self="onOverlay">
      <div ref="dialog" class="modal-card" :class="{ 'modal-card--wide': wide, 'modal-card--managed': trapFocus, 'studio-panel': trapFocus }" role="dialog" aria-modal="true" :aria-label="title" :tabindex="trapFocus ? -1 : undefined">
        <header class="modal-header">
          <h3 class="modal-title">{{ title }}</h3>
          <button type="button" class="modal-close" aria-label="关闭弹窗" data-action="close-modal"
                  @click="emit('close')">×</button>
        </header>
        <div class="modal-body" :class="{ 'modal-body--scroll': scroll }">
          <slot />
        </div>
        <footer v-if="$slots.actions" class="modal-actions modal-card__footer">
          <slot name="actions" />
        </footer>
      </div>
    </div>
  </Teleport>
</template>
<script setup lang="ts">
import { ref } from 'vue'
import { useDialogFocus } from '../composables/useDialogFocus'

/** Shared modal shell; business decisions stay with the caller. */
const props = withDefaults(defineProps<{
  title: string
  wide?: boolean
  scroll?: boolean
  /** Keep a pending/unsaved flow open on Escape or backdrop clicks. */
  persistent?: boolean
  /** Custom hosts can opt out while retaining their own focus manager. */
  trapFocus?: boolean
}>(), { wide: false, scroll: false, persistent: false, trapFocus: true })

const emit = defineEmits<{ close: [] }>()
const dialog = ref<HTMLElement | null>(null)
useDialogFocus(dialog, {
  close: () => emit('close'),
  persistent: () => props.persistent,
  trapFocus: () => props.trapFocus,
})
function onOverlay(): void {
  if (!props.persistent) emit('close')
}
</script>
