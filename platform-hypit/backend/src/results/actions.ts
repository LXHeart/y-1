/**
 * C107-10 results/actions.ts — finish / discard (T10 step 5, T10-3).
 *
 * `finishResult` persists ONLY already-accepted facts/bytes through the
 * Runtime's one-shot Result control — it never calls a Provider, so a
 * persistence failure retried after finish keeps the Provider call count
 * unchanged (TC107-10-03). `discardSubmission` only handles a submission that
 * never became active; anything else is a 409 (适用性失败 409，禁止转 build).
 */
import { DispatchError } from "../commands/dispatcher.ts";
import { openEngineHost } from "../engine/runtime-adapter.ts";
import { projectRootFor } from "../workspace/provision.ts";
import { requireEngineBuildId, requireProjectId, requireResultsAdapter } from "./repository.ts";

async function withResultControl<T>(
  options: { readonly projectsRoot: string; readonly distributionRoot?: string },
  projectId: string,
  run: (control: import("@hypit/runtime-host-node").RuntimeHostResultControl) => Promise<T>,
): Promise<T> {
  const adapter = requireResultsAdapter(options);
  const host = await openEngineHost(adapter, projectRootFor(options.projectsRoot, projectId));
  const control = await host.openResultControl();
  try {
    return await run(control);
  } finally {
    await control.close();
  }
}

export async function runResultsFinish(
  options: { readonly projectsRoot: string; readonly distributionRoot?: string },
  payload: Record<string, unknown>,
) {
  const projectId = requireProjectId(payload);
  const engineBuildId = requireEngineBuildId(payload);
  return await withResultControl(options, projectId, async (control) => {
    const finished = await control.finishResult(engineBuildId);
    if (finished === undefined) {
      throw new DispatchError("not_found", `no active submission exists for build ${engineBuildId}`);
    }
    return {
      engineBuildId: finished.id,
      outcome: finished.outcome,
      ...(finished.issue === undefined ? {} : { issue: finished.issue }),
    };
  });
}

export async function runResultsDiscard(
  options: { readonly projectsRoot: string; readonly distributionRoot?: string },
  payload: Record<string, unknown>,
) {
  const projectId = requireProjectId(payload);
  const engineBuildId = requireEngineBuildId(payload);
  return await withResultControl(options, projectId, async (control) => {
    const discarded = await control.discardSubmission(engineBuildId);
    if (!discarded) {
      // A submission that ever became active cannot be "discarded" — the
      // honest answer is 409, never a silent conversion into a build.
      throw new DispatchError("submission_not_discardable", `submission ${engineBuildId} is not in a discardable state`);
    }
    return { engineBuildId, discarded: true };
  });
}
