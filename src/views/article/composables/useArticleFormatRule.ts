import { computed, type Ref } from 'vue'
import { getPlatformFormatRule } from '../../../config/platform-format-rules'

export function useArticleFormatRule(options: {
  platform: Ref<string>
  selectedTitle: Ref<string>
  content: Ref<string>
  /** 任务书 #70 卡C：任务要求必须关键词（mustInclude）覆盖检查；缺省无检查。 */
  mustInclude?: Ref<readonly string[]>
}) {
  // 任务书 #69 卡B：douyin 已是一等 platform 值，规则 id 直取（wechat 的规则 id 为 wechat-official）。
  const formatRulePlatformId = computed(() =>
    options.platform.value === 'wechat' ? 'wechat-official' : options.platform.value)

  const formatRule = computed(() => getPlatformFormatRule(formatRulePlatformId.value))

  const formatRuleSummary = computed(() => {
    const rule = formatRule.value
    if (!rule) return ''
    const titlePart = rule.maxTitleChars === null
      ? '无独立标题，由文案开头承担'
      : `标题上限 ${rule.maxTitleChars} 字`
    // 任务书 #70 卡C：媒体规格句（PRD §4.7「图片尺寸和视频比例」）；note 不进 summary。
    const parts = [`正文 ${rule.minChars}-${rule.maxChars} 字`, titlePart]
    if (rule.imageSpec) {
      parts.push(`图片建议 ${rule.imageSpec.aspect}(${rule.imageSpec.width}×${rule.imageSpec.height})`)
    }
    if (rule.videoSpec) {
      parts.push(`视频建议 ${rule.videoSpec.aspect}(${rule.videoSpec.width}×${rule.videoSpec.height})`)
    }
    return `${rule.platformLabel}规范建议：${parts.join('；')}。`
  })

  const contentCharCount = computed(() =>
    options.content.value.replace(/!\[[^\]]*\]\([^)]*\)/g, '').trim().length,
  )

  const titleOverLimit = computed(() =>
    formatRule.value?.maxTitleChars != null && options.selectedTitle.value.trim().length > formatRule.value.maxTitleChars,
  )

  const formatIssues = computed(() => {
    const rule = formatRule.value
    if (!rule) return []
    const issues: string[] = []
    const count = contentCharCount.value
    if (count > 0 && count > rule.maxChars) {
      issues.push(`正文约 ${count} 字，超过建议上限 ${rule.maxChars} 字，发布时可能被截断或影响传播。`)
    }
    if (count > 0 && count < rule.minChars) {
      issues.push(`正文约 ${count} 字，低于建议下限 ${rule.minChars} 字，建议补充核心信息。`)
    }
    if (titleOverLimit.value) {
      issues.push(`标题 ${options.selectedTitle.value.trim().length} 字，超过建议上限 ${rule.maxTitleChars} 字。`)
    }
    // 任务书 #70 卡C：必须关键词覆盖检查（PRD §4.7）——标题+正文做子串包含，未覆盖逐项提示。
    const mustIncludeTerms = (options.mustInclude?.value ?? [])
      .map((term) => term.trim())
      .filter((term) => term !== '')
    if (mustIncludeTerms.length > 0) {
      const combined = `${options.selectedTitle.value}\n${options.content.value}`.trim()
      for (const term of mustIncludeTerms) {
        if (!combined.includes(term)) {
          issues.push(`必须包含项「${term}」尚未出现在标题或正文中。`)
        }
      }
    }
    return issues
  })

  return {
    formatRule,
    formatRuleSummary,
    formatIssues,
    titleOverLimit,
  }
}
