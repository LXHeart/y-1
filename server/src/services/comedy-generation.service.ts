import { logger } from '../lib/logger.js'
import { env } from '../lib/env.js'
import { AppError } from '../lib/errors.js'
import { resolveProviderBaseUrlAtRuntime } from '../lib/provider-url.js'
import { streamComedyScript } from './providers/index.js'
import { loadSettings, loadSettingsForUser } from './analysis-settings.service.js'

function resolveRequestConfigValue(value: string | undefined): string | undefined {
  if (typeof value !== 'string') return undefined
  const trimmedValue = value.trim()
  return trimmedValue || undefined
}

const COMEDY_PROVIDER_URL_MESSAGES = {
  invalid: '脱口秀创作服务地址无效',
  protocol: '脱口秀创作服务地址必须使用 HTTP 或 HTTPS',
  credentials: '脱口秀创作服务地址不能包含用户名或密码',
  privateHost: '脱口秀创作服务地址不能指向本地或私有网络地址',
  dnsLookupFailed: '脱口秀创作服务地址域名解析失败，请检查后重试',
} as const

async function resolveQwenConfig(userId?: string): Promise<{ baseUrl: string; apiKey?: string; model?: string; dispatcher?: import('undici').Dispatcher }> {
  const settings = userId ? await loadSettingsForUser(userId) : loadSettings()
  const featureSettings = settings.features.article

  const envBaseUrl = resolveRequestConfigValue(env.QWEN_ANALYSIS_BASE_URL)
  const envApiKey = resolveRequestConfigValue(env.QWEN_ANALYSIS_API_KEY)
  const envModel = resolveRequestConfigValue(env.QWEN_ANALYSIS_MODEL) ?? 'qwen3.5-flash'

  const baseUrl = resolveRequestConfigValue(featureSettings.baseUrl) ?? envBaseUrl
  const apiKey = resolveRequestConfigValue(featureSettings.apiKey) ?? envApiKey
  const model = resolveRequestConfigValue(featureSettings.model) ?? envModel

  if (!baseUrl) {
    throw new AppError('未配置 AI 服务地址，请先在分析设置中配置模型服务', 400)
  }

  const resolvedBaseUrl = await resolveProviderBaseUrlAtRuntime(baseUrl, COMEDY_PROVIDER_URL_MESSAGES)

  return {
    baseUrl: resolvedBaseUrl.baseUrl,
    apiKey,
    model,
    dispatcher: resolvedBaseUrl.dispatcher,
  }
}

interface ComedyGenerationOptions {
  signal?: AbortSignal
  userId?: string
}

export async function* streamComedy(
  topic: string,
  duration: number,
  options: ComedyGenerationOptions = {},
): AsyncIterable<string> {
  const config = await resolveQwenConfig(options.userId)

  logger.info({ topic, duration }, 'Streaming comedy script')

  yield* streamComedyScript(topic, duration, config, {
    signal: options.signal,
    timeoutMs: 60_000,
  })
}
