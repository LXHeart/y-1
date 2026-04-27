<template>
  <div class="video-analysis">
    <section class="input-column">
      <article class="editor-card glass-card">
        <header class="card-head">
          <div class="platform-switch" role="tablist" aria-label="视频平台选择">
            <button
              class="platform-tab"
              :class="{ 'platform-tab-active': activePlatform === 'douyin' }"
              :aria-selected="activePlatform === 'douyin'"
              type="button"
              @click="handleSwitchPlatform('douyin')"
            >
              抖音
            </button>
            <button
              class="platform-tab"
              :class="{ 'platform-tab-active': activePlatform === 'bilibili' }"
              :aria-selected="activePlatform === 'bilibili'"
              type="button"
              @click="handleSwitchPlatform('bilibili')"
            >
              B 站
            </button>
          </div>
          <h2 class="card-title">{{ inputTitle }}</h2>
        </header>

        <label class="field-label" for="video-input">{{ inputLabel }}</label>
        <textarea
          id="video-input"
          v-model="videoInput"
          class="input-area"
          rows="7"
          :placeholder="inputPlaceholder"
          :disabled="isCurrentPlatformParseLoading"
        />

        <p class="field-note">{{ inputNote }}</p>

        <div class="action-row">
          <button
            class="btn-primary"
            :disabled="isCurrentPlatformParseLoading || !videoInput.trim()"
            @click="handleExtractVideo"
          >
            {{ isCurrentPlatformParseLoading ? '提取中…' : '提取视频' }}
          </button>
          <button class="btn-secondary" :disabled="isCurrentPlatformParseLoading" @click="handleReset">
            清空
          </button>
        </div>

        <button
          v-if="activePlatform === 'douyin'"
          class="toggle-link"
          :disabled="parseLoading"
          @click="showSessionPanel = !showSessionPanel"
        >
          {{ showSessionPanel ? '收起登录增强' : '抖音解析失败时再尝试登录增强' }}
        </button>
      </article>

      <DouyinSessionPanel
        v-if="activePlatform === 'douyin' && showSessionPanel"
        :session="douyinSession"
        :loading="sessionLoading"
        :error="sessionError"
        @start="startDouyinSession"
        @refresh="refreshDouyinSession"
        @logout="logoutDouyinSession"
      />
    </section>

    <section class="preview-column">
      <DouyinParsePanel
        v-if="activePlatform === 'douyin'"
        :extracted-video="extractedVideo"
        :loading="parseLoading"
        :error="parseError"
        :analysis="videoAnalysis"
        :analysis-loading="analysisLoading"
        :analysis-error="analysisError"
        @retry="handleExtractDouyinVideo"
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
        @retry="handleExtractBilibiliVideo"
        @retry-analysis="handleRetryBilibiliAnalysis"
      />

      <section v-if="showEmptyState" class="empty-card glass-card">
        <div class="empty-icon" aria-hidden="true">
          <svg width="40" height="40" viewBox="0 0 24 24" fill="none">
            <rect x="3" y="5" width="18" height="14" rx="3" stroke="currentColor" stroke-width="1.4"/>
            <path d="M10 9.5l4.5 2.5-4.5 2.5V9.5z" fill="currentColor" opacity="0.4"/>
          </svg>
        </div>
        <h2 class="empty-title">{{ emptyTitle }}</h2>
        <p class="empty-copy">{{ emptyCopy }}</p>
      </section>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import BilibiliParsePanel from './BilibiliParsePanel.vue'
import DouyinParsePanel from './DouyinParsePanel.vue'
import DouyinSessionPanel from './DouyinSessionPanel.vue'
import { useBilibiliParse } from '../composables/useBilibiliParse'
import { useBilibiliVideoAnalysis } from '../composables/useBilibiliVideoAnalysis'
import { useDouyinParse } from '../composables/useDouyinParse'
import { useDouyinSession } from '../composables/useDouyinSession'
import { useDouyinVideoAnalysis } from '../composables/useDouyinVideoAnalysis'

const autoOpenSessionErrorPatterns = [
  '校验',
  '验证码',
  '验证',
  'challenge',
  'captcha',
  '扫码',
  '登录',
  'session',
]

type SupportedPlatform = 'douyin' | 'bilibili'

function shouldAutoOpenSessionPanel(errorMessage: string): boolean {
  const normalizedError = errorMessage.toLowerCase()
  return autoOpenSessionErrorPatterns.some((pattern) => normalizedError.includes(pattern))
}

const inputTitleByPlatform: Record<SupportedPlatform, string> = {
  douyin: '抖音分享文本或链接',
  bilibili: 'B 站分享文本或链接',
}

const inputLabelByPlatform: Record<SupportedPlatform, string> = {
  douyin: '把抖音 App 复制出来的整段分享文本贴进来',
  bilibili: '把 B 站 App 或网页复制出来的分享文本贴进来',
}

const inputPlaceholderByPlatform: Record<SupportedPlatform, string> = {
  douyin: '例如：7.54 复制打开抖音 https://v.douyin.com/xxxx/',
  bilibili: '例如：https://www.bilibili.com/video/BV1xxxxxxxxx',
}

const inputNoteByPlatform: Record<SupportedPlatform, string> = {
  douyin: '预览和下载都由后端代理处理，避免浏览器直接暴露真实视频地址。',
  bilibili: '预览和下载都走后端代理；当前已支持 B 站单流直连与 DASH 音视频分离资源合成为完整 MP4。',
}

const emptyTitleByPlatform: Record<SupportedPlatform, string> = {
  douyin: '右侧会出现视频预览',
  bilibili: '右侧会出现 B 站视频预览',
}

const emptyCopyByPlatform: Record<SupportedPlatform, string> = {
  douyin: '提取成功后，你可以直接在页面里播放视频、下载 mp4，或者进一步提取 mp3 音频。',
  bilibili: '提取成功后，你可以直接在页面里播放视频或下载 mp4；DASH 双轨样本会由后端自动合成为完整 MP4。',
}

const activePlatform = ref<SupportedPlatform>('douyin')
const videoInput = ref('')
const showSessionPanel = ref(false)

const {
  extractedVideo,
  loading: parseLoading,
  error: parseError,
  extractVideo,
  reset: resetParse,
} = useDouyinParse()

const {
  analysis: videoAnalysis,
  loading: analysisLoading,
  error: analysisError,
  analyzeVideo,
  reset: resetAnalysis,
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

const {
  state: douyinSession,
  loading: sessionLoading,
  error: sessionError,
  refresh: refreshDouyinSession,
  start: startDouyinSession,
  logout: logoutDouyinSession,
} = useDouyinSession()

const isCurrentPlatformParseLoading = computed(() => {
  return activePlatform.value === 'douyin' ? parseLoading.value : bilibiliParseLoading.value
})

const showEmptyState = computed(() => {
  if (activePlatform.value === 'douyin') {
    return !parseLoading.value && !parseError.value && !extractedVideo.value
  }

  return !bilibiliParseLoading.value && !bilibiliParseError.value && !bilibiliExtractedVideo.value
})

const inputTitle = computed(() => inputTitleByPlatform[activePlatform.value])
const inputLabel = computed(() => inputLabelByPlatform[activePlatform.value])
const inputPlaceholder = computed(() => inputPlaceholderByPlatform[activePlatform.value])
const inputNote = computed(() => inputNoteByPlatform[activePlatform.value])
const emptyTitle = computed(() => emptyTitleByPlatform[activePlatform.value])
const emptyCopy = computed(() => emptyCopyByPlatform[activePlatform.value])

void refreshDouyinSession()

async function handleRetryDouyinAnalysis(): Promise<void> {
  const proxyVideoUrl = extractedVideo.value?.proxyVideoUrl
  if (!proxyVideoUrl) return
  await analyzeVideo(proxyVideoUrl)
}

async function handleRetryBilibiliAnalysis(): Promise<void> {
  const proxyVideoUrl = bilibiliExtractedVideo.value?.proxyVideoUrl
  if (!proxyVideoUrl) return
  await analyzeBilibiliVideo(proxyVideoUrl)
}

async function handleExtractDouyinVideo(): Promise<void> {
  resetAnalysis()
  const data = await extractVideo(videoInput.value)
  if (!data) {
    showSessionPanel.value = shouldAutoOpenSessionPanel(parseError.value)
    return
  }
  showSessionPanel.value = false
}

async function handleExtractBilibiliVideo(): Promise<void> {
  resetBilibiliAnalysis()
  await extractBilibiliVideo(videoInput.value)
}

async function handleExtractVideo(): Promise<void> {
  if (activePlatform.value === 'douyin') {
    await handleExtractDouyinVideo()
    return
  }
  await handleExtractBilibiliVideo()
}

function handleSwitchPlatform(platform: SupportedPlatform): void {
  activePlatform.value = platform
  videoInput.value = ''
  showSessionPanel.value = false
  resetAnalysis()
  resetBilibiliAnalysis()
  resetParse()
  resetBilibiliParse()
}

function handleReset(): void {
  videoInput.value = ''
  showSessionPanel.value = false
  resetAnalysis()
  resetBilibiliAnalysis()
  resetParse()
  resetBilibiliParse()
}
</script>

<style scoped>
.video-analysis {
  display: grid;
  grid-template-columns: 320px minmax(0, 1fr);
  gap: var(--space-lg);
  align-items: start;
}

.input-column,
.preview-column {
  display: grid;
  gap: var(--space-md);
}

.input-column {
  position: sticky;
  top: var(--space-md);
}

.editor-card,
.empty-card {
  display: grid;
  gap: var(--space-md);
}

.card-head {
  display: grid;
  gap: var(--space-sm);
}

.platform-switch {
  display: inline-flex;
  width: fit-content;
  gap: 4px;
  padding: 4px;
  border-radius: var(--radius-md);
  background: var(--surface-page);
  border: 1px solid var(--color-border);
}

.platform-tab {
  min-height: 36px;
  padding: 0 16px;
  border: none;
  border-radius: calc(var(--radius-md) - 4px);
  background: transparent;
  color: var(--color-text-muted);
  cursor: pointer;
  font-size: 0.84rem;
  font-weight: 500;
  transition:
    background var(--duration-fast) var(--ease-out),
    color var(--duration-fast) var(--ease-out),
    border-color var(--duration-fast) var(--ease-out);
}

.platform-tab:hover {
  color: var(--color-text-secondary);
  background: var(--color-surface-hover);
}

.platform-tab-active {
  background: var(--surface-card);
  color: var(--color-text);
  font-weight: 600;
  border: 1px solid var(--color-border);
}

.card-title {
  margin: 0;
  font-size: 1.12rem;
  font-weight: 600;
  line-height: 1.25;
  letter-spacing: -0.02em;
}

.field-label {
  font-size: 0.82rem;
  color: var(--color-text-secondary);
  font-weight: 500;
}

.input-area {
  width: 100%;
  resize: vertical;
  min-height: 148px;
  padding: 14px 16px;
  border-radius: var(--radius-lg);
  border: 1px solid var(--color-border);
  background: var(--surface-muted);
  color: var(--color-text);
  outline: none;
  font-size: 0.92rem;
  line-height: 1.6;
  transition:
    border-color var(--duration-fast) var(--ease-out),
    background var(--duration-fast) var(--ease-out),
    box-shadow var(--duration-fast) var(--ease-out);
}

.input-area:focus {
  border-color: var(--color-border-accent);
  background: var(--surface-card);
  box-shadow: var(--focus-ring);
}

.field-note {
  margin: 0;
  max-width: 54ch;
  color: var(--color-text-muted);
  font-size: 0.79rem;
  line-height: 1.5;
}

.action-row {
  display: flex;
  gap: var(--space-sm);
  flex-wrap: wrap;
}

.btn-primary,
.btn-secondary {
  min-height: 40px;
  padding: 0 18px;
  border-radius: var(--radius-md);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  font-size: 0.88rem;
  font-weight: 600;
  transition:
    transform var(--duration-fast) var(--ease-out),
    background var(--duration-fast) var(--ease-out),
    border-color var(--duration-fast) var(--ease-out),
    opacity var(--duration-fast) var(--ease-out);
}

.btn-primary {
  background: var(--color-accent);
  color: white;
  border: none;
}

.btn-primary:hover {
  transform: translateY(-1px);
  background: var(--color-accent-2);
}

.btn-secondary {
  background: var(--surface-card);
  border: 1px solid var(--color-border);
  color: var(--color-text-secondary);
}

.btn-secondary:hover {
  border-color: var(--color-border-hover);
  color: var(--color-text);
  background: var(--color-surface-hover);
}

.toggle-link {
  background: transparent;
  border: none;
  color: var(--color-text-muted);
  cursor: pointer;
  padding: 0;
  font-size: 0.8rem;
  justify-self: start;
  transition: color var(--duration-fast) var(--ease-out);
}

.toggle-link:hover {
  color: var(--color-text-secondary);
}

.empty-card {
  min-height: 300px;
  align-content: center;
  text-align: center;
}

.empty-icon {
  color: var(--color-text-muted);
  opacity: 0.5;
  justify-self: center;
}

.empty-title {
  margin: 0;
  font-size: 1.14rem;
  font-weight: 600;
  color: var(--color-text);
}

.empty-copy {
  margin: 0;
  color: var(--color-text-muted);
  font-size: 0.88rem;
  line-height: 1.6;
  max-width: 42ch;
  justify-self: center;
}

@media (max-width: 980px) {
  .video-analysis {
    grid-template-columns: 1fr;
  }

  .input-column {
    position: static;
  }
}
</style>
