/** 单条内容安全发现。`deep=true` 表示来源为 AI 语境深检。 */
export interface SafetyFinding {
  category: 'absolute_claims' | 'false_promises' | 'diversion' | 'politics' | 'porn'
    | 'illegal' | 'platform_unwanted' | string
  severity: 'high' | 'medium' | 'low' | string
  match: string
  index: number
  advice: string
  deep: boolean
}

export interface SafetyReport {
  findings: SafetyFinding[]
  lexiconVersion: string
  deepCheck: boolean
}

export interface ContentSafetyStreamFrame extends Record<string, unknown> {
  type: 'safety'
  safety: SafetyReport
}
