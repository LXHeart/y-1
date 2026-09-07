// @vitest-environment happy-dom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { COVER_TEXT_LAYOUTS, canvasFontStack } from './cover-text-layout'
import type { CoverTextLayout } from './cover-text-layout'

/** 与模块内 CANVAS_FONT_FALLBACK 同值的断言串（D-02：与 --font-body token 同构的回退栈）。 */
const FALLBACK_FONT_STACK = "'Inter', -apple-system, BlinkMacSystemFont, 'PingFang SC', 'Hiragino Sans GB', sans-serif"

/** 记录器画布上下文：捕获 font/样式赋值与绘制调用，不依赖真实 canvas 实现。 */
function recordedCanvas() {
  const fonts: string[] = []
  const fillStyles: string[] = []
  const fillTextCalls: Array<[string, number, number]> = []
  const fillRectCalls: Array<[number, number, number, number]> = []
  const addColorStopCalls: Array<[number, string]> = []
  const ctx = {
    set font(value: string) { fonts.push(value) },
    get font() { return fonts[fonts.length - 1] ?? '' },
    set fillStyle(value: string) { fillStyles.push(value) },
    get fillStyle() { return fillStyles[fillStyles.length - 1] ?? '' },
    set strokeStyle(value: string) { /* 记录器不消费描边色，赋值合法即可 */ },
    get strokeStyle() { return '' },
    lineWidth: 0,
    textAlign: 'start',
    fillText: (text: string, x: number, y: number) => { fillTextCalls.push([text, x, y]) },
    strokeText: (_text: string, _x: number, _y: number) => { /* 描边调用不改变断言面 */ },
    fillRect: (x: number, y: number, w: number, h: number) => { fillRectCalls.push([x, y, w, h]) },
    createLinearGradient: () => ({
      addColorStop: (offset: number, color: string) => { addColorStopCalls.push([offset, color]) },
    }),
  } as unknown as CanvasRenderingContext2D
  return { ctx, fonts, fillStyles, fillTextCalls, fillRectCalls, addColorStopCalls }
}

function layoutOf(id: CoverTextLayout['id']): CoverTextLayout {
  const layout = COVER_TEXT_LAYOUTS.find(item => item.id === id)
  if (!layout) throw new Error(`排版 ${id} 不在注册表中`)
  return layout
}

function stubFontBodyEmpty(): void {
  vi.stubGlobal('getComputedStyle', () => ({ getPropertyValue: () => '' }))
}

describe('cover-text-layout', () => {
  afterEach(() => { vi.unstubAllGlobals() })

  it('字体栈优先读取 --font-body token', () => {
    const tokenStack = "'Inter', 'PingFang SC', sans-serif"
    vi.stubGlobal('getComputedStyle', () => ({
      getPropertyValue: (name: string) => (name === '--font-body' ? tokenStack : ''),
    }))
    expect(canvasFontStack()).toBe(tokenStack)
  })

  it('token 读空时回退与 --font-body 同构的栈', () => {
    vi.stubGlobal('getComputedStyle', () => ({ getPropertyValue: () => '' }))
    expect(canvasFontStack()).toBe(FALLBACK_FONT_STACK)
    vi.stubGlobal('getComputedStyle', () => ({ getPropertyValue: () => '   ' }))
    expect(canvasFontStack()).toBe(FALLBACK_FONT_STACK)
    expect(FALLBACK_FONT_STACK).toContain('Inter')
    expect(FALLBACK_FONT_STACK).toContain('PingFang SC')
  })

  it('center-block 用 token 字体与内容常量绘制', () => {
    stubFontBodyEmpty()
    const rec = recordedCanvas()
    layoutOf('center-block').draw(rec.ctx, 1080, 1920, '标题A1', '副标题b2')
    const titleFont = rec.fonts.find(font => font.startsWith('bold 60px '))
    expect(titleFont).toBeDefined()
    expect(titleFont).toContain('Inter')
    expect(rec.fonts.some(font => font.includes('system-ui'))).toBe(false)
    expect(rec.fillStyles).toContain('#ffffff')
    expect(rec.fillStyles).toContain('rgba(0,0,0,0.55)')
    expect(rec.fonts.some(font => font.startsWith('34px '))).toBe(true)
    expect(rec.fillTextCalls.map(call => call[0])).toEqual(['标题A1', '副标题b2'])
    // E01 守卫路径：标题/副标题为空串时跳过文字绘制（衬底不受影响）
    const guard = recordedCanvas()
    layoutOf('center-block').draw(guard.ctx, 1080, 1920, '', '')
    expect(guard.fillTextCalls).toEqual([])
  })

  it('top-bar 与 bottom-gradient 的衬底常量', () => {
    stubFontBodyEmpty()
    const topBar = recordedCanvas()
    layoutOf('top-bar').draw(topBar.ctx, 1080, 1920, 'T', 'S')
    expect(topBar.fillStyles).toContain('rgba(20,16,38,0.72)')
    expect(topBar.fillRectCalls).toContainEqual([0, 0, 1080, 170])

    const bottomGradient = recordedCanvas()
    layoutOf('bottom-gradient').draw(bottomGradient.ctx, 1080, 1920, 'T', 'S')
    const gradientColors = bottomGradient.addColorStopCalls.map(call => call[1])
    expect(gradientColors).toContain('rgba(0,0,0,0)')
    expect(gradientColors).toContain('rgba(0,0,0,0.62)')
  })
})
