<template>
  <div class="image-studio">
    <div class="studio-toolbar">
      <label class="studio-upload-btn">
        <input type="file" accept="image/jpeg,image/png,image/webp" @change="onFileSelected">
        <span>选择图片</span>
      </label>
      <span v-if="sourceFile" class="studio-file-name">{{ sourceFile.name }} ({{ sourceFile.size | 0 }} KB)</span>
      <span v-if="downsampleNote" class="studio-warn">{{ downsampleNote }}</span>
    </div>

    <div v-if="!sourceImage" class="studio-empty">
      <p>选择一张本地图片开始编辑</p>
      <p class="studio-empty-hint">支持 JPG / PNG / WebP，长边超 4096px 会自动下采样</p>
    </div>

    <div v-else class="studio-body">
      <div class="studio-canvas-wrap">
        <div ref="frameRef" class="studio-canvas-frame">
          <canvas ref="canvasRef" class="studio-canvas"></canvas>
          <div v-if="cropActive" class="studio-crop-overlay">
            <div class="studio-crop-box" :style="cropBoxStyle"
              @pointerdown="onCropPointerDown" @pointermove="onCropPointerMove"
              @pointerup="onCropPointerUp" @pointercancel="onCropPointerUp"></div>
          </div>
        </div>
      </div>

      <div class="studio-panel">
        <!-- 裁剪 -->
        <fieldset class="panel-group">
          <legend>裁剪比例</legend>
          <div class="ratio-btns">
            <button v-for="r in cropRatios" :key="r.value" type="button"
              :class="['ratio-btn', { active: cropRatio === r.value }]"
              @click="setCropRatio(r.value)">{{ r.label }}</button>
          </div>
        </fieldset>

        <!-- 旋转翻转 -->
        <fieldset class="panel-group">
          <legend>旋转 / 翻转</legend>
          <div class="transform-btns">
            <button type="button" @click="rotate90">顺时针 90°</button>
            <button type="button" @click="flipH">水平翻转</button>
            <button type="button" @click="flipV">垂直翻转</button>
          </div>
        </fieldset>

        <!-- 调色 -->
        <fieldset class="panel-group">
          <legend>调色</legend>
          <label class="slider-row"><span>亮度</span>
            <input type="range" min="0" max="200" :value="adjustments.brightness" @input="setAdj('brightness', $event)">
          </label>
          <label class="slider-row"><span>对比度</span>
            <input type="range" min="0" max="200" :value="adjustments.contrast" @input="setAdj('contrast', $event)">
          </label>
          <label class="slider-row"><span>饱和度</span>
            <input type="range" min="0" max="200" :value="adjustments.saturation" @input="setAdj('saturation', $event)">
          </label>
          <label class="slider-row"><span>色温</span>
            <input type="range" min="-100" max="100" :value="adjustments.temperature" @input="setAdj('temperature', $event)">
          </label>
        </fieldset>

        <!-- 滤镜 -->
        <fieldset class="panel-group">
          <legend>滤镜</legend>
          <div class="ratio-btns">
            <button v-for="f in filterPresets" :key="f.id" type="button"
              :class="['ratio-btn', { active: activeFilter === f.id }]"
              @click="activeFilter = f.id">{{ f.label }}</button>
          </div>
        </fieldset>

        <!-- AI 抠图 -->
        <fieldset class="panel-group">
          <legend>AI 抠图</legend>
          <button type="button" class="secondary-command" :disabled="mattingBusy || !!mattingLayer"
            @click="doMatting">{{ mattingBusy ? '处理中…' : mattingLayer ? '已抠图' : '开始抠图' }}</button>
          <p v-if="mattingError" class="studio-warn">{{ mattingError }}</p>
        </fieldset>

        <!-- 换背景 -->
        <fieldset v-if="mattingLayer" class="panel-group">
          <legend>换背景</legend>
          <div class="ratio-btns">
            <button v-for="m in bgModes" :key="m.id" type="button"
              :class="['ratio-btn', { active: bgConfig.mode === m.id }]"
              @click="bgConfig.mode = m.id">{{ m.label }}</button>
          </div>
          <div v-if="bgConfig.mode === 'color'" class="slider-row">
            <input type="color" v-model="bgConfig.color">
          </div>
          <div v-if="bgConfig.mode === 'gradient'" class="slider-row">
            <input type="color" v-model="bgConfig.gradientFrom">
            <input type="color" v-model="bgConfig.gradientTo">
          </div>
          <div v-if="bgConfig.mode === 'blur'" class="slider-row">
            <span>模糊半径</span>
            <input type="range" min="2" max="40" v-model.number="bgConfig.blurRadius">
          </div>
          <div v-if="bgConfig.mode === 'image'">
            <label class="studio-upload-btn">
              <input type="file" accept="image/*" @change="onBgImageSelected">
              <span>选择背景图</span>
            </label>
          </div>
        </fieldset>

        <!-- 导出 -->
        <fieldset class="panel-group">
          <legend>导出</legend>
          <button type="button" class="primary-command" @click="doExport">下载图片</button>
          <button type="button" class="secondary-command" @click="doExportToLibrary">存入素材库</button>
          <p v-if="exportMsg" class="studio-ok">{{ exportMsg }}</p>
        </fieldset>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, watch, computed, onMounted, nextTick } from 'vue'
import { useAiStudio } from '../../../composables/useAiStudio'
import { useGrassland } from '../../../composables/useGrassland'
import { buildFilterString } from '../../../utils/image-filters'
import type { ImageAdjustments, FilterPreset, CropRatio, BackgroundConfig, BackgroundMode } from '../../../types/grassland/ai-studio'

const MAX_LONG_EDGE = 4096

const grassland = useGrassland()
const studio = useAiStudio()

const sourceFile = ref<File | null>(null)
const sourceImage = ref<HTMLImageElement | null>(null)
const downsampleNote = ref('')
const canvasRef = ref<HTMLCanvasElement | null>(null)

// 变换状态
const rotation = ref(0)        // 0|90|180|270
const flipHorizontal = ref(false)
const flipVertical = ref(false)

// 裁剪
const cropRatios: { value: CropRatio; label: string }[] = [
  { value: 'free', label: '自由' },
  { value: '3:4', label: '小红书 3:4' },
  { value: '9:16', label: '抖音 9:16' },
  { value: '4:3', label: '点评 4:3' },
  { value: '2.35:1', label: '公众号 2.35:1' },
]
const cropRatio = ref<CropRatio>('free')
const cropActive = computed(() => cropRatio.value !== 'free')
// 裁剪框，归一化坐标（相对画布内容），导出时按框裁剪
const cropBox = reactive({ x: 0.05, y: 0.05, w: 0.9, h: 0.9 })
const frameRef = ref<HTMLDivElement | null>(null)
let cropDrag: { px: number; py: number; bx: number; by: number } | null = null

const cropBoxStyle = computed(() => ({
  position: 'absolute' as const,
  left: `${cropBox.x * 100}%`,
  top: `${cropBox.y * 100}%`,
  width: `${cropBox.w * 100}%`,
  height: `${cropBox.h * 100}%`,
  border: '2px dashed #fff',
  cursor: 'move',
  touchAction: 'none' as const,
}))

function parseRatio(r: CropRatio): [number, number] {
  if (r === 'free') return [1, 1]
  const [a, b] = r.split(':').map(Number)
  return [a, b]
}

function setCropRatio(r: CropRatio) {
  cropRatio.value = r
  if (r === 'free' || !canvasRef.value) return
  // 居中 90% 区域内取目标比例的最大框（归一化：先设宽 0.9 反解高，超高则换轴）
  const cw = canvasRef.value.width
  const ch = canvasRef.value.height
  const ratio = parseRatio(r)[0] / parseRatio(r)[1]
  let w = 0.9
  let h = (w * cw) / (ratio * ch)
  if (h > 0.9) {
    h = 0.9
    w = (h * ratio * ch) / cw
  }
  cropBox.x = (1 - w) / 2
  cropBox.y = (1 - h) / 2
  cropBox.w = w
  cropBox.h = h
}

function onCropPointerDown(e: PointerEvent) {
  const frame = frameRef.value
  if (!frame) return
  const rect = frame.getBoundingClientRect()
  cropDrag = {
    px: (e.clientX - rect.left) / rect.width,
    py: (e.clientY - rect.top) / rect.height,
    bx: cropBox.x,
    by: cropBox.y,
  }
  ;(e.currentTarget as HTMLElement).setPointerCapture(e.pointerId)
}

function onCropPointerMove(e: PointerEvent) {
  if (!cropDrag || !frameRef.value) return
  const rect = frameRef.value.getBoundingClientRect()
  const dx = (e.clientX - rect.left) / rect.width - cropDrag.px
  const dy = (e.clientY - rect.top) / rect.height - cropDrag.py
  cropBox.x = Math.min(Math.max(0, cropDrag.bx + dx), 1 - cropBox.w)
  cropBox.y = Math.min(Math.max(0, cropDrag.by + dy), 1 - cropBox.h)
}

function onCropPointerUp() { cropDrag = null }

// 调色
const adjustments = reactive<ImageAdjustments>({
  brightness: 100, contrast: 100, saturation: 100, temperature: 0,
})

// 滤镜
const filterPresets: { id: FilterPreset; label: string }[] = [
  { id: 'original', label: '原图' },
  { id: 'bright', label: '明快' },
  { id: 'warm', label: '暖调' },
  { id: 'mono', label: '黑白' },
]
const activeFilter = ref<FilterPreset>('original')

// AI 抠图
const mattingBusy = ref(false)
const mattingLayer = ref<HTMLImageElement | null>(null)
const mattingError = ref('')
let mattingMediaId: string | null = null

// 背景
const bgModes: { id: BackgroundMode; label: string }[] = [
  { id: 'color', label: '纯色' },
  { id: 'gradient', label: '渐变' },
  { id: 'blur', label: '模糊原图' },
  { id: 'image', label: '背景图' },
]
const bgConfig = reactive<BackgroundConfig>({
  mode: 'color', color: '#ffffff', gradientFrom: '#667eea', gradientTo: '#764ba2',
  blurRadius: 10, imageFile: null,
})
const bgImageEl = ref<HTMLImageElement | null>(null)

// 导出
const exportMsg = ref('')

// ---------- 文件选择 & 下采样 ----------
async function onFileSelected(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  sourceFile.value = file
  downsampleNote.value = ''
  mattingLayer.value = null
  mattingMediaId = null
  mattingError.value = ''
  exportMsg.value = ''
  cropRatio.value = 'free'

  const img = new Image()
  img.onload = () => {
    let w = img.naturalWidth, h = img.naturalHeight
    const longEdge = Math.max(w, h)
    if (longEdge > MAX_LONG_EDGE) {
      const scale = MAX_LONG_EDGE / longEdge
      w = Math.round(w * scale); h = Math.round(h * scale)
      downsampleNote.value = `长边超 ${MAX_LONG_EDGE}px，已自动下采样至 ${w}×${h}`
      const c = document.createElement('canvas')
      c.width = w; c.height = h
      c.getContext('2d')!.drawImage(img, 0, 0, w, h)
      const dsImg = new Image()
      dsImg.onload = () => { sourceImage.value = dsImg; nextTick(render) }
      dsImg.src = c.toDataURL(file.type || 'image/png')
    } else {
      sourceImage.value = img
      nextTick(render)
    }
  }
  img.src = URL.createObjectURL(file)
}

// ---------- 渲染管线 ----------
// 滤镜/调色 → CSS filter 字符串在 utils/image-filters（预览与导出同源语法）

/**
 * 把图层按当前旋转/翻转变换画到独立画布（可选 CSS filter）。
 * 渲染预览与导出合成共用同一几何，保证抠图主体与预览所见一致。
 */
function transformedLayer(
  source: CanvasImageSource, srcW: number, srcH: number, filter?: string,
): HTMLCanvasElement {
  const swapped = rotation.value === 90 || rotation.value === 270
  const dw = swapped ? srcH : srcW
  const dh = swapped ? srcW : srcH
  const c = document.createElement('canvas')
  c.width = dw
  c.height = dh
  const ctx = c.getContext('2d')!
  ctx.save()
  ctx.translate(dw / 2, dh / 2)
  ctx.rotate((rotation.value * Math.PI) / 180)
  if (flipHorizontal.value) ctx.scale(-1, 1)
  if (flipVertical.value) ctx.scale(1, -1)
  if (filter) ctx.filter = filter
  ctx.drawImage(source, -srcW / 2, -srcH / 2)
  ctx.restore()
  return c
}

function render() {
  const canvas = canvasRef.value
  const src = sourceImage.value
  if (!canvas || !src) return
  const layer = transformedLayer(
      src, src.naturalWidth, src.naturalHeight, buildFilterString(adjustments, activeFilter.value))
  canvas.width = layer.width
  canvas.height = layer.height
  canvas.getContext('2d')!.drawImage(layer, 0, 0)
}

function rotate90() {
  rotation.value = (rotation.value + 90) % 360
  render()
  // 画布宽高互换后归一化框不再匹配比例，重置为居中默认框
  setCropRatio(cropRatio.value)
}
function flipH() { flipHorizontal.value = !flipHorizontal.value; render() }
function flipV() { flipVertical.value = !flipVertical.value; render() }
function setAdj(key: keyof ImageAdjustments, e: Event) {
  adjustments[key] = Number((e.target as HTMLInputElement).value)
  render()
}

watch(activeFilter, render)

// ---------- AI 抠图 ----------
async function doMatting() {
  if (!sourceFile.value) return
  mattingBusy.value = true
  mattingError.value = ''
  try {
    // 上传原图（复用会话缓存：同一文件不重复上传）
    if (!mattingMediaId) {
      mattingMediaId = await studio.uploadImageFile(sourceFile.value)
      if (!mattingMediaId) { mattingError.value = studio.error.value; return }
    }
    const result = await studio.mattingImage(mattingMediaId)
    if (!result) { mattingError.value = studio.error.value; return }
    // 加载抠图 PNG
    const img = new Image()
    img.crossOrigin = 'anonymous'
    img.onload = () => { mattingLayer.value = img; render() }
    img.onerror = () => { mattingError.value = '抠图结果加载失败' }
    img.src = result.imageUrl
  } finally {
    mattingBusy.value = false
  }
}

// ---------- 背景 ----------
function onBgImageSelected(e: Event) {
  const file = (e.target as HTMLInputElement).files?.[0]
  if (!file) return
  bgConfig.imageFile = file
  const img = new Image()
  img.onload = () => { bgImageEl.value = img; render() }
  img.src = URL.createObjectURL(file)
}

// ---------- 导出 ----------
/** 裁剪框在画布像素坐标系的区域；free 即整幅画布。 */
function cropFrame(): { x: number; y: number; w: number; h: number } {
  const canvas = canvasRef.value!
  if (cropRatio.value === 'free') {
    return { x: 0, y: 0, w: canvas.width, h: canvas.height }
  }
  return {
    x: Math.round(cropBox.x * canvas.width),
    y: Math.round(cropBox.y * canvas.height),
    w: Math.round(cropBox.w * canvas.width),
    h: Math.round(cropBox.h * canvas.height),
  }
}

function composeExportCanvas(): HTMLCanvasElement {
  const base = canvasRef.value!
  const frame = cropFrame()
  const outCanvas = document.createElement('canvas')
  outCanvas.width = frame.w
  outCanvas.height = frame.h
  const ctx = outCanvas.getContext('2d')!

  if (mattingLayer.value) {
    drawBackground(ctx, frame)
    // 抠图主体是原图坐标系：套用与预览相同的旋转/翻转变换后与主画布同尺寸，
    // 再取同一裁剪区域——下采样/旋转/裁剪三者都不会错位
    const subject = transformedLayer(
      mattingLayer.value, mattingLayer.value.naturalWidth, mattingLayer.value.naturalHeight)
    ctx.drawImage(subject, frame.x, frame.y, frame.w, frame.h, 0, 0, frame.w, frame.h)
  } else {
    ctx.drawImage(base, frame.x, frame.y, frame.w, frame.h, 0, 0, frame.w, frame.h)
  }
  return outCanvas
}

function drawBackground(ctx: CanvasRenderingContext2D, frame: { x: number; y: number; w: number; h: number }) {
  const { w, h } = frame
  if (bgConfig.mode === 'color') {
    ctx.fillStyle = bgConfig.color
    ctx.fillRect(0, 0, w, h)
  } else if (bgConfig.mode === 'gradient') {
    const grad = ctx.createLinearGradient(0, 0, w, h)
    grad.addColorStop(0, bgConfig.gradientFrom)
    grad.addColorStop(1, bgConfig.gradientTo)
    ctx.fillStyle = grad
    ctx.fillRect(0, 0, w, h)
  } else if (bgConfig.mode === 'blur') {
    // 主体突出：模糊的原图（含调色/旋转）作背景
    ctx.filter = `blur(${bgConfig.blurRadius}px)`
    ctx.drawImage(canvasRef.value!, frame.x, frame.y, w, h, 0, 0, w, h)
    ctx.filter = 'none'
  } else if (bgConfig.mode === 'image' && bgImageEl.value) {
    // 等比裁切填充
    const ir = bgImageEl.value.naturalWidth / bgImageEl.value.naturalHeight
    const cr = w / h
    let sx = 0, sy = 0, sw = bgImageEl.value.naturalWidth, sh = bgImageEl.value.naturalHeight
    if (ir > cr) { sw = sh * cr; sx = (bgImageEl.value.naturalWidth - sw) / 2 }
    else { sh = sw / cr; sy = (bgImageEl.value.naturalHeight - sh) / 2 }
    ctx.drawImage(bgImageEl.value, sx, sy, sw, sh, 0, 0, w, h)
  }
}

function downloadBlob(blob: Blob, name: string) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url; a.download = name; a.click()
  URL.revokeObjectURL(url)
}

function timestampName(ext: string) {
  const ts = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19)
  return `edited-${ts}.${ext}`
}

function doExport() {
  const out = composeExportCanvas()
  out.toBlob((blob) => {
    if (!blob) return
    downloadBlob(blob, timestampName('png'))
    exportMsg.value = '已下载'
    setTimeout(() => { exportMsg.value = '' }, 3000)
  }, 'image/png')
}

async function doExportToLibrary() {
  const out = composeExportCanvas()
  out.toBlob(async (blob) => {
    if (!blob) return
    const file = new File([blob], timestampName('png'), { type: 'image/png' })
    const mediaId = await grassland.uploadContentAssetFile(file)
    if (mediaId) {
      exportMsg.value = '已存入素材库'
      setTimeout(() => { exportMsg.value = '' }, 3000)
    }
  }, 'image/png')
}

onMounted(() => { if (sourceImage.value) render() })
</script>

<style scoped>
.image-studio { display: flex; flex-direction: column; gap: 1rem; }
.studio-toolbar { display: flex; align-items: center; gap: 1rem; flex-wrap: wrap; }
.studio-upload-btn { cursor: pointer; padding: 0.4rem 0.8rem; border: 1px solid var(--border, #ccc); border-radius: 4px; }
.studio-upload-btn input { display: none; }
.studio-file-name { font-size: 0.85rem; color: var(--text-secondary, #666); }
.studio-warn { color: var(--warning, #b45309); font-size: 0.85rem; }
.studio-ok { color: var(--success, #16a34a); font-size: 0.85rem; }
.studio-empty { text-align: center; padding: 3rem 1rem; color: var(--text-secondary, #888); }
.studio-empty-hint { font-size: 0.85rem; margin-top: 0.5rem; }
.studio-body { display: flex; gap: 1.5rem; }
.studio-canvas-wrap { flex: 1; position: relative; background: #1a1a1a; border-radius: 6px; overflow: hidden; display: flex; align-items: center; justify-content: center; min-height: 300px; }
.studio-canvas-frame { position: relative; display: inline-block; max-width: 100%; line-height: 0; }
.studio-canvas { max-width: 100%; max-height: 70vh; display: block; }
.studio-crop-overlay { position: absolute; inset: 0; pointer-events: none; }
.studio-crop-box { pointer-events: auto; box-sizing: border-box; }
.studio-panel { width: 260px; display: flex; flex-direction: column; gap: 0.75rem; overflow-y: auto; max-height: 75vh; }
.panel-group { border: 1px solid var(--border, #ddd); border-radius: 6px; padding: 0.6rem; }
.panel-group legend { font-weight: 600; font-size: 0.85rem; padding: 0 0.3rem; }
.ratio-btns { display: flex; flex-wrap: wrap; gap: 0.3rem; }
.ratio-btn { padding: 0.25rem 0.5rem; font-size: 0.75rem; border: 1px solid var(--border, #ccc); border-radius: 3px; background: transparent; cursor: pointer; }
.ratio-btn.active { background: var(--primary, #3b82f6); color: #fff; border-color: var(--primary, #3b82f6); }
.transform-btns { display: flex; gap: 0.3rem; }
.transform-btns button { flex: 1; font-size: 0.75rem; padding: 0.3rem; border: 1px solid var(--border, #ccc); border-radius: 3px; background: transparent; cursor: pointer; }
.slider-row { display: flex; align-items: center; gap: 0.5rem; font-size: 0.8rem; margin: 0.25rem 0; }
.slider-row span { min-width: 3em; }
.slider-row input[type="range"] { flex: 1; }
</style>
