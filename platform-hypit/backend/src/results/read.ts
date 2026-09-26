/**
 * C107-10 results/read.ts — public Outputs listing for one finished Result.
 *
 * Lists exactly what the native manifest publishes (T10 step 1): every public
 * Output with its repository-derived kind, no folder scanning, no guessing.
 * The manifest's own outcome/failure travel alongside so a failed Build's
 * usable Outputs stay visible (TC107-09 交接: 失败带已完成 Outputs).
 */
import { DispatchError } from "../commands/dispatcher.ts";
import {
  indexOutputs,
  openResults,
  readManifest,
  requireEngineBuildId,
  requireProjectId,
  type OpenedResults,
} from "./repository.ts";

export async function runResultsRead(options: DispatcherOptionsRef, payload: Record<string, unknown>) {
  const projectId = requireProjectId(payload);
  const engineBuildId = requireEngineBuildId(payload);
  const results = await openResults(options, projectId);
  try {
    return await describeBuild(results, engineBuildId);
  } finally {
    await results.close();
  }
}

export async function describeBuild(results: OpenedResults, engineBuildId: string) {
  const manifest = await readManifest(results.repository, engineBuildId);
  if (manifest === undefined) {
    throw new DispatchError("not_found", `no Result exists for build ${engineBuildId}`);
  }
  return {
    engineBuildId,
    title: manifest.title ?? null,
    note: manifest.note ?? null,
    source: manifest.source,
    ...(manifest.run === undefined ? {} : { run: manifest.run }),
    targets: manifest.targets,
    finishedAt: manifest.finishedAt ?? null,
    outcome: manifest.outcome ?? null,
    ...(manifest.failure === undefined ? {} : { failure: manifest.failure }),
    outputs: await indexOutputs(results.repository, engineBuildId, manifest),
  };
}

export type DispatcherOptionsRef = { readonly projectsRoot: string; readonly distributionRoot?: string };

/**
 * Cross-Build history for one public Output name (T10 step 4): browse finished
 * Results newest-first, keep pages whose manifest publishes the named Output,
 * dedupe by Build id (run library and terminal history are the same repository
 * here — the dedupe contract guards double bookkeeping, not phantom rows).
 */
export async function runResultsHistory(options: DispatcherOptionsRef, payload: Record<string, unknown>) {
  const projectId = requireProjectId(payload);
  const outputName = requireString(payload, "output");
  const source = typeof payload.source === "string" && payload.source.length > 0 ? payload.source : undefined;
  const limit = typeof payload.limit === "number" && Number.isSafeInteger(payload.limit) && payload.limit > 0
    ? Math.min(payload.limit, 100)
    : 20;
  const before = typeof payload.before === "string" && payload.before.length > 0 ? payload.before : undefined;
  const results = await openResults(options, projectId);
  try {
    const page = await results.repository.browse({ ...(before === undefined ? {} : { before }), limit });
    const matched = [];
    for (const manifest of page.results) {
      if (manifest.outputs[outputName] === undefined) continue;
      if (source !== undefined && manifest.source.path !== source) continue;
      matched.push({
        engineBuildId: manifest.id,
        title: manifest.title ?? null,
        outcome: manifest.outcome ?? null,
        finishedAt: manifest.finishedAt ?? null,
        source: manifest.source.path,
      });
    }
    return { items: matched, ...(page.next === undefined ? {} : { next: page.next }) };
  } finally {
    await results.close();
  }
}

export function resultsNotFound(): DispatchError {
  return new DispatchError("not_found", "Result does not exist");
}

export function invalid(message: string): DispatchError {
  return new DispatchError("invalid_input", message);
}

export function requireString(payload: Record<string, unknown>, field: string): string {
  const value = payload[field];
  if (typeof value !== "string" || value.length === 0) {
    throw new DispatchError("invalid_input", `${field} is required`);
  }
  return value;
}

export function assertFound<T>(value: T | undefined, what: string): T {
  if (value === undefined) throw new DispatchError("not_found", `${what} does not exist`);
  return value;
}
