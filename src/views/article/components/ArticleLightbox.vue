<template>
  <Teleport to="body">
    <div v-if="src" class="lightbox-overlay" @click.self="$emit('close')">
      <button class="lightbox-close" type="button" @click="$emit('close')" aria-label="关闭">
        <svg width="20" height="20" viewBox="0 0 20 20" fill="none"><path d="M5 5l10 10M15 5L5 15" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/></svg>
      </button>
      <img :src="src" class="lightbox-img" alt="放大预览" @click.stop />
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted } from 'vue'

const props = defineProps<{
  src: string
}>()

const emit = defineEmits<{ close: [] }>()

function handleKeydown(e: KeyboardEvent): void {
  if (e.key === 'Escape' && props.src) {
    emit('close')
  }
}

onMounted(() => document.addEventListener('keydown', handleKeydown))
onBeforeUnmount(() => document.removeEventListener('keydown', handleKeydown))
</script>

<style scoped>
.lightbox-overlay {
  position: fixed;
  inset: 0;
  z-index: 9999;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0.75);
  backdrop-filter: blur(4px);
  cursor: zoom-out;
  animation: lightbox-in 0.15s ease;
}

@keyframes lightbox-in {
  from { opacity: 0; }
  to { opacity: 1; }
}

.lightbox-close {
  position: absolute;
  top: 16px;
  right: 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border: none;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.15);
  color: #fff;
  cursor: pointer;
  transition: background 0.15s ease;
}

.lightbox-close:hover {
  background: rgba(255, 255, 255, 0.3);
}

.lightbox-img {
  max-width: 90vw;
  max-height: 85vh;
  object-fit: contain;
  border-radius: var(--radius-md);
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.4);
  cursor: default;
}
</style>
