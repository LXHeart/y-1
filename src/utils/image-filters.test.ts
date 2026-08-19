import { describe, expect, test } from 'vitest'
import { DEFAULT_IMAGE_ADJUSTMENTS, buildFilterString } from './image-filters'

describe('图片编辑台 filter 映射（任务书 #43 Stage 2）', () => {
  test('默认调色输出三个 100% 基准段', () => {
    const filter = buildFilterString(DEFAULT_IMAGE_ADJUSTMENTS)
    expect(filter).toBe('brightness(100%) contrast(100%) saturate(100%)')
  })

  test('调色滑杆映射 brightness/contrast/saturate 百分比', () => {
    const filter = buildFilterString(
      { brightness: 120, contrast: 80, saturation: 60, temperature: 0 })
    expect(filter).toBe('brightness(120%) contrast(80%) saturate(60%)')
  })

  test('色温非零追加 hue-rotate（×0.6 度），为零不追加', () => {
    expect(buildFilterString({ ...DEFAULT_IMAGE_ADJUSTMENTS, temperature: 50 }))
      .toContain('hue-rotate(30deg)')
    expect(buildFilterString({ ...DEFAULT_IMAGE_ADJUSTMENTS, temperature: -50 }))
      .toContain('hue-rotate(-30deg)')
    expect(buildFilterString(DEFAULT_IMAGE_ADJUSTMENTS)).not.toContain('hue-rotate')
  })

  test('滤镜 preset 在调色之后叠加', () => {
    expect(buildFilterString(DEFAULT_IMAGE_ADJUSTMENTS, 'bright'))
      .toBe('brightness(100%) contrast(100%) saturate(100%) brightness(110%) saturate(115%)')
    expect(buildFilterString(DEFAULT_IMAGE_ADJUSTMENTS, 'warm'))
      .toContain('sepia(25%)')
    expect(buildFilterString(DEFAULT_IMAGE_ADJUSTMENTS, 'mono'))
      .toContain('grayscale(100%)')
    expect(buildFilterString(DEFAULT_IMAGE_ADJUSTMENTS, 'original'))
      .not.toContain('sepia')
  })
})
