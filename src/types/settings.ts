export type AnalysisProvider = 'coze' | 'qwen'
export type AnalysisFeature = 'video' | 'image' | 'article' | 'imageGeneration'

export interface FeishuIntegration {
  appId?: string
  appSecret?: string
  folderToken?: string
}

export interface AnalysisSettings {
  features: {
    video: {
      provider: AnalysisProvider
      baseUrl?: string
      apiToken?: string
      apiKey?: string
      model?: string
    }
    image: {
      baseUrl?: string
      apiKey?: string
      model?: string
    }
    article: {
      baseUrl?: string
      apiKey?: string
      model?: string
    }
    imageGeneration: {
      baseUrl?: string
      apiKey?: string
      model?: string
    }
  }
  integrations?: {
    feishu?: FeishuIntegration
  }
}

export interface AnalysisSettingsApiResponse {
  success: boolean
  data?: AnalysisSettings
  error?: string
}

export interface ModelInfo {
  id: string
  ownedBy?: string
}

export interface FeatureModelState {
  loading: boolean
  error: string
  availableModels: ModelInfo[]
  verifying: boolean
  verifyResult: 'idle' | 'success' | 'error'
  verifyError: string
}

export type FeatureModelStateMap = Record<AnalysisFeature, FeatureModelState>

export type HotItemsProvider = '60s' | 'alapi'

export interface HomepageSettings {
  hotItems: {
    provider: HotItemsProvider
    alapiToken?: string
  }
}

export interface HomepageSettingsApiResponse {
  success: boolean
  data?: HomepageSettings
  error?: string
}
