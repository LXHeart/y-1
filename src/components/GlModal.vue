<template>
  <Teleport to="body">
    <div class="modal-overlay" data-testid="gl-modal-overlay" @mousedown.self="onOverlay">
      <div class="modal-card" :class="{ 'modal-card--wide': wide }" role="dialog" aria-modal="true" :aria-label="title">
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
import { onBeforeUnmount, onMounted } from 'vue'

/** 哑组件：只管壳（Teleport/遮罩/Esc/宽窄档），确认逻辑与数据全在调用方。 */
const props = withDefaults(defineProps<{
  title: string
  wide?: boolean
  scroll?: boolean
  /** true 时点遮罩与 Esc 不关闭（防误触丢编辑中的表单，如 draft 单价表）。 */
  persistent?: boolean
}>(), { wide: false, scroll: false, persistent: false })

const emit = defineEmits<{ close: [] }>()

function onOverlay(): void {
  if (!props.persistent) emit('close')
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape' && !props.persistent) emit('close')
}

onMounted(() => window.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))
</script>
