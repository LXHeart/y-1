/**
 * 知乎创作台视图上下文装配（对位 xhs-studio useXhsStudioContext，适配点同类）。
 *
 * 装配顺序对齐既有次序，适配点：
 * - 【F1 同款】handoff getter 改写副本：useWorkspaceHandoff 按 targetView==='article'
 *   过滤，targetView='zhihu-studio' 的 handoff 不会被 apply——这里把副本改写为
 *   'article' 仅供 workspace 匹配，不回写 prop。
 * - 【N1/F5 同款】KeepAlive 门控：active + onActivated/onDeactivated，gate 经
 *   useArticleWorkspace 第五参数透传进 useWorkspaceAutosave（缓存期间不写、不收）；
 *   activate 时无条件补偿一次 queueSave（【F-A】封死缓存期后台 SSE 完成不入队的
 *   丢失窗口，fingerprint 幂等不重复入队）。
 * - 【F-C 同款】非知乎草稿直链：恢复完成后校验 engine.platform，非 zhihu 清空会话
 *   回空态并提示（不脏写、不误存——空 topic 被 isValidInput 拦住）。
 * - 知乎差异①：进入即默认「写回答」（旧视图 syncPlatformMode 同款语义：知乎默认
 *   answer；handoff/draft 恢复随后到达时各自覆写 contentMode，不冲突）。
 * - 知乎差异②：风格三选向知乎开放（#62：styleSkillsActive=true）；不接 useCardSeries
 *   （知乎无图卡链），配图走 engine imageSlots 既有 legacy 链（发布步 ArticleImageSlots）。
 * - 知乎差异③：requestContentMode 横切切换（顶栏 seg）——有产物先 confirm，确认后
 *   setContentMode 清空产物并回 pick 步（两套 prompt 产物不可混用，ZhihuModeToggle 语义）。
 */
import { computed, inject, onActivated, onDeactivated, provide, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useArticleCreation } from '../../../composables/useArticleCreation'
import { useCardSeries } from '../../../composables/useCardSeries'
import { useArticleWorkspace } from '../../article/composables/useArticleWorkspace'
import { useArticleFormatRule } from '../../article/composables/useArticleFormatRule'
import { useZhihuStudioSteps } from './useZhihuStudioSteps'
import { useZhihuStudioTuning } from './useZhihuStudioTuning'
import { ZHIHU_STUDIO_CONTEXT_KEY } from '../types'
import type { ZhihuContentMode, ZhihuStudioContext } from '../types'
import type { CreationHandoff } from '../../../types/ai-creation'

export interface ProvideZhihuStudioContextOptions {
  /** 壳层传入的 handoff prop（ZhihuStudioView 的 props.creationHandoff）。 */
  handoff: () => CreationHandoff | null | undefined
  /** 返回创作中心（视图 emit 上抛给壳层路由）。 */
  goCreationCenter: () => void
}

/** Toast 展示时长（与 xhs-studio 同口径：1.9s 定时清除）。 */
const TOAST_MS = 1900

export function provideZhihuStudioContext(options: ProvideZhihuStudioContextOptions): ZhihuStudioContext {
  const route = useRoute()

  // 1. 生成引擎：平台固定知乎；风格三选开放（#62）；不跳配图（回答/文章都走 images 链）。
  const engine = useArticleCreation()
  engine.platform.value = 'zhihu'
  engine.styleSkillsActive.value = true

  // 2. 知乎差异①：默认写回答（handoff apply/draft 恢复随后覆写 contentMode）。
  engine.setContentMode('answer')

  // 3.【F1 同款】handoff 改写副本：副本只改 targetView，revision 原样保留（重复消费
  //    守卫按既有语义工作）。
  const workspaceHandoff = () => {
    const h = options.handoff()
    return h ? { ...h, targetView: 'article' as const } : h
  }

  // 4.【N1/F5 同款】KeepAlive 门控：active 默认 true（非 KeepAlive 宿主/首次挂载即活跃）。
  const active = ref(true)

  // 4b. 图卡实例仅作持久化占位（useArticleWorkspace 的 cards 参数不可空且被序列化/watch
  //     消费）：知乎无图卡 UI，不渲染任何卡片面板，空状态随工作区序列化无害。
  const cards = useCardSeries('zhihu')

  // 5. 持久化底座：capability 仍为 'article'（旧知乎草稿无损恢复）。
  const autosave = useArticleWorkspace(engine, route, workspaceHandoff, cards, () => active.value)

  onActivated(() => {
    active.value = true
    // 【F-A】补偿必做：缓存期被 gate 短路的 queueSave 在此无条件补一次（幂等）。
    void Promise.resolve().then(() => {
      if (active.value) autosave.queueSave()
    })
  })
  onDeactivated(() => { active.value = false })

  // 6. 平台规范（知乎契约：200-3000 字、标题 30 字、imageSpec 无）。
  const format = useArticleFormatRule({
    platform: engine.platform,
    selectedTitle: engine.selectedTitle,
    content: engine.content,
    mustInclude: autosave.mustInclude,
  })

  // 7. 四步状态机（含知乎差异：images/completed → publish）。
  const steps = useZhihuStudioSteps({
    article: engine,
    restoredProjectId: autosave.restoredProjectId,
    handoffRevision: () => options.handoff()?.revision,
  })

  // 8.【F-C 同款】非知乎草稿直链弹回 + toast。
  const toast = ref('')
  let toastTimer: ReturnType<typeof setTimeout> | null = null
  function notify(message: string): void {
    toast.value = message
    if (toastTimer) clearTimeout(toastTimer)
    toastTimer = setTimeout(() => { toast.value = '' }, TOAST_MS)
  }

  watch(autosave.restoredProjectId, (id) => {
    if (!id || engine.platform.value === 'zhihu') return
    notify('该草稿不属于知乎，请从创作中心用对应平台打开')
    // 先清引擎再 startNew：空 topic 令 startNew 内 flush 的 isValidInput 拦截，
    // 不会把非知乎草稿内容误存成新草稿（「不保存」语义）。
    engine.reset()
    engine.platform.value = 'zhihu'
    engine.styleSkillsActive.value = true
    engine.setContentMode('answer')
    steps.go('pick')
    void autosave.startNew()
  })

  // 9. 左栏调参（挂载即拉取风格目录；知乎差异：目录过滤 'zhihu'）。
  const tuning = useZhihuStudioTuning(engine)

  // 10.【导出前置 flush】成功且 draftId 存在 → draftVersion（DeliveryPanel before-export）。
  const beforeExport = async (): Promise<number | false> =>
    await autosave.flush() && autosave.draftId.value ? autosave.draftVersion.value : false

  const platformLocked = computed(() => autosave.platformLocked.value)
  const fromCreationCenter = computed(() => options.handoff() != null)
  const answerMode = computed(() => engine.contentMode.value === 'answer')

  // 11. 知乎差异③：内容形态横切切换（confirm → 清空产物 → 回选题步）。
  function requestContentMode(mode: ZhihuContentMode): void {
    if (autosave.taskQuestionLocked.value || engine.contentMode.value === mode) return
    const hasProducts = engine.titles.value.length > 0 || engine.outline.value.trim() !== ''
      || engine.content.value.trim() !== ''
    if (hasProducts && !window.confirm('切换模式会清空已生成的候选、大纲和正文，确定切换？')) return
    engine.setContentMode(mode)
    steps.go('pick')
  }

  /** 确认后清空会话回选题步（锁定会话保留平台，其余状态照常清空）。 */
  async function resetSession(): Promise<void> {
    if (!window.confirm('重新开始将清空当前生成内容，确定继续？')) return
    if (!await autosave.startNew()) return
    engine.reset({ keepPlatform: true })
    engine.styleSkillsActive.value = true
    engine.setContentMode('answer')
    steps.go('pick')
  }

  const context: ZhihuStudioContext = {
    engine, autosave, format, steps, tuning, toast, notify,
    answerMode, requestContentMode, beforeExport,
    platformLocked, fromCreationCenter,
    goCreationCenter: options.goCreationCenter, resetSession,
  }
  provide(ZHIHU_STUDIO_CONTEXT_KEY, context)
  return context
}

/** 并行组件标准注入方式：inject 缺失即 throw（测试可显式 provide mock）。 */
export function useZhihuStudioContext(): ZhihuStudioContext {
  const context = inject(ZHIHU_STUDIO_CONTEXT_KEY)
  if (!context) throw new Error('useZhihuStudioContext() 须在 provideZhihuStudioContext() 的视图内使用')
  return context
}
