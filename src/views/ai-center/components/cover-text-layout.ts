/**
 * 封面画布文字排版（源出 VideoStudioView 封面工作台，任务书 #54 建注册表、#89 常量收口）：
 * 8 套排版注册表 + 绘制常量 + 字体栈解析，供视频封面画布与单测共用。
 * 画布内容画在导出图片上——颜色是导出内容常量，刻意不接明暗主题 token（任务书 #89 D-01-③），
 * 导出结果不能随观者主题漂移；仅字体家族运行时从 --font-body 解析（D-02）。
 */

/** 字号是图像排版常量，不映射 --text-*（rem 尺度语义不同，D-02）。 */
const COVER_TITLE_SIZE_PX = 60
const COVER_SUBTITLE_SIZE_PX = 34

// ---- 画布内容色（导出内容色，刻意不接主题 token，D-01-③；值与收口前逐字相等） ----
const COVER_TEXT_INK = '#ffffff'
const COVER_STROKE_TITLE = 'rgba(0,0,0,0.7)'
const COVER_STROKE_SUBTITLE = 'rgba(0,0,0,0.6)'
const COVER_SCRIM_BAR = 'rgba(20,16,38,0.72)'
const COVER_SCRIM_BLOCK = 'rgba(0,0,0,0.55)'
const COVER_SCRIM_GRADIENT = 'rgba(0,0,0,0.62)'
const COVER_SCRIM_GRADIENT_CENTER = 'rgba(0,0,0,0.55)'
const COVER_SCRIM_GRADIENT_EDGE = 'rgba(0,0,0,0)'

/** --font-body 读值为空白时的回退栈（与 token 值同构，D-02），保证 ctx.font 永远拿到合法串。 */
const CANVAS_FONT_FALLBACK = "'Inter', -apple-system, BlinkMacSystemFont, 'PingFang SC', 'Hiragino Sans GB', sans-serif"

export type CoverTextLayoutId =
  | 'left-bold' | 'center-block'
  | 'top-bar' | 'bottom-bar'
  | 'center-gradient' | 'bottom-gradient'
  | 'top-stroke' | 'center-stroke'

export interface CoverTextLayout {
  id: CoverTextLayoutId
  label: string
  draw: (ctx: CanvasRenderingContext2D, w: number, h: number, title: string, subtitle: string) => void
}

/** 字体家族单一来源在 style.css 的 --font-body；读值空白即回退固定栈（§5.3），无异常路径。 */
export function canvasFontStack(): string {
  const tokenValue = getComputedStyle(document.documentElement).getPropertyValue('--font-body').trim()
  return tokenValue || CANVAS_FONT_FALLBACK
}

function titleFont(): string {
  return `bold ${COVER_TITLE_SIZE_PX}px ${canvasFontStack()}`
}

function subtitleFont(): string {
  return `${COVER_SUBTITLE_SIZE_PX}px ${canvasFontStack()}`
}

function strokeTitle(ctx: CanvasRenderingContext2D, text: string, x: number, y: number, centered: boolean): void {
  ctx.font = titleFont()
  ctx.strokeStyle = COVER_STROKE_TITLE; ctx.lineWidth = 6
  ctx.fillStyle = COVER_TEXT_INK
  if (centered) ctx.textAlign = 'center'
  ctx.strokeText(text, x, y); ctx.fillText(text, x, y)
  if (centered) ctx.textAlign = 'start'
}

function strokeSubtitle(ctx: CanvasRenderingContext2D, text: string, x: number, y: number, centered: boolean): void {
  ctx.font = subtitleFont()
  ctx.strokeStyle = COVER_STROKE_SUBTITLE; ctx.lineWidth = 4
  ctx.fillStyle = COVER_TEXT_INK
  if (centered) ctx.textAlign = 'center'
  ctx.strokeText(text, x, y); ctx.fillText(text, x, y)
  if (centered) ctx.textAlign = 'start'
}

function barLayout(ctx: CanvasRenderingContext2D, w: number, h: number, title: string, subtitle: string, top: boolean): void {
  const barH = 170
  const y0 = top ? 0 : h - barH
  ctx.fillStyle = COVER_SCRIM_BAR
  ctx.fillRect(0, y0, w, barH)
  const baseY = top ? 78 : h - 92
  if (title) strokeTitle(ctx, title, 44, baseY, false)
  if (subtitle) strokeSubtitle(ctx, subtitle, 44, baseY + 64, false)
}

function gradientLayout(ctx: CanvasRenderingContext2D, w: number, h: number, title: string, subtitle: string, bottom: boolean): void {
  const grad = ctx.createLinearGradient(0, bottom ? h * 0.55 : 0, 0, bottom ? h : h * 0.45)
  grad.addColorStop(0, COVER_SCRIM_GRADIENT_EDGE)
  grad.addColorStop(1, COVER_SCRIM_GRADIENT)
  ctx.fillStyle = grad
  ctx.fillRect(0, bottom ? h * 0.55 : 0, w, h * 0.45)
  const baseY = bottom ? h - 96 : 92
  if (title) strokeTitle(ctx, title, 40, baseY, false)
  if (subtitle) strokeSubtitle(ctx, subtitle, 40, baseY + 62, false)
}

export const COVER_TEXT_LAYOUTS: CoverTextLayout[] = [
  { id: 'left-bold', label: '下部 · 描边大字', draw: (ctx, w, h, title, subtitle) => {
    const y = h - 120
    if (title) strokeTitle(ctx, title, 40, y, false)
    if (subtitle) strokeSubtitle(ctx, subtitle, 40, y + 60, false)
  } },
  { id: 'center-block', label: '居中 · 色块底', draw: (ctx, w, h, title, subtitle) => {
    const blockH = 160
    ctx.fillStyle = COVER_SCRIM_BLOCK
    ctx.fillRect(0, (h - blockH) / 2, w, blockH)
    if (title) { ctx.font = titleFont(); ctx.fillStyle = COVER_TEXT_INK; ctx.textAlign = 'center'; ctx.fillText(title, w / 2, h / 2 - 10); ctx.textAlign = 'start' }
    if (subtitle) { ctx.font = subtitleFont(); ctx.fillStyle = COVER_TEXT_INK; ctx.textAlign = 'center'; ctx.fillText(subtitle, w / 2, h / 2 + 50); ctx.textAlign = 'start' }
  } },
  { id: 'top-bar', label: '顶部 · 衬条', draw: (ctx, w, h, t, s) => barLayout(ctx, w, h, t, s, true) },
  { id: 'bottom-bar', label: '底部 · 衬条', draw: (ctx, w, h, t, s) => barLayout(ctx, w, h, t, s, false) },
  { id: 'bottom-gradient', label: '底部 · 渐变遮罩', draw: (ctx, w, h, t, s) => gradientLayout(ctx, w, h, t, s, true) },
  { id: 'center-gradient', label: '中部 · 渐变遮罩', draw: (ctx, w, h, title, subtitle) => {
    const grad = ctx.createLinearGradient(0, h * 0.35, 0, h * 0.65)
    grad.addColorStop(0, COVER_SCRIM_GRADIENT_EDGE)
    grad.addColorStop(0.5, COVER_SCRIM_GRADIENT_CENTER)
    grad.addColorStop(1, COVER_SCRIM_GRADIENT_EDGE)
    ctx.fillStyle = grad
    ctx.fillRect(0, h * 0.35, w, h * 0.3)
    if (title) strokeTitle(ctx, title, w / 2, h / 2 - 8, true)
    if (subtitle) strokeSubtitle(ctx, subtitle, w / 2, h / 2 + 58, true)
  } },
  { id: 'top-stroke', label: '顶部 · 描边', draw: (ctx, w, h, title, subtitle) => {
    if (title) strokeTitle(ctx, title, 40, 96, false)
    if (subtitle) strokeSubtitle(ctx, subtitle, 40, 158, false)
  } },
  { id: 'center-stroke', label: '居中 · 描边', draw: (ctx, w, h, title, subtitle) => {
    if (title) strokeTitle(ctx, title, w / 2, h / 2 - 8, true)
    if (subtitle) strokeSubtitle(ctx, subtitle, w / 2, h / 2 + 58, true)
  } },
]
