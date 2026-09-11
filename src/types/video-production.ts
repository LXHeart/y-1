import { getPlatformFormatRule } from '../config/platform-format-rules'

export type VideoProductionStage = 'upload' | 'storyboard' | 'generate' | 'compose'

export type IndustryType = '餐饮' | '零售' | '美业' | '健身' | '教育培训' | '其他'

export type VideoStyle = '烟火纪实' | '治愈清新' | '高级暗调' | '数字人口播' | '复古胶片'

/**
 * 任务书 #64 P9 / #65 卡1：成片时长 15-180 秒、步进 5，默认 30（二期放宽上限）。
 */
export const TARGET_DURATION_MIN = 15
export const TARGET_DURATION_MAX = 180
export const TARGET_DURATION_STEP = 5
export const TARGET_DURATION_DEFAULT = 30

/** 单镜时长硬约束（§4.2：4-6 秒）。 */
export const SHOT_SECONDS_MIN = 4
export const SHOT_SECONDS_MAX = 6
/** 镜头数上限（#65 卡1：3-30，编辑态允许手工减到 1）。 */
export const SHOT_COUNT_MAX = 30

/** 分辨率两档（#65 卡1）；'' = 按平台缺省（bilibili 横版，其余竖版）。 */
export type VideoResolution = '1080x1920' | '1920x1080'

export const RESOLUTION_PORTRAIT: VideoResolution = '1080x1920'
export const RESOLUTION_LANDSCAPE: VideoResolution = '1920x1080'

/**
 * 平台缺省分辨率（任务书 #70 卡C：读平台规则契约 videoSpec，与后端 VideoResolution.defaultFor 同值集——
 * bilibili→16:9 横版，其余/未选→竖版）。
 */
export function defaultResolutionFor(platform: string): VideoResolution {
  return getPlatformFormatRule(platform)?.videoSpec?.aspect === '16:9'
    ? RESOLUTION_LANDSCAPE
    : RESOLUTION_PORTRAIT
}

/** §4.2 运镜词表（与后端 StoryboardPrompts.CAMERA_MOVES 同值集）。 */
export const CAMERA_MOVES = [
  '固定机位', '缓慢推近', '缓慢拉远', '左右横移', '跟随运镜', '环绕',
  '俯拍下摇', '仰拍上摇', '特写切换', '手持感轻晃', '升降镜头', '旋转',
] as const

export interface VideoProductionImage {
  id: string
  dataUrl: string
  name: string
}

/** AI内容中心改造-03 §3.1：视频输入分支——与后端 StoryboardRequest.inputMode 同源。 */
export type VideoInputMode = 'store-photos' | 'script' | 'own-media'

/** 自有素材引用（mediaId=media_reference 行；裁剪范围供素材计划与导出核对）。 */
export interface VideoOwnMediaRef {
  mediaId: string
  label?: string
  trimStartSeconds?: number
  trimEndSeconds?: number
}

export interface VideoProductionForm {
  brief?: import('./creation').CreationBrief
  inputMode?: VideoInputMode
  /** script 分支的已有脚本（权威内容；分镜忠实脚本，不虚构店铺信息）。 */
  script?: string
  /** own-media 分支的自有素材引用（归属/类型校验在后端模型调用前执行）。 */
  ownMediaRefs?: VideoOwnMediaRef[]
  shopName: string
  industryType: IndustryType
  targetPlatform: AiPlatformId | ''
  shopAddress: string
  shopDescription: string
  videoStyle: VideoStyle
  customPrompt: string
  targetDurationSeconds: number
  /** '' = 按平台缺省（bilibili→横版，其余→竖版）；#65 卡1 */
  resolution: VideoResolution | ''
}

/** 一个分镜镜头（SSE shot 帧 / 第 2 步可编辑）。anchorImageIndex 1 基，0=无锚定图。 */
export interface StoryboardShot {
  /** 服务端行 id（#65 卡2：补图按钮锚点）；本地新增镜头无 id。 */
  id?: string
  seq: number
  visual: string
  narration: string
  plannedSeconds: number
  cameraMove: string
  anchorImageIndex: number
  prompt: string
  /** #65 卡2：AI 补图首帧（生成后回填；编辑态本地状态） */
  anchorSource?: 'user' | 'ai'
  anchorMediaId?: string
  anchorUrl?: string | null
}

/** 任务书 #64 卡2：capabilities 新契约（mode=slideshow 时走图文成片降级，不锁死）。 */
export interface VideoCapabilities {
  mode: 'video' | 'slideshow'
  video: {
    available: boolean
    provider: string | null
    model: string | null
    unitPriceCents: number | null
    reason: string
  }
  tts: {
    available: boolean
    model: string | null
    reason: string
  }
}

export const SLIDESHOW_NOTICE = '当前未配置视频生成模型，将以图文成片模式产出（图片轮播+运镜+配音+字幕）'
export const TTS_UNAVAILABLE_NOTICE = '配音模型未配置，成片将无配音'

// ---- 任务书 #100 C100-05：制作任务共享声明（自 useVideoProduction 迁入，旧导入经 re-export 兼容） ----

export interface TaskTake {
  id: string
  takeNo: number
  status: string
  attempts: number
  provider: string | null
  model: string | null
  mediaId: string | null
  durationMs: number | null
  errorCode: string | null
  errorMessage: string | null
  selectable: boolean
  /** 质检评分（任务书 #66 D1）：0-100；null=未评不显角标（advisory）。 */
  score: number | null
  /** 评分提示标签（如「与锚定图差异大」）；未评为空数组。 */
  scoreLabels: string[]
  url: string | null
}

export interface TaskShot {
  id: string
  seq: number
  visual: string
  narration: string
  plannedSeconds: number
  cameraMove: string
  anchorImageIndex: number
  prompt: string
  status: string
  audio: { status: string | null; provider: string | null; model: string | null; durationMs: number | null }
  takes: TaskTake[]
}

export interface VideoTask {
  id: string
  storyboardId: string
  mode: 'video' | 'slideshow'
  phase: string
  progress: number
  targetDurationSeconds: number
  provider: string | null
  model: string | null
  unitPriceCents: number
  estimatedCostCents: number
  actualCostCents: number | null
  actualDurationSeconds: number | null
  errorCode: string | null
  errorMessage: string | null
  /** 选片单调版本（任务书 #100 C100-01）：旧响应/轮询低于已接收版本时选择数据不回退。 */
  selectionVersion?: number
  selection: Record<string, string>
  recommended: Record<string, string>
  finalUrl: string | null
  subtitleUrl: string | null
  shots: TaskShot[]
}

export interface HistoryItem {
  id: string
  storyboardId: string
  mode: string
  phase: string
  progress: number
  targetDurationSeconds: number
  actualDurationSeconds: number | null
  estimatedCostCents: number
  actualCostCents: number | null
  unitPriceCents: number
  createdAt: string | null
  completedAt: string | null
  errorCode: string | null
  errorMessage: string | null
}

import type { AiPlatformId } from './ai-creation'
