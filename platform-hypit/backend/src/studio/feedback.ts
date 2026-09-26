// feedback.ts — C107-18 (task-107) broker bridge over the upstream FEEDBACK store.
//
// K10.2/K11.5: FEEDBACK.json inside the project workspace is the ONLY editable
// comment truth. This bridge adds no second store: it loads the upstream
// studio modules VERBATIM from the pinned distribution checkout
// (createFeedbackStore / readFeedbackMutation keep their exact add/replace/
// delete semantics, stable ids, run binding and second times) and layers the
// broker-side baseHash CAS on top — a stale hash rejects only the current
// mutation and never overwrites concurrent edits (TC107-18-02/03).
import { createHash } from "node:crypto";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { pathToFileURL } from "node:url";

import { DispatchError } from "../commands/dispatcher.ts";
import { projectRootFor } from "../workspace/provision.ts";

export type FeedbackComment = {
  readonly id: string;
  readonly run: string;
  readonly at: number;
  readonly text: string;
  readonly resolved?: boolean;
};

export type FeedbackDocument = { readonly comments: readonly FeedbackComment[] };

type UpstreamStore = {
  readonly path: string;
  read(): Promise<FeedbackDocument>;
  mutate(mutation: unknown): Promise<FeedbackDocument>;
};

/**
 * The upstream distribution checkout carries the studio package's TS sources.
 * A single absolute file URL keeps the load pinned to the SAME tree the engine
 * runs (the package-resolution hook only resolves literal export keys, and the
 * studio package publishes `./src/*` as a wildcard).
 */
function upstreamModule(distributionRoot: string, relative: string): Promise<Record<string, unknown>> {
  const file = join(distributionRoot, "packages/studio/src", relative);
  if (!existsSync(file)) {
    throw new DispatchError("engine_unavailable", `distribution lacks studio module ${relative}`);
  }
  return import(pathToFileURL(file).href) as Promise<Record<string, unknown>>;
}

export function feedbackHash(document: FeedbackDocument): string {
  return createHash("sha256").update(JSON.stringify(document)).digest("hex");
}

async function upstreamStore(distributionRoot: string, workspaceRoot: string): Promise<UpstreamStore> {
  const module = await upstreamModule(distributionRoot, "feedback-store.ts");
  const createFeedbackStore = module.createFeedbackStore as (root: string) => UpstreamStore;
  if (typeof createFeedbackStore !== "function") {
    throw new DispatchError("engine_unavailable", "distribution feedback-store has no createFeedbackStore");
  }
  return createFeedbackStore(workspaceRoot);
}

function workspaceRootFor(projectsRoot: string, projectId: string): string {
  return join(projectRootFor(projectsRoot, projectId), "work");
}

/** The upstream FeedbackView shape: file, run, filtered comments. */
export async function readFeedback(
  distributionRoot: string,
  projectsRoot: string,
  projectId: string,
  run: string | undefined,
): Promise<unknown> {
  const workspaceRoot = workspaceRootFor(projectsRoot, projectId);
  const store = await upstreamStore(distributionRoot, workspaceRoot);
  const document = await store.read();
  const comments = run === undefined ? document.comments : document.comments.filter((entry) => entry.run === run);
  return {
    file: "FEEDBACK.json",
    ...(run === undefined ? {} : { run }),
    comments,
    hash: feedbackHash(document),
  };
}

/**
 * Apply a batch of upstream-format mutations under the broker CAS. expectedHash
 * is the caller's view of the whole document; a mismatch rejects the WHOLE
 * batch before any mutation lands (the caller keeps its unsaved input), while
 * a matching hash applies mutations sequentially through the upstream store's
 * serialized conflict checks.
 */
export async function mutateFeedback(
  distributionRoot: string,
  projectsRoot: string,
  projectId: string,
  mutations: readonly unknown[],
  expectedHash: string | undefined,
): Promise<unknown> {
  if (!Array.isArray(mutations) || mutations.length === 0) {
    throw new DispatchError("invalid_input", "feedback.mutate needs a non-empty mutations array");
  }
  const workspaceRoot = workspaceRootFor(projectsRoot, projectId);
  const store = await upstreamStore(distributionRoot, workspaceRoot);
  const before = await store.read();
  const baseHash = feedbackHash(before);
  if (expectedHash !== undefined && expectedHash !== baseHash) {
    throw new DispatchError("feedback_conflict", "FEEDBACK changed outside this view; input preserved");
  }
  const feedbackModule = await upstreamModule(distributionRoot, "feedback.ts");
  const readFeedbackMutation = feedbackModule.readFeedbackMutation as (value: unknown) => unknown;
  if (typeof readFeedbackMutation !== "function") {
    throw new DispatchError("engine_unavailable", "distribution feedback module has no readFeedbackMutation");
  }
  let document = before;
  for (const raw of mutations) {
    const mutation = readFeedbackMutation(raw);
    document = await store.mutate(mutation);
  }
  return { comments: document.comments, hash: feedbackHash(document), applied: mutations.length };
}
