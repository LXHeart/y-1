export type RiskSeverity = 'low' | 'medium' | 'high' | 'critical'
export type RiskCaseStatus = 'open' | 'in_review' | 'resolved' | 'dismissed'
export type RiskCaseAction = 'start_review' | 'resolve' | 'dismiss' | 'reopen'

export interface RiskSignal {
  id: string
  sourceKind: string
  sourceRef: string
  subjectKind: string
  subjectRef: string
  organizationId: string | null
  ruleCode: string
  ruleVersion: string
  score: number
  severity: RiskSeverity
  status: string
  evidence: Record<string, unknown>
  occurredAt: string | null
  createdAt: string | null
}

export interface RiskCase {
  id: string
  subjectKind: string
  subjectRef: string
  organizationId: string | null
  status: RiskCaseStatus
  severity: RiskSeverity
  score: number
  reason: string
  resolutionNote: string | null
  assignedTo: string | null
  createdAt: string | null
  updatedAt: string | null
  resolvedAt: string | null
}

export interface RiskCaseAudit {
  id: number
  caseId: string
  action: string
  actorAccountId: string | null
  actorRole: string
  note: string | null
  createdAt: string | null
}

export interface RiskCaseDetail {
  case: RiskCase
  signals: RiskSignal[]
  audits: RiskCaseAudit[]
}

export interface RiskCaseQuery {
  status?: string
  severity?: string
  subjectKind?: string
  subjectRef?: string
  limit?: number
  /** 任务 #3：分页偏移（默认 0）。 */
  offset?: number
}

export interface RiskSignalQuery {
  status?: string
  subjectKind?: string
  subjectRef?: string
  limit?: number
}
