import { computed, type Ref } from 'vue'
import { getPlatformFormatRule } from '../../../config/platform-format-rules'

export function useArticleFormatRule(options: {
  platform: Ref<string>
  selectedTitle: Ref<string>
  content: Ref<string>
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
    return `${rule.platformLabel}规范建议：正文 ${rule.minChars}-${rule.maxChars} 字；${titlePart}。`
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
    return issues
  })

  return {
    formatRule,
    formatRuleSummary,
    formatIssues,
    titleOverLimit,
  }
}
