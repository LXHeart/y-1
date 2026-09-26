// runtime-adapter.ts — C107-02 (task-107) trusted-side Runtime Host adapter.
//
// Wraps the native runtime-local Host through the video distribution exactly the
// way the CLI does, with one broker-specific rule from K06.2: every build is
// submitted with the caller's pre-allocated engineBuildId so a crash between
// "allocated" and "acknowledged" can be resolved by querying that fixed id —
// never by generating a fresh one.
import { createReadStream, realpathSync } from "node:fs";
import { once } from "node:events";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { join, relative, resolve } from "node:path";

import { loadHypit } from "./hypit-bootstrap.ts";
import type {
  CompiledRunRequest,
  EngineBuildStatus,
  EngineSubmission,
  EngineSubmitRequest,
} from "./engine-port.ts";
import { observeBuild, type BuildObservation, type BuildOutcome } from "../runtime/observer.ts";
import type { ArtifactAttachment } from "@hypit/workspace";
import type { BuildResultRepositoryLocation } from "@hypit/build-result-kit";

/** The repository INTERFACE lives in @hypit/build-result; the kit only carries addresses. */
type BuildRepository = import("@hypit/build-result").BuildResultRepository;

export type RuntimeAdapterOptions = {
  readonly distributionRoot: string;
  /** Directory holding materialized attachment files referenced by compiled runs. */
  readonly attachmentSourceDir: string;
};

const profileFileName = "hypit.runtime.json";

type RuntimeHostLike = Awaited<
  ReturnType<import("@hypit/cli").CliDistribution["openRuntimeHost"]>
>;

/** Write and select the distribution's initial Runtime Profile (idempotent). */
export async function ensureRuntimeProfile(
  distributionRoot: string,
  workspaceRoot: string,
): Promise<string> {
  const engine = await loadHypit(distributionRoot);
  const distribution = engine.videoCliDistribution;
  if (distribution.initialRuntimeProfile === undefined) {
    throw new Error("this distribution provides no initial Runtime Profile");
  }
  const profile = resolve(workspaceRoot, profileFileName);
  try {
    await writeFile(
      profile,
      `${JSON.stringify(distribution.initialRuntimeProfile, undefined, 2)}\n`,
      { encoding: "utf8", flag: "wx" },
    );
  } catch (error) {
    if (!(error instanceof Error) || !("code" in error) || (error as { code?: string }).code !== "EEXIST") {
      throw error;
    }
  }
  await mkdir(resolve(workspaceRoot, ".hypit"), { recursive: true });
  await writeFile(resolve(workspaceRoot, ".hypit/runtime"), `${profile}\n`, "utf8");
  return profile;
}

/** Open the trusted Runtime Host for a workspace (profile auto-created). */
export async function openEngineHost(
  options: RuntimeAdapterOptions,
  workspaceRoot: string,
): Promise<RuntimeHostLike> {
  return await openHost(options, workspaceRoot);
}

async function openHost(options: RuntimeAdapterOptions, workspaceRoot: string): Promise<RuntimeHostLike> {
  const engine = await loadHypit(options.distributionRoot);
  const distribution = engine.videoCliDistribution;
  const profile = resolve(workspaceRoot, profileFileName);
  let exists = true;
  try {
    await readFile(profile, "utf8");
  } catch {
    exists = false;
  }
  if (!exists) await ensureRuntimeProfile(options.distributionRoot, workspaceRoot);
  return await distribution.openRuntimeHost(profile, {
    packageRoot: workspaceRoot,
    ...(distribution.packageRoot === undefined
      ? {}
      : { distributionPackageRoot: distribution.packageRoot }),
  });
}

function rehydrateAttachments(
  options: RuntimeAdapterOptions,
  compiled: CompiledRunRequest,
): readonly ArtifactAttachment[] {
  return compiled.attachments.map((item) => ({
    artifact: item.blob,
    open: async function* (): AsyncGenerator<Uint8Array> {
      const stream = createReadStream(resolve(options.attachmentSourceDir, item.fileName));
      try {
        for await (const chunk of stream) yield chunk as Uint8Array;
      } finally {
        if (!stream.closed) {
          stream.destroy();
          await once(stream, "close").catch(() => {});
        }
      }
    },
  }));
}

/**
 * Translate the catalog's provenance paths from the compile workspace (a
 * runner slot copy) back to the canonical project workspace. The runtime
 * requires sources to sit inside the project; bytes are identical because the
 * slot holds a frozen copy of the same closure.
 */
function canonicalizeCatalog(
  compiled: CompiledRunRequest,
  projectRoot: string,
): CompiledRunRequest["catalog"] {
  // Match realpath'd forms (the compiler records sources through realpath; on
  // macOS /var becomes /private/var) but EMIT the project-root form the caller
  // passed: the runtime's containment check compares path strings as given.
  const compileRoot = realpathish(compiled.compileWorkspaceRoot);
  const translate = (value: string): string => {
    const resolved = realpathish(value);
    if (resolved === compileRoot || resolved.startsWith(compileRoot + "/")) {
      return join(projectRoot, relative(compileRoot, resolved));
    }
    return value;
  };
  const catalog = compiled.catalog as {
    source: { path: string };
    run?: { path: string };
  };
  return {
    ...compiled.catalog,
    source: { path: translate(catalog.source.path) },
    ...(catalog.run === undefined ? {} : { run: { path: translate(catalog.run.path) } }),
  } as CompiledRunRequest["catalog"];
}

function realpathish(value: string): string {
  try {
    return realpathSync(value);
  } catch {
    return resolve(value);
  }
}

/** The project-owned Results location for a workspace, opened the native way. */
export async function openProjectResultsLocation(
  options: RuntimeAdapterOptions,
  workspaceRoot: string,
): Promise<BuildResultRepositoryLocation> {
  const engine = await loadHypit(options.distributionRoot);
  const distribution = engine.videoCliDistribution;
  if (distribution.openProjectResults === undefined) {
    throw new Error("distribution does not provide a project Results repository");
  }
  const opened = await distribution.openProjectResults(workspaceRoot, {
    packageRoot: workspaceRoot,
    ...(distribution.packageRoot === undefined
      ? {}
      : { distributionPackageRoot: distribution.packageRoot }),
  });
  await opened.close();
  return opened.location;
}

/** Submit one compiled run under the caller's fixed engineBuildId (K06.2). */
export async function submitBuild(
  options: RuntimeAdapterOptions,
  request: EngineSubmitRequest,
  compiled: CompiledRunRequest,
): Promise<EngineSubmission> {
  const host = await openHost(options, request.workspaceRoot);
  const runtime = await host.createRuntime();
  try {
    const attachments = rehydrateAttachments(options, compiled);
    const submission = await runtime.build({
      id: request.engineBuildId,
      definition: compiled.definition,
      componentPackages: compiled.componentPackages,
      catalog: canonicalizeCatalog(compiled, request.workspaceRoot),
      ...(attachments.length === 0 ? {} : { attachments }),
      result: {
        repository: request.repositoryLocation,
        ...(request.title === undefined ? {} : { title: request.title }),
      },
    });
    const finished = "completion" in submission;
    return {
      engineBuildId: submission.id,
      state: finished ? "finished" : "active",
      activity: finished ? "saving-result" : submission.view.activity,
      ...(finished ? { outcome: submission.completion.outcome } : {}),
    };
  } finally {
    await runtime.close();
  }
}

/** Read one build's runtime view (active evidence only; finished Results live in the repository). */
export async function inspectBuild(
  options: RuntimeAdapterOptions,
  workspaceRoot: string,
  engineBuildId: string,
): Promise<EngineBuildStatus> {
  const host = await openHost(options, workspaceRoot);
  const control = await host.openControl({ readOnly: true });
  try {
    const view = await control.inspect(engineBuildId);
    if (view === undefined) return { engineBuildId, found: false };
    return {
      engineBuildId,
      found: true,
      activity: view.activity,
      ...(view.outcome === undefined ? {} : { outcome: view.outcome }),
      ...(view.targets.length === 0 ? {} : { targets: view.targets }),
      cancellationRequested: view.cancellationRequested,
    };
  } finally {
    await control.close();
  }
}

/** Request best-effort cancellation; terminal builds are returned unchanged. */
export async function cancelBuild(
  options: RuntimeAdapterOptions,
  workspaceRoot: string,
  engineBuildId: string,
  reason?: string,
): Promise<EngineBuildStatus> {
  const host = await openHost(options, workspaceRoot);
  const control = await host.openControl();
  try {
    const view = await control.cancel(engineBuildId, reason);
    if (view === undefined) return { engineBuildId, found: false };
    return {
      engineBuildId,
      found: true,
      activity: view.activity,
      ...(view.outcome === undefined ? {} : { outcome: view.outcome }),
      ...(view.targets.length === 0 ? {} : { targets: view.targets }),
      cancellationRequested: view.cancellationRequested,
    };
  } finally {
    await control.close();
  }
}

/** Bring the workspace's runtime worker down; builds already finished keep their Results. */
export async function stopWorker(options: RuntimeAdapterOptions, workspaceRoot: string): Promise<void> {
  const host = await openHost(options, workspaceRoot);
  const controller = await host.controller();
  await controller.worker.down();
}

// -------------------------------------------------------------------------------------------------
// C107-09 (task-107) observation, allocation and activity surfaces.

/** The project Results repository handle, kept OPEN for read operations. */
export type OpenResults = {
  readonly location: BuildResultRepositoryLocation;
  readonly repository: BuildRepository;
  readonly close: () => Promise<void>;
};

/** The project Results repository, kept OPEN for read operations (call close()). */
export async function openResultsRepository(
  options: RuntimeAdapterOptions,
  workspaceRoot: string,
): Promise<OpenResults> {
  const engine = await loadHypit(options.distributionRoot);
  const distribution = engine.videoCliDistribution;
  if (distribution.openProjectResults === undefined) {
    throw new Error("distribution does not provide a project Results repository");
  }
  return await distribution.openProjectResults(workspaceRoot, {
    packageRoot: workspaceRoot,
    ...(distribution.packageRoot === undefined
      ? {}
      : { distributionPackageRoot: distribution.packageRoot }),
  }) as OpenResults;
}

/** A fresh native ordered build id — allocated BEFORE any submission (K06.2). */
export async function allocateEngineBuildId(options: RuntimeAdapterOptions): Promise<string> {
  const engine = await loadHypit(options.distributionRoot);
  return engine.orderedBuildId(Date.now(), randomSuffix());
}

/**
 * C107-09 merged observation: active runtime view + durable Result manifest
 * in one read. `previousOutcome` pins a known terminal fact so a late or
 * racing view can never flip it (09.4 / 状态转换断言).
 */
export async function observeEngineBuild(
  options: RuntimeAdapterOptions,
  workspaceRoot: string,
  engineBuildId: string,
  previousOutcome?: BuildOutcome | null,
): Promise<BuildObservation> {
  const host = await openHost(options, workspaceRoot);
  const control = await host.openControl({ readOnly: true });
  let results: OpenResults | null = null;
  try {
    const [view, opened] = await Promise.all([
      control.inspect(engineBuildId),
      openResultsRepository(options, workspaceRoot),
    ]);
    results = opened;
    const manifest = await results.repository.read(engineBuildId);
    return observeBuild(engineBuildId, view, manifest, previousOutcome ?? null);
  } finally {
    await Promise.allSettled([control.close(), results === null ? undefined : results.close()]);
  }
}

/** Global runtime activity: every active build view + native capacity claims. */
export async function engineActivity(
  options: RuntimeAdapterOptions,
  workspaceRoot: string,
): Promise<{ builds: readonly unknown[]; capacity: readonly unknown[] }> {
  const host = await openHost(options, workspaceRoot);
  const control = await host.openControl({ readOnly: true });
  try {
    return await control.activity();
  } finally {
    await control.close();
  }
}

function randomSuffix(): string {
  const bytes = new Uint8Array(5);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("").toUpperCase();
}
