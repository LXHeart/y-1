import { describe, expect, it } from 'vitest'
import { ref } from 'vue'
import { useArticleFormatRule } from './useArticleFormatRule'

function setup(platform: string, selectedTitle = '', content = '', mustInclude: string[] = []) {
  return useArticleFormatRule({
    platform: ref(platform),
    selectedTitle: ref(selectedTitle),
    content: ref(content),
    mustInclude: ref(mustInclude),
  })
}

describe('useArticleFormatRule（任务书 #70 卡C：媒体规格句 + mustInclude 覆盖检查）', () => {
  it('有规格的平台 summary 追加图片/视频建议句（douyin 双规格）', () => {
    const { formatRuleSummary } = setup('douyin')
    expect(formatRuleSummary.value).toBe(
      '抖音规范建议：正文 15-300 字；标题上限 55 字；图片建议 9:16(1080×1920)；视频建议 9:16(1080×1920)。')
  })

  it('双 null 平台（wechat-official）不追加规格句', () => {
    const { formatRuleSummary } = setup('wechat')
    expect(formatRuleSummary.value).toBe(
      '公众号规范建议：正文 300-3000 字；标题上限 64 字。')
  })

  it('仅 imageSpec 的平台（moments）只追加图片句', () => {
    const { formatRuleSummary } = setup('moments')
    expect(formatRuleSummary.value).toBe(
      '朋友圈规范建议：正文 10-200 字；无独立标题，由文案开头承担；图片建议 1:1(1080×1080)。')
  })

  it('mustInclude 覆盖检查：标题或正文命中即通过，未覆盖逐项提示', () => {
    const covered = setup('douyin', '周六探店', '正文里提到了 周六探店 和 会员价。', ['周六探店', '会员价'])
    expect(covered.formatIssues.value).toEqual([])

    const missing = setup('douyin', '标题', '这段正文完全没有出现任何任务要求的关键词。', ['周六探店', '会员价'])
    expect(missing.formatIssues.value).toEqual([
      '必须包含项「周六探店」尚未出现在标题或正文中。',
      '必须包含项「会员价」尚未出现在标题或正文中。',
    ])
  })

  it('mustInclude 空白项被忽略；未传入时不做覆盖检查', () => {
    const blankOnly = setup('douyin', '标题', '这段正文长度超过十五个字以避免下限提示。', ['  ', ''])
    expect(blankOnly.formatIssues.value).toEqual([])

    const noTerms = setup('douyin', '标题', '这段正文长度超过十五个字以避免下限提示。')
    expect(noTerms.formatIssues.value).toEqual([])
  })

  it('既有字数检查不受影响（超上限仍提示）', () => {
    const { formatIssues } = setup('douyin', '标题', '字'.repeat(301))
    expect(formatIssues.value).toEqual([
      '正文约 301 字，超过建议上限 300 字，发布时可能被截断或影响传播。'])
  })
})
