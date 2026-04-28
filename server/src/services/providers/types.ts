import type { Dispatcher } from 'undici'
import { AppError } from '../../lib/errors.js'
import type { ProviderImageInput, ReviewPlatform } from '../../schemas/image-analysis.js'

export type { ProviderImageInput }

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
  adaptedScript?: string
  adaptedVoiceDescription?: string
  visualStyle?: string
  tone?: string
  characterSheets: AdaptedCharacterSheet[]
  sceneCards: AdaptedSceneCard[]
  propCards: AdaptedPropCard[]
  runId?: string
}

export interface VideoAdaptationUserInstructions {
  scriptInstruction?: string
  characterInstruction?: string
  scenePropsInstruction?: string
  voiceInstruction?: string
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
  userInstructions?: VideoAdaptationUserInstructions
  images?: ProviderImageInput[]
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
  videoCaptions?: unknown
  videoScript?: unknown
  charactersDescription?: unknown
  voiceDescription?: unknown
  propsDescription?: unknown
  sceneDescription?: unknown
  runId?: unknown
}

interface VideoRecreationApiResponse {
  scenes?: unknown
  overall_style?: unknown
  run_id?: unknown
}

interface VideoAdaptationApiResponse {
  adapted_title?: unknown
  adapted_summary?: unknown
  adapted_script?: unknown
  adapted_voice_description?: unknown
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

function firstDefinedString(...values: unknown[]): string | undefined {
  for (const value of values) {
    const text = readOptionalString(value)
    if (text) {
      return text
    }
  }

  return undefined
}

function splitTextLines(value: string | undefined): string[] {
  if (!value) {
    return []
  }

  return value
    .split(/\n+/u)
    .map((line) => line.trim())
    .filter(Boolean)
}

function joinUniqueLines(...groups: Array<string | undefined>): string | undefined {
  const seen = new Set<string>()
  const lines: string[] = []

  for (const group of groups) {
    for (const line of splitTextLines(group)) {
      if (seen.has(line)) {
        continue
      }

      seen.add(line)
      lines.push(line)
    }
  }

  return lines.length > 0 ? lines.join('\n') : undefined
}

function extractCharacterHints(...sources: Array<string | undefined>): string | undefined {
  const lines = sources.flatMap((source) => splitTextLines(source))
  const matched = lines.filter((line) => {
    if (/没有人物|无人出镜|未见人物|没人出镜/u.test(line)) {
      return false
    }

    return /人物|角色|女生|男生|女人|男人|小孩|博主|主持人|店员|顾客|女孩|男孩|女性|男性/u.test(line)
  })

  if (matched.length === 0) {
    return undefined
  }

  return joinUniqueLines(...matched.map((line) => `可见出镜人物线索：${line}`))
}

const PROP_HINT_KEYWORDS = [
  '笔记本电脑',
  '咖啡机',
  '高脚凳',
  '木桌',
  '桌子',
  '椅子',
  '马克杯',
  '杯子',
  '盘子',
  '筷子',
  '勺子',
  '手机',
  '电脑',
  '相机',
  '背包',
  '台灯',
  '柜台',
  '麦克风',
  '耳机',
  '屏幕',
  '键盘',
  '产品',
  '设备',
] as const

function extractPropsHints(...sources: Array<string | undefined>): string | undefined {
  const lines = sources.flatMap((source) => splitTextLines(source))
  const matchedTokens: string[] = []

  for (const line of lines) {
    const lineMatches = PROP_HINT_KEYWORDS.filter((keyword) => line.includes(keyword))
      .sort((a, b) => b.length - a.length)
    const selected: string[] = []

    for (const keyword of lineMatches) {
      if (selected.some((existing) => existing.includes(keyword))) {
        continue
      }

      selected.push(keyword)
      matchedTokens.push(`可见道具/物件：${keyword}`)
    }
  }

  return joinUniqueLines(...matchedTokens)
}

export function completeVideoAnalysisResult(result: VideoAnalysisResult): VideoAnalysisResult {
  return {
    ...result,
    charactersDescription: result.charactersDescription ?? extractCharacterHints(result.sceneDescription, result.videoCaptions),
    propsDescription: result.propsDescription ?? extractPropsHints(result.sceneDescription),
  }
}

export function normalizeVideoAnalysisResult(value: unknown): VideoAnalysisResult {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new AppError('视频内容提取服务返回了无效数据', 502)
  }

  const record = value as VideoAnalysisApiResponse

  return completeVideoAnalysisResult({
    videoCaptions: firstDefinedString(record.video_captions, record.videoCaptions),
    videoScript: normalizeAdaptedScript(record.video_script ?? record.videoScript),
    charactersDescription: firstDefinedString(record.characters_description, record.charactersDescription),
    voiceDescription: firstDefinedString(record.voice_description, record.voiceDescription),
    propsDescription: firstDefinedString(record.props_description, record.propsDescription),
    sceneDescription: firstDefinedString(record.scene_description, record.sceneDescription),
    runId: firstDefinedString(record.run_id, record.runId),
  })
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

function normalizeAdaptedScript(raw: unknown): string | undefined {
  if (typeof raw === 'string' && raw.trim()) {
    return raw.trim()
  }

  if (!Array.isArray(raw) || raw.length === 0) {
    return undefined
  }

  const lines: string[] = []
  for (const shot of raw) {
    if (typeof shot !== 'object' || shot === null) {
      continue
    }

    const s = shot as Record<string, unknown>
    const number = typeof s.shot_number === 'number' ? s.shot_number : '?'
    const type = typeof s.shot_type === 'string' ? s.shot_type : ''
    const visual = typeof s.visual_content === 'string' ? s.visual_content : ''
    const camera = typeof s.camera_movement === 'string' ? s.camera_movement : ''
    const dialogue = typeof s.dialogue_narration === 'string' ? s.dialogue_narration : ''
    const text = typeof s.on_screen_text === 'string' ? s.on_screen_text : ''
    const duration = typeof s.duration_seconds === 'number' ? s.duration_seconds : ''
    const notes = typeof s.notes === 'string' ? s.notes : ''

    if (!visual && !dialogue && !type) {
      continue
    }

    lines.push(`镜头 ${number} | ${type} | ${duration}s`)
    if (visual) lines.push(`  画面：${visual}`)
    if (camera) lines.push(`  运镜：${camera}`)
    if (dialogue && dialogue !== '无') lines.push(`  台词/旁白：${dialogue}`)
    if (text && text !== '无') lines.push(`  字幕：${text}`)
    if (notes) lines.push(`  备注：${notes}`)
    lines.push('')
  }

  return lines.length > 1 ? lines.join('\n').trim() : undefined
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
    adaptedScript: normalizeAdaptedScript(record.adapted_script),
    adaptedVoiceDescription: readOptionalString(record.adapted_voice_description),
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
