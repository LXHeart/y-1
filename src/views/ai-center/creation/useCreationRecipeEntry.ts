import type { CreationDraftPrefill, CreationEntry, CreationHandoff, CreationSource } from '../../../types/ai-creation'
import type { Store, StoreProfile } from '../../../types/grassland'
import { buildCreationBrief, formatCreationAddress as parseAddress } from '../../../lib/creation-brief'

/**
 * 任务书 #101 C101-03：从已有内容开始的场景 handoff 构造。
 *
 * 从 AiCreationCenter 抽出的 handoff 组装逻辑（行为零变化）＋新增 recipe/processingMode
 * 携带：不伪造任务快照——taskContext/contextSnapshotId 只取 entry 实际携带的值；
 * 服务端仍核验平台/形式/模式与 studio 范围。
 */
export interface RecipeEntrySelection {
  recipeId: 'social-card-series' | 'article-visuals' | 'article-format' | 'cover-only'
  version: string
  processingMode: 'create' | 'adapt' | 'format'
}

export interface RecipeEntryContext {
  entry: CreationEntry | null
  taskSourceLocked: boolean
  platformId: string
  contentFormId: string
  sourceType: string
  topic: string
  /** 热点来源已选标题（与 topic 分开存：topic 会被结构化选题覆盖）。 */
  pickedHotTitle: string
  instructions: string
  referenceUrl: string
  referencePlatform: 'douyin' | 'bilibili'
  videoWorkflowId: string
  stores: Store[]
  storeId: string
  storeProfile: StoreProfile | null
  contextSnapshotId: string
  materialIds: string[]
  workflow: { status: string; workflowId: string | null; targetView: string | null }
  nextRevision: () => number
}

/** 现行来源（从视图抽出的 sourceForHandoff，逻辑零变化）。 */
export function sourceForHandoff(context: RecipeEntryContext): CreationSource | null {
  const { entry, taskSourceLocked } = context
  if (entry && taskSourceLocked) return { ...entry.source }
  if (context.sourceType === 'independent') return { type: 'independent' }
  if (context.sourceType === 'hot-topic') {
    return {
      type: 'hot-topic',
      title: context.pickedHotTitle.trim() || context.topic.trim(),
      topicId: entry?.source.type === 'hot-topic' ? entry.source.topicId : undefined,
    }
  }
  if (context.sourceType === 'reference') {
    return { type: 'reference', sourceUrl: context.referenceUrl.trim() }
  }
  if (context.sourceType === 'store' && context.stores.length) {
    // 门店来源由深链锁定（组织/门店 ID 在 entry.source 上），此处仅保留既有语义。
    return entry?.source.type === 'store' ? { ...entry.source } : null
  }
  return null
}

/** 预填（从视图抽出的 prefillForHandoff，逻辑零变化）。 */
export function prefillForHandoff(context: RecipeEntryContext): CreationDraftPrefill {
  const { entry, taskSourceLocked } = context
  if (taskSourceLocked) {
    return {
      ...(entry?.prefill || {}),
      referenceUrl: context.videoWorkflowId === 'video-recreation'
        ? context.referenceUrl.trim() || undefined
        : undefined,
      referencePlatform: context.videoWorkflowId === 'video-recreation' || context.sourceType === 'reference'
        ? context.referencePlatform
        : undefined,
    }
  }
  const store = context.stores.find((item) => item.id === context.storeId)
  return {
    topic: context.topic.trim() || undefined,
    instructions: context.instructions.trim() || undefined,
    referenceUrl: context.videoWorkflowId === 'video-recreation'
      ? context.referenceUrl.trim() || undefined
      : undefined,
    referencePlatform: context.videoWorkflowId === 'video-recreation' || context.sourceType === 'reference'
      ? context.referencePlatform
      : undefined,
    storeName: store?.name,
    address: parseAddress(context.storeProfile?.address),
    storeDescription: context.storeProfile?.description || undefined,
  }
}

/**
 * 组装完整 handoff（startWorkflow 的同步部分，任务上下文冻结仍留在视图）。
 * recipeSelection 存在时携带 recipe + processingMode（从已有内容开始）；
 * canStartBypassTopic = true 允许无主题直达（原稿即输入）。
 */
export function buildCreationHandoff(
  context: RecipeEntryContext,
  recipeSelection: RecipeEntrySelection | null,
): CreationHandoff | null {
  const source = sourceForHandoff(context)
  if (!source || !context.workflow.workflowId || !context.workflow.targetView) return null
  const processingMode = recipeSelection?.processingMode
    ?? context.entry?.processingMode
    ?? context.entry?.brief?.processingMode
    ?? 'create'
  return {
    revision: context.nextRevision(),
    platformId: context.platformId as CreationHandoff['platformId'],
    contentFormId: context.contentFormId as CreationHandoff['contentFormId'],
    source,
    workflowId: context.workflow.workflowId as CreationHandoff['workflowId'],
    targetView: context.workflow.targetView as CreationHandoff['targetView'],
    prefill: prefillForHandoff(context),
    brief: buildCreationBrief(context.entry, context.platformId as never, context.contentFormId as never,
      context.topic, context.instructions),
    taskContext: context.entry?.taskContext,
    contextSnapshotId: context.contextSnapshotId || undefined,
    materialIds: context.materialIds.length ? [...context.materialIds] : undefined,
    ...(recipeSelection
      ? {
        processingMode,
        recipe: { id: recipeSelection.recipeId, version: recipeSelection.version },
      }
      : { processingMode }),
  }
}
