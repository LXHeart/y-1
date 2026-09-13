/**
 * 任务书 #101 §6.2/§6.3：AI 创作工作台（studio）共享类型声明。
 *
 * 与 intelligence creationstudio 包的 DTO 使用同名字段和相同枚举；状态字典见 §4.3，
 * 字段集是完整契约——不能自行增加状态或字段（NEW DTO 拒绝未知字段）。
 * 导入现有类型不改变其原契约。
 */
import type { CreationProject, CreationResultRef, CreationSourceRef } from './creation'
import type { SafetyReport } from './content-safety'

export type RecipeId = 'social-card-series' | 'article-visuals' | 'article-format' | 'cover-only'
export type VisualStrategy = 'story' | 'information' | 'visual'
export type TargetAspect = '3:4' | '9:16' | '1:1' | '16:9' | '2.35:1'
export type ConsistencyMode = 'reference-image' | 'prompt-only'
export type OperationState = 'preparing' | 'ready' | 'failed' | 'unknown'
export interface RecipeRef { id: RecipeId; version: string }
export interface SourceRef { id: string; contentHash: string }
export interface PlanRef { id: string; revision: number }
export interface StudioError { code: string; message: string }
export interface SourceBlock {
  id: string; kind: 'heading' | 'paragraph' | 'list' | 'quote' | 'table' | 'code';
  position: number; startCodePoint: number; endCodePoint: number;
  text: string; textHash: string;
}
export interface VisualStyle { styleId: string; layoutId: string; paletteId: string }
export interface VisualPlanItem {
  itemId: string; cardId: string; position: number;
  role: 'cover' | 'content' | 'summary' | 'illustration';
  title: string; bullets: string[]; caption: string; purpose: string;
  illustration: string; sourceBlockIds: string[]; criticalText: string[];
  layoutId: string; targetAspect: TargetAspect;
  placement: { afterBlockId: string } | null;
  inputMediaRef: CreationResultRef | null;
}
export interface VisualPlanDocument {
  recipe: RecipeRef; strategy: VisualStrategy; style: VisualStyle;
  items: VisualPlanItem[]; explanation: string; uncoveredBlockIds: string[];
}
export interface VisualPlan {
  id: string; draftId: string; status: OperationState; revision: number;
  confirmedRevision: number | null; source: SourceRef; baseDraftVersion: number;
  baseContentHash: string; stale: boolean; document: VisualPlanDocument | null;
  runId: string | null; error: StudioError | null; createdAt: string;
}
export type VisualItemState = 'waiting_anchor' | 'queued' | 'prepared' | 'dispatching'
  | 'generated_unsettled' | 'succeeded' | 'failed' | 'cancelled' | 'unknown'
export interface VisualArtifact {
  id: string; itemId: string; attemptId: string; plan: PlanRef;
  originalMediaRef: CreationResultRef; deliveryMediaRef: CreationResultRef;
  runId: string; width: number; height: number; contentHash: string;
  anchorArtifactId: string | null; createdAt: string;
}
export interface VisualJobItem {
  attemptId: string; itemId: string; position: number; state: VisualItemState;
  runId: string | null; artifact: VisualArtifact | null; error: StudioError | null;
}
export interface VisualJob {
  id: string; requestId: string; draftId: string; plan: PlanRef;
  state: 'queued' | 'running' | 'succeeded' | 'partial' | 'failed' | 'cancelled' | 'unknown';
  version: number; items: VisualJobItem[]; cancelRequested: boolean;
  quoteId: string; createdAt: string; updatedAt: string;
}
export interface StudioApplyResult {
  project: CreationProject; appliedVersion: number; alreadyApplied: boolean;
}
export interface StudioWorkspaceRefs {
  schemaVersion: 1; recipe: RecipeRef | null; sourceDocumentId: string | null;
  visualPlan: PlanRef | null; activeVisualJobId: string | null;
  lastProposalId: string | null; renderTheme: 'standard' | 'compact';
}
export interface StudioPage<T> { items: T[]; nextCursor: string | null }

// ---- §6.3 其余对象（前端同名声明；Java DTO 落各领域包）----

export interface RecipeDefinition {
  id: RecipeId
  version: string
  label: string
  enabled: boolean
  platformIds: string[]
  contentForms: string[]
  processingModes: string[]
  minItems: number
  maxItems: number
  defaultAspect: TargetAspect | null
  supportedStrategies: VisualStrategy[]
}
export interface SourceDocument {
  id: string; draftId: string; schemaVersion: 1;
  kind: 'plain-text' | 'markdown' | 'draft-content';
  title: string; rawText: string; normalizedMarkdown: string; contentHash: string;
  blocks: SourceBlock[]; sourceRefs: CreationSourceRef[]; warnings: string[]; createdAt: string;
}
export type TextProposalAction = 'adapt-body' | 'suggest-metadata'
export type TextProposalStatus = OperationState | 'applied'
export interface TextProposalResult {
  title: string | null; body: string | null; summary: string | null;
  changes: string[]; sourceBlockIds: string[];
}
export interface TextProposal {
  id: string; draftId: string; requestId: string; action: TextProposalAction;
  status: TextProposalStatus; baseDraftVersion: number; baseContentHash: string;
  source: SourceRef; result: TextProposalResult | null; runId: string | null;
  safety: SafetyReport | null; appliedDraftVersion: number | null;
  error: StudioError | null; createdAt: string; expiresAt: string;
}
export interface ImageCapabilities {
  protocol: 'openai-image' | 'minimax-character' | 'legacy-generation';
  referenceKinds: ('image' | 'character')[];
  maxReferences: number; maxReferenceBytes: number;
  generationSizes: string[]; providerLabel: string; modelLabel: string;
  configurationFingerprint: string; available: boolean; unavailableReason: string | null;
}
export interface VisualQuote {
  id: string; plan: PlanRef; selectedItemIds: string[]; imageCalls: number;
  consistencyMode: ConsistencyMode; anchorArtifactId: string | null;
  userCredits: number; platformBudgetCents: number;
  billingSource: 'platform' | 'personal-byok' | 'org-byok';
  pricingVersion: string; configurationFingerprint: string;
  expiresAt: string; warnings: string[];
}
export interface RenderPreview {
  draftId: string; version: number; renderVersion: string;
  contentHash: string; html: string; text: string;
  warnings: string[]; unresolvedMediaIds: string[];
}
export interface StudioExportFile {
  exportId: string; filename: string; contentType: string;
  sha256: string; url: string; sizeBytes: number; expiresAt: string;
}
export interface StudioExportResult {
  draftId: string; version: number;
  format: 'markdown' | 'text' | 'wechat-html' | 'bundle-zip';
  file: StudioExportFile; missingItems: string[];
}
export interface WechatAccount {
  id: string; displayName: string; appId: string;
  state: 'unverified' | 'active' | 'invalid' | 'disconnected';
  version: number; verifiedAt: string | null; error: StudioError | null;
}
export interface WechatDraftSync {
  id: string; requestId: string; accountId: string; draftId: string;
  draftVersion: number; version: number;
  state: 'preparing' | 'uploading' | 'submitting' | 'verifying' | 'succeeded' | 'failed' | 'unknown' | 'cancelled';
  externalDraftMediaId: string | null; payloadHash: string;
  error: StudioError | null; createdAt: string; verifiedAt: string | null;
}
export interface WechatDraftCandidate {
  externalDraftMediaId: string; title: string;
  updatedAt: string; contentMatches: boolean;
}
