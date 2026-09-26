// helpers.ts — C107-09 (task-107) scripted engine port + dispatcher options
// for the runtime command tests (K13 layering: unit layer fakes the engine;
// the live path is covered by tests/engine/compile-adapter.test.ts).
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { CommandStore } from "../../src/commands/store.ts";
import { dispatchCommand, type BuildEnginePort, type DispatcherOptions } from "../../src/commands/dispatcher.ts";
import type { BuildObservation, BuildOutcome } from "../../src/runtime/observer.ts";
import type { RenderCapacity } from "../../src/runtime/capacity.ts";

export type ScriptedEngine = BuildEnginePort & {
  /** Observations returned by observe(), consumed in order per build id. */
  readonly script: (engineBuildId: string, observation: BuildObservation) => void;
  readonly reset: (engineBuildId: string) => void;
  readonly observations: readonly BuildObservation[];
  readonly cancelCalls: readonly { engineBuildId: string; reason?: string | undefined }[];
  readonly allocated: readonly string[];
};

export function notFound(engineBuildId: string): BuildObservation {
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

export function observation(
  engineBuildId: string,
  patch: Partial<BuildObservation> = {},
): BuildObservation {
  return {
    ...notFound(engineBuildId),
    found: true,
    lifecycle: "active",
    ...patch,
  };
}

export function fakeEngine(overrides: Partial<BuildEnginePort> = {}): ScriptedEngine {
  const scripted = new Map<string, BuildObservation[]>();
  const seen: BuildObservation[] = [];
  const cancelCalls: { engineBuildId: string; reason?: string | undefined }[] = [];
  const allocated: string[] = [];
  const engine: ScriptedEngine = {
    script(engineBuildId, item) {
      const queue = scripted.get(engineBuildId) ?? [];
      queue.push(item);
      scripted.set(engineBuildId, queue);
    },
    reset(engineBuildId) {
      scripted.delete(engineBuildId);
    },
    get observations() {
      return seen;
    },
    get cancelCalls() {
      return cancelCalls;
    },
    get allocated() {
      return allocated;
    },
    async allocateBuildId() {
      const id = `bld_fake_${allocated.length + 1}`;
      allocated.push(id);
      return id;
    },
    async observe(_projectRoot, engineBuildId) {
      const queue = scripted.get(engineBuildId);
      const next = queue?.shift();
      const value = next ?? notFound(engineBuildId);
      seen.push(value);
      return value;
    },
    async cancel(_projectRoot, engineBuildId, reason) {
      const found = scripted.has(engineBuildId);
      cancelCalls.push({ engineBuildId, reason });
      return { found };
    },
    async logs() {
      return { records: [], nextCursor: null, total: 0, sources: [] };
    },
    async activity() {
      return { builds: [], capacity: [] };
    },
    ...overrides,
  };
  return engine;
}

export type Options = DispatcherOptions & {
  readonly store: CommandStore;
  readonly cleanup: () => void;
};

export function makeOptions(overrides: {
  engine?: ScriptedEngine;
  capacity?: RenderCapacity;
  capacityPollMs?: number;
  renderLocal?: (payload: Record<string, unknown>) => Promise<void>;
}): Options {
  const dir = mkdtempSync(join(tmpdir(), "hypit-runtime-test-"));
  const store = new CommandStore(join(dir, "bridge.sqlite"));
  const options: Options = {
    store,
    projectsRoot: join(dir, "projects"),
    distributionRoot: join(dir, "generated"),
    ...(overrides.capacity === undefined ? {} : { capacity: overrides.capacity }),
    capacityPollMs: overrides.capacityPollMs ?? 5,
    buildEngine: overrides.engine ?? fakeEngine(),
    engineExecutor: async (kind, payload) => {
      if (kind !== "render.local") throw new Error(`unexpected engine kind ${kind}`);
      if (overrides.renderLocal !== undefined) {
        await overrides.renderLocal(payload as Record<string, unknown>);
      }
      return { ok: true };
    },
    cleanup: () => {
      store.close();
      rmSync(dir, { recursive: true, force: true });
    },
  };
  return options;
}

export async function submit(options: Options, commandId: string, payload: Record<string, unknown>) {
  return await dispatchCommand(options, commandId, "build.submit", {
    runFile: "main.svrun",
    projectId: "11111111-1111-4111-8111-111111111111",
    ...payload,
  });
}

export function outcomeOf(result: unknown): BuildOutcome | null {
  const value = (result ?? {}) as Record<string, unknown>;
  return (value.outcome ?? null) as BuildOutcome | null;
}
