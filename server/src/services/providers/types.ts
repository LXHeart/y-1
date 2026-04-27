import type { Dispatcher } from 'undici'
import { AppError } from '../../lib/errors.js'
import type { ProviderImageInput, ReviewPlatform } from '../../schemas/image-analysis.js'

export interface VideoAnalysisResult {
  videoCaptions?: string
  videoScript?: string
  charactersDescription?: string
  voiceDescription?: string
  propsDescription?: string
  sceneDescription?: string
  runId?: string
  segmented?: boolean
  clipCount?: number
  runIds?: string[]
}

export interface VideoScene {
  shotDescription: string
  characterDescription: string
  actionMovement: string
  dialogueVoiceover: string
  sceneEnvironment: string
}

export interface VideoRecreationResult {
  scenes: VideoScene[]
  overallStyle?: string
  runId?: string
  segmented?: boolean
  clipCount?: number
  runIds?: string[]
}

export interface AdaptedCharacterSheet {
  id: string
  name: string
  description: string
  threeViewPrompt: string
}

export interface AdaptedSceneCard {
  id: string
  title?: string
  description: string
  imagePrompt: string
}

export interface AdaptedPropCard {
  id: string
  name: string
  description: string
  imagePrompt: string
}

export interface VideoAdaptationResult {
  adaptedTitle?: string
  adaptedSummary: string
  visualStyle?: string
  tone?: string
  characterSheets: AdaptedCharacterSheet[]
  sceneCards: AdaptedSceneCard[]
  propCards: AdaptedPropCard[]
  runId?: string
}

export interface VideoAdaptationInput {
  platform: 'douyin' | 'bilibili'
  proxyVideoUrl: string
  extractedContent: {
    videoCaptions?: string
    videoScript?: string
    charactersDescription?: string
    voiceDescription?: string
    propsDescription?: string
    sceneDescription?: string
  }
}

export interface ImageAnalysisResult {
  review: string
  title?: string
  tags?: string[]
  runId?: string
}

export type ImageAnalysisProgressStage = 'prepare' | 'draft' | 'optimize' | 'style-refine' | 'complete'

export interface ImageAnalysisProgressEvent {
  stage: ImageAnalysisProgressStage
  message: string
  attempt?: number
  totalAttempts?: number
  startedAt?: string
  completedAt?: string
  durationMs?: number
}

export interface ImageReviewPromptInput {
  reviewLength: number
  feelings?: string
  platform?: ReviewPlatform
}

export interface ResolvedProviderConfig {
  baseUrl: string
  apiKey?: string
  model?: string
  dispatcher?: Dispatcher
}

export interface ProviderCallOptions {
  signal?: AbortSignal
  timeoutMs: number
  onProgress?: (event: ImageAnalysisProgressEvent) => void
}

export interface ModelInfo {
  id: string
  ownedBy?: string
}

export interface VideoAnalysisProvider {
  readonly id: string
  readonly label: string
  readonly supportsModelListing: boolean
  analyze(
    videoUrl: string,
    config: ResolvedProviderConfig,
    options: ProviderCallOptions,
  ): Promise<VideoAnalysisResult>
  analyzeForRecreation?(
    videoUrl: string,
    config: ResolvedProviderConfig,
    options: ProviderCallOptions,
  ): Promise<VideoRecreationResult>
  adaptVideoContent?(
    input: VideoAdaptationInput,
    config: ResolvedProviderConfig,
    options: ProviderCallOptions,
  ): Promise<VideoAdaptationResult>
  analyzeImages?: (
    images: ProviderImageInput[],
    promptInput: ImageReviewPromptInput,
    config: ResolvedProviderConfig,
    options: ProviderCallOptions,
  ) => Promise<ImageAnalysisResult>
  listModels?: (config: ResolvedProviderConfig) => Promise<ModelInfo[]>
  verifyModel?: (config: ResolvedProviderConfig, modelId: string, options?: { feature?: string }) => Promise<boolean>
}

interface VideoAnalysisApiResponse {
  video_captions?: unknown
  video_script?: unknown
  characters_description?: unknown
  voice_description?: unknown
  props_description?: unknown
  scene_description?: unknown
  run_id?: unknown
}

interface VideoRecreationApiResponse {
  scenes?: unknown
  overall_style?: unknown
  run_id?: unknown
}

interface VideoAdaptationApiResponse {
  adapted_title?: unknown
  adapted_summary?: unknown
  visual_style?: unknown
  tone?: unknown
  character_sheets?: unknown
  scene_cards?: unknown
  prop_cards?: unknown
  run_id?: unknown
}

interface SceneApiResponse {
  shot_description?: unknown
  character_description?: unknown
  action_movement?: unknown
  dialogue_voiceover?: unknown
  scene_environment?: unknown
}

interface ImageAnalysisApiResponse {
  review?: unknown
  title?: unknown
  tags?: unknown
  run_id?: unknown
}

function readOptionalString(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value.trim() : undefined
}

export function normalizeVideoAnalysisResult(value: unknown): VideoAnalysisResult {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new AppError('视频内容提取服务返回了无效数据', 502)
  }

  const record = value as VideoAnalysisApiResponse

  return {
    videoCaptions: readOptionalString(record.video_captions),
    videoScript: readOptionalString(record.video_script),
    charactersDescription: readOptionalString(record.characters_description),
    voiceDescription: readOptionalString(record.voice_description),
    propsDescription: readOptionalString(record.props_description),
    sceneDescription: readOptionalString(record.scene_description),
    runId: readOptionalString(record.run_id),
  }
}

function normalizeScene(value: unknown): VideoScene | null {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    return null
  }

  const record = value as SceneApiResponse
  const shotDescription = readOptionalString(record.shot_description)
  const characterDescription = readOptionalString(record.character_description)
  const sceneEnvironment = readOptionalString(record.scene_environment)

  if (!shotDescription && !characterDescription && !sceneEnvironment) {
    return null
  }

  return {
    shotDescription: shotDescription ?? '',
    characterDescription: characterDescription ?? '',
    actionMovement: readOptionalString(record.action_movement) ?? '',
    dialogueVoiceover: readOptionalString(record.dialogue_voiceover) ?? '',
    sceneEnvironment: sceneEnvironment ?? '',
  }
}

export function normalizeVideoRecreationResult(value: unknown): VideoRecreationResult {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new AppError('视频复刻分析服务返回了无效数据', 502)
  }

  const record = value as VideoRecreationApiResponse
  const rawScenes = record.scenes

  if (!Array.isArray(rawScenes) || rawScenes.length === 0) {
    throw new AppError('视频复刻分析服务返回了空场景列表', 502)
  }

  const scenes = rawScenes
    .map((item) => normalizeScene(item))
    .filter((s): s is VideoScene => s !== null)

  if (scenes.length === 0) {
    throw new AppError('视频复刻分析服务返回了空场景列表', 502)
  }

  return {
    scenes,
    overallStyle: readOptionalString(record.overall_style),
    runId: readOptionalString(record.run_id),
  }
}

function normalizeCharacterSheet(value: unknown): AdaptedCharacterSheet | null {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    return null
  }

  const record = value as Record<string, unknown>
  const id = readOptionalString(record.id)
  const name = readOptionalString(record.name)
  const description = readOptionalString(record.description)
  const threeViewPrompt = readOptionalString(record.three_view_prompt)

  if (!id || !name || !description || !threeViewPrompt) {
    return null
  }

  return {
    id,
    name,
    description,
    threeViewPrompt,
  }
}

function normalizeSceneCard(value: unknown): AdaptedSceneCard | null {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    return null
  }

  const record = value as Record<string, unknown>
  const id = readOptionalString(record.id)
  const description = readOptionalString(record.description)
  const imagePrompt = readOptionalString(record.image_prompt)

  if (!id || !description || !imagePrompt) {
    return null
  }

  return {
    id,
    title: readOptionalString(record.title),
    description,
    imagePrompt,
  }
}

function normalizePropCard(value: unknown): AdaptedPropCard | null {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    return null
  }

  const record = value as Record<string, unknown>
  const id = readOptionalString(record.id)
  const name = readOptionalString(record.name)
  const description = readOptionalString(record.description)
  const imagePrompt = readOptionalString(record.image_prompt)

  if (!id || !name || !description || !imagePrompt) {
    return null
  }

  return {
    id,
    name,
    description,
    imagePrompt,
  }
}

export function normalizeVideoAdaptationResult(value: unknown): VideoAdaptationResult {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new AppError('视频内容改编服务返回了无效数据', 502)
  }

  const record = value as VideoAdaptationApiResponse
  const adaptedSummary = readOptionalString(record.adapted_summary)
  const rawCharacterSheets = Array.isArray(record.character_sheets) ? record.character_sheets : []
  const rawSceneCards = Array.isArray(record.scene_cards) ? record.scene_cards : []
  const rawPropCards = Array.isArray(record.prop_cards) ? record.prop_cards : []

  const characterSheets = rawCharacterSheets
    .map((item) => normalizeCharacterSheet(item))
    .filter((item): item is AdaptedCharacterSheet => item !== null)
  const sceneCards = rawSceneCards
    .map((item) => normalizeSceneCard(item))
    .filter((item): item is AdaptedSceneCard => item !== null)
  const propCards = rawPropCards
    .map((item) => normalizePropCard(item))
    .filter((item): item is AdaptedPropCard => item !== null)

  if (!adaptedSummary) {
    throw new AppError('视频内容改编服务返回了空结果', 502)
  }

  return {
    adaptedTitle: readOptionalString(record.adapted_title),
    adaptedSummary,
    visualStyle: readOptionalString(record.visual_style),
    tone: readOptionalString(record.tone),
    characterSheets,
    sceneCards,
    propCards,
    runId: readOptionalString(record.run_id),
  }
}

export function legacyResultToRecreationResult(legacy: VideoAnalysisResult): VideoRecreationResult {
  return {
    scenes: [
      {
        shotDescription: legacy.sceneDescription ?? '',
        characterDescription: legacy.charactersDescription ?? '',
        actionMovement: '',
        dialogueVoiceover: [legacy.videoScript, legacy.videoCaptions].filter(Boolean).join('\n'),
        sceneEnvironment: [legacy.sceneDescription, legacy.propsDescription].filter(Boolean).join('\n'),
      },
    ],
    overallStyle: legacy.voiceDescription,
    runId: legacy.runId,
    segmented: legacy.segmented,
    clipCount: legacy.clipCount,
    runIds: legacy.runIds,
  }
}

export function normalizeImageAnalysisResult(value: unknown): ImageAnalysisResult {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new AppError('图片评价生成服务返回了无效数据', 502)
  }

  const record = value as ImageAnalysisApiResponse
  const review = readOptionalString(record.review)

  if (!review) {
    throw new AppError('图片评价生成服务返回了空结果', 502)
  }

  const title = readOptionalString(record.title)
  const rawTags = record.tags
  const tags = Array.isArray(rawTags)
    ? rawTags.filter((t: unknown): t is string => typeof t === 'string' && t.trim().length > 0)
    : undefined

  return {
    review,
    ...(title ? { title } : {}),
    ...(tags && tags.length > 0 ? { tags } : {}),
    runId: readOptionalString(record.run_id),
  }
}

export interface ArticleTitleOption {
  title: string
  hook: string
}

export interface ArticleGenerationProvider {
  generateTitles(
    topic: string,
    platform: string,
    config: ResolvedProviderConfig,
    options: ProviderCallOptions,
  ): Promise<ArticleTitleOption[]>
  streamOutline(
    topic: string,
    title: string,
    platform: string,
    config: ResolvedProviderConfig,
    options: ProviderCallOptions,
  ): AsyncIterable<string>
  streamContent(
    topic: string,
    title: string,
    outline: string,
    platform: string,
    config: ResolvedProviderConfig,
    options: ProviderCallOptions,
  ): AsyncIterable<string>
}

export function buildClientAbortedError(): AppError {
  return new AppError('分析请求已取消', 499)
}

export function describeAnalysisVideoUrlType(videoUrl: string): 'proxy' | 'analysis-media' | 'other' {
  if (videoUrl.includes('/api/bilibili/proxy/') || videoUrl.includes('/api/douyin/proxy/')) {
    return 'proxy'
  }

  if (videoUrl.includes('/api/bilibili/analysis-media/') || videoUrl.includes('/api/douyin/analysis-media/')) {
    return 'analysis-media'
  }

  return 'other'
}
