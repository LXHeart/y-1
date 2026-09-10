<template>
  <GlModal v-if="request" :title="request.title" @close="settle(false)">
    <div class="gl-field">
      <p class="confirm-dialog-message">{{ request.message }}</p>
    </div>
    <template #actions>
      <button ref="cancelRef" type="button" class="btn-cancel" data-action="confirm-dialog-cancel" @click="settle(false)">
        {{ request.cancelLabel }}
      </button>
      <button
        type="button"
        class="confirm-dialog-confirm"
        :class="{ 'confirm-dialog-danger': request.danger }"
        data-testid="confirm-dialog-confirm"
        @click="settle(true)"
      >{{ request.confirmLabel }}</button>
    </template>
  </GlModal>
</template>

<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import GlModal from './GlModal.vue'
import { useConfirmDialog } from '../composables/useConfirmDialog'

/**
 * 应用内确认弹窗的渲染壳（全局单例）：请求由 useConfirmDialog 模块级槽持有，
 * 本组件整个应用只挂一份（GrasslandWorkbench 根），不持业务状态。
 * 危险动作焦点落「取消」（安全侧默认）；按钮动词文案由调用方给全。
 */
const { request, settle } = useConfirmDialog()
const cancelRef = ref<HTMLButtonElement | null>(null)
watch(request, (value) => {
  if (value) void nextTick(() => cancelRef.value?.focus())
}, { flush: 'post' })
</script>

<style scoped>
.confirm-dialog-message {
  margin: 0;
  font-size: var(--text-sm);
  color: var(--color-text);
  line-height: 1.6;
  white-space: pre-line;
}

/* 弹窗 footer 在 .gl-field 作用域外（Teleport 到 body），按钮尺寸在此自足；
   与 .btn-cancel 同高（35px），实色主行动按 DESIGN.md v2（无渐变）。 */
.confirm-dialog-confirm {
  min-height: 35px;
  padding: 0 var(--space-md);
  border: none;
  border-radius: var(--radius-md);
  background: var(--color-accent);
  color: var(--color-on-accent);
  font-size: 0.86rem;
  font-weight: 600;
  cursor: pointer;
}
.confirm-dialog-confirm:hover:not(:disabled) { background: var(--color-primary-active); }
.confirm-dialog-confirm:disabled { opacity: 0.5; cursor: not-allowed; }
.confirm-dialog-danger { background: var(--color-danger); }
.confirm-dialog-danger:hover:not(:disabled) { background: color-mix(in srgb, var(--color-danger) 86%, #000000); }
</style>
