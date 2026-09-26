/**
 * hypit-api.ts — C107-21 (task-107) 统一 Hypit API 取数层（W21 固定职责）：
 * 复用 grassland-http 的 fetchApi/request 基建，解包 {success,data} 信封与
 * 错误码；所有面板共用，不各自写 fetch。AbortSignal 支持切换工程时取消在途
 * 请求（§4.3 请求世代）。
 */
import { fetchApi, GrasslandHttpError } from '../../../composables/grassland-http';
import type {
  HypitAcceptedJobResponse,
  HypitBuild,
  HypitBuildCreatedResponse,
  HypitChangeset,
  HypitClonePlan,
  HypitFeedbackView,
  HypitFileContent,
  HypitFileTree,
  HypitJob,
  HypitOutput,
  HypitProject,
  HypitSessionCreated,
  HypitTemplateSummary,
  HypitVariantItem,
} from '../../../types/hypit';

const BASE = '/api/hypit';

/** 统一信封解包：非 2xx 抛 GrasslandHttpError（code 保留）；success=false 同路。 */
export async function hypitRequest<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await fetchApi(path.startsWith('/') ? `${BASE}${path}` : path, init);
  if (!response.ok) {
    let message = `请求失败（${response.status}）`;
    let code: string | undefined;
    try {
      const body = (await response.json()) as { error?: unknown; code?: unknown };
      if (typeof body.error === 'string') message = body.error;
      if (typeof body.code === 'string') code = body.code;
    } catch {
      /* keep the fallback message */
    }
    throw new GrasslandHttpError(response.status, message, code);
  }
  const payload = (await response.json()) as { success?: boolean; data?: T };
  if (payload.success !== true || payload.data === undefined) {
    throw new GrasslandHttpError(response.status, '响应信封缺失 data', 'hypit_engine_error');
  }
  return payload.data;
}

function withSignal(init: RequestInit, signal: AbortSignal | undefined): RequestInit {
  return signal === undefined ? init : { ...init, signal };
}

// --- 工程 ---

export function listProjects(signal?: AbortSignal): Promise<{ items: HypitProject[] }> {
  return hypitRequest('/projects?limit=50', { method: 'GET', ...withSignal({}, signal) });
}

export function getProject(projectId: string, signal?: AbortSignal): Promise<HypitProject> {
  return hypitRequest(`/projects/${projectId}`, { method: 'GET', ...withSignal({}, signal) });
}

export function createProject(body: {
  requestId: string;
  title: string;
  mode: string;
  templateId?: string | null;
  sourceContext?: Record<string, unknown> | null;
}, signal?: AbortSignal): Promise<{ project: HypitProject; job: { jobId: string; state: string; resourceId: string | null } }> {
  return hypitRequest('/projects', { method: 'POST', body: JSON.stringify(body), ...withSignal({}, signal) });
}

export function patchProject(projectId: string, body: {
  requestId: string;
  title: string;
  baseVersion: number;
}, signal?: AbortSignal): Promise<HypitProject> {
  return hypitRequest(`/projects/${projectId}`, { method: 'PATCH', body: JSON.stringify(body), ...withSignal({}, signal) });
}

export function deleteProject(projectId: string, signal?: AbortSignal): Promise<{ jobId: string }> {
  return hypitRequest(`/projects/${projectId}`, { method: 'DELETE', ...withSignal({}, signal) });
}

// --- 文件与变更集 ---

export function listFiles(projectId: string, signal?: AbortSignal): Promise<HypitFileTree> {
  return hypitRequest(`/projects/${projectId}/files`, { method: 'GET', ...withSignal({}, signal) });
}

export function readFile(projectId: string, path: string, signal?: AbortSignal): Promise<HypitFileContent> {
  return hypitRequest(`/projects/${projectId}/file?path=${encodeURIComponent(path)}`, {
    method: 'GET', ...withSignal({}, signal),
  });
}

export function createChangeset(projectId: string, body: {
  requestId: string;
  baseRevision: number;
  applyMode: 'save' | 'validated';
  changes: { path: string; action: 'put' | 'delete'; content?: string; baseHash: string | null }[];
}, signal?: AbortSignal): Promise<HypitChangeset> {
  return hypitRequest(`/projects/${projectId}/changesets`, {
    method: 'POST', body: JSON.stringify(body), ...withSignal({}, signal),
  });
}

export function applyChangeset(projectId: string, changesetId: string, body: {
  requestId: string;
  baseRevision: number;
}, signal?: AbortSignal): Promise<{ revision: number; manifestHash: string; appliedPaths: string[] }> {
  return hypitRequest(`/projects/${projectId}/changesets/${changesetId}/apply`, {
    method: 'POST', body: JSON.stringify(body), ...withSignal({}, signal),
  });
}

// --- 任务 / Build / 输出 ---

export function getJob(projectId: string, jobId: string, signal?: AbortSignal): Promise<HypitJob> {
  return hypitRequest(`/projects/${projectId}/jobs/${jobId}`, { method: 'GET', ...withSignal({}, signal) });
}

export function listBuilds(projectId: string, signal?: AbortSignal): Promise<{ items: HypitBuild[] }> {
  return hypitRequest(`/projects/${projectId}/builds?limit=20`, { method: 'GET', ...withSignal({}, signal) });
}

export function listOutputs(projectId: string, buildId: string, signal?: AbortSignal): Promise<{ items: HypitOutput[] }> {
  return hypitRequest(`/builds/${buildId}/outputs?limit=50`, { method: 'GET', ...withSignal({}, signal) });
}

export function archiveOutput(projectId: string, buildId: string, outputId: string, body: {
  requestId: string;
  action: 'archive';
}, signal?: AbortSignal): Promise<HypitAcceptedJobResponse['data']> {
  void outputId;
  return hypitRequest(`/builds/${buildId}/result-actions`, {
    method: 'POST', body: JSON.stringify(body), ...withSignal({}, signal),
  });
}

export function submitBuild(projectId: string, body: {
  requestId: string;
  planId: string;
  grantId: string | null;
  runFile: string;
  title?: string;
}, signal?: AbortSignal): Promise<HypitBuildCreatedResponse['data']> {
  return hypitRequest(`/projects/${projectId}/builds`, {
    method: 'POST', body: JSON.stringify(body), ...withSignal({}, signal),
  });
}

// --- 计划 / 会话 / 模板 / 变体 / 评论 ---

export function getClonePlan(projectId: string, signal?: AbortSignal): Promise<HypitClonePlan> {
  return hypitRequest(`/projects/${projectId}/clone-plan`, { method: 'GET', ...withSignal({}, signal) });
}

export function openPreviewSession(projectId: string, body: {
  requestId: string;
  runFile?: string;
  revision?: number;
}, signal?: AbortSignal): Promise<HypitSessionCreated> {
  return hypitRequest(`/projects/${projectId}/preview-sessions`, {
    method: 'POST', body: JSON.stringify(body), ...withSignal({}, signal),
  });
}

export function openStudioSession(projectId: string, body: {
  requestId: string;
  runFile?: string;
  revision?: number;
  readOnly?: boolean;
}, signal?: AbortSignal): Promise<HypitSessionCreated> {
  return hypitRequest(`/projects/${projectId}/studio-sessions`, {
    method: 'POST', body: JSON.stringify(body), ...withSignal({}, signal),
  });
}

export function listTemplates(signal?: AbortSignal): Promise<{ items: HypitTemplateSummary[] }> {
  return hypitRequest('/templates', { method: 'GET', ...withSignal({}, signal) });
}

export function listVariants(projectId: string, signal?: AbortSignal): Promise<{ items: HypitVariantItem[] }> {
  return hypitRequest(`/projects/${projectId}/variants?limit=100`, { method: 'GET', ...withSignal({}, signal) });
}

export function createVariants(projectId: string, body: {
  requestId: string;
  baseRunFile: string;
  axes: { key: string; values: string[] }[];
}, signal?: AbortSignal): Promise<{ batchJobId: string; items: HypitVariantItem[] }> {
  return hypitRequest(`/projects/${projectId}/variants`, {
    method: 'POST', body: JSON.stringify(body), ...withSignal({}, signal),
  });
}

export function buildVariant(projectId: string, variantId: string, body: {
  requestId: string;
  grantId: string | null;
}, signal?: AbortSignal): Promise<{ buildId: string; lifecycle: string }> {
  return hypitRequest(`/projects/${projectId}/variants/${variantId}/build`, {
    method: 'POST', body: JSON.stringify(body), ...withSignal({}, signal),
  });
}

export function retryVariant(projectId: string, variantId: string, signal?: AbortSignal): Promise<HypitVariantItem> {
  return hypitRequest(`/projects/${projectId}/variants/${variantId}/retry`, {
    method: 'POST', ...withSignal({}, signal),
  });
}

export function readFeedback(projectId: string, run: string | undefined, signal?: AbortSignal): Promise<HypitFeedbackView> {
  const query = run === undefined ? '' : `?run=${encodeURIComponent(run)}`;
  return hypitRequest(`/projects/${projectId}/feedback${query}`, { method: 'GET', ...withSignal({}, signal) });
}

export function mutateFeedback(projectId: string, body: {
  requestId: string;
  run?: string;
  expectedHash: string;
  mutations: Record<string, unknown>[];
}, signal?: AbortSignal): Promise<{ comments: HypitFeedbackView['comments']; hash: string; applied: number }> {
  return hypitRequest(`/projects/${projectId}/feedback`, {
    method: 'POST', body: JSON.stringify(body), ...withSignal({}, signal),
  });
}

export function exportProject(projectId: string, body: {
  requestId: string;
  title?: string;
  runFile?: string;
}, signal?: AbortSignal): Promise<{ artifactRoot: string; fileCount: number }> {
  return hypitRequest(`/projects/${projectId}/export`, {
    method: 'POST', body: JSON.stringify(body), ...withSignal({}, signal),
  });
}
