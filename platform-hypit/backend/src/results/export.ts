/**
 * C107-10 results/export.ts — per-Output export surface (T10-1/T10-3).
 *
 * Scalar → JSON value; Resource → the real bytes via a content-addressed
 * resource handle (idempotent by sha256); Composite → the value document plus
 * every bound Resource file as its own handle. External files remain live
 * external dependencies: we report the exact uri and current reachability
 * instead of exporting an empty package (T10 step 3).
 */
import { DispatchError } from "../commands/dispatcher.ts";
import { mediaHandleRegistry } from "../tools/media.ts";
import { registerResource, resolveResource, type HandleRegistry } from "../resources/handles.ts";
import { openResults, requireEngineBuildId, requireOutputName, requireProjectId, resultFilePath, resultsRootOf } from "./repository.ts";
import type { DispatcherOptionsRef } from "./read.ts";

type ExportOptions = DispatcherOptionsRef & {
  readonly store: Parameters<typeof mediaHandleRegistry>[0];
};

function resultsHandleRegistry(options: ExportOptions): HandleRegistry {
  const resourcesRoot = `${options.projectsRoot}/../resources`;
  return mediaHandleRegistry(options.store, resourcesRoot, options.projectsRoot);
}

async function registerBuildFile(
  registry: HandleRegistry,
  projectId: string,
  locationRoot: string,
  engineBuildId: string,
  file: { readonly kind: "build-file"; readonly path: string; readonly build?: string; readonly size: number; readonly mediaType: string },
) {
  const absolutePath = resultFilePath(locationRoot, file.build ?? engineBuildId, file.path);
  return await registerResource(registry, {
    absolutePath,
    projectId,
    mediaType: file.mediaType,
    role: "result-export",
    sizeBytes: file.size,
  });
}

export async function runResultsExport(options: ExportOptions, payload: Record<string, unknown>) {
  const projectId = requireProjectId(payload);
  const engineBuildId = requireEngineBuildId(payload);
  const output = requireOutputName(payload);
  const results = await openResults(options, projectId);
  const registry = resultsHandleRegistry(options);
  const repositoryRoot = resultsRootOf(results);
  try {
    const resolved = await results.repository.resolve(engineBuildId, output);
    if (resolved === undefined) {
      throw new DispatchError("not_found", `Output ${output} does not exist`);
    }
    const value = resolved.value;
    if (value.kind === "inline") {
      return { kind: "scalar", mediaType: "application/json", value: value.value };
    }
    if (value.kind === "value") {
      const document = value.document;
      const files = [];
      for (const binding of document.resources) {
        const file = binding.file;
        if (file.kind === "external-file") {
          files.push({ kind: "external-file", uri: file.uri, size: file.size, mediaType: file.mediaType });
          continue;
        }
        const record = await registerBuildFile(registry, projectId, repositoryRoot, engineBuildId, file);
        files.push({
          kind: "build-file",
          handle: record.handle,
          sha256: record.sha256,
          size: record.sizeBytes,
          mediaType: file.mediaType,
          path: file.path,
        });
      }
      return { kind: "composite", valueDocument: JSON.stringify(document), files };
    }
    const file = value;
    if (file.kind === "external-file") {
      return {
        kind: "external-file",
        uri: file.uri,
        size: file.size,
        mediaType: file.mediaType,
        reachable: await probeExternal(file.uri),
      };
    }
    const record = await registerBuildFile(registry, projectId, repositoryRoot, engineBuildId, file);
    return {
      kind: "resource",
      mediaType: record.mediaType ?? file.mediaType,
      size: record.sizeBytes,
      sha256: record.sha256,
      handle: record.handle,
    };
  } finally {
    await results.close();
  }
}

async function probeExternal(uri: string): Promise<boolean> {
  try {
    const response = await fetch(uri, { method: "HEAD" });
    return response.ok;
  } catch {
    return false;
  }
}

/** Resolve a previously returned handle (Java streams bytes from /internal/v1/resources). */
export async function resolveExportHandle(options: ExportOptions, handle: string) {
  return await resolveResource(resultsHandleRegistry(options), handle);
}
