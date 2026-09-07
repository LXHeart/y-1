<template>
  <div class="video-studio">
    <!-- 子区切换 + 工作区状态（任务书 #92 C-05） -->
    <div class="vs-tabs">
      <button v-for="tab in vsTabs" :key="tab.id" type="button"
        :class="['vs-tab', { active: activeTab === tab.id }]"
        @click="activeTab = tab.id">{{ tab.label }}</button>
      <span class="vs-tabs-side">
        <span v-if="resultAssetIds.length" class="result-assets-chip" data-testid="result-assets-chip"
          title="结果素材在「素材库」面板查看；过期或无权资源由素材库呈现不可用态">
          已存 {{ resultAssetIds.length }} 项结果素材
        </span>
        <button v-if="taskReturnId" type="button" class="secondary-command" data-testid="back-to-task"
          :disabled="jumpingBack" @click="backToTask">{{ jumpingBack ? '正在跳转…' : '回到任务' }}</button>
        <WorkspaceSaveBadge :state="autosave.saveState.value" :conflict="autosave.conflictNotice.value" @retry="autosave.retry" />
      </span>
    </div>

    <!-- 1. 剪辑模板 -->
    <div v-if="activeTab === 'templates'" class="vs-section">
      <div class="vs-filter-row">
        <select v-model="tplPlatform">
          <option value="">全部平台</option>
          <option v-for="p in platformOptions" :key="p" :value="p">{{ p }}</option>
        </select>
        <select v-model="tplForm">
          <option value="">全部形式</option>
          <option v-for="f in formOptions" :key="f" :value="f">{{ f }}</option>
        </select>
      </div>
      <div class="vs-template-grid">
        <div v-for="tpl in filteredTemplates" :key="tpl.id" class="vs-template-card">
          <h4>{{ tpl.name }}</h4>
          <p class="tpl-meta">{{ tpl.platforms.join('/') }} · {{ tpl.pace }} · {{ tpl.durationHint }}</p>
          <ol class="tpl-structure">
            <li v-for="beat in tpl.structure" :key="beat.beat">
              <strong>{{ beat.beat }}</strong> {{ (beat.timeShare * 100).toFixed(0) }}% — {{ beat.hint }}
            </li>
          </ol>
          <button type="button" class="secondary-command" @click="handoffTemplate(tpl)">带入创作</button>
        </div>
      </div>
    </div>

    <!-- 2. 字幕工作台 -->
    <div v-if="activeTab === 'subtitles'" class="vs-section">
      <div v-if="!subtitleSource" class="vs-sub-source">
        <p>选择字幕来源：</p>
        <button type="button" class="secondary-command" @click="subtitleSource = 'new'">新建转写（上传音频）</button>
        <button type="button" class="secondary-command" @click="loadTranscriptionList">从历史记录选择</button>
      </div>

      <div v-if="subtitleSource === 'new'" class="vs-sub-upload">
        <label class="studio-upload-btn">
          <input type="file" accept="audio/*" @change="onAudioSelected">
          <span>选择音频文件</span>
        </label>
        <p v-if="transcribing" class="vs-status">转写中…</p>
        <p v-if="transcriptionResult" class="vs-ok">转写完成（{{ transcriptionResult.status }}）</p>
      </div>

      <div v-if="subtitleSource === 'history'" class="vs-sub-history">
        <div v-if="transcriptionList.length === 0" class="vs-status">加载中…</div>
        <div v-for="item in transcriptionList" :key="item.id" class="vs-history-item"
          @click="selectTranscription(item)">
          <span>{{ item.id.slice(0, 8) }}…</span>
          <span>{{ item.status }}</span>
          <span>{{ new Date(item.createdAt).toLocaleString('zh-CN', { hour12: false }) }}</span>
        </div>
      </div>

      <div v-if="cues.length > 0" class="vs-sub-workspace">
        <p class="vs-hint">建议时间轴，请播放校准</p>
        <audio v-if="audioUrl" ref="audioRef" :src="audioUrl" controls @timeupdate="onAudioTimeUpdate"></audio>
        <div class="vs-cue-table">
          <div class="vs-cue-header">
            <span>#</span><span>入点(s)</span><span>出点(s)</span><span>文本</span><span>操作</span>
          </div>
          <div v-for="(cue, idx) in cues" :key="cue.id"
            :class="['vs-cue-row', { active: currentCueId === cue.id }]">
            <span>{{ idx + 1 }}</span>
            <input type="number" :value="cue.start" step="0.1" min="0"
              @change="updateCueTime(cue.id, 'start', $event)">
            <input type="number" :value="cue.end" step="0.1" min="0"
              @change="updateCueTime(cue.id, 'end', $event)">
            <input type="text" :value="cue.text" @change="updateCueText(cue.id, $event)">
            <div class="vs-cue-actions">
              <button type="button" @click="setCueIn(cue.id)" title="入点=当前播放位置">⏎</button>
              <button type="button" @click="setCueOut(cue.id)" title="出点=当前播放位置">⏎</button>
            </div>
          </div>
        </div>
        <div class="vs-export-row">
          <button type="button" class="secondary-command" @click="copySrt">复制 SRT</button>
          <button type="button" class="secondary-command" @click="downloadSrt">下载 .srt</button>
          <button type="button" class="secondary-command" @click="copyVtt">复制 VTT</button>
          <button type="button" class="secondary-command" @click="downloadVtt">下载 .vtt</button>
        </div>
      </div>
    </div>

    <!-- 3. BGM 建议 -->
    <div v-if="activeTab === 'bgm'" class="vs-section">
      <div class="vs-bgm-form">
        <label>平台
          <select v-model="bgmForm.platform">
            <option v-for="p in bgmPlatformOptions" :key="p.value" :value="p.value">{{ p.label }}</option>
          </select>
        </label>
        <label>内容形式
          <select v-model="bgmForm.contentForm">
            <option v-for="f in bgmFormOptions" :key="f" :value="f">{{ f }}</option>
          </select>
        </label>
        <label>时长(秒)
          <input type="number" v-model.number="bgmForm.durationSeconds" min="5" max="600">
        </label>
        <label>主题
          <input type="text" v-model="bgmForm.topic" placeholder="如：秋日穿搭分享">
        </label>
        <label>情绪倾向（可选）
          <input type="text" v-model="bgmForm.moodHint" placeholder="如：温暖治愈">
        </label>
        <button type="button" class="primary-command" :disabled="bgmLoading" @click="fetchBgmAdvice">
          {{ bgmLoading ? '生成中…' : '获取 BGM 建议' }}
        </button>
      </div>
      <p v-if="bgmError" class="vs-error">{{ bgmError }}</p>
      <div v-if="bgmResult" class="vs-bgm-result">
        <div class="bgm-card">
          <h4>情绪方向</h4>
          <p><strong>{{ bgmResult.moodDirection.label }}</strong></p>
          <p>{{ bgmResult.moodDirection.reason }}</p>
          <p class="vs-status">参考风格：{{ bgmResult.moodDirection.referenceStyle }}</p>
        </div>
        <div class="bgm-card">
          <h4>节奏时间轴</h4>
          <ul>
            <li v-for="(r, i) in bgmResult.rhythm" :key="i">
              {{ r.timeRange }} · 强度 {{ r.intensity }}/5 — {{ r.suggestion }}
            </li>
          </ul>
        </div>
        <div class="bgm-card">
          <h4>卡点列表</h4>
          <ul><li v-for="(s, i) in bgmResult.syncPoints" :key="i">{{ s.atSeconds }}s — {{ s.suggestion }}</li></ul>
        </div>
        <div v-if="bgmResult.cautions.length" class="bgm-card">
          <h4>风险提示</h4>
          <ul><li v-for="(c, i) in bgmResult.cautions" :key="i">{{ c }}</li></ul>
        </div>
      </div>
    </div>

    <!-- 4. 封面工作台 -->
    <div v-if="activeTab === 'cover'" class="vs-section">
      <div class="vs-cover-source">
        <p>选择底图来源：</p>
        <div class="vs-cover-btns">
          <button type="button" :class="{ active: coverSource === 'video' }" @click="coverSource = 'video'">视频抽帧</button>
          <button type="button" :class="{ active: coverSource === 'image' }" @click="coverSource = 'image'">本地图片</button>
          <button type="button" :class="{ active: coverSource === 'ai' }" @click="coverSource = 'ai'">AI 生图</button>
        </div>
      </div>

      <div v-if="coverSource === 'video'" class="vs-cover-video">
        <label class="studio-upload-btn">
          <input type="file" accept="video/*" @change="onVideoSelected">
          <span>选择视频文件</span>
        </label>
        <div v-if="videoFile" class="vs-frame-picker">
          <video ref="videoRef" :src="videoUrl" muted @loadeddata="extractFrames"></video>
          <div class="vs-frame-strip">
            <div v-for="(frame, i) in videoFrames" :key="i"
              :class="['vs-frame-thumb', { active: selectedFrameIdx === i }]"
              @click="selectedFrameIdx = i">
              <img :src="frame" :alt="`帧 ${i + 1}`">
            </div>
          </div>
        </div>
      </div>

      <div v-if="coverSource === 'image'" class="vs-cover-image">
        <label class="studio-upload-btn">
          <input type="file" accept="image/*" @change="onCoverImageSelected">
          <span>选择图片</span>
        </label>
      </div>

      <div v-if="coverSource === 'ai'" class="vs-cover-ai">
        <label>主题 <input type="text" v-model="aiCoverPrompt" placeholder="如：秋日暖阳下的咖啡店"></label>
        <button type="button" class="secondary-command" :disabled="aiCoverBusy" @click="generateAiCover">
          {{ aiCoverBusy ? '生成中…' : '生成封面图' }}
        </button>
        <p v-if="aiCoverError" class="vs-error">{{ aiCoverError }}</p>
      </div>

      <!-- 文字叠加 & 导出 -->
      <div v-if="coverBaseImage" class="vs-cover-edit">
        <div class="vs-cover-canvas-wrap">
          <canvas ref="coverCanvasRef" class="vs-cover-canvas"></canvas>
        </div>
        <div class="vs-cover-controls">
          <label>比例
            <select v-model="coverRatio">
              <option value="9:16">9:16</option>
              <option value="3:4">3:4</option>
              <option value="4:3">4:3</option>
              <option value="2.35:1">2.35:1</option>
              <option value="free">原始</option>
            </select>
          </label>
          <label>标题 <input type="text" v-model="coverTitle" maxlength="20"></label>
          <label>副标题 <input type="text" v-model="coverSubtitle" maxlength="20"></label>
          <label>排版
            <select v-model="coverLayout" data-test="cover-layout">
              <option v-for="layout in COVER_TEXT_LAYOUTS" :key="layout.id" :value="layout.id">
                {{ layout.label }}
              </option>
            </select>
          </label>
          <p v-if="coverTitle.length > 20" class="vs-error">标题不超过 20 字</p>
          <button type="button" class="primary-command" @click="renderCover">预览</button>
          <button type="button" class="secondary-command" @click="downloadCover">下载</button>
          <button type="button" class="secondary-command" @click="saveCoverToLibrary">存入素材库</button>
          <p v-if="coverMsg" class="vs-ok">{{ coverMsg }}</p>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, watch, onBeforeUnmount } from 'vue'
import { useAiStudio } from '../../../composables/useAiStudio'
import { useGrassland } from '../../../composables/useGrassland'
import { generateImage } from '../../../composables/useImageGeneration'
import { VIDEO_EDIT_TEMPLATES } from '../../../constants/video-edit-templates'
import { autoSplitSubtitles, buildSrt, buildVtt } from '../../../utils/subtitle-timeline'
import { COVER_TEXT_LAYOUTS } from './cover-text-layout'
import WorkspaceSaveBadge from '../creation/WorkspaceSaveBadge.vue'
import { useWorkspaceAutosave } from '../creation/useWorkspaceAutosave'
import { useCrossAppJump } from '../../../composables/useCrossAppToken'
import type { VideoEditTemplate, SubtitleCue, SpeechTranscriptionItem, BgmAdviceInput, BgmAdviceResult } from '../../../types/grassland/ai-studio'
import type { AiPlatformId, AiContentFormId } from '../../../types/ai-creation'
import type { CoverTextLayoutId } from './cover-text-layout'

const emit = defineEmits<{ 'handoff': [payload: { platformId: AiPlatformId; contentFormId: AiContentFormId; topic: string }] }>()

const studio = useAiStudio()
const grassland = useGrassland()


type VsTab = 'templates' | 'subtitles' | 'bgm' | 'cover'
const vsTabs = [
  { id: 'templates' as VsTab, label: '剪辑模板' },
  { id: 'subtitles' as VsTab, label: '字幕工作台' },
  { id: 'bgm' as VsTab, label: 'BGM 建议' },
  { id: 'cover' as VsTab, label: '封面工作台' },
]
const activeTab = ref<VsTab>('templates')

const platformOptions = ['douyin', 'xiaohongshu', 'bilibili', 'wechat', 'dianping']
const formOptions = ['talking-head', 'story', 'review', 'vlog', 'tutorial', 'unboxing', 'image-carousel', 'article-cover']

// BGM 建议后端白名单（BgmAdvicePrompts）：platform ⊆ 模板平台（bilibili 不在后端白名单），
// contentForm 直接收中文枚举，与上面模板筛选的英文 id 是两套口径。
const bgmPlatformOptions = [
  { value: 'douyin', label: '抖音' },
  { value: 'xiaohongshu', label: '小红书' },
  { value: 'wechat', label: '微信视频号' },
  { value: 'dianping', label: '大众点评' },
]
const bgmFormOptions = ['口播', '剧情', '种草', 'vlog', '教程', '测评', '探店', '美食', '开箱', '图文轮播']

// ---------- 1. 模板 ----------
const tplPlatform = ref('')
const tplForm = ref('')
const filteredTemplates = computed(() =>
  VIDEO_EDIT_TEMPLATES.filter(t =>
    (!tplPlatform.value || t.platforms.includes(tplPlatform.value)) &&
    (!tplForm.value || t.forms.includes(tplForm.value))
  )
)

function handoffTemplate(tpl: VideoEditTemplate) {
  const summary = tpl.structure.map(b => `${b.beat}(${(b.timeShare * 100).toFixed(0)}%)`).join(' → ')
  const platformMap: Record<string, AiPlatformId> = {
    douyin: 'douyin', xiaohongshu: 'xiaohongshu', dianping: 'dianping',
    bilibili: 'douyin', wechat: 'wechat-channels',
  }
  const formMap: Record<string, AiContentFormId> = {
    'talking-head': 'video-text', story: 'video', review: 'video',
    vlog: 'video', tutorial: 'video-text', unboxing: 'video',
    'image-carousel': 'graphic', 'article-cover': 'image-text',
  }
  const pid: AiPlatformId = platformMap[tpl.platforms[0]] || 'douyin'
  const fid: AiContentFormId = formMap[tpl.forms[0]] || 'video'
  emit('handoff', {
    platformId: pid,
    contentFormId: fid,
    topic: `${tpl.name}：${summary}`,
  })
}

// ---------- 2. 字幕工作台 ----------
const subtitleSource = ref<'' | 'new' | 'history'>('')
const transcribing = ref(false)
const transcriptionResult = ref<SpeechTranscriptionItem | null>(null)
const transcriptionList = ref<SpeechTranscriptionItem[]>([])
const audioUrl = ref('')
const audioRef = ref<HTMLAudioElement | null>(null)
const currentCueId = ref('')
const cues = ref<SubtitleCue[]>([])
let transcriptionIdForStorage = ''

async function onAudioSelected(e: Event) {
  const file = (e.target as HTMLInputElement).files?.[0]
  if (!file) return
  audioUrl.value = URL.createObjectURL(file)
  transcribing.value = true
  // 复用草场上传 + 转写
  const mediaId = await grassland.uploadSpeechAudio(file)
  if (!mediaId) { transcribing.value = false; return }
  const result = await grassland.createSpeechTranscription(mediaId, 'auto')
  transcribing.value = false
  if (result) {
    transcriptionResult.value = result as unknown as SpeechTranscriptionItem
    transcriptionIdForStorage = result.id
    applyTranscriptionCues(result as unknown as SpeechTranscriptionItem)
  }
}

/** 有句级时间戳（provider segments）直接成轴；缺失时回落字数占比启发式（可手动校准）。 */
function applyTranscriptionCues(item: SpeechTranscriptionItem): void {
  if (item.segments?.length) {
    cues.value = item.segments.map((segment, index) => ({
      id: `cue-${index + 1}`,
      start: Math.round(segment.start * 10) / 10,
      end: Math.round(segment.end * 10) / 10,
      text: segment.text,
    }))
    persistCues()
    return
  }
  if (item.transcriptText) {
    cues.value = autoSplitSubtitles(item.transcriptText, item.durationMs || 30000)
    restoreCues()
  }
}

async function loadTranscriptionList() {
  subtitleSource.value = 'history'
  transcriptionList.value = await studio.listSpeechTranscriptions()
}

function selectTranscription(item: SpeechTranscriptionItem) {
  transcriptionIdForStorage = item.id
  if ((item.segments?.length ?? 0) > 0 || (item.transcriptText && item.durationMs > 0)) {
    applyTranscriptionCues(item)
  }
}

function onAudioTimeUpdate() {
  if (!audioRef.value) return
  const t = audioRef.value.currentTime
  const active = cues.value.find(c => t >= c.start && t < c.end)
  currentCueId.value = active?.id || ''
}

function updateCueTime(id: string, field: 'start' | 'end', e: Event) {
  const val = Number((e.target as HTMLInputElement).value)
  const cue = cues.value.find(c => c.id === id)
  if (cue) { cue[field] = Math.max(0, Math.round(val * 10) / 10); persistCues() }
}

function updateCueText(id: string, e: Event) {
  const cue = cues.value.find(c => c.id === id)
  if (cue) { cue.text = (e.target as HTMLInputElement).value; persistCues() }
}

function setCueIn(id: string) {
  if (!audioRef.value) return
  const cue = cues.value.find(c => c.id === id)
  if (cue) { cue.start = Math.round(audioRef.value.currentTime * 10) / 10; persistCues() }
}

function setCueOut(id: string) {
  if (!audioRef.value) return
  const cue = cues.value.find(c => c.id === id)
  if (cue) { cue.end = Math.round(audioRef.value.currentTime * 10) / 10; persistCues() }
}

// localStorage 暂存
function storageKey() { return `subtitle-cues-${transcriptionIdForStorage}` }
function persistCues() {
  if (!transcriptionIdForStorage) return
  try { localStorage.setItem(storageKey(), JSON.stringify(cues.value)) } catch { /* quota */ }
}
function restoreCues() {
  if (!transcriptionIdForStorage) return
  try {
    const raw = localStorage.getItem(storageKey())
    if (raw) cues.value = JSON.parse(raw)
  } catch { /* ignore */ }
}

// 导出 SRT / VTT（格式化在 utils/subtitle-timeline）
function copySrt() { navigator.clipboard.writeText(buildSrt(cues.value)) }
function copyVtt() { navigator.clipboard.writeText(buildVtt(cues.value)) }
function downloadSrt() { downloadText(buildSrt(cues.value), 'subtitles.srt') }
function downloadVtt() { downloadText(buildVtt(cues.value), 'subtitles.vtt') }
function downloadText(text: string, name: string) {
  const blob = new Blob([text], { type: 'text/plain;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a'); a.href = url; a.download = name; a.click()
  URL.revokeObjectURL(url)
}

// ---------- 3. BGM 建议 ----------
const bgmForm = reactive<BgmAdviceInput>({
  platform: 'douyin', contentForm: '口播', durationSeconds: 60, topic: '', moodHint: '',
})
const bgmLoading = ref(false)
const bgmResult = ref<BgmAdviceResult | null>(null)
const bgmError = ref('')

async function fetchBgmAdvice() {
  bgmLoading.value = true; bgmError.value = ''; bgmResult.value = null
  const result = await studio.bgmAdvice(bgmForm)
  bgmLoading.value = false
  if (result) { bgmResult.value = result }
  else { bgmError.value = studio.error.value }
}

// ---------- 4. 封面工作台 ----------
const coverSource = ref<'video' | 'image' | 'ai'>('video')
const videoFile = ref<File | null>(null)
const videoUrl = ref('')
const videoRef = ref<HTMLVideoElement | null>(null)
const videoFrames = ref<string[]>([])
const selectedFrameIdx = ref(0)
const coverImageFile = ref<File | null>(null)
const coverBaseImage = ref<HTMLImageElement | null>(null)
const coverCanvasRef = ref<HTMLCanvasElement | null>(null)
const coverRatio = ref('9:16')
const coverTitle = ref('')
const coverSubtitle = ref('')
const coverLayout = ref<CoverTextLayoutId>('left-bold')
const aiCoverPrompt = ref('')
const aiCoverBusy = ref(false)
const aiCoverError = ref('')
const coverMsg = ref('')

function onVideoSelected(e: Event) {
  const file = (e.target as HTMLInputElement).files?.[0]
  if (!file) return
  videoFile.value = file
  videoUrl.value = URL.createObjectURL(file)
}

function extractFrames() {
  if (!videoRef.value) return
  const video = videoRef.value
  const duration = video.duration || 6
  const interval = duration / 6
  const frames: string[] = []
  const canvas = document.createElement('canvas')
  canvas.width = 160; canvas.height = 90
  const ctx = canvas.getContext('2d')!
  // seek 是异步的：必须等 seeked 事件再 drawImage，否则抽出的帧重复/黑帧
  const captureCurrent = () => {
    ctx.drawImage(video, 0, 0, 160, 90)
    frames.push(canvas.toDataURL('image/jpeg', 0.6))
    if (frames.length >= 6) {
      videoFrames.value = frames
      selectedFrameIdx.value = 0
      video.currentTime = 0
      video.onseeked = null
    } else {
      video.currentTime = frames.length * interval
    }
  }
  video.onseeked = captureCurrent
  // loadeddata 时 currentTime 已是 0，再设 0 不触发 seeked，直接采首帧
  if (video.currentTime === 0) captureCurrent()
  else video.currentTime = 0
}

watch(selectedFrameIdx, () => {
  if (videoFrames.value[selectedFrameIdx.value]) {
    const img = new Image()
    img.onload = () => { coverBaseImage.value = img }
    img.src = videoFrames.value[selectedFrameIdx.value]
  }
})

function onCoverImageSelected(e: Event) {
  const file = (e.target as HTMLInputElement).files?.[0]
  if (!file) return
  coverImageFile.value = file
  const img = new Image()
  img.onload = () => { coverBaseImage.value = img }
  img.src = URL.createObjectURL(file)
}

async function generateAiCover() {
  if (!aiCoverPrompt.value.trim()) return
  aiCoverBusy.value = true
  try {
    // 走既有生图端点（D7）：请求体只发 prompt + size（platform 字段后端不存在，
    // 竖版封面用白名单内的 1024x1792）
    const data = await generateImage({
      prompt: `视频封面图，主题：${aiCoverPrompt.value}，高对比度、视觉焦点明确、适合叠加标题文字`,
      size: '1024x1792',
    })
    if (data?.imageUrl) {
      const img = new Image()
      img.crossOrigin = 'anonymous'
      img.onload = () => { coverBaseImage.value = img }
      img.src = data.imageUrl
    } else {
      aiCoverError.value = '封面生成失败，请稍后重试'
    }
  } catch {
    aiCoverError.value = '封面生成失败，请稍后重试'
  } finally { aiCoverBusy.value = false }
}

function renderCover() {
  const canvas = coverCanvasRef.value
  const src = coverBaseImage.value
  if (!canvas || !src) return
  const ctx = canvas.getContext('2d')!
  const [rw, rh] = coverRatio.value === 'free' ? [src.naturalWidth, src.naturalHeight] : coverRatio.value.split(':').map(Number)
  const ratio = rw / rh
  let w = 1080, h = Math.round(w / ratio)
  canvas.width = w; canvas.height = h
  // 底图等比裁切填充
  const ir = src.naturalWidth / src.naturalHeight
  const cr = w / h
  let sx = 0, sy = 0, sw = src.naturalWidth, sh = src.naturalHeight
  if (ir > cr) { sw = sh * cr; sx = (src.naturalWidth - sw) / 2 }
  else { sh = sw / cr; sy = (src.naturalHeight - sh) / 2 }
  ctx.drawImage(src, sx, sy, sw, sh, 0, 0, w, h)
  // 文字叠加（8 套排版注册表，任务书 #54 S2.4）
  COVER_TEXT_LAYOUTS.find((item) => item.id === coverLayout.value)?.draw(ctx, w, h, coverTitle.value, coverSubtitle.value)
}

function downloadCover() {
  renderCover()
  coverCanvasRef.value?.toBlob((blob) => {
    if (!blob) return
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a'); a.href = url; a.download = `cover-${Date.now()}.png`; a.click()
    URL.revokeObjectURL(url)
  })
}

async function saveCoverToLibrary() {
  // 幂等（AC-402）：同一封面已保存过则不再重复上传/登记/追加
  if (savedCoverAssetId) {
    coverMsg.value = '本封面已在素材库'
    setTimeout(() => { coverMsg.value = '' }, 3000)
    return
  }
  renderCover()
  coverCanvasRef.value?.toBlob(async (blob) => {
    if (!blob) return
    const file = new File([blob], `cover-${Date.now()}.png`, { type: 'image/png' })
    const mediaId = await grassland.uploadContentAssetFile(file)
    if (!mediaId) {
      coverMsg.value = '存入素材库失败'
      setTimeout(() => { coverMsg.value = '' }, 3000)
      return
    }
    // 与素材库面板同链路：上传 → 登记个人库（既有保存动作），并把资产 ID 去重写回草稿
    const asset = await grassland.createContentAsset({
      libraryType: 'personal',
      mediaId,
      category: 'other',
      title: coverTitle.value.trim() || '视频封面',
    })
    coverMsg.value = asset ? '已存入素材库' : '存入素材库失败'
    if (asset) {
      savedCoverAssetId = asset.id
      appendResultAsset(asset.id)
    }
    setTimeout(() => { coverMsg.value = '' }, 3000)
  })
}


// 任务书 #92 C-05：视频工作区自动保存与恢复（参考转写 ID/脚本筛选/分镜=子区步骤；本地视频帧与音频
// 文件不落库——D-04 只存可恢复文本与资源 ID）。
const { jumpToGrassland } = useCrossAppJump()
const resultAssetIds = ref<string[]>([])
const taskReturnId = ref('')
const jumpingBack = ref(false)
let savedCoverAssetId = ''
const autosave = useWorkspaceAutosave({
  capability: 'video',
  steps: ['templates', 'subtitles', 'bgm', 'cover'],
  currentStep: activeTab,
  collectInputs: () => ({
    tplPlatform: tplPlatform.value,
    tplForm: tplForm.value,
    subtitleSource: subtitleSource.value,
    bgmPlatform: bgmForm.platform,
    bgmForm: bgmForm.contentForm,
    coverSource: coverSource.value,
    coverRatio: coverRatio.value,
    coverTitle: coverTitle.value,
    coverSubtitle: coverSubtitle.value,
    coverLayout: coverLayout.value,
    aiCoverPrompt: aiCoverPrompt.value,
  }),
  applyInputs: (inputs) => {
    if (typeof inputs.tplPlatform === 'string') tplPlatform.value = inputs.tplPlatform
    if (typeof inputs.tplForm === 'string') tplForm.value = inputs.tplForm
    if (inputs.subtitleSource === 'new' || inputs.subtitleSource === 'history') {
      subtitleSource.value = inputs.subtitleSource
    }
    if (typeof inputs.coverSource === 'string' && ['video', 'image', 'ai'].includes(inputs.coverSource)) {
      coverSource.value = inputs.coverSource as typeof coverSource.value
    }
    if (typeof inputs.coverRatio === 'string') coverRatio.value = inputs.coverRatio
    if (typeof inputs.coverTitle === 'string') coverTitle.value = inputs.coverTitle
    if (typeof inputs.coverSubtitle === 'string') coverSubtitle.value = inputs.coverSubtitle
    if (typeof inputs.aiCoverPrompt === 'string') aiCoverPrompt.value = inputs.aiCoverPrompt
  },
  applyProject: (project) => {
    resultAssetIds.value = [...(project.resultAssetIds || [])]
    taskReturnId.value = (project.workspace?.source?.taskId as string) || project.taskId || ''
  },
  collectResultAssetIds: () => [...new Set(resultAssetIds.value)].slice(0, 20),
  isValidInput: () => Boolean(
    coverTitle.value.trim() || aiCoverPrompt.value.trim() || tplForm.value || subtitleSource.value),
  deriveTitle: () => coverTitle.value.trim().slice(0, 30) || aiCoverPrompt.value.trim().slice(0, 30) || '视频工坊项目',
  engage: () => true,
})
watch([tplPlatform, tplForm, subtitleSource, coverSource, coverRatio, coverTitle, coverSubtitle, aiCoverPrompt],
  () => autosave.queueSave())

/** 结果资产去重追加（AC-402）：同资产不重复入列，追加即落库。 */
function appendResultAsset(assetId: string): void {
  if (!assetId || resultAssetIds.value.includes(assetId)) return
  resultAssetIds.value = [...resultAssetIds.value, assetId].slice(-20)
  void autosave.flush()
}

/** 任务回跳（AC-403）：只带任务 id 跳草场工作台详情弹窗，不携带/不泄露任何详情。 */
async function backToTask(): Promise<void> {
  if (!taskReturnId.value || jumpingBack.value) return
  jumpingBack.value = true
  try {
    await jumpToGrassland(`/?task=${encodeURIComponent(taskReturnId.value)}`)
  } catch {
    // 跳转失败（被拦截/网络）：停留本页，不泄露任何任务详情；按钮复位可重试
  } finally {
    jumpingBack.value = false
  }
}

onBeforeUnmount(() => {
  if (audioUrl.value) URL.revokeObjectURL(audioUrl.value)
  if (videoUrl.value) URL.revokeObjectURL(videoUrl.value)
})
</script>

<style scoped>
.video-studio { display: flex; flex-direction: column; gap: 1rem; }
.vs-tabs { display: flex; gap: 0.5rem; border-bottom: 1px solid var(--color-border); padding-bottom: 0.5rem; }
.vs-tabs-side { margin-left: auto; display: inline-flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.result-assets-chip { padding: 3px 10px; border: 1px solid var(--color-border); border-radius: var(--radius-pill); color: var(--color-text-muted); font-size: var(--text-xs); }
.vs-tabs-side .secondary-command { min-height: 30px; padding: 0 12px; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: transparent; color: var(--color-text-secondary); font-size: var(--text-xs); cursor: pointer; }
.vs-tab { padding: 0.4rem 0.8rem; border: 1px solid transparent; border-radius: var(--radius-pill); background: transparent; cursor: pointer; font-size: 0.9rem; }
.vs-tab.active { background: var(--color-accent); color: var(--color-on-accent); border-color: var(--color-accent); }
.vs-section { display: flex; flex-direction: column; gap: 1rem; }
.vs-filter-row { display: flex; gap: 0.5rem; }
.vs-filter-row select { padding: 0.3rem; }
.vs-template-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 1rem; }
.vs-template-card { border: 1px solid var(--color-border); border-radius: var(--radius-md); padding: 0.8rem; }
.vs-template-card h4 { margin: 0 0 0.3rem; }
.tpl-meta { font-size: 0.8rem; color: var(--color-text-muted); }
.tpl-structure { font-size: 0.8rem; padding-left: 1.2rem; margin: 0.5rem 0; }
.vs-sub-source, .vs-sub-upload { display: flex; align-items: center; gap: 1rem; flex-wrap: wrap; }
.vs-sub-history { display: flex; flex-direction: column; gap: 0.3rem; max-height: 300px; overflow-y: auto; }
.vs-history-item { display: flex; gap: 1rem; padding: 0.4rem; border: 1px solid var(--color-border); border-radius: var(--radius-sm); cursor: pointer; font-size: 0.85rem; }
.vs-history-item:hover { background: var(--color-surface-hover); }
.vs-hint { font-size: 0.85rem; color: var(--color-warning); font-style: italic; }
.vs-cue-table { border: 1px solid var(--color-border); border-radius: var(--radius-md); overflow: hidden; }
.vs-cue-header { display: grid; grid-template-columns: 2em 4em 4em 1fr 5em; gap: 0.3rem; padding: 0.4rem; background: var(--surface-muted); font-weight: 600; font-size: 0.8rem; }
.vs-cue-row { display: grid; grid-template-columns: 2em 4em 4em 1fr 5em; gap: 0.3rem; padding: 0.3rem 0.4rem; align-items: center; font-size: 0.85rem; border-top: 1px solid var(--color-border); }
.vs-cue-row.active { background: var(--color-surface-highlight); }
.vs-cue-row input { width: 100%; padding: 0.15rem 0.3rem; border: 1px solid var(--color-border); border-radius: var(--radius-sm); font-size: 0.8rem; }
.vs-cue-actions { display: flex; gap: 0.2rem; }
.vs-cue-actions button { padding: 0.1rem 0.3rem; font-size: 0.75rem; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: transparent; cursor: pointer; }
.vs-export-row { display: flex; gap: 0.5rem; flex-wrap: wrap; }
.vs-bgm-form { display: flex; flex-direction: column; gap: 0.6rem; max-width: 400px; }
.vs-bgm-form label { display: flex; flex-direction: column; gap: 0.2rem; font-size: 0.85rem; }
.vs-bgm-form input, .vs-bgm-form select { padding: 0.3rem; }
.vs-bgm-result { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 0.8rem; }
.bgm-card { border: 1px solid var(--color-border); border-radius: var(--radius-md); padding: 0.6rem; }
.bgm-card h4 { margin: 0 0 0.3rem; font-size: 0.9rem; }
.bgm-card ul { padding-left: 1rem; margin: 0; font-size: 0.85rem; }
.vs-cover-btns { display: flex; gap: 0.5rem; }
.vs-cover-btns button { padding: 0.4rem 0.8rem; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: transparent; cursor: pointer; }
.vs-cover-btns button.active { background: var(--color-accent); color: var(--color-on-accent); }
.vs-frame-strip { display: flex; gap: 0.5rem; margin-top: 0.5rem; overflow-x: auto; }
.vs-frame-thumb { width: 80px; height: 45px; border: 2px solid transparent; border-radius: var(--radius-sm); overflow: hidden; cursor: pointer; }
.vs-frame-thumb.active { border-color: var(--color-accent); }
.vs-frame-thumb img { width: 100%; height: 100%; object-fit: cover; }
.vs-cover-edit { display: flex; gap: 1.5rem; }
.vs-cover-canvas-wrap { flex: 1; background: var(--surface-muted); border-radius: var(--radius-md); display: flex; align-items: center; justify-content: center; min-height: 300px; }
.vs-cover-canvas { max-width: 100%; max-height: 60vh; }
.vs-cover-controls { width: 240px; display: flex; flex-direction: column; gap: 0.5rem; }
.vs-cover-controls label { display: flex; flex-direction: column; gap: 0.2rem; font-size: 0.85rem; }
.vs-cover-controls input, .vs-cover-controls select { padding: 0.3rem; }
.vs-status { color: var(--color-text-muted); font-size: 0.85rem; }
.vs-ok { color: var(--color-success); font-size: 0.85rem; }
.vs-error { color: var(--color-danger); font-size: 0.85rem; }
.studio-upload-btn { cursor: pointer; padding: 0.4rem 0.8rem; border: 1px solid var(--color-border); border-radius: var(--radius-sm); }
.studio-upload-btn input { display: none; }
</style>
