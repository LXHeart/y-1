<script setup lang="ts">
import { CAMERA_MOVES } from '../../../types/video-production'

interface Shot {
  seq: number
  visual: string
  narration: string
  cameraMove: string
  anchorImageIndex: number
  plannedSeconds: number
  anchorUrl?: string | null
  id?: string
}

interface Image {
  id: string
  dataUrl: string
}

interface Props {
  shot: Shot
  index: number
  images: Image[]
  anchorGenerating: Record<string, boolean>
  anchorErrors: Record<string, string>
  storyboardLoading: boolean
}

defineProps<Props>()

const cameraMoves = CAMERA_MOVES

const emit = defineEmits<{
  'update-shot': [index: number, updates: Partial<Shot>]
  'remove-shot': [index: number]
  'open-lightbox': [url: string]
  'generate-anchor': [shotId: string]
}>()
</script>

<template>
  <div
    class="shot-card gl-tile"
    data-test="shot-card"
  >
    <div class="shot-head">
      <span class="shot-badge">第 {{ shot.seq }} 镜</span>
      <span class="field-note">{{ shot.plannedSeconds }} 秒</span>
      <button
        type="button"
        class="preview-remove"
        :aria-label="`删除第 ${shot.seq} 镜`"
        @click="emit('remove-shot', index)"
      >&times;</button>
    </div>
    <div class="shot-grid">
      <div class="form-field">
        <label :for="`shot-visual-${shot.seq}`">画面描述</label>
        <textarea
          :id="`shot-visual-${shot.seq}`"
          rows="2"
          :value="shot.visual"
          @change="emit('update-shot', index, { visual: ($event.target as HTMLTextAreaElement).value })"
        ></textarea>
      </div>
      <div class="form-field">
        <label :for="`shot-narration-${shot.seq}`">旁白</label>
        <textarea
          :id="`shot-narration-${shot.seq}`"
          rows="2"
          :value="shot.narration"
          @change="emit('update-shot', index, { narration: ($event.target as HTMLTextAreaElement).value })"
        ></textarea>
      </div>
      <div class="form-field">
        <label :for="`shot-move-${shot.seq}`">运镜</label>
        <select
          :id="`shot-move-${shot.seq}`"
          :value="shot.cameraMove"
          @change="emit('update-shot', index, { cameraMove: ($event.target as HTMLSelectElement).value })"
        >
          <option v-for="move in cameraMoves" :key="move" :value="move">{{ move }}</option>
        </select>
      </div>
      <div class="form-field">
        <label :for="`shot-anchor-${shot.seq}`">锚定图</label>
        <select
          :id="`shot-anchor-${shot.seq}`"
          :value="shot.anchorImageIndex"
          @change="emit('update-shot', index, { anchorImageIndex: Number(($event.target as HTMLSelectElement).value) })"
        >
          <option :value="0">无锚定图</option>
          <option v-for="(img, imgIndex) in images" :key="img.id" :value="imgIndex + 1">
            第 {{ imgIndex + 1 }} 张
          </option>
        </select>
      </div>
      <div class="form-field">
        <label :for="`shot-seconds-${shot.seq}`">时长（4-6 秒）</label>
        <input
          :id="`shot-seconds-${shot.seq}`"
          type="number"
          min="4"
          max="6"
          step="1"
          :value="shot.plannedSeconds"
          @change="emit('update-shot', index, { plannedSeconds: Number(($event.target as HTMLInputElement).value) })"
        />
      </div>
      <div class="form-field shot-anchor-thumb">
        <label>锚定图预览</label>
        <div class="anchor-preview">
          <img
            v-if="shot.anchorUrl"
            :src="shot.anchorUrl"
            alt="AI 生成首帧"
            class="script-thumb"
            @click="emit('open-lightbox', shot.anchorUrl)"
          />
          <img
            v-else-if="shot.anchorImageIndex > 0 && images[shot.anchorImageIndex - 1]"
            :src="images[shot.anchorImageIndex - 1].dataUrl"
            alt="锚定图"
            class="script-thumb"
            @click="emit('open-lightbox', images[shot.anchorImageIndex - 1].dataUrl)"
          />
          <span v-else class="field-note">无锚定图</span>
          <span v-if="shot.anchorUrl" class="anchor-badge" data-test="anchor-badge">AI 生成</span>
        </div>
        <button
          v-if="shot.anchorImageIndex === 0 && shot.id"
          type="button"
          class="btn-secondary btn-sm anchor-generate"
          :disabled="anchorGenerating[shot.id] || storyboardLoading"
          data-test="anchor-generate"
          @click="emit('generate-anchor', shot.id)"
        >
          {{ anchorGenerating[shot.id] ? '生成中…' : 'AI 生成首帧' }}
        </button>
        <p v-if="shot.id && anchorErrors[shot.id]" class="field-note anchor-error">
          {{ anchorErrors[shot.id] }}
        </p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.shot-card {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
  padding: var(--space-md);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  margin-bottom: var(--space-md);
}

.shot-head {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
}

.shot-head .field-note {
  margin: 0 0 0 auto;
}

.shot-badge {
  display: inline-flex;
  align-items: center;
  padding: var(--space-micro) var(--space-sm);
  border-radius: var(--radius-pill);
  font-size: var(--type-caption);
  font-weight: var(--weight-heading);
  background: color-mix(in srgb, var(--color-accent) 16%, transparent);
  color: var(--color-accent-2);
}

.shot-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-sm) var(--space-md);
}

.form-field {
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
}

.form-field label {
  font-size: var(--type-caption);
  color: var(--color-text-muted);
}

.form-field input,
.form-field select,
.form-field textarea {
  min-height: var(--control-height);
  padding: var(--space-xs) var(--space-sm);
  border-radius: var(--radius-sm);
  border: 1px solid var(--color-border-control);
  background: var(--surface-hover);
  color: inherit;
  font-size: var(--type-body-sm);
  font-family: var(--font-body);
}

.form-field input:focus,
.form-field select:focus,
.form-field textarea:focus {
  outline: var(--focus-width) solid var(--focus-color);
  border-color: var(--color-accent);
}

.shot-anchor-thumb {
  grid-column: 1 / -1;
}

.preview-remove {
  margin-left: auto;
  padding: var(--space-xxs) var(--space-xs);
  font-size: var(--type-section-title);
  background: none;
  border: none;
  color: var(--color-text-muted);
  cursor: pointer;
}

.preview-remove:hover {
  color: var(--color-danger);
}

.script-thumb {
  width: 56px;
  height: 56px;
  object-fit: cover;
  border-radius: var(--radius-sm);
  cursor: pointer;
  flex-shrink: 0;
}

.anchor-preview {
  position: relative;
  display: inline-flex;
  flex-direction: column;
  gap: var(--space-xs);
}

.anchor-badge {
  position: absolute;
  bottom: 4px;
  left: 4px;
  padding: var(--space-micro) var(--space-xs);
  border-radius: var(--radius-pill);
  font-size: var(--type-caption);
  font-weight: var(--weight-heading);
  background: var(--color-overlay);
  color: var(--color-on-accent);
}

.anchor-generate {
  align-self: flex-start;
}

.anchor-error {
  color: var(--color-danger);
}

/* 父级 scoped 共享类复制（scoped 不穿透子组件，样式须随迁） */
.btn-secondary {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: var(--control-height);
  padding: 0 var(--space-md);
  border-radius: var(--radius-sm);
  font-size: var(--type-body-sm);
  text-decoration: none;
}

.btn-sm {
  font-size: var(--type-caption);
  padding: var(--space-xxs) var(--space-sm);
}

@media (max-width: 720px) {
  .shot-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
