<script setup lang="ts">
import { computed } from 'vue'
import { useFeishuCredentials } from '../composables/useFeishuCredentials'
import { useImageUpload } from '../composables/useImageUpload'

/**
 * 图片评价控制卡（任务书 #91 I1 自 ImageAnalysisView.vue 模板 12–218 整段迁入，纯搬运；
 * D-08：script 域不动 useImageAnalysis，本卡为 props/emit 视图组件）。
 * useFeishuCredentials 改由本卡持有（I1 拍板：表单唯一消费方），导出守卫经 defineExpose 上抛
 * 给视图接线结果卡；useImageUpload 上传局部态随迁。
 */
const props = defineProps<{
  images: ReadonlyArray<{ preview: string }>
  platformLocked: boolean
  platform: 'taobao' | 'dianping'
  reviewLength: number
  feelings: string
  loading: boolean
  generationStage: string
  addFiles: (files: File[]) => string | null
  removeImage: (index: number) => void
  previewImage: (index: number) => void
  startGeneration: () => void
  handleReset: () => void
  cancelAnalysis: () => void
}>()

const emit = defineEmits<{
  'update:platform': [value: 'taobao' | 'dianping']
  'update:reviewLength': [value: number]
  'update:feelings': [value: string]
}>()

// ---------- 飞书凭据内联维护（任务书 #47 S7a / D18②）----------
const {
  showFeishuConfig,
  feishuAppId,
  feishuAppSecret,
  feishuFolderToken,
  feishuSaveError,
  savingFeishu,
  feishuSecretSaved,
  feishuConfigured,
  toggleFeishuConfig,
  submitFeishuCredentials,
  handleExportToFeishu,
} = useFeishuCredentials()

const { isDragging, uploadError, fileInput } = useImageUpload()

function openFilePicker(): void {
  fileInput.value?.click()
}

function handleFileInput(event: Event): void {
  const input = event.target as HTMLInputElement
  if (!input.files?.length) return
  uploadError.value = props.addFiles(Array.from(input.files)) ?? ''
  input.value = ''
}

function handleDrop(event: DragEvent): void {
  isDragging.value = false
  const files = event.dataTransfer?.files
  if (!files?.length) return
  uploadError.value = props.addFiles(Array.from(files)) ?? ''
}

const platformModel = computed({
  get: () => props.platform,
  set: (value: 'taobao' | 'dianping') => emit('update:platform', value),
})
const reviewLengthModel = computed({
  get: () => props.reviewLength,
  set: (value: number) => emit('update:reviewLength', value),
})
const feelingsModel = computed({
  get: () => props.feelings,
  set: (value: string) => emit('update:feelings', value),
})

/** 清空按钮的局部态复位（原视图 handleReset 前两行，随上传局部态迁入）。 */
function resetLocalUploadState(): void {
  isDragging.value = false
  uploadError.value = ''
}

defineExpose({ handleExportToFeishu, resetLocalUploadState })
</script>

<template>
  <article class="control-card gl-zone">
    <header class="section-head">
      <div>
        <p class="section-kicker">图片评价</p>
        <h2 class="section-title">上传图片后生成探店评价与消费体验文案</h2>
      </div>
      <p class="section-note">支持 JPG、PNG、WebP，最多 6 张，每张不超过 5 MB。</p>
    </header>

    <label
      class="drop-zone"
      :class="{ 'drop-zone-active': isDragging }"
      for="image-analysis-input"
      tabindex="0"
      @dragenter.prevent="isDragging = true"
      @dragover.prevent
      @dragleave.prevent="isDragging = false"
      @drop.prevent="handleDrop"
      @keydown.enter.prevent="openFilePicker"
      @keydown.space.prevent="openFilePicker"
    >
      <div class="drop-zone-icon" aria-hidden="true">
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none">
          <path d="M12 5v14M5 12l7-7 7 7" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/>
        </svg>
      </div>
      <div class="drop-zone-copy">
        <p class="drop-zone-title">点击上传或拖入图片</p>
        <p class="drop-zone-text">建议先放主图，再补充细节图，生成结果会更完整。</p>
      </div>
      <input
        id="image-analysis-input"
        ref="fileInput"
        type="file"
        accept="image/jpeg,image/png,image/webp"
        multiple
        class="sr-only"
        @change="handleFileInput"
      >
    </label>

    <p v-if="uploadError" class="error-text">{{ uploadError }}</p>

    <div v-if="images.length" class="selected-images">
      <div class="selected-images-head">
        <p class="selected-images-title">已选图片</p>
        <p class="selected-images-count">{{ images.length }}/6</p>
      </div>
      <ul class="thumb-list">
        <li v-for="(img, i) in images" :key="i" class="thumb-item" @click="previewImage(i)">
          <img :src="img.preview" :alt="`图片 ${i + 1}`" class="thumb-img" />
          <button class="thumb-remove" type="button" @click.stop="removeImage(i)" aria-label="删除图片">&times;</button>
        </li>
      </ul>
    </div>

    <div class="field-block">
      <div class="field-block-head">
        <p class="field-block-title">生成偏好</p>
        <p class="field-block-copy">先选目标平台，再决定大致字数。填 0 则不限制字数。</p>
      </div>

      <div class="settings-row">
        <!-- 创作中心带入的平台（大众点评图文流）在本页定死：不提供淘宝切换，避免跨平台误生成。 -->
        <p v-if="platformLocked" class="platform-locked-chip">
          <svg width="13" height="13" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <rect x="3" y="7.3" width="10" height="7" rx="1.5" stroke="currentColor" stroke-width="1.3"/>
            <path d="M5.5 7.3V5.5a2.5 2.5 0 015 0v1.8" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/>
          </svg>
          大众点评（由创作流程带入）
        </p>

        <div v-else class="platform-toggle" role="tablist" aria-label="评价平台">
          <button
            type="button"
            class="platform-btn"
            :class="{ 'platform-btn-active': platform === 'taobao' }"
            :disabled="loading"
            @click="platformModel = 'taobao'"
          >淘宝</button>
          <button
            type="button"
            class="platform-btn"
            :class="{ 'platform-btn-active': platform === 'dianping' }"
            :disabled="loading"
            @click="platformModel = 'dianping'"
          >大众点评</button>
        </div>

        <label class="field-group-inline">
          <span class="field-label">目标字数</span>
          <input
            v-model.number="reviewLengthModel"
            class="field-input-sm"
            type="number"
            min="0"
            max="300"
            step="1"
            inputmode="numeric"
            :disabled="loading"
          >
        </label>
      </div>

      <p v-if="platform === 'dianping'" class="platform-position-hint">
        大众点评探店定位：围绕探店评价、消费体验与推荐理由展开，覆盖门店环境、菜品与服务细节，可补充评分建议、到店提示与真实体验描述。
      </p>
      <p v-else class="platform-position-hint">
        淘宝评价定位：围绕商品体验与真实感受展开，突出包装、质量与使用细节。
      </p>
    </div>

    <div class="field-block">
      <div class="field-block-head">
        <p class="field-block-title">补充感受</p>
        <p class="field-block-copy">可补充你想强调的细节，比如包装、分量、口感、服务体验。</p>
      </div>

      <textarea
        v-model="feelingsModel"
        class="field-textarea"
        rows="3"
        maxlength="200"
        placeholder="例如：包装挺干净、分量看着很足、实物比图片还精致…"
        :disabled="loading"
      ></textarea>
    </div>

    <div class="action-row">
      <button
        class="btn-primary gl-btn-primary"
        :disabled="loading || images.length === 0"
        @click="startGeneration"
      >
        {{ loading ? '生成中…' : '生成评价' }}
      </button>
      <button class="btn-secondary" :disabled="loading" @click="handleReset">
        清空
      </button>
      <button v-if="generationStage === 'drafting'" class="btn-secondary" @click="cancelAnalysis">
        取消
      </button>
    </div>

    <!-- 任务书 #47 S7a / D18②：飞书凭据从顶部「分析设置」modal 搬到这里——它是唯一用到这组
         凭据的地方。放在上传卡片内（无条件渲染）而不是结果区：用户要先配好凭据才导得出，
         藏在 v-else-if="result" 里等于「没结果就配不了」。modal 下线（S7c）前必须先有此入口。 -->
    <div class="feishu-config">
      <button
        class="btn-secondary btn-sm"
        type="button"
        data-action="toggle-feishu-config"
        @click="toggleFeishuConfig"
      >
        {{ showFeishuConfig ? '收起飞书凭据' : feishuConfigured ? '飞书导出凭据（已配置）' : '配置飞书导出凭据' }}
      </button>

      <form v-if="showFeishuConfig" class="feishu-form" @submit.prevent="submitFeishuCredentials">
        <p class="feishu-hint">配置飞书应用凭证后，才能把评价结果导出到飞书文档。凭据加密保存，页面只显示掩码。</p>

        <label class="result-label" for="feishu-inline-app-id">App ID</label>
        <input
          id="feishu-inline-app-id"
          v-model.trim="feishuAppId"
          class="field-input-sm"
          type="text"
          name="feishuAppId"
          placeholder="飞书应用 App ID"
          autocomplete="off"
          spellcheck="false"
        >

        <label class="result-label" for="feishu-inline-app-secret">App Secret</label>
        <p v-if="feishuSecretSaved" class="feishu-hint">已保存，留空保持不变；输入空格后保存可清空。</p>
        <input
          id="feishu-inline-app-secret"
          v-model="feishuAppSecret"
          class="field-input-sm"
          type="password"
          name="feishuAppSecret"
          :placeholder="feishuSecretSaved ? '留空则保持现有 Secret' : '飞书应用 App Secret'"
          autocomplete="new-password"
        >

        <label class="result-label" for="feishu-inline-folder">文档夹 Token（可选）</label>
        <input
          id="feishu-inline-folder"
          v-model.trim="feishuFolderToken"
          class="field-input-sm"
          type="text"
          name="feishuFolderToken"
          placeholder="留空则创建到默认位置"
          autocomplete="off"
          spellcheck="false"
        >

        <p v-if="feishuSaveError" class="export-error" role="alert">{{ feishuSaveError }}</p>
        <div class="feishu-actions">
          <button class="btn-secondary btn-sm" type="button" @click="showFeishuConfig = false">取消</button>
          <button class="btn-copy" type="submit" :disabled="savingFeishu">
            {{ savingFeishu ? '保存中…' : '保存凭据' }}
          </button>
        </div>
      </form>
    </div>
  </article>
</template>

<style scoped>
/* ===== 自 ImageAnalysisView.vue 逐字随迁（值未改动） ===== */
.platform-locked-chip {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  margin: 0;
  min-height: 40px;
  padding: 0 14px;
  border: 1px solid var(--color-border-accent);
  border-radius: var(--radius-sm);
  background: color-mix(in srgb, var(--color-accent) 8%, transparent);
  color: var(--color-text-secondary);
  font-size: 0.84rem;
  font-weight: 600;
}

.control-card,
.preview-column,
.result-card,
.empty-card,
.status-card,
.progress-card {
  display: grid;
  gap: var(--space-md);
}

.control-card {
  position: sticky;
  top: var(--space-md);
}

.section-head,
.field-block,
.field-block-head,
.result-head,
.result-block,
.result-tags-wrap,
.result-steps,
.result-steps-head {
  display: grid;
  gap: var(--space-sm);
}

.section-head {
  gap: var(--space-xs);
}

.section-kicker,
.result-label,
.selected-images-title,
.field-block-title {
  margin: 0;
  font-size: 0.75rem;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--color-text-muted);
  font-weight: 600;
}

.section-title,
.result-title,
.empty-title,
.status-title {
  margin: 0;
  color: var(--color-text);
}

.section-title {
  font-size: 1.14rem;
  line-height: 1.25;
}

.section-note,
.drop-zone-text,
.selected-images-count,
.field-block-copy,
.empty-copy,
.status-copy {
  margin: 0;
  color: var(--color-text-secondary);
  font-size: 0.86rem;
  line-height: 1.55;
}

.drop-zone {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  align-items: center;
  gap: 14px;
  min-height: 112px;
  padding: 18px;
  border: 1px dashed var(--color-border);
  border-radius: var(--radius-lg);
  background: var(--surface-page);
  cursor: pointer;
  transition: border-color var(--duration-fast) var(--ease-out), background var(--duration-fast) var(--ease-out), box-shadow var(--duration-fast) var(--ease-out);
}

.drop-zone:hover,
.drop-zone-active {
  border-color: var(--color-border-accent);
  background: var(--surface-card);
  box-shadow: var(--focus-ring);
}

.drop-zone-icon {
  width: 42px;
  height: 42px;
  display: grid;
  place-items: center;
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border);
  background: var(--surface-card);
  color: var(--color-text-secondary);
}

.drop-zone-copy {
  display: grid;
  gap: 4px;
}

.drop-zone-title {
  margin: 0;
  color: var(--color-text);
  font-size: 0.96rem;
  font-weight: 600;
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  border: 0;
}

.selected-images {
  display: grid;
  gap: 10px;
  padding: 14px;
  border-radius: var(--radius-lg);
  border: 1px solid var(--color-border);
  background: var(--surface-page);
}

.selected-images-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.thumb-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.thumb-item {
  position: relative;
  width: 72px;
  height: 72px;
  border-radius: var(--radius-md);
  overflow: hidden;
  border: 1px solid var(--color-border);
  background: var(--surface-card);
}

.thumb-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.thumb-remove {
  position: absolute;
  top: 6px;
  right: 6px;
  width: 22px;
  height: 22px;
  display: grid;
  place-items: center;
  border-radius: var(--radius-pill);
  border: 1px solid var(--color-border);
  background: var(--surface-card);
  color: white;
  font-size: 12px;
  line-height: 1;
  cursor: pointer;
}

.settings-row {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
}

.platform-toggle {
  display: inline-flex;
  gap: 4px;
  padding: 4px;
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border);
  background: var(--surface-page);
}

.platform-btn {
  min-height: 36px;
  padding: 0 14px;
  border: none;
  border-radius: var(--radius-xs);
  background: transparent;
  color: var(--color-text-secondary);
  font: inherit;
  font-size: 0.84rem;
  font-weight: 600;
  cursor: pointer;
  transition: background var(--duration-fast) var(--ease-out), color var(--duration-fast) var(--ease-out);
}

.platform-btn-active {
  background: var(--surface-card);
  border: 1px solid var(--color-border);
  color: var(--color-text);
}

.platform-btn:not(.platform-btn-active):hover {
  background: var(--color-surface-hover);
}

.platform-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.field-group-inline {
  display: inline-flex;
  align-items: center;
  gap: 10px;
}

.field-label {
  font-size: 0.82rem;
  font-weight: 600;
  color: var(--color-text-secondary);
}

.field-input-sm,
.field-textarea {
  border: 1px solid var(--color-border);
  background: var(--surface-muted);
  color: var(--color-text);
  font: inherit;
  transition: border-color var(--duration-fast) var(--ease-out), background var(--duration-fast) var(--ease-out), box-shadow var(--duration-fast) var(--ease-out);
}

.field-input-sm {
  width: 86px;
  min-height: 38px;
  padding: 0 10px;
  border-radius: var(--radius-md);
}

.field-textarea {
  width: 100%;
  min-height: 88px;
  padding: 12px 14px;
  resize: vertical;
  line-height: 1.6;
  border-radius: var(--radius-lg);
}

.field-input-sm:focus,
.field-textarea:focus {
  outline: none;
  border-color: var(--color-border-accent);
  background: var(--surface-card);
  box-shadow: var(--focus-ring);
}

.action-row {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.btn-primary,
.btn-secondary,
.btn-copy {
  min-height: 38px;
  padding: 0 var(--space-md);
  border-radius: var(--radius-sm);
}

.feishu-form {
  display: grid;
  gap: var(--space-xs);
  width: 100%;
  max-width: 460px;
  padding: var(--space-sm);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--surface-muted);
}

.feishu-hint { margin: 0; color: var(--color-text-muted); font-size: 0.78rem; line-height: 1.5; }

.feishu-actions { display: flex; justify-content: flex-end; gap: var(--space-xs); margin-top: var(--space-xs); }

.btn-primary:disabled,
.btn-secondary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
  transform: none;
}

.error-text {
  margin: 0;
  color: var(--color-danger);
  font-size: 0.85rem;
}

@media (max-width: 720px) {
  .drop-zone,
  .result-head {
    grid-template-columns: 1fr;
  }

  .btn-primary,
  .btn-secondary,
  .btn-copy,
  .btn-export-feishu {
    width: 100%;
  }
}

.thumb-item {
  cursor: pointer;
}

.thumb-item:hover .thumb-img {
  opacity: 0.85;
  transition: opacity var(--duration-fast) var(--ease-out);
}

.platform-position-hint {
  margin: 0;
  padding: 10px 14px;
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border-accent);
  background: var(--color-surface-highlight);
  color: var(--color-text-secondary);
  font-size: 0.84rem;
  line-height: 1.6;
}
</style>
