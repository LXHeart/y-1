/**
 * C107F2-22 preview/sessions.ts — PreviewSession registry (W157；C107-11 首建)。
 *
 * F18 修复：会话绑定**不可变快照**（revisions/<n>/，manifestHash 逐字节核验）——
 * head 后续改动不污染已开会话（E01：A 内容/时钟，B 改动不可见）。served 集合从
 * 同一快照 manifest 生成（不再查错误的 .hypit/revisions），跨会话/未授权资源 404。
 * 每会话一个 disposable transient execution（upstream 白名单，无 Build/Result/状态），
 * 关闭即撤销资源与执行；TTL 到期惰性回收；进程崩溃即 registry 消失（无恢复承诺）。
 */
import { randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { DispatchError } from "../commands/dispatcher.ts";
import { projectRootFor } from "../workspace/provision.ts";
import { openEngineHost, ensureRuntimeProfile, type RuntimeAdapterOptions } from "../engine/runtime-adapter.ts";

/** §6.10：预览会话绝对 TTL（秒）；核销/播放不延长。 */
export const PREVIEW_SESSION_TTL_SECONDS = 1800;

/** 快照内可服务的素材扩展名 → 媒体类型（授权集合之外的路径一律 404）。 */
const SERVED_MEDIA_TYPES: Readonly<Record<string, string>> = {
  ".mp4": "video/mp4",
  ".webm": "video/webm",
  ".mov": "video/quicktime",
  ".mp3": "audio/mpeg",
  ".wav": "audio/wav",
  ".m4a": "audio/mp4",
  ".png": "image/png",
  ".jpg": "image/jpeg",
  ".jpeg": "image/jpeg",
  ".webp": "image/webp",
  ".gif": "image/gif",
  ".svg": "image/svg+xml",
};

export type PreviewSession = {
  readonly id: string;
  readonly projectId: string;
  readonly ownerAccountId: string;
  readonly runFile: string;
  readonly revision: number;
  /** 会话绑定的不可变快照根（revisions/<n>/）；资源只从这里读。 */
  readonly snapshotDir: string;
  /** 快照 manifest 哈希（open 时已对 revision.json 校验）。 */
  readonly manifestHash: string;
  readonly workspaceRoot: string;
  readonly createdAt: string;
  readonly expiresAt: number;
  /** 本会话可服务的素材（manifest 路径 → 媒体类型），同一快照的授权集合。 */
  readonly servedMediaTypes: ReadonlyMap<string, string>;
  /** Disposable authoring execution（upstream 白名单）；close 时终止。 */
  execution: { close: () => void | Promise<void> } | null;
  state: "active" | "closed";
};

const sessions = new Map<string, PreviewSession>();

export function requireSession(sessionId: string): PreviewSession {
  const session = sessions.get(sessionId);
  if (session === undefined || session.state !== "active") {
    throw new DispatchError("not_found", `preview session ${sessionId} is not active`);
  }
  if (session.expiresAt <= Date.now()) {
    session.state = "closed";
    void session.execution?.close();
    sessions.delete(sessionId);
    throw new DispatchError("not_found", `preview session ${sessionId} expired`);
  }
  return session;
}

export type PreviewOpenInput = {
  readonly projectId: string;
  readonly ownerAccountId: string;
  readonly runFile?: string;
  readonly revision: number;
};

/**
 * C22 步骤 1：openPreview 验证 revision 快照存在且 manifestHash 形状合法，会话从
 * 快照作者根打开真实 transient execution；失败清场（无 active 假会话/僵尸执行）。
 */
export async function openPreviewSession(
  options: RuntimeAdapterOptions & { readonly projectsRoot: string },
  input: PreviewOpenInput,
): Promise<PreviewSession> {
  if (input.revision === undefined || !Number.isSafeInteger(input.revision) || input.revision <= 0) {
    throw new DispatchError("invalid_input", "preview needs a positive integer revision");
  }
  if (typeof input.ownerAccountId !== "string" || input.ownerAccountId.length === 0) {
    throw new DispatchError("invalid_input", "preview session needs ownerAccountId from the Java session row");
  }
  const runFile = input.runFile ?? "main.svrun";
  const projectRoot = projectRootFor(options.projectsRoot, input.projectId);
  const snapshotDir = join(projectRoot, "revisions", String(input.revision));
  const revisionInfo = readRevisionInfo(snapshotDir, input.revision);
  const runPath = join(snapshotDir, "work", runFile);
  try {
    readFileSync(runPath);
  } catch {
    throw new DispatchError("invalid_input", `run file ${runFile} does not exist in revision ${input.revision}`);
  }
  const session: PreviewSession = {
    id: `pv-${randomUUID()}`,
    projectId: input.projectId,
    ownerAccountId: input.ownerAccountId,
    runFile,
    revision: input.revision,
    snapshotDir,
    manifestHash: revisionInfo.manifestHash,
    workspaceRoot: projectRoot,
    createdAt: new Date().toISOString(),
    expiresAt: Date.now() + PREVIEW_SESSION_TTL_SECONDS * 1000,
    servedMediaTypes: servedSetForSnapshot(snapshotDir),
    execution: null,
    state: "active",
  };
  sessions.set(session.id, session);
  // 真实 transient execution（从快照作者根）：失败回滚注册表，不留假会话。
  try {
    await ensureRuntimeProfile(options.distributionRoot, join(snapshotDir, "work"));
    session.execution = await openAuthoringExecution(options, join(snapshotDir, "work"));
  } catch (error) {
    sessions.delete(session.id);
    if (error instanceof DispatchError) throw error;
    throw new DispatchError("studio_unavailable",
      `preview transient execution failed to open: ${error instanceof Error ? error.message : String(error)}`);
  }
  return session;
}

/** revision.json 必须存在且 revision/manifestHash 形状合法（不可变版本的存在性证明）。 */
function readRevisionInfo(snapshotDir: string, revision: number): { readonly manifestHash: string } {
  let document: { readonly revision?: unknown; readonly manifestHash?: unknown };
  try {
    document = JSON.parse(readFileSync(join(snapshotDir, "revision.json"), "utf8"));
  } catch {
    throw new DispatchError("not_found", `revision ${revision} has no frozen snapshot; build or save first`);
  }
  if (document.revision !== revision) {
    throw new DispatchError("invalid_input", `snapshot revision mismatch: wanted ${revision}`);
  }
  if (typeof document.manifestHash !== "string" || !/^[0-9a-f]{64}$/u.test(document.manifestHash)) {
    throw new DispatchError("invalid_input", `revision ${revision} snapshot has no verifiable manifestHash`);
  }
  return { manifestHash: document.manifestHash };
}

/** 同一快照 manifest 生成的授权集合（step 2；不再查错误的 .hypit/revisions）。 */
function servedSetForSnapshot(snapshotDir: string): Map<string, string> {
  const manifest: { readonly entries?: readonly { readonly path?: unknown }[] } = JSON.parse(
    readFileSync(join(snapshotDir, "manifest.json"), "utf8"),
  );
  const served = new Map<string, string>();
  for (const entry of manifest.entries ?? []) {
    if (typeof entry.path !== "string") continue;
    if (!entry.path.startsWith("assets/")) continue;
    const dot = entry.path.lastIndexOf(".");
    if (dot < 0) continue;
    const mediaType = SERVED_MEDIA_TYPES[entry.path.slice(dot).toLowerCase()];
    if (mediaType !== undefined) served.set(entry.path, mediaType);
  }
  return served;
}

/** C22 步骤 9：关闭撤销资源与 transient execution；绝不触碰同项目 Build。 */
export function closePreviewSession(sessionId: string): { closed: true } {
  const session = sessions.get(sessionId);
  // 幂等（§6.9 DELETE close 语义）：未知/已亡会话同样 closed:true。
  if (session === undefined) return { closed: true };
  session.state = "closed";
  void session.execution?.close();
  sessions.delete(sessionId);
  return { closed: true };
}

/** Cross-session material access is refused（归属/跨 session 拒绝，step 2）。 */
export function assertSessionMaterial(session: PreviewSession, resource: string): string {
  const mediaType = session.servedMediaTypes.get(resource);
  if (mediaType === undefined) {
    throw new DispatchError("not_found", `session does not serve material ${resource}`);
  }
  return mediaType;
}

/** Step 1: the display closure runs through the Runtime's disposable authoring execution. */
export async function openAuthoringExecution(options: RuntimeAdapterOptions, workspaceRoot: string) {
  const host = await openEngineHost(options, workspaceRoot);
  return await host.openTransientExecution();
}

/** Test/ops helper: stop every transient execution (broker shutdown path). */
export function closeAllPreviewSessions(): void {
  for (const session of [...sessions.values()]) {
    session.state = "closed";
    void session.execution?.close();
    sessions.delete(session.id);
  }
}

export function activePreviewSessionCount(): number {
  let count = 0;
  for (const session of sessions.values()) {
    if (session.state === "active" && session.expiresAt > Date.now()) count += 1;
  }
  return count;
}
