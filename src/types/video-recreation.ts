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

export interface SceneImageState {
  imageUrl?: string
  loading: boolean
  error?: string
}
