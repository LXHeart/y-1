// events.test.ts — C107-09 (task-107) TC107-09-03 + 09.6: 观察事件持久且可重建
// （sequence 单调、重复观察零事件、终帧幂等、断线按游标续接、状态不倒退）。
import assert from "node:assert/strict";
import test from "node:test";

import { dispatchCommand } from "../../src/commands/dispatcher.ts";
import { appendJobEvent, appendTerminalJobEvent, readJobEvents } from "../../src/commands/events.ts";
import {
  observationDelta,
  observationSummary,
} from "../../src/runtime/observer.ts";
import { fakeEngine, makeOptions, observation, type Options } from "./helpers.ts";
import type { BuildObservation } from "../../src/runtime/observer.ts";

const PROJECT = "11111111-1111-4111-8111-111111111111";

test("observationDelta: repeats append nothing, only real progress appends", () => {
  const active = observationSummary(observation("bld-e", { lifecycle: "active" }));
  assert.notEqual(observationDelta(null, active), null, "first observation is an event");
  assert.equal(observationDelta(active, active), null, "重复观察零事件");

  const progressed = observationSummary(observation("bld-e", { lifecycle: "result_pending" }));
  const delta = observationDelta(active, progressed);
  assert.equal(delta?.kind, "progress");

  const terminal = observationSummary(observation("bld-e", {
    lifecycle: "finished", outcome: "complete",
  }));
  assert.equal(observationDelta(progressed, terminal)?.kind, "terminal");
  assert.equal(observationDelta(terminal, terminal), null, "终帧只发一次");

  // A stale view (finished → active, outputs unchanged) never regresses.
  assert.equal(observationDelta(terminal, active), null, "状态不倒退");
});

test("build.inspect projects durable events with monotonic sequences and cursor resume", async () => {
  const engine = fakeEngine();
  const options: Options = makeOptions({ engine });
  try {
    const id = "bld-events";
    const steps = [
      observation(id, { lifecycle: "active" }),
      observation(id, { lifecycle: "result_pending", outputNames: ["poster.image"] }),
      observation(id, { lifecycle: "active" }), // stale/repeat — no event
      observation(id, { lifecycle: "finished", outcome: "complete", resultReady: true,
        outputNames: ["poster.image", "final.video"] }),
    ];
    for (const [round, step] of steps.entries()) {
      engine.script(id, step);
      await dispatchCommand(options, `cmd-evt-${round}`, "build.inspect", {
        projectId: PROJECT,
        engineBuildId: id,
      });
    }

    const events = readJobEvents(options.store, `build-${id}`, 0);
    assert.equal(events.length, 3, "只有三次真实推进产生事件");
    assert.deepEqual(events.map((event) => event.type), ["progress", "progress", "terminal"]);
    const sequences = events.map((event) => event.sequence);
    assert.deepEqual(sequences, [...sequences].sort((a, b) => a - b), "sequence 单调不减");
    for (let index = 1; index < sequences.length; index += 1) {
      assert.ok(sequences[index]! > sequences[index - 1]!, "sequence 严格递增");
    }

    // Cursor resume: a client holding sequence 1 only sees later frames.
    const resumed = readJobEvents(options.store, `build-${id}`, 1);
    assert.deepEqual(resumed.map((event) => event.type), ["progress", "terminal"]);
  } finally {
    options.cleanup();
  }
});

test("terminal frames are idempotent under the store's event journal", () => {
  const options: Options = makeOptions({ renderLocal: () => Promise.resolve() });
  try {
    const store = options.store;
    const data = { lifecycle: "finished", outcome: "cancelled", outputCount: 1 };
    const first = appendTerminalJobEvent(store, "build-bld-t", data);
    const second = appendTerminalJobEvent(store, "build-bld-t", data);
    assert.equal(second.sequence, first.sequence, "相同终态重复写入不增长 sequence");
    appendJobEvent(store, "build-bld-t", "progress", { lifecycle: "active" });
    const third = appendTerminalJobEvent(store, "build-bld-t", data);
    assert.ok(third.sequence > first.sequence, "终态后的新事件继续单调追加");
    const all = readJobEvents(options.store, "build-bld-t", 0);
    assert.deepEqual(all.map((event) => event.type), ["terminal", "progress", "terminal"]);
  } finally {
    options.cleanup();
  }
});

test("duplicate SSE frames cannot cancel or rewind: repeated terminal observations stay terminal", async () => {
  const engine = fakeEngine();
  const options: Options = makeOptions({ engine });
  try {
    const id = "bld-dup";
    const finished = observation(id, {
      lifecycle: "finished", outcome: "failed", resultReady: true,
    });
    const lateActive = observation(id, { lifecycle: "active" });
    for (const [round, step] of twice(finished, lateActive).entries()) {
      engine.script(id, step);
      await dispatchCommand(options, `cmd-dup-${round}`, "build.inspect", {
        projectId: PROJECT,
        engineBuildId: id,
      });
    }
    const events = readJobEvents(options.store, `build-${id}`, 0);
    assert.deepEqual(events.map((event) => event.type), ["terminal"]);
    const terminal = events[0]?.data as Record<string, unknown>;
    assert.equal(terminal.outcome, "failed", "迟到 active 观察没有产生回退事件");
  } finally {
    options.cleanup();
  }
});

function twice(first: BuildObservation, second: BuildObservation): readonly BuildObservation[] {
  return [first, first, second];
}
