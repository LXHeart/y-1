// capacity.test.ts — C107-09 (task-107) TC107-09-02: 限制 1 的本地 render
// pool 下并发 Build 串行占用渲染槽；远程等待释放槽位不锁死其他 Build。
import assert from "node:assert/strict";
import test from "node:test";

import { RenderCapacity } from "../../src/runtime/capacity.ts";
import { holdsLocalRender } from "../../src/runtime/observer.ts";
import { fakeEngine, makeOptions, notFound, observation, submit, type Options } from "./helpers.ts";

test("RenderCapacity admits one, queues FIFO and hands the slot over on release", async () => {
  const gate = new RenderCapacity(1);
  const first = await gate.acquire("bld-a");
  assert.equal(first.position, 0);
  const secondPromise = gate.acquire("bld-b");
  assert.equal(gate.describe().queued.length, 1, "second build waits in the queue");
  gate.release("bld-a");
  const second = await secondPromise;
  assert.equal(second.key, "bld-b");
  assert.equal(gate.isHeld("bld-a"), false);
  assert.equal(gate.isHeld("bld-b"), true);
  gate.release("bld-b");
  assert.deepEqual(gate.describe().active, []);
});

test("RenderCapacity.abandon drops queued waiters and releases the held slot", async () => {
  const gate = new RenderCapacity(1);
  await gate.acquire("bld-a");
  const queued = gate.acquire("bld-b");
  gate.abandon("bld-b");
  gate.release("bld-a");
  // The abandoned waiter never resolves through the queue again.
  const settled = await Promise.race([
    queued.then(() => "resolved"),
    new Promise((resolve) => setTimeout(() => resolve("pending"), 20)),
  ]);
  assert.equal(settled, "pending");
});

test("holdsLocalRender: remote-only waits release the local render slot", () => {
  const remoteOnly = observation("bld-r", {
    waits: [{ kind: "remote", endpoint: "tts.remote", pool: null, reason: "等待远端回执" }],
  });
  assert.equal(holdsLocalRender(remoteOnly), false, "远程 poll 不占本地渲染槽");

  const localWork = observation("bld-l", {
    waits: [{ kind: "local", endpoint: "hyperframes.local", pool: null, reason: "本地执行（running）" }],
  });
  assert.equal(holdsLocalRender(localWork), true);

  const noOpsYet = observation("bld-n", {});
  assert.equal(holdsLocalRender(noOpsYet), true, "无操作视图按本地工作处理（编译/提交期）");

  const finished = observation("bld-f", { lifecycle: "finished", outcome: "complete", resultReady: true });
  assert.equal(holdsLocalRender(finished), false);
});

test("TC107-09-02 two concurrent submits with limit 1 never render in parallel", { timeout: 20_000 }, async () => {
  const engine = fakeEngine();
  let inFlight = 0;
  let maxInFlight = 0;
  const gate = new RenderCapacity(1);
  const gateReleases: ((key: string) => void)[] = [];
  const options = makeOptions({
    engine,
    capacity: gate,
    capacityPollMs: 5,
    renderLocal: () => {
      inFlight += 1;
      maxInFlight = Math.max(maxInFlight, inFlight);
      return new Promise<void>((resolve) => {
        gateReleases.push(() => {
          inFlight -= 1;
          resolve();
        });
      });
    },
  });
  try {
    const firstPromise = submit(options, "cmd-cap-1", {});
    // Wait until the first build holds the gate, then start the second.
    await waitUntil(() => gate.describe().active.length === 1 || gate.describe().queued.length === 1);
    const firstId = gate.describe().active[0] ?? gate.describe().queued[0];
    assert.ok(firstId !== undefined);
    engine.script(firstId, observation(firstId, {
      waits: [{ kind: "local", endpoint: "hyperframes.local", pool: null, reason: "本地执行（running）" }],
    }));
    const secondPromise = submit(options, "cmd-cap-2", {});
    // Second build must be queued while the first renders.
    await waitUntil(() => gate.describe().queued.length === 1);
    const secondEarly = await Promise.race([
      secondPromise.then(() => "fulfilled"),
      new Promise((resolve) => setTimeout(() => resolve("waiting"), 30)),
    ]);
    assert.equal(secondEarly, "waiting", "第二个 Build 在限额内等待");

    // First build finishes its local render: watcher releases, second starts.
    engine.script(firstId, observation(firstId, { lifecycle: "finished", outcome: "complete", resultReady: true }));
    gateReleases[0]?.("cmd-cap-1");
    const first = await firstPromise;
    assert.equal(first.outcome, "completed");
    await waitUntil(() => !gate.isHeld(firstId), "first build must release its slot");
    await waitUntil(
      () => gate.describe().active[0] !== undefined && gate.describe().active[0] !== firstId,
      "second build acquires the freed slot",
    );
    const secondId = gate.describe().active[0];
    assert.ok(secondId !== undefined);
    assert.notEqual(secondId, firstId);
    engine.script(secondId, observation(secondId, { lifecycle: "finished", outcome: "complete", resultReady: true }));
    gateReleases[1]?.("cmd-cap-2");
    const second = await secondPromise;
    assert.equal(second.outcome, "completed");
    assert.equal(maxInFlight, 1, "两个 Build 从未同时占用渲染槽");
  } finally {
    options.cleanup();
  }
});

test("TC107-09-02 a build parked on remote waits frees the slot for the next build", { timeout: 20_000 }, async () => {
  const engine = fakeEngine();
  const gate = new RenderCapacity(1);
  const options = makeOptions({ engine, capacity: gate, capacityPollMs: 5, renderLocal: () => Promise.resolve() });
  try {
    // Deterministic allocation: this engine's first id is bld_fake_1.
    engine.script("bld_fake_1", observation("bld_fake_1", {
      waits: [{ kind: "local", endpoint: "hyperframes.local", pool: null, reason: "本地执行（running）" }],
    }));
    engine.script("bld_fake_1", observation("bld_fake_1", {
      waits: [{ kind: "remote", endpoint: "tts.remote", pool: null, reason: "等待远端回执" }],
    }));
    engine.script("bld_fake_2", observation("bld_fake_2", {
      lifecycle: "finished", outcome: "complete", resultReady: true,
    }));
    const first = await submit(options, "cmd-cap-3", {});
    assert.equal(first.outcome, "completed");
    const firstId = options.store.get("cmd-cap-3")?.engineBuildId;
    assert.equal(firstId, "bld_fake_1");
    await waitUntil(() => !gate.isHeld(firstId ?? ""), "watcher should release the slot on remote-only waits");

    const second = await submit(options, "cmd-cap-4", {});
    assert.equal(second.outcome, "completed", "第二个 Build 无需等第一个的远端回执");
  } finally {
    options.cleanup();
  }
});

async function waitUntil(condition: () => boolean, label = "condition", budgetMs = 8_000): Promise<void> {
  const deadline = Date.now() + budgetMs;
  while (Date.now() < deadline) {
    if (condition()) return;
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
  throw new Error(`timed out waiting for ${label}`);
}

export { notFound };
