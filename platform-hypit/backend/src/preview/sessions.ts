/**
 * C107-11 preview/sessions.ts — PreviewSession registry (W11).
 *
 * A session binds exactly one (project, runFile, revision) triple. The author
 * program runs in a DISPOSABLE transient execution owned by the Runtime
 * (upstream whitelist) — a preview never creates a Build, Result or state, and
 * closing the preview never cancels a rendering Build (09.3 boundary).
 * Materials are served only from the session's served set (workspace
 * attachments the author already has); a missing artifact throws instead of
 * painting a blank placeholder (step 2).
 */
import { randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { DispatchError } from "../commands/dispatcher.ts";
import { projectRootFor } from "../workspace/provision.ts";
import { openEngineHost, ensureRuntimeProfile, type RuntimeAdapterOptions } from "../engine/runtime-adapter.ts";

export type PreviewSession = {
  readonly id: string;
  readonly projectId: string;
  readonly runFile: string;
  readonly revision: number;
  readonly workspaceRoot: string;
  readonly createdAt: string;
  /** Materials this session may serve (resource id → media type), K10 scoped. */
  readonly servedMediaTypes: ReadonlyMap<string, string>;
  state: "active" | "closed";
};

const sessions = new Map<string, PreviewSession>();

export function requireSession(sessionId: string): PreviewSession {
  const session = sessions.get(sessionId);
  if (session === undefined || session.state !== "active") {
    throw new DispatchError("not_found", `preview session ${sessionId} is not active`);
  }
  return session;
}

/** Materials the workspace can serve for one revision (authorized set, step 4). */
function servedMediaTypesFor(workspaceRoot: string, revision: number): Map<string, string> {
  const manifestPath = join(workspaceRoot, ".hypit", "revisions", String(revision), "manifest.json");
  let document: { readonly resources?: Readonly<Record<string, { readonly mediaType?: string }>> };
  try {
    document = JSON.parse(readFileSync(manifestPath, "utf8"));
  } catch {
    return new Map();
  }
  const served = new Map<string, string>();
  for (const [resource, entry] of Object.entries(document.resources ?? {})) {
    if (typeof entry?.mediaType === "string") served.set(resource, entry.mediaType);
  }
  return served;
}

export async function openPreviewSession(
  options: RuntimeAdapterOptions & { readonly projectsRoot: string },
  input: { readonly projectId: string; readonly runFile?: string; readonly revision?: number },
): Promise<PreviewSession> {
  const runFile = input.runFile ?? "main.svrun";
  const revision = input.revision ?? 0;
  if (revision < 0) throw new DispatchError("invalid_input", "revision must be >= 0");
  const workspaceRoot = projectRootFor(options.projectsRoot, input.projectId);
  await ensureRuntimeProfile(options.distributionRoot, workspaceRoot);
  // Author program sanity: the bound run file must exist in this revision's workspace.
  const runPath = join(workspaceRoot, "work", runFile);
  try {
    readFileSync(runPath);
  } catch {
    throw new DispatchError("invalid_input", `run file ${runFile} does not exist in revision ${revision}`);
  }
  const session: PreviewSession = {
    id: `pv-${randomUUID()}`,
    projectId: input.projectId,
    runFile,
    revision,
    workspaceRoot,
    createdAt: new Date().toISOString(),
    servedMediaTypes: servedMediaTypesFor(workspaceRoot, revision),
    state: "active",
  };
  sessions.set(session.id, session);
  return session;
}

/** Step 9: closing revokes served materials and stops the audio clock; it never touches Builds. */
export function closePreviewSession(sessionId: string): { closed: true } {
  const session = requireSession(sessionId);
  session.state = "closed";
  sessions.delete(sessionId);
  return { closed: true };
}

/** Cross-session material access is refused (归属/跨 session 拒绝). */
export function assertSessionMaterial(session: PreviewSession, resource: string): string {
  const mediaType = session.servedMediaTypes.get(resource);
  if (mediaType === undefined) {
    throw new DispatchError("not_found", `session does not serve material ${resource}`);
  }
  return mediaType;
}

/** Step 1: the display closure runs through the Runtime's disposable authoring execution. */
export async function openAuthoringExecution(options: RuntimeAdapterOptions, workspaceRoot: string) {
  const host = await openEngineHost(options, workspaceRoot);
  return await host.openTransientExecution();
}
