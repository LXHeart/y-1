import * as cheerio from 'cheerio'
import { randomUUID } from 'node:crypto'
import { mkdirSync, readdirSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { logger } from '../lib/logger.js'
import { AppError } from '../lib/errors.js'
import { providerFetch } from '../lib/fetch.js'
import type { ResolvedProviderConfig } from './providers/types.js'
import type { ProviderImageInput } from '../schemas/image-analysis.js'
import { resolveFeatureProviderConfig } from './video-analysis.service.js'
import type { ArticlePlatform } from '../schemas/article-creation.js'

const PROVIDER_FETCH_REDIRECT_POLICY: RequestRedirect = 'error'
const BING_IMAGE_SEARCH_URL = 'https://www.bing.com/images/search'
const DEFAULT_IMAGE_SEARCH_COUNT = 3
const ARTICLE_IMAGE_TIMEOUT_MS = 30_000
const IMAGE_SEARCH_TIMEOUT_MS = 15_000
const IMAGE_GENERATION_TIMEOUT_MS = 300_000
const REQUEST_ABORTED_MESSAGE = 'Request aborted'

const GENERATED_IMAGES_DIR = resolve(process.cwd(), 'server/.data/generated-images')
const GENERATED_IMAGE_TTL_MS = 30 * 60_000
const GENERATED_IMAGE_CLEANUP_INTERVAL_MS = 5 * 60_000

function persistBase64Image(b64: string): string {
  mkdirSync(GENERATED_IMAGES_DIR, { recursive: true })
  const id = randomUUID()
  const filePath = resolve(GENERATED_IMAGES_DIR, `${id}.png`)
  writeFileSync(filePath, Buffer.from(b64, 'base64'))
  return id
}

export function resolveGeneratedImagePath(id: string): string | undefined {
  const filePath = resolve(GENERATED_IMAGES_DIR, `${id}.png`)
  try {
    const stat = statSync(filePath)
    if (Date.now() - stat.mtimeMs > GENERATED_IMAGE_TTL_MS) {
      try { rmSync(filePath) } catch { /* best effort */ }
      return undefined
    }
    return filePath
  } catch {
    return undefined
  }
}

function cleanupExpiredGeneratedImages(): void {
  try {
    const entries = readdirSync(GENERATED_IMAGES_DIR)
    const now = Date.now()
    for (const entry of entries) {
      if (!entry.endsWith('.png')) continue
      const filePath = resolve(GENERATED_IMAGES_DIR, entry)
      try {
        const stat = statSync(filePath)
        if (now - stat.mtimeMs > GENERATED_IMAGE_TTL_MS) {
          rmSync(filePath)
        }
      } catch { /* best effort */ }
    }
  } catch { /* directory may not exist yet */ }
}

setInterval(cleanupExpiredGeneratedImages, GENERATED_IMAGE_CLEANUP_INTERVAL_MS).unref()

interface RecommendImagesInput {
  content: string
  outline?: string
  platform: ArticlePlatform
  userId?: string
  signal?: AbortSignal
}

interface SearchImagesInput {
  keywords: string
  count?: number
  userId?: string
  signal?: AbortSignal
}

interface GenerateImageInput {
  prompt: string
  size: '1024x1024' | '1024x1792' | '1792x1024'
  images?: ProviderImageInput[]
  userId?: string
  signal?: AbortSignal
}

export interface ArticleImagePlacement {
  position: string
  description: string
  searchKeywords: string
  prompt: string
}

export interface ArticleImageRecommendation {
  recommendedCount: number
  placements: ArticleImagePlacement[]
}

export interface ArticleImageSearchResult {
  url: string
  thumbnailUrl: string
  sourceUrl?: string
  description?: string
  width?: number
  height?: number
}

export interface GeneratedArticleImage {
  imageUrl: string
  revisedPrompt?: string
}

function stripMarkdownCodeFence(text: string): string {
  const match = text.match(/```(?:json)?\s*\n?([\s\S]*?)\n?\s*```/u)
  return match ? match[1] : text
}

function parseJsonContent(content: string): unknown {
  const stripped = stripMarkdownCodeFence(content).trim()

  try {
    return JSON.parse(stripped)
  } catch {
    throw new AppError('配图服务返回了无法解析的内容', 502)
  }
}

function extractContentFromChatCompletion(response: unknown): string {
  if (typeof response !== 'object' || response === null) {
    throw new AppError('配图服务返回了无效响应', 502)
  }

  const record = response as Record<string, unknown>
  const choices = record.choices

  if (!Array.isArray(choices) || choices.length === 0) {
    throw new AppError('配图服务返回了空结果', 502)
  }

  const firstChoice = choices[0] as Record<string, unknown> | undefined
  const message = firstChoice?.message as Record<string, unknown> | undefined

  if (typeof message?.content !== 'string' || !message.content.trim()) {
    throw new AppError('配图服务返回了空内容', 502)
  }

  return message.content
}

function readOptionalString(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value.trim() : undefined
}

function readOptionalNumber(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined
}

function readHttpsUrl(value: unknown): string | undefined {
  if (typeof value !== 'string') {
    return undefined
  }

  try {
    const parsedUrl = new URL(value.trim())
    return parsedUrl.protocol === 'https:' ? parsedUrl.toString() : undefined
  } catch {
    return undefined
  }
}

function normalizeRecommendation(value: unknown): ArticleImageRecommendation {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new AppError('配图推荐服务返回了无效数据', 502)
  }

  const record = value as Record<string, unknown>
  const rawPlacements = record.placements

  if (!Array.isArray(rawPlacements)) {
    throw new AppError('配图推荐服务返回了无效数据', 502)
  }

  const placements = rawPlacements
    .map((item) => {
      if (typeof item !== 'object' || item === null || Array.isArray(item)) {
        return null
      }

      const placement = item as Record<string, unknown>
      const position = readOptionalString(placement.position)
      const description = readOptionalString(placement.description)
      const searchKeywords = readOptionalString(placement.searchKeywords)
      const prompt = readOptionalString(placement.prompt)

      if (!position || !description || !searchKeywords || !prompt) {
        return null
      }

      return {
        position,
        description,
        searchKeywords,
        prompt,
      }
    })
    .filter((item): item is ArticleImagePlacement => item !== null)

  if (placements.length === 0) {
    throw new AppError('配图推荐服务返回了空结果', 502)
  }

  const recommendedCount = readOptionalNumber(record.recommendedCount) ?? placements.length

  return {
    recommendedCount: Math.max(placements.length, Math.min(10, Math.floor(recommendedCount))),
    placements,
  }
}

function normalizeGeneratedImage(value: unknown): GeneratedArticleImage {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new AppError('图片生成服务返回了无效数据', 502)
  }

  const record = value as Record<string, unknown>
  const rawData = record.data

  if (!Array.isArray(rawData) || rawData.length === 0) {
    throw new AppError('图片生成服务返回了空结果', 502)
  }

  const first = rawData[0]
  if (typeof first !== 'object' || first === null || Array.isArray(first)) {
    throw new AppError('图片生成服务返回了无效数据', 502)
  }

  const image = first as Record<string, unknown>
  const imageUrl = readHttpsUrl(image.url)
  const b64 = readOptionalString(image.b64_json)

  if (!imageUrl && !b64) {
    throw new AppError('图片生成服务返回了无效图片数据', 502)
  }

  if (imageUrl) {
    return {
      imageUrl,
      revisedPrompt: readOptionalString(image.revised_prompt),
    }
  }

  const imageId = persistBase64Image(b64!)
  return {
    imageUrl: `/api/article-generation/generated-images/${imageId}`,
    revisedPrompt: readOptionalString(image.revised_prompt),
  }
}

function buildRecommendationPrompt(input: RecommendImagesInput): string {
  const outlineSection = input.outline ? `\n文章大纲：\n${input.outline}` : ''
  const platformLabel = input.platform === 'wechat'
    ? '微信公众号'
    : input.platform === 'zhihu'
      ? '知乎'
      : '小红书'

  return `你是一位专业的中文内容编辑和视觉策划师。请根据文章内容，推荐最适合的一张封面配图。\n\n要求：\n- 结合平台：${platformLabel}\n- 只推荐 1 张图\n- position 填写图片位置，例如：开头\n- description 写图片要表达的视觉内容\n- searchKeywords 写中文搜图关键词，便于从网上搜图\n- prompt 写可直接用于 AI 生图的中文提示词\n- 只能返回 JSON，不要返回额外说明\n\n返回格式：\n{\n  \"recommendedCount\": 1,\n  \"placements\": [\n    {\n      \"position\": \"开头\",\n      \"description\": \"用于作为文章头图的概念图\",\n      \"searchKeywords\": \"职场沟通 商务 插画\",\n      \"prompt\": \"现代商务风格插画，展示高效沟通场景，蓝白色调\"\n    }\n  ]\n}\n\n文章正文：\n${input.content}${outlineSection}`
}

function buildSearchEndpoint(keywords: string): string {
  const url = new URL(BING_IMAGE_SEARCH_URL)
  url.searchParams.set('q', keywords)
  return url.toString()
}

function normalizeImageDimensions(element: ReturnType<ReturnType<typeof cheerio.load>>, attr: 'width' | 'height'): number | undefined {
  const rawValue = element.attr(attr)
  if (!rawValue) {
    return undefined
  }

  const parsedValue = Number.parseInt(rawValue, 10)
  return Number.isFinite(parsedValue) ? parsedValue : undefined
}

function pushImageSearchResult(
  results: ArticleImageSearchResult[],
  seenUrls: Set<string>,
  candidate: {
    url?: string
    thumbnailUrl?: string
    sourceUrl?: string
    description?: string
    width?: number
    height?: number
  },
): void {
  const url = readHttpsUrl(candidate.url)
  const thumbnailUrl = readHttpsUrl(candidate.thumbnailUrl) ?? url

  if (!url || !thumbnailUrl || seenUrls.has(url)) {
    return
  }

  seenUrls.add(url)
  results.push({
    url,
    thumbnailUrl,
    sourceUrl: readHttpsUrl(candidate.sourceUrl),
    description: readOptionalString(candidate.description),
    width: candidate.width,
    height: candidate.height,
  })
}

function parseBingMetadataResults($: ReturnType<typeof cheerio.load>, count: number): ArticleImageSearchResult[] {
  const results: ArticleImageSearchResult[] = []
  const seenUrls = new Set<string>()

  $('.iusc').each((_index, element) => {
    if (results.length >= count) {
      return false
    }

    const metadata = $(element).attr('m')
    if (!metadata) {
      return undefined
    }

    try {
      const parsed = JSON.parse(metadata) as Record<string, unknown>
      pushImageSearchResult(results, seenUrls, {
        url: readOptionalString(parsed.murl),
        thumbnailUrl: readOptionalString(parsed.turl),
        sourceUrl: readOptionalString(parsed.purl),
        description: readOptionalString(parsed.desc) ?? readOptionalString(parsed.title),
        width: readOptionalNumber(parsed.w),
        height: readOptionalNumber(parsed.h),
      })
    } catch {
      return undefined
    }

    return undefined
  })

  return results
}

function parseFallbackImageResults($: ReturnType<typeof cheerio.load>, count: number): ArticleImageSearchResult[] {
  const results: ArticleImageSearchResult[] = []
  const seenUrls = new Set<string>()
  const resultContainers = $('.iusc, .imgpt, .dgControl, .img_cont, .mimg')

  resultContainers.each((_index, element) => {
    if (results.length >= count) {
      return false
    }

    const container = $(element)
    const image = container.is('img') ? container : container.find('img').first()
    if (image.length === 0) {
      return undefined
    }

    const link = container.closest('a').length > 0
      ? container.closest('a')
      : image.closest('a')

    pushImageSearchResult(results, seenUrls, {
      url: image.attr('data-src')?.trim() || image.attr('src')?.trim(),
      thumbnailUrl: image.attr('src')?.trim() || image.attr('data-src')?.trim(),
      sourceUrl: link.attr('href')?.trim(),
      description: image.attr('alt')?.trim() || container.attr('aria-label')?.trim(),
      width: normalizeImageDimensions(image, 'width'),
      height: normalizeImageDimensions(image, 'height'),
    })

    return undefined
  })

  return results
}

function parseImageSearchResults(html: string, count: number): ArticleImageSearchResult[] {
  const $ = cheerio.load(html)
  const metadataResults = parseBingMetadataResults($, count)
  if (metadataResults.length > 0) {
    return metadataResults
  }

  const fallbackResults = parseFallbackImageResults($, count)
  if (fallbackResults.length > 0) {
    return fallbackResults
  }

  throw new AppError('搜图失败，请稍后重试', 502)
}

async function requestJson(
  endpoint: string,
  init: RequestInit & { dispatcher?: ResolvedProviderConfig['dispatcher'] },
  failureMessage: string,
  timeoutMs: number,
  signal?: AbortSignal,
): Promise<unknown> {
  const controller = new AbortController()
  let isTimeoutAbort = false
  const timeout = setTimeout(() => {
    isTimeoutAbort = true
    controller.abort(new Error('timeout'))
  }, timeoutMs)
  const abortFromCaller = (): void => controller.abort(new Error(REQUEST_ABORTED_MESSAGE))

  if (signal?.aborted) {
    abortFromCaller()
  } else {
    signal?.addEventListener('abort', abortFromCaller, { once: true })
  }

  try {
    const response = await providerFetch(endpoint, {
      ...init,
      signal: controller.signal,
      redirect: PROVIDER_FETCH_REDIRECT_POLICY,
    })

    if (!response.ok) {
      const responseText = await response.text()
      logger.error({ endpoint, status: response.status, responseTextLength: responseText.length }, 'Article image upstream returned non-ok response')
      throw new AppError(failureMessage, response.status >= 500 ? 502 : 400)
    }

    return await response.json() as unknown
  } catch (error: unknown) {
    if (error instanceof AppError) {
      throw error
    }

    if (error instanceof DOMException && error.name === 'AbortError') {
      if (signal?.aborted && !isTimeoutAbort) {
        throw new Error(REQUEST_ABORTED_MESSAGE)
      }

      throw new AppError(failureMessage, 504)
    }

    logger.error({ err: error, endpoint }, 'Article image upstream request failed')
    throw new AppError(failureMessage, 502)
  } finally {
    clearTimeout(timeout)
    signal?.removeEventListener('abort', abortFromCaller)
  }
}

export async function resolveImageGenerationConfig(
  userId?: string,
  options?: { requireModel?: boolean },
): Promise<ResolvedProviderConfig> {
  try {
    return await resolveFeatureProviderConfig('imageGeneration', 'qwen', userId, options)
  } catch {
    return resolveFeatureProviderConfig('article', 'qwen', userId, options)
  }
}

export async function recommendImages(input: RecommendImagesInput): Promise<ArticleImageRecommendation> {
  const config = await resolveFeatureProviderConfig('article', 'qwen', input.userId)

  logger.info({ platform: input.platform, hasOutline: Boolean(input.outline) }, 'Generating article image recommendations')

  const body = await requestJson(
    `${config.baseUrl.replace(/\/$/u, '')}/chat/completions`,
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(config.apiKey ? { Authorization: `Bearer ${config.apiKey}` } : {}),
      },
      body: JSON.stringify({
        model: config.model || 'qwen3.5-flash',
        messages: [
          {
            role: 'user',
            content: buildRecommendationPrompt(input),
          },
        ],
      }),
      dispatcher: config.dispatcher,
    },
    '配图推荐失败，请稍后重试',
    ARTICLE_IMAGE_TIMEOUT_MS,
    input.signal,
  )

  return normalizeRecommendation(parseJsonContent(extractContentFromChatCompletion(body)))
}

export async function searchImages(input: SearchImagesInput): Promise<ArticleImageSearchResult[]> {
  const count = input.count ?? DEFAULT_IMAGE_SEARCH_COUNT
  const endpoint = buildSearchEndpoint(input.keywords)
  const controller = new AbortController()
  let isTimeoutAbort = false
  const timeout = setTimeout(() => {
    isTimeoutAbort = true
    controller.abort(new Error('timeout'))
  }, IMAGE_SEARCH_TIMEOUT_MS)
  const abortFromCaller = (): void => controller.abort(new Error(REQUEST_ABORTED_MESSAGE))

  if (input.signal?.aborted) {
    abortFromCaller()
  } else {
    input.signal?.addEventListener('abort', abortFromCaller, { once: true })
  }

  logger.info({ keywords: input.keywords, count }, 'Searching article images')

  try {
    const response = await fetch(endpoint, {
      method: 'GET',
      headers: {
        'User-Agent': 'Mozilla/5.0 (compatible; ArticleBot/1.0)',
        Accept: 'text/html,application/json;q=0.9,*/*;q=0.8',
      },
      signal: controller.signal,
      redirect: 'follow',
    })

    if (!response.ok) {
      throw new AppError('搜图失败，请稍后重试', response.status >= 500 ? 502 : 400)
    }

    const bodyText = await response.text()
    const contentType = response.headers.get('content-type') || ''

    if (contentType.includes('application/json')) {
      try {
        const json = JSON.parse(bodyText) as unknown
        if (Array.isArray(json)) {
          const normalizedResults: ArticleImageSearchResult[] = []

          for (const item of json) {
            if (typeof item !== 'object' || item === null || Array.isArray(item)) {
              continue
            }

            const record = item as Record<string, unknown>
            const url = readHttpsUrl(record.url)
            const thumbnailUrl = readHttpsUrl(record.thumbnailUrl) ?? url
            if (!url || !thumbnailUrl) {
              continue
            }

            normalizedResults.push({
              url,
              thumbnailUrl,
              sourceUrl: readHttpsUrl(record.sourceUrl),
              description: readOptionalString(record.description),
              width: readOptionalNumber(record.width),
              height: readOptionalNumber(record.height),
            })

            if (normalizedResults.length >= count) {
              break
            }
          }

          return normalizedResults
        }
      } catch {
        logger.warn({ endpoint }, 'Image search upstream returned invalid JSON, falling back to HTML parsing')
      }
    }

    return parseImageSearchResults(bodyText, count)
  } catch (error: unknown) {
    if (error instanceof AppError) {
      throw error
    }

    if (error instanceof DOMException && error.name === 'AbortError') {
      if (input.signal?.aborted && !isTimeoutAbort) {
        throw new Error(REQUEST_ABORTED_MESSAGE)
      }

      throw new AppError('搜图超时，请稍后重试', 504)
    }

    logger.error({ err: error, endpoint }, 'Article image search request failed')
    throw new AppError('搜图失败，请稍后重试', 502)
  } finally {
    clearTimeout(timeout)
    input.signal?.removeEventListener('abort', abortFromCaller)
  }
}

export async function generateImage(input: GenerateImageInput): Promise<GeneratedArticleImage> {
  const config = await resolveImageGenerationConfig(input.userId)
  const endpoint = `${config.baseUrl.replace(/\/$/u, '')}/images/generations`

  let prompt = input.prompt

  if (input.images && input.images.length > 0) {
    logger.info({ imageCount: input.images.length, size: input.size }, 'Generating article image with reference images')
    const descriptions = await describeReferenceImages(input.images, input.signal)
    prompt = buildEnhancedPrompt(input.prompt, descriptions)
  } else {
    logger.info({ size: input.size }, 'Generating article image')
  }

  const body = await requestJson(
    endpoint,
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(config.apiKey ? { Authorization: `Bearer ${config.apiKey}` } : {}),
      },
      body: JSON.stringify({
        model: config.model,
        prompt,
        n: 1,
        size: input.size,
      }),
      dispatcher: config.dispatcher,
    },
    '图片生成失败，请稍后重试',
    IMAGE_GENERATION_TIMEOUT_MS,
    input.signal,
  )

  return normalizeGeneratedImage(body)
}

async function describeReferenceImages(
  images: ProviderImageInput[],
  signal?: AbortSignal,
): Promise<string[]> {
  const visionConfig = await resolveVisionConfig()
  const descriptions: string[] = []

  for (let i = 0; i < images.length; i++) {
    const img = images[i]
    const content: unknown[] = [
      { type: 'text', text: '请详细描述这张图片的视觉内容，包括：主体、构图、色调、风格、氛围。简洁精准地描述，用于辅助AI生图。不要输出多余说明。' },
      { type: 'image_url', image_url: { url: img.dataUrl } },
    ]

    const body = await requestJson(
      `${visionConfig.baseUrl.replace(/\/$/u, '')}/chat/completions`,
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          ...(visionConfig.apiKey ? { Authorization: `Bearer ${visionConfig.apiKey}` } : {}),
        },
        body: JSON.stringify({
          model: visionConfig.model || 'qwen2.5-vl-72b-instruct',
          messages: [{ role: 'user', content }],
        }),
        dispatcher: visionConfig.dispatcher,
      },
      '参考图分析失败，请稍后重试',
      ARTICLE_IMAGE_TIMEOUT_MS,
      signal,
    )

    descriptions.push(extractContentFromChatCompletion(body))
  }

  return descriptions
}

async function resolveVisionConfig(): Promise<ResolvedProviderConfig> {
  try {
    return await resolveFeatureProviderConfig('article', 'qwen')
  } catch {
    return resolveImageGenerationConfig()
  }
}

function buildEnhancedPrompt(userPrompt: string, descriptions: string[]): string {
  const refSection = descriptions
    .map((desc, i) => `素材${i + 1}: ${desc}`)
    .join('\n')

  return `[参考素材]\n${refSection}\n\n[创作要求]\n${userPrompt}`
}
