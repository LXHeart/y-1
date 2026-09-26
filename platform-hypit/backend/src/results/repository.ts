/**
 * C107-10 results/repository.ts — shared plumbing for the Results surface.
 *
 * Every read goes through the native `BuildResultRepository` against the
 * project's durable Result location; we never scan folders or guess outputs.
 * Output kind comes from `describeOutput` (scalar / composite / resource) and
 * forwarding stays internal to the repository (K05: a forwarded Output is
 * indistinguishable from one produced by the named Build).
 */
import { buildResultDirectory } from "@hypit/build-result";
import { join } from "node:path";
import { DispatchError } from "../commands/dispatcher.ts";
import { openResultsRepository, type OpenResults } from "../engine/runtime-adapter.ts";
import { projectRootFor } from "../workspace/provision.ts";

export type ResultsAdapter = {
  readonly distributionRoot: string;
  readonly attachmentSourceDir: string;
};

export function requireResultsAdapter(options: {
  distributionRoot?: string;
}): ResultsAdapter {
  if (options.distributionRoot === undefined) {
    throw new DispatchError("engine_unavailable", "results commands need distributionRoot");
  }
  return { distributionRoot: options.distributionRoot, attachmentSourceDir: "" };
}

export type OpenedResults = OpenResults & { readonly projectRoot: string };

/** Open the project Results repository (caller MUST close). */
export async function openResults(
  options: DispatcherOptionsLike,
  projectId: string,
): Promise<OpenedResults> {
  const adapter = requireResultsAdapter(options);
  const projectRoot = projectRootFor(options.projectsRoot, projectId);
  const opened = await openResultsRepository(adapter, projectRoot);
  return { ...opened, projectRoot };
}

type DispatcherOptionsLike = { readonly projectsRoot: string; readonly distributionRoot?: string };

export function requireProjectId(payload: Record<string, unknown>): string {
  const value = payload.projectId;
  if (typeof value !== "string" || value.length === 0) {
    throw new DispatchError("invalid_input", "results commands need projectId");
  }
  return value;
}

export function requireEngineBuildId(payload: Record<string, unknown>): string {
  const value = payload.engineBuildId;
  if (typeof value !== "string" || value.length === 0) {
    throw new DispatchError("invalid_input", "results commands need engineBuildId");
  }
  return value;
}

export function requireOutputName(payload: Record<string, unknown>): string {
  const value = payload.output;
  if (typeof value !== "string" || value.length === 0) {
    throw new DispatchError("invalid_input", "results commands need output");
  }
  return value;
}

/**
 * One indexed public Output. `kind` mirrors the repository description triad;
 * forwarded Outputs surface under the naming Build (repository semantics).
 */
export type IndexedOutput = {
  readonly name: string;
  readonly kind: "scalar" | "composite" | "resource";
  readonly type: { readonly module: { readonly name: string; readonly version: string }; readonly name: string };
  readonly size?: number | undefined;
  readonly mediaType?: string | undefined;
  readonly displayName?: string | undefined;
  readonly highlighted?: boolean | undefined;
};

/** Manifest shape we rely on (subset of BuildResultManifest). */
export type ResultsManifestView = {
  readonly id: string;
  readonly title?: string;
  readonly note?: string;
  readonly highlightedOutputs?: readonly string[];
  readonly source: { readonly path: string };
  readonly run?: { readonly path: string };
  readonly targets: readonly string[];
  readonly finishedAt?: number;
  readonly outcome?: "complete" | "failed" | "cancelled";
  readonly failure?: string;
  readonly outputs: Readonly<Record<string, {
    readonly displayName?: string;
    readonly type: { readonly module: { readonly name: string; readonly version: string }; readonly name: string };
    readonly value: unknown;
  }>>;
};

export async function readManifest(
  repository: OpenResults["repository"],
  engineBuildId: string,
): Promise<ResultsManifestView | undefined> {
  return await repository.read(engineBuildId) as ResultsManifestView | undefined;
}

/** Index every public Output of a manifest through `describeOutput` (never opens bytes). */
export async function indexOutputs(
  repository: OpenResults["repository"],
  engineBuildId: string,
  manifest: ResultsManifestView,
): Promise<IndexedOutput[]> {
  const highlighted = new Set(manifest.highlightedOutputs ?? []);
  const indexed: IndexedOutput[] = [];
  for (const name of Object.keys(manifest.outputs)) {
    const description = await repository.describeOutput(engineBuildId, name);
    if (description === undefined) continue;
    const output = manifest.outputs[name]!;
    indexed.push({
      name,
      kind: description.kind,
      type: description.type,
      ...(description.kind === "resource"
        ? { size: description.size, mediaType: description.mediaType }
        : {}),
      ...(output.displayName === undefined ? {} : { displayName: output.displayName }),
      ...(highlighted.has(name) ? { highlighted: true } : {}),
    });
  }
  return indexed;
}

/**
 * The physical root of the SELECTED repository. `location.root` is only the
 * configuration base (e.g. the workspace); the default fs repository actually
 * stores under `<base>/.hypit/results` and exposes that as `.root`.
 */
export function resultsRootOf(opened: OpenResults): string {
  const candidate = (opened.repository as { readonly root?: unknown }).root;
  return typeof candidate === "string" ? candidate : opened.location.root;
}

/** Absolute path of a build-file inside its owning Build's result directory. */
export function resultFilePath(
  repositoryRoot: string,
  owningBuild: string,
  path: string,
): string {
  return join(buildResultDirectory(repositoryRoot, owningBuild), path);
}
