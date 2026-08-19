import { describe, expect, test } from 'vitest'
import { VIDEO_EDIT_TEMPLATES } from './video-edit-templates'

describe('视频剪辑模板常量（任务书 #43 Stage 3）', () => {
  test('至少 10 个模板且 id 唯一', () => {
    expect(VIDEO_EDIT_TEMPLATES.length).toBeGreaterThanOrEqual(10)
    const ids = new Set(VIDEO_EDIT_TEMPLATES.map(t => t.id))
    expect(ids.size).toBe(VIDEO_EDIT_TEMPLATES.length)
  })

  test('每个模板的 timeShare 总和为 1（±0.01 容差）', () => {
    for (const template of VIDEO_EDIT_TEMPLATES) {
      const total = template.structure.reduce((sum, beat) => sum + beat.timeShare, 0)
      expect(total).toBeCloseTo(1, 2)
    }
  })

  test('平台与形式筛选（前端零积分过滤）', () => {
    const douyin = VIDEO_EDIT_TEMPLATES.filter(t => t.platforms.includes('douyin'))
    expect(douyin.length).toBeGreaterThan(0)
    const tutorials = VIDEO_EDIT_TEMPLATES.filter(t => t.forms.includes('tutorial'))
    expect(tutorials.length).toBeGreaterThan(0)
    expect(tutorials.every(t => t.structure.length > 0)).toBe(true)
  })

  test('覆盖任务书点名的十类场景', () => {
    const names = VIDEO_EDIT_TEMPLATES.map(t => t.name).join('/')
    expect(names).toContain('抖音口播')
    expect(names).toContain('剧情种草')
    expect(names).toContain('探店')
    expect(names).toContain('美食制作')
    expect(names).toContain('开箱')
    expect(names).toContain('对比测评')
    expect(names).toContain('教程清单')
    expect(names).toContain('朋友圈好物')
    expect(names).toContain('图文轮播')
    expect(names).toContain('公众号头图封面')
  })
})
