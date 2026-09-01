/** 单条内容安全发现。`deep=true` 表示来源为 AI 语境深检。 */
export interface SafetyFinding {
  category: 'absolute_claims' | 'false_promises' | 'diversion' | 'politics' | 'porn'
    | 'illegal' | 'platform_unwanted' | string
  severity: 'high' | 'medium' | 'low' | string
  match: string
  index: number
  advice: string
  deep: boolean
  /** low_originality 的文内重复片段 top5（任务书 #63；仅该类别携带，其余缺省）。 */
  fragments?: string[]
}

export interface SafetyReport {
  findings: SafetyFinding[]
  lexiconVersion: string
  deepCheck: boolean
  appliedOverlays?: string[]
}

export interface ContentSafetyStreamFrame extends Record<string, unknown> {
  type: 'safety'
  safety: SafetyReport
}
