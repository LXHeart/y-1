// ---------- trust：审判官池 + 投票 ----------

import type { AdjudicationSnapshot } from './dispute'

/** 审判官入池记录。active=false 为已退池（软删，保留历史面板/投票完整性）。 */
export interface Judge {
  id: string
  accountId: string
  /** 归属组织；null = 平台级审判官。抽签时排除与争议同组织者。 */
  organizationId: string | null
  /** 报名时的有效等级快照；抽签和运营授权时仍会实时复验。 */
  eligibilityTier: number
  active: boolean
  opsAdmitted: boolean
  version: number
  opsAdmittedAt: string | null
  opsAdmittedBy: string | null
  createdAt: string | null
}

export interface JudgeAdmissionAudit {
  id: number
  action: 'granted' | 'revoked'
  actorAccountId: string
  reason: string
  previousVersion: number
  newVersion: number
  createdAt: string | null
}

export interface AdminJudge extends Judge {
  audit?: JudgeAdmissionAudit[]
}

export interface AdminJudgePage {
  items: AdminJudge[]
  nextCursor: string | null
  hasMore: boolean
}

export interface UpdateJudgeAdmissionInput {
  admitted: boolean
  expectedVersion: number
  reason: string
}

/** 投票选择。abstain 不计入任一方多数。 */
export type VoteChoice = 'for_merchant' | 'for_recommender' | 'abstain'

export interface JudgeVote {
  disputeId: string
  round: number
  vote: VoteChoice
  rationale: string | null
  votedAt: string | null
  tallies: AdjudicationSnapshot['tallies']
}
