// ---------- trust：审判官池 + 投票（+ 任务书 #74 卡 E 考试/见习/考核）----------

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
  /** 任务书 #74 卡 E：准入考试 / 见习标识 / 挂起状态（审判台展示）。 */
  admissionLevel?: 'full' | 'probation'
  probation?: boolean
  examPassedAt?: string | null
  suspendedNow?: boolean
  suspendedUntil?: string | null
  createdAt: string | null
}

/** 卡 E：准入审计动作（考试及格=probation、转正=promoted、挂起/恢复）。 */
export interface JudgeAdmissionAudit {
  id: number
  action: 'granted' | 'revoked' | 'probation' | 'promoted' | 'suspended' | 'reinstated'
  actorAccountId: string
  reason: string
  previousVersion: number
  newVersion: number
  createdAt: string | null
}

export interface AdminJudge extends Judge {
  audit?: JudgeAdmissionAudit[]
  probationSince?: string | null
  suspensionReason?: string | null
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

// ---------- 任务书 #74 卡 G：脱敏判例库 ----------

/** 判例（构造性脱敏：无 org/account/金额字段）。 */
export interface PrecedentCase {
  id: string
  disputeId: string
  taskType: string | null
  taskPlatform: string | null
  disputeKind: string | null
  /** 争议焦点（kind+platform+reason 前 80 字）。 */
  focus: string | null
  /** 双方主张摘要（脱敏证据元信息拼接）。 */
  claimsSummary: string | null
  decision: string | null
  /** 终局经由：panel=面板多数 / cs=客服终裁 / retrial=发回重审后再终局。 */
  finalVia: 'panel' | 'cs' | 'retrial' | string
  /** 各轮投票分布 JSON 字符串：[{forMerchant, forRecommender, abstain, matchedPlatformCount}]。 */
  voteSummary: string | null
  /** 终局轮每票理由摘要数组 JSON 字符串（不含审判官账号）。 */
  rationaleDigest: string | null
  createdAt: string | null
}

// ---------- 任务书 #74 卡 E：准入考试 ----------

/** 考试题（用户端出题响应不含 answerIndex）。 */
export interface JudgeExamQuestion {
  id: string
  category: string
  question: string
  options: string[]
  answerIndex?: number
  active?: boolean
  version?: number
  createdAt?: string | null
}

export interface JudgeExamAttempt {
  id: string
  accountId: string
  score: number
  passed: boolean
  answers: string | null
  createdAt: string | null
}

/** 考核看板行（90 天窗口实时聚合）。 */
export interface JudgeAssessmentRow {
  accountId: string
  assigned: number
  voted: number
  abstained: number
  abstainRate: number
  /** 弃权率 >40% 且分配 ≥5 次 → 建议暂停（运营确认制）。 */
  suggestSuspension: boolean
}
