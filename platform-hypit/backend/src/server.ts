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
import { existsSync } from "node:fs";
import { join, resolve } from "node:path";

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
import { assertConfigured, ensureRuntimeDirs, loadConfig, type HypitBackendConfig } from "./config.ts";
import { CommandStore } from "./commands/store.ts";
import { dispatchCommand } from "./commands/dispatcher.ts";
import { assemblePlanDocument, assemblePricingDocument } from "./engine/planning.ts";
import { describeVocabulary } from "./engine/vocabulary.ts";
import { mediaHandleRegistry } from "./tools/media.ts";
import { HandleError, registerResource, resolveResource, type HandleRegistry } from "./resources/handles.ts";
import { planRangeServe, resolveRange } from "./resources/stream.ts";

export type BrokerCommand = {
  readonly commandId: string;
  readonly kind: string;
  state: "queued" | "running" | "succeeded" | "failed";
  result?: unknown;
  error?: { code: string; message: string };
};

const MAX_BODY_BYTES = 1024 * 1024;
const TEMPLATE_FILES = ["package.json", "main.svml", "main.svrun"] as const;

export async function runServer(overrides?: Partial<HypitBackendConfig>): Promise<void> {
  const base = loadConfig();
  const config: HypitBackendConfig = { ...base, ...overrides };
  ensureRuntimeDirs(config);
  const engine = await loadHypit(config.generatedRoot);
  const supervisor = new RunnerSupervisor({
    backendRoot: resolve(config.generatedRoot, "../../backend"),
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
  // C107-05: one handle registry shared by dispatcher media tools and the
  // internal resource routes (same index file + store, so handles resolve
  // identically from both paths).
  const projectsRoot = resolve(config.dataRoot, "../projects");
  const resourcesRoot = resolve(config.dataRoot, "../resources");
  const registry = mediaHandleRegistry(store, resourcesRoot, projectsRoot);
  const dispatcherOptions = {
    store,
    projectsRoot,
    templateDir: resolve(config.dataRoot, "../../fixtures/minimal-local"),
    templateFiles: [...TEMPLATE_FILES],
    engineExecutor: legacyEngineDispatch,
    distributionRoot: config.generatedRoot,
  };

  const server = createServer((request, response) => {
    void handle(request, response).catch((error: unknown) => {
      response.writeHead(500, { "content-type": "application/json" });
      response.end(JSON.stringify({
        error: error instanceof Error ? error.message : String(error),
      }));
    });
  });

  async function handle(request: import("node:http").IncomingMessage, response: import("node:http").ServerResponse): Promise<void> {
    const url = new URL(request.url ?? "/", "http://internal");
    if (request.method === "GET" && url.pathname === "/healthz") {
      response.writeHead(200, { "content-type": "application/json" });
      response.end(JSON.stringify({
        ok: true,
        enginePort: "y1.hypit-engine-port@1",
        distributionRoot: config.generatedRoot,
      }));
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
    if (request.method === "POST" && url.pathname === "/internal/v1/commands") {
      const body = await readJsonBody(request);
      const commandId = typeof body.commandId === "string" ? body.commandId : crypto.randomUUID();
      const kind = typeof body.kind === "string" ? body.kind : "";
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
      await ingestResource(request, response, registry);
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
      return await describeVocabulary(config.generatedRoot);
    }
    if (kind === "status") {
      return { state: "ready", engine: "loaded", distributionRoot: config.generatedRoot };
    }
    throw new Error(`unknown command kind "${kind}"`);
  }

  /**
   * C107-08 plan flow (mirrors render.local's trust split): compile + needs
   * evaluation in the isolated runner slot, then pure Host reads (providers/
   * preflight) on the trusted side against the PROJECT workspace profile.
   */
  async function runPlan(payload: unknown): Promise<unknown> {
    const record = payload as { readonly sourceDir?: unknown; readonly projectId?: unknown; readonly runFile?: unknown };
    const workspaceRoot = typeof record.sourceDir === "string"
      ? resolve(record.sourceDir as string)
      : typeof record.projectId === "string"
        ? projectRootFor(projectsRoot, record.projectId as string)
        : undefined;
    if (workspaceRoot === undefined || typeof record.runFile !== "string") {
      throw new Error("plan requires sourceDir (or projectId) and runFile");
    }
    const runFile = record.runFile as string;
    await ensureRuntimeProfile(config.generatedRoot, workspaceRoot);
    const runner = await supervisor.withSlot(async (lease) => {
      await copyTree(record.sourceDir as string, lease.inputDir);
      return await lease.request("plan", { workspaceRoot: lease.inputDir, runFile }) as import("./engine/planning.ts").RunnerPlanOutput;
    });
    const host = await openEngineHost(
      { distributionRoot: config.generatedRoot, attachmentSourceDir: "" },
      workspaceRoot,
    );
    return await assemblePlanDocument(host, runner);
  }

  /** Pricing reads for the exact planned requests (Host-only, may refresh OAuth). */
  async function runPricing(payload: unknown): Promise<unknown> {
    const record = payload as { readonly sourceDir?: unknown; readonly projectId?: unknown; readonly runFile?: unknown };
    const workspaceRoot = typeof record.sourceDir === "string"
      ? resolve(record.sourceDir as string)
      : typeof record.projectId === "string"
        ? projectRootFor(projectsRoot, record.projectId as string)
        : undefined;
    if (workspaceRoot === undefined || typeof record.runFile !== "string") {
      throw new Error("pricing requires sourceDir (or projectId) and runFile");
    }
    const runFile = record.runFile as string;
    await ensureRuntimeProfile(config.generatedRoot, workspaceRoot);
    const runner = await supervisor.withSlot(async (lease) => {
      await copyTree(record.sourceDir as string, lease.inputDir);
      return await lease.request("plan", { workspaceRoot: lease.inputDir, runFile }) as import("./engine/planning.ts").RunnerPlanOutput;
    });
    const host = await openEngineHost(
      { distributionRoot: config.generatedRoot, attachmentSourceDir: "" },
      workspaceRoot,
    );
    return await assemblePricingDocument(host, runner);
  }

  async function runCheckedCopy(payload: unknown): Promise<unknown> {
    const record = payload as { readonly sourceDir?: unknown; readonly projectId?: unknown; readonly entryFile?: unknown };
    // Java callers name the project; the workspace path is resolved on the
    // trusted side from the same projectsRoot provision used (C107-08).
    const sourceDir = typeof record.sourceDir === "string"
      ? (record.sourceDir as string)
      : typeof record.projectId === "string"
        ? projectRootFor(projectsRoot, record.projectId as string)
        : undefined;
    if (sourceDir === undefined || typeof record.entryFile !== "string") {
      throw new Error("check requires sourceDir (or projectId) and entryFile");
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
      readonly runFile?: unknown;
      readonly engineBuildId?: unknown;
      readonly title?: unknown;
    };
    for (const [name, value] of Object.entries({
      workspaceRoot: record.workspaceRoot,
      sourceDir: record.sourceDir,
      runFile: record.runFile,
    })) {
      if (typeof value !== "string") throw new Error(`render.local requires ${name}`);
    }
    const workspaceRoot = resolve(record.workspaceRoot as string);
    const sourceDir = record.sourceDir as string;
    const runFile = record.runFile as string;
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
    const submission = await supervisor.withSlot(async (lease) => {
      await copyTree(sourceDir, lease.inputDir);
      const compiled = await lease.request("compile", {
        workspaceRoot: lease.inputDir,
        runFile,
      }) as import("./engine/engine-port.ts").CompiledRunRequest;
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
    await new Promise<void>((resolvePromise) => server.close(() => resolvePromise()));
    await rm(config.runnerSlotRoot, { recursive: true, force: true }).catch(() => {});
  };
  process.once("SIGTERM", () => void shutdown());
  process.once("SIGINT", () => void shutdown());
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
