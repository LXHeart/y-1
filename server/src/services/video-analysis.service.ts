import { logger } from '../lib/logger.js'
import { env } from '../lib/env.js'
import { AppError } from '../lib/errors.js'
import { resolveProviderBaseUrlAtRuntime } from '../lib/provider-url.js'
import type { AnalysisProvider } from '../schemas/settings.js'
import { getProvider } from './providers/index.js'
import { loadSettings, loadSettingsForUser } from './analysis-settings.service.js'
import type { VideoAnalysisResult, VideoRecreationResult } from './providers/types.js'
import { legacyResultToRecreationResult } from './providers/types.js'

export type { VideoAnalysisResult, VideoRecreationResult } from './providers/types.js'

export interface VideoAnalysisRequestConfig {
  provider?: AnalysisProvider
  baseUrl?: string
  apiToken?: string
  apiKey?: string
  model?: string
}

interface AnalyzeVideoContentOptions {
  signal?: AbortSignal
  analysisConfig?: VideoAnalysisRequestConfig
  userId?: string
}

function resolveRequestConfigValue(value: string | undefined): string | undefined {
  if (typeof value !== 'string') {
    return undefined
  }

  const trimmedValue = value.trim()
  return trimmedValue || undefined
}

function getEnvDefaultsForProvider(providerId: AnalysisProvider): { baseUrl?: string; apiKey?: string; model?: string } {
  if (providerId === 'qwen') {
    return {
      baseUrl: resolveRequestConfigValue(env.QWEN_ANALYSIS_BASE_URL),
      apiKey: resolveRequestConfigValue(env.QWEN_ANALYSIS_API_KEY),
      model: resolveRequestConfigValue(env.QWEN_ANALYSIS_MODEL) ?? 'qwen3.5-flash',
    }
  }

  return {
    baseUrl: resolveRequestConfigValue(env.COZE_ANALYSIS_BASE_URL) ?? resolveRequestConfigValue(env.VIDEO_ANALYSIS_API_BASE_URL),
    apiKey: resolveRequestConfigValue(env.COZE_ANALYSIS_API_TOKEN) ?? resolveRequestConfigValue(env.VIDEO_ANALYSIS_API_TOKEN),
  }
}

const VIDEO_PROVIDER_URL_MESSAGES = {
  invalid: '视频分析服务地址无效',
  protocol: '视频分析服务地址必须使用 HTTP 或 HTTPS',
  credentials: '视频分析服务地址不能包含用户名或密码',
  privateHost: '视频分析服务地址不能指向本地或私有网络地址',
  dnsLookupFailed: '视频分析服务地址域名解析失败，请检查后重试',
} as const

function getFeatureProviderBaseUrlMessages(feature: 'image' | 'article' | 'imageGeneration'): {
  invalid: string
  protocol: string
  credentials: string
  privateHost: string
} {
  if (feature === 'image') {
    return {
      invalid: '图片分析服务地址无效',
      protocol: '图片分析服务地址必须使用 HTTP 或 HTTPS',
      credentials: '图片分析服务地址不能包含用户名或密码',
      privateHost: '图片分析服务地址不能指向本地或私有网络地址',
    }
  }

  if (feature === 'imageGeneration') {
    return {
      invalid: '图片生成服务地址无效',
      protocol: '图片生成服务地址必须使用 HTTP 或 HTTPS',
      credentials: '图片生成服务地址不能包含用户名或密码',
      privateHost: '图片生成服务地址不能指向本地或私有网络地址',
    }
  }

  return {
    invalid: '文章生成服务地址无效',
    protocol: '文章生成服务地址必须使用 HTTP 或 HTTPS',
    credentials: '文章生成服务地址不能包含用户名或密码',
    privateHost: '文章生成服务地址不能指向本地或私有网络地址',
  }
}

export async function resolveProviderConfig(
  providerId: AnalysisProvider,
  requestConfig: VideoAnalysisRequestConfig | undefined,
  videoSettings = loadSettings().features.video,
): Promise<{ baseUrl: string; apiKey?: string; model?: string; dispatcher?: import('undici').Dispatcher }> {
  const envDefaults = getEnvDefaultsForProvider(providerId)

  const fromSettings = providerId === 'coze'
    ? {
        baseUrl: videoSettings.baseUrl,
        apiKey: videoSettings.apiToken,
        model: undefined,
      }
    : {
        baseUrl: videoSettings.baseUrl,
        apiKey: videoSettings.apiKey,
        model: videoSettings.model,
      }

  const baseUrl = resolveRequestConfigValue(requestConfig?.baseUrl)
    ?? resolveRequestConfigValue(fromSettings.baseUrl)
    ?? envDefaults.baseUrl

  const apiKey = resolveRequestConfigValue(requestConfig?.apiKey ?? requestConfig?.apiToken)
    ?? resolveRequestConfigValue(fromSettings.apiKey)
    ?? envDefaults.apiKey

  const model = resolveRequestConfigValue(requestConfig?.model)
    ?? resolveRequestConfigValue(fromSettings.model)
    ?? envDefaults.model

  if (!baseUrl) {
    throw new AppError('未配置视频分析服务地址', 500)
  }

  const resolvedBaseUrl = await resolveProviderBaseUrlAtRuntime(baseUrl, VIDEO_PROVIDER_URL_MESSAGES)

  return {
    baseUrl: resolvedBaseUrl.baseUrl,
    apiKey,
    model,
    dispatcher: resolvedBaseUrl.dispatcher,
  }
}

export async function resolveFeatureProviderConfig(
  feature: 'video' | 'image' | 'article' | 'imageGeneration',
  providerId: AnalysisProvider,
  userId?: string,
  options?: { requireModel?: boolean },
): Promise<{ baseUrl: string; apiKey?: string; model?: string; dispatcher?: import('undici').Dispatcher }> {
  const settings = userId ? await loadSettingsForUser(userId) : loadSettings()

  if (feature === 'video') {
    return resolveProviderConfig(providerId, undefined, settings.features.video)
  }
  const featureSettings = settings.features[feature] ?? {}
  const envBaseUrl = feature === 'imageGeneration'
    ? resolveRequestConfigValue(env.IMAGE_GENERATION_BASE_URL)
    : resolveRequestConfigValue(env.QWEN_ANALYSIS_BASE_URL)
  const envApiKey = feature === 'imageGeneration'
    ? resolveRequestConfigValue(env.IMAGE_GENERATION_API_KEY)
    : resolveRequestConfigValue(env.QWEN_ANALYSIS_API_KEY)
  const envModel = feature === 'imageGeneration'
    ? resolveRequestConfigValue(env.IMAGE_GENERATION_MODEL)
    : resolveRequestConfigValue(env.QWEN_ANALYSIS_MODEL)
  const baseUrl = resolveRequestConfigValue(featureSettings.baseUrl) ?? envBaseUrl
  const apiKey = resolveRequestConfigValue(featureSettings.apiKey) ?? envApiKey
  const model = resolveRequestConfigValue(featureSettings.model)
    ?? envModel
    ?? (feature === 'imageGeneration' ? undefined : 'qwen3.5-flash')

  if (!baseUrl) {
    throw new AppError(
      feature === 'image'
        ? '未配置图片分析服务地址，请先在分析设置中配置图片分析模型服务'
        : feature === 'article'
          ? '未配置文章生成服务地址，请先在分析设置中配置文章模型服务'
          : '未配置图片生成服务地址，请先在分析设置中配置图片生成模型服务',
      400,
    )
  }

  if (!model && feature === 'imageGeneration' && options?.requireModel !== false) {
    throw new AppError('未配置图片生成模型，请先在分析设置中配置图片生成模型服务', 400)
  }

  const resolvedBaseUrl = await resolveProviderBaseUrlAtRuntime(baseUrl, getFeatureProviderBaseUrlMessages(feature))

  return {
    baseUrl: resolvedBaseUrl.baseUrl,
    apiKey,
    model,
    dispatcher: resolvedBaseUrl.dispatcher,
  }
}

export async function analyzeVideoContent(
  videoUrl: string,
  options: AnalyzeVideoContentOptions = {},
): Promise<VideoAnalysisResult> {
  const settings = options.userId ? await loadSettingsForUser(options.userId) : loadSettings()
  const providerId = options.analysisConfig?.provider ?? settings.features.video.provider

  logger.info({
    provider: providerId,
    videoUrlType: videoUrl.includes('/api/bilibili/') ? 'bilibili' : 'douyin',
  }, 'Dispatching video analysis to provider')

  const provider = getProvider(providerId)
  const config = await resolveProviderConfig(providerId, options.analysisConfig, settings.features.video)

  return provider.analyze(videoUrl, config, {
    signal: options.signal,
    timeoutMs: env.VIDEO_ANALYSIS_API_TIMEOUT_MS,
  })
}

export async function analyzeVideoForRecreation(
  videoUrl: string,
  options: AnalyzeVideoContentOptions = {},
): Promise<VideoRecreationResult> {
  const settings = options.userId ? await loadSettingsForUser(options.userId) : loadSettings()
  const providerId = options.analysisConfig?.provider ?? settings.features.video.provider

  logger.info({
    provider: providerId,
    videoUrlType: videoUrl.includes('/api/bilibili/') ? 'bilibili' : 'douyin',
  }, 'Dispatching video recreation analysis to provider')

  const provider = getProvider(providerId)
  const config = await resolveProviderConfig(providerId, options.analysisConfig, settings.features.video)
  const callOptions = {
    signal: options.signal,
    timeoutMs: env.VIDEO_ANALYSIS_API_TIMEOUT_MS,
  }

  if (provider.analyzeForRecreation) {
    return provider.analyzeForRecreation(videoUrl, config, callOptions)
  }

  const legacyResult = await provider.analyze(videoUrl, config, callOptions)
  return legacyResultToRecreationResult(legacyResult)
}
