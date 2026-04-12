<template>
  <div class="app-shell">
    <header class="page-header">
      <div class="brand">
        <p class="brand-kicker">Video Extractor</p>
        <h1 class="brand-title">粘贴抖音或 B 站分享文本，直接预览并下载视频</h1>
      </div>
      <p class="brand-copy">
        抖音支持匿名提取与登录增强；B 站支持单流直连，也支持 DASH 音视频分离资源由后端合成为完整 MP4 后预览和下载。
      </p>
    </header>

    <main class="layout">
      <section class="input-column">
        <article class="editor-card glass-card">
          <header class="card-head">
            <p class="eyebrow">平台</p>
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
            :disabled="isCurrentPlatformLoading"
          />

          <p class="field-note">
            {{ inputNote }}
          </p>

          <div class="action-row">
            <button
              class="btn-primary"
              :disabled="isCurrentPlatformLoading || !videoInput.trim()"
              @click="handleExtractVideo"
            >
              {{ isCurrentPlatformLoading ? '提取中…' : '提取视频' }}
            </button>
            <button class="btn-secondary" :disabled="isCurrentPlatformLoading" @click="handleReset">
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
          <p class="empty-kicker">准备就绪</p>
          <h2 class="empty-title">{{ emptyTitle }}</h2>
          <p class="empty-copy">
            {{ emptyCopy }}
          </p>
        </section>
      </section>
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import BilibiliParsePanel from './components/BilibiliParsePanel.vue'
import DouyinParsePanel from './components/DouyinParsePanel.vue'
import DouyinSessionPanel from './components/DouyinSessionPanel.vue'
import { useBilibiliParse } from './composables/useBilibiliParse'
import { useBilibiliVideoAnalysis } from './composables/useBilibiliVideoAnalysis'
import { useDouyinParse } from './composables/useDouyinParse'
import { useDouyinSession } from './composables/useDouyinSession'
import { useDouyinVideoAnalysis } from './composables/useDouyinVideoAnalysis'

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

const isCurrentPlatformLoading = computed(() => {
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

onMounted(() => {
  void refreshDouyinSession()
})

async function handleRetryDouyinAnalysis(): Promise<void> {
  const proxyVideoUrl = extractedVideo.value?.proxyVideoUrl
  if (!proxyVideoUrl) {
    return
  }

  await analyzeVideo(proxyVideoUrl)
}

async function handleRetryBilibiliAnalysis(): Promise<void> {
  const proxyVideoUrl = bilibiliExtractedVideo.value?.proxyVideoUrl
  if (!proxyVideoUrl) {
    return
  }

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
.app-shell {
  width: min(1280px, calc(100% - 32px));
  margin: 0 auto;
  padding: var(--space-page) 0 48px;
}

.page-header {
  display: grid;
  gap: 18px;
  margin-bottom: 28px;
}

.brand {
  display: grid;
  gap: 12px;
}

.brand-kicker,
.eyebrow,
.empty-kicker {
  margin: 0;
  font-size: 0.78rem;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--color-text-muted);
}

.brand-title,
.card-title,
.empty-title {
  margin: 0;
  line-height: 1.05;
}

.brand-title {
  max-width: 13ch;
  font-size: clamp(2.5rem, 1.5rem + 4vw, 5.2rem);
}

.brand-copy,
.field-note,
.empty-copy {
  margin: 0;
  max-width: 60ch;
  color: var(--color-text-secondary);
  font-size: 1rem;
}

.layout {
  display: grid;
  grid-template-columns: 360px minmax(0, 1fr);
  gap: 22px;
  align-items: start;
}

.input-column,
.preview-column {
  display: grid;
  gap: 18px;
}

.input-column {
  position: sticky;
  top: 20px;
}

.editor-card,
.empty-card {
  border-radius: var(--radius-lg);
  padding: 22px;
}

.editor-card {
  display: grid;
  gap: 16px;
}

.card-head {
  display: grid;
  gap: 12px;
}

.platform-switch {
  display: inline-flex;
  width: fit-content;
  gap: 8px;
  padding: 6px;
  border-radius: 999px;
  border: 1px solid var(--color-border);
  background: rgba(255,255,255,0.04);
}

.platform-tab {
  min-height: 38px;
  padding: 0 16px;
  border: none;
  border-radius: 999px;
  background: transparent;
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: transform 150ms ease, background 150ms ease, color 150ms ease;
}

.platform-tab:hover {
  transform: translateY(-1px);
}

.platform-tab-active {
  background: rgba(255,255,255,0.1);
  color: var(--color-text);
  font-weight: 700;
}

.card-title,
.empty-title {
  font-size: 1.32rem;
}

.field-label {
  font-size: 0.92rem;
  color: var(--color-text);
  font-weight: 600;
}

.input-area {
  width: 100%;
  resize: vertical;
  min-height: 160px;
  padding: 14px 16px;
  border-radius: 18px;
  border: 1px solid var(--color-border);
  background: rgba(255,255,255,0.05);
  color: var(--color-text);
  outline: none;
  transition: border-color 160ms ease, background 160ms ease;
}

.input-area:focus {
  border-color: rgba(139,92,246,0.6);
  background: rgba(255,255,255,0.08);
}

.action-row {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.btn-primary,
.btn-secondary,
.toggle-link {
  border: none;
  cursor: pointer;
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
  transition: transform 150ms ease, opacity 150ms ease;
}

.btn-primary {
  background: var(--gradient-accent);
  color: white;
  font-weight: 700;
}

.btn-secondary {
  background: rgba(255,255,255,0.05);
  border: 1px solid var(--color-border);
  color: var(--color-text);
}

.btn-primary:hover,
.btn-secondary:hover,
.toggle-link:hover {
  transform: translateY(-1px);
}

.toggle-link {
  background: transparent;
  color: var(--color-text-secondary);
  padding: 0;
  justify-self: start;
}

.empty-card {
  min-height: 320px;
  display: grid;
  align-content: center;
  gap: 12px;
}

@media (max-width: 980px) {
  .layout {
    grid-template-columns: 1fr;
  }

  .input-column {
    position: static;
  }
}
</style>

