// observer.ts — C107-09 (task-107) merged Build observation (card step 09.4).
//
// One Build's truth lives in TWO native places while it runs: the active
// runtime control view (submission/execution stores — active evidence only)
// and the project Result repository (durable manifest, seeded at submission
// and finished once the outcome is decided and the Outputs are written).
// This module merges them into the stable observation the broker exposes:
// lifecycle / outcome / resultReady are STRICTLY separate fields and a known
// outcome never regresses (§4.3: outcome=null→complete|failed|cancelled is
// one-way; a storage backfill changes resultReady, never outcome).
import type { BuildView } from "@hypit/runtime-host-node";
import type { BuildResultManifest } from "@hypit/build-result";

export type BuildLifecycle =
  | "submitting"
  | "active"
  | "execution_decided"
  | "result_pending"
  | "finished"
  | "submission_incomplete";

export type BuildOutcome = "complete" | "failed" | "cancelled";

export type BuildObservation = {
  readonly engineBuildId: string;
  readonly found: boolean;
  readonly lifecycle: BuildLifecycle;
  readonly outcome: BuildOutcome | null;
  /** True only when the durable manifest carries the final outcome. */
  readonly resultReady: boolean;
  readonly activity: string | null;
  readonly targets: readonly string[];
  readonly outputNames: readonly string[];
  readonly cancellationRequested: boolean;
  readonly failure: string | null;
  /** Remote endpoint/pool/model waits visible on the active view (09.5). */
  readonly waits: readonly BuildWait[];
  readonly finishedAt: string | null;
};

export type BuildWait = {
  readonly kind: "remote" | "local";
  readonly endpoint: string | null;
  readonly pool: string | null;
  readonly reason: string;
};

type NativeOperationView = {
  readonly id?: string | undefined;
  readonly endpoint?: string | undefined;
  readonly status?: string | undefined;
  readonly wakeAt?: number | undefined;
  readonly receipt?: { readonly id: string; readonly url?: string } | undefined;
  readonly progress?: { readonly phase?: string | undefined } | undefined;
  readonly failure?: { readonly code: string; readonly message: string } | undefined;
};

/**
 * Merge one build's active view (undefined once the runtime moved on) with
 * its durable Result manifest (undefined until the repository is addressed).
 * `previousOutcome` (the caller's last persisted fact) wins over conflicting
 * fresh observations — a terminal state is never overwritten (K05/K06).
 */
export function observeBuild(
  engineBuildId: string,
  view: BuildView | undefined,
  manifest: BuildResultManifest | undefined,
  previousOutcome: BuildOutcome | null = null,
): BuildObservation {
  if (view === undefined && manifest === undefined) {
    return {
      engineBuildId,
      found: false,
      lifecycle: "submitting",
      outcome: null,
      resultReady: false,
      activity: null,
      targets: [],
      outputNames: [],
      cancellationRequested: false,
      failure: null,
      waits: [],
      finishedAt: null,
    };
  }

  // Outcome precedence: the persisted caller fact, then the durable manifest,
  // then the live execution decision. Each level only fills a null.
  const outcome = previousOutcome
    ?? manifestOutcome(manifest)
    ?? (view?.outcome === undefined ? null : view.outcome as BuildOutcome);
  const manifestReady = manifest?.outcome !== undefined;

  // Lifecycle: the durable repository is authoritative once finished; the
  // active view carries the pre-finish phases (transients may be skipped,
  // never regressed — the caller enforces forward-only CAS on this value).
  let lifecycle: BuildLifecycle;
  if (manifestReady) {
    lifecycle = "finished";
  } else if (view !== undefined) {
    if (view.activity === "submitting") {
      lifecycle = "submitting";
    } else if (outcome !== null) {
      // Decision known, Result write still open — never claim success.
      lifecycle = "result_pending";
    } else {
      lifecycle = "active";
    }
  } else {
    // No active evidence but a seeded manifest: the execution context is
    // gone while the Result never finished (D-05 recovery window).
    lifecycle = "submission_incomplete";
  }

  const waits = view === undefined ? [] : describeWaits(view.operations ?? []);
  return {
    engineBuildId,
    found: true,
    lifecycle,
    outcome,
    resultReady: manifestReady,
    activity: view?.activity ?? null,
    targets: view?.targets ?? manifest?.targets ?? [],
    outputNames: manifest === undefined ? [] : Object.keys(manifest.outputs),
    cancellationRequested: view?.cancellationRequested ?? false,
    failure: manifest?.failure ?? view?.issue?.message ?? null,
    waits,
    finishedAt: manifest?.finishedAt === undefined ? null : new Date(manifest.finishedAt).toISOString(),
  };
}

function manifestOutcome(manifest: BuildResultManifest | undefined): BuildOutcome | null {
  return manifest?.outcome === undefined ? null : manifest.outcome;
}

/**
 * Endpoint/pool waits for the activity API: an operation parked on a remote
 * wake (receipt/wakeAt, status pending) is a REMOTE wait; anything running
 * or failed locally is LOCAL work. Remote polls must not occupy the local
 * render slot (09.5 / D-05).
 */
export function describeWaits(operations: readonly NativeOperationView[]): readonly BuildWait[] {
  const waits: BuildWait[] = [];
  for (const operation of operations) {
    const status = operation.status ?? "unknown";
    if (status === "completed") continue;
    const remote = status === "pending"
      && (operation.wakeAt !== undefined || operation.receipt !== undefined);
    waits.push({
      kind: remote ? "remote" : "local",
      endpoint: operation.endpoint ?? null,
      pool: null,
      reason: remote
        ? "等待远端回执"
        : operation.failure === undefined
          ? `本地执行（${status}）`
          : `本地失败（${operation.failure.code}）`,
    });
  }
  return waits;
}

/** A build no longer needs the local render slot once only remote waits remain. */
export function holdsLocalRender(observation: BuildObservation): boolean {
  if (observation.outcome !== null || observation.resultReady) return false;
  if (observation.lifecycle === "finished" || observation.lifecycle === "submission_incomplete") {
    return false;
  }
  const outstanding = observation.waits.filter((wait) => wait.kind !== "remote");
  return outstanding.length > 0 || observation.waits.length === 0;
}

/** Forward-only lifecycle rank (the Java side mirrors this order). */
export const LIFECYCLE_ORDER: readonly BuildLifecycle[] = [
  "submitting",
  "active",
  "execution_decided",
  "result_pending",
  "finished",
  // submission_incomplete is a side state: it is only entered from submitting
  // and is not "after" finished; it is ranked last purely so a row already
  // marked incomplete does not bounce back to active on a late view.
  "submission_incomplete",
];

export function lifecycleRank(lifecycle: BuildLifecycle): number {
  const index = LIFECYCLE_ORDER.indexOf(lifecycle);
  return index < 0 ? 0 : index;
}

/** Stable per-observation summary used for event delta detection (09.6). */
export type ObservationSummary = {
  readonly lifecycle: BuildLifecycle;
  readonly outcome: BuildOutcome | null;
  readonly outputCount: number;
};

export function observationSummary(observation: BuildObservation): ObservationSummary {
  return {
    lifecycle: observation.lifecycle,
    outcome: observation.outcome,
    outputCount: observation.outputNames.length,
  };
}

/**
 * Event delta between two observations of the same build (09.6 / 状态转换断言):
 * null when nothing progressed (a repeated observation appends NO event —
 * duplicate SSE frames are dropped, state never regresses). Terminal is
 * emitted exactly once: once outcome is known it can only repeat, never
 * change, so later observations stay null-delta.
 */
export function observationDelta(
  previous: ObservationSummary | null,
  next: ObservationSummary,
): { kind: "progress" | "terminal"; data: ObservationSummary } | null {
  if (previous === null) {
    return {
      kind: next.outcome === null ? "progress" : "terminal",
      data: next,
    };
  }
  if (previous.outcome !== null && next.outcome === previous.outcome) {
    return null;
  }
  const progressed = lifecycleRank(next.lifecycle) > lifecycleRank(previous.lifecycle)
    || next.outcome !== null
    || next.outputCount > previous.outputCount;
  if (!progressed) return null;
  return {
    kind: next.outcome === null ? "progress" : "terminal",
    data: next,
  };
}
