// hypit.ts — C107-03 (task-107) shared Hypit DTOs for the AI frontend.
// Mirrors contracts/hypit-api.v1.json; amounts are decimal strings, unknowns
// are explicit null, and lists are cursor-paged. No field here may drift from
// the contract without a contract change first.
export type HypitMoney = {
  amount: string | null;
  currency: string;
  estimated: boolean;
  source: string;
  unknownReason: string | null;
};

export type HypitProjectMode = 'clone' | 'brief' | 'template' | 'import';
export type HypitProjectStatus = 'provisioning' | 'ready' | 'deleting' | 'deleted' | 'provisioning_failed';

export type HypitProject = {
  id: string;
  ownerAccountId: string;
  title: string;
  mode: HypitProjectMode;
  status: HypitProjectStatus;
  revision: number;
  version: number;
  selectedRun: string | null;
  sourceContext: { kind: 'media' | 'analysis' | 'brief'; id: string | null; label: string | null } | null;
  createdAt: string;
  updatedAt: string;
};

export type HypitJobState =
  | 'queued' | 'running' | 'waiting_input' | 'cancel_requested'
  | 'succeeded' | 'failed' | 'cancelled';

export type HypitJob = {
  id: string;
  projectId: string | null;
  kind: string;
  state: HypitJobState;
  phase: string | null;
  progress: { done: number | null; total: number | null; unit: string | null } | null;
  checkpointSummary: string | null;
  blockedReason: string | null;
  nextActions: string[];
  error: { code: string; message: string } | null;
  createdAt: string;
  updatedAt: string;
};

export type HypitAcceptedJob = {
  jobId: string;
  state: HypitJobState;
  resourceId: string | null;
};

export type HypitFileChange = {
  path: string;
  action: 'put' | 'delete';
  content?: string;
  baseHash: string | null;
};

export type HypitBuildLifecycle =
  | 'submitting' | 'active' | 'execution_decided' | 'result_pending' | 'finished'
  | 'submission_incomplete';

export type HypitBuild = {
  id: string;
  engineBuildId: string | null;
  projectId: string;
  revision: number;
  planId: string | null;
  runFile: string;
  lifecycle: HypitBuildLifecycle;
  outcome: 'complete' | 'failed' | 'cancelled' | null;
  operations: unknown[];
  receiptSummary: unknown;
  resultReady: boolean;
  archiveState: 'pending' | 'archiving' | 'archived' | 'failed';
  outputCount: number;
  createdAt: string;
  finishedAt: string | null;
};

export type HypitOutput = {
  id: string;
  buildId: string;
  name: string;
  displayName: string | null;
  kind: 'scalar' | 'resource' | 'composite';
  typeRef: string | null;
  mediaType: string | null;
  sizeBytes: number | null;
  durationSeconds: number | null;
  valueSummary: unknown;
  archiveState: 'pending' | 'archiving' | 'archived' | 'failed';
  mediaId: string | null;
  /** C107F2-37（缺陷 AB）：归档后由 /api/media/{id} 签发的短时下载 URL（未取到为空）。 */
  downloadUrl?: string | null;
  dependencies: string[];
  /** C107F2-12：索引落库时间（未知为 null）。 */
  createdAt: string | null;
};

export type HypitFeatureReadiness = {
  id: string;
  installed: boolean;
  configured: boolean;
  prepared: boolean;
  ready: boolean;
  reason: string | null;
  action: string | null;
};

export type HypitCapabilities = {
  enabled: boolean;
  version: string | null;
  features: HypitFeatureReadiness[];
  templates: { id: string; title: string; available: boolean }[];
};

export type HypitCapabilitiesResponse = { success: true; data: HypitCapabilities };
export type HypitProjectListResponse = { success: true; data: { items: HypitProject[]; nextCursor: string | null } };
export type HypitProjectCreatedResponse = { success: true; data: { project: HypitProject; job: HypitAcceptedJob } };
export type HypitJobResponse = { success: true; data: HypitJob };
export type HypitAcceptedJobResponse = { success: true; data: HypitAcceptedJob };
export type HypitBuildResponse = { success: true; data: HypitBuild };
export type HypitBuildCreatedResponse = { success: true; data: { build: HypitBuild; job: HypitAcceptedJob } };
export type HypitOutputListResponse = { success: true; data: HypitOutputListData };

/**
 * C107F2-12（§6.5 API-05）：Outputs 列表目标形状。items 是唯一正式消费字段；
 * outputs 为旧 detail 响应兼容别名，内容必须与 items 相同；nextCursor=null
 * 不伪造分页。
 */
export type HypitOutputListData = {
  items: HypitOutput[];
  nextCursor: string | null;
  build: HypitBuild;
  planSnapshot: Record<string, unknown>;
  /** 旧 detail 响应兼容，内容必须与 items 相同。 */
  outputs: HypitOutput[];
};

export type HypitArchiveRequest = {
  requestId: string;
  outputNames: string[];
};

export type HypitArchiveData = { outputs: HypitOutput[] };

export type HypitErrorResponse = {
  success: false;
  error: string;
  code: string;
  details?: Record<string, unknown>;
};

export type HypitSseEvent = {
  id: string;
  sequence: number;
  type: 'snapshot' | 'progress' | 'checkpoint' | 'output' | 'diagnostic' | 'terminal' | 'heartbeat';
  projectId: string | null;
  jobId?: string;
  buildId?: string;
  at: string;
  data: unknown;
};

/** C107F2-13（§6.6）：snapshot reset 载荷——权威 job + 最新 sequence（客户端重建状态）。 */
export type HypitSseSnapshotData = {
  reset: true;
  job: HypitJob;
  latestSequence: number;
};

// --- C107-21 视图层补充类型（W21；仅前端消费，形状沿契约） ---

export type HypitFileEntry = {
  path: string;
  sizeBytes: number;
  sha256: string;
};

export type HypitFileTree = {
  revision: number;
  manifestHash: string;
  files: HypitFileEntry[];
};

/** §6.4（C107F2-10）：正式字段 hash/revision；baseHash 仅为旧客户端兼容别名，值等于 hash。 */
export type HypitFileContent = {
  path: string;
  content: string;
  hash: string;
  revision: number;
  baseHash?: string;
};

export type HypitChangeset = {
  changesetId: string;
  baseRevision: number;
  applyMode: 'save' | 'validated';
  checkStatus: 'not_checked' | 'passed' | 'failed';
  state: 'draft' | 'applied' | 'conflict';
};

export type HypitTemplateSummary = {
  templateId: string;
  title: string;
  description: string;
  runPaths: string[];
  requiredCapabilities: string[];
  materialState: string;
  localOrRemote: string;
};

export type HypitPlanStep = {
  index: number;
  capability: string;
  boundSystemId: string | null;
  anchorSeconds: number;
  description: string;
};

export type HypitClonePlan = {
  planId: string;
  status: 'READY' | 'WAITING_INPUT';
  steps: HypitPlanStep[];
  materialGaps: { kind: string; description: string; suggestedSource: string | null }[];
  /**
   * C107F3-10（§6.4 尾注）：可信再生成来源元数据，由服务端生成（旧结果无这些
   * 字段——可选兼容，客户端只读展示，不得伪造）。
   */
  sourceAnalysisId?: string;
  sourceMediaHash?: string;
  sourceJobId?: string;
  baseRevision?: number;
  resultRevision?: number;
};

/** C107F3-10（API-002）：agent-jobs 创建输入。regenerateFromLatestAnalysis 缺省/ false 走旧路径；true 仅 intent=author（服务端校验组合）。 */
export type HypitAgentJobCreateInput = {
  requestId: string;
  intent: string;
  brief: string;
  assetIds?: string[];
  baseRevision?: number;
  scope?: Record<string, unknown> | null;
  regenerateFromLatestAnalysis?: boolean;
};

export type HypitVariantItem = {
  id: string;
  batchJobId: string;
  ordinal: number;
  runFile: string;
  state: 'draft' | 'planned' | 'queued' | 'running' | 'succeeded' | 'failed' | 'cancelled';
  attempt: number;
  parameters: Record<string, unknown>;
  /** C107F2-27：本会话内 build/retry 回执关联（服务端列表 DTO 不含，仅动作回执补写）。 */
  buildId?: string;
};

/** C107F2-30：可下载导出产物元数据（§6.13 形状锁定）。 */
export type HypitPackageDownload = {
  exportId: string;
  downloadPath: string;
  filename: string;
  mediaType: 'application/zip';
  sizeBytes: number;
  sha256: string;
  expiresAt: string;
  revision: number;
};

/** C107F2-27：retry/cancel 200 回执（§6.12 返回既有 Variant DTO 的状态面）。 */
export type HypitVariantMutationResult = {
  id: string;
  state: HypitVariantItem['state'];
  attempt: number;
};

/** C107F2-27：远程执行授权待确认快照（主生成流与变体构建流共享展示）。 */
export type HypitPendingGrant = {
  planId: string;
  pricingId: string;
  currency: string;
  maxAmount: string | null;
  known: boolean;
  variantCount: number;
  needs: string[];
  /** 授权 scope targets（定价行 need 去重）；服务端 requireGrantCovers 子集校验。
   *  主生成流的既有 pendingGrant 无此字段（其 scope 固定 final.video），可选兼容。 */
  targets?: string[];
};

export type HypitFeedbackComment = {
  id: string;
  run: string;
  at: number;
  text: string;
  resolved?: boolean;
};

export type HypitFeedbackView = {
  file: string;
  run?: string;
  comments: HypitFeedbackComment[];
  hash: string;
};

export type HypitSessionCreated = {
  sessionId: string;
  ticketUrl?: string;
  expiresAt?: string;
  revision: number | null;
  readOnly?: boolean;
  reused?: boolean;
};
