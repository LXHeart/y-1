export type VideoProductionStage = 'upload' | 'storyboard' | 'generate' | 'compose'

export type IndustryType = '餐饮' | '零售' | '美业' | '健身' | '教育培训' | '其他'

export type VideoStyle = '烟火纪实' | '治愈清新' | '高级暗调' | '数字人口播' | '复古胶片'

/**
 * 任务书 #64 P9：成片时长 15-60 秒、步进 5，默认 30。
 */
export const TARGET_DURATION_MIN = 15
export const TARGET_DURATION_MAX = 60
export const TARGET_DURATION_STEP = 5
export const TARGET_DURATION_DEFAULT = 30

/** 单镜时长硬约束（§4.2：4-6 秒）。 */
export const SHOT_SECONDS_MIN = 4
export const SHOT_SECONDS_MAX = 6
/** 镜头数上限（§4.2：3-10，编辑态允许手工减到 1）。 */
export const SHOT_COUNT_MAX = 10

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

export interface VideoProductionForm {
  shopName: string
  industryType: IndustryType
  targetPlatform: AiPlatformId | ''
  shopAddress: string
  shopDescription: string
  videoStyle: VideoStyle
  customPrompt: string
  targetDurationSeconds: number
}

/** 一个分镜镜头（SSE shot 帧 / 第 2 步可编辑）。anchorImageIndex 1 基，0=无锚定图。 */
export interface StoryboardShot {
  seq: number
  visual: string
  narration: string
  plannedSeconds: number
  cameraMove: string
  anchorImageIndex: number
  prompt: string
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

import type { AiPlatformId } from './ai-creation'
