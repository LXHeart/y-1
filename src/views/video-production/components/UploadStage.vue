<script setup lang="ts">
import { computed, ref } from 'vue'
import BilibiliParsePanel from '../../../components/BilibiliParsePanel.vue'
import DouyinParsePanel from '../../../components/DouyinParsePanel.vue'
import MediaLibraryPanel from '../../../components/MediaLibraryPanel.vue'
import VideoReferenceInput from './VideoReferenceInput.vue'
import { formatYuan } from '../../../lib/money'
import { useAuth } from '../../../composables/useAuth'
import type { IndustryType, VideoStyle, VideoInputMode, VideoOwnMediaRef, VideoProductionImage } from '../../../types/video-production'
import type { ContentAsset } from '../../../types/grassland'
import type { useVideoReference } from '../composables/useVideoReference'

/**
 * 第一步：上传素材 & 店铺信息（任务书 #91 V1 自 VideoProductionView.vue 模板 19–190 整段迁入，
 * 纯搬运；D-06：images/form 留视图经 props 下传，七字段 per-field emit，拖排 emit 经 reorderImage；
 * 参考输入/时长区块随本组件整体搬、状态（useVideoReference 28 绑定）留视图经 props 下传不拆）。
 * 拖放局部态与常量（MAX_IMAGES/industryTypes/videoStyles）随迁。
 * AI内容中心改造-03 §3.1：inputMode 分段——店铺照片（旧流程零变化）/ 已有脚本 / 自有素材。
 */
const props = defineProps<{
  images: VideoProductionImage[]
  goToCreationCenter: () => void
  addImages: (files: File[]) => void
  removeImage: (id: string) => void
  reorderImage: (from: number, to: number) => void
  openLightbox: (src: string) => void
  inputMode: VideoInputMode
  script: string
  ownMediaRefs: VideoOwnMediaRef[]
  shopName: string
  industryType: IndustryType
  targetPlatform: string
  shopAddress: string
  videoStyle: VideoStyle
  shopDescription: string
  customPrompt: string
  targetDurationSeconds: number
  handleDurationInput: (raw: string) => void
  estimatedPriceCents: number | null
  isLandscape: boolean
  verticalDurationHint: string
  error: string
  canProceedToStoryboard: boolean
  storyboardLoading: boolean
  generateStoryboard: () => void
  videoPlatforms: ReadonlyArray<{ id: string; label: string }>
  referencePlatform: VideoRefState['referencePlatform']['value']
  referenceInput: string
  hotTopicInput: string
  referenceCards: VideoRefState['referenceCards']['value']
  hasSelectedReferenceCards: boolean
  referenceApplied: VideoRefState['referenceApplied']['value']
  referenceParseLoading: VideoRefState['referenceParseLoading']['value']
  handleSwitchReferencePlatform: VideoRefState['handleSwitchReferencePlatform']
  handleExtractReference: VideoRefState['handleExtractReference']
  handleClearReference: VideoRefState['handleClearReference']
  applyReferenceToPrompt: VideoRefState['applyReferenceToPrompt']
  applyHotTopicToPrompt: VideoRefState['applyHotTopicToPrompt']
  toggleReferenceCard: VideoRefState['toggleReferenceCard']
  douyinExtractedVideo: VideoRefState['douyinExtractedVideo']['value']
  douyinParseLoading: VideoRefState['douyinParseLoading']['value']
  douyinParseError: VideoRefState['douyinParseError']['value']
  douyinVideoAnalysis: VideoRefState['douyinVideoAnalysis']['value']
  douyinAnalysisLoading: VideoRefState['douyinAnalysisLoading']['value']
  douyinAnalysisError: VideoRefState['douyinAnalysisError']['value']
  handleRetryDouyinAnalysis: VideoRefState['handleRetryDouyinAnalysis']
  bilibiliExtractedVideo: VideoRefState['bilibiliExtractedVideo']['value']
  bilibiliParseLoading: VideoRefState['bilibiliParseLoading']['value']
  bilibiliParseError: VideoRefState['bilibiliParseError']['value']
  bilibiliVideoAnalysis: VideoRefState['bilibiliVideoAnalysis']['value']
  bilibiliAnalysisLoading: VideoRefState['bilibiliAnalysisLoading']['value']
  bilibiliAnalysisError: VideoRefState['bilibiliAnalysisError']['value']
  handleRetryBilibiliAnalysis: VideoRefState['handleRetryBilibiliAnalysis']
}>()

type VideoRefState = ReturnType<typeof useVideoReference>

const emit = defineEmits<{
  'update:inputMode': [value: VideoInputMode]
  'update:script': [value: string]
  'update:ownMediaRefs': [value: VideoOwnMediaRef[]]
  'update:shopName': [value: string]
  'update:industryType': [value: IndustryType]
  'update:targetPlatform': [value: string]
  'update:shopAddress': [value: string]
  'update:videoStyle': [value: VideoStyle]
  'update:shopDescription': [value: string]
  'update:customPrompt': [value: string]
  'update:referenceInput': [value: string]
  'update:hotTopicInput': [value: string]
}>()

const MAX_IMAGES = 9
const industryTypes: IndustryType[] = ['餐饮', '零售', '美业', '健身', '教育培训', '其他']
const videoStyles: VideoStyle[] = ['烟火纪实', '治愈清新', '高级暗调', '数字人口播', '复古胶片']

const isDragging = ref(false)
const dragIndex = ref<number | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)

function handleFileSelect(event: Event): void {
  const input = event.target as HTMLInputElement
  if (input.files) {
    props.addImages(Array.from(input.files))
    input.value = ''
  }
}

function handleDrop(event: DragEvent): void {
  isDragging.value = false
  if (event.dataTransfer?.files) {
    props.addImages(Array.from(event.dataTransfer.files))
  }
}

function onDropReorder(toIndex: number): void {
  if (dragIndex.value !== null && dragIndex.value !== toIndex) {
    props.reorderImage(dragIndex.value, toIndex)
  }
  dragIndex.value = null
}

// 七字段 per-field emit 的 computed 中继（D-06：子组件禁止直改 form prop）
const inputModeModel = computed({ get: () => props.inputMode, set: (v: VideoInputMode) => emit('update:inputMode', v) })
const scriptModel = computed({ get: () => props.script, set: (v: string) => emit('update:script', v) })
const shopNameModel = computed({ get: () => props.shopName, set: (v: string) => emit('update:shopName', v) })
const industryTypeModel = computed({ get: () => props.industryType, set: (v: IndustryType) => emit('update:industryType', v) })
const targetPlatformModel = computed({ get: () => props.targetPlatform, set: (v: string) => emit('update:targetPlatform', v) })
const shopAddressModel = computed({ get: () => props.shopAddress, set: (v: string) => emit('update:shopAddress', v) })
const videoStyleModel = computed({ get: () => props.videoStyle, set: (v: VideoStyle) => emit('update:videoStyle', v) })
const shopDescriptionModel = computed({ get: () => props.shopDescription, set: (v: string) => emit('update:shopDescription', v) })
const customPromptModel = computed({ get: () => props.customPrompt, set: (v: string) => emit('update:customPrompt', v) })
const referenceInputModel = computed({
  get: () => props.referenceInput,
  set: (value: string) => emit('update:referenceInput', value),
})
const hotTopicInputModel = computed({
  get: () => props.hotTopicInput,
  set: (value: string) => emit('update:hotTopicInput', value),
})

const auth = useAuth()
const ownMediaLibraryOpen = ref(false)
const ownAssetIds = ref<string[]>([])

/** 自有素材选择：MediaLibraryPanel 选中对象 → mediaId 引用（label 便于素材计划核对）。 */
function onOwnAssetsSelected(assets: ContentAsset[]): void {
  ownAssetIds.value = assets.map((asset) => asset.id)
  const videoish = assets.filter((asset) => asset.mediaId)
  emit('update:ownMediaRefs', videoish.map((asset) => ({
    mediaId: asset.mediaId,
    label: asset.title || asset.mimeType || '',
  })))
}

function removeOwnMedia(mediaId: string): void {
  emit('update:ownMediaRefs', props.ownMediaRefs.filter((ref) => ref.mediaId !== mediaId))
}
</script>

<template>
  <section class="stage-card gl-zone fade-in">
    <header class="card-head">
      <div class="card-head-row">
        <button class="btn-back" type="button" @click="goToCreationCenter">
          <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <path d="M10 3L5 8l5 5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
          返回创作中心
        </button>
        <p class="eyebrow">第一步</p>
      </div>
      <h2 class="card-title">选择创作起点</h2>
      <p class="field-note">三种输入方式：店铺照片（原有流程）、已有脚本、自有素材——没有店铺照片也能开始。</p>
    </header>

    <!-- AI改造-03 §3.1：输入分支分段控件（加工方式用分段控件，AGENTS §9.3） -->
    <fieldset class="form-field form-field-wide input-mode-field">
      <legend>输入方式 *</legend>
      <div class="option-grid">
        <label class="style-option" :class="{ active: inputModeModel === 'store-photos' }">
          <input v-model="inputModeModel" type="radio" name="vp-input-mode" value="store-photos" data-test="vp-input-mode-store">
          店铺照片
        </label>
        <label class="style-option" :class="{ active: inputModeModel === 'script' }">
          <input v-model="inputModeModel" type="radio" name="vp-input-mode" value="script" data-test="vp-input-mode-script">
          已有脚本
        </label>
        <label class="style-option" :class="{ active: inputModeModel === 'own-media' }">
          <input v-model="inputModeModel" type="radio" name="vp-input-mode" value="own-media" data-test="vp-input-mode-own">
          自有素材
        </label>
      </div>
    </fieldset>

    <div v-if="inputModeModel === 'script'" class="form-field form-field-wide">
      <label for="vp-script">已有脚本 *（至少 50 字；分镜与旁白忠实脚本，不虚构店铺或数据）</label>
      <textarea
        id="vp-script"
        v-model="scriptModel"
        data-test="vp-script"
        rows="8"
        maxlength="20000"
        placeholder="粘贴完整脚本：口播、镜头安排、演示步骤等。系统按脚本组织分镜，不要求店铺照片。"
      />
    </div>

    <div v-if="inputModeModel === 'own-media'" class="form-field form-field-wide">
      <label>自有素材 *（从素材库选择图片/视频；镜头优先复用素材，覆盖不到的画面才生成）</label>
      <button
        type="button"
        class="btn-secondary"
        data-test="vp-own-media-toggle"
        @click="ownMediaLibraryOpen = !ownMediaLibraryOpen"
      >
        {{ ownMediaLibraryOpen ? '收起素材库' : '从素材库选择' }}
      </button>
      <ul v-if="ownMediaRefs.length" class="own-media-list" data-test="vp-own-media-list">
        <li v-for="ref in ownMediaRefs" :key="ref.mediaId">
          {{ ref.label || ref.mediaId }}
          <button type="button" class="preview-remove" aria-label="移除素材" @click="removeOwnMedia(ref.mediaId)">&times;</button>
        </li>
      </ul>
      <p v-else class="field-note">尚未选择素材。素材归属与类型会在生成前校验；无权或失效素材会被明确拒绝。</p>
      <div v-if="ownMediaLibraryOpen" class="own-media-library">
        <MediaLibraryPanel
          :authenticated="auth.isAuthenticated.value"
          selectable
          :selected-asset-ids="ownAssetIds"
          @selection-assets="onOwnAssetsSelected"
          @request-login="ownMediaLibraryOpen = false"
        />
      </div>
    </div>

    <div v-if="inputModeModel === 'store-photos'" class="upload-area">
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
        @click="fileInput?.click()"
      >
        <svg width="32" height="32" viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <path d="M12 5v14M5 12l7-7 7 7" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
        </svg>
        <span>拖拽图片到此处，或点击上传</span>
        <span class="field-note">{{ images.length }} / {{ MAX_IMAGES }} 张</span>
      </label>
    </div>

    <div v-if="inputModeModel === 'store-photos' && images.length > 0" class="preview-grid">
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
      <div v-if="inputModeModel === 'store-photos'" class="form-field">
        <label for="vp-shop-name">店铺名称 *</label>
        <input id="vp-shop-name" v-model="shopNameModel" type="text" placeholder="例如：老王面馆" />
      </div>
      <div class="form-field">
        <label for="vp-industry">行业类型 *</label>
        <select id="vp-industry" v-model="industryTypeModel">
          <option v-for="t in industryTypes" :key="t" :value="t">{{ t }}</option>
        </select>
      </div>
      <div class="form-field">
        <label for="vp-platform">发布平台 *</label>
        <select id="vp-platform" v-model="targetPlatformModel">
          <option value="">请选择发布平台</option>
          <option v-for="item in videoPlatforms" :key="item.id" :value="item.id">{{ item.label }}</option>
        </select>
      </div>
      <div v-if="inputModeModel === 'store-photos'" class="form-field">
        <label for="vp-address">店铺地址</label>
        <input id="vp-address" v-model="shopAddressModel" type="text" placeholder="选填" />
      </div>
      <div class="form-field">
        <label for="vp-style">视频风格 *</label>
        <select id="vp-style" v-model="videoStyleModel">
          <option v-for="s in videoStyles" :key="s" :value="s">{{ s }}</option>
        </select>
      </div>
      <div v-if="inputModeModel === 'store-photos'" class="form-field form-field-wide">
        <label for="vp-desc">店铺简介</label>
        <textarea id="vp-desc" v-model="shopDescriptionModel" rows="2" placeholder="简短描述店铺特色（选填）"></textarea>
      </div>
      <div class="form-field form-field-wide">
        <label for="vp-prompt">自定义要求</label>
        <textarea id="vp-prompt" v-model="customPromptModel" rows="2" maxlength="1500" placeholder="对视频脚本有什么特殊要求？（选填）"></textarea>
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
      @update:reference-input="referenceInputModel = $event"
      @update:hot-topic-input="hotTopicInputModel = $event"
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

    <div class="form-field form-field-wide duration-field">
      <label for="vp-duration">成片时长（{{ targetDurationSeconds }} 秒）</label>
      <input
        id="vp-duration"
        :value="targetDurationSeconds"
        type="range"
        min="15"
        max="180"
        step="5"
        data-test="target-duration"
        @input="handleDurationInput(($event.target as HTMLInputElement).value)"
      />
      <p class="field-note">
        15-180 秒，按 5 秒步进；计费按成片实际秒数一口价结算<template v-if="estimatedPriceCents !== null">
          （预计 {{ formatYuan(estimatedPriceCents) }} 起）</template>。
        <span v-if="isLandscape" class="resolution-tag" data-test="resolution-tag">B 站默认横版 16:9</span>
      </p>
      <p v-if="verticalDurationHint" class="field-note duration-hint" data-test="vertical-duration-hint">
        {{ verticalDurationHint }}
      </p>
    </div>

    <div v-if="error" class="error-hint">{{ error }}</div>

    <div class="action-row">
      <button
        class="btn-primary gl-btn-primary"
        :disabled="!canProceedToStoryboard || storyboardLoading"
        @click="generateStoryboard"
      >
        {{ storyboardLoading ? '生成中…' : '生成分镜' }}
      </button>
    </div>
  </section>
</template>

<style scoped>
/* AI改造-03：输入分支分段控件与自有素材列表（token 全取全局变量） */
.input-mode-field { border: none; padding: 0; margin: 0; }
.option-grid { display: flex; flex-wrap: wrap; gap: var(--space-xs); }
.style-option {
  display: inline-flex; align-items: center; gap: 6px; padding: 0 var(--space-md); min-height: 34px;
  border: 1px solid var(--color-border); border-radius: var(--radius-pill); cursor: pointer;
  font-size: var(--text-sm); color: var(--color-text); background: transparent;
}
.style-option.active {
  border-color: var(--color-accent); color: var(--color-accent);
  background: color-mix(in srgb, var(--color-accent) 10%, transparent);
}
.style-option input { accent-color: var(--color-accent); }
.own-media-list { list-style: none; margin: 6px 0 0; padding: 0; display: grid; gap: 6px; font-size: var(--text-sm); }
.own-media-list li {
  display: flex; align-items: center; justify-content: space-between; gap: var(--space-sm);
  padding: 6px 10px; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-surface);
}
.own-media-library { margin-top: 10px; border-top: 1px dashed var(--color-border); padding-top: 10px; }

/* ===== 自 VideoProductionView.vue 随迁（原文件内两段 .btn-back/.card-head-row 重复定义
   的级联顺序原样保留：先首段后尾段，最终视觉不变） ===== */
.card-head-row {
  display: flex;
  align-items: center;
  gap: var(--space-md);
  margin-bottom: var(--space-sm);
}

.btn-back {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--color-text-secondary);
  font-size: 0.86rem;
  cursor: pointer;
  transition: background var(--duration-fast) var(--ease-out), border-color var(--duration-fast) var(--ease-out), color var(--duration-fast) var(--ease-out);
}

.btn-back:hover {
  background: var(--surface-hover);
  border-color: var(--color-border-hover);
  color: var(--color-text);
}

.btn-back:disabled {
  opacity: 0.5;
  cursor: not-allowed;
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
  border-radius: var(--radius-pill);
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
  border-radius: var(--radius-pill);
  background: var(--color-overlay);
  color: var(--color-on-accent);
  font-size: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
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

.duration-field input[type='range'] {
  width: 100%;
  accent-color: var(--color-accent);
}

/* #65 卡3：分辨率标签 / 竖版时长软提示 */
.resolution-tag {
  display: inline-flex;
  align-items: center;
  margin-left: var(--space-xs);
  padding: 1px 8px;
  border-radius: var(--radius-pill);
  font-size: var(--text-xs);
  background: color-mix(in srgb, var(--color-accent) 12%, transparent);
  color: var(--color-accent);
}

.duration-hint {
  color: var(--color-warning, var(--color-text-muted));
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

.fade-in {
  animation: fadeIn 0.3s ease;
}

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(8px); }
  to { opacity: 1; transform: translateY(0); }
}
</style>
