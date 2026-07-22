<template>
  <div class="video-production">
    <nav class="steps-bar" aria-label="制作步骤">
      <div
        v-for="(s, i) in steps"
        :key="s.key"
        class="step-dot"
        :class="{
          'step-active': stage === s.key,
          'step-done': stepIndex(stage) > i,
        }"
      >
        <span class="step-num">{{ i + 1 }}</span>
        <span class="step-label">{{ s.label }}</span>
      </div>
    </nav>

    <!-- Step 1: Upload -->
    <section v-if="stage === 'upload'" class="stage-card glass-card fade-in">
      <header class="card-head">
        <p class="eyebrow">第一步</p>
        <h2 class="card-title">上传素材 & 填写店铺信息</h2>
        <p class="field-note">上传 1-9 张店铺/产品照片，填写基本信息后生成推广脚本。</p>
      </header>

      <div class="upload-area">
        <input
          ref="fileInput"
          type="file"
          accept="image/*"
          multiple
          class="hidden-input"
          @change="handleFileSelect"
        />
        <label
          class="drop-zone"
          :class="{ 'drop-zone-active': isDragging }"
          @dragover.prevent="isDragging = true"
          @dragleave="isDragging = false"
          @drop.prevent="handleDrop"
          @click="($refs.fileInput as HTMLInputElement)?.click()"
        >
          <svg width="32" height="32" viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <path d="M12 5v14M5 12l7-7 7 7" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
          <span>拖拽图片到此处，或点击上传</span>
          <span class="field-note">{{ images.length }} / {{ MAX_IMAGES }} 张</span>
        </label>
      </div>

      <div v-if="images.length > 0" class="preview-grid">
        <div
          v-for="(img, idx) in images"
          :key="img.id"
          class="preview-item"
          draggable="true"
          @dragstart="dragIndex = idx"
          @dragover.prevent
          @drop="onDropReorder(idx)"
        >
          <img :src="img.dataUrl" :alt="img.name" class="preview-thumb" @click="openLightbox(img.dataUrl)" />
          <button type="button" class="preview-remove" @click="removeImage(img.id)" aria-label="删除图片">&times;</button>
          <span class="preview-order">{{ idx + 1 }}</span>
        </div>
      </div>

      <div class="form-grid">
        <div class="form-field">
          <label for="vp-shop-name">店铺名称 *</label>
          <input id="vp-shop-name" v-model="form.shopName" type="text" placeholder="例如：老王面馆" />
        </div>
        <div class="form-field">
          <label for="vp-industry">行业类型 *</label>
          <select id="vp-industry" v-model="form.industryType">
            <option v-for="t in industryTypes" :key="t" :value="t">{{ t }}</option>
          </select>
        </div>
        <div class="form-field">
          <label for="vp-address">店铺地址</label>
          <input id="vp-address" v-model="form.shopAddress" type="text" placeholder="选填" />
        </div>
        <div class="form-field">
          <label for="vp-style">视频风格 *</label>
          <select id="vp-style" v-model="form.videoStyle">
            <option v-for="s in videoStyles" :key="s" :value="s">{{ s }}</option>
          </select>
        </div>
        <div class="form-field form-field-wide">
          <label for="vp-desc">店铺简介</label>
          <textarea id="vp-desc" v-model="form.shopDescription" rows="2" placeholder="简短描述店铺特色（选填）"></textarea>
        </div>
        <div class="form-field form-field-wide">
          <label for="vp-prompt">自定义要求</label>
          <textarea id="vp-prompt" v-model="form.customPrompt" rows="2" placeholder="对视频脚本有什么特殊要求？（选填）"></textarea>
        </div>
      </div>

      <div v-if="error" class="error-hint">{{ error }}</div>

      <div class="action-row">
        <button
          class="btn-primary"
          :disabled="!canProceedToScript || scriptLoading"
          @click="generateScript"
        >
          {{ scriptLoading ? '生成中…' : '生成脚本' }}
        </button>
      </div>
    </section>

    <!-- Step 2: Script Editing -->
    <section v-if="stage === 'script'" class="stage-card glass-card fade-in">
      <header class="card-head">
        <div class="card-head-row">
          <button class="btn-back" type="button" @click="goBackToUpload">
            <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
              <path d="M10 3L5 8l5 5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
            返回修改
          </button>
          <p class="eyebrow">第二步</p>
        </div>
        <h2 class="card-title">编辑推广脚本</h2>
        <p class="field-note">AI 根据素材和信息生成脚本，你可以自由编辑修改。</p>
      </header>

      <div class="script-thumbnails">
        <img
          v-for="img in images"
          :key="img.id"
          :src="img.dataUrl"
          :alt="img.name"
          class="script-thumb"
          @click="openLightbox(img.dataUrl)"
        />
      </div>

      <div class="stream-area">
        <textarea
          v-model="script"
          class="stream-textarea"
          :class="{ 'stream-loading': scriptLoading }"
          placeholder="脚本会在这里实时生成..."
          rows="16"
        ></textarea>
        <div v-if="scriptLoading" class="stream-badge">
          <span class="stream-dot"></span>
          生成中
        </div>
      </div>

      <div v-if="error" class="error-hint">{{ error }}</div>

      <div class="action-row">
        <button
          class="btn-secondary"
          :disabled="scriptLoading"
          @click="generateScript"
        >
          {{ scriptLoading ? '生成中…' : '重新生成' }}
        </button>
        <button
          class="btn-primary"
          :disabled="scriptLoading || !script.trim()"
          @click="startVideoGeneration"
        >
          生成视频
        </button>
      </div>
    </section>

    <!-- Step 3: Video Generation -->
    <section v-if="stage === 'generate'" class="stage-card glass-card fade-in">
      <header class="card-head">
        <div class="card-head-row">
          <button class="btn-back" type="button" @click="goBackToScript">
            <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
              <path d="M10 3L5 8l5 5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
            返回脚本
          </button>
          <p class="eyebrow">第三步</p>
        </div>
        <h2 class="card-title">生成视频</h2>
      </header>

      <div v-if="videoLoading" class="progress-area">
        <div class="progress-bar-track">
          <div class="progress-bar-fill" :style="{ width: videoProgress + '%' }"></div>
        </div>
        <p class="field-note">{{ videoProgress }}% — 视频生成中，请耐心等待…</p>
      </div>

      <div v-else-if="videoUrl" class="result-area">
        <video :src="videoUrl" controls class="result-video"></video>
        <div class="action-row">
          <a :href="videoUrl" download class="btn-primary" target="_blank">下载视频</a>
          <button class="btn-secondary" @click="reset">新建视频</button>
        </div>
      </div>

      <div v-else-if="error" class="result-area">
        <p class="error-hint">{{ error }}</p>
        <div class="action-row">
          <button class="btn-primary" @click="startVideoGeneration">重试</button>
          <button class="btn-secondary" @click="goBackToScript">返回脚本</button>
        </div>
      </div>

      <div v-else class="result-area">
        <p class="field-note">视频生成服务即将上线，敬请期待！</p>
        <div class="action-row">
          <button class="btn-secondary" @click="goBackToScript">返回脚本</button>
          <button class="btn-secondary" @click="reset">新建视频</button>
        </div>
      </div>
    </section>

    <!-- Lightbox -->
    <Teleport to="body">
      <div v-if="lightboxSrc" class="lightbox-overlay" @click="closeLightbox">
        <img :src="lightboxSrc" class="lightbox-img" @click.stop />
        <button class="lightbox-close" @click="closeLightbox" aria-label="关闭">&times;</button>
      </div>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useVideoProduction } from '../composables/useVideoProduction'
import type { IndustryType, VideoStyle } from '../types/video-production'

const {
  stage, images, form, script, videoUrl,
  scriptLoading, videoLoading, videoProgress, error,
  canProceedToScript,
  addImages, removeImage, reorderImage,
  generateScript, startVideoGeneration,
  goBackToUpload, goBackToScript,
  reset,
} = useVideoProduction()

const MAX_IMAGES = 9

const steps = [
  { key: 'upload' as const, label: '上传素材' },
  { key: 'script' as const, label: '编辑脚本' },
  { key: 'generate' as const, label: '生成视频' },
]

const industryTypes: IndustryType[] = ['餐饮', '零售', '美业', '健身', '教育培训', '其他']
const videoStyles: VideoStyle[] = ['烟火纪实', '治愈清新', '高级暗调', '数字人口播', '复古胶片']

const isDragging = ref(false)
const dragIndex = ref<number | null>(null)
const lightboxSrc = ref('')

function stepIndex(s: typeof stage.value): number {
  return steps.findIndex((step) => step.key === s)
}

function handleFileSelect(event: Event): void {
  const input = event.target as HTMLInputElement
  if (input.files) {
    addImages(Array.from(input.files))
    input.value = ''
  }
}

function handleDrop(event: DragEvent): void {
  isDragging.value = false
  if (event.dataTransfer?.files) {
    addImages(Array.from(event.dataTransfer.files))
  }
}

function onDropReorder(toIndex: number): void {
  if (dragIndex.value !== null && dragIndex.value !== toIndex) {
    reorderImage(dragIndex.value, toIndex)
  }
  dragIndex.value = null
}

function openLightbox(src: string): void {
  lightboxSrc.value = src
}

function closeLightbox(): void {
  lightboxSrc.value = ''
}
</script>

<style scoped>
.video-production {
  max-width: 800px;
  margin: 0 auto;
  padding: 24px 16px;
}

.steps-bar {
  display: flex;
  justify-content: center;
  gap: 32px;
  margin-bottom: 24px;
}

.step-dot {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  opacity: 0.4;
  transition: opacity 0.3s;
}

.step-active {
  opacity: 1;
}

.step-done {
  opacity: 0.7;
}

.step-num {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  font-weight: 600;
  background: rgba(255, 255, 255, 0.1);
  border: 1px solid rgba(255, 255, 255, 0.15);
}

.step-active .step-num {
  background: var(--color-accent, #6366f1);
  border-color: var(--color-accent, #6366f1);
  color: #fff;
}

.step-done .step-num {
  background: rgba(34, 197, 94, 0.2);
  border-color: rgba(34, 197, 94, 0.4);
}

.step-label {
  font-size: 12px;
  color: var(--color-text-muted, #888);
}

.upload-area {
  margin-bottom: 16px;
}

.hidden-input {
  display: none;
}

.drop-zone {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 32px;
  border: 2px dashed rgba(255, 255, 255, 0.15);
  border-radius: 12px;
  cursor: pointer;
  transition: border-color 0.2s, background 0.2s;
  color: var(--color-text-muted, #888);
  font-size: 14px;
}

.drop-zone:hover,
.drop-zone-active {
  border-color: var(--color-accent, #6366f1);
  background: rgba(99, 102, 241, 0.05);
}

.preview-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 16px;
}

.preview-item {
  position: relative;
  width: 72px;
  height: 72px;
  border-radius: 8px;
  overflow: hidden;
  cursor: grab;
}

.preview-thumb {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.preview-remove {
  position: absolute;
  top: 2px;
  right: 2px;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.6);
  color: #fff;
  border: none;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  line-height: 1;
}

.preview-order {
  position: absolute;
  bottom: 2px;
  left: 2px;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.5);
  color: #fff;
  font-size: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  margin-bottom: 16px;
}

.form-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.form-field label {
  font-size: 13px;
  color: var(--color-text-muted, #888);
}

.form-field input,
.form-field select,
.form-field textarea {
  padding: 8px 12px;
  border-radius: 8px;
  border: 1px solid rgba(255, 255, 255, 0.12);
  background: rgba(255, 255, 255, 0.05);
  color: inherit;
  font-size: 14px;
  font-family: inherit;
}

.form-field input:focus,
.form-field select:focus,
.form-field textarea:focus {
  outline: none;
  border-color: var(--color-accent, #6366f1);
}

.form-field-wide {
  grid-column: 1 / -1;
}

.script-thumbnails {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
  overflow-x: auto;
}

.script-thumb {
  width: 56px;
  height: 56px;
  object-fit: cover;
  border-radius: 6px;
  cursor: pointer;
  flex-shrink: 0;
}

.stream-area {
  position: relative;
  margin-bottom: 16px;
}

.stream-textarea {
  width: 100%;
  padding: 12px;
  border-radius: 8px;
  border: 1px solid rgba(255, 255, 255, 0.12);
  background: rgba(255, 255, 255, 0.05);
  color: inherit;
  font-size: 14px;
  font-family: inherit;
  line-height: 1.6;
  resize: vertical;
}

.stream-textarea:focus {
  outline: none;
  border-color: var(--color-accent, #6366f1);
}

.stream-loading {
  border-color: var(--color-accent, #6366f1);
}

.stream-badge {
  position: absolute;
  top: 8px;
  right: 12px;
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--color-accent, #6366f1);
}

.stream-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--color-accent, #6366f1);
  animation: pulse 1.2s ease-in-out infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.3; }
}

.progress-area {
  margin-bottom: 16px;
}

.progress-bar-track {
  height: 6px;
  border-radius: 3px;
  background: rgba(255, 255, 255, 0.1);
  overflow: hidden;
  margin-bottom: 8px;
}

.progress-bar-fill {
  height: 100%;
  border-radius: 3px;
  background: var(--color-accent, #6366f1);
  transition: width 0.3s ease;
}

.result-area {
  text-align: center;
}

.result-video {
  width: 100%;
  max-width: 480px;
  border-radius: 12px;
  margin-bottom: 16px;
}

.error-hint {
  color: #f87171;
  font-size: 13px;
  margin-bottom: 12px;
}

.action-row {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
}

.btn-primary {
  padding: 8px 20px;
  border-radius: 8px;
  border: none;
  background: var(--color-accent, #6366f1);
  color: #fff;
  font-size: 14px;
  cursor: pointer;
  transition: opacity 0.2s;
}

.btn-primary:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.btn-primary:hover:not(:disabled) {
  opacity: 0.85;
}

.btn-secondary {
  padding: 8px 20px;
  border-radius: 8px;
  border: 1px solid rgba(255, 255, 255, 0.15);
  background: transparent;
  color: inherit;
  font-size: 14px;
  cursor: pointer;
  transition: opacity 0.2s;
}

.btn-secondary:hover {
  opacity: 0.85;
  background: rgba(255, 255, 255, 0.05);
}

.btn-back {
  display: flex;
  align-items: center;
  gap: 4px;
  background: none;
  border: none;
  color: var(--color-text-muted, #888);
  font-size: 13px;
  cursor: pointer;
  padding: 0;
}

.btn-back:hover {
  color: inherit;
}

.card-head-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.card-head {
  margin-bottom: 16px;
}

.eyebrow {
  font-size: 12px;
  color: var(--color-accent, #6366f1);
  text-transform: uppercase;
  letter-spacing: 0.5px;
  margin-bottom: 4px;
}

.card-title {
  font-size: 18px;
  font-weight: 600;
  margin-bottom: 4px;
}

.field-note {
  font-size: 13px;
  color: var(--color-text-muted, #888);
}

.lightbox-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.85);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  cursor: pointer;
}

.lightbox-img {
  max-width: 90vw;
  max-height: 90vh;
  border-radius: 8px;
  cursor: default;
}

.lightbox-close {
  position: absolute;
  top: 16px;
  right: 16px;
  width: 36px;
  height: 36px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.1);
  color: #fff;
  border: none;
  font-size: 20px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
}

.fade-in {
  animation: fadeIn 0.3s ease;
}

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(8px); }
  to { opacity: 1; transform: translateY(0); }
}
</style>
