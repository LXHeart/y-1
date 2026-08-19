/**
 * AI 工作室类型（任务书 #43 —— 图片编辑台 + 视频工坊）。
 */

// ---------- 图片编辑台 ----------

export interface MattingResult {
  imageUrl: string
}

export interface ImageAdjustments {
  brightness: number   // 0–200, default 100
  contrast: number     // 0–200, default 100
  saturation: number   // 0–200, default 100
  temperature: number  // -100–100, default 0 (CSS hue-rotate 近似)
}

export type FilterPreset = 'original' | 'bright' | 'warm' | 'mono'

export type CropRatio = 'free' | '3:4' | '9:16' | '4:3' | '2.35:1'

export type BackgroundMode = 'color' | 'gradient' | 'blur' | 'image'

export interface BackgroundConfig {
  mode: BackgroundMode
  color: string
  gradientFrom: string
  gradientTo: string
  blurRadius: number
  imageFile: File | null
}

// ---------- 视频工坊 ----------

export interface VideoEditTemplate {
  id: string
  name: string
  platforms: string[]
  forms: string[]
  pace: 'fast' | 'medium' | 'slow'
  durationHint: string
  structure: TemplateBeat[]
  subtitleStyle: string
  bgmMood: string
}

export interface TemplateBeat {
  beat: string
  timeShare: number
  hint: string
}

export interface SubtitleCue {
  id: string
  start: number // seconds
  end: number   // seconds
  text: string
}

export interface SpeechTranscriptionItem {
  id: string
  mediaReferenceId: string
  durationMs: number
  status: string
  detectedLanguage: string | null
  transcriptText: string | null
  createdAt: string
}

export interface BgmAdviceInput {
  platform: string
  contentForm: string
  durationSeconds: number
  topic: string
  moodHint?: string
}

export interface BgmMoodDirection {
  label: string
  reason: string
  referenceStyle: string
}

export interface BgmRhythmItem {
  timeRange: string
  intensity: number   // 1-5
  suggestion: string
}

export interface BgmSyncPoint {
  atSeconds: number
  suggestion: string
}

export interface BgmAdviceResult {
  moodDirection: BgmMoodDirection
  rhythm: BgmRhythmItem[]
  syncPoints: BgmSyncPoint[]
  cautions: string[]
}
