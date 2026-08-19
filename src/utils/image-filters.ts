/**
 * 图片编辑台滤镜映射（任务书 #43 Stage 2，D2）：调色滑杆 + 滤镜 preset →
 * 单条 CSS filter 字符串。预览与导出共用同一字符串（ctx.filter 与 CSS 同源语法）。
 */
import type { FilterPreset, ImageAdjustments } from '../types/grassland/ai-studio'

export const DEFAULT_IMAGE_ADJUSTMENTS: ImageAdjustments = {
  brightness: 100, contrast: 100, saturation: 100, temperature: 0,
}

export function buildFilterString(
  adjustments: ImageAdjustments, preset: FilterPreset = 'original',
): string {
  const parts: string[] = []
  parts.push(`brightness(${adjustments.brightness}%)`)
  parts.push(`contrast(${adjustments.contrast}%)`)
  parts.push(`saturate(${adjustments.saturation}%)`)
  if (adjustments.temperature !== 0) {
    // 色温用 hue-rotate 近似（canvas 与 CSS 均无原生色温滤镜）
    parts.push(`hue-rotate(${adjustments.temperature * 0.6}deg)`)
  }
  if (preset === 'bright') {
    parts.push('brightness(110%)', 'saturate(115%)')
  } else if (preset === 'warm') {
    parts.push('sepia(25%)', 'saturate(120%)')
  } else if (preset === 'mono') {
    parts.push('grayscale(100%)')
  }
  return parts.join(' ')
}
