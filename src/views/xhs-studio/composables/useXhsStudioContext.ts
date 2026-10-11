/**
 * 小红书创作台视图上下文装配（阶段 0 骨架，方案 §2.3/§2.4/§2.5/§4.1）。
 *
 * 装配顺序对齐 ArticleCreationView 既有次序，另带三处适配：
 * - 【F1】handoff getter 改写副本：useWorkspaceHandoff 按 targetView==='article' 过滤
 *   （useWorkspaceHandoff.ts:81），targetView='xhs-studio' 的 handoff 不会被 apply——
 *   这里把副本改写为 'article' 仅供 workspace 匹配，不回写 prop。
 * - 【N1/F5】KeepAlive 门控：active + onActivated/onDeactivated，gate 经
 *   useArticleWorkspace 第五参数透传进 useWorkspaceAutosave（缓存期间不写、不收）；
 *   activate 时无条件补偿一次 queueSave（【F-A】封死缓存期后台 SSE 完成不入队的丢失窗口，
 *   fingerprint 幂等不重复入队）。
 * - 【F-C】非小红书草稿直链：恢复完成后校验 engine.platform，非 xiaohongshu 清空会话
 *   回空态并提示（不脏写、不误存——空 topic 被 isValidInput 拦住）。
 */
import { computed, inject, onActivated, onDeactivated, provide, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useArticleCreation } from '../../../composables/useArticleCreation'
import { useCardSeries } from '../../../composables/useCardSeries'
import { useArticleWorkspace } from '../../article/composables/useArticleWorkspace'
import { useArticleFormatRule } from '../../article/composables/useArticleFormatRule'
import { useXhsStudioSteps } from './useXhsStudioSteps'
import { useXhsStudioTuning } from './useXhsStudioTuning'
import { XHS_STUDIO_CONTEXT_KEY } from '../types'
import type { XhsStudioContext } from '../types'
import type { CreationHandoff } from '../../../types/ai-creation'

export interface ProvideXhsStudioContextOptions {
  /** 壳层传入的 handoff prop（XhsStudioView 的 props.creationHandoff）。 */
  handoff: () => CreationHandoff | null | undefined
  /** 返回创作中心（视图 emit 上抛给壳层路由）。 */
  goCreationCenter: () => void
}

/** Toast 展示时长（与方案 §4.1 一致：1.9s 定时清除）。 */
const TOAST_MS = 1900

export function provideXhsStudioContext(options: ProvideXhsStudioContextOptions): XhsStudioContext {
  const route = useRoute()

  // 1. 生成引擎：平台固定小红书；风格三选注入 + noteMode（跳过配图阶段，视觉素材由图卡承担）。
  const engine = useArticleCreation()
  engine.platform.value = 'xiaohongshu'
  engine.styleSkillsActive.value = true
  engine.imagesStageSkipped.value = true

  // 2. 图卡系列（legacy v1 真实链：/api/card-series/plan|generate|persist）。
  const cards = useCardSeries('xiaohongshu')

  // 3.【F1】handoff 改写副本：副本只改 targetView，revision 原样保留（重复消费守卫按既有语义工作）。
  const workspaceHandoff = () => {
    const h = options.handoff()
    return h ? { ...h, targetView: 'article' as const } : h
  }

  // 4.【N1/F5】KeepAlive 门控：active 默认 true（非 KeepAlive 宿主/首次挂载即活跃）。
  const active = ref(true)

  // 5. 持久化底座：capability 仍为 'article'（旧小红书草稿无损恢复）。
  const autosave = useArticleWorkspace(engine, route, workspaceHandoff, cards, () => active.value)

  onActivated(() => {
    active.value = true
    // 【F-A】补偿必做：缓存期被 gate 短路的 queueSave 在此无条件补一次（幂等）。
    void Promise.resolve().then(() => {
      if (active.value) autosave.queueSave()
    })
  })
  onDeactivated(() => { active.value = false })

  // 6. 平台规范（小红书契约：50-1000 字、标题 20 字、imageSpec 3:4·1080×1440）。
  const format = useArticleFormatRule({
    platform: engine.platform,
    selectedTitle: engine.selectedTitle,
    content: engine.content,
    mustInclude: autosave.mustInclude,
  })

  // 7. 四步状态机（内部接线 F-B 两个重映射钩子：restoredProjectId 重算 / handoff revision 重置）。
  const steps = useXhsStudioSteps({
    article: engine,
    restoredProjectId: autosave.restoredProjectId,
    handoffRevision: () => options.handoff()?.revision,
  })

  // 8.【F-C】非小红书草稿直链：applyProject 会回填原平台且 styleSkillsActive/imagesStageSkipped
  //    不随平台回退——恢复完成后校验，非 xiaohongshu 即清空会话回空态并提示（不保存）。
  const toast = ref('')
  let toastTimer: ReturnType<typeof setTimeout> | null = null
  function notify(message: string): void {
    toast.value = message
    if (toastTimer) clearTimeout(toastTimer)
    toastTimer = setTimeout(() => { toast.value = '' }, TOAST_MS)
  }

  watch(autosave.restoredProjectId, (id) => {
    if (!id || engine.platform.value === 'xiaohongshu') return
    notify('该草稿不属于小红书，请从创作中心用对应平台打开')
    // 先清引擎再 startNew：空 topic 令 startNew 内 flush 的 isValidInput 拦截，
    // 不会把非小红书草稿内容误存成新草稿（方案 F-C「不保存」语义）。
    engine.reset()
    engine.platform.value = 'xiaohongshu'
    engine.styleSkillsActive.value = true
    engine.imagesStageSkipped.value = true
    steps.go('pick')
    void autosave.startNew()
  })

  // 9. 左栏调参（挂载即拉取风格目录）。
  const tuning = useXhsStudioTuning(engine)

  // 10. 图卡上下文同步（对齐旧视图 :371-372 同款）。
  watch(autosave.contextSnapshotId, value => { cards.setContextSnapshotId(value ?? '') }, { immediate: true })
  watch(() => engine.brief.value, value => { cards.setBrief(value ?? undefined) })

  // 11.【F6】导出前置 flush：成功且 draftId 存在 → draftVersion（等价 useArticleStudio 同款实现）。
  const beforeExport = async (): Promise<number | false> =>
    await autosave.flush() && autosave.draftId.value ? autosave.draftVersion.value : false

  const platformLocked = computed(() => autosave.platformLocked.value)
  const fromCreationCenter = computed(() => options.handoff() != null)

  /** 确认后清空会话回选题步（锁定会话保留平台，其余状态照常清空）。 */
  async function resetSession(): Promise<void> {
    if (!window.confirm('重新开始将清空当前生成内容，确定继续？')) return
    if (!await autosave.startNew()) return
    engine.reset({ keepPlatform: true })
    autosave.resetCards()
    steps.go('pick')
  }

  const context: XhsStudioContext = {
    engine, cards, autosave, format, steps, tuning, beforeExport,
    platformLocked, fromCreationCenter, toast, notify,
    goCreationCenter: options.goCreationCenter, resetSession,
  }
  provide(XHS_STUDIO_CONTEXT_KEY, context)
  return context
}

/** 并行组件标准注入方式：inject 缺失即 throw（测试可显式 provide mock）。 */
export function useXhsStudioContext(): XhsStudioContext {
  const context = inject(XHS_STUDIO_CONTEXT_KEY)
  if (!context) throw new Error('useXhsStudioContext() 须在 provideXhsStudioContext() 的视图内使用')
  return context
}
