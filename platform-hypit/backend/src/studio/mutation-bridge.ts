/**
 * C107F2-19 studio/mutation-bridge.ts — server-side write-back relay.
 *
 * F16 写回侧：Studio 页面的写请求绝不直达子进程工作区——
 *  - PUT /__studio/source（整文件保存）与 POST /__studio/mutation（语义修改）
 *    一律经本桥转发 Java 既有 changeset/apply（带 sessionId 归属、baseRevision、
 *    baseHash），版本控制权威在 Java；文件由 workspace.apply journal 原子落盘，
 *    子进程经 watchSource 自动重载。
 *  - 语义修改的补丁计算在子进程（补丁 0002 的 compute 模式：只算不写），
 *    本桥把补丁应用为整文件内容后走同一条 Java 保存通道。
 *  - PUT /__studio/artifact-name 是 Result Repository 命名（非源码版本控制），
 *    以每会话 bridge token 中继子进程原生处理。
 */
import { createHash, randomUUID } from "node:crypto";
import { readFile } from "node:fs/promises";
import { isAbsolute, join, relative, sep } from "node:path";
import { DispatchError } from "../commands/dispatcher.ts";
import type { StudioSession } from "./sessions.ts";

export type MutationRequest = {
  readonly sessionId: string;
  readonly revision: number;
  readonly baseRevision: number;
  readonly readOnly: boolean;
  readonly operations: readonly unknown[];
};

const MAX_OPERATIONS = 200;
const MAX_BYTES_PER_OPERATION = 256 * 1024;
const MAX_SOURCE_BYTES = 4 * 1024 * 1024;

export function assertBridgeMutation(request: MutationRequest): void {
  if (request.readOnly) {
    throw new DispatchError("invalid_input", "read-only sessions cannot mutate");
  }
  if (!Number.isSafeInteger(request.revision) || !Number.isSafeInteger(request.baseRevision)) {
    throw new DispatchError("invalid_input", "mutation needs integer revisions");
  }
  if (request.revision !== request.baseRevision) {
    // Same contract as upstream commitMutation: the Source changed outside Studio.
    throw new DispatchError("revision_conflict", "the Source changed outside Studio");
  }
  if (!Array.isArray(request.operations) || request.operations.length === 0
    || request.operations.length > MAX_OPERATIONS) {
    throw new DispatchError("invalid_input", `mutations need 1..${MAX_OPERATIONS} operations`);
  }
  for (const operation of request.operations) {
    const size = JSON.stringify(operation)?.length ?? 0;
    if (size > MAX_BYTES_PER_OPERATION) {
      throw new DispatchError("invalid_input", "mutation operation exceeds the byte budget");
    }
  }
}

/** Authorization header for the relayed child call (bridge token, never logged). */
export function bridgeAuthorization(bridgeToken: string): string {
  if (bridgeToken.length < 32) {
    throw new DispatchError("invalid_input", "bridge token must be at least 32 characters");
  }
  return `Bearer ${bridgeToken}`;
}

/** Upstream source patch (start/end are UTF-16 code unit offsets, as in Studio). */
export type SourcePatch = {
  readonly path: string;
  readonly range: { readonly start: number; readonly end: number };
  readonly replacement: string;
  readonly preimage: string;
};

/** Apply ordered upstream patches to a file snapshot; refuses range/preimage drift. */
export function applySourcePatches(content: string, patches: readonly SourcePatch[]): string {
  let next = content;
  for (const patch of [...patches].sort((left, right) => right.range.start - left.range.start)) {
    if (patch.range.end > next.length || patch.range.start < 0) {
      throw new DispatchError("invalid_input", `source range exceeds file: ${patch.path}`);
    }
    const current = next.slice(patch.range.start, patch.range.end);
    if (current !== patch.preimage) {
      throw new DispatchError("revision_conflict", `source changed outside the bridge: ${patch.path}`);
    }
    next = `${next.slice(0, patch.range.start)}${patch.replacement}${next.slice(patch.range.end)}`;
  }
  return next;
}

export type WritebackOutcome = {
  readonly revision: number;
  readonly manifestHash: string;
};

/** Java write-back client (broker→Java /internal/hypit/sessions/{sid}/writeback). */
export type StudioWritebackClient = {
  saveSource(input: {
    readonly sessionId: string;
    readonly path: string;
    readonly content: string;
    readonly baseRevision: number;
    readonly baseHash: string;
    readonly requestId: string;
  }): Promise<WritebackOutcome>;
};

export function httpStudioWritebackClient(options: {
  readonly baseUrl: string;
  readonly token: string;
  readonly fetch?: typeof globalThis.fetch;
  readonly timeoutMs?: number;
}): StudioWritebackClient {
  const doFetch = options.fetch ?? globalThis.fetch;
  return {
    async saveSource(input) {
      let response: Response;
      try {
        response = await doFetch(`${options.baseUrl.replace(/\/$/u, "")}/internal/hypit/sessions/${encodeURIComponent(input.sessionId)}/writeback`, {
          method: "POST",
          headers: {
            "content-type": "application/json",
            authorization: `Bearer ${options.token}`,
          },
          body: JSON.stringify({
            path: input.path,
            content: input.content,
            baseRevision: input.baseRevision,
            baseHash: input.baseHash,
            requestId: input.requestId,
          }),
          signal: AbortSignal.timeout(options.timeoutMs ?? 30_000),
        });
      } catch (error) {
        throw new DispatchError("studio_unavailable", `studio writeback unreachable: ${String(error)}`);
      }
      const body = await response.text().catch(() => "");
      if (!response.ok) {
        throw new DispatchError(
          response.status === 409 ? "revision_conflict" : "studio_writeback_failed",
          `writeback rejected (${response.status}): ${body.slice(0, 300)}`,
        );
      }
      let parsed: { revision?: unknown; manifestHash?: unknown };
      try {
        parsed = JSON.parse(body) as { revision?: unknown; manifestHash?: unknown };
      } catch {
        throw new DispatchError("studio_writeback_failed", "writeback response was not JSON");
      }
      if (typeof parsed.revision !== "number" || typeof parsed.manifestHash !== "string") {
        throw new DispatchError("studio_writeback_failed", "writeback response missing revision/manifestHash");
      }
      return { revision: parsed.revision, manifestHash: parsed.manifestHash };
    },
  };
}

/** 会话源文件路径裁决：必须工作区相对、不得越界；返回归一相对路径与绝对路径。 */
function sessionSourceFile(session: StudioSession, sourcePath: string): {
  readonly path: string;
  readonly absolute: string;
} {
  if (sourcePath.length === 0 || sourcePath.startsWith("/") || sourcePath.includes("\0")) {
    throw new DispatchError("invalid_input", `studio cannot write source file ${sourcePath}`);
  }
  const absolute = join(session.workspaceRoot, sourcePath);
  const rel = relative(session.workspaceRoot, absolute);
  if (rel === ".." || rel.startsWith(`..${sep}`) || isAbsolute(rel) || rel.length === 0) {
    throw new DispatchError("invalid_input", `studio cannot write source file ${sourcePath}`);
  }
  return { path: rel, absolute };
}

async function readSessionSource(session: StudioSession, sourcePath: string): Promise<{
  readonly path: string;
  readonly absolute: string;
  readonly content: string;
}> {
  const resolved = sessionSourceFile(session, sourcePath);
  try {
    return { ...resolved, content: await readFile(resolved.absolute, "utf8") };
  } catch {
    throw new DispatchError("invalid_input", `studio source file is not readable: ${resolved.path}`);
  }
}

async function childCall(
  session: StudioSession,
  pathSuffix: string,
  init: { readonly method: string; readonly body?: string },
): Promise<{ readonly status: number; readonly body: string }> {
  const response = await fetch(`http://127.0.0.1:${session.child.port}${pathSuffix}`, {
    ...init,
    headers: {
      ...(init.body === undefined ? {} : { "content-type": "application/json" }),
      authorization: bridgeAuthorization(session.bridgeToken),
      origin: `http://127.0.0.1:${session.child.port}`,
      host: `127.0.0.1:${session.child.port}`,
    },
  }).catch((error: unknown) => {
    throw new DispatchError("studio_unavailable", `studio child unreachable: ${String(error)}`);
  });
  return { status: response.status, body: await response.text().catch(() => "") };
}

/** PUT /__studio/source 整文件保存：读当前文件→Java changeset→apply。 */
export async function relaySourceSave(
  session: StudioSession,
  writeback: StudioWritebackClient,
  body: { readonly text: string; readonly path?: string },
): Promise<WritebackOutcome> {
  if (session.readOnly) {
    throw new DispatchError("forbidden", "read-only sessions cannot mutate");
  }
  if (typeof body.text !== "string" || body.text.length > MAX_SOURCE_BYTES) {
    throw new DispatchError("invalid_input", "source text missing or exceeds the byte budget");
  }
  const requested = body.path ?? relative(session.workspaceRoot, session.runPath);
  const current = await readSessionSource(session, requested);
  return await writeback.saveSource({
    sessionId: session.id,
    path: current.path,
    content: body.text,
    baseRevision: session.revision,
    baseHash: sha256Hex(current.content),
    requestId: randomUUID(), // Java 侧 UUID.fromString；幂等键随重试复用由 C20 收口
  });
}

/**
 * POST /__studio/mutation 语义修改：子进程 compute 模式只算补丁不落盘
 * （补丁 0002），本桥应用补丁得到整文件内容后走 Java 保存通道。
 */
export async function relaySemanticMutation(
  session: StudioSession,
  writeback: StudioWritebackClient,
  mutation: Record<string, unknown>,
): Promise<WritebackOutcome> {
  if (session.readOnly) {
    throw new DispatchError("forbidden", "read-only sessions cannot mutate");
  }
  const result = await childCall(session, "/__studio/mutation", {
    method: "POST",
    body: JSON.stringify(mutation),
  });
  if (result.status !== 200) {
    throw new DispatchError(
      result.status === 409 ? "revision_conflict" : "invalid_input",
      `semantic mutation rejected: ${result.body.slice(0, 300)}`,
    );
  }
  let parsed: { patches?: unknown };
  try {
    parsed = JSON.parse(result.body) as { patches?: unknown };
  } catch {
    throw new DispatchError("studio_unavailable", "compute relay response was not JSON");
  }
  if (!Array.isArray(parsed.patches)) {
    throw new DispatchError("studio_unavailable", "compute relay response missing patches");
  }
  const patches = parsed.patches.filter((patch): patch is SourcePatch =>
    typeof patch?.path === "string" && typeof patch?.replacement === "string");
  if (patches.length === 0) {
    throw new DispatchError("invalid_input", "semantic mutation produced no source change");
  }
  // 按文件分组应用（补丁可跨 run 文件与其源文件，如 main.svrun→main.svml），
  // 每个文件一次 Java 保存调用，携带各自的基线内容哈希。
  const grouped = new Map<string, SourcePatch[]>();
  for (const patch of patches) {
    const held = grouped.get(patch.path) ?? [];
    held.push(patch);
    grouped.set(patch.path, held);
  }
  let outcome: WritebackOutcome | undefined;
  for (const [path, filePatches] of grouped) {
    const current = await readSessionSource(session, path);
    const next = applySourcePatches(current.content, filePatches);
    outcome = await writeback.saveSource({
      sessionId: session.id,
      path: current.path,
      content: next,
      baseRevision: session.revision,
      baseHash: sha256Hex(current.content),
      requestId: randomUUID(), // Java 侧 UUID.fromString；幂等键随重试复用由 C20 收口
    });
  }
  return outcome!;
}

/** PUT /__studio/artifact-name：结果命名中继子进程原生处理（非源码版本控制）。 */
export async function relayArtifactRename(
  session: StudioSession,
  body: unknown,
): Promise<{ readonly status: number; readonly body: string }> {
  return await childCall(session, "/__studio/artifact-name", {
    method: "PUT",
    body: JSON.stringify(body),
  });
}

/** 保存后观察子进程快照 revision：等它越过基线（watchSource 重载自增）再返回；超时回退 Java revision。 */
export async function observeChildRevision(
  session: StudioSession,
  fallback: number,
  timeoutMs = 3_000,
): Promise<number> {
  const childRevision = async (): Promise<number | undefined> => {
    try {
      const response = await fetch(`http://127.0.0.1:${session.child.port}/__studio/session`, {
        headers: { accept: "application/json" },
      });
      if (!response.ok) return undefined;
      const snapshot = await response.json() as { revision?: unknown };
      return typeof snapshot.revision === "number" ? snapshot.revision : undefined;
    } catch {
      return undefined;
    }
  };
  const baseline = await childRevision();
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const revision = await childRevision();
    // 子进程 revision 是自己的发布计数：等待它越过保存前基线，而不是见到任意数字就返回
    // （首个成功响应多半还是重载前的旧快照）。
    if (revision !== undefined && (baseline === undefined || revision > baseline)) return revision;
    await new Promise((sleep) => setTimeout(sleep, 200));
  }
  return fallback;
}

export function sha256Hex(content: string): string {
  return createHash("sha256").update(content, "utf8").digest("hex");
}
