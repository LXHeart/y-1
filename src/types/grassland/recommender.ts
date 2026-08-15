// ---------- 推荐官画像（identity）+ 声誉（marketplace）----------

/**
 * 自报的社交账号（PRD 六「社交平台」）。
 *
 * ⚠️ `followers` 是**推荐官自己填的**，平台没有核验过——UI 必须标明「自报」，
 * 否则商家会当成平台数据来决策。真核验属 PRD 九自动核实引擎，未做。
 */
export interface SocialAccount {
  platform: string
  handle: string | null
  followers: number | null
}

/**
 * 推荐官画像（identity 域）。没填过资料时后端返回**空画像而非 404**——
 * 「这人没填」本身就是商家要的事实。
 */
export interface RecommenderProfile {
  accountId: string
  displayName: string | null
  bio: string | null
  contentTags: string[]
  domainTags: string[]
  socialAccounts: SocialAccount[]
  updatedAt: string | null
}

/** PUT 整份覆盖：数组给空数组即清空；标签与社交账号收的是**数组**而非逗号串。 */
export interface UpdateRecommenderProfileInput {
  displayName?: string
  bio?: string
  contentTags: string[]
  domainTags: string[]
  socialAccounts: SocialAccount[]
}

/** 等级（PRD 五）。Lv5 是邀请制，后端策略永不自动授予。 */
export type RecommenderLevel = 'Lv1' | 'Lv2' | 'Lv3' | 'Lv4' | 'Lv5'

/**
 * 声誉指标（PRD 六「数据面板」，marketplace 从撮合事实实时派生）。
 *
 * ⚠️ `averageScore` / `averageResponseSeconds` **可能为 null**——分别表示「还没人评过」
 * 与「还没有接单→提交的样本」。不能显示成 0，那会被读成「评分极低 / 秒回」。
 * PRD 六的「平均曝光数据」后端明确不做（需平台数据采集）。
 */
export interface RecommenderReputation {
  accountId: string
  level: RecommenderLevel
  levelTitle: string
  calculatedLevel: RecommenderLevel
  effectiveLevel: RecommenderLevel
  levelNumber: number
  judgeEligible: boolean
  policyVersion: number
  taskPriorityWeight: number
  settlementDelayDays: number
  commissionBonusBps: number
  aiQuotaMultiplierBps: number
  premiumSupport: boolean
  benefits: string[]
  acceptedCount: number
  completedCount: number
  /** 0–1 的小数（完成/已接单）；无接单时为 0。 */
  completionRate: number
  ratingCount: number
  averageScore: number | null
  averageResponseSeconds: number | null
  lastActiveAt: string | null
  inactiveDowngraded: boolean
}

/** 平台管理员维护的单级门槛与结构化权益。比例/倍率均使用服务端原始口径。 */
export interface ReputationLevelRule {
  levelNumber: number
  level: RecommenderLevel
  title: string
  minCompleted: number
  minCompletionRate: number
  minAverageScore: number | null
  inviteOnly: boolean
  judgeEligible: boolean
  taskPriorityWeight: number
  settlementDelayDays: number
  commissionBonusBps: number
  aiQuotaMultiplierBps: number
  premiumSupport: boolean
  benefits: string[]
}

export interface ReputationPolicy {
  version: number
  updatedAt: string | null
  levels: ReputationLevelRule[]
}

export interface UpdateReputationPolicyInput {
  expectedVersion: number
  levels: ReputationLevelRule[]
}

/** 管理端声誉快照额外暴露 Lv5 邀请状态和完整终态计数。 */
export interface AdminReputation extends RecommenderReputation {
  merchantCancelledCount: number
  rejectedCount: number
  withdrawnCount: number
  terminalCount: number
  lv5Admitted: boolean
  admissionVersion: number
  admissionUpdatedBy: string | null
  admissionNote: string | null
  admissionUpdatedAt: string | null
}

export interface Lv5Admission {
  accountId: string
  admitted: boolean
  version: number
  updatedBy: string | null
  note: string | null
  updatedAt: string | null
}

export interface UpdateLv5AdmissionInput {
  admitted: boolean
  expectedVersion: number
  note?: string
}

// ---------- 确定性推荐匹配（marketplace）----------

export interface MatchDimension {
  key: 'platformFit' | 'level' | 'completionRate' | 'averageRating' | 'responseSpeed' | 'recentActivity'
  label: string
  score: number
  maxScore: number
  evidence: Record<string, string | number | boolean | null>
  reason: string
}

export interface TaskRecommenderInvitation {
  id: string
  taskId: string
  recommenderAccountId: string
  scoringVersion: string
  createdAt: string
  appliedAt: string | null
  created?: boolean
}

export interface RecommenderMatch {
  accountId: string
  totalScore: number
  level: RecommenderLevel
  reputationPolicyVersion: number
  computedAt: string
  dimensions: MatchDimension[]
  reasons: string[]
  invitation: TaskRecommenderInvitation | null
}

export interface RecommenderRecommendationPage {
  scoringVersion: string
  computedAt: string
  eligibleCount: number
  items: RecommenderMatch[]
}
