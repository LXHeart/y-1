import { computed, ref, watch } from 'vue'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import type { useImageAnalysis } from '../../../composables/useImageAnalysis'
import type { CreationBrief, CreationDeliveryContract } from '../../../types/creation'
import type { CreationHandoff } from '../../../types/ai-creation'
import type { GenerationStage } from '../../../types/image-analysis'
import { buildCreationBrief } from '../../../lib/creation-brief'
import { useWorkspaceAutosave } from '../../ai-center/creation/useWorkspaceAutosave'
import { useWorkspaceHandoff, useWorkspaceSource } from '../../ai-center/creation/useWorkspaceHandoff'

export function useReviewWorkspace(review: ReturnType<typeof useImageAnalysis>,
  route: RouteLocationNormalizedLoaded, handoff: () => CreationHandoff | null | undefined) {
  const { result, reviewLength, feelings, brief, platform, generationStage, isEditing, editTitle, editReview, editTags } = review
  const source = useWorkspaceSource()
  const topic = ref('')
  /** 交付字段：标题/正文由点评编辑器派生；话题（点评标签）可独立编辑（AI改造-02 §2.4）。 */
  const deliveryDraft = ref<Partial<CreationDeliveryContract>>({})
  const currentTitle = computed(() => (isEditing.value ? editTitle.value : result.value?.title ?? ''))
  const currentBody = computed(() => (isEditing.value ? editReview.value : result.value?.review ?? ''))
  const currentTags = computed(() => (isEditing.value ? editTags.value : result.value?.tags ?? []))
  const deliveryValue = computed<Partial<CreationDeliveryContract>>(() => ({
    titleOrOpening: deliveryDraft.value.titleOrOpening || currentTitle.value,
    bodyOrDescription: deliveryDraft.value.bodyOrDescription || currentBody.value,
    topics: deliveryDraft.value.topics?.length ? deliveryDraft.value.topics : [...currentTags.value],
    summary: deliveryDraft.value.summary,
    shareCopy: deliveryDraft.value.shareCopy,
  }))
  function updateDelivery(value: Partial<CreationDeliveryContract>): void {
    const derived = deliveryValue.value
    deliveryDraft.value = {
      titleOrOpening: value.titleOrOpening && value.titleOrOpening !== derived.titleOrOpening ? value.titleOrOpening : '',
      bodyOrDescription: value.bodyOrDescription && value.bodyOrDescription !== derived.bodyOrDescription
        ? value.bodyOrDescription : '',
      topics: value.topics && value.topics.join(' ') !== (derived.topics ?? []).join(' ') ? value.topics : undefined,
      summary: value.summary ?? '',
      shareCopy: value.shareCopy ?? '',
    }
  }
  const resumableSteps: Partial<Record<GenerationStage, GenerationStage>> = {
    drafting: 'idle', optimizing: 'draft-review', 'style-refining': 'optimize-review',
  }
  const currentStep = computed({
    get: () => resumableSteps[generationStage.value] ?? generationStage.value,
    set: (step: string) => { generationStage.value = step as GenerationStage },
  })
  const autosave = useWorkspaceAutosave({
    capability: 'image', workflow: 'review-copy', delivery: true,
    steps: ['idle', 'draft-review', 'optimize-review', 'complete'], currentStep,
    collectInputs: () => ({ ...source.collectInputs(), review: { reviewLength: reviewLength.value, feelings: feelings.value,
      tags: isEditing.value ? editTags.value : result.value?.tags ?? [], imageCount: result.value?.imageCount ?? 0 } }),
    collectBrief: () => brief.value ?? undefined,
    collectSource: source.collectSource,
    collectDelivery: () => deliveryValue.value,
    collectDraftFields: () => ({ topic: topic.value, platform: platform.value, contentForm: 'graphic',
      articleTitle: isEditing.value ? editTitle.value : result.value?.title ?? '',
      content: isEditing.value ? editReview.value : result.value?.review ?? '' }),
    collectRunIds: () => result.value?.runId ? [result.value.runId] : [],
    applyInputs: () => {},
    applyProject: (project) => {
      source.restore(project)
      const inputs = project.workspace?.inputs ?? {}
      const saved = (inputs.review ?? {}) as Record<string, unknown>
      topic.value = project.topic ?? ''
      platform.value = project.platform === 'dianping' ? 'dianping' : 'taobao'
      reviewLength.value = typeof saved.reviewLength === 'number' ? saved.reviewLength : 0
      feelings.value = typeof saved.feelings === 'string' ? saved.feelings : ''
      brief.value = (inputs.brief ?? null) as CreationBrief | null
      result.value = project.content ? { review: project.content, title: project.articleTitle ?? '',
        tags: Array.isArray(saved.tags) ? saved.tags.filter((tag): tag is string => typeof tag === 'string') : [],
        imageCount: typeof saved.imageCount === 'number' ? saved.imageCount : 0,
        runId: project.runIds?.[project.runIds.length - 1] } : null
      if (isEditing.value) {
        editTitle.value = project.articleTitle ?? ''
        editReview.value = project.content ?? ''
        editTags.value = [...(result.value?.tags ?? [])]
      }
      const delivery = project.workspace?.delivery
      deliveryDraft.value = {
        titleOrOpening: delivery?.titleOrOpening ?? '',
        bodyOrDescription: delivery?.bodyOrDescription ?? '',
        topics: delivery?.topics?.length ? [...delivery.topics] : undefined,
        summary: delivery?.summary ?? '',
        shareCopy: delivery?.shareCopy ?? '',
      }
      review.bindCreationContext(project.sourceType === 'task', source.contextSnapshotId.value)
    },
    isValidInput: () => Boolean(topic.value.trim() || result.value?.review.trim() || feelings.value.trim() || brief.value),
    deriveTitle: () => (result.value?.title || topic.value || '图片评价').trim().slice(0, 60),
    restoreRouteDraftId: () => typeof route?.query.draft === 'string' ? route.query.draft : null,
    engage: () => document.documentElement.dataset.app === 'ai',
  })
  watch([result, reviewLength, feelings, brief, platform, currentStep, editTitle, editReview, editTags, isEditing],
    () => autosave.queueSave(), { deep: true })
  useWorkspaceHandoff({ handoff, target: 'image', autosave, cancel: () => { if (review.loading.value) review.cancel() },
    apply: (next) => {
      review.reset()
      source.accept(next)
      topic.value = next.prefill?.topic ?? ''
      platform.value = 'dianping'
      brief.value = next.brief ?? buildCreationBrief(next, next.platformId, next.contentFormId,
        topic.value, next.prefill?.instructions ?? '')
      review.bindCreationContext(next.source.type === 'task', next.contextSnapshotId)
    },
  })
  async function resetWorkspace(): Promise<boolean> {
    if (!await autosave.startNew()) return false
    const previousPlatform = platform.value
    review.reset()
    deliveryDraft.value = {}
    if (source.locked.value) platform.value = previousPlatform
    topic.value = ''
    review.bindCreationContext(source.source.value.type === 'task', source.contextSnapshotId.value)
    return true
  }
  return { ...autosave, platformLocked: source.locked, resetWorkspace, deliveryValue, updateDelivery }
}
