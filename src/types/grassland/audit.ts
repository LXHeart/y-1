export type UnifiedAuditSource =
  | 'identity'
  | 'permission'
  | 'task_review'
  | 'ops_case'
  | 'dispute'
  | 'evidence_access'

export interface IdentityAdminAudit {
  id: string
  accountId: string
  action: string
  fromIdentityType: string | null
  toIdentityType: string | null
  sessionId: string | null
  deviceId: string | null
  ipAddress: string | null
  userAgent: string | null
  occurredAt: string | null
}

export interface TaskReviewAudit {
  id: string
  taskId: string
  action: string
  reviewerAccountId: string | null
  note: string | null
  createdAt: string | null
}

export interface DisputeAudit {
  id: number
  disputeId: string
  action: string
  actorAccountId: string | null
  actorRole: string | null
  note: string | null
  createdAt: string | null
}

export interface EvidenceAccessAudit {
  id: number
  evidenceId: string
  disputeId: string
  viewerAccountId: string
  viewerRole: string
  purpose: string
  viewedAt: string | null
}

export interface EvidenceAccessAuditQuery {
  disputeId?: string
  evidenceId?: string
  viewerAccountId?: string
  viewerRole?: string
  from?: string
  to?: string
  limit?: number
}
