<template>
  <Teleport to="body">
    <div
      v-if="images.length > 0 && previewIndex !== null"
      class="preview-overlay"
      role="dialog"
      aria-modal="true"
      tabindex="-1"
      @click="$emit('close')"
      @keydown="handleKeydown"
    >
      <img
        :src="images[previewIndex]?.preview"
        :alt="`图片 ${previewIndex + 1}`"
        class="preview-img"
        @click.stop
      />
      <button class="preview-close" type="button" aria-label="关闭预览" @click.stop="$emit('close')">&times;</button>
      <div v-if="images.length > 1" class="preview-nav">
        <button
          class="preview-nav-btn"
          type="button"
          :disabled="previewIndex <= 0"
          aria-label="上一张"
          @click.stop="$emit('navigate', Math.max(0, previewIndex - 1))"
        >&lsaquo;</button>
        <span class="preview-count">{{ previewIndex + 1 }} / {{ images.length }}</span>
        <button
          class="preview-nav-btn"
          type="button"
          :disabled="previewIndex >= images.length - 1"
          aria-label="下一张"
          @click.stop="$emit('navigate', Math.min(images.length - 1, previewIndex + 1))"
        >&rsaquo;</button>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
export interface LightboxImage {
  preview: string
}

defineProps<{
  images: LightboxImage[]
  previewIndex: number | null
}>()

const emit = defineEmits<{
  close: []
  navigate: [index: number]
}>()

function handleKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    emit('close')
  }
}
</script>

<style scoped>
.preview-overlay {
  position: fixed;
  inset: 0;
  z-index: 1100;
  display: grid;
  place-items: center;
  background: rgba(0, 0, 0, 0.85);
  padding: var(--space-md);
}

.preview-img {
  max-width: 90vw;
  max-height: 80vh;
  object-fit: contain;
  border-radius: var(--radius-lg);
  user-select: none;
}

.preview-close {
  position: absolute;
  top: 16px;
  right: 16px;
  width: 36px;
  height: 36px;
  display: grid;
  place-items: center;
  border-radius: 999px;
  border: 1px solid rgba(255, 255, 255, 0.15);
  background: rgba(0, 0, 0, 0.5);
  color: white;
  font-size: 20px;
  line-height: 1;
  cursor: pointer;
}

.preview-close:hover {
  background: rgba(0, 0, 0, 0.7);
}

.preview-nav {
  position: absolute;
  bottom: 24px;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  gap: 16px;
}

.preview-nav-btn {
  width: 36px;
  height: 36px;
  display: grid;
  place-items: center;
  border-radius: 999px;
  border: 1px solid rgba(255, 255, 255, 0.15);
  background: rgba(0, 0, 0, 0.5);
  color: white;
  font-size: 22px;
  line-height: 1;
  cursor: pointer;
}

.preview-nav-btn:hover:not(:disabled) {
  background: rgba(0, 0, 0, 0.7);
}

.preview-nav-btn:disabled {
  opacity: 0.3;
  cursor: not-allowed;
}

.preview-count {
  color: rgba(255, 255, 255, 0.7);
  font-size: 0.84rem;
  font-weight: 600;
}
</style>
