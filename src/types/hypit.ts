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
  dependencies: string[];
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
export type HypitOutputListResponse = { success: true; data: { items: HypitOutput[]; nextCursor: string | null } };

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

export type HypitFileContent = {
  path: string;
  content: string;
  baseHash: string;
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
};

export type HypitVariantItem = {
  id: string;
  batchJobId: string;
  ordinal: number;
  runFile: string;
  state: 'draft' | 'planned' | 'queued' | 'running' | 'succeeded' | 'failed' | 'cancelled';
  attempt: number;
  parameters: Record<string, unknown>;
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
