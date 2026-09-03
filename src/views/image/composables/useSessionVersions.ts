import { ref, watch, type Ref } from 'vue'
import type { ImageAnalysisResult, GenerationStage } from '../../../types/image-analysis'

export type { GenerationStage } from '../../../types/image-analysis'

export interface SessionVersion {
  id: string
  label: string
  platformLabel: string
  savedAt: string
  data: ImageAnalysisResult
}

export function useSessionVersions(
  generationStage: Ref<GenerationStage>,
  result: Ref<ImageAnalysisResult | null>,
  platform: Ref<string>,
  stepResults: Ref<Record<string, unknown>>
) {
  const sessionVersions = ref<SessionVersion[]>([])
  const selectedVersionId = ref<string | null>(null)
  let versionCounter = 0

  function versionLabelForStage(stage: GenerationStage): string {
    if (stage === 'draft-review') return '初稿'
    if (stage === 'complete') {
      if (stepResults.value['style-refine']) return '风格优化版'
      if (stepResults.value.optimize) return '润色版'
      return '终版'
    }
    return '草稿'
  }

  function saveVersionSnapshot(label?: string): void {
    if (!result.value) return
    versionCounter += 1
    const snapshot: SessionVersion = {
      id: `session-version-${versionCounter}`,
      label: label ?? versionLabelForStage(generationStage.value),
      platformLabel: platform.value === 'dianping' ? '大众点评' : '淘宝',
      savedAt: new Date().toLocaleTimeString('zh-CN', { hour12: false }),
      data: {
        ...result.value,
        tags: result.value.tags ? [...result.value.tags] : undefined,
      },
    }
    sessionVersions.value = [...sessionVersions.value, snapshot]
    selectedVersionId.value = snapshot.id
  }

  function selectVersion(id: string): void {
    selectedVersionId.value = selectedVersionId.value === id ? null : id
  }

  function removeVersion(id: string): void {
    sessionVersions.value = sessionVersions.value.filter((v) => v.id !== id)
    if (selectedVersionId.value === id) selectedVersionId.value = null
  }

  watch(generationStage, (stage) => {
    if ((stage === 'draft-review' || stage === 'complete') && result.value) {
      saveVersionSnapshot()
    }
  })

  return {
    sessionVersions,
    selectedVersionId,
    versionLabelForStage,
    saveVersionSnapshot,
    selectVersion,
    removeVersion,
  }
}
