<template>
  <div class="app-tooltip-wrap" @mouseenter="show = true" @mouseleave="show = false" @focusin="show = true" @focusout="show = false">
    <slot />
    <Transition name="tooltip">
      <div v-if="show" class="app-tooltip" :class="`app-tooltip--${position}`" role="tooltip">
        {{ text }}
      </div>
    </Transition>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'

defineProps<{
  text: string
  position?: 'top' | 'bottom' | 'left' | 'right'
}>()

const show = ref(false)
</script>

<style scoped>
.app-tooltip-wrap {
  position: relative;
  display: inline-flex;
}

.app-tooltip {
  position: absolute;
  z-index: 900;
  max-width: 240px;
  padding: 6px 10px;
  border-radius: 8px;
  background: var(--color-surface-strong);
  border: 1px solid var(--color-border);
  color: var(--color-text);
  font-size: 0.76rem;
  line-height: 1.4;
  white-space: normal;
  pointer-events: none;
  box-shadow: var(--shadow-elevated);
}

.app-tooltip--top {
  bottom: calc(100% + 8px);
  left: 50%;
  transform: translateX(-50%);
}

.app-tooltip--bottom {
  top: calc(100% + 8px);
  left: 50%;
  transform: translateX(-50%);
}

.app-tooltip--left {
  right: calc(100% + 8px);
  top: 50%;
  transform: translateY(-50%);
}

.app-tooltip--right {
  left: calc(100% + 8px);
  top: 50%;
  transform: translateY(-50%);
}

/* Transition */
.tooltip-enter-active,
.tooltip-leave-active {
  transition: opacity var(--duration-fast) var(--ease-out);
}
.tooltip-enter-from,
.tooltip-leave-to {
  opacity: 0;
}
</style>
