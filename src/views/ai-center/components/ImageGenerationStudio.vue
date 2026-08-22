<template>
  <section class="image-generation-studio gl-field" aria-label="图片生成">
    <p class="studio-intro">
      为图文/视频制作生成封面、配图与素材：输入描述提示词，上传参考素材保持风格一致，AI 帮你生成图片。
    </p>

    <div class="gen-card gl-zone">
      <div v-if="materials.length > 0" class="materials-area">
        <div class="materials-label">参考素材（{{ materials.length }}/4）</div>
        <div class="materials-grid">
          <div v-for="(mat, index) in materials" :key="index" class="material-thumb">
            <img :src="mat.previewUrl" :alt="`素材${index + 1}`" class="material-img" />
            <span class="material-tag">素材{{ index + 1 }}</span>
            <button type="button" class="material-remove" :disabled="generating" @click="removeMaterial(index)" aria-label="删除素材">&times;</button>
          </div>
        </div>
      </div>

      <p class="auth-note">请确认您拥有上传素材的使用权；涉及人脸、商标、店铺招牌或个人信息的内容需已获得授权。</p>

      <div class="prompt-area" style="position: relative">
        <textarea
          ref="promptRef"
          v-model="prompt"
          class="prompt-input"
          placeholder="描述你想生成的图片，例如：一只橘色的猫坐在窗台上，窗外是夕阳，油画风格。输入 @ 可引用素材"
          rows="4"
          :disabled="generating"
          @keydown.ctrl.enter="handleGenerate"
          @keydown.meta.enter="handleGenerate"
          @input="onPromptInput"
        />
        <div v-if="mentionVisible" class="mention-dropdown" :style="mentionStyle">
          <button
            v-for="(mat, idx) in materials"
            :key="idx"
            type="button"
            class="mention-item"
            @mousedown.prevent="insertMention(idx)"
          >
            素材{{ idx + 1 }}
          </button>
        </div>
        <div class="prompt-footer">
          <span class="char-count gl-num">{{ prompt.length }} / 4000</span>
          <div class="prompt-actions">
            <button type="button" class="upload-btn" :disabled="generating || materials.length >= 4" @click="triggerUpload">
              + 上传素材
            </button>
            <div class="size-selector">
              <button
                v-for="option in sizeOptions"
                :key="option.value"
                class="size-btn"
                :class="{ 'size-btn-active': selectedSize === option.value }"
                type="button"
                :disabled="generating"
                @click="selectedSize = option.value"
              >
                {{ option.label }}
              </button>
            </div>
          </div>
        </div>
        <input
          ref="fileInputRef"
          type="file"
          accept="image/jpeg,image/png,image/webp"
          multiple
          style="display: none"
          @change="onFileSelected"
        />
      </div>

      <button
        class="gen-btn gl-btn-primary"
        :class="{ 'gen-btn-loading': generating }"
        type="button"
        :disabled="!canGenerate"
        @click="handleGenerate"
      >
        <template v-if="generating">
          <span class="spinner" />
          生成中...
        </template>
        <template v-else>生成图片</template>
      </button>
    </div>

    <p v-if="error" class="error-msg">{{ error }}</p>

    <div v-if="results.length > 0" class="results-grid">
      <div
        v-for="(item, index) in results"
        :key="index"
        class="result-card gl-tile"
      >
        <div class="result-img-wrap" @click="openLightbox(item.imageUrl)">
          <img :src="item.imageUrl" :alt="item.revisedPrompt || prompt" class="result-img" loading="lazy" />
        </div>
        <p v-if="item.revisedPrompt" class="result-prompt">{{ item.revisedPrompt }}</p>
        <div class="result-actions">
          <a :href="item.imageUrl" target="_blank" rel="noopener" class="result-action-btn" download>下载</a>
          <button class="result-action-btn" type="button" @click="copyPrompt(item.revisedPrompt || prompt)">复制提示词</button>
        </div>
      </div>
    </div>

    <div v-if="lightboxUrl" class="lightbox-overlay" @click.self="lightboxUrl = ''">
      <button class="lightbox-close" type="button" @click="lightboxUrl = ''" aria-label="关闭">&times;</button>
      <img :src="lightboxUrl" class="lightbox-img" alt="放大查看" />
    </div>

    <OversizedImageDialog
      :show="showOversizedDialog"
      :files="oversizedFiles"
      :compressing="compressing"
      @compress="compressOversizedImages"
      @skip="removeOversizedImages"
      @cancel="cancelOversizedImages"
    />
  </section>
</template>

<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { compressImageToFile } from '../../../composables/compress-image'
import { generateImage } from '../../../composables/useImageGeneration'
import OversizedImageDialog from './OversizedImageDialog.vue'

interface GenerateResult {
  imageUrl: string
  revisedPrompt?: string
}

interface MaterialItem {
  file: File
  previewUrl: string
}

const MAX_MATERIALS = 4
const MAX_FILE_SIZE = 5 * 1024 * 1024
const prompt = ref('')
const selectedSize = ref<'1024x1024' | '1024x1792' | '1792x1024'>('1024x1024')
const generating = ref(false)
const error = ref('')
const results = ref<GenerateResult[]>([])
const lightboxUrl = ref('')
const materials = ref<MaterialItem[]>([])
const mentionVisible = ref(false)
const mentionStyle = ref<Record<string, string>>({})

const oversizedFiles = ref<File[]>([])
const showOversizedDialog = ref(false)
const compressing = ref(false)
const pendingFiles = ref<File[]>([])

const promptRef = ref<HTMLTextAreaElement | null>(null)
const fileInputRef = ref<HTMLInputElement | null>(null)

const sizeOptions = [
  { value: '1024x1024' as const, label: '1:1' },
  { value: '1024x1792' as const, label: '2:3' },
  { value: '1792x1024' as const, label: '3:2' },
]

const canGenerate = computed(() => prompt.value.trim().length > 0 && !generating.value)

function copyPrompt(text: string): void {
  navigator.clipboard.writeText(text).catch(() => {})
}

function openLightbox(url: string): void {
  lightboxUrl.value = url
}

function triggerUpload(): void {
  fileInputRef.value?.click()
}

function onFileSelected(event: Event): void {
  const input = event.target as HTMLInputElement
  if (!input.files) return

  const files = Array.from(input.files).filter(f => f.type.startsWith('image/'))
  input.value = ''

  const availableSlots = MAX_MATERIALS - materials.value.length
  if (availableSlots <= 0) return

  const toProcess = files.slice(0, availableSlots)
  const oversized: File[] = []
  const normal: File[] = []

  for (const file of toProcess) {
    if (file.size > MAX_FILE_SIZE) {
      oversized.push(file)
    } else {
      normal.push(file)
    }
  }

  for (const file of normal) {
    materials.value.push({ file, previewUrl: URL.createObjectURL(file) })
  }

  if (oversized.length > 0) {
    oversizedFiles.value = oversized
    pendingFiles.value = normal
    showOversizedDialog.value = true
  }
}

async function compressOversizedImages(): Promise<void> {
  if (oversizedFiles.value.length === 0) return
  compressing.value = true
  try {
    for (const file of oversizedFiles.value) {
      if (materials.value.length >= MAX_MATERIALS) break
      const compressed = await compressImageToFile(file, MAX_FILE_SIZE)
      materials.value.push({ file: compressed, previewUrl: URL.createObjectURL(compressed) })
    }
  } finally {
    oversizedFiles.value = []
    showOversizedDialog.value = false
    compressing.value = false
  }
}

function removeOversizedImages(): void {
  oversizedFiles.value = []
  showOversizedDialog.value = false
}

function cancelOversizedImages(): void {
  for (const mat of pendingFiles.value) {
    const idx = materials.value.findIndex(m => m.file === mat)
    if (idx !== -1) {
      URL.revokeObjectURL(materials.value[idx].previewUrl)
      materials.value.splice(idx, 1)
    }
  }
  oversizedFiles.value = []
  showOversizedDialog.value = false
}

function removeMaterial(index: number): void {
  const removed = materials.value.splice(index, 1)[0]
  if (removed) URL.revokeObjectURL(removed.previewUrl)
}

function onPromptInput(): void {
  const textarea = promptRef.value
  if (!textarea || materials.value.length === 0) {
    mentionVisible.value = false
    return
  }

  const cursorPos = textarea.selectionStart
  const textBefore = prompt.value.slice(0, cursorPos)
  const atMatch = textBefore.match(/@[^@\s]*$/u)

  if (atMatch) {
    mentionVisible.value = true
    const lineHeight = 22
    const charsPerLine = Math.floor(textarea.clientWidth / 8)
    const lines = textBefore.slice(0, textBefore.length - atMatch[0].length).split('\n')
    const topLine = lines.length - 1
    const topCharInLine = lines[lines.length - 1].length
    const approxLeft = (topCharInLine % Math.max(charsPerLine, 1)) * 8
    const approxTop = topLine * lineHeight + lineHeight + 4

    mentionStyle.value = {
      left: `${Math.min(approxLeft, textarea.clientWidth - 100)}px`,
      top: `${approxTop}px`,
    }
  } else {
    mentionVisible.value = false
  }
}

function insertMention(index: number): void {
  const textarea = promptRef.value
  if (!textarea) return

  const cursorPos = textarea.selectionStart
  const textBefore = prompt.value.slice(0, cursorPos)
  const textAfter = prompt.value.slice(cursorPos)
  const atMatch = textBefore.match(/@[^@\s]*$/u)

  if (atMatch) {
    const before = textBefore.slice(0, textBefore.length - atMatch[0].length)
    const insert = `@素材${index + 1} `
    prompt.value = `${before}${insert}${textAfter}`

    nextTick(() => {
      const newPos = before.length + insert.length
      textarea.setSelectionRange(newPos, newPos)
      textarea.focus()
    })
  }

  mentionVisible.value = false
}

async function handleGenerate(): Promise<void> {
  if (!canGenerate.value) return

  const trimmed = prompt.value.trim()
  error.value = ''
  generating.value = true

  try {
    const data = await generateImage({
      prompt: trimmed,
      size: selectedSize.value,
      images: materials.value.map((mat) => mat.file),
    })

    results.value = [
      data,
      ...results.value,
    ]
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : '网络错误，请重试'
  } finally {
    generating.value = false
  }
}
</script>

<style scoped>
.image-generation-studio {
  display: grid;
  gap: var(--space-lg);
  max-width: 820px;
  margin: 0 auto;
}

.studio-intro {
  margin: 0;
  font-size: var(--text-sm);
  color: var(--color-text-secondary);
  line-height: 1.7;
}

.gen-card {
  display: grid;
  gap: var(--space-md);
}

.materials-area {
  display: grid;
  gap: var(--space-sm);
}

.materials-label {
  font-size: 0.82rem;
  color: var(--color-text-muted);
}

.auth-note {
  margin: 0;
  padding: var(--space-sm) var(--space-md);
  border-radius: var(--radius-sm);
  background: var(--surface-muted);
  border: 1px solid var(--color-border);
  color: var(--color-text-muted);
  font-size: 0.8rem;
  line-height: 1.6;
}

.materials-grid {
  display: flex;
  gap: var(--space-sm);
  flex-wrap: wrap;
}

.material-thumb {
  position: relative;
  width: 72px;
  height: 72px;
  border-radius: var(--radius-sm);
  overflow: hidden;
  border: 1px solid var(--color-border);
  background: var(--surface-muted);
}

.material-img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.material-tag {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  padding: 1px 4px;
  background: var(--color-overlay);
  color: var(--color-on-accent);
  font-size: 0.65rem;
  text-align: center;
  line-height: 1.4;
}

.material-remove {
  position: absolute;
  top: 2px;
  right: 2px;
  width: 18px;
  height: 18px;
  border: none;
  border-radius: 50%;
  background: var(--color-overlay);
  color: var(--color-on-accent);
  font-size: 0.7rem;
  line-height: 1;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  transition: background var(--duration-fast) var(--ease-out);
}

.material-remove:hover:not(:disabled) {
  background: color-mix(in srgb, var(--color-danger) 80%, transparent);
}

.material-remove:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.prompt-area {
  display: grid;
  gap: var(--space-sm);
}

.prompt-input {
  width: 100%;
  min-height: 100px;
  padding: var(--space-md);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--surface-muted);
  color: var(--color-text);
  font-size: 0.92rem;
  line-height: 1.6;
  resize: vertical;
  font-family: inherit;
  transition: border-color var(--duration-fast) var(--ease-out);
  box-sizing: border-box;
}

.prompt-input:focus {
  outline: none;
  border-color: var(--color-accent);
  box-shadow: var(--focus-ring);
}

.prompt-input::placeholder {
  color: var(--color-text-muted);
}

.prompt-input:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.mention-dropdown {
  position: absolute;
  z-index: 100;
  min-width: 80px;
  padding: 4px;
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  box-shadow: var(--shadow-elevated);
}

.mention-item {
  display: block;
  width: 100%;
  padding: 6px 10px;
  border: none;
  border-radius: 4px;
  background: transparent;
  color: var(--color-text);
  font-size: 0.82rem;
  text-align: left;
  cursor: pointer;
  transition: background var(--duration-fast) var(--ease-out);
}

.mention-item:hover {
  background: var(--surface-hover);
}

.prompt-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-md);
}

.char-count {
  font-size: 0.78rem;
  color: var(--color-text-muted);
}

.prompt-actions {
  display: flex;
  align-items: center;
  gap: var(--space-md);
}

.upload-btn {
  padding: 4px 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--color-accent-2);
  font-size: var(--text-xs);
  cursor: pointer;
  transition: background var(--duration-fast) var(--ease-out), border-color var(--duration-fast) var(--ease-out), color var(--duration-fast) var(--ease-out);
}

.upload-btn:hover:not(:disabled) {
  background: var(--surface-hover);
  border-color: var(--color-border-accent);
}

.upload-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.size-selector {
  display: flex;
  gap: 6px;
}

.size-btn {
  padding: 4px 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--color-text-muted);
  font-size: var(--text-xs);
  cursor: pointer;
  transition: background var(--duration-fast) var(--ease-out), border-color var(--duration-fast) var(--ease-out), color var(--duration-fast) var(--ease-out);
}

.size-btn:hover:not(:disabled) {
  color: var(--color-text-secondary);
  border-color: var(--color-border-hover);
}

.size-btn-active {
  background: color-mix(in srgb, var(--color-accent) 12%, transparent);
  border-color: var(--color-border-accent);
  color: var(--color-accent-2);
  font-weight: 600;
}

.size-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.gen-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  height: 44px;
  padding: 0 28px;
  font-size: 0.92rem;
}

.gen-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
  transform: none;
}

.gen-btn-loading {
  pointer-events: none;
}

.spinner {
  width: 16px;
  height: 16px;
  border: 2px solid rgba(255, 255, 255, 0.3);
  border-top-color: #fff;
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.error-msg {
  padding: var(--space-sm) var(--space-md);
  border-radius: var(--radius-sm);
  background: color-mix(in srgb, var(--color-danger) 10%, transparent);
  border: 1px solid color-mix(in srgb, var(--color-danger) 20%, transparent);
  color: var(--color-danger);
  font-size: 0.86rem;
  margin: 0;
}

.results-grid {
  display: grid;
  gap: var(--space-lg);
}

.result-card {
  display: grid;
  gap: var(--space-sm);
}

.result-img-wrap {
  cursor: pointer;
  border-radius: var(--radius-sm);
  overflow: hidden;
  background: var(--surface-muted);
}

.result-img-wrap:hover {
  box-shadow: 0 0 0 2px var(--color-border-accent);
}

.result-img {
  display: block;
  width: 100%;
  height: auto;
  max-height: 600px;
  object-fit: contain;
}

.result-prompt {
  font-size: 0.82rem;
  color: var(--color-text-muted);
  margin: 0;
  line-height: 1.5;
}

.result-actions {
  display: flex;
  gap: var(--space-sm);
}

.result-action-btn {
  padding: 4px 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--color-text-secondary);
  font-size: var(--text-xs);
  cursor: pointer;
  text-decoration: none;
  transition: background var(--duration-fast) var(--ease-out), border-color var(--duration-fast) var(--ease-out), color var(--duration-fast) var(--ease-out);
}

.result-action-btn:hover {
  background: var(--surface-hover);
  border-color: var(--color-border-hover);
}

.lightbox-overlay {
  position: fixed;
  inset: 0;
  z-index: 9999;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--color-overlay);
  backdrop-filter: blur(12px);
  cursor: pointer;
}

.lightbox-close {
  position: absolute;
  top: 20px;
  right: 24px;
  width: 40px;
  height: 40px;
  border: none;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.1);
  color: #fff;
  font-size: 1.4rem;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background var(--duration-fast) var(--ease-out);
}

.lightbox-close:hover {
  background: rgba(255, 255, 255, 0.2);
}

.lightbox-img {
  max-width: 92vw;
  max-height: 88vh;
  object-fit: contain;
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-elevated);
}
</style>
