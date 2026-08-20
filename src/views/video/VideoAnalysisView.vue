<template>
  <div class="video-analysis-page">
    <header class="view-header">
      <div class="view-header-copy">
        <p class="view-kicker">视频制作 · 可选输入手段</p>
        <h1 class="view-title">视频参考提取</h1>
        <p class="view-copy">提取与分析抖音 / B 站参考视频，作为视频制作过程中的可选参考输入；分析结果只产生创作建议，可在视频制作中带入脚本、分镜、角色、道具和场景生成。</p>
      </div>
      <button class="btn-secondary view-header-action" type="button" @click="emit('open-view', 'video-production')">
        去视频制作
      </button>
    </header>

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
          @click="showHotPanel = !showHotPanel"
        >
          {{ showHotPanel ? '收起抖音热点选题' : '展开抖音热点选题' }}
        </button>

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

      <DouyinHotItemsPanel
        v-if="activePlatform === 'douyin' && showHotPanel"
        @use-link="handleUseHotLink"
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
        :task-context="taskExecutionContext"
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
        :task-context="taskExecutionContext"
        @retry="handleExtractBilibiliVideo"
        @retry-analysis="handleRetryBilibiliAnalysis"
      />

      <section v-if="currentProxyVideoUrl" class="recreation-card glass-card">
        <div class="recreation-head">
          <div>
            <p class="recreation-kicker">视频复刻 · 仅参考结构</p>
            <h2>复刻分镜与参考图</h2>
            <p>把参考视频拆成独立分镜场景，逐场景生成 AI 参考图；结果只作创作参考，不复刻原文。</p>
          </div>
          <button
            class="btn-primary"
            type="button"
            :disabled="recreationLoading"
            @click="handleAnalyzeRecreation"
          >
            {{ recreationLoading ? '分镜分析中…' : recreationResult ? '重新生成分镜' : '生成复刻分镜' }}
          </button>
        </div>
        <p v-if="recreationError" class="recreation-error">{{ recreationError }}</p>
        <VideoRecreationPanel
          v-if="recreationResult"
          :scenes="recreationResult.scenes"
          :overall-style="recreationResult.overallStyle"
          :task-context="taskExecutionContext"
        />
      </section>

      <section v-if="referenceTarget && currentAnalysis" class="reference-handoff glass-card">
        <div>
          <p class="reference-handoff-kicker">发布目标</p>
          <h2>{{ referenceTarget.label }}</h2>
          <p>分析摘要会作为可编辑的创作要求带入下一步。</p>
        </div>
        <button class="btn-primary" type="button" @click="startFromReference">
          带入创作
        </button>
      </section>

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
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import BilibiliParsePanel from '../../components/BilibiliParsePanel.vue'
import DouyinHotItemsPanel from '../../components/DouyinHotItemsPanel.vue'
import DouyinParsePanel from '../../components/DouyinParsePanel.vue'
import DouyinSessionPanel from '../../components/DouyinSessionPanel.vue'
import VideoRecreationPanel from '../../components/VideoRecreationPanel.vue'
import { useBilibiliParse } from '../../composables/useBilibiliParse'
import { useBilibiliVideoAnalysis } from '../../composables/useBilibiliVideoAnalysis'
import { useDouyinParse } from '../../composables/useDouyinParse'
import { useDouyinSession } from '../../composables/useDouyinSession'
import { useDouyinVideoAnalysis } from '../../composables/useDouyinVideoAnalysis'
import { useVideoRecreationScenes } from '../../composables/useVideoRecreationScenes'
import type { CreationHandoff } from '../../types/ai-creation'
import type { VideoTaskExecutionContext } from '../../types/video-recreation'

const props = defineProps<{
  creationHandoff?: CreationHandoff | null
}>()

const emit = defineEmits<{
  'open-view': [view: 'video-production']
  'start-workflow': [handoff: CreationHandoff]
}>()

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
const showHotPanel = ref(false)
const hydratedCreationRevision = ref<number | null>(null)
const taskExecutionContext = ref<VideoTaskExecutionContext | undefined>()

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

const {
  result: recreationResult,
  loading: recreationLoading,
  error: recreationError,
  analyzeScenes,
  reset: resetRecreation,
} = useVideoRecreationScenes()

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
const currentAnalysis = computed(() => activePlatform.value === 'douyin'
  ? videoAnalysis.value
  : bilibiliVideoAnalysis.value)
const currentExtractedTitle = computed(() => activePlatform.value === 'douyin'
  ? extractedVideo.value?.title
  : bilibiliExtractedVideo.value?.title)
const currentProxyVideoUrl = computed(() => activePlatform.value === 'douyin'
  ? extractedVideo.value?.proxyVideoUrl
  : bilibiliExtractedVideo.value?.proxyVideoUrl)
const referenceTarget = computed(() => {
  const handoff = props.creationHandoff
  if (!handoff || handoff.workflowId !== 'reference-analyze') return null
  const platform = {
    xiaohongshu: '小红书', douyin: '抖音', dianping: '大众点评', kuaishou: '快手',
    'wechat-channels': '视频号', bilibili: 'Bilibili', 'wechat-official': '公众号',
    zhihu: '知乎', moments: '朋友圈',
  }[handoff.platformId]
  return { label: `${platform} · ${handoff.contentFormId === 'video' ? '视频' : '图文'}` }
})

let handoffRevision = Date.now()

function startFromReference(): void {
  const previous = props.creationHandoff
  const analysis = currentAnalysis.value
  if (!previous || previous.workflowId !== 'reference-analyze' || !analysis) return
  const sections = [
    ['脚本与字幕', analysis.videoScript ?? analysis.videoCaptions],
    ['人物', analysis.charactersDescription],
    ['场景', analysis.sceneDescription],
    ['道具', analysis.propsDescription],
    ['声音', analysis.voiceDescription],
  ].filter((item): item is [string, string] => Boolean(item[1]))
  const topic = currentExtractedTitle.value?.trim() || '参考视频内容改编'
  const instructions = [
    '参考视频分析摘要：',
    ...sections.map(([label, content]) => `${label}：${content}`),
  ].join('\n')
  const isVideo = previous.contentFormId === 'video'
  const isReview = previous.platformId === 'dianping' && previous.contentFormId === 'graphic'
  handoffRevision = Math.max(handoffRevision + 1, Date.now())
  emit('start-workflow', {
    ...previous,
    revision: handoffRevision,
    workflowId: isVideo ? 'video-script' : isReview ? 'review-copy' : 'longform',
    targetView: isVideo ? 'video-production' : isReview ? 'image' : 'article',
    prefill: {
      ...previous.prefill,
      topic,
      instructions,
      referencePlatform: activePlatform.value,
    },
  })
}

void refreshDouyinSession()

async function handleRetryDouyinAnalysis(): Promise<void> {
  const proxyVideoUrl = extractedVideo.value?.proxyVideoUrl
  if (!proxyVideoUrl) return
  await analyzeVideo(proxyVideoUrl, taskExecutionContext.value)
}

async function handleRetryBilibiliAnalysis(): Promise<void> {
  const proxyVideoUrl = bilibiliExtractedVideo.value?.proxyVideoUrl
  if (!proxyVideoUrl) return
  await analyzeBilibiliVideo(proxyVideoUrl, taskExecutionContext.value)
}

async function handleExtractDouyinVideo(): Promise<void> {
  resetAnalysis()
  resetRecreation()
  const data = await extractVideo(videoInput.value)
  if (!data) {
    showSessionPanel.value = shouldAutoOpenSessionPanel(parseError.value)
    return
  }
  showSessionPanel.value = false
}

async function handleExtractBilibiliVideo(): Promise<void> {
  resetBilibiliAnalysis()
  resetRecreation()
  await extractBilibiliVideo(videoInput.value)
}

async function handleAnalyzeRecreation(): Promise<void> {
  if (!currentProxyVideoUrl.value || recreationLoading.value) return
  await analyzeScenes(activePlatform.value, currentProxyVideoUrl.value, taskExecutionContext.value)
}

function handleUseHotLink(url: string): void {
  if (!url) return
  videoInput.value = url
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
  showHotPanel.value = false
  resetAnalysis()
  resetBilibiliAnalysis()
  resetParse()
  resetBilibiliParse()
  resetRecreation()
}

function handleReset(): void {
  videoInput.value = ''
  showSessionPanel.value = false
  showHotPanel.value = false
  resetAnalysis()
  resetBilibiliAnalysis()
  resetParse()
  resetBilibiliParse()
  resetRecreation()
}

watch(() => props.creationHandoff, (handoff) => {
  if (!handoff || handoff.targetView !== 'video' || hydratedCreationRevision.value === handoff.revision) return
  hydratedCreationRevision.value = handoff.revision
  const nextPlatform: SupportedPlatform = handoff.prefill?.referencePlatform
    ?? (handoff.platformId === 'bilibili' ? 'bilibili' : 'douyin')
  handleSwitchPlatform(nextPlatform)
  taskExecutionContext.value = handoff.workflowId === 'video-recreation'
    && handoff.source.type === 'task'
    && handoff.contextSnapshotId
    ? {
        taskMode: true,
        contextSnapshotId: handoff.contextSnapshotId,
        targetPlatform: handoff.platformId,
      }
    : undefined
  videoInput.value = handoff.workflowId === 'video-recreation'
    ? handoff.prefill?.referenceUrl || ''
    : handoff.source.type === 'reference' ? handoff.source.sourceUrl || '' : ''
}, { immediate: true })
</script>

<style scoped>
.video-analysis-page {
  display: grid;
  gap: var(--space-lg);
}

.view-header {
  display: flex;
  align-items: start;
  justify-content: space-between;
  gap: var(--space-md);
  flex-wrap: wrap;
}

.view-header-copy {
  display: grid;
  gap: 6px;
}

.reference-handoff {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 18px;
}

.recreation-card {
  display: grid;
  gap: var(--space-md);
  padding: 18px;
}

.recreation-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}

.recreation-head > div {
  display: grid;
  gap: 4px;
}

.recreation-kicker,
.recreation-head h2,
.recreation-head p {
  margin: 0;
}

.recreation-kicker {
  color: var(--color-accent);
  font-size: 0.78rem;
  font-weight: 700;
}

.recreation-head h2 {
  font-size: 1.05rem;
}

.recreation-head p:not(.recreation-kicker) {
  color: var(--color-text-secondary);
  font-size: 0.84rem;
  line-height: 1.6;
  max-width: 56ch;
}

.recreation-error {
  margin: 0;
  color: var(--color-danger, #b42318);
  font-size: 0.84rem;
}

@media (max-width: 640px) {
  .recreation-head {
    align-items: stretch;
    flex-direction: column;
  }
}

.reference-handoff-kicker,
.reference-handoff h2,
.reference-handoff p {
  margin: 0;
}

.reference-handoff-kicker {
  color: var(--color-accent);
  font-size: 0.78rem;
  font-weight: 700;
}

.reference-handoff h2 {
  margin-top: 3px;
  font-size: 1rem;
}

.reference-handoff p:last-child {
  margin-top: 4px;
  color: var(--color-text-secondary);
  font-size: 0.82rem;
}

@media (max-width: 640px) {
  .reference-handoff {
    align-items: stretch;
    flex-direction: column;
  }
}

.view-kicker {
  margin: 0;
  font-size: 0.78rem;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: var(--color-text-muted);
  font-weight: 600;
}

.view-title {
  margin: 0;
  font-size: 1.42rem;
  font-weight: 700;
  line-height: 1.2;
  color: var(--color-text);
}

.view-copy {
  margin: 0;
  max-width: 64ch;
  color: var(--color-text-secondary);
  font-size: 0.88rem;
  line-height: 1.6;
}

.view-header-action {
  min-height: 40px;
  padding: 0 18px;
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border);
  background: var(--surface-card);
  color: var(--color-text-secondary);
  font-size: 0.88rem;
  font-weight: 600;
  cursor: pointer;
  transition:
    border-color var(--duration-fast) var(--ease-out),
    background var(--duration-fast) var(--ease-out),
    color var(--duration-fast) var(--ease-out);
}

.view-header-action:hover {
  border-color: var(--color-border-hover);
  color: var(--color-text);
  background: var(--color-surface-hover);
}

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
