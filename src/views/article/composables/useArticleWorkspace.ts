import { computed, ref, watch } from 'vue'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import type { useArticleCreation } from '../../../composables/useArticleCreation'
import type { CardSeriesWorkspaceState, useCardSeries } from '../../../composables/useCardSeries'
import type { CreationBrief, CreationDeliveryContract, CreationResultRef } from '../../../types/creation'
import type { AiPlatformId, CreationHandoff } from '../../../types/ai-creation'
import { useWorkspaceAutosave } from '../../ai-center/creation/useWorkspaceAutosave'
import { useWorkspaceHandoff, useWorkspaceSource } from '../../ai-center/creation/useWorkspaceHandoff'
import { buildCreationBrief } from '../../../lib/creation-brief'
import { parseHashtagTopics } from '../../../lib/creation-delivery'

export function useArticleWorkspace(article: ReturnType<typeof useArticleCreation>,
  route: RouteLocationNormalizedLoaded, handoff: () => CreationHandoff | null | undefined,
  cards: ReturnType<typeof useCardSeries>) {
  const { stage, topic, platform, selectedTitle, outline, content, contentMode, question, questionRef,
    titleFormula, genre, style, brief } = article
  const source = useWorkspaceSource()
  /** 用户编辑过的交付字段（发布描述/话题/摘要/分享配文）；未编辑字段由正文派生。 */
  const deliveryDraft = ref<Partial<CreationDeliveryContract>>({})
  /** 面板绑定视图：草稿值优先，缺省回落到当前正文/标题派生。 */
  const deliveryValue = computed<Partial<CreationDeliveryContract>>(() => ({
    titleOrOpening: deliveryDraft.value.titleOrOpening
      || (contentMode.value === 'answer' ? selectedTitle.value || question.value : selectedTitle.value || topic.value),
    bodyOrDescription: deliveryDraft.value.bodyOrDescription || content.value,
    topics: deliveryDraft.value.topics?.length ? deliveryDraft.value.topics : parseHashtagTopics(content.value),
    summary: deliveryDraft.value.summary,
    shareCopy: deliveryDraft.value.shareCopy,
  }))
  /** 编辑粘性按字段：与派生值一致的字段解除粘住，正文变化可继续跟随。 */
  function updateDelivery(value: Partial<CreationDeliveryContract>): void {
    const derived = deliveryValue.value
    deliveryDraft.value = {
      titleOrOpening: value.titleOrOpening && value.titleOrOpening !== derived.titleOrOpening
        ? value.titleOrOpening : '',
      bodyOrDescription: value.bodyOrDescription && value.bodyOrDescription !== derived.bodyOrDescription
        ? value.bodyOrDescription : '',
      topics: value.topics && value.topics.join(' ') !== (derived.topics ?? []).join(' ')
        ? value.topics : undefined,
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
      ...source.collectInputs(),
    }),
    omitInputKeys: ['topic', 'platform', 'selectedTitle', 'articleTitle', 'outline', 'content',
      'contentMode', 'question', 'questionText', 'questionRef', 'answerOpening'],
    collectBrief: () => brief.value ?? undefined,
    collectSource: source.collectSource,
    collectStatus: () => article.completed.value ? 'completed' : content.value ? 'in_progress' : 'draft',
    collectResultRefs: () => {
      const refs: CreationResultRef[] = []
      const cardIds = cards.cards.value
      for (const card of cardIds) {
        const mediaId = card.cardId ? cards.persistedMediaIds.value[card.cardId] : undefined
        if (!mediaId || !card.cardId) continue
        refs.push({
          id: mediaId, refType: 'media', role: 'card', cardId: card.cardId,
          position: card.position,
        })
      }
      return refs
    },
    collectDelivery: () => deliveryValue.value,
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
      const delivery = project.workspace?.delivery
      deliveryDraft.value = {
        titleOrOpening: delivery?.titleOrOpening ?? '',
        bodyOrDescription: delivery?.bodyOrDescription ?? '',
        topics: delivery?.topics?.length ? [...delivery.topics] : undefined,
        summary: delivery?.summary ?? '',
        shareCopy: delivery?.shareCopy ?? '',
      }
    },
    isValidInput: () => Boolean(topic.value.trim() || question.value.trim() || content.value.trim()),
    deriveTitle: () => (contentMode.value === 'answer' ? question.value : selectedTitle.value || topic.value).trim().slice(0, 60),
    restoreRouteDraftId: () => typeof route.query.draft === 'string' ? route.query.draft : null,
    engage: () => document.documentElement.dataset.app === 'ai',
  })
  // 图卡实例的平台随文章平台同步（小红书/抖音流才有面板，其余平台隐藏）。
  watch(platform, value => { cards.platform.value = value }, { immediate: true })
  watch([topic, platform, selectedTitle, outline, content, contentMode, question, questionRef,
    titleFormula, genre, style, brief, stage, article.completed, cards.cards, cards.results,
    cards.persistedMediaIds, deliveryDraft], () => autosave.queueSave(), { deep: true })
  useWorkspaceHandoff({ handoff, target: 'article', autosave, cancel: article.cancel,
    apply: (next) => {
      article.reset({ keepPlatform: true })
      question.value = ''
      questionRef.value = ''
      deliveryDraft.value = {}
      cards.reset()
      source.accept(next)
      article.setTopic(next.prefill?.topic ?? '')
      article.setBrief(next.brief ?? buildCreationBrief(next, next.platformId, next.contentFormId,
        next.prefill?.topic ?? '', next.prefill?.instructions ?? ''))
      article.bindCreationContext(next.source.type === 'task', next.contextSnapshotId, next.platformId)
      const target = next.platformId === 'wechat-official' ? 'wechat' : next.platformId
      if (target === 'wechat' || target === 'zhihu' || target === 'xiaohongshu' || target === 'douyin') platform.value = target
      article.setContentMode(platform.value === 'zhihu' ? 'answer' : 'article')
      if (source.questionLocked.value) article.setQuestion(next.taskContext?.questionText?.trim() ?? '')
    },
  })
  return { ...autosave, platformLocked: source.locked, taskQuestionLocked: source.questionLocked, mustInclude: source.mustInclude,
    deliveryDraft, deliveryValue, updateDelivery, resetCards: cards.reset, contextSnapshotId: source.contextSnapshotId }
}
