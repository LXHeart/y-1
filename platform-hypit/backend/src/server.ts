// server.ts — C107-02 (task-107) trusted broker HTTP host.
//
// C107-04 wiring: the C02 in-memory command map is replaced by the durable
// bridge.sqlite command store + dispatcher (K05/K06). The broker never
// evaluates author code itself — compilation always goes through the
// supervised runner slot.
import { createServer } from "node:http";
import { createHash } from "node:crypto";
import { createReadStream, createWriteStream } from "node:fs";
import { cp, mkdir, rename, rm } from "node:fs/promises";
import { existsSync, readFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { setTimeout as delay } from "node:timers/promises";

import { loadHypit } from "./engine/hypit-bootstrap.ts";
import {
  cancelBuild,
  ensureRuntimeProfile,
  inspectBuild,
  openEngineHost,
  openProjectResultsLocation,
  stopWorker,
  submitBuild,
} from "./engine/runtime-adapter.ts";
import { projectRootFor } from "./workspace/provision.ts";
import { RunnerSupervisor } from "./runner/supervisor.ts";
import { assertConfigured, assertTemplateResources, ensureRuntimeDirs, loadConfig, type HypitBackendConfig } from "./config.ts";
import { CommandStore } from "./commands/store.ts";
import { dispatchCommand, DispatchError, maintenanceCounts, MAINTENANCE_EXEMPT_KINDS } from "./commands/dispatcher.ts";
import { purgeExpiredTransfers, serveTransferContent, storeTransferContent, TransferError } from "./project-package/transfer.ts";
import { assemblePlanDocument, assemblePricingDocument } from "./engine/planning.ts";
import { runtimeProfileDigest } from "./engine/runtime-adapter.ts";
import { ExecutionAuthorizer, httpExecutionBridge, type PrepareRequest } from "./providers/authorization.ts";
import { snapshotRevision, verifySnapshot } from "./workspace/revisions.ts";
import { manifestHash as computeManifestHash, readManifestFile } from "./workspace/manifest.ts";
import { readHead as readWorkspaceHead } from "./workspace/transactions.ts";
import { describeVocabulary, VocabularyInputError } from "./engine/vocabulary.ts";
import { mediaHandleRegistry } from "./tools/media.ts";
import { HandleError, registerResource, resolveResource, type HandleRegistry } from "./resources/handles.ts";
import { planRangeServe, resolveRange } from "./resources/stream.ts";
import { createPreviewSurfaceHandler } from "./preview/server.ts";
import { closeAllPreviewSessions } from "./preview/sessions.ts";
import { createStudioProxyHandler } from "./studio/proxy.ts";
import { httpStudioWritebackClient } from "./studio/mutation-bridge.ts";
import { closeAllStudioSessions, reapExpiredStudioSessions } from "./studio/sessions.ts";

export type BrokerCommand = {
  readonly commandId: string;
  readonly kind: string;
  state: "queued" | "running" | "succeeded" | "failed";
  result?: unknown;
  error?: { code: string; message: string };
};

const MAX_BODY_BYTES = 1024 * 1024;
// C107F2-37：minimal-local 模板与 blank 骨架同构（main.svml 依 ./style.svs 的
// recipes 令牌满足 film:Film appearance 必填），清单必须同步含 style.svs。
const TEMPLATE_FILES = ["package.json", "main.svml", "main.svrun", "style.svs"] as const;

export async function runServer(overrides?: Partial<HypitBackendConfig>): Promise<void> {
  const base = loadConfig();
  const config: HypitBackendConfig = { ...base, ...overrides };
  ensureRuntimeDirs(config);
  // C03（TC-F2-03-03）：模板资源/catalog 预检在监听前执行——失败即启动非零，
  // 输出脱敏路径类别与原因；禁止带病启动后 healthz 假阳性。
  assertTemplateResources(config);
  const engine = await loadHypit(config.generatedRoot);
  const supervisor = new RunnerSupervisor({
    // C03（D-03/F02 修复）：backendRoot 由 import.meta.url 推导（镜像 /app），
    // 不再从 generatedRoot 的父目录猜测出不存在路径。
    backendRoot: config.backendRoot,
    distributionRoot: config.generatedRoot,
    slotRoot: config.runnerSlotRoot,
    socketDir: config.runnerSocketDir,
    runnerStateRoot: resolve(config.dataRoot, "../runner-state"),
    runnerTmpRoot: config.runnerTmpRoot,
    frameLimitBytes: config.runnerFrameLimitBytes,
    requestTimeoutMs: config.runnerRequestTimeoutMs,
    killTimeoutMs: config.runnerKillTimeoutMs,
  });
  const store = new CommandStore(join(config.dataRoot, "bridge.sqlite"));
  // C09（TC-F2-09-02）：版本号唯一来源是发行版 manifest（upstream 0.2.16），
  // 不允许在 Java/前端任何一处再硬编码版本字符串。
  const engineVersion = readEngineVersion(config.generatedRoot);
  // C09（§6.8）：readiness 最多缓存 5 秒——liveness（healthz）与 feature
  // readiness 分离；探测结果带脱敏 reason，不带宿主路径或秘密。
  let readinessCache: { at: number; body: unknown } | null = null;
  /**
   * C09（§6.8 readiness）：每依赖布尔 + 脱敏 reason。critical 依赖决定 ok；
   * studio/preview/程序/浏览器探测未接线时如实 ready=false，reason 说明责任卡，
   * 不影响工作区主链可用性（那是 capabilities.enabled 的职责）。
   */
  const computeReadiness = (): Record<string, unknown> => {
    const runner = supervisor.daemonStatus;
    const critical: { name: string; ready: boolean; reason: string | null }[] = [
      { name: "engine", ready: engineVersion !== null, reason: engineVersion === null ? "发行版 manifest 不可读" : null },
      {
        name: "templates",
        ready: templateResourcesReadable(),
        reason: templateResourcesReadable() ? null : "模板目录/catalog 不可读",
      },
      { name: "runner", ready: runner !== null, reason: runner === null ? "runner daemon 未完成握手" : null },
      {
        name: "config",
        ready: config.internalToken.length >= 32,
        reason: config.internalToken.length >= 32 ? null : "内部 token 未配置",
      },
    ];
    const pending: { name: string; ready: boolean; reason: string | null }[] = [
      { name: "programs", ready: false, reason: "程序状态探测由后续接线卡提供" },
      { name: "browser", ready: false, reason: "浏览器/渲染探测由后续接线卡提供" },
      {
        name: "studio",
        ready: config.sessionAssertionSecret.length >= 32,
        reason: config.sessionAssertionSecret.length >= 32
          ? null
          : "HYPIT_SESSION_ASSERTION_SECRET 未配置（≥32 字符）",
      },
      { name: "preview", ready: config.studioProxyPort > 0 && config.sessionAssertionSecret.length >= 32,
        reason: config.studioProxyPort > 0 && config.sessionAssertionSecret.length >= 32
          ? "preview surface wired"
          : "preview surface needs HYPIT_STUDIO_PORT and HYPIT_SESSION_ASSERTION_SECRET" },
    ];
    return {
      ok: critical.every((dep) => dep.ready),
      version: engineVersion,
      dependencies: [...critical, ...pending],
      runner,
    };
  };
  /** 模板资源可读性（启动断言的只读复查，不重复完整校验）。 */
  const templateResourcesReadable = (): boolean => {
    try {
      return existsSync(join(config.fixturesRoot, "minimal-local", "main.svrun")) && existsSync(config.templatesRoot);
    } catch {
      return false;
    }
  };
  // C107F2-32 (§6.15): maintenance window is a PERSISTENT single-slot lease in
  // the command store. The in-memory flag is only the fast-path gate — the
  // durable row means a broker restart INSIDE a window stays fail-closed (new
  // side effects keep being refused) until the recorded holder exits with its
  // leaseId. Draining never kills in-flight work: enter closes the gate FIRST
  // (atomic), then waits for queued/dispatching/acknowledged commands, native
  // build evidence and in-flight write streams to reach a safe point (bounded
  // 60s; timeout reports the in-flight business ids instead of lying).
  let maintenanceActive = store.readMaintenanceLease() !== undefined;
  let activeWriteStreams = 0;
  // C107-05: one handle registry shared by dispatcher media tools and the
  // internal resource routes (same index file + store, so handles resolve
  // identically from both paths).
  const projectsRoot = resolve(config.dataRoot, "../projects");
  const resourcesRoot = resolve(config.dataRoot, "../resources");
  const registry = mediaHandleRegistry(store, resourcesRoot, projectsRoot);
  // C107F2-35 (§7.4): staging reclaim sweep — unfinished upload transfers /
  // failed imports are TTL-reclaimed (24h) on a bounded ≤60s cadence. Failures
  // leave entries in place (retryable next sweep); the interval is unref'd so
  // it never holds the process open.
  const stagingRootForTransfers = resolve(projectsRoot, "..");
  const stagingSweeper = setInterval(() => {
    void purgeExpiredTransfers(stagingRootForTransfers).catch(() => {});
  }, 60_000);
  stagingSweeper.unref();
  const studioSweeper = setInterval(() => reapExpiredStudioSessions(), 30_000);
  studioSweeper.unref();
  // C107F2-07（F27 修复）：真实执行路径的授权桥——provider 提交前经 Java
  // prepare/complete/fail/cancel（operationId 稳定；预算/scope/幂等权威在 Java）。
  const executionAuthorizer = new ExecutionAuthorizer(httpExecutionBridge({
    baseUrl: config.javaInternalBaseUrl,
    token: config.internalToken,
  }));
  const dispatcherOptions = {
    store,
    projectsRoot,
    // C03（D-03/F03 修复）：模板根走配置唯一表（HYPIT_FIXTURES_ROOT），镜像内
    // /app/platform-hypit/fixtures；不再从 dataRoot 相对路径猜出 /data/fixtures。
    templateDir: resolve(config.fixturesRoot, "minimal-local"),
    templateFiles: [...TEMPLATE_FILES],
    templatesRoot: config.templatesRoot,
    // C107F2-05（D-05）：clone/brief 中性骨架根。
    blankDir: resolve(config.fixturesRoot, "blank"),
    engineExecutor: legacyEngineDispatch,
    distributionRoot: config.generatedRoot,
    captureBrowserCache: config.captureBrowserCache,
    // C107F-06（D-08）：packages.build 的 tsc 经 runner 槽执行（同 supervisor 单执行槽）。
    runnerSupervisor: supervisor,
  };

  const server = createServer((request, response) => {
    void handle(request, response).catch((error: unknown) => {
      response.writeHead(500, { "content-type": "application/json" });
      response.end(JSON.stringify({
        error: error instanceof Error ? error.message : String(error),
      }));
    });
  });

  // C107F2-19：Studio 会话代理面——独立端口（默认 9464），凭 Nginx auth_request
  // 下发的 session-access 断言准入，不持全局 internal bearer（F16 修复：签票
  // 入口不被内部闸门挡住）。断言密钥未配置时不监听（readiness 如实 false）。
  // C107F2-22：同一端口同一条 access 链承载 /preview/<sid>/ 资源面（快照映射+Range）。
  const studioProxyHandler = createStudioProxyHandler({
    assertionSecret: config.sessionAssertionSecret,
    writeback: httpStudioWritebackClient({
      baseUrl: config.javaInternalBaseUrl,
      token: config.internalToken,
    }),
  });
  const previewSurfaceHandler = createPreviewSurfaceHandler({
    assertionSecret: config.sessionAssertionSecret,
  });
  const studioProxy = config.studioProxyPort > 0
    ? createServer((request, response) => {
        const url = new URL(request.url ?? "/", "http://studio");
        const handler = studioProxyHandler.matches(url.pathname)
          ? studioProxyHandler
          : previewSurfaceHandler.matches(url.pathname) ? previewSurfaceHandler : null;
        if (handler !== null) {
          void handler.handle(request, response, url).catch((error: unknown) => {
            response.writeHead(500, { "content-type": "application/json" });
            response.end(JSON.stringify({ error: error instanceof Error ? error.message : String(error) }));
          });
          return;
        }
        response.writeHead(404, { "content-type": "application/json" });
        response.end(JSON.stringify({ error: "unknown studio surface" }));
      })
    : null;
  if (studioProxy !== null) {
    studioProxy.on("upgrade", (request, socket, head) => {
      const url = new URL(request.url ?? "/", "http://studio");
      if (!studioProxyHandler.matches(url.pathname) || url.pathname === "/studio") {
        socket.destroy();
        return;
      }
      studioProxyHandler.handleUpgrade(request, socket, head, url);
    });
    studioProxy.listen(config.studioProxyPort, config.host);
  }

  async function handle(request: import("node:http").IncomingMessage, response: import("node:http").ServerResponse): Promise<void> {
    const url = new URL(request.url ?? "/", "http://internal");
    if (request.method === "GET" && url.pathname === "/healthz") {
      // 进程存活 ≠ 功能可用（F05 属 C09）；此处只报进程事实 + runner 执行面
      // 归属（C04：broker 内作者执行计数必须恒 0，执行在 runner 容器 daemon）。
      response.writeHead(200, { "content-type": "application/json" });
      response.end(JSON.stringify({
        ok: true,
        enginePort: "y1.hypit-engine-port@1",
        distributionRoot: config.generatedRoot,
        runner: {
          authorProcessSpawnCount: supervisor.authorProcessSpawnCount,
          daemonStatus: supervisor.daemonStatus,
        },
      }));
      return;
    }
    if (request.method === "GET" && url.pathname === "/internal/v1/readiness") {
      // C09：token 闸门在上方统一校验；缓存 5 秒内直接回放，避免高频探测
      // 打到模板/runner 状态检查。ok 只覆盖关键依赖（engine/templates/runner/
      // config）；studio/preview/程序/浏览器探测由后续接线卡提供真实结果，
      // 如实 ready=false 而不是从 ok 里消失。
      const now = Date.now();
      if (readinessCache === null || now - readinessCache.at > 5000) {
        readinessCache = { at: now, body: computeReadiness() };
      }
      response.writeHead(200, { "content-type": "application/json" });
      response.end(JSON.stringify(readinessCache.body));
      return;
    }
    if (url.pathname.startsWith("/internal/") && config.internalToken.length === 0) {
      response.writeHead(503, { "content-type": "application/json" });
      response.end(JSON.stringify({ error: "internal endpoints disabled: HYPIT_INTERNAL_TOKEN not configured" }));
      return;
    }
    const authorization = request.headers.authorization ?? "";
    if (authorization !== `Bearer ${config.internalToken}`) {
      response.writeHead(401, { "content-type": "application/json" });
      response.end(JSON.stringify({ error: "invalid internal token" }));
      return;
    }
    if (request.method === "GET" && url.pathname.startsWith("/internal/v1/commands/")) {
      const commandId = decodeURIComponent(url.pathname.split("/").pop() ?? "");
      const command = store.get(commandId);
      if (command === undefined) {
        response.writeHead(404, { "content-type": "application/json" });
        response.end(JSON.stringify({ error: "unknown command" }));
        return;
      }
      response.writeHead(200, { "content-type": "application/json" });
      response.end(JSON.stringify(toBrokerShape(command)));
      return;
    }
    if (request.method === "PUT" && url.pathname.startsWith("/internal/v1/package-transfers/")
      && url.pathname.endsWith("/content")) {
      // C107F2-30 (W186): browser upload landing — Java streams the multipart
      // zip here AFTER verifying the caller; the broker only stages bytes.
      if (maintenanceActive) {
        response.writeHead(503, { "content-type": "application/json" });
        response.end(JSON.stringify({ error: "broker is in maintenance mode: transfer upload refused" }));
        return;
      }
      const transferId = decodeURIComponent(url.pathname.split("/").slice(-2)[0] ?? "");
      activeWriteStreams += 1;
      try {
        const stored = await storeTransferContent(resolve(projectsRoot, ".."), transferId, request);
        response.writeHead(201, { "content-type": "application/json" });
        response.end(JSON.stringify(stored));
      } catch (error: unknown) {
        const code = error instanceof TransferError ? error.code : "engine_error";
        const status = code === "too_large" ? 413 : code === "invalid_input" || code === "not_found" ? 400 : 500;
        if (!response.headersSent) {
          response.writeHead(status, { "content-type": "application/json" });
          response.end(JSON.stringify({ error: (error as Error).message, code }));
        } else {
          response.destroy();
        }
      } finally {
        activeWriteStreams -= 1;
      }
      return;
    }
    if (request.method === "GET" && url.pathname.startsWith("/internal/v1/package-transfers/")
      && url.pathname.endsWith("/content")) {
      const transferId = decodeURIComponent(url.pathname.split("/").slice(-2)[0] ?? "");
      try {
        await serveTransferContent(resolve(projectsRoot, ".."), transferId, request, response);
      } catch (error: unknown) {
        const code = error instanceof TransferError ? error.code : "engine_error";
        const status = code === "invalid_input" || code === "not_found" ? 400 : 500;
        if (!response.headersSent) {
          response.writeHead(status, { "content-type": "application/json" });
          response.end(JSON.stringify({ error: (error as Error).message, code }));
        } else {
          response.destroy();
        }
      }
      return;
    }
    if (request.method === "POST" && url.pathname === "/internal/v1/maintenance/enter") {
      const body = await readJsonBody(request);
      const reason = typeof body.reason === "string" && body.reason.length > 0 ? body.reason : "unspecified";
      // Single-slot lease: another operator's window is 409, never "already mine".
      const leaseId = crypto.randomUUID();
      if (!store.tryAcquireMaintenanceLease(leaseId, reason)) {
        response.writeHead(409, { "content-type": "application/json" });
        response.end(JSON.stringify({ error: "maintenance window held by another operator" }));
        return;
      }
      // Atomic fence FIRST (refuse new side effects from this instant), then
      // count what is still in flight and wait for a consistent point.
      maintenanceActive = true;
      // C107F2-38（TC-F2-38-03）：崩失在途回收——死进程遗留的 dispatching 行
      // 会让排空永不收敛（备份被永久阻塞）；静默超龄在途转 unknown，调用方按
      // receipt 重放收敛。新鲜在途不受影响（真实派发/重放都刷新 updated_at）。
      const staleMs = Number(process.env.HYPIT_MAINTENANCE_STALE_DISPATCH_MS ?? "900000");
      const sweptStale = store.sweepStaleInflight(staleMs);
      const timeoutMs = Number(process.env.HYPIT_MAINTENANCE_DRAIN_TIMEOUT_MS ?? "60000");
      const startedAt = Date.now();
      let counts = maintenanceCounts(dispatcherOptions);
      while (counts.activeCommands + counts.activeBuilds + activeWriteStreams > 0
        && Date.now() - startedAt < timeoutMs) {
        await delay(200);
        counts = maintenanceCounts(dispatcherOptions);
      }
      const drained = counts.activeCommands + counts.activeBuilds + activeWriteStreams === 0;
      response.writeHead(200, { "content-type": "application/json" });
      response.end(JSON.stringify({
        leaseId,
        drained,
        activeCommands: counts.activeCommands,
        activeBuilds: counts.activeBuilds,
        activeWrites: activeWriteStreams,
        waitedMs: Date.now() - startedAt,
        inflight: counts.inflight,
        sweptStale,
      }));
      return;
    }
    if (request.method === "POST" && url.pathname === "/internal/v1/maintenance/exit") {
      const body = await readJsonBody(request);
      const leaseId = typeof body.leaseId === "string" ? body.leaseId : "";
      // Only the recorded holder may lift the window (TC-F2-32-04).
      if (leaseId.length === 0 || !store.releaseMaintenanceLease(leaseId)) {
        response.writeHead(409, { "content-type": "application/json" });
        response.end(JSON.stringify({ error: "maintenance window belongs to another lease" }));
        return;
      }
      maintenanceActive = false;
      response.writeHead(200, { "content-type": "application/json" });
      response.end(JSON.stringify({ ok: true }));
      return;
    }
    if (request.method === "POST" && url.pathname === "/internal/v1/commands") {
      const body = await readJsonBody(request);
      const commandId = typeof body.commandId === "string" ? body.commandId : crypto.randomUUID();
      const kind = typeof body.kind === "string" ? body.kind : "";
      // C107F2-32: fence exempts cancellation/receipt convergence so in-flight
      // work can reach a safe terminal state during the drain; all other new
      // side effects are refused until the holder exits.
      if (maintenanceActive && !MAINTENANCE_EXEMPT_KINDS.has(kind)) {
        response.writeHead(503, { "content-type": "application/json" });
        response.end(JSON.stringify({ error: "broker is in maintenance mode: new commands refused" }));
        return;
      }
      const payload = (body.payload ?? {}) as Record<string, unknown>;
      try {
        const outcome = await dispatchCommand(dispatcherOptions, commandId, kind, payload);
        response.writeHead(200, { "content-type": "application/json" });
        response.end(JSON.stringify(toBrokerShape(outcome.command)));
      } catch (error) {
        const failed = store.get(commandId);
        response.writeHead(failed === undefined ? 400 : 200, { "content-type": "application/json" });
        response.end(JSON.stringify(failed === undefined
          ? { error: error instanceof Error ? error.message : String(error) }
          : toBrokerShape(failed)));
      }
      return;
    }
    if (request.method === "POST" && url.pathname === "/internal/v1/resources") {
      if (maintenanceActive) {
        response.writeHead(503, { "content-type": "application/json" });
        response.end(JSON.stringify({ error: "broker is in maintenance mode: resource ingest refused" }));
        return;
      }
      // C107F2-32: an ingest already past the fence when the gate closes is
      // real in-flight work — the drain waits for it instead of mid-cutting.
      activeWriteStreams += 1;
      try {
        await ingestResource(request, response, registry);
      } finally {
        activeWriteStreams -= 1;
      }
      return;
    }
    if (request.method === "GET" && url.pathname.startsWith("/internal/v1/resources/")) {
      const handle = decodeURIComponent(url.pathname.split("/").pop() ?? "");
      await serveResource(request, response, registry, handle);
      return;
    }
    response.writeHead(404, { "content-type": "application/json" });
    response.end(JSON.stringify({ error: "not found" }));
  }

  /** Stored row → §6.3 wire shape {commandId,state,result?,error?}. */
  function toBrokerShape(command: {
    readonly commandId: string;
    readonly state: string;
    readonly resultJson: string | null;
    readonly errorCode: string | null;
    readonly errorMessage: string | null;
    readonly engineBuildId: string | null;
  }): Record<string, unknown> {
    return {
      commandId: command.commandId,
      state: command.state,
      ...(command.resultJson === null ? {} : { result: JSON.parse(command.resultJson) }),
      ...(command.errorCode === null ? {} : { error: { code: command.errorCode, message: command.errorMessage } }),
    };
  }

  /** C02 engine kinds, now reached through the durable dispatcher. */
  async function legacyEngineDispatch(kind: string, payload: unknown): Promise<unknown> {
    assertConfigured(config);
    if (kind === "check") {
      return await runCheckedCopy(payload);
    }
    if (kind === "render.local") {
      return await runLocalRender(payload);
    }
    if (kind === "plan") {
      return await runPlan(payload);
    }
    if (kind === "pricing") {
      return await runPricing(payload);
    }
    if (kind === "vocabulary") {
      // C107F-05（API-F08）：surface（逗号分隔或数组）与 visual（空串=形状清单）透传；
      // 入参错误（未知包/未知形状）转 invalid_input 语义（命令行 400 面）。
      const record = (payload ?? {}) as { readonly surface?: unknown; readonly visual?: unknown };
      const surfaceList = typeof record.surface === "string" && record.surface.trim() !== ""
        ? record.surface.split(",").map((part) => part.trim()).filter((part) => part !== "")
        : Array.isArray(record.surface) && record.surface.length > 0
          ? record.surface.map(String)
          : undefined;
      const visualValue = typeof record.visual === "string" ? record.visual : undefined;
      const options: import("./engine/vocabulary.ts").VocabularyOptions = {
        ...(surfaceList === undefined ? {} : { surface: surfaceList }),
        ...(visualValue === undefined ? {} : { visual: visualValue }),
      };
      try {
        return await describeVocabulary(config.generatedRoot, options);
      } catch (error) {
        if (error instanceof VocabularyInputError) {
          throw new DispatchError("invalid_input", error.message);
        }
        throw error;
      }
    }
    if (kind === "status") {
      return { state: "ready", engine: "loaded", distributionRoot: config.generatedRoot };
    }
    throw new Error(`unknown command kind "${kind}"`);
  }

  /**
   * C107F2-06（RULE-04/F25）：不可变执行根解析。
   *
   * 正式请求只允许 projectId（+可选 revision）：源码根=该 revision 的冻结快照；
   * 未指定 revision 时冻结当前 head（plan 时点快照，之后的 work 编辑不影响本
   * 操作）。快照缺失/被篡改 → 明确失败，绝不回退当前 work（TC-F2-06-04）。
   * sourceDir 仅保留给可信 fixture/工具入口（测试直调），生产 Java 载荷不含。
   */
  async function resolveFrozenRoot(projectId: string, requestedRevision?: number): Promise<{
    readonly root: string;
    readonly revision: number;
    readonly manifestHash: string;
  }> {
    const projectRoot = projectRootFor(projectsRoot, projectId);
    if (requestedRevision !== undefined) {
      const revisionMeta = join(projectRoot, "revisions", String(requestedRevision), "revision.json");
      if (!existsSync(revisionMeta)) {
        throw new DispatchError("not_found", `revision ${requestedRevision} snapshot missing for project`);
      }
      const meta = JSON.parse(readFileSync(revisionMeta, "utf8")) as { revision?: number; manifestHash?: string };
      if (typeof meta.revision !== "number" || typeof meta.manifestHash !== "string") {
        throw new DispatchError("engine_error", `revision ${requestedRevision} snapshot metadata corrupt`);
      }
      // 快照完整性：逐字节重算（verifySnapshot）+ manifest 清单本身对得上
      // revision.json 记录的摘要——任一不符 = 快照被篡改/损坏，明确失败，
      // 绝不回退当前 work（TC-F2-06-04）。
      const verification = await verifySnapshot(projectRoot, requestedRevision);
      if (!verification.ok) {
        throw new DispatchError("engine_error",
          `revision ${requestedRevision} snapshot integrity verification failed`);
      }
      try {
        const manifest = await readManifestFile(join(projectRoot, "revisions", String(requestedRevision), "manifest.json"));
        if (computeManifestHash(manifest) !== meta.manifestHash) {
          throw new DispatchError("engine_error",
            `revision ${requestedRevision} manifest does not match its recorded digest`);
        }
      } catch (error) {
        if (error instanceof DispatchError) throw error;
        throw new DispatchError("engine_error",
          `revision ${requestedRevision} snapshot manifest is unreadable`);
      }
      return { root: join(projectRoot, "revisions", String(requestedRevision)), revision: meta.revision, manifestHash: meta.manifestHash };
    }
    const snapshot = await snapshotRevision(projectRoot, await currentHeadRevision(projectRoot));
    return { root: snapshot.snapshotDir, revision: snapshot.revision, manifestHash: snapshot.manifestHash };
  }

  async function currentHeadRevision(projectRoot: string): Promise<number> {
    const head = await readWorkspaceHead(projectRoot);
    if (head === null) throw new DispatchError("engine_error", "workspace has no published head");
    return head.revision;
  }

  /**
   * C107-08 plan flow (mirrors render.local's trust split): compile + needs
   * evaluation in the isolated runner slot, then pure Host reads (providers/
   * preflight) on the trusted side against the PROJECT workspace profile.
   * C107F2-06：源码根改为冻结快照；profile 摘要随计划返回（绑定 RULE-04）。
   */
  async function runPlan(payload: unknown): Promise<unknown> {
    const record = payload as {
      readonly sourceDir?: unknown;
      readonly projectId?: unknown;
      readonly revision?: unknown;
      readonly runFile?: unknown;
    };
    if (typeof record.runFile !== "string") {
      throw new Error("plan requires projectId (or trusted sourceDir) and runFile");
    }
    const runFile = record.runFile as string;
    // F25 修复：projectId 路径下 copyTree 曾引用未提供的 record.sourceDir。
    // 正式链路（projectId）一律走冻结快照；sourceDir 仅限可信 fixture 入口。
    const frozen = typeof record.projectId === "string"
      ? await resolveFrozenRoot(record.projectId as string,
          typeof record.revision === "number" ? record.revision : undefined)
      : typeof record.sourceDir === "string"
        ? { root: resolve(record.sourceDir as string), revision: 0, manifestHash: "" }
        : undefined;
    if (frozen === undefined) throw new Error("plan requires projectId (or trusted sourceDir) and runFile");
    const workspaceRoot = typeof record.projectId === "string"
      ? projectRootFor(projectsRoot, record.projectId as string)
      : frozen.root;
    if (typeof record.projectId === "string") {
      await ensureRuntimeProfile(config.generatedRoot, workspaceRoot);
    }
    const runner = await supervisor.withSlot(async (lease) => {
      await copyTree(frozen.root, lease.inputDir);
      return await lease.request("plan", { workspaceRoot: lease.inputDir, runFile }) as import("./engine/planning.ts").RunnerPlanOutput;
    });
    const host = await openEngineHost(
      { distributionRoot: config.generatedRoot, attachmentSourceDir: "" },
      workspaceRoot,
    );
    const doc = await assemblePlanDocument(host, runner);
    // RULE-04：计划绑定 revision+manifestHash+profileHash（Java 持久化并在
    // build 提交时回传比对；profile 摘要不含凭据值）。
    const profileHash = await runtimeProfileDigest(workspaceRoot);
    return { ...doc, revision: frozen.revision, manifestHash: frozen.manifestHash, profileHash };
  }

  /** Pricing reads for the exact planned requests (Host-only, may refresh OAuth). */
  async function runPricing(payload: unknown): Promise<unknown> {
    const record = payload as {
      readonly sourceDir?: unknown;
      readonly projectId?: unknown;
      readonly revision?: unknown;
      readonly runFile?: unknown;
    };
    if (typeof record.runFile !== "string") {
      throw new Error("pricing requires projectId (or trusted sourceDir) and runFile");
    }
    const runFile = record.runFile as string;
    const frozen = typeof record.projectId === "string"
      ? await resolveFrozenRoot(record.projectId as string,
          typeof record.revision === "number" ? record.revision : undefined)
      : typeof record.sourceDir === "string"
        ? { root: resolve(record.sourceDir as string), revision: 0, manifestHash: "" }
        : undefined;
    if (frozen === undefined) throw new Error("pricing requires projectId (or trusted sourceDir) and runFile");
    const workspaceRoot = typeof record.projectId === "string"
      ? projectRootFor(projectsRoot, record.projectId as string)
      : frozen.root;
    if (typeof record.projectId === "string") {
      await ensureRuntimeProfile(config.generatedRoot, workspaceRoot);
    }
    const runner = await supervisor.withSlot(async (lease) => {
      await copyTree(frozen.root, lease.inputDir);
      return await lease.request("plan", { workspaceRoot: lease.inputDir, runFile }) as import("./engine/planning.ts").RunnerPlanOutput;
    });
    const host = await openEngineHost(
      { distributionRoot: config.generatedRoot, attachmentSourceDir: "" },
      workspaceRoot,
    );
    const doc = await assemblePricingDocument(host, runner);
    const profileHash = await runtimeProfileDigest(workspaceRoot);
    return { ...doc, revision: frozen.revision, manifestHash: frozen.manifestHash, profileHash };
  }

  async function runCheckedCopy(payload: unknown): Promise<unknown> {
    const record = payload as {
      readonly sourceDir?: unknown;
      readonly projectId?: unknown;
      readonly revision?: unknown;
      readonly entryFile?: unknown;
    };
    // C107F2-06：projectId 正式路径冻结到 revision 快照（未指定=head）；
    // sourceDir 仅可信 fixture 入口。entryFile 解析层级统一（work 文件名）。
    const sourceDir = typeof record.projectId === "string"
      ? (await resolveFrozenRoot(record.projectId as string,
          typeof record.revision === "number" ? record.revision : undefined)).root
      : typeof record.sourceDir === "string"
        ? (record.sourceDir as string)
        : undefined;
    if (sourceDir === undefined || typeof record.entryFile !== "string") {
      throw new Error("check requires projectId (or trusted sourceDir) and entryFile");
    }
    return await supervisor.withSlot(async (lease) => {
      await copyTree(sourceDir, lease.inputDir);
      return await lease.request("check", {
        workspaceRoot: lease.inputDir,
        entryFile: record.entryFile,
      });
    });
  }

  async function runLocalRender(payload: unknown): Promise<unknown> {
    const record = payload as {
      readonly workspaceRoot?: unknown;
      readonly sourceDir?: unknown;
      readonly projectId?: unknown;
      readonly revision?: unknown;
      readonly manifestHash?: unknown;
      readonly profileHash?: unknown;
      readonly runFile?: unknown;
      readonly engineBuildId?: unknown;
      readonly title?: unknown;
    };
    for (const [name, value] of Object.entries({
      workspaceRoot: record.workspaceRoot,
      runFile: record.runFile,
    })) {
      if (typeof value !== "string") throw new Error(`render.local requires ${name}`);
    }
    const workspaceRoot = resolve(record.workspaceRoot as string);
    const runFile = record.runFile as string;
    // C107F2-06（F26/RULE-04）：build.submit 携带冻结绑定（revision/manifestHash/
    // profileHash）时，编译闭包取该 revision 快照并核验 manifest 与 profile 摘要；
    // 任一不匹配 → plan_stale（明确失败），绝不回退当前 work。
    let sourceDir = record.sourceDir as string | undefined;
    if (typeof record.projectId === "string") {
      const frozen = await resolveFrozenRoot(record.projectId as string,
        typeof record.revision === "number" ? record.revision : undefined);
      if (typeof record.manifestHash === "string" && record.manifestHash.length > 0
          && record.manifestHash !== frozen.manifestHash) {
        throw new DispatchError("plan_stale",
          `build bound to manifest ${String(record.manifestHash).slice(0, 12)}… but revision ${frozen.revision} snapshot is ${frozen.manifestHash.slice(0, 12)}…`);
      }
      if (typeof record.profileHash === "string" && record.profileHash.length > 0) {
        const currentProfile = await runtimeProfileDigest(workspaceRoot);
        if (record.profileHash !== currentProfile) {
          throw new DispatchError("plan_stale",
            `build profile digest ${record.profileHash.slice(0, 12)}… no longer matches workspace profile`);
        }
      }
      sourceDir = frozen.root;
    }
    if (typeof sourceDir !== "string") throw new Error("render.local requires sourceDir or frozen projectId");
    await ensureRuntimeProfile(config.generatedRoot, workspaceRoot);
    const repositoryLocation = await openProjectResultsLocation(
      { distributionRoot: config.generatedRoot, attachmentSourceDir: "" },
      workspaceRoot,
    );
    // The render Worker is a trusted, persistent executor (managed programs,
    // browsers, ffmpeg); it is not the isolated author runner and outlives slots.
    {
      const engineHost = await openEngineHost(
        { distributionRoot: config.generatedRoot, attachmentSourceDir: "" },
        workspaceRoot,
      );
      const controller = await engineHost.controller();
      await controller.programs.up({ maxWaitMs: 300_000 });
      await controller.worker.up({ maxWaitMs: 60_000 });
    }
    const grantId = typeof (record as { readonly grantId?: unknown }).grantId === "string"
      ? (record as { readonly grantId: string }).grantId
      : undefined;
    const executionTargets = Array.isArray((record as { readonly targets?: unknown }).targets)
      ? ((record as { readonly targets: readonly unknown[] }).targets.filter((item): item is string => typeof item === "string"))
      : undefined;
    const submission = await supervisor.withSlot(async (lease) => {
      await copyTree(sourceDir, lease.inputDir);
      const compiled = await lease.request("compile", {
        workspaceRoot: lease.inputDir,
        runFile,
      }) as import("./engine/engine-port.ts").CompiledRunRequest;
      // C107F2-07（RULE-05 先 prepared 后副作用）：带 grantId 的提交在真正
      // submit 前对该构建的远程 Need 逐个取得许可；许可失败构建不提交
      // （Provider 调用计数为 0）。无远程 Need 的全本地构建 grantId 可空。
      if (grantId !== undefined) {
        await authorizeBuildNeeds(executionAuthorizer, {
          projectId: typeof record.projectId === "string" ? record.projectId : undefined,
          engineBuildId: typeof record.engineBuildId === "string" ? record.engineBuildId : "pending",
          grantId,
          targets: executionTargets ?? [],
          workspaceRoot: lease.inputDir,
          runFile,
          estimatedCost: typeof (record as { readonly estimatedCost?: unknown }).estimatedCost === "number"
            ? (record as { readonly estimatedCost: number }).estimatedCost
            : (undefined as unknown as number),
        });
      }
      return await submitBuild(
        {
          distributionRoot: config.generatedRoot,
          attachmentSourceDir: lease.outputDir,
        },
        {
          engineBuildId: typeof record.engineBuildId === "string"
            ? record.engineBuildId
            : engine.orderedBuildId(Date.now(), randomHex()),
          workspaceRoot,
          runFile,
          repositoryLocation,
          ...(typeof record.title === "string" ? { title: record.title } : {}),
        },
        compiled,
      );
    });
    return submission;
  }

  server.listen(config.port, config.host);
  const shutdown = async (): Promise<void> => {
    clearInterval(stagingSweeper);
    clearInterval(studioSweeper);
    await new Promise<void>((resolvePromise) => server.close(() => resolvePromise()));
    if (studioProxy !== null) {
      await new Promise<void>((resolvePromise) => studioProxy.close(() => resolvePromise()));
    }
    // C107F2-19：受管 Studio 子进程随 broker 退出回收，不留孤儿。
    closeAllStudioSessions();
    closeAllPreviewSessions();
    await rm(config.runnerSlotRoot, { recursive: true, force: true }).catch(() => {});
  };
  process.once("SIGTERM", () => void shutdown());
  process.once("SIGINT", () => void shutdown());
}

/**
 * C09（TC-F2-09-02）：发行版 manifest 是版本唯一来源；读不到如实 null，不猜默认值。
 */
function readEngineVersion(generatedRoot: string): string | null {
  try {
    const manifest = JSON.parse(readFileSync(join(generatedRoot, "package.json"), "utf8")) as {
      version?: unknown;
    };
    return typeof manifest.version === "string" && manifest.version.length > 0 ? manifest.version : null;
  } catch {
    return null;
  }
}

async function readJsonBody(request: import("node:http").IncomingMessage): Promise<Record<string, unknown>> {
  const chunks: Buffer[] = [];
  let size = 0;
  for await (const chunk of request) {
    size += (chunk as Buffer).length;
    if (size > MAX_BODY_BYTES) throw new Error("request body too large");
    chunks.push(chunk as Buffer);
  }
  if (chunks.length === 0) return {};
  const parsed: unknown = JSON.parse(Buffer.concat(chunks).toString("utf8"));
  if (typeof parsed !== "object" || parsed === null) throw new Error("request body must be a JSON object");
  return parsed as Record<string, unknown>;
}

async function copyTree(source: string, target: string): Promise<void> {
  if (!existsSync(source)) throw new Error(`source directory does not exist: ${source}`);
  await mkdir(target, { recursive: true });
  await cp(source, target, { recursive: true, force: true });
}

function randomHex(): string {
  const bytes = new Uint8Array(5);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("").toUpperCase();
}

// Engine surface exports for Java-side integration tests (C03+)
export { inspectBuild, cancelBuild, stopWorker };

// ---------------------------------------------------------------------------------------------------
// C107-05 internal resource routes: ingest (K07) + Range serve (K09).

/** Mirrors HypitResourceService.MAX_UPLOAD_BYTES — refuse early, keep no partial file. */
const MAX_RESOURCE_BYTES = 500 * 1024 * 1024;

function sanitizeUploadName(raw: string | undefined): string | null {
  if (typeof raw !== "string") return null;
  const base = raw.split("/").pop()!.split("\\").pop()!.trim();
  if (base.length === 0 || base === "." || base === ".." || base.includes("\0") || base.length > 160) {
    return null;
  }
  return base;
}

/**
 * Stream the request body to the controlled resources root while hashing, then
 * register it. The broker never buffers the whole upload in memory; overflow or
 * connection failure leaves no partial file behind.
 */
async function ingestResource(
  request: import("node:http").IncomingMessage,
  response: import("node:http").ServerResponse,
  registry: HandleRegistry,
): Promise<void> {
  const uploadsRoot = join(registry.allowedRoots[0] ?? "", "uploads");
  const stagingRoot = join(uploadsRoot, ".staging");
  await mkdir(stagingRoot, { recursive: true });
  const providedName = sanitizeUploadName(request.headers["x-hypit-file-name"] as string | undefined);
  if (request.headers["x-hypit-file-name"] !== undefined && providedName === null) {
    response.writeHead(400, { "content-type": "application/json" });
    response.end(JSON.stringify({ error: "invalid file name" }));
    return;
  }
  const mediaType = typeof request.headers["content-type"] === "string" && request.headers["content-type"].length > 0
    ? request.headers["content-type"]
    : null;
  const staging = join(stagingRoot, `${Date.now().toString(36)}-${crypto.randomUUID()}`);
  const hash = createHash("sha256");
  let size = 0;
  let failed = false;
  try {
    await new Promise<void>((resolvePromise, rejectPromise) => {
      const sink = createWriteStream(staging);
      request.on("data", (chunk: Buffer) => {
        if (failed) return;
        size += chunk.length;
        if (size > MAX_RESOURCE_BYTES) {
          failed = true;
          sink.destroy();
          request.destroy();
          rejectPromise(new HandleError("too_large", "resource exceeds the 500MiB limit"));
          return;
        }
        hash.update(chunk);
        if (!sink.write(chunk)) {
          request.pause();
          sink.once("drain", () => request.resume());
        }
      });
      request.on("end", () => {
        if (failed) return;
        sink.end(() => resolvePromise());
      });
      request.on("error", (error) => {
        if (failed) return;
        failed = true;
        sink.destroy();
        rejectPromise(error);
      });
      sink.on("error", (error) => {
        if (failed) return;
        failed = true;
        request.destroy();
        rejectPromise(error);
      });
    });
    if (size === 0) throw new HandleError("empty_body", "resource upload had no bytes");
    const sha256 = hash.digest("hex");
    const finalName = providedName === null ? `${sha256}.bin` : `${sha256.slice(0, 8)}-${providedName}`;
    const target = join(uploadsRoot, finalName);
    await rename(staging, target);
    const record = await registerResource(registry, {
      absolutePath: target,
      projectId: null,
      mediaType,
      role: "upload",
      sha256,
      sizeBytes: size,
    });
    response.writeHead(200, { "content-type": "application/json" });
    response.end(JSON.stringify({ handle: record.handle, sha256: record.sha256, sizeBytes: record.sizeBytes }));
  } catch (error) {
    await rm(staging, { force: true }).catch(() => {});
    const code = error instanceof HandleError ? error.code : "ingest_failed";
    const status = code === "too_large" ? 413 : code === "empty_body" || code === "invalid_path" ? 400 : 500;
    if (!response.headersSent) {
      response.writeHead(status, { "content-type": "application/json" });
      response.end(JSON.stringify({
        error: error instanceof Error ? error.message : String(error),
        code,
      }));
    } else {
      response.destroy();
    }
  }
}

/**
 * Serve a registered resource with RFC 9110 single-range semantics. If-Range is
 * honored via the strong ETag `"sha256"`: a mismatching validator downgrades the
 * request to a full 200 body, matching what <video> scrubbing expects.
 */
async function serveResource(
  request: import("node:http").IncomingMessage,
  response: import("node:http").ServerResponse,
  registry: HandleRegistry,
  handle: string,
): Promise<void> {
  let record;
  try {
    record = await resolveResource(registry, handle);
  } catch (error) {
    if (error instanceof HandleError && (error.code === "not_found" || error.code === "invalid_handle")) {
      response.writeHead(error.code === "not_found" ? 404 : 400, { "content-type": "application/json" });
      response.end(JSON.stringify({ error: error.message, code: error.code }));
      return;
    }
    throw error;
  }
  const etag = `"${record.sha256}"`;
  const ifRange = request.headers["if-range"];
  // Date-form If-Range or any mismatching validator → ignore Range (full 200).
  const rangeHonored = typeof ifRange !== "string" || ifRange.trim() === etag;
  const decision = resolveRange(rangeHonored ? request.headers.range as string | undefined : undefined, record.sizeBytes);
  const serve = planRangeServe(record.sizeBytes, record.mediaType ?? "application/octet-stream", decision);
  const headers: Record<string, string> = { ...serve.headers, ETag: etag };
  response.writeHead(serve.status, headers);
  if (serve.length === 0) {
    response.end();
    return;
  }
  await new Promise<void>((resolvePromise) => {
    const stream = createReadStream(record.absolutePath, {
      start: serve.offset,
      end: serve.offset + serve.length - 1,
    });
    stream.on("error", () => {
      response.destroy();
      resolvePromise();
    });
    response.on("close", () => {
      stream.destroy();
      resolvePromise();
    });
    stream.pipe(response);
    stream.on("end", () => resolvePromise());
  });
}


/**
 * C107F2-07：构建提交前的远程 Need 授权（TC-F2-07-01/02）。
 *
 * 在 slot 内重跑 plan 得到 needs：pending=false 且非本地的 Need 视为远程执行
 * （与 assemblePlanDocument 的 providers/needs 语义一致）；每个远程 Need 以
 * 稳定 operationId（buildId:need）取得 Java 许可——全部通过才允许 submit。
 * 空执行 targets 或任一 target 未获授权 → 抛错（构建不提交，零 Provider 调用）。
 */
async function authorizeBuildNeeds(
  authorizer: ExecutionAuthorizer,
  info: {
    readonly projectId: string | undefined;
    readonly engineBuildId: string;
    readonly grantId: string;
    readonly targets: readonly string[];
    readonly workspaceRoot: string;
    readonly runFile: string;
    readonly estimatedCost?: number;
  },
): Promise<void> {
  if (info.targets.length === 0) {
    throw new DispatchError("invalid_input", "带 grantId 的构建必须声明执行 targets（空集合无授权效力）");
  }
  const { createHash } = await import("node:crypto");
  const { planRunInRunner } = await import("./engine/planning.ts");
  const plan: import("./engine/planning.ts").RunnerPlanOutput = await planRunInRunner(
    { distributionRoot: process.env.HYPIT_GENERATED_ROOT ?? "." },
    { workspaceRoot: info.workspaceRoot, runFile: info.runFile },
  );
  // 保守口径：带 grantId 的构建对所有 provider Need 逐个取得许可（宁多勿漏；
  // 本地能力也会过桥，但零费用记录不影响结算），任一未授权即整体拒绝。
  for (const need of plan.needs) {
    const requestHash = createHash("sha256")
      .update(`${info.engineBuildId}:${need.request}:${need.capability}:${info.targets.join(",")}`)
      .digest("hex");
    const request: PrepareRequest = {
      operationId: `${info.engineBuildId}:${need.request}`,
      ...(info.projectId === undefined ? {} : { projectId: info.projectId }),
      needId: need.request,
      capability: need.capability,
      model: need.capability,
      endpointId: need.capability,
      requestHash,
      grantId: info.grantId,
      targets: info.targets,
      // 估价来自冻结 pricing（Java 消费同一快照）；未知费用 grant 须显式
      // allowUnknownCost——Java 权威拒绝（RULE-05）。
      ...(info.estimatedCost === undefined ? {} : { estimatedCost: info.estimatedCost }),
    };
    await authorizer.authorize(request);
  }
}
