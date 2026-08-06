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
