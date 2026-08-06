import { describe, expect, test } from 'vitest'
import {
  STYLE_TEMPLATES,
  STYLE_TEMPLATE_VERSION,
  getStyleTemplate,
} from './style-templates'

describe('风格化脚本模板', () => {
  test('版本号存在且模板按 PRD 暴露六种抽象风格', () => {
    expect(STYLE_TEMPLATE_VERSION).toMatch(/^\d{4}-\d{2}-\d{2}$/)
    expect(STYLE_TEMPLATES.map((template) => template.id)).toEqual([
      'light-comedy',
      'reversal-opening',
      'observational-humor',
      'professional-review',
      'emotional-story',
      'checklist-dense',
    ])
  })

  test('所有条目 id 唯一且具备非空的 label 与表达特征描述', () => {
    const ids = STYLE_TEMPLATES.map((template) => template.id)
    expect(new Set(ids).size).toBe(ids.length)
    for (const template of STYLE_TEMPLATES) {
      expect(template.label.trim().length).toBeGreaterThan(0)
      expect(template.description.trim().length).toBeGreaterThan(0)
    }
  })

  test('查找函数命中返回模板，未命中返回 null', () => {
    expect(getStyleTemplate('light-comedy')?.label).toBe('轻喜剧/段子式表达')
    expect(getStyleTemplate('checklist-dense')?.id).toBe('checklist-dense')
    expect(getStyleTemplate('unknown-style')).toBeNull()
    expect(getStyleTemplate('')).toBeNull()
  })
})
