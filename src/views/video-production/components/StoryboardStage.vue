<script setup lang="ts">
import { useRouter } from 'vue-router'
import SafetyFindingsPanel from '../../../components/SafetyFindingsPanel.vue'
import ShotEditorCard from './ShotEditorCard.vue'
import type { SafetyReport } from '../../../types/content-safety'
import type { StoryboardShot, VideoProductionImage } from '../../../types/video-production'

/**
 * 第二步：编辑分镜（任务书 #91 V2 自 VideoProductionView.vue 模板 193–291 整段迁入，纯搬运；
 * storyboardLoading v-if/v-else 对整段搬；goCanvasMode 随迁——router 为单例，子组件可用）。
 * shots/storyboardId 留视图 props 下传（TC vm 断言）。
 */
const props = defineProps<{
  goBackToUpload: () => void
  shots: StoryboardShot[]
  images: VideoProductionImage[]
  totalPlannedSeconds: number
  targetDurationSeconds: number
  referenceApplied: boolean
  restoredStoryboardId: string
  storyboardId: string
  storyboardLoading: boolean
  canAddShot: boolean
  anchorGenerating: Record<string, boolean>
  anchorErrors: Record<string, string>
  updateShot: (index: number, patch: Partial<StoryboardShot>) => void
  removeShot: (index: number) => void
  addShot: () => void
  generateStoryboard: () => void
  generateAnchorImage: (shotId: string) => void
  openLightbox: (src: string) => void
  error: string
  safetyReport: SafetyReport | null
  narrationText: string
  beginGeneration: () => void
}>()

const emit = defineEmits<{ 'update:safetyReport': [value: SafetyReport] }>()

const router = useRouter()

/** C3 双模式互切：同一 storyboard 进画布专业模式（仅前端路由，后端零感知）。 */
function goCanvasMode(): void {
  if (!props.storyboardId) return
  router.push({ name: 'video-canvas', query: { storyboard: props.storyboardId } })
}
</script>

<template>
  <section class="stage-card gl-zone fade-in">
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
      <h2 class="card-title">编辑分镜</h2>
      <p class="field-note">
        AI 生成了 {{ shots.length }} 个镜头、合计约 {{ totalPlannedSeconds }} 秒（目标 {{ targetDurationSeconds }} 秒）。
        你可以逐镜编辑画面、旁白、运镜与锚定图。
      </p>
      <p v-if="referenceApplied" class="field-note reference-applied-note">已包含参考输入（参考视频分析 / 热点主题），见自定义要求。</p>
      <p v-if="restoredStoryboardId" class="gl-hint restore-hint" data-test="restored-storyboard-hint">
        已载入分镜 {{ restoredStoryboardId.slice(0, 8) }}，可直接编辑或进入生成与挑选
      </p>
      <button
        v-if="storyboardId"
        type="button"
        class="btn-secondary canvas-entry"
        data-test="open-canvas-mode"
        @click="goCanvasMode"
      >
        在画布中编排（专业模式）
      </button>
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

    <div v-if="storyboardLoading" class="stream-area">
      <p class="field-note stream-badge">
        <span class="stream-dot"></span>
        分镜生成中，逐镜送达…
      </p>
      <div v-for="shot in shots" :key="shot.seq" class="shot-card" data-test="streaming-shot">
        <span class="shot-badge">第 {{ shot.seq }} 镜</span>
        <span class="shot-visual-preview">{{ shot.visual }}</span>
      </div>
    </div>

    <template v-else>
      <ShotEditorCard
        v-for="(shot, index) in shots"
        :key="shot.seq"
        :shot="shot"
        :index="index"
        :images="images"
        :anchor-generating="anchorGenerating"
        :anchor-errors="anchorErrors"
        :storyboard-loading="storyboardLoading"
        @update-shot="updateShot"
        @remove-shot="removeShot"
        @open-lightbox="openLightbox"
        @generate-anchor="generateAnchorImage"
      />

      <div class="action-row">
        <button type="button" class="btn-secondary" :disabled="!canAddShot" data-test="add-shot" @click="addShot">
          添加镜头
        </button>
        <button type="button" class="btn-secondary" :disabled="storyboardLoading" @click="generateStoryboard">
          重新生成分镜
        </button>
      </div>
    </template>

    <div v-if="error" class="error-hint">{{ error }}</div>

    <SafetyFindingsPanel
      v-if="safetyReport"
      :report="safetyReport"
      :text="narrationText"
      @updated="emit('update:safetyReport', $event)"
    />

    <div class="action-row">
      <button
        class="btn-primary gl-btn-primary"
        :disabled="storyboardLoading || shots.length === 0"
        data-test="begin-generation"
        @click="beginGeneration"
      >
        进入生成与挑选
      </button>
    </div>
  </section>
</template>

<style scoped>
/* ===== 自 VideoProductionView.vue 随迁（两段重复定义级联顺序原样保留） ===== */
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

.reference-applied-note {
  color: color-mix(in srgb, var(--color-success) 90%, transparent);
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
  border-radius: var(--radius-pill);
  background: var(--color-accent);
  animation: pulse 1.2s ease-in-out infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.3; }
}

/* 流式态逐镜卡（streaming-shot）与编辑卡（ShotEditorCard）同名类各持一份 scoped 副本 */
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

.shot-badge {
  display: inline-flex;
  align-items: center;
  padding: 2px 10px;
  border-radius: var(--radius-pill);
  font-size: var(--text-xs);
  font-weight: 600;
  background: color-mix(in srgb, var(--color-accent) 16%, transparent);
  color: var(--color-accent);
}

.shot-visual-preview {
  font-size: var(--text-sm);
  color: var(--color-text-secondary);
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

/* #69 卡C：分镜恢复提示 */
.restore-hint { margin-bottom: var(--space-xs); }

.canvas-entry { margin-top: var(--space-sm); }
</style>
