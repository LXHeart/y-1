import { ref } from 'vue'
import type { VideoScene } from '../types/video-recreation'
import type { SceneImageState } from '../types/video-recreation'

export function useVideoRecreation() {
  const sceneImages = ref<Map<number, SceneImageState>>(new Map())
  const allImagesLoading = ref(false)

  async function generateSceneImage(
    index: number,
    scene: VideoScene,
    overallStyle?: string,
  ): Promise<void> {
    const current = sceneImages.value.get(index)
    if (current?.loading) return

    sceneImages.value = new Map(sceneImages.value).set(index, { loading: true })

    try {
      const response = await fetch('/api/video-recreation/generate-scene-image', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ scene, overallStyle }),
      })

      const body = await response.json() as {
        success?: boolean
        data?: { imageUrl: string; revisedPrompt?: string }
        error?: string
      }

      if (!response.ok || !body.success || !body.data) {
        throw new Error(body.error || '参考图生成失败')
      }

      sceneImages.value = new Map(sceneImages.value).set(index, {
        imageUrl: body.data.imageUrl,
        loading: false,
      })
    } catch (err: unknown) {
      sceneImages.value = new Map(sceneImages.value).set(index, {
        loading: false,
        error: err instanceof Error ? err.message : '参考图生成失败',
      })
    }
  }

  async function generateAllImages(
    scenes: VideoScene[],
    overallStyle?: string,
  ): Promise<void> {
    allImagesLoading.value = true

    const newMap = new Map(sceneImages.value)
    for (let i = 0; i < scenes.length; i++) {
      if (!newMap.get(i)?.imageUrl) {
        newMap.set(i, { loading: true })
      }
    }
    sceneImages.value = newMap

    try {
      const response = await fetch('/api/video-recreation/generate-all-scene-images', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ scenes, overallStyle }),
      })

      const body = await response.json() as {
        success?: boolean
        data?: { images: Array<{ imageUrl: string; revisedPrompt?: string }> }
        error?: string
      }

      if (!response.ok || !body.success || !body.data) {
        throw new Error(body.error || '批量生成参考图失败')
      }

      const resultMap = new Map<number, SceneImageState>()
      body.data.images.forEach((img, i) => {
        resultMap.set(i, { imageUrl: img.imageUrl, loading: false })
      })
      sceneImages.value = resultMap
    } catch (err: unknown) {
      const errMap = new Map(sceneImages.value)
      for (let i = 0; i < scenes.length; i++) {
        const entry = errMap.get(i)
        if (entry?.loading) {
          errMap.set(i, { loading: false, error: err instanceof Error ? err.message : '批量生成参考图失败' })
        }
      }
      sceneImages.value = errMap
    } finally {
      allImagesLoading.value = false
    }
  }

  function copyFullScript(scenes: VideoScene[], overallStyle?: string): string {
    const lines: string[] = []
    if (overallStyle) {
      lines.push(`整体风格：${overallStyle}`)
      lines.push('')
    }

    for (let i = 0; i < scenes.length; i++) {
      const s = scenes[i]
      lines.push(`【场景 ${i + 1}】`)
      if (s.shotDescription) lines.push(`镜头：${s.shotDescription}`)
      if (s.characterDescription) lines.push(`人物：${s.characterDescription}`)
      if (s.actionMovement) lines.push(`动作：${s.actionMovement}`)
      if (s.dialogueVoiceover) lines.push(`对白：${s.dialogueVoiceover}`)
      if (s.sceneEnvironment) lines.push(`环境：${s.sceneEnvironment}`)
      lines.push('')
    }

    return lines.join('\n').trim()
  }

  function reset(): void {
    sceneImages.value = new Map()
    allImagesLoading.value = false
  }

  return { sceneImages, allImagesLoading, generateSceneImage, generateAllImages, copyFullScript, reset }
}
