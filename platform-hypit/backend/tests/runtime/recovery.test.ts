// recovery.test.ts — C107-09 (task-107) TC107-09-04 + 09.8: 执行上下文丢失按
// 事实恢复（固定 id 查询、不盲重提）、终态 outcome 不被迟到观察翻转、
// 取消与完成竞态时已完成 Outputs 保留。
import assert from "node:assert/strict";
import test from "node:test";

import { observeBuild } from "../../src/runtime/observer.ts";
import { fakeEngine, makeOptions, notFound, observation, submit, type Options } from "./helpers.ts";

test("TC107-09-04 crash between allocation and submission: retry reuses the SAME engine id", async () => {
  let renders = 0;
  const options = makeOptions({
    renderLocal: () => {
      renders += 1;
      return Promise.resolve();
    },
  });
  const engine = options.buildEngine as ReturnType<typeof fakeEngine>;
  try {
    // First attempt allocates + persists the id; the "process dies" right
    // after the native submission, before any observation lands.
    await assert.rejects(() => submit(options, "cmd-recovery-1", {}), /not observable/);
    const engineId = options.store.get("cmd-recovery-1")?.engineBuildId;
    assert.ok(engineId !== null && engineId !== undefined);
    assert.equal(renders, 1);

    // Process "restart": the command is mid-flight again, engine has NO
    // evidence for the id (notFound) → the retry may resubmit the same id.
    options.store.transition("cmd-recovery-1", () => ({ state: "dispatching", resultJson: null }));
    engine.script(engineId, observation(engineId, { lifecycle: "submitting" }));
    const retried = await submit(options, "cmd-recovery-1", {});
    const view = JSON.parse(retried.command.resultJson ?? "{}");
    assert.equal(view.engineBuildId, engineId, "resubmission used the persisted id");
    assert.equal(engine.allocated.length, 1, "no fresh allocation on retry");
  } finally {
    options.cleanup();
  }
});

test("TC107-09-04 execution context lost: seeded manifest without a view stays incomplete", async () => {
  const engine = fakeEngine();
  const options = makeOptions({ engine });
  try {
    // Fresh engine allocates bld_fake_1; the manifest is seeded (found) but
    // neither an active view nor a finished outcome exists — the D-05
    // "execution context lost" window. Re-dispatch reports the fact.
    engine.script("bld_fake_1", observation("bld_fake_1", { lifecycle: "submission_incomplete" }));
    const result = await submit(options, "cmd-recovery-2", { previousOutcome: null });
    const view = JSON.parse(result.command.resultJson ?? "{}");
    assert.equal(view.found, true);
    assert.equal(view.lifecycle, "submission_incomplete");
    assert.equal(view.outcome, null, "no outcome is invented for lost context");
    assert.equal(options.store.get("cmd-recovery-2")?.engineBuildId, view.engineBuildId);
  } finally {
    options.cleanup();
  }
});

test("outcome never regresses: a pinned complete beats a late cancelled view", () => {
  const merged = observeBuild(
    "bld_race",
    // Late active view claims a cancellation decision…
    { id: "bld_race", createdAt: 0, activity: "saving-result", outcome: "cancelled",
      cancellationRequested: true, targets: [], acceptedRecords: 0, outstandingCommands: 0,
      operations: [] },
    // …but the durable manifest finished complete.
    { format: "hypit.build-result@1", id: "bld_race", targets: ["final.video"],
      outputs: { "final.video": { type: { module: { name: "m", version: "1" }, name: "t" },
        value: { kind: "inline", value: 1 } } },
      outcome: "complete", finishedAt: 1, source: { path: "main.svml" } },
    "complete",
  );
  assert.equal(merged.outcome, "complete");
  assert.equal(merged.lifecycle, "finished");
  assert.equal(merged.resultReady, true);
  assert.deepEqual(merged.outputNames, ["final.video"], "already-complete Outputs survive");
});

test("failed build keeps its earlier successful outputs visible", () => {
  const merged = observeBuild(
    "bld_partial",
    undefined,
    { format: "hypit.build-result@1", id: "bld_partial", targets: ["final.video", "poster.image"],
      outputs: { "poster.image": { type: { module: { name: "m", version: "1" }, name: "t" },
        value: { kind: "inline", value: 2 } } },
      outcome: "failed", finishedAt: 2, failure: "target render failed",
      source: { path: "main.svml" } },
    null,
  );
  assert.equal(merged.outcome, "failed");
  assert.deepEqual(merged.outputNames, ["poster.image"], "已完成 Output 不因终态 failed 消失");
});

test("an unseen build observes as not-found without inventing state", () => {
  const merged = observeBuild("bld_ghost", undefined, undefined, null);
  assert.equal(merged.found, false);
  assert.equal(merged.outcome, null);
  assert.deepEqual(notFound("bld_ghost"), merged);
});

test("build.submit with no engine evidence and no executor config fails honestly", async (t) => {
  const options = makeOptions({ engine: fakeEngine() });
  t.after(() => options.cleanup());
  const { engineExecutor: _drop, ...bare } = options;
  void _drop;
  await assert.rejects(
    () => submit(bare as Options, "cmd-recovery-3", {}),
    /engine executor not configured/,
  );
});
