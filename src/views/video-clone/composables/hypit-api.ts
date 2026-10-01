/**
 * hypit-api.ts — C107-21 (task-107) 统一 Hypit API 取数层（W21 固定职责）：
 * 复用 grassland-http 的 fetchApi/request 基建，解包 {success,data} 信封与
 * 错误码；所有面板共用，不各自写 fetch。AbortSignal 支持切换工程时取消在途
 * 请求（§4.3 请求世代）。
 */
import { fetchApi, GrasslandHttpError } from '../../../composables/grassland-http';
import type {
  HypitArchiveData,
  HypitArchiveRequest,
  HypitBuild,
  HypitBuildCreatedResponse,
  HypitCapabilities,
  HypitChangeset,
  HypitClonePlan,
  HypitFeedbackView,
  HypitFileContent,
  HypitFileTree,
  HypitJob,
  HypitOutputListData,
  HypitProject,
  HypitSessionCreated,
  HypitTemplateSummary,
  HypitVariantItem,
  HypitVariantMutationResult,
  HypitPackageDownload,
} from '../../../types/hypit';

const BASE = '/api/hypit';

/**
 * C107F2-09 运行时闸门：工作区数据调用（列表）在首次 capabilities 探测结果
 * 出来前等待；disabled/unavailable 时零网络请求（TC-F2-09-01）。由
 * useHypitRuntime 安装/卸载；capabilities 自身与 operator 诊断面板不经闸门。
 */
export type HypitRuntimeGateState = 'ready' | 'blocked';
export type HypitRuntimeGate = (signal?: AbortSignal) => Promise<HypitRuntimeGateState>;

let runtimeGate: HypitRuntimeGate | null = null;

export function setHypitRuntimeGate(gate: HypitRuntimeGate | null): void {
  runtimeGate = gate;
}

async function throughRuntimeGate(signal?: AbortSignal): Promise<void> {
  if (runtimeGate === null) return;
  const state = await runtimeGate(signal);
  if (state !== 'ready') {
    // 拒绝语义与 §6.1 一致：服务端 disabled 口径同 hypit_disabled，不发网络请求。
    throw new GrasslandHttpError(503, '视频复刻服务当前不可用', 'hypit_disabled');
  }
}

/** 统一信封解包：非 2xx 抛 GrasslandHttpError（code 保留）；success=false 同路。 */
export async function hypitRequest<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await fetchApi(path.startsWith('/') ? `${BASE}${path}` : path, init);
  if (!response.ok) {
    let message = `请求失败（${response.status}）`;
    let code: string | undefined;
    try {
      const body = (await response.json()) as { error?: unknown; code?: unknown };
      // 错误体两种形态：字符串（直出文案）与对象 {code,message}（hypit_* 信封）。
      // 对象形态必须透出 message——session expired / conflict 的可行动文案在其中，
      // 只回退「请求失败（401）」会让过期会话没有恢复入口提示。
      if (typeof body.error === 'string') {
        message = body.error;
      } else if (body.error !== null && typeof body.error === 'object') {
        const wrapped = body.error as { message?: unknown; code?: unknown };
        if (typeof wrapped.message === 'string' && wrapped.message.length > 0) message = wrapped.message;
        if (typeof wrapped.code === 'string') code = wrapped.code;
      }
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

/** C107F2-09：capabilities 探测（不经运行时闸门——它就是闸门的数据源）。 */
export function getCapabilities(signal?: AbortSignal): Promise<HypitCapabilities> {
  return hypitRequest('/capabilities', { method: 'GET', ...withSignal({}, signal) });
}

export function listProjects(cursor?: string, signal?: AbortSignal): Promise<{ items: HypitProject[]; nextCursor: string | null }> {
  const query = cursor === undefined || cursor === '' ? 'limit=50' : `limit=50&cursor=${encodeURIComponent(cursor)}`;
  return throughRuntimeGate(signal).then(
    () => hypitRequest(`/projects?${query}`, { method: 'GET', ...withSignal({}, signal) }));
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
  return hypitRequest<HypitFileContent>(`/projects/${projectId}/file?path=${encodeURIComponent(path)}`, {
    method: 'GET', ...withSignal({}, signal),
    // TC-F2-10-04：缺 hash/revision 是契约违规，显式报错而不是把 undefined 当 CAS 基线。
  }).then((content) => {
    if (typeof content?.hash !== 'string' || content.hash === '' || typeof content?.revision !== 'number') {
      throw new GrasslandHttpError(502, '文件响应缺少 hash/revision 契约字段', 'hypit_contract_violation');
    }
    return content;
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

/**
 * C107F2-12（§6.5）：Outputs 列表读全量 HypitOutputListData（items 为正式字段）。
 * 消费层只使用 items；outputs 兼容别名由服务端保证同内容。
 */
export function listOutputs(projectId: string, buildId: string, signal?: AbortSignal): Promise<HypitOutputListData> {
  void projectId;
  return hypitRequest(`/builds/${buildId}/outputs?limit=50`, { method: 'GET', ...withSignal({}, signal) });
}

/**
 * C107F2-12（F12 修复）：归档走 POST /builds/{id}/archive，载荷
 * {requestId, outputNames}（按名批量）；不再误用 result-actions/outputId。
 */
export function archiveOutput(buildId: string, outputNames: string[], signal?: AbortSignal): Promise<HypitArchiveData> {
  return hypitRequest(`/builds/${buildId}/archive`, {
    method: 'POST',
    body: JSON.stringify({ requestId: crypto.randomUUID(), outputNames } satisfies HypitArchiveRequest),
    ...withSignal({}, signal),
  });
}

/**
 * C107F2-37（缺陷 AB）：/api/media/{id} 是元数据端点（返回 JSON 而非字节），
 * 下载必须用它签发的短时 presigned downloadUrl——与 KYB/画布预览同一既有模式。
 */
export async function readMediaDownloadUrl(mediaId: string, signal?: AbortSignal): Promise<string | null> {
  const response = await fetchApi(`/api/media/${encodeURIComponent(mediaId)}`, { ...withSignal({}, signal) });
  if (!response.ok) {
    throw new GrasslandHttpError(response.status, `媒体元数据请求失败（${response.status}）`, 'media_unavailable');
  }
  const body = (await response.json()) as { success: boolean; data?: { downloadUrl?: string | null } };
  return body.data?.downloadUrl ?? null;
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

/** C107F2-23：关闭预览会话（§6.9 DELETE 幂等 200 closed:true）。 */
export function closePreviewSession(projectId: string, sessionId: string, signal?: AbortSignal): Promise<{ closed: boolean }> {
  return hypitRequest(`/projects/${projectId}/preview-sessions/${encodeURIComponent(sessionId)}`, {
    method: 'DELETE', ...withSignal({}, signal),
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

/** C107F2-20：关闭会话（§6.9 DELETE 幂等 200 closed:true）。 */
export function closeStudioSession(projectId: string, sessionId: string, signal?: AbortSignal): Promise<{ closed: boolean }> {
  return hypitRequest(`/projects/${projectId}/studio-sessions/${encodeURIComponent(sessionId)}`, {
    method: 'DELETE', ...withSignal({}, signal),
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

/** C107F2-27：重试携稳定 requestId（§6.12 retry body={requestId}）；200 回 Variant 状态面。 */
export function retryVariant(projectId: string, variantId: string, body: {
  requestId: string;
}, signal?: AbortSignal): Promise<HypitVariantMutationResult> {
  return hypitRequest(`/projects/${projectId}/variants/${variantId}/retry`, {
    method: 'POST', body: JSON.stringify(body), ...withSignal({}, signal),
  });
}

/** C107F2-27：取消单项（§6.12 cancel body={requestId}）；终态重复取消零副作用。 */
export function cancelVariant(projectId: string, variantId: string, body: {
  requestId: string;
  reason?: string;
}, signal?: AbortSignal): Promise<HypitVariantMutationResult> {
  return hypitRequest(`/projects/${projectId}/variants/${variantId}/cancel`, {
    method: 'POST', body: JSON.stringify(body), ...withSignal({}, signal),
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

/** C107F2-30：导出 202 AcceptedJob——产物经 exportStatus 轮询（不再回 artifactRoot）。 */
export function exportProject(projectId: string, body: {
  requestId: string;
  title?: string;
  runFile?: string;
}, signal?: AbortSignal): Promise<{ jobId: string; exportId: string; status: string }> {
  return hypitRequest(`/projects/${projectId}/export`, {
    method: 'POST', body: JSON.stringify(body), ...withSignal({}, signal),
  });
}

/** C107F2-30：导出状态 + 下载元数据（owner 绑定；他人/不存在同答 404）。 */
export function exportStatus(exportId: string, signal?: AbortSignal): Promise<{
  exportId: string;
  status: string;
  download?: HypitPackageDownload;
}> {
  return hypitRequest(`/exports/${encodeURIComponent(exportId)}`, { method: 'GET', ...withSignal({}, signal) });
}

/**
 * C107F2-30：multipart 上传导入（requestId + file=.zip + 可选 title）。fetchApi
 * 不手工拼 boundary；服务端 202 {jobId, projectId, status}。上传中断抛错（无半包）。
 */
export function importPackage(file: File, requestId: string, title: string | undefined,
  onProgress?: (percent: number) => void, signal?: AbortSignal): Promise<{
  jobId: string;
  projectId: string;
  status: string;
}> {
  const form = new FormData();
  form.append('requestId', requestId);
  form.append('file', file, file.name || 'project.zip');
  if (title !== undefined && title.length > 0) form.append('title', title);
  return new Promise((resolvePromise, rejectPromise) => {
    const xhr = new XMLHttpRequest();
    xhr.open('POST', '/api/hypit/imports');
    xhr.withCredentials = true;
    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable && onProgress) onProgress(Math.round((event.loaded / event.total) * 100));
    };
    xhr.onload = () => {
      try {
        const payload = JSON.parse(xhr.responseText) as { success?: boolean; data?: { jobId: string; projectId: string; status: string }; error?: { message?: string } | string };
        if (xhr.status >= 200 && xhr.status < 300 && payload.success && payload.data) {
          resolvePromise(payload.data);
        } else {
          const message = typeof payload.error === 'object' && payload.error !== null
            ? (payload.error as { message?: string }).message
            : typeof payload.error === 'string' ? payload.error : `导入失败（${xhr.status}）`;
          rejectPromise(Object.assign(new Error(message ?? `导入失败（${xhr.status}）`), { status: xhr.status }));
        }
      } catch {
        rejectPromise(new Error(`导入失败（${xhr.status}）`));
      }
    };
    xhr.onerror = () => rejectPromise(new Error('上传中断（网络错误）；工程未创建，可重试'));
    xhr.onabort = () => rejectPromise(new Error('上传已取消；工程未创建'));
    if (signal !== undefined) {
      signal.addEventListener('abort', () => xhr.abort(), { once: true });
    }
    xhr.send(form);
  });
}

// --- 参考素材上传与交接（C107F2-31）---

export interface HypitAssetDto {
  id: string;
  projectId: string;
  role: string;
  originKind: string;
  originUrl: string | null;
  mediaId: string | null;
  resourceHandle: string;
  mimeType: string;
  sizeBytes: number;
  sha256: string;
  status: string;
  reused?: boolean;
}

/**
 * C107F2-31：multipart 参考素材上传（§6.14 /assets/upload，256MiB 服务端核验）。
 * XHR 真进度 + 可 abort；上传中断不产生 ready 素材。返回 202 job 收敛终态由
 * assets 列表轮询呈现。
 */
export function uploadAsset(projectId: string, file: File, role: string, requestId: string,
  onProgress?: (percent: number) => void, signal?: AbortSignal): Promise<HypitAssetDto> {
  const form = new FormData();
  form.append('requestId', requestId);
  form.append('role', role);
  form.append('file', file, file.name);
  return new Promise((resolvePromise, rejectPromise) => {
    const xhr = new XMLHttpRequest();
    xhr.open('POST', `/api/hypit/projects/${encodeURIComponent(projectId)}/assets/upload`);
    xhr.withCredentials = true;
    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable && onProgress) onProgress(Math.round((event.loaded / event.total) * 100));
    };
    xhr.onload = () => {
      try {
        const payload = JSON.parse(xhr.responseText) as { success?: boolean; data?: HypitAssetDto; error?: { message?: string } | string };
        if (xhr.status >= 200 && xhr.status < 300 && payload.success && payload.data) {
          resolvePromise(payload.data);
        } else {
          const message = typeof payload.error === 'object' && payload.error !== null
            ? (payload.error as { message?: string }).message
            : typeof payload.error === 'string' ? payload.error : `上传失败（${xhr.status}）`;
          rejectPromise(Object.assign(new Error(message ?? `上传失败（${xhr.status}）`), { status: xhr.status }));
        }
      } catch {
        rejectPromise(new Error(`上传失败（${xhr.status}）`));
      }
    };
    xhr.onerror = () => rejectPromise(new Error('上传中断（网络错误）；未产生素材，可重试'));
    xhr.onabort = () => rejectPromise(new Error('上传已取消；未产生素材'));
    if (signal !== undefined) {
      signal.addEventListener('abort', () => xhr.abort(), { once: true });
    }
    xhr.send(form);
  });
}

/** C107F2-31：sourceContext 交接物化（media 复制真实字节；analysis/brief 返回说明）。 */
export function importSource(projectId: string, signal?: AbortSignal): Promise<{
  sourceKind: string | null;
  asset: HypitAssetDto | null;
  note?: string;
}> {
  return hypitRequest(`/projects/${projectId}/assets/import-source`, {
    method: 'POST', ...withSignal({}, signal),
  });
}

/** C107F2-31：素材列表（原样透传信封内 items）。 */
export function listAssets(projectId: string, signal?: AbortSignal): Promise<{ items: HypitAssetDto[] }> {
  return hypitRequest(`/projects/${projectId}/assets`, { method: 'GET', ...withSignal({}, signal) });
}

/** C107F2-31：URL 导入（既有 SSRF/平台防线在服务端；失败保留具体原因）。 */
export function importUrlAsset(projectId: string, url: string, requestId: string,
  signal?: AbortSignal): Promise<HypitAssetDto> {
  return hypitRequest(`/projects/${projectId}/import-url`, {
    method: 'POST',
    body: JSON.stringify({ requestId, url, role: 'reference' }),
    ...withSignal({}, signal),
  });
}
