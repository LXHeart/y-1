<template>
  <div class="video-production gl-field">
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
    <section v-if="stage === 'upload'" class="stage-card gl-zone fade-in">
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
          <label for="vp-platform">发布平台 *</label>
          <select id="vp-platform" v-model="form.targetPlatform">
            <option value="">请选择发布平台</option>
            <option v-for="item in videoPlatforms" :key="item.id" :value="item.id">{{ item.label }}</option>
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
          <textarea id="vp-prompt" v-model="form.customPrompt" rows="2" maxlength="1500" placeholder="对视频脚本有什么特殊要求？（选填）"></textarea>
        </div>
      </div>

      <VideoReferenceInput
        :reference-platform="referencePlatform"
        :reference-input="referenceInput"
        :hot-topic-input="hotTopicInput"
        :reference-cards="referenceCards"
        :has-selected-cards="hasSelectedReferenceCards"
        :applied="referenceApplied"
        :parse-loading="referenceParseLoading"
        @switch-platform="handleSwitchReferencePlatform"
        @update:reference-input="referenceInput = $event"
        @update:hot-topic-input="hotTopicInput = $event"
        @extract="handleExtractReference"
        @clear-reference="handleClearReference"
        @apply-to-prompt="applyReferenceToPrompt"
        @apply-hot-topic="applyHotTopicToPrompt"
        @toggle-card="toggleReferenceCard"
      >
        <template #parse-panels>
          <DouyinParsePanel
            v-if="referencePlatform === 'douyin'"
            :extracted-video="douyinExtractedVideo"
            :loading="douyinParseLoading"
            :error="douyinParseError"
            :analysis="douyinVideoAnalysis"
            :analysis-loading="douyinAnalysisLoading"
            :analysis-error="douyinAnalysisError"
            @retry="handleExtractReference"
            @retry-analysis="handleRetryDouyinAnalysis"
          />

          <BilibiliParsePanel
            v-else
            :extracted-video="bilibiliExtractedVideo"
            :loading="bilibiliParseLoading"
            :error="bilibiliParseError"
            :analysis="bilibiliVideoAnalysis"
            :analysis-loading="bilibiliAnalysisLoading"
            :analysis-error="bilibiliAnalysisError"
            @retry="handleExtractReference"
            @retry-analysis="handleRetryBilibiliAnalysis"
          />
        </template>
      </VideoReferenceInput>

      <div v-if="error" class="error-hint">{{ error }}</div>

      <div class="action-row">
        <button
          class="btn-primary gl-btn-primary"
          :disabled="!canProceedToScript || scriptLoading"
          @click="generateScript"
        >
          {{ scriptLoading ? '生成中…' : '生成脚本' }}
        </button>
      </div>
    </section>

    <!-- Step 2: Script Editing -->
    <section v-if="stage === 'script'" class="stage-card gl-zone fade-in">
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
        <p v-if="referenceApplied" class="field-note reference-applied-note">已包含参考输入（参考视频分析 / 热点主题），见自定义要求。</p>
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

      <SafetyFindingsPanel
        v-if="safetyReport"
        :report="safetyReport"
        :text="script"
        @updated="safetyReport = $event"
      />

      <div class="action-row">
        <button
          class="btn-secondary"
          :disabled="scriptLoading"
          @click="generateScript"
        >
          {{ scriptLoading ? '生成中…' : '重新生成' }}
        </button>
        <button
          class="btn-primary gl-btn-primary"
          :disabled="scriptLoading || !script.trim() || !videoGenerationAvailable"
          @click="startVideoGeneration"
        >
          生成视频
        </button>
      </div>

      <p v-if="!videoGenerationAvailable" class="field-note">{{ videoGenerationReason }}</p>
    </section>

    <!-- Step 3: Video Generation -->
    <section v-if="stage === 'generate'" class="stage-card gl-zone fade-in">
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
          <a :href="videoUrl" download class="btn-primary gl-btn-primary" target="_blank">下载视频</a>
          <button class="btn-secondary" @click="handleResetAll">新建视频</button>
        </div>
      </div>

      <div v-else-if="error" class="result-area">
        <p class="error-hint">{{ error }}</p>
        <div class="action-row">
          <button class="btn-primary gl-btn-primary" @click="startVideoGeneration">重试</button>
          <button class="btn-secondary" @click="goBackToScript">返回脚本</button>
        </div>
      </div>

      <div v-else class="result-area">
        <p class="field-note">视频生成服务即将上线，敬请期待！</p>
        <div class="action-row">
          <button class="btn-secondary" @click="goBackToScript">返回脚本</button>
          <button class="btn-secondary" @click="handleResetAll">新建视频</button>
        </div>
      </div>
    </section>

    <Teleport to="body">
      <div v-if="lightboxSrc" class="lightbox-overlay" @click="closeLightbox">
        <img :src="lightboxSrc" class="lightbox-img" @click.stop />
        <button class="lightbox-close" @click="closeLightbox" aria-label="关闭">&times;</button>
      </div>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { AI_PLATFORM_DEFINITIONS } from '../../config/ai-platform-capabilities'
import BilibiliParsePanel from '../../components/BilibiliParsePanel.vue'
import DouyinParsePanel from '../../components/DouyinParsePanel.vue'
import VideoReferenceInput from './components/VideoReferenceInput.vue'
import { useBilibiliParse } from '../../composables/useBilibiliParse'
import { useBilibiliVideoAnalysis } from '../../composables/useBilibiliVideoAnalysis'
import { useDouyinParse } from '../../composables/useDouyinParse'
import { useDouyinVideoAnalysis } from '../../composables/useDouyinVideoAnalysis'
import { useVideoProduction } from '../../composables/useVideoProduction'
import SafetyFindingsPanel from '../../components/SafetyFindingsPanel.vue'
import { buildVideoAnalysisDisplayCards } from '../../types/video-recreation'
import type { CreationHandoff } from '../../types/ai-creation'
import type { IndustryType, VideoStyle } from '../../types/video-production'

const props = defineProps<{
  creationHandoff?: CreationHandoff | null
}>()

const {
  stage, images, form, script, safetyReport, videoUrl,
  scriptLoading, videoLoading, videoProgress, error,
  canProceedToScript,
  videoGenerationAvailable, videoGenerationReason,
  addImages, removeImage, reorderImage,
  generateScript, startVideoGeneration,
  goBackToUpload, goBackToScript,
  reset, bindCreationContext,
} = useVideoProduction()

const hydratedCreationRevision = ref<number | null>(null)

type ReferencePlatform = 'douyin' | 'bilibili'

interface ReferenceCardOption {
  key: string
  label: string
  content: string
  selected: boolean
}

const referencePlatform = ref<ReferencePlatform>('douyin')
const referenceInput = ref('')
const hotTopicInput = ref('')
const referenceCards = ref<ReferenceCardOption[]>([])
const referenceApplied = ref(false)

const {
  extractedVideo: douyinExtractedVideo,
  loading: douyinParseLoading,
  error: douyinParseError,
  extractVideo: extractDouyinVideo,
  reset: resetDouyinParse,
} = useDouyinParse()

const {
  analysis: douyinVideoAnalysis,
  loading: douyinAnalysisLoading,
  error: douyinAnalysisError,
  analyzeVideo: analyzeDouyinVideo,
  reset: resetDouyinAnalysis,
} = useDouyinVideoAnalysis()

const {
  extractedVideo: bilibiliExtractedVideo,
  loading: bilibiliParseLoading,
  error: bilibiliParseError,
  extractVideo: extractBilibiliVideo,
  reset: resetBilibiliParse,
} = useBilibiliParse()

const {
  analysis: bilibiliVideoAnalysis,
  loading: bilibiliAnalysisLoading,
  error: bilibiliAnalysisError,
  analyzeVideo: analyzeBilibiliVideo,
  reset: resetBilibiliAnalysis,
} = useBilibiliVideoAnalysis()

watch(() => props.creationHandoff, (handoff) => {
  if (!handoff || handoff.targetView !== 'video-production' || hydratedCreationRevision.value === handoff.revision) return
  hydratedCreationRevision.value = handoff.revision
  reset()
  bindCreationContext(handoff.source.type === 'task', handoff.contextSnapshotId)
  clearOptionalInputState()
  const promptParts = [
    handoff.prefill?.topic ? `创作主题：${handoff.prefill.topic}` : '',
    handoff.prefill?.instructions || '',
  ].filter(Boolean)
  form.value = {
    ...form.value,
    targetPlatform: handoff.platformId,
    shopName: handoff.prefill?.storeName || '',
    shopAddress: handoff.prefill?.address || '',
    shopDescription: handoff.prefill?.storeDescription || '',
    customPrompt: promptParts.join('\n'),
  }
}, { immediate: true })

const MAX_IMAGES = 9
// 朋友圈的视频形式是 video-text（PRD §4.4），同样落到视频制作。
const videoPlatforms = AI_PLATFORM_DEFINITIONS.filter((item) =>
  item.forms.some((form) => form.id === 'video' || form.id === 'video-text'))

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

// ---- 可选输入方式：参考视频链接 / 热点主题 ----

const referenceParseLoading = computed(() => {
  return referencePlatform.value === 'douyin' ? douyinParseLoading.value : bilibiliParseLoading.value
})

const activeReferenceAnalysis = computed(() => {
  return referencePlatform.value === 'douyin' ? douyinVideoAnalysis.value : bilibiliVideoAnalysis.value
})

const hasSelectedReferenceCards = computed(() => referenceCards.value.some((card) => card.selected))

function toggleReferenceCard(key: string): void {
  const card = referenceCards.value.find((c) => c.key === key)
  if (card) card.selected = !card.selected
}

watch(activeReferenceAnalysis, (analysis) => {
  referenceCards.value = buildVideoAnalysisDisplayCards(analysis)
    .filter((card) => !card.isFallback)
    .map((card) => ({ key: card.key, label: card.label, content: card.content, selected: true }))
})

function handleSwitchReferencePlatform(platform: ReferencePlatform): void {
  referencePlatform.value = platform
}

async function handleExtractReference(): Promise<void> {
  referenceCards.value = []
  referenceApplied.value = false

  if (referencePlatform.value === 'douyin') {
    resetDouyinAnalysis()
    const data = await extractDouyinVideo(referenceInput.value)
    if (!data) return
    await analyzeDouyinVideo(data.proxyVideoUrl)
    return
  }

  resetBilibiliAnalysis()
  const data = await extractBilibiliVideo(referenceInput.value)
  if (!data) return
  await analyzeBilibiliVideo(data.proxyVideoUrl)
}

async function handleRetryDouyinAnalysis(): Promise<void> {
  const proxyVideoUrl = douyinExtractedVideo.value?.proxyVideoUrl
  if (!proxyVideoUrl) return
  await analyzeDouyinVideo(proxyVideoUrl)
}

async function handleRetryBilibiliAnalysis(): Promise<void> {
  const proxyVideoUrl = bilibiliExtractedVideo.value?.proxyVideoUrl
  if (!proxyVideoUrl) return
  await analyzeBilibiliVideo(proxyVideoUrl)
}

function handleClearReference(): void {
  referenceInput.value = ''
  referenceCards.value = []
  resetDouyinAnalysis()
  resetBilibiliAnalysis()
  resetDouyinParse()
  resetBilibiliParse()
}

function appendToCustomPrompt(text: string): void {
  const existing = form.value.customPrompt.trim()
  form.value.customPrompt = existing ? `${existing}\n${text}` : text
}

function applyReferenceToPrompt(): void {
  const selectedCards = referenceCards.value.filter((card) => card.selected)
  if (selectedCards.length === 0) return

  const referenceText = [
    '参考视频分析产出（仅为创作建议）：',
    ...selectedCards.map((card) => `【${card.label}】\n${card.content}`),
  ].join('\n')

  appendToCustomPrompt(referenceText)
  referenceApplied.value = true
}

function applyHotTopicToPrompt(): void {
  const topic = hotTopicInput.value.trim()
  if (!topic) return
  appendToCustomPrompt(`创作主题：${topic}`)
  referenceApplied.value = true
}

function clearOptionalInputState(): void {
  referenceInput.value = ''
  hotTopicInput.value = ''
  referenceCards.value = []
  referenceApplied.value = false
  resetDouyinAnalysis()
  resetBilibiliAnalysis()
  resetDouyinParse()
  resetBilibiliParse()
}

function handleResetAll(): void {
  reset()
  clearOptionalInputState()
}
</script>

<style scoped>
.video-production {
  max-width: 800px;
  margin: 0 auto;
  padding: var(--space-lg) var(--space-md);
}

.steps-bar {
  display: flex;
  justify-content: center;
  gap: var(--space-xl);
  margin-bottom: var(--space-lg);
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
  border-radius: 999px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  font-weight: 600;
  background: var(--color-surface-strong);
  border: 1px solid var(--color-border);
  transition: background 0.3s, border-color 0.3s, color 0.3s;
}

/* 激活态用淡底描边而非实心紫：与表单区的权重失衡来自步骤条过度抢眼（视觉审查 ⑮） */
.step-active .step-num {
  background: color-mix(in srgb, var(--color-accent) 16%, transparent);
  border-color: color-mix(in srgb, var(--color-accent) 45%, transparent);
  color: var(--color-accent);
}

.step-done .step-num {
  background: color-mix(in srgb, var(--color-success) 14%, transparent);
  border-color: color-mix(in srgb, var(--color-success) 35%, transparent);
  color: var(--color-success);
}

.step-label {
  font-size: 12px;
  color: var(--color-text-muted);
}

.step-active .step-label {
  color: var(--color-text);
  font-weight: 600;
}

.upload-area {
  margin-bottom: var(--space-md);
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
  border: 2px dashed var(--color-border);
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: border-color 0.2s, background 0.2s;
  color: var(--color-text-muted);
  font-size: 14px;
}

.drop-zone:hover,
.drop-zone-active {
  border-color: var(--color-accent);
  background: color-mix(in srgb, var(--color-accent) 5%, transparent);
}

.preview-grid {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-xs);
  margin-bottom: var(--space-md);
}

.preview-item {
  position: relative;
  width: 72px;
  height: 72px;
  border-radius: var(--radius-sm);
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
  border-radius: 999px;
  background: var(--color-overlay);
  color: var(--color-on-accent);
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
  border-radius: 999px;
  background: var(--color-overlay);
  color: var(--color-on-accent);
  font-size: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.input-methods {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 16px;
  padding: 12px;
  border-radius: var(--radius-md);
  border: 1px dashed var(--color-border);
}

.input-method-toggle {
  display: flex;
  align-items: center;
  gap: 6px;
  background: none;
  border: none;
  color: inherit;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  padding: 0;
}

.input-method-toggle:hover {
  color: var(--color-accent);
}

.toggle-caret {
  color: var(--color-text-muted);
  font-size: 12px;
}

.reference-area,
.topic-area {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 10px;
  padding: 12px;
  border-radius: var(--radius-sm);
  background: var(--surface-furrow);
}

.reference-platform-switch {
  display: inline-flex;
  gap: 4px;
  padding: 4px;
  border-radius: var(--radius-sm);
  background: var(--surface-hover);
  width: fit-content;
}

.reference-platform-tab {
  padding: 4px 14px;
  border: 1px solid transparent;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--color-text-muted);
  font-size: 13px;
  cursor: pointer;
}

.reference-platform-tab-active {
  background: var(--color-accent);
  color: var(--color-on-accent);
}

.reference-input,
.topic-input {
  padding: 8px 12px;
  border-radius: var(--radius-sm);
  border: 1px solid var(--color-border);
  background: var(--surface-hover);
  color: inherit;
  font-size: 14px;
  font-family: inherit;
  resize: vertical;
}

.reference-input:focus,
.topic-input:focus {
  outline: none;
  border-color: var(--color-accent);
}

.action-row-start {
  justify-content: flex-start;
}

.reference-apply {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px;
  border-radius: var(--radius-sm);
  border: 1px solid var(--color-border);
}

.reference-card-option {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  cursor: pointer;
}

.reference-applied-hint,
.reference-applied-note {
  color: color-mix(in srgb, var(--color-success) 90%, transparent);
}

.topic-row {
  display: flex;
  gap: 8px;
}

.topic-input {
  flex: 1;
}

.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--space-sm);
  margin-bottom: var(--space-md);
}

.form-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.form-field label {
  font-size: 13px;
  color: var(--color-text-muted);
}

.form-field input,
.form-field select,
.form-field textarea {
  min-height: 38px;
  padding: 8px 12px;
  border-radius: var(--radius-sm);
  border: 1px solid var(--color-border);
  background: var(--surface-hover);
  color: inherit;
  font-size: 14px;
  font-family: inherit;
}

.form-field input:focus,
.form-field select:focus,
.form-field textarea:focus {
  outline: none;
  border-color: var(--color-accent);
}

.form-field-wide {
  grid-column: 1 / -1;
}

.script-thumbnails {
  display: flex;
  gap: var(--space-xs);
  margin-bottom: var(--space-md);
  overflow-x: auto;
}

.script-thumb {
  width: 56px;
  height: 56px;
  object-fit: cover;
  border-radius: var(--radius-sm);
  cursor: pointer;
  flex-shrink: 0;
}

.stream-area {
  position: relative;
  margin-bottom: var(--space-md);
}

.stream-textarea {
  width: 100%;
  padding: 12px;
  border-radius: var(--radius-sm);
  border: 1px solid var(--color-border);
  background: var(--surface-hover);
  color: inherit;
  font-size: 14px;
  font-family: inherit;
  line-height: 1.6;
  resize: vertical;
}

.stream-textarea:focus {
  outline: none;
  border-color: var(--color-accent);
}

.stream-loading {
  border-color: var(--color-accent);
}

.stream-badge {
  position: absolute;
  top: 8px;
  right: 12px;
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--color-accent);
}

.stream-dot {
  width: 6px;
  height: 6px;
  border-radius: 999px;
  background: var(--color-accent);
  animation: pulse 1.2s ease-in-out infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.3; }
}

.progress-area {
  margin-bottom: var(--space-md);
}

.progress-bar-track {
  height: 6px;
  border-radius: 3px;
  background: var(--color-border-hover);
  overflow: hidden;
  margin-bottom: 8px;
}

.progress-bar-fill {
  height: 100%;
  border-radius: 3px;
  background: var(--color-accent);
  transition: width 0.3s ease;
}

.result-area {
  text-align: center;
}

.result-video {
  width: 100%;
  max-width: 480px;
  border-radius: var(--radius-md);
  margin-bottom: var(--space-md);
}

.error-hint {
  color: var(--color-danger);
  font-size: 13px;
  margin-bottom: var(--space-sm);
}

.action-row {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
}

.btn-primary,
.btn-secondary {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 38px;
  padding: 0 var(--space-md);
  border-radius: var(--radius-sm);
  font-size: var(--text-sm);
  text-decoration: none;
}

.btn-back {
  display: flex;
  align-items: center;
  gap: 4px;
  background: none;
  border: none;
  color: var(--color-text-muted);
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
  margin-bottom: var(--space-md);
}

.eyebrow {
  font-size: 12px;
  color: var(--color-accent);
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
  color: var(--color-text-muted);
}

.lightbox-overlay {
  position: fixed;
  inset: 0;
  background: var(--color-overlay);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  cursor: pointer;
}

.lightbox-img {
  max-width: 90vw;
  max-height: 90vh;
  border-radius: var(--radius-sm);
  cursor: default;
}

.lightbox-close {
  position: absolute;
  top: 16px;
  right: 16px;
  width: 36px;
  height: 36px;
  border-radius: 999px;
  background: var(--color-border-hover);
  color: var(--color-on-accent);
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
