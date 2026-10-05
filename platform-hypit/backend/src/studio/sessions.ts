/**
 * C107F2-19 studio/sessions.ts — Studio session registry (broker side).
 *
 * F16 修复：会话不再只是内存票据记录——每个会话绑定一个真实启动、ready 探测
 * 通过的 Studio 子进程（launcher），HTTP/WS 由 proxy 受控代理到该进程。会话
 * 事实（owner/revision/readOnly/expiry）先由 Java 登记 PG（§7.1 V92），本表
 * 只持有进程绑定；票据签发与核销在 Java（nonceHash CAS），断言校验在 proxy。
 * 复用键（owner/project/run/revision/readOnly 全等才复用）由 C107F2-20 收口。
 */
import { createServer as createTcpProbe } from "node:net";
import { join } from "node:path";
import { DispatchError } from "../commands/dispatcher.ts";
import { projectRootFor } from "../workspace/provision.ts";
import { bridgeTokenForSession, launchStudio, type StudioProcess } from "./launcher.ts";

export type StudioSession = {
  readonly id: string;
  readonly projectId: string;
  readonly ownerAccountId: string;
  readonly runFile: string;
  /** Session base revision; advances after each bridged write-back. */
  revision: number;
  readonly readOnly: boolean;
  readonly createdAt: number;
  readonly expiresAt: number;
  readonly workspaceRoot: string;
  readonly runPath: string;
  readonly bridgeToken: string;
  readonly child: StudioProcess;
  state: "active" | "closed" | "failed";
};

/** Session absolute TTL (§6.9: 3600s; redemption never extends it). */
export const STUDIO_SESSION_TTL_SECONDS = 3600;

const sessions = new Map<string, StudioSession>();

const PORT_RANGE: readonly [number, number] = [25179, 25478];

/** Best-effort free-port pick on the loopback studio band (one session, one port). */
async function allocatePort(): Promise<number> {
  for (let candidate = PORT_RANGE[0]; candidate <= PORT_RANGE[1]; candidate += 1) {
    const probeServer = createTcpProbe();
    const free = await new Promise<boolean>((resolveFree) => {
      probeServer.once("error", () => resolveFree(false));
      probeServer.listen(candidate, "127.0.0.1", () => resolveFree(true));
    });
    await new Promise<void>((resolveClose) => probeServer.close(() => resolveClose()));
    if (free) return candidate;
  }
  throw new DispatchError("studio_unavailable", "no free studio port in the session band");
}

export type RegisterStudioSessionInput = {
  readonly sessionId: string;
  readonly projectId: string;
  readonly ownerAccountId: string;
  readonly runFile: string;
  readonly revision: number;
  readonly readOnly: boolean;
  readonly ttlSeconds?: number;
};

/**
 * C19 step 2：创建会话=先有 Java 登记的 PG 行（sessionId 由 Java 生成传入），
 * 再启动与 workspace/revision 绑定的实例；启动失败 reject 且不留 active 假会话。
 */
export async function registerStudioSession(
  options: {
    readonly distributionRoot: string;
    readonly projectsRoot: string;
    readonly basePath?: string;
    readonly readyTimeoutMs?: number;
  },
  input: RegisterStudioSessionInput,
  launch: typeof launchStudio = launchStudio,
): Promise<StudioSession> {
  if (!/^[A-Za-z0-9][A-Za-z0-9\-_]{7,63}$/u.test(input.sessionId)) {
    throw new DispatchError("invalid_input", "sessionId must be 8..64 [A-Za-z0-9-_]");
  }
  if (!Number.isSafeInteger(input.revision) || input.revision <= 0) {
    throw new DispatchError("invalid_input", "studio session revision must be a positive integer");
  }
  const existing = sessions.get(input.sessionId);
  if (existing !== undefined && existing.state === "active") {
    throw new DispatchError("invalid_input", "studio session already registered");
  }
  if (existing !== undefined) sessions.delete(input.sessionId);
  const workspaceRoot = join(projectRootFor(options.projectsRoot, input.projectId), "work");
  const runPath = join(workspaceRoot, input.runFile);
  const port = await allocatePort();
  const bridgeToken = bridgeTokenForSession();
  const child = await launch({
    distributionRoot: options.distributionRoot,
    workspaceRoot,
    runFile: runPath,
    port,
    basePath: options.basePath ?? `/studio/${input.sessionId}`,
    bridgeToken,
    ...(options.readyTimeoutMs === undefined ? {} : { readyTimeoutMs: options.readyTimeoutMs }),
  });
  const ttl = input.ttlSeconds ?? STUDIO_SESSION_TTL_SECONDS;
  const session: StudioSession = {
    id: input.sessionId,
    projectId: input.projectId,
    ownerAccountId: input.ownerAccountId,
    runFile: input.runFile,
    revision: input.revision,
    readOnly: input.readOnly,
    createdAt: Date.now(),
    expiresAt: Date.now() + ttl * 1000,
    workspaceRoot,
    runPath,
    bridgeToken,
    child,
    state: "active",
  };
  sessions.set(session.id, session);
  // 子进程意外退出（崩溃/OOM）后会话立即失效，不留僵尸 active 记录。
  void session.child.exited.then(() => {
    if (sessions.get(session.id) === session && session.state === "active") {
      session.state = "failed";
      sessions.delete(session.id);
    }
  });
  return session;
}

export function requireStudioSession(sessionId: string): StudioSession {
  const session = sessions.get(sessionId);
  if (session === undefined || session.state !== "active") {
    throw new DispatchError("not_found", "studio session is not active");
  }
  if (session.expiresAt <= Date.now()) {
    session.state = "closed";
    session.child.stop();
    sessions.delete(sessionId);
    throw new DispatchError("invalid_input", "studio session expired");
  }
  return session;
}

/** Write-back advanced the project head; the session base moves with it. */
export function advanceStudioSessionRevision(session: StudioSession, revision: number): void {
  if (Number.isSafeInteger(revision) && revision > session.revision) session.revision = revision;
}

/** Idempotent close: stops the managed child and forgets the session（§6.9 DELETE close 幂等 200）。 */
export function closeStudioSession(sessionId: string): { closed: true } {
  const session = sessions.get(sessionId);
  // 幂等：未知/已亡会话同样 closed:true——broker 侧进程可能已先亡（崩溃/重启自愈）。
  if (session === undefined) return { closed: true };
  session.state = "closed";
  session.child.stop();
  sessions.delete(sessionId);
  return { closed: true };
}

/** C107F2-20 撤销：撤属主在本工程的全部活跃会话（工程删除/注销入口），返回撤销数。 */
export function revokeStudioSessionsForOwner(ownerAccountId: string, projectId: string): number {
  let revoked = 0;
  for (const session of [...sessions.values()]) {
    if (session.ownerAccountId === ownerAccountId && session.projectId === projectId
      && session.state === "active") {
      session.state = "closed";
      session.child.stop();
      sessions.delete(session.id);
      revoked += 1;
    }
  }
  return revoked;
}

/** 属主活跃会话 id（撤销联动核验用，不含 secret）。 */
export function activeStudioSessionIdsFor(ownerAccountId: string, projectId: string): string[] {
  return [...sessions.values()]
    .filter((session) => session.ownerAccountId === ownerAccountId
      && session.projectId === projectId && session.state === "active")
    .map((session) => session.id);
}

export function activeStudioSessionCount(): number {
  let count = 0;
  for (const session of sessions.values()) {
    if (session.state === "active" && session.expiresAt > Date.now()) count += 1;
  }
  return count;
}

/** Test/ops helper: stop every managed child (broker shutdown path). */
export function closeAllStudioSessions(): void {
  for (const session of [...sessions.values()]) {
    session.state = "closed";
    session.child.stop();
    sessions.delete(session.id);
  }
}

/** Reclaim expired processes even when the user never returns to the editor. */
export function reapExpiredStudioSessions(now = Date.now()): number {
  let closed = 0;
  for (const session of [...sessions.values()]) {
    if (session.expiresAt <= now) {
      closeStudioSession(session.id);
      closed += 1;
    }
  }
  return closed;
}
