import { computed, ref, watch } from 'vue'
import type { CreationHandoff } from '../../../types/ai-creation'
import type { ImageCapabilities, SourceDocument } from '../../../types/creation-studio'
import type { useArticleCreation } from '../../../composables/useArticleCreation'
import type { useArticleWorkspace } from './useArticleWorkspace'
import { studioPost, studioRequest, studioErrorMessage, useStudioGuard } from '../../../lib/creation-studio-http'
import { useSourceDocument } from './useSourceDocument'
import { useTextProposal } from './useTextProposal'
import { defaultSelectedBlockIds, useVisualPlan, type PreparePlanInput } from './useVisualPlan'
import { useVisualJob } from './useVisualJob'
import { useArticleRender } from './useArticleRender'
import { useArticleUrlState } from './useArticleUrlState'

/** Article UI assembles this flow; data, saves and external actions stay in composables. */
export function useArticleStudio(article: ReturnType<typeof useArticleCreation>,
  autosave: ReturnType<typeof useArticleWorkspace>, handoff: () => CreationHandoff | null | undefined) {
  const { studio } = autosave
  const url = useArticleUrlState(() => autosave.draftId.value)
  const guard = useStudioGuard(() => autosave.draftId.value)
  const importing = ref(false)
  const preparingSource = ref(false)
  const source = ref<SourceDocument | null>(null)
  const selectedBlockIds = ref<string[]>([])
  const sourceError = ref('')
  const capabilityState = ref<{ image: ImageCapabilities; studioEnabled: boolean; wechatEnabled: boolean } | null>(null)
  const capabilitiesError = ref('')
  const requestedProposal = ref<'adapt-body' | 'suggest-metadata'>('adapt-body')
  const formatRevealed = ref(false)
  let pendingSource: { key: string; requestId: string } | null = null
  guard.onInvalidate(() => {
    source.value = null; selectedBlockIds.value = []; sourceError.value = ''
    capabilityState.value = null; capabilitiesError.value = ''; pendingSource = null
    importing.value = false; preparingSource.value = false; formatRevealed.value = false
  })

  const handoffProcessingMode = computed(() => article.brief.value?.processingMode
    ?? handoff()?.processingMode ?? handoff()?.brief?.processingMode ?? 'create')
  const sourceEntryVisible = computed(() => handoffProcessingMode.value !== 'create'
    || studio.value.sourceDocumentId != null)
  const sourceEntryNote = computed(() => handoffProcessingMode.value === 'format'
    ? '排版模式：保留原稿文字，可手动编辑标题与摘要' : '导入后可编辑原稿，再按需发起改编或图片策划')
  const supported = computed(() => ['wechat', 'zhihu', 'xiaohongshu', 'douyin'].includes(article.platform.value))
  const studioExportEnabled = computed(() => supported.value && capabilityState.value?.studioEnabled === true)
  const wechatEnabled = computed(() => studioExportEnabled.value && capabilityState.value?.wechatEnabled === true)
  const editorDisabled = computed(() => autosave.readonly.value || autosave.externalMutationBusy.value
    || importing.value || preparingSource.value)
  const studioWriteDisabled = computed(() => editorDisabled.value || capabilityState.value?.studioEnabled === false
    || Boolean(capabilitiesError.value))

  async function loadCapabilities(): Promise<void> {
    const id = autosave.draftId.value
    if (!id || !supported.value) return
    const valid = guard.capture()
    try {
      const result = await studioRequest<NonNullable<typeof capabilityState.value>>(
        '/api/creation-studio/drafts/' + id + '/capabilities')
      if (valid()) { capabilityState.value = result; capabilitiesError.value = '' }
    } catch (failure) { if (valid()) capabilitiesError.value = studioErrorMessage(failure) }
  }
  watch(() => autosave.draftId.value, () => { void loadCapabilities() }, { immediate: true })

  function bindSource(document: SourceDocument): void {
    source.value = document
    const defaults = defaultSelectedBlockIds(document.blocks)
    selectedBlockIds.value = defaults.length === document.blocks.length ? defaults : []
  }
  const sourceDocument = useSourceDocument({
    draftId: () => autosave.draftId.value,
    onImported: async document => {
      const valid = guard.capture()
      const previousStage = article.stage.value
      const previousContent = article.content.value
      const previousStudio = { ...studio.value }
      article.importContent(document.normalizedMarkdown)
      autosave.setStudioSource(document.id)
      const saved = await autosave.flush()
      if (!valid()) return false
      if (!saved) {
        article.content.value = previousContent; article.stage.value = previousStage; studio.value = previousStudio
        return false
      }
      bindSource(document)
      return true
    },
  })
  async function onSourceImportRequested(input: { kind: 'plain-text' | 'markdown'; text: string }): Promise<void> {
    if (importing.value || autosave.externalMutationBusy.value) return
    importing.value = true
    try {
      // Create an empty project if necessary. Never replace existing text before source creation succeeds.
      if (!await autosave.ensureDraftForSource()) {
        sourceDocument.fail('草稿尚未保存成功，请先处理保存提示再导入')
        return
      }
      importing.value = true
      await sourceDocument.importSource({ ...input, draftId: autosave.draftId.value,
        expectedDraftVersion: autosave.draftVersion.value })
    } finally { importing.value = false }
  }

  async function ensureCurrentSource(): Promise<boolean> {
    if (!await autosave.flush() || !autosave.draftId.value) return false
    const valid = guard.capture()
    const content = article.content.value.replace(/\r\n?/g, '\n')
    if (source.value?.kind !== 'draft-content' || source.value.normalizedMarkdown !== content) {
      const selectedPositions = source.value?.normalizedMarkdown === content
        ? source.value.blocks.filter(block => selectedBlockIds.value.includes(block.id)).map(block => block.position) : []
      const draftId = autosave.draftId.value
      const expectedDraftVersion = autosave.draftVersion.value
      const key = JSON.stringify([draftId, expectedDraftVersion, content])
      if (pendingSource?.key !== key) pendingSource = { key, requestId: crypto.randomUUID() }
      const document = await studioPost<SourceDocument>('/api/creation-studio/sources', {
        requestId: pendingSource.requestId, draftId, expectedDraftVersion, kind: 'draft-content',
      })
      if (!valid() || article.content.value.replace(/\r\n?/g, '\n') !== content) return false
      bindSource(document)
      if (selectedPositions.length) selectedBlockIds.value = document.blocks
        .filter(block => selectedPositions.includes(block.position)).map(block => block.id)
    }
    if (!source.value) return false
    const selected = source.value.blocks.filter(block => selectedBlockIds.value.includes(block.id))
    const length = selected.reduce((sum, block) => sum + [...block.text].length, 0)
    if (!selected.length || selected.length > 200 || length > 8000) {
      sourceError.value = '请选择本次处理范围：最多 200 段、8,000 字符。未选择内容保留在原稿中。'
      return false
    }
    sourceError.value = ''
    return true
  }

  const textProposal = useTextProposal({
    draftId: () => autosave.draftId.value, draftVersion: () => autosave.draftVersion.value,
    sourceDocumentId: () => source.value?.id ?? studio.value.sourceDocumentId,
    runExternalMutation: autosave.runExternalMutation, onApplied: () => {},
    onPrepared: proposal => { void url.remember('studioProposal', proposal.id) },
  })
  watch([url.proposalId, () => studio.value.lastProposalId, () => autosave.draftId.value], ([pendingId, savedId, draftId]) => {
    const id = pendingId ?? savedId
    if (draftId && id && textProposal.current.value?.id !== id) void textProposal.refresh(id)
  }, { immediate: true })
  const proposalAction = computed(() => textProposal.current.value?.action ?? requestedProposal.value)
  const proposalVisible = computed(() => textProposal.current.value != null || textProposal.preparing.value
    || Boolean(textProposal.error.value) || (handoffProcessingMode.value === 'adapt' && article.stage.value === 'content'))
  async function onProposalPrepare(action: 'adapt-body' | 'suggest-metadata'): Promise<void> {
    if (preparingSource.value || textProposal.preparing.value) return
    requestedProposal.value = action
    preparingSource.value = true
    try {
      if (!await ensureCurrentSource() || !source.value) return
      textProposal.bindSource(source.value, selectedBlockIds.value)
      await textProposal.prepare(action)
    } catch (failure) { sourceError.value = studioErrorMessage(failure) }
    finally { preparingSource.value = false }
  }
  async function onProposalApply(id: string, fields: Array<'title' | 'body' | 'summary'>): Promise<void> {
    if (await textProposal.apply(id, fields)) {
      studio.value = { ...studio.value, lastProposalId: id }
      await autosave.flush()
      await url.remember('studioProposal', null)
    }
  }
  function onSuggestSummary(): void { void onProposalPrepare('suggest-metadata') }

  const visualPlan = useVisualPlan({
    draftId: () => autosave.draftId.value, draftVersion: () => autosave.draftVersion.value,
    sourceDocumentId: () => source.value?.id ?? studio.value.sourceDocumentId,
    sourceContentHash: () => source.value?.contentHash ?? null,
    recipe: () => studio.value.recipe?.id !== 'article-format' ? studio.value.recipe : null,
    beforeConfirm: autosave.flush,
    onPlanCreated: plan => {
      if (plan.revision < 1) { void url.remember('studioPlan', plan.id); return }
      if (studio.value.visualPlan?.id === plan.id && studio.value.visualPlan.revision === plan.revision) return
      if (studio.value.visualPlan?.id && studio.value.visualPlan.id !== plan.id) void url.remember('studioJob', null)
      autosave.setStudioPlan({ id: plan.id, revision: plan.revision })
      studio.value = { ...studio.value, activeVisualJobId: null }
      void autosave.flush().then(saved => { if (saved) void url.remember('studioPlan', null) })
    },
  })
  const studioPlanEnabled = computed(() => Boolean(studio.value.visualPlan
    || (studio.value.recipe && studio.value.recipe.id !== 'article-format')))
  const visualRecipe = computed(() => visualPlan.current.value?.document?.recipe.id ?? studio.value.recipe?.id)
  const cardStudioEnabled = computed(() => visualRecipe.value === 'social-card-series')
  const articleVisualEnabled = computed(() => visualRecipe.value === 'article-visuals' || visualRecipe.value === 'cover-only')

  async function onPrepareVisualPlan(input: PreparePlanInput = {}, fresh = false): Promise<void> {
    if (preparingSource.value || visualPlan.preparing.value) return
    preparingSource.value = true
    try {
      if (!await ensureCurrentSource() || !source.value) return
      visualPlan.bindSource(source.value)
      await visualPlan.prepare({ ...input, selectedBlockIds: [...selectedBlockIds.value] }, fresh)
    } catch (failure) { visualPlan.error.value = studioErrorMessage(failure) }
    finally { preparingSource.value = false }
  }
  const visualJob = useVisualJob({ plan: () => visualPlan.current.value, beforeGenerate: async () => {
    if (!await autosave.flush() || !await visualPlan.flush()) return false
    await visualPlan.refresh()
    return !visualPlan.current.value?.stale && !visualPlan.dirty.value
  } })
  watch([url.planId, () => studio.value.visualPlan?.id, () => autosave.draftId.value], ([pendingId, savedId, draftId]) => {
    const id = pendingId ?? savedId
    if (draftId && id && visualPlan.current.value?.id !== id) void visualPlan.restore(id)
  }, { immediate: true })
  watch(() => visualJob.current.value?.id, id => {
    if (id) { autosave.setStudioJob(id); void url.remember('studioJob', id) }
  })
  watch([url.jobId, () => studio.value.activeVisualJobId, () => visualPlan.current.value?.id], ([pendingId, savedId, planId]) => {
    const id = pendingId ?? savedId
    if (id && planId && visualJob.current.value?.id !== id) void visualJob.restore(id)
  }, { immediate: true })
  watch(() => visualPlan.current.value?.id, id => { if (id) void visualJob.loadCapabilities() })
  watch(capabilityState, value => { if (value) visualJob.capabilities.value = value.image })
  watch(() => JSON.stringify([article.content.value.replace(/\r\n?/g, '\n'), article.selectedTitle.value,
    article.platform.value, article.contentMode.value, article.question.value, article.questionRef.value,
    article.brief.value]), (_value, previous) => {
    if (previous && visualPlan.current.value && !autosave.isRestoring()) visualPlan.current.value.stale = true
  })

  async function onAdoptRequested(selection: { itemId: string; artifactId: string }): Promise<void> {
    const plan = visualPlan.current.value
    if (!plan || visualPlan.dirty.value || plan.stale) return
    await autosave.adoptVisualArtifacts({ planId: plan.id, planRevision: plan.revision, selections: [selection] })
  }

  const articleRender = useArticleRender({ draftId: () => autosave.draftId.value,
    draftVersion: () => autosave.draftVersion.value })
  const formatPanelVisible = computed(() => formatRevealed.value || studio.value.recipe?.id === 'article-format'
    || handoffProcessingMode.value === 'format')
  async function onRenderRequested(input: { theme: 'standard' | 'compact'; includeTitle: boolean; citeExternalLinks: boolean }): Promise<void> {
    if (await autosave.flush()) await articleRender.render(input)
  }
  function onOpenFormat(): void { formatRevealed.value = true }
  async function beforeExport(): Promise<number | false> {
    return await autosave.flush() && autosave.draftId.value ? autosave.draftVersion.value : false
  }

  return { sourceEntryVisible, sourceEntryNote, sourceDocument, handoffProcessingMode,
    source, selectedBlockIds, sourceError, importing, editorDisabled, studioWriteDisabled, studioExportEnabled, wechatEnabled,
    capabilitiesError, proposalVisible, proposalAction, textProposal, visualPlan, visualJob,
    studioPlanEnabled, cardStudioEnabled, articleVisualEnabled, articleRender, formatPanelVisible,
    onSourceImportRequested, onPrepareVisualPlan, onAdoptRequested, onProposalPrepare, onProposalApply,
    onSuggestSummary, onRenderRequested, onOpenFormat, beforeExport }
}
