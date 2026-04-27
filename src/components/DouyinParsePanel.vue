<template>
  <section v-if="loading || error || extractedVideo" class="result-panel glass-card fade-in">
    <div v-if="loading" class="loading-state">
      <div class="spinner" />
      <div>
        <p class="loading-title">正在提取视频</p>
        <p class="loading-copy">会先尝试匿名链路；命中校验页时，再启用扫码登录增强。</p>
      </div>
    </div>

    <div v-else-if="error" class="error-state">
      <div>
        <p class="error-title">提取失败</p>
        <p class="error-copy">{{ error }}</p>
      </div>
      <button class="btn-secondary" @click="$emit('retry')">重试</button>
    </div>

    <template v-else-if="extractedVideo">
      <header class="result-header">
        <div class="result-copy">
          <p class="eyebrow">抖音结果</p>
          <h2 class="result-title">{{ displayTitle }}</h2>
          <dl class="meta-list">
            <div v-if="extractedVideo.author">
              <dt>作者</dt>
              <dd>{{ extractedVideo.author }}</dd>
            </div>
            <div v-if="extractedVideo.videoId">
              <dt>视频 ID</dt>
              <dd>{{ extractedVideo.videoId }}</dd>
            </div>
            <div v-if="durationLabel">
              <dt>视频时长</dt>
              <dd>{{ durationLabel }}</dd>
            </div>
            <div>
              <dt>链路</dt>
              <dd>{{ extractedVideo.usedSession ? '登录增强' : '匿名提取' }}</dd>
            </div>
          </dl>
        </div>
        <img v-if="extractedVideo.coverUrl" :src="extractedVideo.coverUrl" alt="抖音视频封面" class="cover-image">
      </header>

      <div class="video-shell">
        <video
          :src="extractedVideo.proxyVideoUrl"
          :poster="extractedVideo.coverUrl"
          class="video-player"
          controls
          playsinline
          preload="metadata"
        />
      </div>

      <div class="result-notes">
        <p>页面内预览走后端视频代理，下载视频和提取音频都会返回附件响应，避免只在浏览器里打开。</p>
      </div>

      <div class="result-actions">
        <a class="btn-primary" :href="extractedVideo.downloadVideoUrl">下载视频</a>
        <a class="btn-secondary" :href="extractedVideo.downloadAudioUrl">提取音频</a>
        <a class="btn-secondary" :href="extractedVideo.proxyVideoUrl" target="_blank" rel="noreferrer">新标签页预览</a>
        <button class="btn-secondary" @click="$emit('retry')">重新提取</button>
      </div>

      <section class="analysis-panel" aria-labelledby="analysis-heading">
        <div class="analysis-header">
          <div class="analysis-header-copy">
            <div>
              <p class="analysis-kicker">视频内容提取</p>
              <h3 id="analysis-heading" class="analysis-title">提取结果</h3>
            </div>
            <p class="analysis-hint">分析时会自动按最长 30 秒分段；30 秒到 2 分钟的视频通常效果最好，当前最多支持 10 分钟。</p>
          </div>
          <button class="btn-secondary" :disabled="analysisLoading || isAnalysisTooLong" @click="$emit('retry-analysis')">
            {{ analysisActionLabel }}
          </button>
        </div>

        <div v-if="analysisLoading" class="analysis-status analysis-status-loading">
          <div class="spinner spinner-small" />
          <p>正在提取视频内容…</p>
        </div>

        <div v-else-if="analysisError" class="analysis-status analysis-status-error">
          <p class="analysis-status-title">视频内容提取失败</p>
          <p>{{ analysisError }}</p>
        </div>

        <div v-else-if="isAnalysisTooLong" class="analysis-status analysis-status-warning">
          <p class="analysis-status-title">当前视频暂不支持分析</p>
          <p>仅支持分析 10 分钟以内的抖音视频，分析时会自动按最长 30 秒分段，建议选择 30 秒到 2 分钟的视频。</p>
        </div>

        <template v-else-if="analysisHasContent">
          <div v-if="segmentedAnalysisHint" class="analysis-status analysis-status-info">
            <p>{{ segmentedAnalysisHint }}</p>
          </div>

          <div class="analysis-grid">
            <article v-for="section in analysisSections" :key="section.key" class="analysis-card">
              <p class="analysis-card-label">{{ section.label }}</p>
              <p class="analysis-card-copy">{{ section.content }}</p>
            </article>
          </div>
        </template>

        <div v-else class="analysis-status analysis-status-empty">
          <p>点击“分析视频”后先提取视频内容。</p>
        </div>

        <p v-if="analysisRunIdText" class="analysis-run-id">{{ analysisRunIdText }}</p>
      </section>
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { DouyinVideoAnalysisResult, ExtractedDouyinVideoPayload } from '../types/douyin'

const maxAnalysisDurationSeconds = 10 * 60

const props = defineProps<{
  extractedVideo: ExtractedDouyinVideoPayload | null
  loading: boolean
  error: string
  analysis: DouyinVideoAnalysisResult | null
  analysisLoading: boolean
  analysisError: string
}>()

defineEmits<{
  retry: []
  'retry-analysis': []
}>()

function formatDurationLabel(durationSeconds: number): string {
  const totalSeconds = Math.max(1, Math.ceil(durationSeconds))
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60

  if (minutes === 0) {
    return `${seconds} 秒`
  }

  if (seconds === 0) {
    return `${minutes} 分钟`
  }

  return `${minutes} 分 ${seconds} 秒`
}

const displayTitle = computed(() => {
  if (!props.extractedVideo) {
    return ''
  }

  return props.extractedVideo.title || '已提取到可播放视频'
})

const durationLabel = computed(() => {
  if (!props.extractedVideo?.durationSeconds) {
    return ''
  }

  return formatDurationLabel(props.extractedVideo.durationSeconds)
})

const isAnalysisTooLong = computed(() => {
  return (props.extractedVideo?.durationSeconds || 0) > maxAnalysisDurationSeconds
})

const hasAttemptedAnalysis = computed(() => {
  return Boolean(props.analysis || props.analysisError)
})

const analysisActionLabel = computed(() => {
  if (props.analysisLoading) {
    return '提取中…'
  }

  if (isAnalysisTooLong.value) {
    return '超过 10 分钟'
  }

  return hasAttemptedAnalysis.value ? '重新分析' : '分析视频'
})

const segmentedAnalysisHint = computed(() => {
  if (!props.analysis?.segmented || !props.analysis.clipCount) {
    return ''
  }

  return `该结果由 ${props.analysis.clipCount} 个最长 30 秒的片段合并生成。`
})

const analysisSections = computed(() => {
  if (!props.analysis) {
    return []
  }

  return [
    { key: 'videoCaptions', label: '字幕提取', content: props.analysis.videoCaptions },
    { key: 'videoScript', label: '视频脚本', content: props.analysis.videoScript },
    { key: 'charactersDescription', label: '人物描述', content: props.analysis.charactersDescription },
    { key: 'sceneDescription', label: '场景描述', content: props.analysis.sceneDescription },
    { key: 'propsDescription', label: '道具描述', content: props.analysis.propsDescription },
    { key: 'voiceDescription', label: '音色描述', content: props.analysis.voiceDescription },
  ].filter((section): section is { key: string, label: string, content: string } => Boolean(section.content?.trim()))
})

const analysisHasContent = computed(() => analysisSections.value.length > 0)

const analysisRunIdText = computed(() => {
  if (props.analysis?.runId) {
    return `运行 ID：${props.analysis.runId}`
  }

  if (props.analysis?.runIds?.length) {
    return `运行 ID：${props.analysis.runIds.join('、')}`
  }

  return ''
})
</script>

<style scoped>
.result-panel {
  display: grid;
  gap: 20px;
}

.loading-state,
.error-state {
  min-height: 280px;
  display: grid;
  place-items: center;
  text-align: center;
  gap: 16px;
}

.loading-state {
  color: var(--color-text-secondary);
}

.spinner {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  border: 3px solid rgba(255,255,255,0.12);
  border-top-color: var(--color-accent);
  animation: spin 0.9s linear infinite;
}

.spinner-small {
  width: 18px;
  height: 18px;
  border-width: 2px;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.loading-title,
.error-title,
.eyebrow,
.result-title,
.error-copy,
.loading-copy,
.result-notes p,
.analysis-kicker,
.analysis-title,
.analysis-hint,
.analysis-status-title,
.analysis-status p,
.analysis-card-label,
.analysis-card-copy,
.analysis-run-id {
  margin: 0;
}

.loading-title,
.error-title {
  font-size: 1.08rem;
  color: var(--color-text);
  font-weight: 700;
}

.loading-copy,
.error-copy,
.result-notes p,
.analysis-hint {
  color: var(--color-text-secondary);
}

.error-state {
  align-content: center;
}

.eyebrow,
.analysis-kicker {
  font-size: 0.75rem;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--color-text-muted);
  font-weight: 600;
}

.result-header {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 148px;
  gap: 18px;
  align-items: start;
}

.result-title {
  font-size: clamp(1.32rem, 1.1rem + 0.8vw, 1.85rem);
  line-height: 1.12;
}

.result-copy {
  display: grid;
  gap: 10px;
}

.meta-list {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin: 0;
}

.meta-list div {
  padding: 10px 12px;
  border-radius: 14px;
  border: 1px solid var(--color-border);
  background: var(--surface-page);
}

.meta-list dt {
  margin: 0 0 4px;
  font-size: 0.75rem;
  color: var(--color-text-muted);
}

.meta-list dd {
  margin: 0;
  font-size: 0.92rem;
  color: var(--color-text);
}

.cover-image {
  width: 148px;
  aspect-ratio: 3 / 4;
  object-fit: cover;
  border-radius: 18px;
  border: 1px solid var(--color-border);
}

.video-shell {
  overflow: hidden;
  border-radius: var(--radius-xl);
  border: 1px solid var(--color-border);
  background: #06080d;
}

.video-player {
  display: block;
  width: 100%;
  max-height: 72vh;
}

.result-notes {
  padding: 14px 16px;
  border-radius: var(--radius-lg);
  background: var(--surface-page);
  border: 1px solid var(--color-border);
}

.result-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.analysis-panel {
  display: grid;
  gap: 16px;
  padding: 18px;
  border-radius: var(--radius-xl);
  border: 1px solid var(--color-border);
  background: var(--surface-page);
}

.analysis-header {
  display: flex;
  align-items: start;
  justify-content: space-between;
  gap: 12px;
}

.analysis-header-copy {
  display: grid;
  gap: 6px;
}

.analysis-title {
  font-size: 1.03rem;
  color: var(--color-text);
}

.analysis-status {
  display: grid;
  gap: 10px;
  padding: 16px;
  border-radius: var(--radius-lg);
  background: var(--surface-card);
  color: var(--color-text-secondary);
  border: 1px solid var(--color-border);
}

.analysis-status-loading {
  grid-template-columns: auto 1fr;
  align-items: center;
}

.analysis-status-error {
  border-color: rgba(239, 107, 107, 0.28);
  background: rgba(239, 107, 107, 0.08);
}

.analysis-status-warning {
  border-color: rgba(255, 184, 77, 0.28);
  background: rgba(255, 184, 77, 0.08);
}

.analysis-status-info {
  border-color: rgba(114, 132, 248, 0.22);
  background: rgba(114, 132, 248, 0.08);
}

.analysis-status-empty {
  border-style: dashed;
}

.analysis-status-title,
.analysis-card-label {
  color: var(--color-text);
}

.analysis-grid {
  display: grid;
  gap: 12px;
}

.analysis-card {
  display: grid;
  gap: 8px;
  padding: 16px;
  border-radius: var(--radius-lg);
  border: 1px solid var(--color-border);
  background: var(--surface-card);
}

.analysis-card-label {
  font-size: 0.8rem;
  color: var(--color-text-muted);
}

.analysis-card-copy,
.analysis-run-id {
  white-space: pre-wrap;
  word-break: break-word;
}

.analysis-card-copy {
  line-height: 1.7;
  color: var(--color-text);
}

.analysis-run-id {
  font-size: 0.8rem;
  color: var(--color-text-muted);
}

.btn-primary,
.btn-secondary {
  min-height: 42px;
  padding: 0 18px;
  border-radius: var(--radius-md);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  text-decoration: none;
  cursor: pointer;
  transition:
    transform 150ms ease,
    opacity 150ms ease,
    background 150ms ease,
    border-color 150ms ease;
}

.btn-primary {
  background: var(--color-accent);
  color: white;
  font-weight: 700;
  border: none;
}

.btn-secondary {
  border: 1px solid var(--color-border);
  background: var(--surface-card);
  color: var(--color-text);
}

.btn-primary:hover,
.btn-secondary:hover {
  transform: translateY(-1px);
}

.btn-primary:hover {
  background: var(--color-accent-2);
}

.btn-secondary:hover {
  border-color: var(--color-border-hover);
  background: var(--color-surface-hover);
}

.btn-secondary:disabled {
  opacity: 0.7;
  cursor: not-allowed;
  transform: none;
}

@media (max-width: 768px) {
  .result-header {
    grid-template-columns: 1fr;
  }

  .cover-image {
    width: min(220px, 100%);
  }

  .analysis-header {
    flex-direction: column;
  }
}
</style>
