import { describe, expect, test } from 'vitest'
import {
  CARD_SERIES_LAYOUTS,
  CARD_SERIES_PALETTES,
  CARD_SERIES_PRESETS,
  CARD_SERIES_SIZES,
  CARD_SERIES_STYLES,
  findCardSeriesLayout,
  findCardSeriesPalette,
  findCardSeriesStyle,
} from './card-series-templates'

/**
 * 任务书 #54 B 决策：12 风格 × 8 布局 × 3 配色静态矩阵 + preset。
 * 快照锁定维度规模与描述词非空（描述词进服务端 prompt，后端校验 ≤200 字）。
 */
describe('card-series-templates 常量', () => {
  test('矩阵规模锁定：12 风格 × 8 布局 × 3 配色', () => {
    expect(CARD_SERIES_STYLES).toHaveLength(12)
    expect(CARD_SERIES_LAYOUTS).toHaveLength(8)
    expect(CARD_SERIES_PALETTES).toHaveLength(3)
  })

  test('每项描述词非空且 ≤200 字（后端契约）', () => {
    for (const style of CARD_SERIES_STYLES) {
      expect(style.id).toBeTruthy()
      expect(style.prompt.length).toBeGreaterThan(4)
      expect(style.prompt.length).toBeLessThanOrEqual(200)
    }
    for (const layout of CARD_SERIES_LAYOUTS) {
      expect(layout.prompt.length).toBeGreaterThan(4)
      expect(layout.prompt.length).toBeLessThanOrEqual(200)
      expect(['top-title', 'center-title', 'bottom-list']).toContain(layout.textLayout)
    }
    for (const palette of CARD_SERIES_PALETTES) {
      expect(palette.prompt.length).toBeGreaterThan(4)
      expect(palette.prompt.length).toBeLessThanOrEqual(200)
    }
  })

  test('preset 引用的风格/布局/配色都存在', () => {
    expect(CARD_SERIES_PRESETS.length).toBeGreaterThanOrEqual(6)
    for (const preset of CARD_SERIES_PRESETS) {
      expect(findCardSeriesStyle(preset.styleId)).toBeDefined()
      expect(findCardSeriesLayout(preset.layoutId)).toBeDefined()
      if (preset.paletteId) expect(findCardSeriesPalette(preset.paletteId)).toBeDefined()
    }
  })

  test('尺寸选项与后端 SIZES 白名单一致', () => {
    expect(CARD_SERIES_SIZES.map((item) => item.id)).toEqual(['1024x1792', '1024x1024', '1792x1024'])
  })

  test('id 唯一', () => {
    expect(new Set(CARD_SERIES_STYLES.map((item) => item.id)).size).toBe(CARD_SERIES_STYLES.length)
    expect(new Set(CARD_SERIES_LAYOUTS.map((item) => item.id)).size).toBe(CARD_SERIES_LAYOUTS.length)
    expect(new Set(CARD_SERIES_PALETTES.map((item) => item.id)).size).toBe(CARD_SERIES_PALETTES.length)
  })

  // 任务书 #101 C101-01：常量改由 contracts/creation-visual-presets.v1.json 单源导出。
  // 旧 ID／文案／签名全部保留；palette 新增交付图补边色（内容数据，非 UI token）。
  test('#101 契约单源：全部旧 ID 与文案保留', async () => {
    const contract = (await import('../../contracts/creation-visual-presets.v1.json')).default
    expect(contract.version).toBeTruthy()
    expect(CARD_SERIES_STYLES.map((item) => item.id)).toEqual(contract.styles.map((item) => item.id))
    expect(CARD_SERIES_LAYOUTS.map((item) => item.id)).toEqual(contract.layouts.map((item) => item.id))
    expect(CARD_SERIES_PALETTES.map((item) => item.id)).toEqual(contract.palettes.map((item) => item.id))
    expect(CARD_SERIES_PRESETS.map((item) => item.id)).toEqual(contract.presets.map((item) => item.id))
    // 文案与描述词逐字一致（不因迁移改写）
    for (const [index, style] of CARD_SERIES_STYLES.entries()) {
      expect(style.label).toBe(contract.styles[index].label)
      expect(style.prompt).toBe(contract.styles[index].prompt)
    }
    for (const [index, layout] of CARD_SERIES_LAYOUTS.entries()) {
      expect(layout.label).toBe(contract.layouts[index].label)
      expect(layout.textLayout).toBe(contract.layouts[index].textLayout)
    }
  })

  test('#101 palette 补边色为固定内容数据', () => {
    expect(CARD_SERIES_PALETTES.map((item) => [item.id, item.backgroundHex])).toEqual([
      ['macaron', '#F5F0E8'],
      ['warm', '#FFECD2'],
      ['neon', '#1A1025'],
    ])
    for (const palette of CARD_SERIES_PALETTES) {
      expect(palette.backgroundHex).toMatch(/^#[0-9A-F]{6}$/i)
    }
  })
})
