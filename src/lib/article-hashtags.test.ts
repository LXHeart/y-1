import { describe, expect, test } from 'vitest'
import { stripTrailingHashtagLines } from './article-hashtags'

describe('stripTrailingHashtagLines（任务书 #60）', () => {
  test('剥离末尾单个话题行', () => {
    expect(stripTrailingHashtagLines('第一段\n\n第二段\n\n#探店 #美食')).toBe('第一段\n\n第二段')
  })

  test('剥离末尾多个连续话题行（含行首空格）', () => {
    expect(stripTrailingHashtagLines('正文\n\n#探店\n  #美食 聚会')).toBe('正文')
  })

  test('无话题行：仅去掉尾部空行，内容原样', () => {
    expect(stripTrailingHashtagLines('第一段\n\n第二段\n\n')).toBe('第一段\n\n第二段')
  })

  test('正文中间的 # 开头行不受影响', () => {
    expect(stripTrailingHashtagLines('#开头的引子\n\n正文\n\n结尾')).toBe('#开头的引子\n\n正文\n\n结尾')
  })

  test('只剩话题行 → 空串', () => {
    expect(stripTrailingHashtagLines('#只有标签')).toBe('')
  })
})
