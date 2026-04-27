<template>
  <section class="recreation-panel">
    <div class="recreation-toolbar">
      <button
        type="button"
        class="btn-primary btn-sm"
        :disabled="allImagesLoading || scenes.length === 0"
        @click="handleGenerateAll"
      >
        {{ allImagesLoading ? '生成中…' : '一键生成全部参考图' }}
      </button>
      <button type="button" class="btn-secondary btn-sm" @click="handleCopy">
        {{ copied ? '已复制' : '复制完整脚本' }}
      </button>
    </div>

    <div v-if="overallStyle" class="style-summary">
      <span class="style-label">整体风格</span>
      <span class="style-text">{{ overallStyle }}</span>
    </div>

    <div class="scene-list">
      <div v-for="(scene, i) in scenes" :key="i" class="scene-card">
        <div class="scene-head">
          <span class="scene-index">场景 {{ i + 1 }} / {{ scenes.length }}</span>
        </div>

        <div class="scene-visual">
          <div v-if="getImageState(i)?.imageUrl" class="scene-image-wrap">
            <img
              :src="getImageState(i)!.imageUrl"
              :alt="scene.shotDescription"
              class="scene-image clickable-img"
              @click="openLightbox(getImageState(i)!.imageUrl!)"
            />
          </div>
          <div v-else-if="getImageState(i)?.loading" class="scene-image-placeholder">
            <span class="stream-dot"></span> 生成中…
          </div>
          <div v-else-if="getImageState(i)?.error" class="scene-image-placeholder scene-image-error">
            {{ getImageState(i)!.error }}
            <button type="button" class="btn-secondary btn-xs" @click="handleGenerateOne(i)">重试</button>
          </div>
          <button
            v-else
            type="button"
            class="scene-gen-btn"
            :disabled="allImagesLoading"
            @click="handleGenerateOne(i)"
          >
            生成参考图
          </button>
        </div>

        <div class="scene-fields">
          <div v-if="scene.shotDescription" class="scene-field">
            <span class="field-label">镜头</span>
            <span class="field-value">{{ scene.shotDescription }}</span>
          </div>
          <div v-if="scene.characterDescription" class="scene-field">
            <span class="field-label">人物</span>
            <span class="field-value">{{ scene.characterDescription }}</span>
          </div>
          <div v-if="scene.actionMovement" class="scene-field">
            <span class="field-label">动作</span>
            <span class="field-value">{{ scene.actionMovement }}</span>
          </div>
          <div v-if="scene.dialogueVoiceover" class="scene-field">
            <span class="field-label">对白</span>
            <span class="field-value">{{ scene.dialogueVoiceover }}</span>
          </div>
          <div v-if="scene.sceneEnvironment" class="scene-field">
            <span class="field-label">环境</span>
            <span class="field-value">{{ scene.sceneEnvironment }}</span>
          </div>
        </div>
      </div>
    </div>

    <Teleport to="body">
      <div v-if="lightboxSrc" class="lightbox-overlay" @click.self="closeLightbox">
        <button class="lightbox-close" type="button" @click="closeLightbox" aria-label="关闭">
          <svg width="20" height="20" viewBox="0 0 20 20" fill="none"><path d="M5 5l10 10M15 5L5 15" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/></svg>
        </button>
        <img :src="lightboxSrc" class="lightbox-img" alt="放大预览" @click.stop />
      </div>
    </Teleport>
  </section>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import type { VideoScene } from '../types/video-recreation'
import { useVideoRecreation } from '../composables/useVideoRecreation'

const props = defineProps<{
  scenes: VideoScene[]
  overallStyle?: string
}>()

const {
  sceneImages,
  allImagesLoading,
  generateSceneImage,
  generateAllImages,
  copyFullScript,
} = useVideoRecreation()

const copied = ref(false)
const lightboxSrc = ref('')

function getImageState(index: number) {
  return sceneImages.value.get(index)
}

function handleGenerateOne(index: number): void {
  generateSceneImage(index, props.scenes[index], props.overallStyle)
}

function handleGenerateAll(): void {
  generateAllImages(props.scenes, props.overallStyle)
}

async function handleCopy(): Promise<void> {
  const text = copyFullScript(props.scenes, props.overallStyle)
  try {
    await navigator.clipboard.writeText(text)
  } catch {
    const textarea = document.createElement('textarea')
    textarea.value = text
    textarea.style.cssText = 'position:fixed;opacity:0'
    document.body.appendChild(textarea)
    textarea.select()
    document.execCommand('copy')
    document.body.removeChild(textarea)
  }
  copied.value = true
  setTimeout(() => { copied.value = false }, 2000)
}

function openLightbox(src: string): void {
  lightboxSrc.value = src
}

function closeLightbox(): void {
  lightboxSrc.value = ''
}

function handleLightboxKey(e: KeyboardEvent): void {
  if (e.key === 'Escape' && lightboxSrc.value) closeLightbox()
}

onMounted(() => document.addEventListener('keydown', handleLightboxKey))
onBeforeUnmount(() => document.removeEventListener('keydown', handleLightboxKey))
</script>

<style scoped>
.recreation-panel {
  display: grid;
  gap: 16px;
}

.recreation-toolbar {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.style-summary {
  display: flex;
  gap: 8px;
  padding: 10px 14px;
  background: var(--color-surface);
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border);
  font-size: 0.85em;
}

.style-label {
  color: var(--color-text-secondary);
  white-space: nowrap;
}

.style-text {
  color: var(--color-text);
}

.scene-list {
  display: grid;
  gap: 12px;
}

.scene-card {
  display: grid;
  gap: 10px;
  padding: 16px;
  background: var(--color-surface);
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border);
}

.scene-head {
  display: flex;
  align-items: center;
}

.scene-index {
  font-weight: 600;
  font-size: 0.9em;
  color: var(--color-text);
}

.scene-visual {
  min-height: 120px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.scene-image-wrap {
  width: 100%;
}

.scene-image {
  width: 100%;
  max-height: 280px;
  object-fit: cover;
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border);
}

.scene-image-placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 24px;
  color: var(--color-text-secondary);
  font-size: 0.85em;
}

.scene-image-error {
  color: #e53e3e;
}

.scene-gen-btn {
  padding: 10px 20px;
  border: 1px dashed var(--color-border);
  border-radius: var(--radius-md);
  background: transparent;
  color: var(--color-text-secondary);
  cursor: pointer;
  font-size: 0.85em;
  transition: all 0.15s ease;
}

.scene-gen-btn:hover:not(:disabled) {
  border-color: var(--color-primary);
  color: var(--color-primary);
  background: color-mix(in srgb, var(--color-primary) 5%, transparent);
}

.scene-gen-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.scene-fields {
  display: grid;
  gap: 6px;
}

.scene-field {
  display: grid;
  grid-template-columns: 42px 1fr;
  gap: 6px;
  font-size: 0.85em;
  line-height: 1.6;
}

.field-label {
  color: var(--color-text-secondary);
  white-space: nowrap;
}

.field-value {
  color: var(--color-text);
}

.clickable-img {
  cursor: zoom-in;
  transition: opacity 0.15s ease;
}

.clickable-img:hover {
  opacity: 0.85;
}

.btn-xs {
  padding: 3px 8px;
  font-size: 0.78em;
}

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
