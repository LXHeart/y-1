import { ref } from 'vue'
import { request } from './grassland-http'
import type { VideoScene } from '../types/video-recreation'
import type { SceneImageState } from '../types/video-recreation'
import type { VideoTaskExecutionContext } from '../types/video-recreation'

export function useVideoRecreation(taskContext?: VideoTaskExecutionContext) {
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
      const data = await request<{ imageUrl: string; revisedPrompt?: string }>(
        '/api/video-recreation/generate-scene-image', {
          method: 'POST',
          body: JSON.stringify({ scene, overallStyle, ...(taskContext || {}) }),
        }, { fallbackError: '参考图生成失败' })

      if (!data) {
        throw new Error('参考图生成失败')
      }

      sceneImages.value = new Map(sceneImages.value).set(index, {
        imageUrl: data.imageUrl,
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
      const data = await request<{ images: Array<{ imageUrl: string; revisedPrompt?: string }> }>(
        '/api/video-recreation/generate-all-scene-images', {
          method: 'POST',
          body: JSON.stringify({ scenes, overallStyle, ...(taskContext || {}) }),
        }, { fallbackError: '批量生成参考图失败' })

      if (!data) {
        throw new Error('批量生成参考图失败')
      }

      const resultMap = new Map<number, SceneImageState>()
      data.images.forEach((img, i) => {
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
