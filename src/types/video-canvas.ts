import type { CreationProject } from './creation'

/**
 * 创作画布共享契约（任务书 #100 §6.1）。
 *
 * 类型只是声明契约：数字的运行时校验（布局范围、裁剪整数毫秒、版本正安全整数等）
 * 由服务端执行，TypeScript 类型不能代替服务端校验。可选字段的含义是「不修改/不提供」，
 * 不是用 null 清空；除明确标注 `| null` 的字段外，缺字段、null、空字符串、错误类型都拒绝。
 */

export interface CanvasViewport { panX: number; panY: number; scale: number }

export interface VideoCanvasLayout {
  schemaVersion: 1
  storyboardId: string
  viewport: CanvasViewport
  positions: Record<string, { x: number; y: number }>
  activeBranchId: string | null
}

export interface ShotContentPatch {
  shotId: string
  visual?: string
  narration?: string
  plannedSeconds?: number
  cameraMove?: string
  anchorImageIndex?: number
}

export interface EditStoryboardRequest {
  expectedEditVersion: number
  patches: ShotContentPatch[]
}

export interface EditStoryboardResult {
  storyboardId: string
  editVersion: number
  updatedShotIds: string[]
}

export interface WorkspaceBindingRequest {
  operationId: string
  draftId?: string
  expectedDraftVersion?: number
}

export interface WorkspaceBindingResult {
  project: CreationProject
  storyboardId: string
  productionTaskId: string | null
  editVersion: number
}

/** 选片写入结果（API-01）：库内完整选择 + 单调版本（从 0 起，每次成功写入 +1）。 */
export interface SelectionResult {
  selection: Record<string, string>
  selectionVersion: number
}

export type CanvasNodeKind = 'brief' | 'media' | 'shot' | 'take' | 'delivery' | 'note'

export type CanvasRefType = 'draft' | 'media' | 'content-asset' | 'shot' | 'take' | 'note'

export interface CanvasNodeRef {
  id: string
  kind: CanvasNodeKind
  refType: CanvasRefType
  refId: string | null
  label: string | null
  text: string | null
  x: number
  y: number
}

export interface CanvasReferenceEdge {
  id: string
  kind: 'reference'
  fromNodeId: string
  toNodeId: string
}

export interface CanvasDocumentBody {
  schemaVersion: 1
  storyboardId: string
  viewport: CanvasViewport
  nodes: CanvasNodeRef[]
  edges: CanvasReferenceEdge[]
  activeBranchId: string | null
}

export interface SaveCanvasRequest { expectedRevision: number; document: CanvasDocumentBody }

export interface CanvasDocument {
  id: string
  draftId: string
  revision: number
  updatedAt: string
  document: CanvasDocumentBody
}

export type ShotMediaSource =
  | { kind: 'generated' }
  | { kind: 'own-media'; mediaId: string; trimStartMs: number | null;
      trimEndMs: number | null; audioMode: 'source' | 'narration' | 'mute' }

export interface SaveShotSourcesRequest {
  expectedEditVersion: number
  sources: { shotId: string; source: ShotMediaSource }[]
}

export interface CreateVariantRequest {
  operationId: string
  expectedEditVersion: number
  expectedDraftVersion: number
  title: string
  shotIds: string[]
}

export interface VariantSummary {
  draftId: string
  storyboardId: string
  parentStoryboardId: string | null
  rootStoryboardId: string
  sourceEditVersion: number | null
  title: string
  createdAt: string
}

/** 任务书 #100 C100-16~18（§6.6）：画布 AI 计划协议。 */
export interface CreateCanvasPlanRequest {
  operationId: string
  draftId: string
  storyboardId: string
  selectedNodeIds: string[]
  expectedEditVersion: number
  expectedCanvasRevision: number
  instruction: string
}

export interface AppendedShot {
  visual: string
  narration: string
  plannedSeconds: number
  cameraMove: string
  anchorImageIndex: number
}

export type CanvasEditAction =
  | { kind: 'update-shot'; patch: ShotContentPatch }
  | { kind: 'append-shot'; shot: AppendedShot }

export type CanvasPlanAction =
  | { kind: 'edit'; actions: CanvasEditAction[] }
  | { kind: 'variant'; title: string; shotIds: string[] }
  | { kind: 'prepare-generation'; mode: 'initial' | 'regenerate' | 'reroll'; shotId: string | null }

export interface CanvasPlanResult {
  id: string
  status: 'preparing' | 'ready' | 'clarify' | 'failed' | 'applied' | 'expired'
  draftId: string
  storyboardId: string
  baseDraftVersion: number
  baseEditVersion: number
  baseCanvasRevision: number
  summary: string
  clarification: string | null
  action: CanvasPlanAction | null
  runId: string | null
  errorCode: string | null
  expiresAt: string
}

export interface ApplyCanvasPlanResult {
  planId: string
  storyboardId: string
  draftId: string
  editVersion: number
  affectedShotIds: string[]
  variant: VariantSummary | null
  preparedGeneration: { mode: 'initial' | 'regenerate' | 'reroll'; shotId: string | null } | null
}

export interface CreateVariantResult {
  variant: VariantSummary
  project: CreationProject
  shotIdMap: Record<string, string>
}
