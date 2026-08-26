export type AiProviderCapability = 'text' | 'image' | 'image_generation' | 'video_generation'

export interface AiTaskContext {
  runId: string
  capability: string
  provider: string
  model: string | null
  resolutionType: 'PLATFORM' | 'BYOK'
  priceTableVersion: string
  platformModelVersion: number | null
  fallbackAuthorized: boolean
  contextSnapshotId: string | null
  /** 平台凭据版本快照（任务书 #47 D7）；BYOK / env 兜底 run 为 null。 */
  credentialVersion: number | null
  startedAt: string
}

export interface AiRun {
  runId: string
  capability: string
  provider: string
  model: string | null
  status: 'running' | 'completed' | 'failed' | 'cancelled'
  actualCents: number | null
  startedAt: string
  completedAt: string | null
  taskContext: AiTaskContext
  content: string | null
  inputTokens: number | null
  outputTokens: number | null
}

export interface AiProviderKey {
  id: string
  organizationId: string | null
  capability: AiProviderCapability
  provider: string
  baseUrl: string
  model: string | null
  maskedHint: string
  enabled: boolean
  createdAt: string | null
  updatedAt: string | null
}

export interface CreateAiProviderKeyInput {
  capability: AiProviderCapability
  provider: string
  baseUrl: string
  model?: string
  apiKey: string
}

export interface UpdateAiProviderKeyInput {
  baseUrl: string
  model?: string
}

/** 组织 BYOK 回退策略（ADR-D17 / D-11）：无行=默认不允许。 */
export interface AiOrgByokPolicyState {
  configured: boolean
  allowPlatformFallback: boolean
  version: number
  updatedAt: string | null
}

export type PlatformModelRole = 'primary' | 'backup'
export type PlatformModelHealth = 'healthy' | 'degraded' | 'unhealthy'

/**
 * 真正经控制面解析的能力全集（与后端 `CreatePlatformModelRequest` 的 capability 正则逐值一致）。
 *
 * `image_generation` / `video_generation` 刻意不在其中——它们走 `preparePlatformAsyncExecution`
 * 的专用 adapter 配置，在平台模型表里建行不会被任何执行路径读取（旧表单是自由文本框，
 * 填这两个值或拼错成 `txet` 都能建出永不生效的死配置）。
 */
export const PLATFORM_CAPABILITIES = ['text', 'voice', 'retrieval', 'image_edit', 'content_safety'] as const

export type PlatformCapability = (typeof PLATFORM_CAPABILITIES)[number]

export interface PlatformModelConfig {
  id: string
  capability: string
  modelRole: PlatformModelRole
  provider: string
  model: string
  baseUrl: string
  maxConcurrency: number | null
  healthStatus: PlatformModelHealth
  enabled: boolean
  version: number
  createdAt: string
  updatedAt: string
}

/**
 * 建平台模型配置。凭据两种给法（后端 `resolveDestination` 归一）：
 *
 * - 推荐 `credentialId`：provider/baseUrl 留空，由该凭据带出。治理台表单走这条——
 *   凭据才是地址与密钥的真相源（运行时是 `COALESCE(credential.base_url, config.base_url)`）。
 * - 兼容 `provider` + `baseUrl`：后端按 (provider, baseUrl) 反查凭据，查不到会**隐式新建**一条
 *   无密钥凭据，容易积出空壳行。
 *
 * 两者都不给 → 400。同时给 → 以 `credentialId` 为准。
 */
export interface CreatePlatformModelInput {
  capability: PlatformCapability
  modelRole: PlatformModelRole
  credentialId?: string
  provider?: string
  model: string
  baseUrl?: string
  maxConcurrency?: number
  healthStatus?: PlatformModelHealth
}

export type UpdatePlatformModelInput = Omit<CreatePlatformModelInput, 'capability' | 'modelRole'>

/**
 * 平台通用凭据（任务书 #47 S1）。provider + baseUrl + 密钥同行，模型配置经 credentialId 引用。
 *
 * 后端只回掩码：`hasKey` 表示是否自带密钥（false = sandbox 或走 env 兜底），
 * `maskedHint` 形如 `sk-****cdef`。密文与明文都不下发。
 */
export interface PlatformProviderCredential {
  id: string
  name: string
  provider: string
  baseUrl: string
  hasKey: boolean
  maskedHint: string | null
  enabled: boolean
  version: number
  createdAt: string | null
  updatedAt: string | null
}

export interface CreatePlatformCredentialInput {
  name: string
  provider: string
  baseUrl: string
  /** 可空：sandbox provider 无需密钥，也允许先建无密钥凭据回落 env 兜底。 */
  apiKey?: string
}

export type UpdatePlatformCredentialInput = Omit<CreatePlatformCredentialInput, 'apiKey'>

/**
 * 一个上游模型。两处共用同一形状：
 * - `GET {credential}/models` —— 实时问上游「这把 key 能用什么」（要触网，可能失败）
 * - `GET/PUT {credential}/selected-models` —— admin 勾选的子集（落库，平台模型表单读这个）
 */
export interface UpstreamModel {
  id: string
  ownedBy?: string
}

/**
 * 个人 BYOK 开关（任务书 #47 D11–D14）。按 capability 一项。
 *
 * `configured=false` 表示走「无行即 on」的默认（D14），前端据此区分「未配置」与「显式设为 true」；
 * 保存时把 `version` 原样回传作 `expectedVersion`。
 */
export interface AiProviderPreference {
  capability: AiProviderCapability
  useOwnKey: boolean
  configured: boolean
  version: number
  updatedAt: string | null
}

/** 计费主体（D21 常驻显示）：用户必须一眼看出「我现在在用谁的模型、谁付钱」。 */
export type AiBillingSubject = 'own-key' | 'organization' | 'platform'
