import { z } from 'zod'
import { isSafeProviderBaseUrl } from '../lib/provider-url.js'

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

export interface UpdateAnalysisSettingsRequest {
  features?: {
    video?: {
      provider?: AnalysisProvider
      baseUrl?: string
      apiToken?: string
      apiKey?: string
      model?: string
    }
    image?: {
      baseUrl?: string
      apiKey?: string
      model?: string
    }
    article?: {
      baseUrl?: string
      apiKey?: string
      model?: string
    }
    imageGeneration?: {
      baseUrl?: string
      apiKey?: string
      model?: string
    }
  }
  integrations?: {
    feishu?: {
      appId?: string
      appSecret?: string
      folderToken?: string
    }
  }
}

export function normalizeOptionalTrimmedString(value: string | undefined): string | undefined {
  if (typeof value !== 'string') {
    return undefined
  }

  const trimmedValue = value.trim()
  return trimmedValue || undefined
}

const URL_ERROR_MESSAGE = '分析服务地址必须是有效的 HTTP(S) URL，且不能包含用户名或密码，也不能指向本地或私有网络地址'

function isSafeHttpUrl(value: string): boolean {
  return isSafeProviderBaseUrl(value)
}

const optionalHttpUrlSchema = z.string().optional().transform(normalizeOptionalTrimmedString).refine((value) => {
  return !value || isSafeHttpUrl(value)
}, URL_ERROR_MESSAGE)

const optionalSecretSchema = z.string().optional().transform(normalizeOptionalTrimmedString)
const updateOptionalSecretSchema = z.string().optional().transform((value) => {
  if (typeof value !== 'string') {
    return undefined
  }

  return value.trim()
})

const optionalModelSchema = z.string().optional().transform(normalizeOptionalTrimmedString)

const feishuIntegrationSchema = z.object({
  appId: optionalSecretSchema,
  appSecret: optionalSecretSchema,
  folderToken: optionalSecretSchema,
}).default({ appId: undefined, appSecret: undefined, folderToken: undefined })

const updateFeishuIntegrationSchema = z.object({
  appId: updateOptionalSecretSchema,
  appSecret: updateOptionalSecretSchema,
  folderToken: updateOptionalSecretSchema,
}).optional()

export const videoFeatureSettingsSchema = z.object({
  provider: z.enum(['coze', 'qwen']).default('coze'),
  baseUrl: optionalHttpUrlSchema,
  apiToken: optionalSecretSchema,
  apiKey: optionalSecretSchema,
  model: optionalModelSchema,
})

export const qwenFeatureSettingsSchema = z.object({
  baseUrl: optionalHttpUrlSchema,
  apiKey: optionalSecretSchema,
  model: optionalModelSchema,
})

const updateVideoFeatureSettingsSchema = z.object({
  provider: z.enum(['coze', 'qwen']).optional(),
  baseUrl: optionalHttpUrlSchema,
  apiToken: updateOptionalSecretSchema,
  apiKey: updateOptionalSecretSchema,
  model: optionalModelSchema,
})

const updateQwenFeatureSettingsSchema = z.object({
  baseUrl: optionalHttpUrlSchema,
  apiKey: updateOptionalSecretSchema,
  model: optionalModelSchema,
})

export const analysisSettingsSchema = z.object({
  integrations: z.object({
    feishu: feishuIntegrationSchema,
  }).default({ feishu: { appId: undefined, appSecret: undefined, folderToken: undefined } }),
  features: z.object({
    video: videoFeatureSettingsSchema.default({
      provider: 'coze',
      baseUrl: undefined,
      apiToken: undefined,
      apiKey: undefined,
      model: undefined,
    }),
    image: qwenFeatureSettingsSchema.default({
      baseUrl: undefined,
      apiKey: undefined,
      model: undefined,
    }),
    article: qwenFeatureSettingsSchema.default({
      baseUrl: undefined,
      apiKey: undefined,
      model: undefined,
    }),
    imageGeneration: qwenFeatureSettingsSchema.default({
      baseUrl: undefined,
      apiKey: undefined,
      model: undefined,
    }),
  }).default({
    video: {
      provider: 'coze',
      baseUrl: undefined,
      apiToken: undefined,
      apiKey: undefined,
      model: undefined,
    },
    image: {
      baseUrl: undefined,
      apiKey: undefined,
      model: undefined,
    },
    article: {
      baseUrl: undefined,
      apiKey: undefined,
      model: undefined,
    },
    imageGeneration: {
      baseUrl: undefined,
      apiKey: undefined,
      model: undefined,
    },
  }),
})

export const updateAnalysisSettingsRequest = z.object({
  integrations: z.object({
    feishu: updateFeishuIntegrationSchema,
  }).default({}),
  features: z.object({
    video: updateVideoFeatureSettingsSchema.optional(),
    image: updateQwenFeatureSettingsSchema.optional(),
    article: updateQwenFeatureSettingsSchema.optional(),
    imageGeneration: updateQwenFeatureSettingsSchema.optional(),
  }).default({}),
})

export const listModelsRequestSchema = z.object({
  feature: z.enum(['video', 'image', 'article', 'imageGeneration']),
  provider: z.enum(['coze', 'qwen']).optional(),
})

export const verifyModelRequestSchema = z.object({
  feature: z.enum(['video', 'image', 'article', 'imageGeneration']),
  provider: z.enum(['coze', 'qwen']).optional(),
  model: z.string().trim().min(1, '模型名称不能为空'),
})

export type HotItemsProvider = '60s' | 'alapi'

export interface HomepageSettings {
  hotItems: {
    provider: HotItemsProvider
    alapiToken?: string
  }
}

export interface UpdateHomepageSettingsRequest {
  hotItems?: {
    provider?: HotItemsProvider
    alapiToken?: string
  }
}

export const homepageSettingsSchema = z.object({
  hotItems: z.object({
    provider: z.enum(['60s', 'alapi']).default('60s'),
    alapiToken: optionalSecretSchema,
  }).default({
    provider: '60s',
    alapiToken: undefined,
  }),
})

export const updateHomepageSettingsRequest = z.object({
  hotItems: z.object({
    provider: z.enum(['60s', 'alapi']).optional(),
    alapiToken: updateOptionalSecretSchema,
  }).optional(),
})
