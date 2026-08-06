import { describe, expect, test } from 'vitest'
import {
  PLATFORM_FORMAT_RULES,
  PLATFORM_FORMAT_RULES_VERSION,
  getPlatformFormatRule,
} from './platform-format-rules'

describe('平台内容规范规则', () => {
  test('版本号存在且覆盖九个平台', () => {
    expect(PLATFORM_FORMAT_RULES_VERSION).toMatch(/^\d{4}-\d{2}-\d{2}$/)
    expect(PLATFORM_FORMAT_RULES.map((rule) => rule.platformId)).toEqual([
      'xiaohongshu', 'douyin', 'dianping', 'kuaishou', 'wechat-channels',
      'bilibili', 'wechat-official', 'zhihu', 'moments',
    ])
  })

  test('所有平台 id 唯一且字段完整可用', () => {
    const ids = PLATFORM_FORMAT_RULES.map((rule) => rule.platformId)
    expect(new Set(ids).size).toBe(ids.length)
    for (const rule of PLATFORM_FORMAT_RULES) {
      expect(rule.platformLabel.trim().length).toBeGreaterThan(0)
      expect(rule.minChars).toBeGreaterThan(0)
      expect(rule.maxChars).toBeGreaterThan(rule.minChars)
      expect(rule.maxTitleChars === null || rule.maxTitleChars > 0).toBe(true)
      expect(rule.tagHint.trim().length).toBeGreaterThan(0)
      expect(rule.emojiHint.trim().length).toBeGreaterThan(0)
      expect(rule.structureHints.length).toBeGreaterThan(0)
    }
  })

  test('查找函数命中返回规则，未命中返回 null', () => {
    expect(getPlatformFormatRule('xiaohongshu')?.maxTitleChars).toBe(20)
    expect(getPlatformFormatRule('wechat-official')?.maxTitleChars).toBe(64)
    expect(getPlatformFormatRule('zhihu')?.maxTitleChars).toBe(100)
    expect(getPlatformFormatRule('douyin')?.maxTitleChars).toBe(55)
    expect(getPlatformFormatRule('moments')?.maxTitleChars).toBeNull()
    expect(getPlatformFormatRule('weibo')).toBeNull()
    expect(getPlatformFormatRule('')).toBeNull()
  })
})
