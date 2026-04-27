import { AppError } from '../lib/errors.js'
import { logger } from '../lib/logger.js'
import { env } from '../lib/env.js'
import { getProvider } from './providers/index.js'
import { loadSettings, loadSettingsForUser } from './analysis-settings.service.js'
import { resolveProviderConfig } from './video-analysis.service.js'
import type { AnalysisProvider } from '../schemas/settings.js'
import type { VideoAdaptationResult } from './providers/types.js'

interface ExtractedContentInput {
  videoCaptions?: string
  videoScript?: string
  charactersDescription?: string
  voiceDescription?: string
  propsDescription?: string
  sceneDescription?: string
}

interface AdaptVideoContentInput {
  platform: 'douyin' | 'bilibili'
  proxyVideoUrl: string
  extractedContent: ExtractedContentInput
  userId?: string
  signal?: AbortSignal
}

export async function adaptVideoContent(input: AdaptVideoContentInput): Promise<VideoAdaptationResult> {
  const settings = input.userId ? await loadSettingsForUser(input.userId) : loadSettings()
  const providerId = settings.features.video.provider as AnalysisProvider
  const provider = getProvider(providerId)

  if (!provider.adaptVideoContent) {
    throw new AppError('当前视频分析服务不支持内容改编，请切换到 Qwen 后重试', 400)
  }

  const config = await resolveProviderConfig(providerId, undefined, settings.features.video)

  logger.info({
    provider: providerId,
    platform: input.platform,
    hasCaptions: Boolean(input.extractedContent.videoCaptions),
    hasScript: Boolean(input.extractedContent.videoScript),
  }, 'Dispatching video adaptation to provider')

  return provider.adaptVideoContent({
    platform: input.platform,
    proxyVideoUrl: input.proxyVideoUrl,
    extractedContent: input.extractedContent,
  }, config, {
    signal: input.signal,
    timeoutMs: env.VIDEO_ANALYSIS_API_TIMEOUT_MS,
  })
}
