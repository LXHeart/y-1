/**
 * 小红书创作台四步状态机（阶段 0 骨架，方案 §4.1【F3/N3/F-B】）。
 *
 * - current 是「本地用户步」：初始按引擎 stage 映射；此后仅用户动作（go）与终态跳转
 *   （completed→publish，唯一自动跳转）可更新——引擎 stage 推进（如 streamContent 末尾
 *   enterCheck 置 'check'）不自动切步，正文生成完成后停留在创作步（编辑工位语义）。
 * - reached 按数据存在性派生（草稿恢复即正确）：titles⇒generate；content⇒proof；completed⇒publish。
 * - go() 的引擎协同（红线：article.stage 写入口仅引擎函数与这两条转换）：
 *   go('proof') 且 stage!=='check' → enterCheck()；go('generate') 且 stage==='check' → 置回 'content'。
 * - 【F-B】两个重映射钩子：restoredProjectId 变化（异步恢复完成）重算初始映射；
 *   handoff revision 非 initial 变化（会话内换选题）立即回 'pick'。
 * - ?step= 深链：合法且 ≤reached 才生效；go()/终态跳转以 queued replace 同步 URL。
 */
import { computed, ref, watch } from 'vue'
import type { Ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { useArticleCreation } from '../../../composables/useArticleCreation'
import type { XhsStudioStep, XhsStudioStepsApi } from '../types'

const STEP_ORDER: readonly XhsStudioStep[] = ['pick', 'generate', 'proof', 'publish']

export function isXhsStudioStep(value: unknown): value is XhsStudioStep {
  return typeof value === 'string' && (STEP_ORDER as readonly string[]).includes(value)
}

function stepIndex(step: XhsStudioStep): number {
  return STEP_ORDER.indexOf(step)
}

/** 引擎 stage → 用户步的初始映射（【N3】恢复语义：与用户上次离开时所处的步一致）。 */
export function mapEngineStageToStep(
  stage: string,
  completed: boolean,
): XhsStudioStep {
  if (completed) return 'publish'
  if (stage === 'check') return 'proof'
  if (stage === 'titles' || stage === 'outline' || stage === 'content' || stage === 'images') return 'generate'
  return 'pick' // topic | question
}

export function useXhsStudioSteps(options: {
  article: ReturnType<typeof useArticleCreation>
  /** 一次恢复/继续创作 adopt 完成时变化（useWorkspaceAutosave.apply 设置）。 */
  restoredProjectId: Ref<string>
  /** 二次 handoff 判据：() => props.creationHandoff?.revision。 */
  handoffRevision: () => number | undefined
}): XhsStudioStepsApi {
  const { article } = options
  const route = useRoute()
  const router = useRouter()
  const current = ref<XhsStudioStep>(mapEngineStageToStep(article.stage.value, article.completed.value))

  /** 最深可见步：按数据存在性派生（titles⇒generate；content⇒proof；completed⇒publish）。 */
  const reached = computed<XhsStudioStep>(() => {
    if (article.completed.value) return 'publish'
    if (article.content.value.trim()) return 'proof'
    if (article.titles.value.length > 0) return 'generate'
    return 'pick'
  })

  function canGo(step: XhsStudioStep): boolean {
    return stepIndex(step) <= stepIndex(reached.value)
  }

  /** queued replace：串行化 URL 写入，沿用 useArticleUrlState 的模式防竞态。 */
  let queued = Promise.resolve()
  function syncUrl(step: XhsStudioStep): void {
    queued = queued.then(async () => {
      const query = { ...route.query, step }
      if (JSON.stringify(query) !== JSON.stringify(route.query)) await router.replace({ query })
    }).catch(() => {})
  }

  function go(step: XhsStudioStep): void {
    if (!canGo(step)) return
    // 引擎协同（仅有的两条 stage 转换写入口之二）：
    // 进校对且引擎未在检查态 → 自动复查语义保留；回创作步且处于检查态 → 回编辑工位。
    if (step === 'proof' && article.stage.value !== 'check') article.enterCheck()
    if (step === 'generate' && article.stage.value === 'check') article.stage.value = 'content'
    current.value = step
    syncUrl(step)
  }

  /** 按初始映射表重算 current；?step= 深链合法且 ≤reached 时覆盖（N3/F-B①）。 */
  function remapFromEngine(): void {
    const mapped = mapEngineStageToStep(article.stage.value, article.completed.value)
    const deep = route.query.step
    current.value = typeof deep === 'string' && isXhsStudioStep(deep) && stepIndex(deep) <= stepIndex(reached.value)
      ? deep
      : mapped
  }

  // 进入视图时的 ?step= 深链（可覆盖初始映射值）。
  remapFromEngine()

  // 【F-B①】恢复完成重算：?draft= 恢复是异步 loadProject→adopt，setup 时 stage 还是
  // 'topic'（映射 pick）；回填 'content' 后按映射表重算，否则 current 停留 'pick'。
  watch(options.restoredProjectId, (id) => {
    if (id) remapFromEngine()
  })

  // 【F-B②】二次 handoff：apply 链会 reset 清数据，但 reset 不带动 current（红线），
  // 这里立即重置回 pick（不等异步 drain/apply 完成，prefill.topic 随后到达 pick 表单）。
  watch(options.handoffRevision, () => {
    current.value = 'pick'
  })

  // 终态跳转：completed 变 true → publish（唯一允许的自动跳转）。
  watch(() => article.completed.value, (completed) => {
    if (completed) {
      current.value = 'publish'
      syncUrl('publish')
    }
  })

  return { current, reached, canGo, go }
}
