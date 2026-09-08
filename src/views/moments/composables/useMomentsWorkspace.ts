import { computed, ref, watch } from 'vue'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import { MOMENTS_STYLES, type MomentsCaption, type MomentsOrderSuggestion, type useMomentsCreation } from '../../../composables/useMomentsCreation'
import type { CreationBrief, CreationDeliveryContract } from '../../../types/creation'
import type { CreationHandoff } from '../../../types/ai-creation'
import { buildCreationBrief } from '../../../lib/creation-brief'
import { useWorkspaceAutosave } from '../../ai-center/creation/useWorkspaceAutosave'
import { useWorkspaceHandoff, useWorkspaceSource } from '../../ai-center/creation/useWorkspaceHandoff'

export function useMomentsWorkspace(moments: ReturnType<typeof useMomentsCreation>,
  route: RouteLocationNormalizedLoaded, handoff: () => CreationHandoff | null | undefined) {
  const { topic, style, feelings, brief, result } = moments
  const source = useWorkspaceSource()
  /** 分享配文等交付字段：文案主体（共用短文案）由 result.copy 派生（AI改造-03 §3.4）。 */
  const deliveryDraft = ref<Partial<CreationDeliveryContract>>({})
  const deliveryValue = computed<Partial<CreationDeliveryContract>>(() => ({
    titleOrOpening: deliveryDraft.value.titleOrOpening || '',
    bodyOrDescription: deliveryDraft.value.bodyOrDescription || result.value?.copy || '',
    topics: deliveryDraft.value.topics,
    summary: deliveryDraft.value.summary,
    shareCopy: deliveryDraft.value.shareCopy || '',
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
  const autosave = useWorkspaceAutosave({
    capability: 'moments', delivery: true, steps: ['compose'], currentStep: ref('compose'),
    collectInputs: () => ({ ...source.collectInputs(), moments: { style: style.value, feelings: feelings.value,
      imageOrder: result.value?.imageOrder ?? [], captions: result.value?.captions ?? [] } }),
    omitInputKeys: ['topic', 'style', 'feelings', 'content'],
    collectBrief: () => brief.value ?? undefined,
    collectDelivery: () => deliveryValue.value,
    collectDraftFields: () => ({ topic: topic.value, platform: 'moments', contentForm: 'image-text', content: result.value?.copy ?? '' }),
    collectSource: source.collectSource,
    applyInputs: () => {},
    applyProject: (project) => {
      source.restore(project)
      const inputs = project.workspace?.inputs ?? {}
      const saved = (inputs.moments ?? inputs) as Record<string, unknown>
      topic.value = project.topic ?? (typeof inputs.topic === 'string' ? inputs.topic : '')
      style.value = MOMENTS_STYLES.find(item => item.id === saved.style)?.id ?? ''
      feelings.value = typeof saved.feelings === 'string' ? saved.feelings : ''
      brief.value = (inputs.brief ?? project.workspace?.brief ?? null) as CreationBrief | null
      result.value = project.content ? { copy: project.content,
        imageOrder: Array.isArray(saved.imageOrder) ? saved.imageOrder as MomentsOrderSuggestion[] : [],
        captions: Array.isArray(saved.captions) ? saved.captions as MomentsCaption[] : [] } : null
      const delivery = project.workspace?.delivery
      deliveryDraft.value = {
        titleOrOpening: delivery?.titleOrOpening ?? '',
        bodyOrDescription: delivery?.bodyOrDescription ?? '',
        topics: delivery?.topics?.length ? [...delivery.topics] : undefined,
        summary: delivery?.summary ?? '',
        shareCopy: delivery?.shareCopy ?? '',
      }
      moments.bindCreationContext(project.sourceType === 'task', source.contextSnapshotId.value)
    },
    isValidInput: () => Boolean(topic.value.trim() || result.value?.copy.trim() || brief.value?.extraInstructions?.trim()),
    deriveTitle: () => topic.value.trim().slice(0, 60),
    restoreRouteDraftId: () => typeof route?.query.draft === 'string' ? route.query.draft : null,
    engage: () => document.documentElement.dataset.app === 'ai',
  })
  watch([topic, style, feelings, brief, result], () => autosave.queueSave(), { deep: true })
  useWorkspaceHandoff({ handoff, target: 'moments', autosave, cancel: moments.cancel,
    apply: (next) => {
      moments.reset()
      source.accept(next)
      topic.value = next.prefill?.topic || next.prefill?.storeName || ''
      brief.value = next.brief ?? buildCreationBrief(next, next.platformId, next.contentFormId,
        topic.value, next.prefill?.instructions ?? '')
      moments.bindCreationContext(next.source.type === 'task', next.contextSnapshotId)
    },
  })
  async function resetWorkspace(): Promise<void> {
    moments.cancel()
    if (!await autosave.startNew()) return
    moments.reset()
    deliveryDraft.value = {}
    moments.bindCreationContext(source.source.value.type === 'task', source.contextSnapshotId.value)
  }
  return { ...autosave, resetWorkspace, deliveryValue, updateDelivery }
}
