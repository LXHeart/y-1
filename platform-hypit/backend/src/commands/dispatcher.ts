// dispatcher.ts — C107-04 (task-107) command dispatch on the durable store.
//
// Replaces C107-02's in-memory command map: every internal command goes
// through bridge.sqlite idempotency (same commandId + payload hash replays
// the recorded result; a different hash is a conflict, K06.2 accept). Kinds
// registered here: workspace lifecycle (provision/apply/delete/listing/read)
// plus the C02 legacy engine kinds (check / render.local / status) rerouted
// through the same durable path.
import { cp, mkdir, rm, writeFile } from "node:fs/promises";
import { existsSync, mkdirSync } from "node:fs";
import { dirname, join, resolve } from "node:path";

import { CommandStore, CommandConflictError, type StoredCommand } from "./store.ts";
import { resolveResource } from "../resources/handles.ts";
import { appendJobEvent, appendTerminalJobEvent, readJobEvents } from "./events.ts";
import {
  computeWorkspaceManifest,
  listingEntries,
  type FileListingEntry,
} from "../workspace/manifest.ts";
import {
  applyWorkspaceChanges,
  readHead,
  readWorkspaceFile,
  recoverPendingTransactions,
  WorkspaceConflictError,
  type ApplyReceipt,
  type FileChange,
  type HeadState,
} from "../workspace/transactions.ts";
import { snapshotRevision, verifySnapshot } from "../workspace/revisions.ts";
import {
  projectRootFor,
  provisionFromTemplate,
  provisionWorkspace,
  removeWorkspace,
} from "../workspace/provision.ts";
import { resolveWithinWorkspace } from "../workspace/paths.ts";
import { runMediaTool, isMediaTool, mediaHandleRegistry, type MediaToolContext } from "../tools/media.ts";
import { runSpeechTool, isSpeechTool, type SpeechToolContext } from "../tools/speech.ts";
import { runImageTool, isImageTool, type ImageToolContext } from "../tools/image.ts";
import { isPackageTool, runPackageTool, type PackageToolContext } from "../tools/packages.ts";
import { isProgramId } from "../programs/catalog.ts";
import { ProgramsManager } from "../programs/manager.ts";
import { describeProviderCatalog } from "../providers/catalog.ts";
import { openCredentialStores, type CredentialStores } from "../providers/credentials.ts";
import { OAuthFlowService } from "../providers/oauth.ts";
import { RenderCapacity } from "../runtime/capacity.ts";
import {
  holdsLocalRender,
  observationDelta,
  observationSummary,
  type BuildObservation,
  type BuildOutcome,
  type ObservationSummary,
} from "../runtime/observer.ts";
import { readBuildLogs, type LogPage } from "../runtime/logs.ts";
import { runResultsRead, runResultsHistory, type DispatcherOptionsRef } from "../results/read.ts";
import { runResultsExport } from "../results/export.ts";
import { runResultsPresentation } from "../results/presentation.ts";
import { runResultsFinish, runResultsDiscard } from "../results/actions.ts";
import { runResultsReuse } from "../results/reuse.ts";
import { openPreviewSession } from "../preview/sessions.ts";
import { openStudioSession } from "../studio/sessions.ts";
import { readFeedback, mutateFeedback } from "../studio/feedback.ts";
import {
  allocateEngineBuildId,
  cancelBuild,
  engineActivity,
  observeEngineBuild,
  openEngineHost,
  openResultsRepository,
} from "../engine/runtime-adapter.ts";

export type DispatcherOptions = {
  readonly store: CommandStore;
  readonly projectsRoot: string;
  readonly templateDir?: string;
  readonly templateFiles?: readonly string[];
  readonly distributionRoot?: string;
  /** Engine-side executor for check/render (C02 surface, injected by server). */
  readonly engineExecutor?: (kind: string, payload: unknown) => Promise<unknown>;
  /** C107-09 broker render admission gate (D-05 default: one local render). */
  readonly capacity?: RenderCapacity;
  /** Observation poll interval for capacity release watchers (tests shrink). */
  readonly capacityPollMs?: number;
  /** C107-09 engine port override (scripted observations in unit tests). */
  readonly buildEngine?: BuildEnginePort;
};

export class DispatchError extends Error {
  constructor(
    readonly code: string,
    message: string,
  ) {
    super(message);
    this.name = "DispatchError";
  }
}

export type DispatchOutcome =
  | { readonly outcome: "replayed"; readonly command: StoredCommand }
  | { readonly outcome: "completed"; readonly command: StoredCommand };

/**
 * Idempotent dispatch: returns the recorded result for a replayed command
 * without re-running any file side effect (TC107-04-01: 并发同 body 单资源).
 */
export async function dispatchCommand(
  options: DispatcherOptions,
  commandId: string,
  kind: string,
  payload: Record<string, unknown>,
): Promise<DispatchOutcome> {
  const payloadHash = CommandStore.hashPayload(kind, payload);
  const projectId = typeof payload.projectId === "string" ? (payload.projectId as string) : null;
  const accepted = options.store.accept(commandId, kind, payloadHash, projectId);
  if (accepted.state === "succeeded" || accepted.state === "failed") {
    if (accepted.payloadHash !== payloadHash) {
      throw new CommandConflictError(`command ${commandId} replayed with different payload`);
    }
    return { outcome: "replayed", command: accepted };
  }
  options.store.transition(commandId, () => ({ state: "dispatching" }));
  try {
    const result = await runKind(options, commandId, kind, payload);
    const completed = options.store.transition(commandId, (row) => ({
      state: "succeeded",
      resultJson: JSON.stringify(result),
      errorCode: null,
      errorMessage: null,
      engineBuildId: typeof (result as { engineBuildId?: unknown }).engineBuildId === "string"
        ? ((result as { engineBuildId: string }).engineBuildId)
        : row.engineBuildId,
    }));
    return { outcome: "completed", command: completed };
  } catch (error) {
    const code = error instanceof DispatchError
      ? error.code
      : error instanceof CommandConflictError
        ? "command_conflict"
        : error instanceof WorkspaceConflictError
          ? "revision_conflict"
          : "engine_error";
    const failed = options.store.transition(commandId, () => ({
      state: "failed",
      errorCode: code,
      errorMessage: error instanceof Error ? error.message : String(error),
    }));
    throw new DispatchError(code, failed.errorMessage ?? "command failed");
  }
}

async function runKind(
  options: DispatcherOptions,
  commandId: string,
  kind: string,
  payload: Record<string, unknown>,
): Promise<unknown> {
  switch (kind) {
    case "workspace.provision":
      return await runProvision(options, payload);
    case "workspace.apply":
      return await runApply(options, commandId, payload);
    case "workspace.check":
      return await runCheck(options, payload);
    case "workspace.snapshot":
      return await runSnapshot(options, payload);
    case "workspace.delete":
      return await runDelete(options, payload);
    case "workspace.files":
      return await runFiles(options, payload);
    case "workspace.read":
      return await runRead(options, payload);
    case "workspace.recover":
      return await runRecover(options, payload);
    case "check":
    case "render.local":
    case "plan":
    case "pricing":
    case "vocabulary":
    case "status":
      if (options.engineExecutor === undefined) {
        throw new DispatchError("engine_unavailable", "engine executor not configured");
      }
      return await options.engineExecutor(kind, payload);
    case "build.submit":
      return await runBuildSubmit(options, commandId, payload);
    case "build.inspect":
      return await runBuildInspect(options, payload);
    case "build.cancel":
      return await runBuildCancel(options, commandId, payload);
    case "build.logs":
      return await runBuildLogsCommand(options, payload);
    case "build.activity":
      return await runBuildActivity(options, payload);
    case "results.read":
      return await runResultsRead(options, payload);
    case "results.history":
      return await runResultsHistory(options, payload);
    case "results.export":
      return await runResultsExport(options, payload);
    case "results.presentation":
      return await runResultsPresentation(options, payload);
    case "results.finish":
      return await runResultsFinish(options, payload);
    case "results.discard":
      return await runResultsDiscard(options, payload);
    case "results.reuse":
      return await runResultsReuse(options, payload);
    case "preview.session":
      return await runPreviewSession(options, payload);
    case "studio.session":
      return await runStudioSession(options, payload);
    case "knowledge.search":
      return await runKnowledgeSearch(payload);
    case "knowledge.read":
      return await runKnowledgeRead(payload);
    case "templates.list":
      return await runTemplatesList();
    case "templates.detail":
      return await runTemplatesDetail(payload);
    case "feedback.read":
      return await runFeedbackRead(options, payload);
    case "feedback.mutate":
      return await runFeedbackMutate(options, payload);
    default:
      if (isMediaTool(kind)) {
        return await runMediaTool(kind, payload, mediaContext(options));
      }
      if (isPackageTool(kind)) {
        // C107-17: the install command inherits the dispatch commandId so the
        // journaled workspace change replays idempotently with the command.
        const effectivePayload = kind === "packages.install" ? { ...payload, commandId } : payload;
        return await runPackageTool(packageToolContext(options), kind, effectivePayload);
      }
      if (isSpeechTool(kind)) {
        return await runSpeechTool(kind, payload, toolContext(options, speechProgramsRoot(options)));
      }
      if (isImageTool(kind)) {
        return await runImageTool(kind, payload, imageToolContext(options));
      }
      if (kind.startsWith("programs.")) {
        return await runProgramsAction(options, kind, payload);
      }
      if (kind.startsWith("providers.") || kind.startsWith("credentials.") || kind.startsWith("authflow.")) {
        return await runProviderDomainAction(options, kind, payload);
      }
      throw new DispatchError("unknown_kind", `unknown command kind "${kind}"`);
  }
}

/** C107-06: program action surface (prepare/up/down/status/logs) for the runtime API. */
async function runProgramsAction(
  options: DispatcherOptions,
  kind: string,
  payload: Record<string, unknown>,
): Promise<unknown> {
  if (options.distributionRoot === undefined) {
    throw new DispatchError("programs_unavailable", "programs actions need distributionRoot");
  }
  const action = kind.slice("programs.".length);
  const programRaw = payload.program;
  if (typeof programRaw !== "string" || !isProgramId(programRaw)) {
    throw new DispatchError("invalid_input", "programs.* needs program in {whisperx.local, image.opencv.local}");
  }
  const program = programRaw;
  const manager = new ProgramsManager({
    programsRoot: speechProgramsRoot(options),
    distributionRoot: options.distributionRoot,
    upTimeoutMs: 120_000,
  });
  switch (action) {
    case "prepare": {
      const status = await manager.prepare(program);
      return { program: status.id, phase: status.phase, detail: status.detail, identity: status.identity };
    }
    case "up": {
      const status = await manager.up(program);
      return { program: status.id, phase: status.phase, pid: status.pid, detail: status.detail, identity: status.identity };
    }
    case "down": {
      const status = await manager.down(program);
      return { program: status.id, phase: status.phase };
    }
    case "status": {
      const status = await manager.status(program);
      return { program: status.id, phase: status.phase, pid: status.pid, detail: status.detail, identity: status.identity };
    }
    case "logs": {
      const logs = await manager.logs(program);
      return { program, logs };
    }
    default:
      throw new DispatchError("invalid_input", `unknown programs action "${action}"`);
  }
}

/**
 * Media tool context derived from the C04 options (no server.ts shape change):
 * resources/programs roots are siblings of projectsRoot; sources resolve from
 * registered handles or workspace-relative paths only.
 */
function mediaContext(options: DispatcherOptions): MediaToolContext {
  if (options.distributionRoot === undefined) {
    throw new DispatchError("media_unavailable", "media tools need distributionRoot");
  }
  const resourcesRoot = resolve(options.projectsRoot, "../resources");
  return {
    registry: mediaHandleRegistry(options.store, resourcesRoot, options.projectsRoot),
    distributionRoot: options.distributionRoot,
    programsRoot: resolve(options.projectsRoot, "../programs"),
    resourcesRoot,
    resolveSource: async (payload) => {
      const handle = payload.handle;
      if (typeof handle === "string") {
        const record = await resolveResource(mediaHandleRegistry(options.store, resourcesRoot, options.projectsRoot), handle);
        return record.absolutePath;
      }
      const projectId = payload.projectId;
      const path = payload.path;
      if (typeof projectId === "string" && typeof path === "string") {
        return await workspaceFilePath(options, projectId, path);
      }
      throw new DispatchError("invalid_input", "media tools need {handle} or {projectId,path}");
    },
  };
}

/** The shared managed-programs root (sibling of projectsRoot, same as media tools). */
function speechProgramsRoot(options: DispatcherOptions): string {
  return resolve(options.projectsRoot, "../programs");
}

/** C107-17 package tool context: fixed toolchain comes from the distribution. */
function packageToolContext(options: DispatcherOptions): PackageToolContext {
  if (options.distributionRoot === undefined) {
    throw new DispatchError("engine_unavailable", "package tools need distributionRoot");
  }
  return { projectsRoot: options.projectsRoot, distributionRoot: options.distributionRoot };
}

/** Speech tool context shares the media resolve rules and the handle registry. */
function toolContext(options: DispatcherOptions, programsRoot: string): SpeechToolContext {
  const media = mediaContext(options);
  return {
    registry: media.registry,
    programsRoot,
    resolveSource: media.resolveSource,
  };
}

function imageToolContext(options: DispatcherOptions): ImageToolContext {
  const media = mediaContext(options);
  return {
    registry: media.registry,
    programsRoot: media.programsRoot,
    distributionRoot: options.distributionRoot!,
    resolveSource: media.resolveSource,
  };
}

function requireProjectId(payload: Record<string, unknown>): string {
  const projectId = payload.projectId;
  if (typeof projectId !== "string") throw new DispatchError("invalid_input", "projectId is required");
  return projectId;
}

async function runProvision(options: DispatcherOptions, payload: Record<string, unknown>): Promise<unknown> {
  const projectId = requireProjectId(payload);
  const useTemplate = payload.template === true;
  if (useTemplate) {
    if (options.templateDir === undefined || options.templateFiles === undefined) {
      throw new DispatchError("template_unavailable", "no template configured on this broker");
    }
    const receipt = await provisionFromTemplate(
      options.projectsRoot,
      projectId,
      options.templateDir,
      options.templateFiles,
    );
    return { projectRoot: receipt.projectRoot, state: receipt.state, head: receipt.head };
  }
  const receipt = await provisionWorkspace(options.projectsRoot, projectId);
  return { projectRoot: receipt.projectRoot, state: receipt.state, head: receipt.head };
}

async function runApply(
  options: DispatcherOptions,
  commandId: string,
  payload: Record<string, unknown>,
): Promise<unknown> {
  const projectId = requireProjectId(payload);
  const baseRevision = payload.baseRevision;
  const applyMode = payload.applyMode;
  const changes = payload.changes;
  if (typeof baseRevision !== "number" || !Number.isSafeInteger(baseRevision) || baseRevision < 0) {
    throw new DispatchError("invalid_input", "baseRevision must be a safe non-negative integer");
  }
  if (applyMode !== "save" && applyMode !== "validated") {
    throw new DispatchError("invalid_input", "applyMode must be save or validated");
  }
  if (!Array.isArray(changes)) throw new DispatchError("invalid_input", "changes must be an array");
  const projectRoot = projectRootFor(options.projectsRoot, projectId);

  // validated mode must prove checkPassed on the POST-apply bytes BEFORE the
  // head moves (K06.3.8): apply to a throwaway copy, check it, then commit.
  if (applyMode === "validated") {
    await checkStagedCopy(options, projectRoot, projectId, changes as FileChange[]);
  }

  const receipt: ApplyReceipt = await applyWorkspaceChanges({
    projectId,
    projectRoot,
    commandId,
    baseRevision,
    changes: changes as FileChange[],
  });
  options.store.indexFileTransaction(receipt.journalId, projectId, commandId, "committed");
  // Revisions are immutable; freeze the new head right after it published.
  const snapshot = await snapshotRevision(projectRoot, receipt.revision);
  appendJobEvent(options.store, `project-${projectId}`, "checkpoint", {
    revision: receipt.revision,
    manifestHash: receipt.manifestHash,
    commandId,
  });
  return {
    revision: receipt.revision,
    manifestHash: receipt.manifestHash,
    journalId: receipt.journalId,
    appliedPaths: receipt.appliedPaths,
    snapshotDir: snapshot.snapshotDir,
  };
}

/**
 * Standalone validated-check of projected changes against the current work
 * tree: apply to a throwaway copy, run the isolated-runner check, report.
 */
async function runCheck(options: DispatcherOptions, payload: Record<string, unknown>): Promise<unknown> {
  const projectId = requireProjectId(payload);
  const changes = payload.changes;
  if (!Array.isArray(changes)) throw new DispatchError("invalid_input", "changes must be an array");
  const projectRoot = projectRootFor(options.projectsRoot, projectId);
  const result = await checkProjected(options, projectRoot, projectId, changes as FileChange[]);
  return { ok: result.ok, diagnostics: result.diagnostics };
}

/** Shared projected-check core (validated apply + standalone workspace.check). */
async function checkProjected(
  options: DispatcherOptions,
  projectRoot: string,
  projectId: string,
  changes: readonly FileChange[],
): Promise<{ ok: boolean; diagnostics: readonly { severity?: string }[] }> {
  if (options.engineExecutor === undefined) {
    throw new DispatchError("engine_unavailable", "engine executor not configured for validated apply");
  }
  const staged = join(projectRoot, ".journal", `check-${Date.now().toString(36)}`);
  try {
    await mkdir(staged, { recursive: true });
    await cp(join(projectRoot, "work"), join(staged, "work"), { recursive: true, force: true });
    for (const change of changes) {
      const target = await resolveWithinWorkspace(join(staged, "work"), change.path);
      if (change.action === "put") {
        await mkdir(dirname(target), { recursive: true });
        await writeFile(target, change.content as string, "utf8");
      } else {
        await rm(target, { force: true });
      }
    }
    const check = await options.engineExecutor("check", {
      sourceDir: join(staged, "work"),
      entryFile: "main.svrun",
    }) as { ok?: boolean; diagnostics?: readonly { severity?: string }[] };
    return {
      ok: check.ok === true && !(check.diagnostics ?? []).some((diagnostic) => diagnostic.severity === "error"),
      diagnostics: check.diagnostics ?? [],
    };
  } finally {
    await rm(staged, { recursive: true, force: true }).catch(() => {});
  }
}

/**
 * Check a staged copy of the workspace with the projected changes applied.
 * Runs the same isolated-runner `check` the public API exposes; failures map
 * to hypit_compile_failed semantics (draft stays, head untouched).
 */
async function checkStagedCopy(
  options: DispatcherOptions,
  projectRoot: string,
  projectId: string,
  changes: readonly FileChange[],
): Promise<void> {
  const result = await checkProjected(options, projectRoot, projectId, changes);
  if (!result.ok) {
    throw new DispatchError("compile_failed", "validated changeset failed check; draft preserved");
  }
}

async function runSnapshot(options: DispatcherOptions, payload: Record<string, unknown>): Promise<unknown> {
  const projectId = requireProjectId(payload);
  const revision = payload.revision;
  if (typeof revision !== "number") throw new DispatchError("invalid_input", "revision is required");
  const projectRoot = projectRootFor(options.projectsRoot, projectId);
  const verification = await verifySnapshot(projectRoot, revision);
  return { ok: verification.ok, mismatches: verification.mismatches };
}

async function runDelete(options: DispatcherOptions, payload: Record<string, unknown>): Promise<unknown> {
  const projectId = requireProjectId(payload);
  await removeWorkspace(options.projectsRoot, projectId);
  return { deleted: true };
}

async function runFiles(options: DispatcherOptions, payload: Record<string, unknown>): Promise<unknown> {
  const projectId = requireProjectId(payload);
  const projectRoot = projectRootFor(options.projectsRoot, projectId);
  const head = await readHead(projectRoot);
  if (head === null) throw new DispatchError("not_provisioned", "workspace has no head");
  const manifest = await computeWorkspaceManifest(join(projectRoot, "work"));
  const files: readonly FileListingEntry[] = listingEntries(manifest);
  return { revision: head.revision, manifestHash: head.manifestHash, files };
}

async function runRead(options: DispatcherOptions, payload: Record<string, unknown>): Promise<unknown> {
  const projectId = requireProjectId(payload);
  const path = payload.path;
  if (typeof path !== "string") throw new DispatchError("invalid_input", "path is required");
  const projectRoot = projectRootFor(options.projectsRoot, projectId);
  return await readWorkspaceFile(projectRoot, projectId, path);
}

async function runRecover(options: DispatcherOptions, payload: Record<string, unknown>): Promise<unknown> {
  const projectId = requireProjectId(payload);
  const projectRoot = projectRootFor(options.projectsRoot, projectId);
  if (!existsSync(projectRoot)) return { recovered: [] };
  const recovered = await recoverPendingTransactions(projectRoot, projectId);
  const head: HeadState | null = await readHead(projectRoot);
  return { recovered, head };
}

/** Resource-handle registration used by asset flows (C05 consumes). */
export function registerHandle(
  store: CommandStore,
  handle: string,
  absolutePath: string,
  projectId: string | null,
  mediaType: string | null,
): void {
  store.registerResourceHandle(handle, absolutePath, projectId, mediaType);
}

/** Expose workspace-relative resolution for command payloads (C05+ reuse). */
export async function workspaceFilePath(
  options: DispatcherOptions,
  projectId: string,
  relative: string,
): Promise<string> {
  const projectRoot = projectRootFor(options.projectsRoot, projectId);
  return await resolveWithinWorkspace(join(projectRoot, "work"), relative);
}

/**
 * C107-07: provider/credential/auth-flow command surface for the runtime API.
 *
 * Credential stores and auth flows are process singletons rooted at the host
 * state directory (sibling of projectsRoot): OAuth flow state is deliberately
 * short-lived in memory, and secrets only move between the trusted stores and
 * the broker — never into command results.
 */
async function runProviderDomainAction(
  options: DispatcherOptions,
  kind: string,
  payload: Record<string, unknown>,
): Promise<unknown> {
  if (options.distributionRoot === undefined) {
    throw new DispatchError("providers_unavailable", "provider actions need distributionRoot");
  }
  const hostStateRoot = resolve(options.projectsRoot, "../state");
  mkdirSync(hostStateRoot, { recursive: true });

  if (kind === "providers.catalog") {
    const catalog = await describeProviderCatalog(options.distributionRoot);
    return { providers: catalog };
  }

  const credentials = await credentialStoresFor(options.distributionRoot, hostStateRoot);
  if (kind.startsWith("credentials.")) {
    const ref = credentialRefFrom(payload);
    const action = kind.slice("credentials.".length);
    if (action === "status") {
      return await credentials.status(ref);
    }
    if (action === "put") {
      const secret = payload.secret;
      if (typeof secret !== "string" || secret.length === 0) {
        throw new DispatchError("invalid_input", "credentials.put needs a non-empty secret");
      }
      await credentials.put(ref, secret);
      return await credentials.status(ref);
    }
    if (action === "delete") {
      const removed = await credentials.remove(ref);
      return { store: ref.store, key: ref.key, removed };
    }
    throw new DispatchError("invalid_input", `unknown credentials action "${action}"`);
  }

  if (kind.startsWith("authflow.")) {
    const owner = requiredString(payload, "owner");
    const action = kind.slice("authflow.".length);
    if (action === "start") {
      const service = await flowServiceFor(options.distributionRoot, hostStateRoot);
      const started = await service.start(
        owner,
        requiredString(payload, "endpoint"),
        requiredString(payload, "slot"),
        credentialRefFrom(payload, "targetStore", "targetKey"),
        {
          kind: requiredString(payload, "acquisitionKind"),
          authorizationEndpoint: requiredString(payload, "authorizationEndpoint"),
          redirectUri: requiredString(payload, "redirectUri"),
          tokenEndpoint: requiredString(payload, "tokenEndpoint"),
          clientId: requiredString(payload, "clientId"),
          scopes: stringArray(payload, "scopes"),
        },
      );
      // The verifier stays server-side; the caller only sees the authorize URL.
      return { flowId: started.flowId, authorizeUrl: started.authorizeUrl, handover: started.handover, expiresAt: started.expiresAt };
    }
    const flowId = requiredString(payload, "flowId");
    const service = await flowServiceFor(options.distributionRoot, hostStateRoot);
    if (action === "progress") {
      return await service.progress(owner, flowId);
    }
    if (action === "complete") {
      return await service.complete(owner, flowId, requiredString(payload, "codeAndState"));
    }
    if (action === "cancel") {
      return await service.cancel(owner, flowId);
    }
    throw new DispatchError("invalid_input", `unknown authflow action "${action}"`);
  }
  throw new DispatchError("invalid_input", `unknown provider action "${kind}"`);
}

const credentialStoresCache = new Map<string, Promise<CredentialStores>>();
const flowServiceCache = new Map<string, Promise<OAuthFlowService>>();

function credentialStoresFor(distributionRoot: string, hostStateRoot: string): Promise<CredentialStores> {
  const key = `${distributionRoot}\u0000${hostStateRoot}`;
  let cached = credentialStoresCache.get(key);
  if (cached === undefined) {
    cached = openCredentialStores(distributionRoot, hostStateRoot);
    credentialStoresCache.set(key, cached);
  }
  return cached;
}

async function flowServiceFor(distributionRoot: string, hostStateRoot: string): Promise<OAuthFlowService> {
  const key = `${distributionRoot}\u0000${hostStateRoot}`;
  let cached = flowServiceCache.get(key);
  if (cached === undefined) {
    const credentials = await credentialStoresFor(distributionRoot, hostStateRoot);
    cached = Promise.resolve(new OAuthFlowService({ distributionRoot, credentials }));
    flowServiceCache.set(key, cached);
  }
  return await cached;
}

function credentialRefFrom(payload: Record<string, unknown>, storeField = "store", keyField = "key") {
  const store = payload[storeField];
  const key = payload[keyField];
  if (typeof store !== "string" || typeof key !== "string" || store.length === 0 || key.length === 0) {
    throw new DispatchError("invalid_input", `needs ${storeField} and ${keyField} as non-empty strings`);
  }
  return { store, key };
}

function requiredString(payload: Record<string, unknown>, field: string): string {
  const value = payload[field];
  if (typeof value !== "string" || value.length === 0) {
    throw new DispatchError("invalid_input", `needs ${field} as a non-empty string`);
  }
  return value;
}

function stringArray(payload: Record<string, unknown>, field: string): string[] {
  const value = payload[field];
  if (!Array.isArray(value) || value.some((item) => typeof item !== "string")) {
    throw new DispatchError("invalid_input", `needs ${field} as a string array`);
  }
  return value as string[];
}

// -------------------------------------------------------------------------------------------------
// C107-09 (task-107) persistent Build command surface (card steps 09.1–09.9).

/** Longest a capacity watcher may poll before failing open (liveness guard). */
const CAPACITY_WATCH_LIMIT_MS = 8 * 60 * 60 * 1000;
const CAPACITY_WATCH_ERROR_BUDGET = 10;

/**
 * Broker render gate: an injected gate wins (tests); otherwise every broker
 * with an engine gets the D-05 deployment default (one concurrent local
 * render, HYPIT_MAX_LOCAL_RENDERS to tune) — the gate lives in the dispatcher
 * so no host wiring change is required for it to be enforced.
 */
const sharedDefaultGate: { instance: RenderCapacity | null } = { instance: null };

function capacityOf(options: DispatcherOptions): RenderCapacity {
  if (options.capacity !== undefined) return options.capacity;
  if (sharedDefaultGate.instance === null) {
    const raw = Number(process.env.HYPIT_MAX_LOCAL_RENDERS ?? "1");
    sharedDefaultGate.instance = new RenderCapacity(Number.isSafeInteger(raw) && raw >= 1 ? raw : 1);
  }
  return sharedDefaultGate.instance;
}

function requireAdapter(options: DispatcherOptions): { distributionRoot: string; attachmentSourceDir: string } {
  if (options.distributionRoot === undefined) {
    throw new DispatchError("engine_unavailable", "build commands need distributionRoot");
  }
  return { distributionRoot: options.distributionRoot, attachmentSourceDir: "" };
}

/**
 * The engine surface the build commands use — injectable so the dispatcher's
 * persistent-Behaviour tests can drive scripted observations (K13 layering:
 * unit tests fake the engine; one real render covers the live path in
 * tests/engine/compile-adapter.test.ts).
 */
export type BuildEnginePort = {
  allocateBuildId(): Promise<string>;
  observe(
    projectRoot: string,
    engineBuildId: string,
    previousOutcome?: BuildOutcome | null,
  ): Promise<BuildObservation>;
  cancel(projectRoot: string, engineBuildId: string, reason?: string): Promise<{ found: boolean }>;
  logs(
    projectRoot: string,
    engineBuildId: string,
    cursor: number,
    limit: number | undefined,
  ): Promise<LogPage>;
  activity(projectRoot: string): Promise<{ builds: readonly unknown[]; capacity: readonly unknown[] }>;
};

function buildEngineOf(options: DispatcherOptions): BuildEnginePort {
  if (options.buildEngine !== undefined) return options.buildEngine;
  const adapter = requireAdapter(options);
  return {
    allocateBuildId: async () => await allocateEngineBuildId(adapter),
    observe: async (root, id, previous) =>
      await observeEngineBuild(adapter, root, id, previous ?? null),
    cancel: async (root, id, reason) => {
      const view = await cancelBuild(adapter, root, id, reason);
      return { found: view.found };
    },
    logs: async (root, id, cursor, limit) => {
      const host = await openEngineHost(adapter, root);
      const control = await host.openControl({ readOnly: true });
      const results = await openResultsRepository(adapter, root);
      try {
        return await readBuildLogs({
          repository: results.repository,
          runtimeLogs: control.logs === undefined
            ? undefined
            : async (build, lines) => await control.logs?.(build, lines),
          engineBuildId: id,
          workspaceRoot: root,
          cursor,
          limit,
        });
      } finally {
        await Promise.allSettled([control.close(), results.close()]);
      }
    },
    activity: async (root) => await engineActivity(adapter, root),
  };
}

/** Sidecar job id for one build's persisted observation events (09.6). */
function buildEventJobId(engineBuildId: string): string {
  return `build-${engineBuildId}`;
}

/** Rebuildable observation events: only real deltas append (重复观察零事件). */
function projectObservation(store: CommandStore, observation: BuildObservation): void {
  if (!observation.found) return;
  const jobId = buildEventJobId(observation.engineBuildId);
  const history = readJobEvents(store, jobId, 0);
  let previous: ObservationSummary | null = null;
  for (let index = history.length - 1; index >= 0; index -= 1) {
    const data = history[index]?.data;
    if (data !== null && typeof data === "object" && "lifecycle" in (data as Record<string, unknown>)) {
      previous = data as ObservationSummary;
      break;
    }
  }
  const delta = observationDelta(previous, observationSummary(observation));
  if (delta === null) return;
  const data = { ...delta.data, engineBuildId: observation.engineBuildId };
  if (delta.kind === "terminal") {
    appendTerminalJobEvent(store, jobId, data);
  } else {
    appendJobEvent(store, jobId, "progress", data);
  }
}

/**
 * 09.2 K06.2 accept: the engineBuildId is allocated BEFORE the first native
 * submission and persisted on the command row. A replay after a crash first
 * queries THAT fixed id — found evidence returns without resubmitting; only
 * a provably unseen id is submitted again (same id, never a fresh one).
 */
async function runBuildSubmit(
  options: DispatcherOptions,
  commandId: string,
  payload: Record<string, unknown>,
): Promise<unknown> {
  const engine = buildEngineOf(options);
  const projectId = requireProjectId(payload);
  const runFile = requiredString(payload, "runFile");
  const title = typeof payload.title === "string" && payload.title.length > 0 ? payload.title : undefined;
  const previousOutcome = optionalOutcome(payload.previousOutcome);
  const projectRoot = projectRootFor(options.projectsRoot, projectId);
  const capacity = capacityOf(options);

  let engineBuildId = options.store.get(commandId)?.engineBuildId ?? null;
  if (engineBuildId === null) {
    engineBuildId = await engine.allocateBuildId();
    const allocated = engineBuildId;
    options.store.transition(commandId, () => ({ engineBuildId: allocated, state: "acknowledged" }));
  } else {
    // Crash recovery: query the fixed id before any resubmission (09.8).
    const prior = await engine.observe(projectRoot, engineBuildId, previousOutcome);
    if (prior.found) {
      projectObservation(options.store, prior);
      watchCapacityRelease(options, projectRoot, engineBuildId, previousOutcome);
      return prior;
    }
  }

  await capacity.acquire(engineBuildId);
  try {
    if (options.engineExecutor === undefined) {
      throw new DispatchError("engine_unavailable", "engine executor not configured for build submit");
    }
    // render.local keeps programs/worker up, compiles in the isolated slot and
    // submits under the caller's fixed id — the detached Worker renders on.
    await options.engineExecutor("render.local", {
      projectId,
      workspaceRoot: projectRoot,
      sourceDir: projectRoot,
      runFile,
      engineBuildId,
      ...(title === undefined ? {} : { title }),
    });
    const observation = await engine.observe(projectRoot, engineBuildId, previousOutcome);
    watchCapacityRelease(options, projectRoot, engineBuildId, previousOutcome);
    if (!observation.found) {
      throw new DispatchError("engine_error", `build ${engineBuildId} submitted but not observable`);
    }
    projectObservation(options.store, observation);
    return observation;
  } catch (error) {
    capacity.abandon(engineBuildId);
    throw error;
  }
}

async function runBuildInspect(options: DispatcherOptions, payload: Record<string, unknown>): Promise<unknown> {
  const engine = buildEngineOf(options);
  const projectId = requireProjectId(payload);
  const engineBuildId = requiredString(payload, "engineBuildId");
  const projectRoot = projectRootFor(options.projectsRoot, projectId);
  const observation = await engine.observe(projectRoot, engineBuildId, optionalOutcome(payload.previousOutcome));
  projectObservation(options.store, observation);
  return observation;
}

/** 09.7: idempotent cancel — stop not-yet-started work, best-effort remote cancel. */
async function runBuildCancel(
  options: DispatcherOptions,
  commandId: string,
  payload: Record<string, unknown>,
): Promise<unknown> {
  const engine = buildEngineOf(options);
  const projectId = requireProjectId(payload);
  const engineBuildId = requiredString(payload, "engineBuildId");
  const reason = typeof payload.reason === "string" && payload.reason.length > 0 ? payload.reason : undefined;
  const projectRoot = projectRootFor(options.projectsRoot, projectId);
  const previousOutcome = optionalOutcome(payload.previousOutcome);
  const before = await engine.observe(projectRoot, engineBuildId, previousOutcome);
  if (before.found && before.resultReady) {
    // Terminal: repeat cancel returns the existing facts, no new side effect.
    projectObservation(options.store, before);
    return before;
  }
  if (!before.found) {
    // No evidence this build exists anywhere — no remote cancel is issued.
    return before;
  }
  const view = await engine.cancel(projectRoot, engineBuildId, reason);
  if (!view.found) {
    return before;
  }
  const after = await engine.observe(projectRoot, engineBuildId, previousOutcome);
  projectObservation(options.store, after);
  return after;
}

async function runBuildLogsCommand(options: DispatcherOptions, payload: Record<string, unknown>): Promise<unknown> {
  const engine = buildEngineOf(options);
  const projectId = requireProjectId(payload);
  const engineBuildId = requiredString(payload, "engineBuildId");
  const cursor = typeof payload.cursor === "number" && Number.isSafeInteger(payload.cursor) && payload.cursor >= 0
    ? payload.cursor
    : 0;
  const limit = typeof payload.limit === "number" && Number.isSafeInteger(payload.limit) && payload.limit > 0
    ? payload.limit
    : undefined;
  const projectRoot = projectRootFor(options.projectsRoot, projectId);
  return await engine.logs(projectRoot, engineBuildId, cursor, limit);
}

/** 09.5: activity = active builds + native weighted claims + broker gate. */
async function runBuildActivity(options: DispatcherOptions, payload: Record<string, unknown>): Promise<unknown> {
  const engine = buildEngineOf(options);
  const projectId = typeof payload.projectId === "string" ? payload.projectId : undefined;
  if (projectId === undefined) {
    throw new DispatchError("invalid_input", "build.activity needs projectId");
  }
  const projectRoot = projectRootFor(options.projectsRoot, projectId);
  const activity = await engine.activity(projectRoot);
  return {
    builds: activity.builds,
    capacity: activity.capacity,
    localRender: capacityOf(options).describe(),
  };
}

/** C107-11: bind a preview session to (project, runFile, revision) — never a Build. */
async function runPreviewSession(options: DispatcherOptions, payload: Record<string, unknown>): Promise<unknown> {
  if (options.distributionRoot === undefined) {
    throw new DispatchError("engine_unavailable", "preview sessions need distributionRoot");
  }
  const projectId = requireProjectId(payload);
  const runFile = typeof payload.runFile === "string" && payload.runFile.length > 0 ? payload.runFile : undefined;
  const revision = typeof payload.revision === "number" && Number.isSafeInteger(payload.revision)
    ? payload.revision
    : undefined;
  const session = await openPreviewSession(
    { distributionRoot: options.distributionRoot, attachmentSourceDir: "", projectsRoot: options.projectsRoot },
    {
      projectId,
      ...(runFile === undefined ? {} : { runFile }),
      ...(revision === undefined ? {} : { revision }),
    },
  );
  return {
    sessionId: session.id,
    revision: session.revision,
    runFile: session.runFile,
    served: [...session.servedMediaTypes.entries()].map(([resource, mediaType]) => ({ resource, mediaType })),
  };
}

/**
 * C107-12: bind a Studio session for the broker (single-use ticket URL under
 * the deployment base path; same Run reuses the active session).
 */
async function runStudioSession(options: DispatcherOptions, payload: Record<string, unknown>): Promise<unknown> {
  const projectId = requireProjectId(payload);
  const secret = process.env.HYPIT_STUDIO_TICKET_SECRET ?? "";
  if (secret.length < 32) {
    throw new DispatchError("studio_unavailable", "studio ticket secret is not configured");
  }
  const basePath = typeof payload.basePath === "string" && payload.basePath.length > 0
    ? payload.basePath
    : "/studio";
  const ttlSeconds = typeof payload.ttlSeconds === "number" ? payload.ttlSeconds : undefined;
  const runFile = typeof payload.runFile === "string" && payload.runFile.length > 0 ? payload.runFile : undefined;
  const revision = typeof payload.revision === "number" && Number.isSafeInteger(payload.revision)
    ? payload.revision
    : undefined;
  const { session, ticket, reused } = openStudioSession(
    { basePath, secret, ...(ttlSeconds === undefined ? {} : { ttlSeconds }) },
    {
      projectId,
      ...(runFile === undefined ? {} : { runFile }),
      ...(revision === undefined ? {} : { revision }),
      readOnly: payload.readOnly === true,
    },
  );
  return {
    sessionId: session.id,
    reused,
    ticketUrl: ticket.url,
    expiresAt: ticket.expiresAt,
    revision: session.revision,
    readOnly: session.readOnly,
  };
}

/** C107-14: knowledge search/read over the broker-side index (same generator as JR). */
async function runKnowledgeSearch(payload: Record<string, unknown>): Promise<unknown> {
  const { searchKnowledge } = await import("../knowledge/index.ts");
  const topic = typeof payload.topic === "string" ? payload.topic : undefined;
  const query = typeof payload.query === "string" ? payload.query : undefined;
  const limit = typeof payload.limit === "number" && Number.isSafeInteger(payload.limit) ? payload.limit : undefined;
  return await searchKnowledge({
    ...(topic === undefined ? {} : { topic }),
    ...(query === undefined ? {} : { query }),
    ...(limit === undefined ? {} : { limit }),
  });
}

async function runKnowledgeRead(payload: Record<string, unknown>): Promise<unknown> {
  const { readKnowledge } = await import("../knowledge/index.ts");
  if (typeof payload.path !== "string") {
    throw new DispatchError("invalid_input", "knowledge.read needs path");
  }
  return await readKnowledge(payload.path);
}

/** C107-20: built-in template catalog — the file under platform-hypit/templates is the truth. */
async function runTemplatesList(): Promise<unknown> {
  const catalog = await catalogOf();
  return { templates: catalog.templates };
}

async function runTemplatesDetail(payload: Record<string, unknown>): Promise<unknown> {
  const templateId = typeof payload.templateId === "string" ? payload.templateId : "";
  if (templateId.length === 0) throw new DispatchError("invalid_input", "templates.detail needs templateId");
  const catalog = await catalogOf();
  const found = catalog.templates.find((entry) => entry.templateId === templateId);
  if (found === undefined) throw new DispatchError("not_found", `template not found: ${templateId}`);
  return found;
}

type CatalogEntry = {
  templateId: string;
  title: string;
  description: string;
  sourcePath: string;
  runPaths: string[];
  dependencies: string[];
  requiredCapabilities: string[];
  materialState: string;
  localOrRemote: string;
};

async function catalogOf(): Promise<{ templates: CatalogEntry[] }> {
  const { readFile } = await import("node:fs/promises");
  const path = resolve(process.env.HYPIT_TEMPLATES_ROOT ?? "../platform-hypit/templates", "catalog.json");
  return JSON.parse(await readFile(path, "utf8")) as { templates: CatalogEntry[] };
}

/** C107-18: review comments ride the upstream FEEDBACK store via the bridge. */
async function runFeedbackRead(options: DispatcherOptions, payload: Record<string, unknown>): Promise<unknown> {
  const projectId = requireProjectId(payload);
  const run = typeof payload.run === "string" && payload.run.length > 0 ? payload.run : undefined;
  const root = options.distributionRoot;
  if (root === undefined) throw new DispatchError("engine_unavailable", "feedback needs distributionRoot");
  return await readFeedback(root, options.projectsRoot, projectId, run);
}

async function runFeedbackMutate(options: DispatcherOptions, payload: Record<string, unknown>): Promise<unknown> {
  const projectId = requireProjectId(payload);
  const root = options.distributionRoot;
  if (root === undefined) throw new DispatchError("engine_unavailable", "feedback needs distributionRoot");
  const expectedHash = typeof payload.expectedHash === "string" ? payload.expectedHash : undefined;
  const mutations = payload.mutations;
  return await mutateFeedback(root, options.projectsRoot, projectId, mutations as readonly unknown[], expectedHash);
}

function optionalOutcome(value: unknown): BuildOutcome | null {
  return value === "complete" || value === "failed" || value === "cancelled" ? value : null;
}

/**
 * 09.3/09.5: the submit call returns when the Worker ACCEPTS the build; this
 * watcher releases the broker render slot once only remote waits remain (or
 * the build reaches a terminal/result-ready state). Observation errors fail
 * OPEN after a bounded budget — liveness over strictness for the gate.
 */
function watchCapacityRelease(
  options: DispatcherOptions,
  projectRoot: string,
  engineBuildId: string,
  previousOutcome: BuildOutcome | null,
): void {
  const capacity = capacityOf(options);
  if (!capacity.isHeld(engineBuildId)) return;
  const engine = buildEngineOf(options);
  const pollMs = options.capacityPollMs ?? 1_000;
  const deadline = Date.now() + CAPACITY_WATCH_LIMIT_MS;
  let errors = 0;
  // Watchers are detached: they must never hold the process open.
  const schedule = (fn: () => void, delay: number): void => {
    const timer = setTimeout(fn, delay);
    (timer as { unref?: () => void }).unref?.();
  };
  const tick = async (): Promise<void> => {
    if (Date.now() > deadline || errors >= CAPACITY_WATCH_ERROR_BUDGET) {
      capacity.release(engineBuildId);
      return;
    }
    let observation: BuildObservation;
    try {
      observation = await engine.observe(projectRoot, engineBuildId, previousOutcome);
    } catch {
      errors += 1;
      schedule(() => void tick(), pollMs);
      return;
    }
    if (!holdsLocalRender(observation)) {
      capacity.release(engineBuildId);
      return;
    }
    schedule(() => void tick(), pollMs);
  };
  schedule(() => void tick(), pollMs);
}
