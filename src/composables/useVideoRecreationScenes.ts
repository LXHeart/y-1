import { ref } from 'vue'
import { request } from './grassland-http'
import type { VideoScene, VideoRecreationResult, VideoTaskExecutionContext } from '../types/video-recreation'

export type RecreationSourcePlatform = 'douyin' | 'bilibili'

function readOptionalString(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value.trim() : undefined
}

function normalizeScene(value: unknown): VideoScene | null {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return null
  const record = value as Record<string, unknown>
  const scene: VideoScene = {
    shotDescription: readOptionalString(record.shot_description ?? record.shotDescription) ?? '',
    characterDescription: readOptionalString(record.character_description ?? record.characterDescription) ?? '',
    actionMovement: readOptionalString(record.action_movement ?? record.actionMovement) ?? '',
    dialogueVoiceover: readOptionalString(record.dialogue_voiceover ?? record.dialogueVoiceover) ?? '',
    sceneEnvironment: readOptionalString(record.scene_environment ?? record.sceneEnvironment) ?? '',
  }
  if (!scene.shotDescription && !scene.characterDescription && !scene.sceneEnvironment) return null
  return scene
}

export function normalizeRecreationResult(value: unknown): VideoRecreationResult | null {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return null
  const record = value as Record<string, unknown>
  if (!Array.isArray(record.scenes) || record.scenes.length === 0) return null

  const scenes = record.scenes
    .map((item) => normalizeScene(item))
    .filter((item): item is VideoScene => item !== null)
  if (scenes.length === 0) return null

  return {
    scenes,
    overallStyle: readOptionalString(record.overall_style ?? record.overallStyle),
    runId: readOptionalString(record.run_id ?? record.runId),
  }
}

/**
 * 复刻分镜分析（PRD §4.4）：对已提取的参考视频调用 `POST /api/{platform}/analyze-video`
 * （body `mode:"recreation"`），返回结构化分镜场景，供 VideoRecreationPanel 逐场景生成参考图。
 */
export function useVideoRecreationScenes() {
  const result = ref<VideoRecreationResult | null>(null)
  const loading = ref(false)
  const error = ref('')
  let requestCounter = 0
  let currentController: AbortController | null = null

  async function analyzeScenes(
    platform: RecreationSourcePlatform,
    proxyVideoUrl: string,
    taskContext?: VideoTaskExecutionContext,
  ): Promise<VideoRecreationResult | null> {
    const normalizedProxyVideoUrl = proxyVideoUrl.trim()
    if (!normalizedProxyVideoUrl) {
      result.value = null
      error.value = '缺少可分析的视频地址'
      return null
    }

    currentController?.abort()
    const controller = new AbortController()
    currentController = controller
    const requestId = ++requestCounter

    loading.value = true
    error.value = ''
    result.value = null

    try {
      const data = await request<unknown>(`/api/${platform}/analyze-video`, {
        method: 'POST',
        body: JSON.stringify({
          proxyVideoUrl: normalizedProxyVideoUrl,
          mode: 'recreation',
          ...(taskContext || {}),
        }),
        signal: controller.signal,
      }, { fallbackError: '复刻分镜分析失败，请稍后重试' })

      const normalized = normalizeRecreationResult(data)
      if (!normalized) {
        throw new Error('复刻分镜分析失败，请稍后重试')
      }
      if (requestId !== requestCounter) return null

      result.value = normalized
      return normalized
    } catch (requestError: unknown) {
      if (requestId !== requestCounter) return null
      if (requestError instanceof DOMException && requestError.name === 'AbortError') return null
      error.value = requestError instanceof Error ? requestError.message : '复刻分镜分析失败，请稍后重试'
      return null
    } finally {
      if (requestId === requestCounter) loading.value = false
      if (currentController === controller) currentController = null
    }
  }

  function reset(): void {
    currentController?.abort()
    currentController = null
    requestCounter += 1
    result.value = null
    loading.value = false
    error.value = ''
  }

  return { result, loading, error, analyzeScenes, reset }
}
