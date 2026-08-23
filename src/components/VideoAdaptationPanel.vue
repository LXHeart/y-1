<template>
  <section class="adaptation-panel" aria-labelledby="adaptation-heading">
    <div class="adaptation-header">
      <div class="adaptation-header-copy">
        <div>
          <p class="analysis-kicker">内容改编</p>
          <h3 id="adaptation-heading" class="analysis-title">改编提取结果</h3>
        </div>
        <p class="analysis-hint">根据提取结果进行创作改编，每类资产可输入独立的改编要求。改编后可直接复制到 Seedance、GPT-Image、MiniMax 等工具使用。</p>
      </div>
      <button class="btn-secondary" :disabled="loading" @click="toggleForm">
        {{ actionLabel }}
      </button>
    </div>

    <div v-if="showForm" class="adaptation-form">
      <div v-if="!taskContext" class="instruction-field">
        <label for="script-instruction">视频脚本改编提示</label>
        <textarea id="script-instruction" v-model="scriptInstruction" placeholder="例如：改为美食探店风格，增加特写镜头描述" rows="2" class="instruction-input" />
        <p class="field-hint">改编后的脚本可直接用于 Seedance 2.0 视频生成</p>
      </div>

      <div class="instruction-field">
        <label for="character-instruction">人物三视图改编提示</label>
        <textarea id="character-instruction" v-model="characterInstruction" placeholder="例如：改为二次元风格，增加服饰细节" rows="2" class="instruction-input" />
        <p class="field-hint">改编后的人物设定可直接用于 Nano Banana 2 / GPT-Image-2 图片生成</p>
      </div>

      <div class="instruction-field">
        <label for="scene-props-instruction">场景道具改编提示</label>
        <textarea id="scene-props-instruction" v-model="scenePropsInstruction" placeholder="例如：换成户外自然光场景，增加氛围感道具" rows="2" class="instruction-input" />
        <p class="field-hint">改编后的场景道具描述可直接用于 Nano Banana 2 / GPT-Image-2 图片生成</p>
      </div>

      <div class="instruction-field">
        <label for="voice-instruction">人物音色改编提示</label>
        <textarea id="voice-instruction" v-model="voiceInstruction" placeholder="例如：换成温柔女声，语速稍慢" rows="2" class="instruction-input" />
        <p class="field-hint">改编后的音色描述可直接用于 MiniMax 音色生成</p>
      </div>

      <div class="instruction-field">
        <label>参考图片（可选）</label>
        <p class="field-hint" style="margin-bottom: 6px">上传参考图片，模型会分析图片内容并结合改编要求进行创作。最多 4 张，支持 JPG / PNG / WebP。</p>
        <div class="image-upload-area">
          <label class="upload-trigger">
            <span>+ 添加图片</span>
            <input type="file" accept="image/jpeg,image/png,image/webp" multiple class="upload-input" @change="handleImageSelect">
          </label>
          <div v-if="previewImages.length > 0" class="image-preview-list">
            <div v-for="(preview, index) in previewImages" :key="index" class="image-preview-item">
              <img :src="preview.url" :alt="preview.name" class="preview-thumb">
              <button class="preview-remove" @click="removeImage(index)">&times;</button>
            </div>
          </div>
        </div>
      </div>

      <div class="form-actions">
        <button class="btn-primary" :disabled="loading" @click="handleAdapt">确认改编</button>
        <button class="btn-secondary" @click="cancelForm">取消</button>
      </div>
    </div>

    <div v-if="loading" class="analysis-status analysis-status-loading">
      <div class="spinner spinner-small" />
      <p>正在改编内容…</p>
    </div>

    <div v-else-if="error" class="analysis-status analysis-status-error">
      <p class="analysis-status-title">改编失败</p>
      <p>{{ error }}</p>
      <button class="btn-secondary" @click="retryForm">重试</button>
    </div>

    <template v-else-if="adaptationCards.length > 0">
      <div v-if="adaptationMeta" class="analysis-status analysis-status-info">
        <p>{{ adaptationMeta }}</p>
      </div>

      <div class="analysis-grid">
        <article v-for="card in adaptationCards" :key="card.key" class="analysis-card">
          <div class="card-header">
            <p class="analysis-card-label">{{ card.label }}</p>
            <div class="card-actions">
              <span class="card-api-badge">{{ card.targetApi }}</span>
              <button class="btn-copy" @click="handleCopy(card.content, card.key)">
                {{ copiedKey === card.key ? '已复制' : card.copyLabel }}
              </button>
            </div>
          </div>
          <p class="analysis-card-copy">{{ card.content }}</p>
        </article>
      </div>

      <p v-if="result?.runId" class="analysis-run-id">运行 ID：{{ result.runId }}</p>
    </template>

    <div v-else-if="hasAttempted" class="analysis-status analysis-status-empty">
      <p>点击"开始改编"后输入改编要求，或不填任何提示直接改编。</p>
    </div>

    <section class="generation-history" aria-labelledby="adaptation-history-heading">
      <div class="history-heading-row">
        <div>
          <p class="analysis-kicker">版本记录</p>
          <h4 id="adaptation-history-heading" class="history-heading">历史改编</h4>
        </div>
        <button type="button" class="btn-secondary btn-sm" :disabled="historyLoading" @click="loadHistory(true)">
          刷新
        </button>
      </div>

      <p v-if="historyError" class="history-message history-error">{{ historyError }}</p>
      <p v-else-if="historyLoading && historyItems.length === 0" class="history-message">正在加载历史改编…</p>
      <p v-else-if="historyItems.length === 0" class="history-message">还没有历史改编。</p>

      <div v-else class="history-list">
        <article v-for="item in historyItems" :key="item.id" class="history-row">
          <button type="button" class="history-row-button" @click="toggleHistory(item.id)">
            <span class="history-row-main">
              <strong>{{ item.resultTitle }}</strong>
              <small>{{ formatGenerationTime(item.createdAt) }} · {{ item.provider }}{{ item.model ? ` / ${item.model}` : '' }}</small>
            </span>
            <span class="history-mode">{{ item.mode === 'task' ? '任务' : '独立' }}</span>
          </button>

          <div v-if="expandedHistoryId === item.id" class="history-detail">
            <p v-if="detailLoading" class="history-message">正在加载版本详情…</p>
            <p v-else-if="detailError" class="history-message history-error">{{ detailError }}</p>
            <template v-else-if="historyDetail">
              <div class="history-actions">
                <button type="button" class="btn-primary btn-sm" @click="refillHistoryInput">回填输入</button>
                <span class="history-model">{{ historyDetail.resolution === 'byok' ? 'BYOK' : '平台' }} · {{ historyDetail.provider }}{{ historyDetail.model ? ` / ${historyDetail.model}` : '' }}</span>
              </div>
              <div class="analysis-grid">
                <article v-for="card in historyCards" :key="card.key" class="analysis-card">
                  <p class="analysis-card-label">{{ card.label }}</p>
                  <p class="analysis-card-copy">{{ card.content }}</p>
                </article>
              </div>
              <details class="history-prompt">
                <summary>查看提示词</summary>
                <pre>{{ historyDetail.promptText }}</pre>
              </details>
              <details class="history-prompt">
                <summary>输入摘要</summary>
                <pre>{{ JSON.stringify(historyDetail.inputSummary, null, 2) }}</pre>
              </details>
            </template>
          </div>
        </article>
      </div>

      <button
        v-if="historyNextBefore"
        type="button"
        class="btn-secondary btn-sm history-more"
        :disabled="historyLoading"
        @click="loadHistory(false)"
      >
        加载更多
      </button>
    </section>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useVideoContentAdaptation } from '../composables/useVideoContentAdaptation'
import { getCreationGeneration, useCreationGenerations } from '../composables/useCreationGenerations'
import { buildVideoAdaptationDisplayCards } from '../types/video-recreation'
import type { VideoAdaptationResult, VideoAnalysisResult } from '../types/video-recreation'
import type { VideoTaskExecutionContext } from '../types/video-recreation'
import type { CreationGenerationDetail } from '../types/grassland/creation-generation'

const props = defineProps<{
  platform: 'douyin' | 'bilibili'
  proxyVideoUrl: string
  extractedContent: VideoAnalysisResult | null
  taskContext?: VideoTaskExecutionContext
}>()

const scriptInstruction = ref('')
const characterInstruction = ref('')
const scenePropsInstruction = ref('')
const voiceInstruction = ref('')
const showForm = ref(false)
const copiedKey = ref('')
const selectedImages = ref<File[]>([])
const previewImages = ref<Array<{ url: string; name: string }>>([])

const { result, loading, error, adaptContent } = useVideoContentAdaptation()
const {
  items: historyItems,
  nextBefore: historyNextBefore,
  loading: historyLoading,
  error: historyError,
  load: loadHistory,
} = useCreationGenerations('video_adaptation')
const expandedHistoryId = ref('')
const historyDetail = ref<CreationGenerationDetail | null>(null)
const detailLoading = ref(false)
const detailError = ref('')

const hasAttempted = computed(() => Boolean(result.value || error.value))

const adaptationCards = computed(() => buildVideoAdaptationDisplayCards(result.value))
const historyCards = computed(() => buildVideoAdaptationDisplayCards(
  historyDetail.value?.result as unknown as VideoAdaptationResult | null,
))

const adaptationMeta = computed(() => {
  if (!result.value) {
    return ''
  }

  const parts: string[] = []
  if (result.value.adaptedTitle) {
    parts.push(`改编标题：${result.value.adaptedTitle}`)
  }
  if (result.value.visualStyle) {
    parts.push(`视觉风格：${result.value.visualStyle}`)
  }
  if (result.value.tone) {
    parts.push(`情绪基调：${result.value.tone}`)
  }

  return parts.length > 0 ? parts.join(' · ') : ''
})

const actionLabel = computed(() => {
  if (loading.value) {
    return '改编中…'
  }

  if (result.value) {
    return '重新改编'
  }

  return '开始改编'
})

function toggleForm(): void {
  if (result.value && !showForm.value) {
    showForm.value = true
    return
  }

  if (showForm.value) {
    cancelForm()
    return
  }

  showForm.value = true
}

function cancelForm(): void {
  showForm.value = false
}

function retryForm(): void {
  error.value = ''
  showForm.value = true
}

function handleImageSelect(event: Event): void {
  const input = event.target as HTMLInputElement
  if (!input.files) {
    return
  }

  const maxImages = 4
  const maxSize = 5 * 1024 * 1024
  const allowed = ['image/jpeg', 'image/png', 'image/webp']

  for (const file of Array.from(input.files)) {
    if (selectedImages.value.length >= maxImages) {
      break
    }

    if (!allowed.includes(file.type)) {
      continue
    }

    if (file.size > maxSize) {
      continue
    }

    selectedImages.value = [...selectedImages.value, file]
    const url = URL.createObjectURL(file)
    previewImages.value = [...previewImages.value, { url, name: file.name }]
  }

  input.value = ''
}

function removeImage(index: number): void {
  const removed = previewImages.value[index]
  if (removed) {
    URL.revokeObjectURL(removed.url)
  }

  selectedImages.value = selectedImages.value.filter((_, i) => i !== index)
  previewImages.value = previewImages.value.filter((_, i) => i !== index)
}

async function handleAdapt(): Promise<void> {
  if (!props.extractedContent) {
    return
  }

  showForm.value = false

  const adapted = await adaptContent(
    props.platform,
    props.proxyVideoUrl,
    {
      videoCaptions: props.extractedContent.videoCaptions,
      videoScript: props.extractedContent.videoScript,
      charactersDescription: props.extractedContent.charactersDescription,
      voiceDescription: props.extractedContent.voiceDescription,
      propsDescription: props.extractedContent.propsDescription,
      sceneDescription: props.extractedContent.sceneDescription,
    },
    {
      scriptInstruction: scriptInstruction.value.trim() || undefined,
      characterInstruction: characterInstruction.value.trim() || undefined,
      scenePropsInstruction: scenePropsInstruction.value.trim() || undefined,
      voiceInstruction: voiceInstruction.value.trim() || undefined,
    },
    selectedImages.value.length > 0 ? selectedImages.value : undefined,
    props.taskContext,
  )
  if (adapted) await loadHistory(true)
}

async function toggleHistory(id: string): Promise<void> {
  if (expandedHistoryId.value === id) {
    expandedHistoryId.value = ''
    historyDetail.value = null
    return
  }
  expandedHistoryId.value = id
  historyDetail.value = null
  detailLoading.value = true
  detailError.value = ''
  try {
    historyDetail.value = await getCreationGeneration(id)
  } catch (cause) {
    detailError.value = cause instanceof Error ? cause.message : '版本详情加载失败'
  } finally {
    detailLoading.value = false
  }
}

function refillHistoryInput(): void {
  const custom = historyDetail.value?.inputSummary.customInstruction
  if (typeof custom !== 'object' || custom === null || Array.isArray(custom)) return
  const instructions = custom as Record<string, unknown>
  scriptInstruction.value = readText(instructions.scriptInstruction)
  characterInstruction.value = readText(instructions.characterInstruction)
  scenePropsInstruction.value = readText(instructions.scenePropsInstruction)
  voiceInstruction.value = readText(instructions.voiceInstruction)
  showForm.value = true
}

function readText(value: unknown): string {
  return typeof value === 'string' ? value : ''
}

function formatGenerationTime(value: string): string {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN')
}

async function handleCopy(text: string, key: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(text)
    copiedKey.value = key
    setTimeout(() => {
      if (copiedKey.value === key) {
        copiedKey.value = ''
      }
    }, 2_000)
  } catch {
    copiedKey.value = ''
  }
}

onMounted(() => loadHistory(true))
</script>

<style scoped>
.adaptation-panel {
  display: grid;
  gap: 16px;
  padding: 18px;
  border-radius: var(--radius-xl);
  border: 1px solid var(--color-border);
  background: var(--surface-page);
}

.adaptation-header {
  display: flex;
  align-items: start;
  justify-content: space-between;
  gap: 12px;
}

.adaptation-header-copy {
  display: grid;
  gap: 6px;
}

.adaptation-form {
  display: grid;
  gap: 14px;
  padding: 16px;
  border-radius: var(--radius-lg);
  border: 1px solid var(--color-border);
  background: var(--surface-card);
}

.instruction-field {
  display: grid;
  gap: 6px;
}

.instruction-field label {
  font-size: 0.88rem;
  font-weight: 600;
  color: var(--color-text);
}

.instruction-input {
  width: 100%;
  padding: 10px 12px;
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border);
  background: var(--surface-page);
  color: var(--color-text);
  font-size: 0.9rem;
  line-height: 1.5;
  resize: vertical;
  font-family: inherit;
}

.instruction-input:focus {
  outline: none;
  border-color: var(--color-accent);
}

.field-hint {
  margin: 0;
  font-size: 0.78rem;
  color: var(--color-text-muted);
}

.form-actions {
  display: flex;
  gap: 10px;
}

.generation-history {
  display: grid;
  gap: 12px;
  padding-top: 16px;
  border-top: 1px solid var(--color-border);
}

.history-heading-row,
.history-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.history-heading {
  margin: 2px 0 0;
  color: var(--color-text);
  font-size: 1rem;
}

.history-list {
  display: grid;
}

.history-row {
  border-top: 1px solid var(--color-border);
}

.history-row:last-child {
  border-bottom: 1px solid var(--color-border);
}

.history-row-button {
  display: flex;
  width: 100%;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 2px;
  border: 0;
  background: transparent;
  color: var(--color-text);
  text-align: left;
  cursor: pointer;
}

.history-row-main {
  display: grid;
  min-width: 0;
  gap: 4px;
}

.history-row-main strong {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.history-row-main small,
.history-model,
.history-message {
  color: var(--color-text-muted);
  font-size: 0.8rem;
}

.history-mode {
  flex: 0 0 auto;
  padding: 2px 7px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  color: var(--color-text-secondary);
  font-size: 0.75rem;
}

.history-detail {
  display: grid;
  gap: 12px;
  padding: 2px 0 16px;
}

.history-prompt {
  border-top: 1px solid var(--color-border);
  padding-top: 10px;
  color: var(--color-text-secondary);
  font-size: 0.82rem;
}

.history-prompt summary {
  cursor: pointer;
  font-weight: 600;
}

.history-prompt pre {
  max-height: 260px;
  overflow: auto;
  margin: 10px 0 0;
  padding: 10px;
  border-radius: var(--radius-sm);
  background: var(--surface-page);
  color: var(--color-text);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 0.75rem;
  line-height: 1.55;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.history-error {
  color: var(--color-danger);
}

.history-more {
  justify-self: center;
}

.image-upload-area {
  display: grid;
  gap: 10px;
}

.upload-trigger {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 10px 18px;
  border-radius: var(--radius-md);
  border: 1px dashed var(--color-border);
  color: var(--color-text-secondary);
  font-size: 0.88rem;
  cursor: pointer;
  transition: border-color 150ms ease, color 150ms ease;
  width: fit-content;
}

.upload-trigger:hover {
  border-color: var(--color-accent);
  color: var(--color-text);
}

.upload-input {
  display: none;
}

.image-preview-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.image-preview-item {
  position: relative;
}

.preview-thumb {
  width: 72px;
  height: 72px;
  object-fit: cover;
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border);
}

.preview-remove {
  position: absolute;
  top: -6px;
  right: -6px;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  border: 1px solid var(--color-border);
  background: var(--surface-card);
  color: var(--color-text-secondary);
  font-size: 12px;
  line-height: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  padding: 0;
}

.preview-remove:hover {
  background: color-mix(in srgb, var(--color-danger) 20%, transparent);
  color: var(--color-danger);
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.card-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.card-api-badge {
  padding: 2px 8px;
  border-radius: var(--radius-sm);
  font-size: 0.7rem;
  color: var(--color-text-muted);
  border: 1px solid var(--color-border);
  background: var(--surface-page);
  white-space: nowrap;
}

.btn-copy {
  padding: 4px 12px;
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border);
  background: var(--surface-page);
  color: var(--color-text);
  font-size: 0.8rem;
  cursor: pointer;
  transition:
    transform 100ms ease,
    border-color 100ms ease;
  white-space: nowrap;
}

.btn-copy:hover {
  border-color: var(--color-border-hover);
  transform: translateY(-1px);
}

@media (max-width: 720px) {
  .adaptation-header {
    flex-direction: column;
  }

  .card-header {
    flex-direction: column;
    align-items: start;
  }
}
</style>
