/**
 * PRD §4.7 平台适配与规范检查：按平台给出只读的内容规范提示。
 *
 * 这些规则仅用于前端生成与编辑时的提示和轻校验，不作为服务端权威校验。
 * 数值为建议值，可由运营配置演进；规则版本化，更新不改变历史任务与历史记录。
 */
import type { AiPlatformId } from '../types/ai-creation'
import rulesContract from '../../contracts/platform-format-rules.json'

export const PLATFORM_FORMAT_RULES_VERSION = rulesContract.version

/**
 * 平台媒体主规格（任务书 #70 卡C，PRD §4.7「图片尺寸和视频比例」维度）：
 * aspect 为发布端建议画幅；width/height 为对应像素；note 为补充说明（不进 summary）。
 */
export interface PlatformMediaSpec {
  aspect: string
  width: number
  height: number
  note?: string
}

export interface PlatformFormatRule {
  platformId: AiPlatformId
  platformLabel: string
  /** 正文字数建议下限。 */
  minChars: number
  /** 正文字数建议上限（建议值，可由运营配置演进）。 */
  maxChars: number
  /** 标题长度上限；null 表示该平台没有独立标题，以正文/文案开头承担标题作用。 */
  maxTitleChars: number | null
  /** 标签使用提示。 */
  tagHint: string
  /** Emoji 使用提示。 */
  emojiHint: string
  /** 内容结构要点，对应 PRD §4.7 表格“主要适配内容”。 */
  structureHints: readonly string[]
  /** 图片主规格建议；null 表示该平台无图片主规格建议。 */
  imageSpec?: PlatformMediaSpec | null
  /** 视频主规格建议；null 表示该平台无视频主规格建议。 */
  videoSpec?: PlatformMediaSpec | null
}

const PLATFORM_FORMAT_RULES: readonly PlatformFormatRule[] = Object.freeze(rulesContract.platforms.map((rule) => Object.freeze({
  ...rule,
  platformId: rule.platformId as AiPlatformId,
  structureHints: Object.freeze([...rule.structureHints]),
})))
// The contract file is the single source of truth for client/server rule values.

export { PLATFORM_FORMAT_RULES }

export function getPlatformFormatRule(platformId: AiPlatformId | string): PlatformFormatRule | null {
  return PLATFORM_FORMAT_RULES.find((rule) => rule.platformId === platformId) ?? null
}
