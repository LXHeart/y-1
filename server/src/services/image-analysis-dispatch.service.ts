import { logger } from '../lib/logger.js'
import { env } from '../lib/env.js'
import { AppError } from '../lib/errors.js'
import { resolveProviderBaseUrlAtRuntime } from '../lib/provider-url.js'
import { getProvider } from './providers/index.js'
import { draftStep, optimizeStep, styleRefineStep } from './providers/qwen-provider.js'
import { loadSettings, loadSettingsForUser } from './analysis-settings.service.js'
import { buildStylePreferenceAppendix, loadImageReviewStylePreferences, optimizeStylePreferences } from './image-review-style.service.js'
import type { ImageReviewGenerationInput, ProviderImageInput } from '../schemas/image-analysis.js'
import type { ImageAnalysisProgressEvent, ImageAnalysisResult, ProviderCallOptions, ResolvedProviderConfig } from './providers/types.js'

function resolveRequestConfigValue(value: string | undefined): string | undefined {
  if (typeof value !== 'string') {
    return undefined
  }

  const trimmedValue = value.trim()
  return trimmedValue || undefined
}

const IMAGE_PROVIDER_URL_MESSAGES = {
  invalid: '图片分析服务地址无效',
  protocol: '图片分析服务地址必须使用 HTTP 或 HTTPS',
  credentials: '图片分析服务地址不能包含用户名或密码',
  privateHost: '图片分析服务地址不能指向本地或私有网络地址',
  dnsLookupFailed: '图片分析服务地址域名解析失败，请检查后重试',
} as const

export async function resolveQwenConfig(userId?: string): Promise<{ baseUrl: string; apiKey?: string; model?: string; dispatcher?: import('undici').Dispatcher }> {
  const settings = userId ? await loadSettingsForUser(userId) : loadSettings()
  const featureSettings = settings.features.image

  const baseUrl = resolveRequestConfigValue(featureSettings.baseUrl) ?? resolveRequestConfigValue(env.QWEN_ANALYSIS_BASE_URL)
  const apiKey = resolveRequestConfigValue(featureSettings.apiKey) ?? resolveRequestConfigValue(env.QWEN_ANALYSIS_API_KEY)
  const model = resolveRequestConfigValue(featureSettings.model)
    ?? resolveRequestConfigValue(env.QWEN_ANALYSIS_MODEL)
    ?? 'qwen3.5-flash'

  if (!baseUrl) {
    throw new AppError('未配置图片分析服务地址，请先在分析设置中配置图片分析模型服务', 500)
  }

  const resolvedBaseUrl = await resolveProviderBaseUrlAtRuntime(baseUrl, IMAGE_PROVIDER_URL_MESSAGES)

  return {
    baseUrl: resolvedBaseUrl.baseUrl,
    apiKey,
    model,
    dispatcher: resolvedBaseUrl.dispatcher,
  }
}

interface AnalyzeImageContentOptions {
  signal?: AbortSignal
  userId?: string
  onProgress?: (event: ImageAnalysisProgressEvent) => void
}

export async function analyzeImageContent(
  images: ProviderImageInput[],
  promptInput: ImageReviewGenerationInput,
  options: AnalyzeImageContentOptions = {},
): Promise<ImageAnalysisResult> {
  logger.info({
    provider: 'qwen',
    imageCount: images.length,
    reviewLength: promptInput.reviewLength,
    hasFeelings: Boolean(promptInput.feelings),
  }, 'Dispatching image review generation to provider')

  const provider = getProvider('qwen')
  if (!provider.analyzeImages) {
    throw new AppError('当前图片分析服务不可用', 500)
  }

  const config = await resolveQwenConfig(options.userId)

  if (options.userId) {
    const preferences = await loadImageReviewStylePreferences(options.userId)
    if (preferences.length > 0) {
      promptInput = { ...promptInput, stylePreferences: buildStylePreferenceAppendix(preferences) }
      logger.info({ userId: options.userId, preferenceCount: preferences.length }, 'Injecting user style preferences into image review prompt')
    }
  }

  return provider.analyzeImages(images, promptInput, config, {
    signal: options.signal,
    timeoutMs: env.VIDEO_ANALYSIS_API_TIMEOUT_MS,
    ...(options.onProgress ? { onProgress: options.onProgress } : {}),
  })
}

async function resolveStepConfig(userId?: string): Promise<{ config: ResolvedProviderConfig; promptInput: ImageReviewGenerationInput }> {
  const config = await resolveQwenConfig(userId)
  return { config, promptInput: {} as ImageReviewGenerationInput }
}

async function injectPreferences(promptInput: ImageReviewGenerationInput, userId?: string): Promise<ImageReviewGenerationInput> {
  if (!userId) return promptInput
  const preferences = await loadImageReviewStylePreferences(userId)
  if (preferences.length === 0) return promptInput
  logger.info({ userId, preferenceCount: preferences.length }, 'Injecting user style preferences into image review step')
  return { ...promptInput, stylePreferences: buildStylePreferenceAppendix(preferences) }
}

function stepCallOptions(options: AnalyzeImageContentOptions = {}): ProviderCallOptions {
  return {
    signal: options.signal,
    timeoutMs: env.VIDEO_ANALYSIS_API_TIMEOUT_MS,
    ...(options.onProgress ? { onProgress: options.onProgress } : {}),
  }
}

export async function draftImageReview(
  images: ProviderImageInput[],
  promptInput: ImageReviewGenerationInput,
  options: AnalyzeImageContentOptions = {},
): Promise<ImageAnalysisResult> {
  logger.info({ imageCount: images.length, reviewLength: promptInput.reviewLength }, 'Running draft step')
  const config = await resolveQwenConfig(options.userId)
  const enrichedInput = await injectPreferences(promptInput, options.userId)
  return draftStep(images, enrichedInput, config, stepCallOptions(options))
}

export async function optimizeImageReview(
  previousReview: string,
  promptInput: ImageReviewGenerationInput,
  options: AnalyzeImageContentOptions = {},
): Promise<ImageAnalysisResult> {
  logger.info({ reviewLength: promptInput.reviewLength }, 'Running optimize step')
  const config = await resolveQwenConfig(options.userId)
  const enrichedInput = await injectPreferences(promptInput, options.userId)
  return optimizeStep(previousReview, enrichedInput, config, stepCallOptions(options))
}

export async function styleRefineImageReview(
  previousReview: string,
  promptInput: ImageReviewGenerationInput,
  options: AnalyzeImageContentOptions = {},
): Promise<ImageAnalysisResult> {
  logger.info({ reviewLength: promptInput.reviewLength }, 'Running style-refine step')
  const config = await resolveQwenConfig(options.userId)
  const enrichedInput = await injectPreferences(promptInput, options.userId)
  return styleRefineStep(previousReview, enrichedInput, config, stepCallOptions(options))
}

export async function optimizeImageReviewStylePreferences(
  preferences: string[],
  userId?: string,
): Promise<string[]> {
  const config = await resolveQwenConfig(userId)
  return optimizeStylePreferences(preferences, config)
}
