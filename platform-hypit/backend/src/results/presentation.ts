/**
 * C107-10 results/presentation.ts — human presentation write-back (T10 step 2).
 *
 * title/note/highlightedOutputs/outputDisplayNames update the ORIGINAL Result
 * repository in place; Output identity never changes (upstream
 * `updatePresentation` contract). The Java PATCH endpoint forwards here.
 */
import { DispatchError } from "../commands/dispatcher.ts";
import { openResults, requireEngineBuildId, requireProjectId } from "./repository.ts";
import type { DispatcherOptionsRef } from "./read.ts";
import type { BuildResultPresentationUpdate } from "@hypit/build-result";

export type PresentationUpdate = {
  readonly title?: string | null;
  readonly note?: string | null;
  readonly highlightedOutputs?: readonly string[];
  readonly outputDisplayNames?: Readonly<Record<string, string | null>>;
};

export function parsePresentationUpdate(payload: Record<string, unknown>): PresentationUpdate {
  const update: { title?: string | null; note?: string | null; highlightedOutputs?: string[]; outputDisplayNames?: Record<string, string | null> } = {};
  if (payload.title !== undefined) {
    if (payload.title !== null && typeof payload.title !== "string") {
      throw new DispatchError("invalid_input", "title must be a string or null");
    }
    update.title = payload.title as string | null;
  }
  if (payload.note !== undefined) {
    if (payload.note !== null && typeof payload.note !== "string") {
      throw new DispatchError("invalid_input", "note must be a string or null");
    }
    update.note = payload.note as string | null;
  }
  if (payload.highlightedOutputs !== undefined) {
    if (!Array.isArray(payload.highlightedOutputs)
      || payload.highlightedOutputs.some((item) => typeof item !== "string")) {
      throw new DispatchError("invalid_input", "highlightedOutputs must be a string array");
    }
    update.highlightedOutputs = payload.highlightedOutputs as string[];
  }
  if (payload.outputDisplayNames !== undefined) {
    if (payload.outputDisplayNames === null || typeof payload.outputDisplayNames !== "object"
      || Array.isArray(payload.outputDisplayNames)) {
      throw new DispatchError("invalid_input", "outputDisplayNames must be an object");
    }
    const names: Record<string, string | null> = {};
    for (const [name, value] of Object.entries(payload.outputDisplayNames as Record<string, unknown>)) {
      if (value !== null && typeof value !== "string") {
        throw new DispatchError("invalid_input", `displayName for ${name} must be a string or null`);
      }
      names[name] = value as string | null;
    }
    update.outputDisplayNames = names;
  }
  return update;
}

export async function runResultsPresentation(
  options: DispatcherOptionsRef,
  payload: Record<string, unknown>,
) {
  const projectId = requireProjectId(payload);
  const engineBuildId = requireEngineBuildId(payload);
  const update = parsePresentationUpdate(payload);
  const results = await openResults(options, projectId);
  try {
    const manifest = await results.repository.updatePresentation(engineBuildId,
      update as BuildResultPresentationUpdate);
    return {
      engineBuildId,
      title: manifest.title ?? null,
      note: manifest.note ?? null,
      highlightedOutputs: manifest.highlightedOutputs ?? [],
    };
  } catch (error) {
    if (error instanceof Error && error.message.includes("does not exist")) {
      throw new DispatchError("not_found", `no Result exists for build ${engineBuildId}`);
    }
    throw error;
  } finally {
    await results.close();
  }
}
