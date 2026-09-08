import { computed, onScopeDispose, ref, watch } from 'vue'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import type { useVideoProduction } from '../../../composables/useVideoProduction'
import type { CreationBrief, CreationDeliveryContract } from '../../../types/creation'
import type { CreationHandoff } from '../../../types/ai-creation'
import type { StoryboardShot, VideoProductionForm } from '../../../types/video-production'
import { buildCreationBrief } from '../../../lib/creation-brief'
import { useWorkspaceAutosave } from '../../ai-center/creation/useWorkspaceAutosave'
import { useWorkspaceHandoff, useWorkspaceSource } from '../../ai-center/creation/useWorkspaceHandoff'

export function useVideoWorkspace(video: ReturnType<typeof useVideoProduction>, route: RouteLocationNormalizedLoaded,
  handoff: () => CreationHandoff | null | undefined, clearOptionalInputs: () => void) {
  const source = useWorkspaceSource()
  const topic = ref('')
  const productionTaskId = ref('')
  let loadedReferences = ''
  /** 交付字段（AI改造-03 §3.4）：描述/话题/分享配文独立编辑，改配文不触发视频重生成。 */
  const deliveryDraft = ref<Partial<CreationDeliveryContract>>({})
  const deliveryValue = computed<Partial<CreationDeliveryContract>>(() => ({
    titleOrOpening: deliveryDraft.value.titleOrOpening || topic.value || video.form.value.shopName,
    bodyOrDescription: deliveryDraft.value.bodyOrDescription || '',
    topics: deliveryDraft.value.topics,
    summary: deliveryDraft.value.summary,
    shareCopy: deliveryDraft.value.shareCopy || '',
  }))
  function updateDelivery(value: Partial<CreationDeliveryContract>): void {
    const derived = deliveryValue.value
    deliveryDraft.value = {
      titleOrOpening: value.titleOrOpening && value.titleOrOpening !== derived.titleOrOpening ? value.titleOrOpening : '',
      bodyOrDescription: value.bodyOrDescription ?? '',
      topics: value.topics && value.topics.join(' ') !== (derived.topics ?? []).join(' ') ? value.topics : undefined,
      summary: value.summary ?? '',
      shareCopy: value.shareCopy ?? '',
    }
  }
  const autosave = useWorkspaceAutosave({
    capability: 'video', workflow: 'video-script', delivery: true,
    steps: ['upload', 'storyboard', 'generate', 'compose'], currentStep: video.stage,
    collectInputs: () => {
      const { brief: _brief, targetPlatform: _platform, ...form } = video.form.value
      return { ...source.collectInputs(), video: { form, storyboardId: video.storyboardId.value,
        productionTaskId: video.task.value?.id ?? productionTaskId.value, referenceShotStructure: video.referenceShotStructure.value,
        shots: video.shots.value.map(({ anchorUrl: _url, ...shot }) => shot) } }
    },
    collectBrief: () => video.form.value.brief,
    collectSource: source.collectSource,
    collectDelivery: () => deliveryValue.value,
    collectDraftFields: () => ({ topic: topic.value || video.form.value.shopName, platform: video.form.value.targetPlatform,
      contentForm: source.contentForm.value === 'video-text' ? 'video-text' : 'video' }),
    applyInputs: () => {},
    applyProject: (project) => {
      source.restore(project)
      const inputs = project.workspace?.inputs ?? {}
      const saved = (inputs.video ?? {}) as { form?: Partial<VideoProductionForm>; storyboardId?: string;
        productionTaskId?: string; shots?: StoryboardShot[]; referenceShotStructure?: typeof video.referenceShotStructure.value }
      topic.value = project.topic ?? ''
      video.form.value = { ...video.form.value, ...saved.form,
        targetPlatform: (project.platform ?? '') as VideoProductionForm['targetPlatform'], brief: inputs.brief as CreationBrief | undefined }
      const anchors = new Map(video.shots.value.map(shot => [shot.id, shot]))
      video.shots.value = Array.isArray(saved.shots) ? saved.shots.map(shot => ({ ...shot,
        anchorUrl: anchors.get(shot.id)?.anchorMediaId === shot.anchorMediaId ? anchors.get(shot.id)?.anchorUrl : null })) : []
      video.storyboardId.value = saved.storyboardId ?? ''
      productionTaskId.value = saved.productionTaskId ?? ''
      video.referenceShotStructure.value = saved.referenceShotStructure ?? null
      video.bindCreationContext(project.sourceType === 'task', source.contextSnapshotId.value)
      const referenceKey = `${project.id}:${saved.storyboardId ?? ''}:${saved.productionTaskId ?? ''}`
      const delivery = project.workspace?.delivery
      deliveryDraft.value = {
        titleOrOpening: delivery?.titleOrOpening ?? '',
        bodyOrDescription: delivery?.bodyOrDescription ?? '',
        topics: delivery?.topics?.length ? [...delivery.topics] : undefined,
        summary: delivery?.summary ?? '',
        shareCopy: delivery?.shareCopy ?? '',
      }
      if (referenceKey !== loadedReferences) {
        loadedReferences = referenceKey
        void video.restoreWorkspaceReferences(saved.storyboardId, saved.productionTaskId)
      }
    },
    isValidInput: () => Boolean(topic.value.trim() || video.form.value.shopName.trim() || video.form.value.customPrompt.trim() || video.storyboardId.value),
    deriveTitle: () => (topic.value || video.form.value.shopName || '视频创作').trim().slice(0, 60),
    restoreRouteDraftId: () => typeof route?.query.draft === 'string' ? route.query.draft : null,
    engage: () => document.documentElement.dataset.app === 'ai',
  })
  watch([video.form, video.shots, video.stage, video.storyboardId, () => video.task.value?.id, video.referenceShotStructure],
    () => autosave.queueSave(), { deep: true })
  useWorkspaceHandoff({ handoff, target: 'video-production', autosave, cancel: video.suspend,
    apply: (next) => {
      video.reset()
      clearOptionalInputs()
      loadedReferences = ''
      productionTaskId.value = ''
      deliveryDraft.value = {}
      source.accept(next)
      topic.value = next.prefill?.topic ?? ''
      video.form.value = { ...video.form.value, targetPlatform: next.platformId,
        shopName: next.prefill?.storeName ?? '', shopAddress: next.prefill?.address ?? '', shopDescription: next.prefill?.storeDescription ?? '',
        customPrompt: [topic.value ? `创作主题：${topic.value}` : '', next.prefill?.instructions ?? ''].filter(Boolean).join('\n'),
        brief: next.brief ?? buildCreationBrief(next, next.platformId, next.contentFormId, topic.value, next.prefill?.instructions ?? '') }
      video.bindCreationContext(next.source.type === 'task', next.contextSnapshotId)
    },
  })
  async function resetWorkspace(): Promise<void> {
    video.suspend()
    if (!await autosave.startNew()) return
    video.reset()
    clearOptionalInputs()
    topic.value = ''
    deliveryDraft.value = {}
    loadedReferences = ''
    productionTaskId.value = ''
  }
  onScopeDispose(video.suspend)
  return { ...autosave, resetWorkspace, deliveryValue, updateDelivery,
    retryReferences: () => video.restoreWorkspaceReferences(video.storyboardId.value, productionTaskId.value) }
}
