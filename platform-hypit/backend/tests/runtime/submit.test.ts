// submit.test.ts — C107-09 (task-107) TC107-09-01: 同 command/固定 engineId 的
// 重复 submit 与丢 ack 恢复只产生一个原生 Build；engineBuildId 在任何原生提交
// 之前已持久化（K06.2 accept 顺序）；同 id 不同 body 是冲突不是重放。
import assert from "node:assert/strict";
import test from "node:test";

import { CommandConflictError } from "../../src/commands/store.ts";
import { fakeEngine, makeOptions, observation, submit, type Options } from "./helpers.ts";

test("TC107-09-01 repeated submit of the same command replays one native build", async () => {
  let renders = 0;
  const options = makeOptions({
    // Deterministic first allocation on a fresh engine: bld_fake_1.
    renderLocal: () => {
      renders += 1;
      return Promise.resolve();
    },
  });
  const engine = options.buildEngine as ReturnType<typeof fakeEngine>;
  try {
    engine.script("bld_fake_1", observation("bld_fake_1", { lifecycle: "active" }));
    const first = await submit(options, "cmd-submit-1", {});
    assert.equal(first.outcome, "completed");
    const recordedEngineId = options.store.get("cmd-submit-1")?.engineBuildId;
    assert.equal(recordedEngineId, "bld_fake_1");
    assert.equal(JSON.parse(first.command.resultJson ?? "{}").lifecycle, "active");

    const replay = await submit(options, "cmd-submit-1", {});
    assert.equal(replay.outcome, "replayed", "same commandId+body replays the recorded result");
    assert.equal(renders, 1, "no second native submission");
    assert.equal(replay.command.engineBuildId, recordedEngineId);
  } finally {
    options.cleanup();
  }
});

test("TC107-09-01 lost ack: a retry against the fixed engine id never allocates a second id", async () => {
  let renders = 0;
  const options = makeOptions({
    renderLocal: () => {
      renders += 1;
      return Promise.resolve();
    },
  });
  const engine = options.buildEngine as ReturnType<typeof fakeEngine>;
  try {
    engine.script("bld_fake_1", observation("bld_fake_1", { lifecycle: "active" }));
    const first = await submit(options, "cmd-submit-2", {});
    const engineId = options.store.get("cmd-submit-2")?.engineBuildId;
    assert.ok(engineId !== null);

    // "Crash": mark the command mid-flight again so runKind re-executes, but
    // the engine already accepted the build — evidence exists under the id.
    options.store.transition("cmd-submit-2", () => ({ state: "dispatching", resultJson: null }));
    assert.ok(engineId !== undefined && engineId !== null);
    engine.script(engineId, observation(engineId, { lifecycle: "active" }));
    const recovered = await submit(options, "cmd-submit-2", {});
    assert.equal(recovered.outcome, "completed");
    const view = JSON.parse(recovered.command.resultJson ?? "{}");
    assert.equal(view.found, true);
    assert.equal(renders, 1, "recovery queried the fixed id — no fresh native build");
    assert.equal(engine.allocated.length, 1, "no second engine id was ever allocated");
  } finally {
    options.cleanup();
  }
});

test("engineBuildId is durable BEFORE the native submission runs", async () => {
  let observedAtRender: string | null = null;
  const options = makeOptions({
    renderLocal: (payload) => {
      observedAtRender = options.store.get("cmd-submit-3")?.engineBuildId ?? null;
      assert.equal(
        (payload as { engineBuildId?: string }).engineBuildId,
        observedAtRender,
        "submit payload and stored id agree",
      );
      return Promise.resolve();
    },
  });
  try {
    const result = await assert.rejects(
      () => submit(options, "cmd-submit-3", {}),
      /submitted but not observable/,
    );
    void result;
    assert.ok(observedAtRender !== null, "render.local ran");
    const stored = options.store.get("cmd-submit-3");
    assert.equal(stored?.engineBuildId, observedAtRender, "the id survived the failed attempt");
    assert.equal(stored?.state, "failed");
  } finally {
    options.cleanup();
  }
});

test("same commandId with a different body is a conflict, not a replay", async () => {
  const options = makeOptions({ renderLocal: () => Promise.resolve() });
  const engine = options.buildEngine as ReturnType<typeof fakeEngine>;
  try {
    engine.script("bld_fake_1", observation("bld_fake_1", { lifecycle: "active" }));
    await submit(options, "cmd-submit-4", { runFile: "main.svrun" });
    await assert.rejects(
      () => submit(options, "cmd-submit-4", { runFile: "other.svrun" }),
      CommandConflictError,
    );
  } finally {
    options.cleanup();
  }
});
