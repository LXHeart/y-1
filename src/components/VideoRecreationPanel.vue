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

    <section class="recent-generations" aria-labelledby="recent-generations-heading">
      <div class="recent-heading-row">
        <div>
          <p class="recent-kicker">生成记录</p>
          <h3 id="recent-generations-heading" class="recent-heading">最近产物</h3>
        </div>
        <button type="button" class="btn-secondary btn-sm" :disabled="recentLoading" @click="loadRecent">
          刷新
        </button>
      </div>

      <p v-if="recentError" class="recent-message recent-error">{{ recentError }}</p>
      <p v-else-if="recentLoading && recentItems.length === 0" class="recent-message">正在加载最近产物…</p>
      <p v-else-if="recentItems.length === 0" class="recent-message">还没有图片产物。</p>

      <div v-else class="recent-list">
        <article v-for="item in recentItems" :key="item.id" class="recent-row">
          <button type="button" class="recent-row-button" @click="toggleRecent(item.id)">
            <span>
              <strong>{{ item.kind === 'asset_image' ? '资产图' : '场景图' }} · {{ item.resultTitle }}</strong>
              <small>{{ formatGenerationTime(item.createdAt) }} · {{ item.mode === 'task' ? '任务' : '独立' }}</small>
            </span>
            <span aria-hidden="true">{{ expandedRecentId === item.id ? '−' : '+' }}</span>
          </button>

          <div v-if="expandedRecentId === item.id" class="recent-detail">
            <p v-if="recentDetailLoading" class="recent-message">正在加载产物…</p>
            <p v-else-if="recentDetailError" class="recent-message recent-error">{{ recentDetailError }}</p>
            <div v-else-if="recentDetail" class="recent-media-grid">
              <figure v-for="media in recentDetail.resultMedia" :key="media.mediaId" class="recent-media">
                <img
                  v-if="media.available && media.imageUrl"
                  :src="media.imageUrl"
                  alt="生成图片"
                  @click="openLightbox(media.imageUrl)"
                />
                <div v-else class="recent-expired">已过期</div>
                <figcaption>
                  <button
                    v-if="media.available && media.imageUrl"
                    type="button"
                    class="btn-secondary btn-xs"
                    :disabled="savingMediaId === media.mediaId"
                    @click="saveToLibrary(media)"
                  >
                    {{ savingMediaId === media.mediaId ? '保存中…' : savedMediaIds.has(media.mediaId) ? '已保存' : '保存到素材库' }}
                  </button>
                </figcaption>
              </figure>
            </div>
          </div>
        </article>
      </div>
    </section>

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
import type { VideoTaskExecutionContext } from '../types/video-recreation'
import { useVideoRecreation } from '../composables/useVideoRecreation'
import { getCreationGeneration, listCreationGenerations } from '../composables/useCreationGenerations'
import { useGrasslandGovernance } from '../composables/useGrasslandGovernance'
import type { RunFn } from '../composables/grassland-http'
import type {
  CreationGenerationDetail,
  CreationGenerationResultMediaItem,
  CreationGenerationSummary,
} from '../types/grassland/creation-generation'

const props = defineProps<{
  scenes: VideoScene[]
  overallStyle?: string
  taskContext?: VideoTaskExecutionContext
}>()

const {
  sceneImages,
  allImagesLoading,
  generateSceneImage,
  generateAllImages,
  copyFullScript,
} = useVideoRecreation(props.taskContext)

const copied = ref(false)
const lightboxSrc = ref('')
const recentItems = ref<CreationGenerationSummary[]>([])
const recentLoading = ref(false)
const recentError = ref('')
const expandedRecentId = ref('')
const recentDetail = ref<CreationGenerationDetail | null>(null)
const recentDetailLoading = ref(false)
const recentDetailError = ref('')
const savingMediaId = ref('')
const savedMediaIds = ref(new Set<string>())
const directRun: RunFn = async operation => operation()
const { uploadContentAssetFile } = useGrasslandGovernance(directRun)

function getImageState(index: number) {
  return sceneImages.value.get(index)
}

async function handleGenerateOne(index: number): Promise<void> {
  await generateSceneImage(index, props.scenes[index], props.overallStyle)
  await loadRecent()
}

async function handleGenerateAll(): Promise<void> {
  await generateAllImages(props.scenes, props.overallStyle)
  await loadRecent()
}

async function loadRecent(): Promise<void> {
  if (recentLoading.value) return
  recentLoading.value = true
  recentError.value = ''
  try {
    const [assets, scenes] = await Promise.all([
      listCreationGenerations({ kind: 'asset_image', limit: 10 }),
      listCreationGenerations({ kind: 'scene_image', limit: 10 }),
    ])
    recentItems.value = [...assets.items, ...scenes.items]
      .sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt))
      .slice(0, 12)
  } catch (cause) {
    recentError.value = cause instanceof Error ? cause.message : '最近产物加载失败'
  } finally {
    recentLoading.value = false
  }
}

async function toggleRecent(id: string): Promise<void> {
  if (expandedRecentId.value === id) {
    expandedRecentId.value = ''
    recentDetail.value = null
    return
  }
  expandedRecentId.value = id
  recentDetail.value = null
  recentDetailLoading.value = true
  recentDetailError.value = ''
  try {
    recentDetail.value = await getCreationGeneration(id)
  } catch (cause) {
    recentDetailError.value = cause instanceof Error ? cause.message : '产物详情加载失败'
  } finally {
    recentDetailLoading.value = false
  }
}

async function saveToLibrary(media: CreationGenerationResultMediaItem): Promise<void> {
  if (!media.imageUrl || savingMediaId.value) return
  savingMediaId.value = media.mediaId
  recentDetailError.value = ''
  try {
    const response = await fetch(media.imageUrl, { credentials: 'include' })
    if (!response.ok) throw new Error('生成图片读取失败，可能已经过期')
    const blob = await response.blob()
    const extension = blob.type === 'image/jpeg' ? 'jpg' : blob.type === 'image/webp' ? 'webp' : 'png'
    const file = new File([blob], `creation-${media.mediaId}.${extension}`, {
      type: blob.type || 'image/png',
    })
    const uploaded = await uploadContentAssetFile(file)
    if (!uploaded) throw new Error('素材保存失败')
    savedMediaIds.value = new Set(savedMediaIds.value).add(media.mediaId)
  } catch (cause) {
    recentDetailError.value = cause instanceof Error ? cause.message : '素材保存失败'
  } finally {
    savingMediaId.value = ''
  }
}

function formatGenerationTime(value: string): string {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN')
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

onMounted(() => {
  document.addEventListener('keydown', handleLightboxKey)
  loadRecent()
})
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
  color: var(--color-danger);
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
  border-color: var(--color-accent);
  color: var(--color-accent);
  background: color-mix(in srgb, var(--color-accent) 5%, transparent);
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

.recent-generations {
  display: grid;
  gap: 12px;
  padding-top: 16px;
  border-top: 1px solid var(--color-border);
}

.recent-heading-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.recent-kicker,
.recent-heading,
.recent-message {
  margin: 0;
}

.recent-kicker {
  color: var(--color-text-muted);
  font-size: 0.75rem;
}

.recent-heading {
  margin-top: 2px;
  color: var(--color-text);
  font-size: 1rem;
}

.recent-list {
  display: grid;
}

.recent-row {
  border-top: 1px solid var(--color-border);
}

.recent-row:last-child {
  border-bottom: 1px solid var(--color-border);
}

.recent-row-button {
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

.recent-row-button > span:first-child {
  display: grid;
  gap: 4px;
}

.recent-row-button small,
.recent-message {
  color: var(--color-text-muted);
  font-size: 0.8rem;
}

.recent-detail {
  padding: 0 0 14px;
}

.recent-media-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  gap: 10px;
}

.recent-media {
  display: grid;
  gap: 7px;
  margin: 0;
}

.recent-media img,
.recent-expired {
  width: 100%;
  aspect-ratio: 1;
  border: 1px solid var(--color-border);
  border-radius: 6px;
  object-fit: cover;
}

.recent-media img {
  cursor: zoom-in;
}

.recent-expired {
  display: grid;
  place-items: center;
  background: var(--surface-page);
  color: var(--color-text-muted);
  font-size: 0.82rem;
}

.recent-error {
  color: var(--color-danger);
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
  color: var(--color-on-accent);
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
