export type PersonalDataExportStatus = 'queued' | 'processing' | 'completed' | 'failed' | 'expired'

export interface PersonalDataExport {
  id: string
  status: PersonalDataExportStatus
  format: 'zip'
  createdAt: string | null
  completedAt: string | null
  expiresAt: string | null
  sizeBytes: number | null
  sha256: string | null
  downloadUrl?: string
  errorCode?: string | null
}

export interface AccountClosureBlocker {
  domain: 'identity' | 'marketplace' | 'finance' | 'trust' | 'intelligence'
  code: string
  message: string
  count: number
  amountCents: number | null
}

export interface AccountClosureCheck {
  eligible: boolean
  blockers: AccountClosureBlocker[]
  domains: Record<string, boolean>
}

export interface AccountClosureRequest {
  id: string
  status: 'blocked' | 'retention' | 'erasing' | 'completed' | 'cancelled' | 'failed'
  blockers: AccountClosureBlocker[]
  retentionUntil: string | null
  requestedAt: string | null
  completedAt: string | null
  errorCode: string | null
  check?: AccountClosureCheck
  existing?: boolean
}

export interface PiiLifecycleAudit {
  id: string
  action: string
  requestId: string | null
  actorType: 'account' | 'system' | 'admin'
  detail: Record<string, unknown>
  occurredAt: string | null
}
