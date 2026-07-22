import { logger } from '../lib/logger.js'
import { env } from '../lib/env.js'
import { AppError } from '../lib/errors.js'
import { resolveProviderBaseUrlAtRuntime } from '../lib/provider-url.js'
import { providerFetch } from '../lib/fetch.js'
import { loadSettings, loadSettingsForUser } from './analysis-settings.service.js'

function resolveConfigValue(value: string | undefined): string | undefined {
  if (typeof value !== 'string') return undefined
  const trimmed = value.trim()
  return trimmed || undefined
}

const SCRIPT_PROVIDER_URL_MESSAGES = {
  invalid: '视频制作脚本服务地址无效',
  protocol: '视频制作脚本服务地址必须使用 HTTP 或 HTTPS',
  credentials: '视频制作脚本服务地址不能包含用户名或密码',
  privateHost: '视频制作脚本服务地址不能指向本地或私有网络地址',
  dnsLookupFailed: '视频制作脚本服务地址域名解析失败，请检查后重试',
} as const

interface ScriptConfig {
  baseUrl: string
  apiKey?: string
  model: string
  dispatcher?: import('undici').Dispatcher
}

async function resolveScriptConfig(userId?: string): Promise<ScriptConfig> {
  const settings = userId ? await loadSettingsForUser(userId) : loadSettings()
  const featureSettings = settings.features.videoProduction

  const baseUrl = resolveConfigValue(featureSettings.baseUrl) ?? resolveConfigValue(env.VIDEO_PRODUCTION_SCRIPT_BASE_URL)
  const apiKey = resolveConfigValue(featureSettings.apiKey) ?? resolveConfigValue(env.VIDEO_PRODUCTION_SCRIPT_API_KEY)
  const model = resolveConfigValue(featureSettings.model) ?? resolveConfigValue(env.VIDEO_PRODUCTION_SCRIPT_MODEL) ?? 'doubao-1.5-pro-256k-250115'

  if (!baseUrl) {
    throw new AppError('未配置视频制作脚本服务地址，请先在分析设置中配置', 400)
  }

  const resolved = await resolveProviderBaseUrlAtRuntime(baseUrl, SCRIPT_PROVIDER_URL_MESSAGES)

  return {
    baseUrl: resolved.baseUrl,
    apiKey,
    model,
    dispatcher: resolved.dispatcher,
  }
}

const SCRIPT_SYSTEM_PROMPT = `你是一位专业的短视频脚本策划师，专门为实体店铺制作推广视频脚本。

## 任务
根据用户提供的店铺信息和素材图片，生成一段适合短视频平台（15秒）的推广视频脚本。

## 输出要求
1. 脚本必须分为 3-5 个镜头
2. 每个镜头包含：画面描述、旁白/字幕文字、预估时长
3. 总时长控制在 15 秒以内
4. 语言简洁有力，突出店铺特色和吸引力
5. 适合 {videoStyle} 风格
6. 行业类型：{industryType}

## 输出格式
直接输出脚本内容，包含镜头描述和旁白文字。不需要 JSON 格式，纯文本即可。

示例格式：
【镜头1】(3秒) 画面：[描述画面内容]
旁白：[旁白文字]

【镜头2】(4秒) 画面：[描述画面内容]
旁白：[旁白文字]

…`

interface ScriptGenerationOptions {
  signal?: AbortSignal
  userId?: string
}

export async function* streamVideoScript(
  images: string[],
  shopName: string,
  industryType: string,
  videoStyle: string,
  shopAddress?: string,
  shopDescription?: string,
  customPrompt?: string,
  options: ScriptGenerationOptions = {},
): AsyncIterable<string> {
  const config = await resolveScriptConfig(options.userId)

  const systemPrompt = SCRIPT_SYSTEM_PROMPT
    .replace('{videoStyle}', videoStyle)
    .replace('{industryType}', industryType)

  const userParts: string[] = []
  userParts.push(`店铺名称：${shopName}`)
  if (shopAddress) userParts.push(`店铺地址：${shopAddress}`)
  if (shopDescription) userParts.push(`店铺描述：${shopDescription}`)
  if (customPrompt) userParts.push(`用户要求：${customPrompt}`)
  userParts.push(`\n请根据以上信息和 ${images.length} 张素材图片，生成推广视频脚本。`)

  const imageContent = images.map((img) => ({
    type: 'image_url' as const,
    image_url: { url: img.startsWith('data:') ? img : `data:image/jpeg;base64,${img}` },
  }))

  logger.info({ shopName, industryType, videoStyle, imageCount: images.length }, 'Streaming video production script')

  yield* requestTextChatStream(
    config,
    {
      model: config.model,
      messages: [
        { role: 'system', content: systemPrompt },
        {
          role: 'user',
          content: [
            { type: 'text', text: userParts.join('\n') },
            ...imageContent,
          ],
        },
      ],
    },
    { signal: options.signal, timeoutMs: 60_000 },
  )
}

interface TextChatRequest {
  model: string
  messages: Array<{
    role: string
    content: string | Array<{ type: string; text?: string; image_url?: { url: string } }>
  }>
}

interface StreamOptions {
  signal?: AbortSignal
  timeoutMs: number
}

async function* requestTextChatStream(
  config: ScriptConfig,
  requestBody: TextChatRequest,
  options: StreamOptions,
): AsyncGenerator<string> {
  const baseUrl = config.baseUrl.replace(/\/$/u, '')
  const endpoint = `${baseUrl}/chat/completions`
  const controller = new AbortController()
  let isClientDisconnected = false

  const timeout = setTimeout(() => controller.abort(), options.timeoutMs)

  const abortFromCaller = (): void => {
    isClientDisconnected = true
    controller.abort()
  }

  if (options.signal?.aborted) {
    abortFromCaller()
  } else {
    options.signal?.addEventListener('abort', abortFromCaller, { once: true })
  }

  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
  }
  if (config.apiKey) {
    headers.Authorization = `Bearer ${config.apiKey}`
  }

  try {
    const response = await providerFetch(endpoint, {
      method: 'POST',
      headers,
      body: JSON.stringify({ ...requestBody, stream: true, enable_thinking: false }),
      signal: controller.signal,
      dispatcher: config.dispatcher,
    })

    if (!response.ok) {
      const responseText = await response.text()
      throw new AppError(`脚本生成失败（状态码 ${response.status}）`, response.status >= 500 ? 502 : 400)
    }

    const reader = response.body?.getReader()
    if (!reader) {
      throw new AppError('脚本生成返回了空响应', 502)
    }

    const decoder = new TextDecoder()
    let buffer = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop() ?? ''

      for (const line of lines) {
        const trimmed = line.trim()
        if (!trimmed || !trimmed.startsWith('data: ')) continue

        const payload = trimmed.slice(6).trim()
        if (payload === '[DONE]') return

        try {
          const parsed = JSON.parse(payload) as Record<string, unknown>
          const choices = parsed.choices as Array<Record<string, unknown>> | undefined
          const delta = choices?.[0]?.delta as Record<string, unknown> | undefined
          const content = delta?.content
          if (typeof content === 'string' && content.length > 0) {
            yield content
          }
        } catch {
          // skip malformed SSE lines
        }
      }
    }
  } catch (error: unknown) {
    if (error instanceof AppError) throw error

    if (error instanceof DOMException && error.name === 'AbortError') {
      if (isClientDisconnected) return
      throw new AppError('脚本生成超时，请稍后重试', 504)
    }

    logger.error({ err: error }, 'Video production script stream error')
    throw new AppError('脚本生成失败，请稍后重试', 502)
  } finally {
    clearTimeout(timeout)
    options.signal?.removeEventListener('abort', abortFromCaller)
  }
}

export interface VideoGenerationResult {
  videoUrl: string
  taskId: string
}

export async function generateVideo(
  _script: string,
  _images: string[],
  _videoStyle: string,
  _shopName: string,
  _shopAddress?: string,
): Promise<VideoGenerationResult> {
  // Stub: Seedance API integration will be added later
  logger.info('Video generation called (stub)')

  return {
    videoUrl: '',
    taskId: `stub-${Date.now()}`,
  }
}
