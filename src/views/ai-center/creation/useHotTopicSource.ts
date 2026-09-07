import { ref, watch } from 'vue'
import type { Ref } from 'vue'
import { useHomepageHotItems } from '../../../composables/useHomepageHotItems'
import type { HomepageHotFilters } from '../../../types/homepage-hot'
import type { AiPlatformId, CreationSourceType } from '../../../types/ai-creation'

/**
 * 热点来源取数与业务流（任务书 #92 自 AiCreationCenter 外迁——组件分层规约：取数与业务流进
 * 域 composable，视图只持装配；行为零变更，由既有 AiCreationCenter 测试锁定）。
 *
 * 持有热点列表加载/过滤、选题（pick）与结构化选题（refine）的 epoch 守卫：
 * 换热点/换来源/换平台都会使在途的 refine 结果作废。
 */
export interface HotTopicSourceOptions {
  /** useCreationAssistant() 实例（结构引用：resolvingTopic/topicError/structuredTopic/topicFromHot）。 */
  assistant: {
    resolvingTopic: Ref<boolean>
    topicError: Ref<string>
    structuredTopic: Ref<unknown>
    topicFromHot: (title: string, platform?: string, instructions?: string) => Promise<{ topic: string } | null>
  }
  sourceType: Ref<CreationSourceType | ''>
  topic: Ref<string>
  /** 已选热点标题（与 topic 分开存：topic 会被结构化选题覆盖，refine 仍需原标题）。 */
  pickedHotTitle: Ref<string>
  instructions: Ref<string>
  platformId: Ref<AiPlatformId | ''>
  isAuthenticated: () => boolean
  requestLogin: () => void
}

export function useHotTopicSource(options: HotTopicSourceOptions) {
  const {
    items: hotItems,
    groups: hotGroups,
    provider: hotProvider,
    fetchedAt: hotFetchedAt,
    taxonomy: hotTaxonomy,
    filters: hotFilters,
    loading: hotLoading,
    error: hotError,
    loadHotItems: fetchHotItems,
  } = useHomepageHotItems()
  const hotLoaded = ref(false)
  let hotRefineEpoch = 0

  watch(options.sourceType, (next) => {
    if (next === 'hot-topic') void ensureHotItemsLoaded()
  }, { immediate: true })

  async function ensureHotItemsLoaded(): Promise<void> {
    if (hotLoaded.value || hotLoading.value) return
    await fetchHotItems()
    // 加载失败不标记为已加载，重新选择该来源时会自动重试
    if (!hotError.value) hotLoaded.value = true
  }

  async function refreshHotItems(): Promise<void> {
    await fetchHotItems()
    hotLoaded.value = !hotError.value
  }

  async function applyHotFilters(filters: HomepageHotFilters): Promise<void> {
    await fetchHotItems(filters)
    hotLoaded.value = !hotError.value
  }

  function pickHotTopic(title: string): void {
    hotRefineEpoch += 1
    options.topic.value = title
    options.pickedHotTitle.value = title
    // 换热点就丢掉上一条的结构化结果，否则「角度/立意」会挂在不相干的标题下。
    options.assistant.structuredTopic.value = null
  }

  /**
   * 热点 → 结构化选题（§4.9.5）。把纯标题换成角度/立意/受众/切入点，
   * 并用结构化 topic 覆盖创作主题——后续大纲/正文拿到的是可创作的选题而不是一句热搜词。
   */
  async function refineHotTopic(): Promise<void> {
    if (!options.pickedHotTitle.value) return
    if (!options.isAuthenticated()) {
      options.requestLogin()
      return
    }
    const requestEpoch = ++hotRefineEpoch
    const requestedHotTitle = options.pickedHotTitle.value
    const topicBeforeRequest = options.topic.value
    const instructionsBeforeRequest = options.instructions.value.trim()
    const refined = await options.assistant.topicFromHot(
      requestedHotTitle, options.platformId.value || undefined, instructionsBeforeRequest || undefined)
    const stillCurrent = requestEpoch === hotRefineEpoch
      && options.sourceType.value === 'hot-topic'
      && options.pickedHotTitle.value === requestedHotTitle
      && options.topic.value === topicBeforeRequest
      && options.instructions.value.trim() === instructionsBeforeRequest
    if (!stillCurrent) {
      if (options.assistant.structuredTopic.value === refined) options.assistant.structuredTopic.value = null
      return
    }
    if (refined) options.topic.value = refined.topic
  }

  /** 使在途 refine 作废（entry 重放/重启创作链路时调用，不清字段值）。 */
  function invalidateHotRefine(): void {
    hotRefineEpoch += 1
  }

  /** 清理热点上下文：默认连选题一起清；保留选择时只废在途结果与结构化选题。 */
  function clearHotTopicContext(clearSelection = true): void {
    hotRefineEpoch += 1
    options.assistant.structuredTopic.value = null
    if (clearSelection) {
      options.pickedHotTitle.value = ''
      options.topic.value = ''
    }
  }

  return {
    hotItems, hotGroups, hotProvider, hotFetchedAt, hotTaxonomy, hotFilters, hotLoading, hotError,
    refreshHotItems, applyHotFilters, pickHotTopic, refineHotTopic, invalidateHotRefine, clearHotTopicContext,
  }
}
