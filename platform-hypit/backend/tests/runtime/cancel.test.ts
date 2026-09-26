// cancel.test.ts — C107-09 (task-107) TC107-09-04 / 09.7: 取消幂等（终态重复
// 取消零新副作用）、取消不删除已成功的公共 Output、未知 Build 取消如实不炸。
import assert from "node:assert/strict";
import test from "node:test";

import { dispatchCommand } from "../../src/commands/dispatcher.ts";
import { fakeEngine, makeOptions, observation, type Options } from "./helpers.ts";

const PROJECT = "11111111-1111-4111-8111-111111111111";

test("TC107-09-04 cancel of an active build stops it exactly once", async () => {
  const engine = fakeEngine();
  const options = makeOptions({ engine });
  const id = "bld_cancel_active";
  try {
    // First cancel: the engine reports the cancellation back.
    engine.script(id, observation(id, { lifecycle: "active", cancellationRequested: true }));
    engine.script(id, observation(id, {
      lifecycle: "finished", outcome: "cancelled", resultReady: true,
      outputNames: ["poster.image"],
    }));
    const first = await dispatchCommand(options, "cmd-cancel-1", "build.cancel", {
      projectId: PROJECT,
      engineBuildId: id,
      reason: "用户取消",
    });
    const after = JSON.parse(first.command.resultJson ?? "{}");
    assert.equal(after.outcome, "cancelled");
    assert.deepEqual(after.outputNames, ["poster.image"], "取消保留已成功 Output");
    assert.equal(engine.cancelCalls.length, 1);

    // Repeat cancel on the terminal build: existing facts, no new engine call.
    engine.script(id, observation(id, {
      lifecycle: "finished", outcome: "cancelled", resultReady: true,
      outputNames: ["poster.image"],
    }));
    const second = await dispatchCommand(options, "cmd-cancel-1b", "build.cancel", {
      projectId: PROJECT,
      engineBuildId: id,
    });
    const repeated = JSON.parse(second.command.resultJson ?? "{}");
    assert.equal(repeated.outcome, "cancelled");
    assert.equal(engine.cancelCalls.length, 1, "重复取消零新副作用");
  } finally {
    options.cleanup();
  }
});

test("cancel of an already-complete build touches nothing", async () => {
  const engine = fakeEngine();
  const options = makeOptions({ engine });
  const id = "bld_cancel_done";
  try {
    engine.script(id, observation(id, {
      lifecycle: "finished", outcome: "complete", resultReady: true, outputNames: ["final.video"],
    }));
    const result = await dispatchCommand(options, "cmd-cancel-2", "build.cancel", {
      projectId: PROJECT,
      engineBuildId: id,
    });
    const view = JSON.parse(result.command.resultJson ?? "{}");
    assert.equal(view.outcome, "complete", "完成态不被取消翻转");
    assert.equal(engine.cancelCalls.length, 0);
  } finally {
    options.cleanup();
  }
});

test("cancel of an unknown build returns the not-found observation", async () => {
  const engine = fakeEngine();
  const options = makeOptions({ engine });
  try {
    const result = await dispatchCommand(options, "cmd-cancel-3", "build.cancel", {
      projectId: PROJECT,
      engineBuildId: "bld_never_seen",
    });
    const view = JSON.parse(result.command.resultJson ?? "{}");
    assert.equal(view.found, false);
    assert.equal(engine.cancelCalls.length, 0, "未知 Build 不触发远程取消");
  } finally {
    options.cleanup();
  }
});

test("page close (no observer) never cancels: inspecting twice leaves the build active", async (t) => {
  const options: Options = makeOptions({ renderLocal: () => Promise.resolve() });
  t.after(() => options.cleanup());
  const engine = options.buildEngine as ReturnType<typeof fakeEngine>;
  const id = "bld_observer_gone";
  engine.script(id, observation(id, { lifecycle: "active" }));
  engine.script(id, observation(id, { lifecycle: "active" }));
  for (let round = 0; round < 2; round += 1) {
    const inspected = await dispatchCommand(options, `cmd-inspect-${round}`, "build.inspect", {
      projectId: PROJECT,
      engineBuildId: id,
    });
    const view = JSON.parse(inspected.command.resultJson ?? "{}");
    assert.equal(view.lifecycle, "active");
  }
  assert.equal(engine.cancelCalls.length, 0, "观察断线不产生取消副作用");
});
