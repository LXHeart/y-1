/**
 * 小红书创作台热榜选题薄封装（方案 §4.2 工程师 A，featureId=pick-hotlist reuse）。
 *
 * 真实数据源复用：`useHomepageHotItems`（60s/ALAPI 实时榜）+ `useCreationAssistant`
 * 的 `topicFromHot` AI 拆解（SSE），组件 HotTopicPicker 原样挂载（平台分组/热度值/
 * 来源标签由组件自带，无小红书赛道专属榜——不做匹配度等假数据）。
 *
 * 行为差异（方案 §4.2 记录在案）：热榜 pick 只预填表单题目、不自动触发生成
 * （原型「点击即生成」会误触计费）；epoch 守卫抄 useHotTopicSource.ts 口径——
 * 换热点/改输入使在途 refine 结果作废。
 */
import { ref } from 'vue'
import { useHomepageHotItems } from '../../../composables/useHomepageHotItems'
import { useCreationAssistant } from '../../../composables/useCreationAssistant'
import type { HomepageHotFilters } from '../../../types/homepage-hot'
import type { useXhsTopicForm } from './useXhsTopicForm'

export interface XhsHotListOptions {
  form: ReturnType<typeof useXhsTopicForm>
  /** refine 前的登录守卫（AI 拆解为计费请求）。 */
  isAuthenticated: () => boolean
  requestLogin: () => void
}

export function useXhsHotList(options: XhsHotListOptions) {
  const hot = useHomepageHotItems()
  const assistant = useCreationAssistant()
  /** 已选热点标题（与表单题目分开存：refine 后题目被结构化选题覆盖，仍需原标题）。 */
  const pickedTitle = ref('')
  /** 挂载拉取一次成功后不再重复拉（刷新/筛选拉新）。 */
  const loaded = ref(false)
  let refineEpoch = 0

  async function ensureLoaded(): Promise<void> {
    if (loaded.value || hot.loading.value) return
    await hot.loadHotItems()
    if (!hot.error.value) loaded.value = true
  }

  async function refresh(): Promise<void> {
    await hot.loadHotItems()
    loaded.value = !hot.error.value
  }

  async function applyFilters(filters: HomepageHotFilters): Promise<void> {
    await hot.loadHotItems(filters)
    loaded.value = !hot.error.value
  }

  /** 选为选题：仅预填表单题目（不触发生成）；换热点丢掉上一条结构化结果。 */
  function pick(title: string): void {
    refineEpoch += 1
    pickedTitle.value = title
    assistant.structuredTopic.value = null
    options.form.prefill(title)
  }

  /**
   * AI 拆解热点为结构化选题（SSE）；拆解 topic 回填表单题目。
   * epoch 守卫：请求在途期间换热点/改表单题目或角度 → 旧结果作废不回填。
   */
  async function refine(angleHint: string): Promise<void> {
    if (!pickedTitle.value) return
    if (!options.isAuthenticated()) {
      options.requestLogin()
      return
    }
    const requestEpoch = ++refineEpoch
    const requestedTitle = pickedTitle.value
    const titleBeforeRequest = options.form.fields.title
    const hintBeforeRequest = angleHint.trim()
    const refined = await assistant.topicFromHot(
      requestedTitle, 'xiaohongshu', hintBeforeRequest || undefined)
    const stillCurrent = requestEpoch === refineEpoch
      && pickedTitle.value === requestedTitle
      && options.form.fields.title === titleBeforeRequest
      && angleHint.trim() === hintBeforeRequest
    if (!stillCurrent) {
      if (assistant.structuredTopic.value === refined) assistant.structuredTopic.value = null
      return
    }
    if (refined) options.form.prefill(refined.topic)
  }

  return { hot, assistant, pickedTitle, ensureLoaded, refresh, applyFilters, pick, refine }
}
