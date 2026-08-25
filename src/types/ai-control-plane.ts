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

export interface CreatePlatformModelInput {
  capability: string
  modelRole: PlatformModelRole
  provider: string
  model: string
  baseUrl: string
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
