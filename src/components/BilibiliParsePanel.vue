<template>
  <section v-if="loading || error || extractedVideo" class="result-panel glass-card fade-in">
    <div v-if="loading" class="loading-state">
      <div class="spinner" />
      <div>
        <p class="loading-title">正在提取视频</p>
        <p class="loading-copy">正在解析 B 站视频源；若命中 DASH 分离流，后端会自动合成带声音的预览与下载文件。</p>
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
          <p class="eyebrow">Bilibili 结果</p>
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
              <dt>播放模式</dt>
              <dd>{{ playbackModeLabel }}</dd>
            </div>
          </dl>
        </div>
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
        <p>{{ resultNote }}</p>
      </div>

      <div class="result-actions">
        <a class="btn-primary" :href="extractedVideo.downloadVideoUrl">下载视频</a>
        <a class="btn-secondary" :href="extractedVideo.proxyVideoUrl" target="_blank" rel="noreferrer">新标签页预览</a>
        <button class="btn-secondary" @click="$emit('retry')">重新提取</button>
      </div>

      <section class="analysis-panel" aria-labelledby="bilibili-analysis-heading">
        <div class="analysis-header">
          <div class="analysis-header-copy">
            <div>
              <p class="analysis-kicker">视频内容提取</p>
              <h3 id="bilibili-analysis-heading" class="analysis-title">结构化内容分析</h3>
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
          <p>仅支持分析 10 分钟以内的 B 站视频，分析时会自动按最长 30 秒分段，建议选择 30 秒到 2 分钟的视频。</p>
        </div>

        <template v-else-if="analysisSections.length">
          <div v-if="segmentedAnalysisHint" class="analysis-status analysis-status-info">
            <p>{{ segmentedAnalysisHint }}</p>
          </div>

          <div class="analysis-grid">
            <article v-for="section in analysisSections" :key="section.title" class="analysis-card">
              <p class="analysis-card-label">{{ section.title }}</p>
              <p class="analysis-card-copy">{{ section.content }}</p>
            </article>
          </div>
        </template>


        <div v-else class="analysis-status analysis-status-empty">
          <p>点击“分析视频”后再提取结构化内容分析结果。</p>
        </div>

        <p v-if="analysisRunIdText" class="analysis-run-id">{{ analysisRunIdText }}</p>
      </section>
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { BilibiliVideoAnalysisResult, ExtractedBilibiliVideoPayload } from '../types/bilibili'

interface AnalysisSection {
  title: string
  content: string
}

const maxAnalysisDurationSeconds = 10 * 60

const props = defineProps<{
  extractedVideo: ExtractedBilibiliVideoPayload | null
  loading: boolean
  error: string
  analysis: BilibiliVideoAnalysisResult | null
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

const playbackModeLabel = computed(() => {
  if (!props.extractedVideo) {
    return ''
  }

  return props.extractedVideo.playbackMode === 'dash'
    ? 'DASH 双轨后端合成'
    : '单流直连代理'
})

const resultNote = computed(() => {
  if (!props.extractedVideo) {
    return ''
  }

  return props.extractedVideo.playbackMode === 'dash'
    ? '当前样本为 B 站 DASH 音视频分离流，页面预览与下载均由后端合成为完整 MP4。'
    : '页面内预览和下载都走后端代理，当前样本可直接透传播放。'
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

const analysisRunIdText = computed(() => {
  if (props.analysis?.runId) {
    return `运行 ID：${props.analysis.runId}`
  }

  if (props.analysis?.runIds?.length) {
    return `运行 ID：${props.analysis.runIds.join('、')}`
  }

  return ''
})

const analysisSections = computed<AnalysisSection[]>(() => {
  const sections = [
    { title: '视频脚本', content: props.analysis?.videoScript },
    { title: '视频字幕', content: props.analysis?.videoCaptions },
    { title: '人物描述', content: props.analysis?.charactersDescription },
    { title: '场景描述', content: props.analysis?.sceneDescription },
    { title: '道具描述', content: props.analysis?.propsDescription },
    { title: '音色描述', content: props.analysis?.voiceDescription },
  ]

  return sections.flatMap((section) => {
    if (!section.content) {
      return []
    }

    return [{
      title: section.title,
      content: section.content,
    }]
  })
})
</script>

<style scoped>
.result-panel {
  border-radius: var(--radius-lg);
  padding: 24px;
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
  border: 3px solid rgba(255,255,255,0.15);
  border-top-color: var(--color-accent-2);
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
  font-size: 1.1rem;
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
  font-size: 0.76rem;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--color-text-muted);
}

.result-header {
  display: grid;
  gap: 18px;
}

.result-title {
  font-size: clamp(1.35rem, 1.1rem + 0.8vw, 1.9rem);
  line-height: 1.15;
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
  background: rgba(255,255,255,0.04);
}

.meta-list dt {
  margin: 0 0 4px;
  font-size: 0.76rem;
  color: var(--color-text-muted);
}

.meta-list dd {
  margin: 0;
  font-size: 0.95rem;
}

.video-shell {
  overflow: hidden;
  border-radius: 22px;
  border: 1px solid rgba(255,255,255,0.14);
  background: rgba(0,0,0,0.35);
}

.video-player {
  display: block;
  width: 100%;
  max-height: 72vh;
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
  border-radius: 20px;
  border: 1px solid var(--color-border);
  background: rgba(255,255,255,0.03);
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
  font-size: 1.05rem;
  color: var(--color-text);
}

.analysis-status {
  display: grid;
  gap: 10px;
  padding: 16px;
  border-radius: 16px;
  background: rgba(255,255,255,0.03);
  color: var(--color-text-secondary);
}

.analysis-status-loading {
  grid-template-columns: auto 1fr;
  align-items: center;
}

.analysis-status-error {
  border: 1px solid rgba(255,107,107,0.35);
  background: rgba(255,107,107,0.08);
}

.analysis-status-warning {
  border: 1px solid rgba(255,184,77,0.35);
  background: rgba(255,184,77,0.08);
}

.analysis-status-empty {
  border: 1px dashed var(--color-border);
}

.analysis-status-title,
.analysis-card-label {
  color: var(--color-text);
  font-weight: 700;
}

.analysis-grid {
  display: grid;
  gap: 12px;
}

.analysis-card {
  display: grid;
  gap: 8px;
  padding: 16px;
  border-radius: 16px;
  border: 1px solid var(--color-border);
  background: rgba(255,255,255,0.03);
}

.analysis-card-copy,
.analysis-run-id {
  color: var(--color-text-secondary);
  white-space: pre-wrap;
  word-break: break-word;
}

.btn-primary,
.btn-secondary {
  min-height: 44px;
  padding: 0 18px;
  border-radius: 999px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  text-decoration: none;
  cursor: pointer;
  transition: transform 150ms ease, opacity 150ms ease;
}

.btn-primary:hover,
.btn-secondary:hover {
  transform: translateY(-1px);
}

.btn-primary:disabled,
.btn-secondary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
  transform: none;
}

.btn-primary {
  border: none;
  color: #08111f;
  background: linear-gradient(135deg, var(--color-accent-2), var(--color-accent));
  box-shadow: 0 12px 24px rgba(0,0,0,0.22);
}

.btn-secondary {
  border: 1px solid var(--color-border);
  color: var(--color-text);
  background: rgba(255,255,255,0.04);
}

@media (max-width: 720px) {
  .result-panel {
    padding: 18px;
  }

  .analysis-header {
    align-items: stretch;
    flex-direction: column;
  }

  .btn-primary,
  .btn-secondary {
    width: 100%;
  }
}
</style>
