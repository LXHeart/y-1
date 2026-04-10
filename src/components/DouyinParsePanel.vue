<template>
  <section v-if="loading || error || extractedVideo" class="result-panel glass-card fade-in">
    <div v-if="loading" class="loading-state">
      <div class="spinner" />
      <div>
        <p class="loading-title">正在提取视频</p>
        <p class="loading-copy">会先尝试匿名链路，必要时你也可以启用扫码登录增强。</p>
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
          <p class="eyebrow">视频结果</p>
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
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { ExtractedDouyinVideoPayload } from '../types/douyin'

const props = defineProps<{
  extractedVideo: ExtractedDouyinVideoPayload | null
  loading: boolean
  error: string
}>()

defineEmits<{
  retry: []
}>()

const displayTitle = computed(() => {
  if (!props.extractedVideo) {
    return ''
  }

  return props.extractedVideo.title || '已提取到可播放视频'
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
.result-notes p {
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
.result-notes p {
  color: var(--color-text-secondary);
}

.error-state {
  align-content: center;
}

.eyebrow {
  font-size: 0.76rem;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--color-text-muted);
}

.result-header {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 148px;
  gap: 18px;
  align-items: start;
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

.cover-image {
  width: 148px;
  aspect-ratio: 3 / 4;
  object-fit: cover;
  border-radius: 18px;
  border: 1px solid var(--color-border);
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

.btn-primary {
  background: var(--gradient-accent);
  color: white;
  font-weight: 700;
}

.btn-secondary {
  border: 1px solid var(--color-border);
  background: rgba(255,255,255,0.04);
  color: var(--color-text);
}

.btn-primary:hover,
.btn-secondary:hover {
  transform: translateY(-1px);
}

@media (max-width: 768px) {
  .result-header {
    grid-template-columns: 1fr;
  }

  .cover-image {
    width: min(220px, 100%);
  }
}
</style>
