import { ref } from 'vue'
import type { ApiResponse } from '../types/douyin'
import type { VideoAdaptationResult, VideoAdaptationUserInstructions } from '../types/video-recreation'

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function readOptionalString(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value.trim() : undefined
}

function normalizeAdaptedCharacterSheet(value: unknown): { id: string; name: string; description: string; threeViewPrompt: string } | null {
  if (!isPlainObject(value)) {
    return null
  }

  const id = readOptionalString(value.id)
  const name = readOptionalString(value.name)
  const description = readOptionalString(value.description)
  const threeViewPrompt = readOptionalString(value.three_view_prompt ?? value.threeViewPrompt)

  if (!id || !name || !description || !threeViewPrompt) {
    return null
  }

  return { id, name, description, threeViewPrompt }
}

function normalizeAdaptedSceneCard(value: unknown): { id: string; title?: string; description: string; imagePrompt: string } | null {
  if (!isPlainObject(value)) {
    return null
  }

  const id = readOptionalString(value.id)
  const description = readOptionalString(value.description)
  const imagePrompt = readOptionalString(value.image_prompt ?? value.imagePrompt)

  if (!id || !description || !imagePrompt) {
    return null
  }

  return { id, title: readOptionalString(value.title), description, imagePrompt }
}

function normalizeAdaptedPropCard(value: unknown): { id: string; name: string; description: string; imagePrompt: string } | null {
  if (!isPlainObject(value)) {
    return null
  }

  const id = readOptionalString(value.id)
  const name = readOptionalString(value.name)
  const description = readOptionalString(value.description)
  const imagePrompt = readOptionalString(value.image_prompt ?? value.imagePrompt)

  if (!id || !name || !description || !imagePrompt) {
    return null
  }

  return { id, name, description, imagePrompt }
}

function normalizeVideoAdaptationResult(value: unknown): VideoAdaptationResult | null {
  if (!isPlainObject(value)) {
    return null
  }

  const adaptedSummary = readOptionalString(value.adapted_summary ?? value.adaptedSummary)
  if (!adaptedSummary) {
    return null
  }

  const rawCharacterSheets: unknown[] = Array.isArray(value.character_sheets ?? value.characterSheets)
    ? (value.character_sheets ?? value.characterSheets) as unknown[]
    : []
  const rawSceneCards: unknown[] = Array.isArray(value.scene_cards ?? value.sceneCards)
    ? (value.scene_cards ?? value.sceneCards) as unknown[]
    : []
  const rawPropCards: unknown[] = Array.isArray(value.prop_cards ?? value.propCards)
    ? (value.prop_cards ?? value.propCards) as unknown[]
    : []

  const characterSheets = rawCharacterSheets
    .map(normalizeAdaptedCharacterSheet)
    .filter((item): item is NonNullable<typeof item> => item !== null)
  const sceneCards = rawSceneCards
    .map(normalizeAdaptedSceneCard)
    .filter((item): item is NonNullable<typeof item> => item !== null)
  const propCards = rawPropCards
    .map(normalizeAdaptedPropCard)
    .filter((item): item is NonNullable<typeof item> => item !== null)

  return {
    adaptedTitle: readOptionalString(value.adapted_title ?? value.adaptedTitle),
    adaptedSummary,
    adaptedScript: readOptionalString(value.adapted_script ?? value.adaptedScript),
    adaptedVoiceDescription: readOptionalString(value.adapted_voice_description ?? value.adaptedVoiceDescription),
    visualStyle: readOptionalString(value.visual_style ?? value.visualStyle),
    tone: readOptionalString(value.tone),
    characterSheets,
    sceneCards,
    propCards,
    runId: readOptionalString(value.run_id ?? value.runId),
  }
}

export function useVideoContentAdaptation() {
  const result = ref<VideoAdaptationResult | null>(null)
  const loading = ref(false)
  const error = ref('')
  let requestCounter = 0
  let currentController: AbortController | null = null

  async function adaptContent(
    platform: 'douyin' | 'bilibili',
    proxyVideoUrl: string,
    extractedContent: {
      videoCaptions?: string
      videoScript?: string
      charactersDescription?: string
      voiceDescription?: string
      propsDescription?: string
      sceneDescription?: string
    },
    userInstructions?: VideoAdaptationUserInstructions,
    images?: File[],
  ): Promise<VideoAdaptationResult | null> {
    const normalizedProxyVideoUrl = proxyVideoUrl.trim()

    if (!normalizedProxyVideoUrl) {
      error.value = '缺少视频代理地址'
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
      const hasImages = images && images.length > 0
      let body: BodyInit

      if (hasImages) {
        const formData = new FormData()
        formData.append('platform', platform)
        formData.append('proxyVideoUrl', normalizedProxyVideoUrl)
        formData.append('extractedContent', JSON.stringify(extractedContent))
        if (userInstructions) {
          formData.append('userInstructions', JSON.stringify(userInstructions))
        }
        for (const file of images!) {
          formData.append('images', file)
        }
        body = formData
      } else {
        body = JSON.stringify({
          platform,
          proxyVideoUrl: normalizedProxyVideoUrl,
          extractedContent,
          userInstructions: userInstructions || undefined,
        })
      }

      const response = await fetch('/api/video-recreation/adapt-content', {
        method: 'POST',
        headers: hasImages ? {} : { 'Content-Type': 'application/json' },
        body,
        signal: controller.signal,
      })

      const contentType = response.headers.get('content-type') || ''
      if (!contentType.includes('application/json')) {
        const fallbackText = await response.text()
        throw new Error(fallbackText || '内容改编失败，请稍后重试')
      }

      const responseBody = await response.json() as ApiResponse<unknown>
      const normalizedData = normalizeVideoAdaptationResult(responseBody.data)

      if (!response.ok || !responseBody.success || !normalizedData) {
        throw new Error(responseBody.error || '内容改编失败，请稍后重试')
      }

      if (requestId !== requestCounter) {
        return null
      }

      result.value = normalizedData
      return normalizedData
    } catch (requestError: unknown) {
      if (requestId !== requestCounter) {
        return null
      }

      if (requestError instanceof DOMException && requestError.name === 'AbortError') {
        return null
      }

      error.value = requestError instanceof Error ? requestError.message : '内容改编失败，请稍后重试'
      return null
    } finally {
      if (requestId === requestCounter) {
        loading.value = false
      }

      if (currentController === controller) {
        currentController = null
      }
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

  return {
    result,
    loading,
    error,
    adaptContent,
    reset,
  }
}
