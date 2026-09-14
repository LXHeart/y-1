import { computed, ref, watch } from 'vue'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import type { useArticleCreation } from '../../../composables/useArticleCreation'
import type { CardSeriesWorkspaceState, useCardSeries } from '../../../composables/useCardSeries'
import type {
  CreationBrief, CreationDeliveryContract, CreationProject, CreationResultRef,
} from '../../../types/creation'
import type { StudioWorkspaceRefs } from '../../../types/creation-studio'
import type { AiPlatformId, CreationHandoff } from '../../../types/ai-creation'
import { useWorkspaceAutosave } from '../../ai-center/creation/useWorkspaceAutosave'
import { useWorkspaceHandoff, useWorkspaceSource } from '../../ai-center/creation/useWorkspaceHandoff'
import { buildCreationBrief } from '../../../lib/creation-brief'
import { parseHashtagTopics } from '../../../lib/creation-delivery'
import { studioPost, studioErrorMessage } from '../../../lib/creation-studio-http'

export function useArticleWorkspace(article: ReturnType<typeof useArticleCreation>,
  route: RouteLocationNormalizedLoaded, handoff: () => CreationHandoff | null | undefined,
  cards: ReturnType<typeof useCardSeries>) {
  const { stage, topic, platform, selectedTitle, outline, content, contentMode, question, questionRef,
    titleFormula, genre, style, brief } = article
  const source = useWorkspaceSource()
  /**
   * 任务书 #101 C101-03：studio 引用（workspace.inputs.studio，§6.2 StudioWorkspaceRefs）。
   * 工作区只保留稳定引用——原稿正文/块在服务端；未知 schemaVersion 读侧只读（不覆盖）。
   */
  const emptyStudio = (): StudioWorkspaceRefs => ({
    schemaVersion: 1, recipe: null, sourceDocumentId: null,
    visualPlan: null, activeVisualJobId: null, lastProposalId: null, renderTheme: 'standard',
  })
  const studio = ref<StudioWorkspaceRefs>(emptyStudio())
  const preparingSource = ref(false)
  async function ensureDraftForSource(): Promise<boolean> {
    preparingSource.value = true
    try { return await autosave.flush() && Boolean(autosave.draftId.value) }
    finally { preparingSource.value = false }
  }
  /** 来源导入：写入引用并随共享保存队列落草稿（§6.4：不隐式改写草稿）。 */
  function setStudioSource(documentId: string, recipe?: StudioWorkspaceRefs['recipe']): void {
    studio.value = { ...studio.value, sourceDocumentId: documentId, ...(recipe ? { recipe } : {}) }
  }
  /**
   * 任务书 #101 C101-06：新建视觉计划落引用。PATCH 重修订／确认不更新此引用——
   * revision 以服务端 GET 读取为准，避免每次保存触发草稿写（§6.2）。
   */
  function setStudioPlan(plan: { id: string; revision: number }): void {
    studio.value = { ...studio.value, visualPlan: { id: plan.id, revision: plan.revision } }
  }
  /** 任务书 #101 C101-11：当前视觉任务落引用——刷新后按 ID 读回继续轮询（采用结果在 12 卡）。 */
  function setStudioJob(jobId: string): void {
    if (studio.value.activeVisualJobId === jobId) return
    studio.value = { ...studio.value, activeVisualJobId: jobId }
  }
  /**
   * 任务书 #101 C101-12：服务端采用写回的引用快照（API101-17 原子采用后经 applyProject 捕获）。
   * 后续客户端保存以此为准合并——不在采用路径上的旧图卡引用不回擦已采用结果。
   */
  const serverResultRefs = ref<CreationResultRef[]>([])
  const serverDeliveryMedia = ref<{ coverRef: CreationResultRef | null; mediaRefs: CreationResultRef[] }>(
    { coverRef: null, mediaRefs: [] })
  /** 已采用媒体 ID 集（制作面板「已采用」标记）。 */
  const adoptedMediaIds = computed(() => new Set([
    ...serverDeliveryMedia.value.mediaRefs.map((ref) => ref.id),
    ...(serverDeliveryMedia.value.coverRef ? [serverDeliveryMedia.value.coverRef.id] : []),
  ]))
  /** 采用中状态与错误（§8.2 请求中禁用按钮）。 */
  const adopting = ref(false)
  const adoptError = ref('')
  /** C101-12：采用视觉候选（API101-17）——服务端写 resultRefs/coverRef，成功后 adopt 服务器版本。 */
  async function adoptVisualArtifacts(input: {
    planId: string
    planRevision: number
    selections: Array<{ itemId: string; artifactId: string }>
  }): Promise<boolean> {
    if (adopting.value) return false
    adopting.value = true
    adoptError.value = ''
    try {
      const result = await autosave.runExternalMutation<boolean>(async (expectedVersion) => {
        const data = await studioPost<{ project: CreationProject; alreadyApplied: boolean }>(
          `/api/creation-studio/visual-plans/${input.planId}/adopt`, {
            requestId: crypto.randomUUID(),
            draftId: autosave.draftId.value,
            expectedDraftVersion: expectedVersion,
            expectedPlanRevision: input.planRevision,
            selections: input.selections,
          })
        return { project: data.project, value: data.alreadyApplied }
      })
      return result != null
    } catch (failure) {
      adoptError.value = studioErrorMessage(failure)
      return false
    } finally {
      adopting.value = false
    }
  }
  /** 用户编辑过的交付字段（发布描述/话题/摘要/分享配文）；未编辑字段由正文派生。 */
  const deliveryDraft = ref<Partial<CreationDeliveryContract>>({})
  /** 面板绑定视图：草稿值优先，缺省回落到当前正文/标题派生。 */
  const deliveryValue = computed<Partial<CreationDeliveryContract>>(() => ({
    titleOrOpening: deliveryDraft.value.titleOrOpening
      ?? (contentMode.value === 'answer' ? selectedTitle.value || question.value : selectedTitle.value || topic.value),
    bodyOrDescription: deliveryDraft.value.bodyOrDescription ?? content.value,
    topics: deliveryDraft.value.topics ?? parseHashtagTopics(content.value),
    summary: deliveryDraft.value.summary,
    shareCopy: deliveryDraft.value.shareCopy,
    // C101-12：采用媒体（服务端权威优先；未采用时旧版图卡持久化媒体兜底）
    coverRef: serverDeliveryMedia.value.coverRef ?? undefined,
    mediaRefs: deliveryMediaRefs.value,
  }))
  /** 交付媒体列表：已采用优先；否则旧版已存图卡；两者皆空时不置键。 */
  const deliveryMediaRefs = computed<CreationResultRef[] | undefined>(() => {
    if (serverDeliveryMedia.value.mediaRefs.length) return serverDeliveryMedia.value.mediaRefs
    const legacy: CreationResultRef[] = []
    for (const card of cards.cards.value) {
      const mediaId = card.cardId ? cards.persistedMediaIds.value[card.cardId] : undefined
      if (mediaId && card.cardId) {
        legacy.push({ id: mediaId, refType: 'media', role: 'card', cardId: card.cardId, position: card.position })
      }
    }
    return legacy.length ? legacy : undefined
  })
  /** 保存用户提交的交付字段；空字符串和空话题也是明确的编辑结果。 */
  function updateDelivery(value: Partial<CreationDeliveryContract>): void {
    if (contentMode.value !== 'answer' && value.titleOrOpening !== undefined) selectedTitle.value = value.titleOrOpening
    deliveryDraft.value = {
      titleOrOpening: value.titleOrOpening,
      bodyOrDescription: value.bodyOrDescription,
      topics: value.topics,
      summary: value.summary ?? '',
      shareCopy: value.shareCopy ?? '',
    }
  }
  const autosave = useWorkspaceAutosave({
    capability: 'article', delivery: true,
    steps: ['question', 'topic', 'titles', 'outline', 'content', 'check', 'images'],
    currentStep: stage,
    collectDraftFields: () => ({
      topic: topic.value, platform: platform.value === 'wechat' ? 'wechat-official' : platform.value,
      contentForm: source.contentForm.value, contentMode: contentMode.value,
      questionText: question.value, questionRef: questionRef.value,
      articleTitle: contentMode.value === 'answer' ? '' : selectedTitle.value,
      outline: outline.value, content: content.value,
    }),
    collectInputs: () => ({
      article: { answerOpening: contentMode.value === 'answer' ? selectedTitle.value : '',
        titleFormula: titleFormula.value, genre: genre.value, style: style.value, completed: article.completed.value },
      cards: cards.collectWorkspaceState(),
      ...(studio.value.recipe || studio.value.sourceDocumentId || studio.value.visualPlan
        || studio.value.activeVisualJobId || studio.value.lastProposalId ? { studio: studio.value } : {}),
      ...source.collectInputs(),
    }),
    omitInputKeys: ['topic', 'platform', 'selectedTitle', 'articleTitle', 'outline', 'content',
      'contentMode', 'question', 'questionText', 'questionRef', 'answerOpening'],
    collectBrief: () => brief.value ?? undefined,
    collectSource: source.collectSource,
    collectStatus: () => article.completed.value ? 'completed' : content.value ? 'in_progress' : 'draft',
    collectResultRefs: () => {
      // C101-12：服务端采用的引用是权威（按 cardId 占位）；旧版图卡引用只补充未覆盖的卡。
      const merged: CreationResultRef[] = []
      const seenIds = new Set<string>()
      const seenCardIds = new Set<string>()
      for (const ref of serverResultRefs.value) {
        merged.push(ref)
        seenIds.add(ref.id)
        if (ref.cardId) seenCardIds.add(ref.cardId)
      }
      for (const card of cards.cards.value) {
        const mediaId = card.cardId ? cards.persistedMediaIds.value[card.cardId] : undefined
        if (!mediaId || !card.cardId || seenIds.has(mediaId) || seenCardIds.has(card.cardId)) continue
        merged.push({
          id: mediaId, refType: 'media', role: 'card', cardId: card.cardId,
          position: card.position,
        })
      }
      return merged
    },
    collectDelivery: () => {
      const delivery: Partial<CreationDeliveryContract> = { ...deliveryValue.value }
      // 服务端采用写回的媒体引用随每次保存保留（不因文本编辑回擦；未采用不置键）
      if (serverDeliveryMedia.value.coverRef) delivery.coverRef = serverDeliveryMedia.value.coverRef
      if (serverDeliveryMedia.value.mediaRefs.length) {
        delivery.mediaRefs = serverDeliveryMedia.value.mediaRefs
      }
      return delivery
    },
    applyInputs: () => {},
    applyProject: (project) => {
      source.restore(project)
      const inputs = project.workspace?.inputs ?? {}
      const settings = inputs.article as Record<string, unknown> | undefined
      const savedCards = inputs.cards as Record<string, unknown> | undefined
      const read = (value: unknown) => typeof value === 'string' ? value : ''
      topic.value = project.topic ?? read(inputs.topic)
      const savedPlatform = project.platform ?? inputs.platform
      const mapped = savedPlatform === 'wechat-official' ? 'wechat' : savedPlatform
      if (mapped === 'wechat' || mapped === 'zhihu' || mapped === 'douyin' || mapped === 'xiaohongshu') platform.value = mapped
      contentMode.value = (project.contentMode ?? inputs.contentMode) === 'answer' ? 'answer' : 'article'
      question.value = project.questionText ?? read(inputs.questionText ?? inputs.question)
      questionRef.value = project.questionRef ?? read(inputs.questionRef)
      selectedTitle.value = contentMode.value === 'answer'
        ? read(settings?.answerOpening ?? inputs.answerOpening ?? project.articleTitle ?? inputs.selectedTitle)
        : project.articleTitle ?? read(inputs.articleTitle ?? inputs.selectedTitle)
      outline.value = project.outline ?? read(inputs.outline)
      content.value = project.content ?? read(inputs.content)
      titleFormula.value = read(settings?.titleFormula)
      genre.value = read(settings?.genre)
      style.value = read(settings?.style)
      article.completed.value = settings?.completed === true || project.status === 'completed'
      article.setBrief((inputs.brief ?? project.workspace?.brief ?? null) as CreationBrief | null)
      article.bindCreationContext(project.sourceType === 'task', read(inputs.contextSnapshotId), project.platform as AiPlatformId)
      cards.restoreWorkspaceState(savedCards as Partial<CardSeriesWorkspaceState> | undefined)
      // #101：恢复 studio 引用；未知 schemaVersion 只读降级（不覆盖、不清空）。
      const savedStudio = inputs.studio as Partial<StudioWorkspaceRefs> | undefined
      if (savedStudio && savedStudio.schemaVersion === 1) {
        studio.value = {
          schemaVersion: 1,
          recipe: savedStudio.recipe ?? null,
          sourceDocumentId: typeof savedStudio.sourceDocumentId === 'string' ? savedStudio.sourceDocumentId : null,
          visualPlan: savedStudio.visualPlan ?? null,
          activeVisualJobId: typeof savedStudio.activeVisualJobId === 'string' ? savedStudio.activeVisualJobId : null,
          lastProposalId: typeof savedStudio.lastProposalId === 'string' ? savedStudio.lastProposalId : null,
          renderTheme: savedStudio.renderTheme === 'compact' ? 'compact' : 'standard',
        }
      } else if (!savedStudio) studio.value = emptyStudio()
      const delivery = project.workspace?.delivery
      deliveryDraft.value = {
        titleOrOpening: delivery?.titleOrOpening,
        bodyOrDescription: delivery?.bodyOrDescription,
        topics: delivery?.topics ? [...delivery.topics] : undefined,
        summary: delivery?.summary ?? '',
        shareCopy: delivery?.shareCopy ?? '',
      }
      // C101-12：捕获服务端采用写回的引用快照（后续保存以此为权威合并，不回擦已采用结果）
      serverResultRefs.value = [...(project.workspace?.resultRefs ?? [])]
      serverDeliveryMedia.value = {
        coverRef: delivery?.coverRef ?? null,
        mediaRefs: [...(delivery?.mediaRefs ?? [])],
      }
    },
    isValidInput: () => preparingSource.value || Boolean(topic.value.trim() || question.value.trim() || content.value.trim()),
    deriveTitle: () => (contentMode.value === 'answer' ? question.value : selectedTitle.value || topic.value).trim().slice(0, 60),
    restoreRouteDraftId: () => typeof route.query.draft === 'string' ? route.query.draft : null,
    engage: () => document.documentElement.dataset.app === 'ai'
      || typeof route.query.draft === 'string'
      || (handoff()?.targetView === 'article' && (handoff()?.recipe != null
        || (handoff()?.processingMode != null && handoff()?.processingMode !== 'create'))),
  })
  // 图卡实例的平台随文章平台同步（小红书/抖音流才有面板，其余平台隐藏）。
  watch(platform, value => { cards.platform.value = value }, { immediate: true })
  watch([topic, platform, selectedTitle, outline, content, contentMode, question, questionRef,
    titleFormula, genre, style, brief, stage, article.completed, cards.cards, cards.results,
    cards.persistedMediaIds, deliveryDraft, studio], () => autosave.queueSave(), { deep: true })
  useWorkspaceHandoff({ handoff, target: 'article', autosave, cancel: article.cancel,
    apply: (next) => {
      article.reset({ keepPlatform: true })
      question.value = ''
      questionRef.value = ''
      deliveryDraft.value = {}
      serverResultRefs.value = []
      serverDeliveryMedia.value = { coverRef: null, mediaRefs: [] }
      cards.reset()
      source.accept(next)
      article.setTopic(next.prefill?.topic ?? '')
      article.setBrief(next.brief ?? buildCreationBrief(next, next.platformId, next.contentFormId,
        next.prefill?.topic ?? '', next.prefill?.instructions ?? ''))
      if (brief.value) brief.value = { ...brief.value, processingMode: next.processingMode ?? brief.value.processingMode }
      article.bindCreationContext(next.source.type === 'task', next.contextSnapshotId, next.platformId)
      const target = next.platformId === 'wechat-official' ? 'wechat' : next.platformId
      if (target === 'wechat' || target === 'zhihu' || target === 'xiaohongshu' || target === 'douyin') platform.value = target
      article.setContentMode(platform.value === 'zhihu' ? 'answer' : 'article')
      if (source.questionLocked.value) article.setQuestion(next.taskContext?.questionText?.trim() ?? '')
      // #101：handoff 携带 recipe（从已有内容开始）→ 记入 studio 引用；原稿在正文阶段导入。
      studio.value = { ...emptyStudio(), recipe: next.recipe ?? null }
    },
  })
  return { ...autosave, platformLocked: source.locked, taskQuestionLocked: source.questionLocked, mustInclude: source.mustInclude,
    deliveryDraft, deliveryValue, updateDelivery, resetCards: cards.reset, contextSnapshotId: source.contextSnapshotId,
    studio, setStudioSource, setStudioPlan, setStudioJob, ensureDraftForSource,
    startNew: async () => {
      if (!await autosave.startNew()) return false
      studio.value = emptyStudio()
      serverResultRefs.value = []
      serverDeliveryMedia.value = { coverRef: null, mediaRefs: [] }
      deliveryDraft.value = {}
      return true
    },
    adoptedMediaIds, adoptedResultRefs: serverResultRefs, adopting, adoptError, adoptVisualArtifacts }
}
