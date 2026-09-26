/**
 * C107-10 results/reuse.ts — explicit Candidate reuse (T10 step 8, T10-2).
 *
 * `proposeReuse` turns selected public Outputs into NATIVE run semantics:
 * one `<build-record>` per source Build + one `<satisfy>` per selected
 * Output, spliced into the CURRENT run file content. The result is a plain
 * changeset fragment set — applying it stays the explicit save path, we never
 * overwrite a Run in place. Selections must resolve in THIS project's Result
 * repository (another account's private Result does not exist here) and must
 * be finished Results with the named Output present.
 */
import { readFile } from "node:fs/promises";
import { DispatchError, workspaceFilePath } from "../commands/dispatcher.ts";
import { openResults, requireProjectId } from "./repository.ts";
import type { DispatcherOptionsRef } from "./read.ts";

export type ReuseSelection = {
  readonly buildId: string;
  readonly outputName: string;
  readonly target: string;
};

export function parseSelections(value: unknown): ReuseSelection[] {
  if (!Array.isArray(value) || value.length === 0) {
    throw new DispatchError("invalid_input", "reuse needs selections");
  }
  return value.map((item) => {
    if (item === null || typeof item !== "object") {
      throw new DispatchError("invalid_input", "each selection must be an object");
    }
    const record = item as Record<string, unknown>;
    for (const field of ["buildId", "outputName", "target"]) {
      if (typeof record[field] !== "string" || (record[field] as string).length === 0) {
        throw new DispatchError("invalid_input", `selection.${field} is required`);
      }
    }
    return {
      buildId: record.buildId as string,
      outputName: record.outputName as string,
      target: record.target as string,
    };
  });
}

/** Splice build-record/satisfy declarations before the first <target> line. */
export function spliceReuseDeclarations(runContent: string, declarations: readonly string[]): string {
  const lines = runContent.split("\n");
  const targetIndex = lines.findIndex((line) => line.trim().startsWith("<target"));
  if (targetIndex < 0) {
    // No explicit targets: append before the closing </svrun>.
    const close = lines.lastIndexOf("</svrun>");
    if (close < 0) {
      throw new DispatchError("invalid_input", "run file has neither <target> nor </svrun>");
    }
    lines.splice(close, 0, ...declarations);
    return lines.join("\n");
  }
  lines.splice(targetIndex, 0, ...declarations);
  return lines.join("\n");
}

type ReuseOptions = DispatcherOptionsRef & { readonly store: unknown };

export async function runResultsReuse(
  options: ReuseOptions,
  payload: Record<string, unknown>,
) {
  const projectId = requireProjectId(payload);
  const runFile = typeof payload.runFile === "string" && payload.runFile.length > 0 ? payload.runFile : "main.svrun";
  const selections = parseSelections(payload.selections);
  const results = await openResults(options, projectId);
  try {
    const declarations: string[] = [];
    const recordsByBuild = new Map<string, string>();
    let alias = 0;
    for (const selection of selections) {
      const manifest = await results.repository.read(selection.buildId);
      if (manifest === undefined) {
        throw new DispatchError("not_found",
          `build ${selection.buildId} has no Result in this project (foreign Results cannot be reused)`);
      }
      if (manifest.outcome === undefined) {
        throw new DispatchError("invalid_input", `build ${selection.buildId} is not finished yet`);
      }
      if (manifest.outputs[selection.outputName] === undefined) {
        throw new DispatchError("not_found", `Output ${selection.outputName} is not public on build ${selection.buildId}`);
      }
      if (!recordsByBuild.has(selection.buildId)) {
        const id = `prior${alias === 0 ? "" : alias}`;
        alias += 1;
        recordsByBuild.set(selection.buildId, id);
        declarations.push(
          `  <build-record id="${id}" build="${selection.buildId}" output="${selection.outputName}"/>`,
        );
      }
      declarations.push(
        `  <satisfy output="${selection.target}" candidate="${recordsByBuild.get(selection.buildId)}"/>`,
      );
    }
    const runPath = await workspaceFilePath(options as Parameters<typeof workspaceFilePath>[0], projectId, runFile);
    const current = await readFile(runPath, "utf8");
    const updatedContent = spliceReuseDeclarations(current, declarations);
    return {
      runFile,
      changes: [
        { path: runFile, action: "update", content: updatedContent },
      ],
      selections: selections.map((selection) => ({
        build: selection.buildId,
        output: selection.outputName,
        target: selection.target,
      })),
    };
  } finally {
    await results.close();
  }
}
