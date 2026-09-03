import { computed, ref, watch } from 'vue'
import type { Ref } from 'vue'
import { useDouyinParse } from '../../../composables/useDouyinParse'
import { useBilibiliParse } from '../../../composables/useBilibiliParse'
import { useDouyinVideoAnalysis } from '../../../composables/useDouyinVideoAnalysis'
import { useBilibiliVideoAnalysis } from '../../../composables/useBilibiliVideoAnalysis'
import { buildVideoAnalysisDisplayCards } from '../../../types/video-recreation'
import type { VideoProductionForm } from '../../../types/video-production'
import type { ShotStructureRef } from '../../../composables/useVideoProduction'

type ReferencePlatform = 'douyin' | 'bilibili'

interface ReferenceCardOption {
  key: string
  label: string
  content: string
  selected: boolean
}

interface VideoReferenceDeps {
  /** useVideoProduction 的 form Ref(appendToCustomPrompt 写 customPrompt) */
  form: Ref<VideoProductionForm>
  /** useVideoProduction 的 referenceShotStructure Ref(#66 E1 结构化参考) */
  referenceShotStructure: Ref<ShotStructureRef | null>
}

export function useVideoReference(deps: VideoReferenceDeps) {
  const referencePlatform = ref<ReferencePlatform>('douyin')
  const referenceInput = ref('')
  const hotTopicInput = ref('')
  const referenceCards = ref<ReferenceCardOption[]>([])
  const referenceApplied = ref(false)

  const {
    extractedVideo: douyinExtractedVideo,
    loading: douyinParseLoading,
    error: douyinParseError,
    extractVideo: extractDouyinVideo,
    reset: resetDouyinParse,
  } = useDouyinParse()

  const {
    analysis: douyinVideoAnalysis,
    loading: douyinAnalysisLoading,
    error: douyinAnalysisError,
    analyzeVideo: analyzeDouyinVideo,
    reset: resetDouyinAnalysis,
  } = useDouyinVideoAnalysis()

  const {
    extractedVideo: bilibiliExtractedVideo,
    loading: bilibiliParseLoading,
    error: bilibiliParseError,
    extractVideo: extractBilibiliVideo,
    reset: resetBilibiliParse,
  } = useBilibiliParse()

  const {
    analysis: bilibiliVideoAnalysis,
    loading: bilibiliAnalysisLoading,
    error: bilibiliAnalysisError,
    analyzeVideo: analyzeBilibiliVideo,
    reset: resetBilibiliAnalysis,
  } = useBilibiliVideoAnalysis()

  const referenceParseLoading = computed(() => {
    return referencePlatform.value === 'douyin' ? douyinParseLoading.value : bilibiliParseLoading.value
  })

  const activeReferenceAnalysis = computed(() => {
    return referencePlatform.value === 'douyin' ? douyinVideoAnalysis.value : bilibiliVideoAnalysis.value
  })

  const hasSelectedReferenceCards = computed(() => referenceCards.value.some((card) => card.selected))

  function toggleReferenceCard(key: string): void {
    const card = referenceCards.value.find((c) => c.key === key)
    if (card) card.selected = !card.selected
  }

  watch(activeReferenceAnalysis, (analysis) => {
    referenceCards.value = buildVideoAnalysisDisplayCards(analysis)
      .filter((card) => !card.isFallback)
      .map((card) => ({ key: card.key, label: card.label, content: card.content, selected: true }))
  })

  function handleSwitchReferencePlatform(platform: ReferencePlatform): void {
    referencePlatform.value = platform
  }

  async function handleExtractReference(): Promise<void> {
    referenceCards.value = []
    referenceApplied.value = false

    if (referencePlatform.value === 'douyin') {
      resetDouyinAnalysis()
      const data = await extractDouyinVideo(referenceInput.value)
      if (!data) return
      await analyzeDouyinVideo(data.proxyVideoUrl)
      return
    }

    resetBilibiliAnalysis()
    const data = await extractBilibiliVideo(referenceInput.value)
    if (!data) return
    await analyzeBilibiliVideo(data.proxyVideoUrl)
  }

  async function handleRetryDouyinAnalysis(): Promise<void> {
    const proxyVideoUrl = douyinExtractedVideo.value?.proxyVideoUrl
    if (!proxyVideoUrl) return
    await analyzeDouyinVideo(proxyVideoUrl)
  }

  async function handleRetryBilibiliAnalysis(): Promise<void> {
    const proxyVideoUrl = bilibiliExtractedVideo.value?.proxyVideoUrl
    if (!proxyVideoUrl) return
    await analyzeBilibiliVideo(proxyVideoUrl)
  }

  function handleClearReference(): void {
    referenceInput.value = ''
    referenceCards.value = []
    deps.referenceShotStructure.value = null
    resetDouyinAnalysis()
    resetBilibiliAnalysis()
    resetDouyinParse()
    resetBilibiliParse()
  }

  function appendToCustomPrompt(text: string): void {
    const existing = deps.form.value.customPrompt.trim()
    deps.form.value.customPrompt = existing ? `${existing}\n${text}` : text
  }

  function applyReferenceToPrompt(): void {
    const selectedCards = referenceCards.value.filter((card) => card.selected)
    if (selectedCards.length === 0) return

    const referenceText = [
      '参考视频分析产出（仅为创作建议）：',
      ...selectedCards.map((card) => `【${card.label}】\n${card.content}`),
    ].join('\n')

    appendToCustomPrompt(referenceText)
    referenceApplied.value = true
    // 任务书 #66 E1：结构化参考随「带入」透传（仅参考节奏与结构；热点话题带入不携带）
    const analysis = activeReferenceAnalysis.value
    deps.referenceShotStructure.value = analysis?.shotStructure?.length
      ? { shotStructure: analysis.shotStructure, hookAtSeconds: analysis.hookAtSeconds }
      : null
  }

  function applyHotTopicToPrompt(): void {
    const topic = hotTopicInput.value.trim()
    if (!topic) return
    appendToCustomPrompt(`创作主题：${topic}`)
    referenceApplied.value = true
  }

  function clearOptionalInputState(): void {
    referenceInput.value = ''
    hotTopicInput.value = ''
    referenceCards.value = []
    referenceApplied.value = false
    deps.referenceShotStructure.value = null
    resetDouyinAnalysis()
    resetBilibiliAnalysis()
    resetDouyinParse()
    resetBilibiliParse()
  }

  return {
    referencePlatform,
    referenceInput,
    hotTopicInput,
    referenceCards,
    referenceApplied,
    referenceParseLoading,
    hasSelectedReferenceCards,
    handleSwitchReferencePlatform,
    handleExtractReference,
    handleRetryDouyinAnalysis,
    handleRetryBilibiliAnalysis,
    handleClearReference,
    applyReferenceToPrompt,
    applyHotTopicToPrompt,
    toggleReferenceCard,
    clearOptionalInputState,
    douyinExtractedVideo,
    douyinParseLoading,
    douyinParseError,
    douyinVideoAnalysis,
    douyinAnalysisLoading,
    douyinAnalysisError,
    bilibiliExtractedVideo,
    bilibiliParseLoading,
    bilibiliParseError,
    bilibiliVideoAnalysis,
    bilibiliAnalysisLoading,
    bilibiliAnalysisError,
  }
}
