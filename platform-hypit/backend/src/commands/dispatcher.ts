// dispatcher.ts — C107-04 (task-107) command dispatch on the durable store.
//
// Replaces C107-02's in-memory command map: every internal command goes
// through bridge.sqlite idempotency (same commandId + payload hash replays
// the recorded result; a different hash is a conflict, K06.2 accept). Kinds
// registered here: workspace lifecycle (provision/apply/delete/listing/read)
// plus the C02 legacy engine kinds (check / render.local / status) rerouted
// through the same durable path.
import { cp, mkdir, rename, rm, writeFile } from "node:fs/promises";
import { existsSync, mkdirSync } from "node:fs";
import { randomUUID } from "node:crypto";
import { dirname, join, resolve } from "node:path";

import { CommandStore, CommandConflictError, type StoredCommand } from "./store.ts";
import { resolveResource, registerResource } from "../resources/handles.ts";
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
import { isSnapshotTool, runSnapshotTool, type SnapshotToolContext } from "../tools/snapshot.ts";
import { isCaptureTool, runCaptureTool, type CaptureToolContext } from "../tools/capture.ts";
import { exportProjectPackage, type ExportReceipt } from "../project-package/export.ts";
import { importProjectPackage, type ImportReceipt } from "../project-package/import.ts";
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
import { closeStudioSession, registerStudioSession, revokeStudioSessionsForOwner } from "../studio/sessions.ts";
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
  /** C03（§6.8 配置唯一表）：模板 catalog 根（server 从 config 注入；缺省 env/repo 回退）。 */
  readonly templatesRoot?: string;
  /** C107F2-05（D-05）：clone/brief 中性骨架目录（fixtures/blank，三件文件）。 */
  readonly blankDir?: string;
  readonly distributionRoot?: string;
  /** Engine-side executor for check/render (C02 surface, injected by server). */
  readonly engineExecutor?: (kind: string, payload: unknown) => Promise<unknown>;
  /** C107F-06（D-08）：包编译 runner 槽 supervisor（server 注入；缺失时 packages.build 显式失败）。 */
  readonly runnerSupervisor?: import("../runner/supervisor.ts").RunnerSupervisor;
  /** C107-09 broker render admission gate (D-05 default: one local render). */
  readonly capacity?: RenderCapacity;
  /** Observation poll interval for capacity release watchers (tests shrink). */
  readonly capacityPollMs?: number;
  /** C107-09 engine port override (scripted observations in unit tests). */
  readonly buildEngine?: BuildEnginePort;
  /** C107F-03 (D-07): capture Chrome cache directory; unset = capture surface not deployed. */
  readonly captureBrowserCache?: string;
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
  // C107F2-38：重放（含从 stale_dispatch 回收的 unknown 行重派）清掉遗留错误
  // 留痕，避免 dispatching 行携带过期错误字段误导 receipt 读取。
  options.store.transition(commandId, () => ({ state: "dispatching", errorCode: null, errorMessage: null }));
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
    case "preview.session.close":
      return { closed: true }; // 预览为瞬态展示：broker 侧无持久绑定，幂等回执。
    case "studio.session":
      return await runStudioSession(options, payload);
    case "studio.session.close":
      return runStudioSessionClose(payload);
    case "studio.session.revoke":
      return runStudioSessionRevoke(payload);
    case "knowledge.search":
      return await runKnowledgeSearch(payload);
    case "knowledge.read":
      return await runKnowledgeRead(payload);
    case "templates.list":
      return await runTemplatesList(options);
    case "templates.detail":
      return await runTemplatesDetail(options, payload);
    case "feedback.read":
      return await runFeedbackRead(options, payload);
    case "feedback.mutate":
      return await runFeedbackMutate(options, payload);
    case "project-package.export":
      return await runProjectPackageExport(options, commandId, payload);
    case "project-package.import":
      return await runProjectPackageImport(options, commandId, payload);
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
      if (isSnapshotTool(kind)) {
        return await runSnapshotTool(snapshotToolContext(options), payload);
      }
      if (isCaptureTool(kind)) {
        return await runCaptureTool(captureToolContext(options), kind, payload);
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
  return {
    projectsRoot: options.projectsRoot,
    distributionRoot: options.distributionRoot,
    ...(options.runnerSupervisor === undefined ? {} : { supervisor: options.runnerSupervisor }),
  };
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

/** C107F-03: snapshot tool context — frames land in the shared resources area. */
function snapshotToolContext(options: DispatcherOptions): SnapshotToolContext {
  if (options.distributionRoot === undefined) {
    throw new DispatchError("engine_unavailable", "snapshot tool needs distributionRoot");
  }
  const resourcesRoot = resolve(options.projectsRoot, "../resources");
  const registry = mediaHandleRegistry(options.store, resourcesRoot, options.projectsRoot);
  return {
    distributionRoot: options.distributionRoot,
    framesRoot: join(resourcesRoot, "media", "snapshot-frames"),
    registerResource: async (input) =>
      (await registerResource(registry, input)).handle,
  };
}

/** C107F-03: capture tool context (D-07 — unset cache = surface not deployed). */
function captureToolContext(options: DispatcherOptions): CaptureToolContext {
  const resourcesRoot = resolve(options.projectsRoot, "../resources");
  const registry = mediaHandleRegistry(options.store, resourcesRoot, options.projectsRoot);
  return {
    captureBrowserCache: options.captureBrowserCache ?? "",
    outputsRoot: join(resourcesRoot, "media", "capture-outputs"),
    registerResource: async (input) =>
      (await registerResource(registry, input)).handle,
    workspaceRootFor: (projectId: string) => projectRootFor(options.projectsRoot, projectId),
  };
}

/**
 * C107F-01: project-package transfer kinds — the broker-side implementations
 * (C107-20) become dispatcher-routed so the Java orchestration layer's
 * "project-package.export"/"project-package.import" commands reach them.
 * Idempotency stays with the CommandStore (same commandId + payload hash).
 */async function runProjectPackageExport(
  options: DispatcherOptions,
  commandId: string,
  payload: Record<string, unknown>,
): Promise<ExportReceipt> {
  if (options.distributionRoot === undefined) {
    throw new DispatchError("engine_unavailable", "project package export needs distributionRoot");
  }
  const projectId = requireProjectId(payload);
  const title = typeof payload.title === "string" ? payload.title : undefined;
  const selectedRun = typeof payload.selectedRun === "string" ? payload.selectedRun : undefined;
  // C107F2-30: exportId = Java command uuid; present ⇒ also pack the zip.
  const exportId = typeof payload.exportId === "string" ? payload.exportId : undefined;
  return await exportProjectPackage(
    {
      projectsRoot: options.projectsRoot,
      distributionRoot: options.distributionRoot,
      sourceCommit: await templateSourceCommit(options.templatesRoot),
    },
    projectId,
    { title, selectedRun, exportId },
  );
}

async function runProjectPackageImport(
  options: DispatcherOptions,
  commandId: string,
  payload: Record<string, unknown>,
): Promise<ImportReceipt> {
  if (options.templateDir === undefined || options.templateFiles === undefined) {
    throw new DispatchError("template_unavailable", "project package import needs the provisioning template");
  }
  // C107F2-37（缺陷 G）：浏览器上传链（C107F2-30 transfer staging）只带
  // transferId、不带 artifactRoot——二者其一在场即可。原守卫无条件要求
  // artifactRoot，把 transfer 分支判成死代码（Java dispatchTransferImport
  // 真实浏览器导入全链 503 invalid_input）。
  const artifactRootRaw = payload.artifactRoot;
  const artifactRoot = typeof artifactRootRaw === "string" && artifactRootRaw.length > 0 ? artifactRootRaw : "";
  const transferId = typeof payload.transferId === "string" && payload.transferId.length > 0
    ? payload.transferId
    : undefined;
  if (transferId === undefined && artifactRoot === "") {
    throw new DispatchError("invalid_input", "project-package.import needs artifactRoot or transferId");
  }
  // New project ids are broker-generated; the staging root is the broker data
  // parent so export-relative artifactRoots resolve inside it (same base as
  // export's own artifacts placement).
  // C107F2-28: the parse/check gate lives in importProjectPackage via
  // ctx.engineExecutor. The broker-level dispatch lands bytes only — the
  // orchestration layer (Java import service) chains the existing
  // workspace.check against the imported project and owns the ready gate
  // (C107F2-29/C107F2-30). Executor injection stays at the API boundary so
  // the gate is driver-verified (fix2-c28 TC-F2-28-02).
  //
  // C107F2-29: the Java reservation owns the project identity — the caller's
  // payload.newProjectId (stable across replays) is honored; the broker never
  // invents a second id for the same logical import.
  const requestedId = typeof payload.newProjectId === "string" ? payload.newProjectId : "";
  if (requestedId !== "" && !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/iu.test(requestedId)) {
    throw new DispatchError("invalid_input", "newProjectId must be a uuid");
  }
  // C107F2-30: browser uploads arrive as a staged transfer (Java PUT the zip);
  // legacy directory bundles keep the artifactRoot entry.
  return await importProjectPackage(
    {
      projectsRoot: options.projectsRoot,
      stagingRoot: resolve(options.projectsRoot, ".."),
      provisionTemplateDir: options.templateDir,
      provisionTemplateFiles: options.templateFiles,
    },
    transferId === undefined ? artifactRoot : "",
    {
      newProjectId: requestedId === "" ? randomUUID() : requestedId,
      requestId: commandId,
      ...(transferId === undefined ? {} : { transferId }),
    },
  );
}

function requireProjectId(payload: Record<string, unknown>): string {
  const projectId = payload.projectId;
  if (typeof projectId !== "string") throw new DispatchError("invalid_input", "projectId is required");
  return projectId;
}

/**
 * C107F2-05（F29 修复）：
 * - template 模式：templateId 必须命中 catalog 且 materialState=ready；克隆源 =
 *   catalog.sourcePath（受控 templates 根内，禁止公开 sourceDir 透传）；未带
 *   templateId 的旧调用保留 minimal-local 兼容路径。
 * - clone/brief：缺省改为 D-05 中性 blank 骨架 revision1（可编辑、非完成作品），
 *   不再返回 head=null 的空工程；package.json 取自 minimal-local（引擎包清单）。
 * - 幂等：同 projectId 重复 provision 返回 existing（provisionFromTemplate 内建）。
 */
async function runProvision(options: DispatcherOptions, payload: Record<string, unknown>): Promise<unknown> {
  const projectId = requireProjectId(payload);
  const useTemplate = payload.template === true;
  const templateId = typeof payload.templateId === "string" ? (payload.templateId as string).trim() : "";

  if (useTemplate && templateId.length > 0) {
    const catalog = await catalogOf(options.templatesRoot);
    const entry = catalog.templates.find((item) => item.templateId === templateId);
    if (entry === undefined) {
      throw new DispatchError("not_found", `template not found: ${templateId}`);
    }
    if (entry.materialState !== "ready") {
      throw new DispatchError("engine_unavailable", `template material not ready: ${templateId}`);
    }
    const templatesRoot = options.templatesRoot
      ?? process.env.HYPIT_TEMPLATES_ROOT
      ?? resolve(import.meta.dirname, "../../platform-hypit/templates");
    const cloneDir = resolve(templatesRoot, "../../", entry.sourcePath);
    const files = await templateFilesOf(cloneDir, entry);
    const receipt = await provisionFromTemplate(options.projectsRoot, projectId, cloneDir, files);
    return { projectRoot: receipt.projectRoot, state: receipt.state, head: receipt.head, templateId };
  }
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
  // clone/brief：中性 blank 骨架（D-05）。
  if (options.blankDir === undefined || options.templateDir === undefined) {
    throw new DispatchError("template_unavailable", "no blank skeleton configured on this broker");
  }
  const { readdir } = await import("node:fs/promises");
  for (const name of ["main.svml", "main.svrun", "style.svs"]) {
    const names = await readdir(options.blankDir);
    if (!names.includes(name)) throw new DispatchError("template_unavailable", `blank skeleton missing ${name}`);
  }
  const receipt = await provisionFromTemplate(
    options.projectsRoot,
    projectId,
    options.blankDir,
    ["main.svml", "main.svrun", "style.svs"],
    new Map([["package.json", resolve(options.templateDir, "package.json")]]),
  );
  return { projectRoot: receipt.projectRoot, state: receipt.state, head: receipt.head, skeleton: "blank" };
}

/** 模板目录受控文件集：全部普通文件，剔除 catalog 封面等展示资产。 */
async function templateFilesOf(cloneDir: string, entry: CatalogEntry): Promise<string[]> {
  const { readdir, stat } = await import("node:fs/promises");
  const names = await readdir(cloneDir);
  const files: string[] = [];
  for (const name of names) {
    if (name === (entry as unknown as { cover?: string }).cover) continue;
    if ((await stat(resolve(cloneDir, name))).isFile()) files.push(name);
  }
  for (const run of entry.runPaths) {
    if (!files.includes(run)) throw new DispatchError("engine_unavailable", `template ${entry.templateId} missing run ${run}`);
  }
  return files;
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
        // work 文件带在途输入保护（0444），staged 拷贝保留该权限位，直接
        // writeFile 会 EACCES。经临时 inode + rename 原子替换——只需目录写
        // 权限，staged 树本就是抛弃型检查副本，不触碰受保护原件。
        const scratch = `${target}.staged-${Date.now().toString(36)}-${randomUUID().slice(0, 8)}`;
        await writeFile(scratch, change.content as string, "utf8");
        await rename(scratch, target);
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
 * C107F2-32 (§6.15): command kinds allowed THROUGH the maintenance write
 * fence — cancellation and receipt convergence must keep working so in-flight
 * work can reach a safe terminal state during the drain; everything else that
 * mutates state is refused with 503 until the holder exits.
 */
export const MAINTENANCE_EXEMPT_KINDS: ReadonlySet<string> = new Set([
  "build.cancel",
  "preview.session.close",
  "studio.session.close",
  "studio.session.revoke",
  "status",
]);

/**
 * C107F2-32: maintenance drain evidence. `activeCommands` counts the REAL
 * in-flight states (queued/dispatching/acknowledged — the old probe counted
 * 'running', a state this store never uses, so enter always claimed drained
 * instantly); `activeBuilds` also counts admitted local renders (capacity
 * gate) and dispatching commands that carry an allocated engineBuildId.
 */
export function maintenanceCounts(options: DispatcherOptions): {
  activeCommands: number;
  activeBuilds: number;
  inflight: readonly { commandId: string; kind: string; state: string }[];
} {
  return {
    activeCommands: options.store.countActiveCommands(),
    activeBuilds: Math.max(options.store.countActiveBuilds(), capacityOf(options).heldCount()),
    inflight: options.store.activeCommandSummaries(20),
  };
}

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
    // C107F2-06（RULE-04/F26）：build.submit 携带冻结绑定（revision/manifestHash/
    // profileHash，见 engine-port.ts FrozenBuildCommand）时，编译闭包取该
    // revision 快照（server 侧核验 manifest）；缺省旧载荷（无冻结字段）保持
    // 兼容——但正式 Java 链路一律提供冻结字段。
    const frozenRevision = typeof payload.revision === "number" ? payload.revision : undefined;
    const frozenManifest = typeof payload.manifestHash === "string" ? payload.manifestHash : undefined;
    // profileHash 必须透传：server 侧 plan_stale 校验（F26/RULE-04）读的是
    // render.local 载荷里的 profileHash——缺了漂移检测就永远不触发。
    const frozenProfile = typeof payload.profileHash === "string" ? payload.profileHash : undefined;
    await options.engineExecutor("render.local", {
      projectId,
      workspaceRoot: projectRoot,
      ...(frozenRevision === undefined ? { sourceDir: projectRoot } : { revision: frozenRevision }),
      ...(frozenManifest === undefined ? {} : { manifestHash: frozenManifest }),
      ...(frozenProfile === undefined ? {} : { profileHash: frozenProfile }),
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
  const ownerAccountId = typeof payload.ownerAccountId === "string" ? payload.ownerAccountId : "";
  if (ownerAccountId.length === 0) {
    throw new DispatchError("invalid_input", "preview.session needs ownerAccountId from the Java session row");
  }
  const runFile = typeof payload.runFile === "string" && payload.runFile.length > 0 ? payload.runFile : undefined;
  const revision = typeof payload.revision === "number" && Number.isSafeInteger(payload.revision)
    ? payload.revision
    : 0;
  const session = await openPreviewSession(
    { distributionRoot: options.distributionRoot, attachmentSourceDir: "", projectsRoot: options.projectsRoot },
    {
      projectId,
      ownerAccountId,
      revision,
      ...(runFile === undefined ? {} : { runFile }),
    },
  );
  return {
    sessionId: session.id,
    previewUrl: `/preview/${session.id}/`,
    revision: session.revision,
    runFile: session.runFile,
    manifestHash: session.manifestHash,
    expiresAt: new Date(session.expiresAt).toISOString(),
    served: [...session.servedMediaTypes.entries()].map(([resource, mediaType]) => ({ resource, mediaType })),
  };
}

/**
 * C107F2-19：为 Java 已登记 PG 的会话（payload.sessionId 由 Java 生成）启动
 * 真实 Studio 子进程（launcher ready 探测），并把进程绑定注册进会话表。
 * 启动失败如实 failed——不留 active 假会话/僵尸子进程（E03）。票据签发在
 * Java（nonceHash CAS 核销），本命令只回进程事实。
 */
async function runStudioSession(options: DispatcherOptions, payload: Record<string, unknown>): Promise<unknown> {
  if (options.distributionRoot === undefined) {
    throw new DispatchError("engine_unavailable", "studio sessions need distributionRoot");
  }
  const projectId = requireProjectId(payload);
  const sessionId = typeof payload.sessionId === "string" ? payload.sessionId : "";
  const ownerAccountId = typeof payload.ownerAccountId === "string" ? payload.ownerAccountId : "";
  if (sessionId.length === 0 || ownerAccountId.length === 0) {
    throw new DispatchError("invalid_input", "studio.session needs sessionId and ownerAccountId from the Java session row");
  }
  const runFile = typeof payload.runFile === "string" && payload.runFile.length > 0 ? payload.runFile : "main.svrun";
  const revision = typeof payload.revision === "number" && Number.isSafeInteger(payload.revision)
    ? payload.revision
    : 0;
  const ttlSeconds = typeof payload.ttlSeconds === "number" && Number.isSafeInteger(payload.ttlSeconds)
    ? payload.ttlSeconds
    : undefined;
  const session = await registerStudioSession(
    {
      distributionRoot: options.distributionRoot,
      projectsRoot: options.projectsRoot,
      ...(ttlSeconds === undefined ? {} : { ttlSeconds }),
    },
    {
      sessionId,
      projectId,
      ownerAccountId,
      runFile,
      revision,
      readOnly: payload.readOnly === true,
    },
  );
  return {
    sessionId: session.id,
    port: session.child.port,
    pid: session.child.pid,
    expiresAt: new Date(session.expiresAt).toISOString(),
    revision: session.revision,
    readOnly: session.readOnly,
  };
}

/** C107F2-20：关闭/撤销受管 Studio 会话（终止子进程与 WS）；幂等——未知会话也 closed。 */
function runStudioSessionClose(payload: Record<string, unknown>): unknown {
  const sessionId = typeof payload.sessionId === "string" ? payload.sessionId : "";
  if (sessionId.length === 0) {
    throw new DispatchError("invalid_input", "studio.session.close needs sessionId");
  }
  return closeStudioSession(sessionId);
}

/** C107F2-20：撤销属主在工程内的全部活跃会话（工程删除/注销联动）。 */
function runStudioSessionRevoke(payload: Record<string, unknown>): unknown {
  const ownerAccountId = typeof payload.ownerAccountId === "string" ? payload.ownerAccountId : "";
  const projectId = typeof payload.projectId === "string" ? payload.projectId : "";
  if (ownerAccountId.length === 0 || projectId.length === 0) {
    throw new DispatchError("invalid_input", "studio.session.revoke needs ownerAccountId and projectId");
  }
  return { revoked: revokeStudioSessionsForOwner(ownerAccountId, projectId) };
}

/** C107-14: knowledge search/read over the broker-side index (same generator as JR). */
async function runKnowledgeSearch(payload: Record<string, unknown>): Promise<unknown> {  const { searchKnowledge } = await import("../knowledge/index.ts");
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
async function runTemplatesList(options: DispatcherOptions): Promise<unknown> {
  const catalog = await catalogOf(options.templatesRoot);
  return { templates: catalog.templates };
}

async function runTemplatesDetail(options: DispatcherOptions, payload: Record<string, unknown>): Promise<unknown> {
  const templateId = typeof payload.templateId === "string" ? payload.templateId : "";
  if (templateId.length === 0) throw new DispatchError("invalid_input", "templates.detail needs templateId");
  const catalog = await catalogOf(options.templatesRoot);
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

async function catalogOf(templatesRoot?: string): Promise<{ templates: CatalogEntry[]; sourceCommit: string }> {
  const { readFile } = await import("node:fs/promises");
  // C03：优先 server 注入的 config.templatesRoot；独立调用走文档化 env，最后
  // 相对 broker 源码根（backendRoot/../platform-hypit/templates），不再依赖 CWD。
  const root = templatesRoot
    ?? process.env.HYPIT_TEMPLATES_ROOT
    ?? resolve(import.meta.dirname, "../../platform-hypit/templates");
  const path = resolve(root, "catalog.json");
  const catalog = JSON.parse(await readFile(path, "utf8")) as { templates: CatalogEntry[]; sourceCommit: string };
  if (typeof catalog.sourceCommit !== "string" || catalog.sourceCommit.length !== 40) {
    throw new DispatchError("engine_unavailable", "template catalog carries no pinned sourceCommit");
  }
  return catalog;
}

/** C107F-01: bundle provenance — the pinned upstream commit from the same catalog as templates.list. */
async function templateSourceCommit(templatesRoot?: string): Promise<string> {
  return (await catalogOf(templatesRoot)).sourceCommit;
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
  const requestId = typeof payload.requestId === "string" ? payload.requestId : undefined;
  const mutations = payload.mutations;
  return await mutateFeedback(root, options.projectsRoot, projectId, mutations as readonly unknown[],
    expectedHash, requestId);
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
