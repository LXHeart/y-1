/**
 * 系列 AI 图卡模板常量（任务书 #54 B 决策，#43 D8 同款；#101 C101-01 起契约单源化）。
 *
 * 风格×布局×配色三维矩阵 + preset 组合，本地化改写自 baoyu-skills 的 baoyu-xhs-images
 * 技能（JimLiu，MIT：12 风格 × 8 布局 × 3 配色），仅取其分类学思路与命名参照，
 * 描述词按草场商家内容营销场景重写；服务端只收描述词文本（后端模板无关）。
 *
 * 任务书 #101：常量与查询函数改为从 contracts/creation-visual-presets.v1.json 导出
 * （前后端单源，服务端 CreationVisualPresetCatalog 加载同一契约）；全部旧 ID 与签名保留。
 * palette.backgroundHex 仅用于生成交付图补边，属于内容数据，不作为应用 UI 颜色。
 */
import presetsContract from '../../contracts/creation-visual-presets.v1.json'

export interface CardSeriesStyle {
  id: string
  label: string
  /** 送入计划/生图 prompt 的视觉风格描述词（后端校验 ≤200 字） */
  prompt: string
}

export interface CardSeriesLayout {
  id: string
  label: string
  /** 送入生图 prompt 的画面布局描述词（引导画面分区与文字留白——文字由前端叠排） */
  prompt: string
  /** 前端叠字的文字排版模式 */
  textLayout: 'top-title' | 'center-title' | 'bottom-list'
}

export interface CardSeriesPalette {
  id: string
  label: string
  prompt: string
  /** #101：交付图补边色（内容数据，不进应用 UI token） */
  backgroundHex: string
}

export const CARD_SERIES_PRESET_CONTRACT_VERSION = presetsContract.version

export const CARD_SERIES_STYLES: readonly CardSeriesStyle[] = Object.freeze(
  presetsContract.styles.map((style) => Object.freeze({ ...style })),
)

export const CARD_SERIES_LAYOUTS: readonly CardSeriesLayout[] = Object.freeze(
  presetsContract.layouts.map((layout) => Object.freeze({
    ...layout,
    textLayout: layout.textLayout as CardSeriesLayout['textLayout'],
  })),
)

export const CARD_SERIES_PALETTES: readonly CardSeriesPalette[] = Object.freeze(
  presetsContract.palettes.map((palette) => Object.freeze({ ...palette })),
)

/** 组合 preset：风格+布局（+可选配色）一键选择。 */
export interface CardSeriesPreset {
  id: string
  label: string
  styleId: string
  layoutId: string
  paletteId?: string
}

export const CARD_SERIES_PRESETS: readonly CardSeriesPreset[] = Object.freeze(
  presetsContract.presets.map((preset) => Object.freeze({ ...preset })),
)

export const CARD_SERIES_SIZES: ReadonlyArray<{ id: string; label: string }> = [
  { id: '1024x1792', label: '竖版 9:16（小红书/抖音/朋友圈）' },
  { id: '1024x1024', label: '方形 1:1' },
  { id: '1792x1024', label: '横版 16:9' },
]

export function findCardSeriesStyle(id: string): CardSeriesStyle | undefined {
  return CARD_SERIES_STYLES.find((item) => item.id === id)
}

export function findCardSeriesLayout(id: string): CardSeriesLayout | undefined {
  return CARD_SERIES_LAYOUTS.find((item) => item.id === id)
}

export function findCardSeriesPalette(id: string): CardSeriesPalette | undefined {
  return CARD_SERIES_PALETTES.find((item) => item.id === id)
}
