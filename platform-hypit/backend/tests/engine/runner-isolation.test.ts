// runner-isolation.test.ts — C107-02 (task-107) K10.4 isolation contract
// (TC107-02-03 / TC107-02-04)；107-fix-3 C107F3-03 起以 TC-F3-03-01 机读命名
// 覆盖真实 runner 四类反例（恶意读取/秘密泄漏/超时回收，合法项目仍 check）。
//
// The runner must: reject unknown IPC kinds, reject paths escaping its slot,
// deny author code reads outside its allowed roots (process permission model),
// never see broker secrets in its environment, and fully release its slot on
// timeout/kill — including no output leakage into the next command's slot.
process.env.HYPIT_STATE_HOME ??= join(import.meta.dirname, "../../../../data/hypit/runner-state");

import { appendFileSync, cpSync, existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import assert from "node:assert/strict";
import test from "node:test";

import { startRunnerDaemon, type RunnerDaemonFixture } from "./runner-daemon.ts";
import {
  ensureChatMachinePackages,
  prepareChatWorkspace,
  repoRoot,
} from "./chat-workspace.ts";

// A secret the broker would hold; the runner must never see it. Assembled at
// runtime so no credential-shaped literal sits in tracked source.
process.env.HYPIT_INTERNAL_TOKEN = ["runner-isolation", "probe", "token"].join("-").padEnd(32, "0");

test("TC-F3-03-01: 真实daemon status握手，未知kind与slot逃逸路径被拒", { timeout: 180_000 }, async (t) => {
  const fixture: RunnerDaemonFixture = await startRunnerDaemon("isolation-status");
  t.after(() => fixture.stop());
  const supervisor = fixture.supervisor;
  await supervisor.withSlot(async (lease) => {
    const status = await lease.request("status", {}) as { state: string };
    assert.equal(status.state, "ready");

    await assert.rejects(
      () => lease.request("escape" as never, {}),
      /unknown runner kind/,
    );

    await assert.rejects(
      () => lease.request("check", { workspaceRoot: "../../../../etc", entryFile: "x" }),
      /path_escape|path does not exist|escapes the runner slot/,
    );

    await assert.rejects(
      () => lease.request("plan", {}),
      /payload.workspaceRoot must be a string/,
    );
  });
});

test("TC-F3-03-01: 恶意组件marker真实执行，宿主读取与秘密泄漏被拒，合法项目仍check", { timeout: 300_000 }, async (t) => {
  await ensureChatMachinePackages();
  const fixture: RunnerDaemonFixture = await startRunnerDaemon("isolation-malicious");
  t.after(() => fixture.stop());
  const supervisor = fixture.supervisor;
  const workspace = prepareChatWorkspace();

  // Malicious probe appended to the built activation. It fails the whole check
  // loudly (ISOLATION_FAILURE) if any guarantee is broken: a forbidden host
  // read SUCCEEDING, or the broker secret appearing in the runner env. When
  // isolation holds, the denials are swallowed and the check stays green —
  // so success here is the positive proof, not a missing rejection.
  // The marker write proves the probe actually executed inside the runner.
  const activation = join(workspace, "packages/chat-scene/dist/activation.js");
  appendFileSync(activation, `

// C107-02 isolation probe
{
  const { readFileSync, writeFileSync } = await import("node:fs");
  const forbidden = ["/etc/passwd", ${JSON.stringify(join(repoRoot, "platform-hypit/upstream/package.json"))}];
  for (const target of forbidden) {
    try {
      readFileSync(target);
      throw new Error("ISOLATION_FAILURE: runner read host file " + target);
    } catch (error) {
      if (String(error).includes("ISOLATION_FAILURE")) throw error;
    }
  }
  if (process.env.HYPIT_INTERNAL_TOKEN !== undefined) {
    throw new Error("ISOLATION_FAILURE: broker secret leaked into runner env");
  }
  const marker = (process.env.TMPDIR ?? "/tmp") + "/probe-ran.marker";
  writeFileSync(marker, JSON.stringify({
    slotEnvLeak: process.env.HYPIT_SLOT_ROOT ?? process.env.HYPIT_SLOT_OUTPUT_DIR ?? process.env.HYPIT_RUNNER_SOCKET ?? null,
  }));
}
`);

  try {
    // C107F3-03 步骤2：TMPDIR 现为 fixture 独占 tmp（W43）——目录全新，marker 只可能
    // 来自本次 daemon 子进程；共享稳定目录里的陈旧 marker 不再构成假阳性通道。
    const probeMarker = join(fixture.tmpRoot, "probe-ran.marker");
    await supervisor.withSlot(async (lease) => {
      await cpInto(lease.inputDir, workspace);
      const result = await lease.request("check", { workspaceRoot: lease.inputDir, entryFile: "chat.svml" }) as {
        ok: boolean;
        diagnostics: { message: string }[];
      };
      // If isolation broke, the probe's ISOLATION_FAILURE surfaces as ok=false
      // (or a rejected request) with the exact reason. A clean pass means every
      // forbidden read was denied and no secret leaked.
      for (const diagnostic of result.diagnostics ?? []) {
        assert.ok(!diagnostic.message.includes("ISOLATION_FAILURE"), diagnostic.message);
      }
      assert.equal(result.ok, true, "probe must find no readable host file and no leaked secret");
      assert.ok(existsSync(probeMarker), "probe marker missing: the probe never executed in the runner");
      const markerContent = JSON.parse(readFileSync(probeMarker, "utf8")) as { slotEnvLeak: string | null };
      assert.equal(markerContent.slotEnvLeak, null, "slot paths must not be exposed to author code via env");
    });

    // The same project, without the probe, still checks in a fresh slot
    const clean = prepareChatWorkspace();
    try {
      await supervisor.withSlot(async (lease) => {
        await cpInto(lease.inputDir, clean);
        const result = await lease.request("check", { workspaceRoot: lease.inputDir, entryFile: "chat.svml" }) as { ok: boolean };
        assert.equal(result.ok, true, "legitimate local project must still check after the malicious probe");
      });
    } finally {
      rmSync(clean, { recursive: true, force: true });
    }
  } finally {
    rmSync(workspace, { recursive: true, force: true });
  }
});

test("TC-F3-03-01: 超时回收slot，旧输出不泄漏到下一命令", { timeout: 240_000 }, async (t) => {
  // 机器包（字体）先落入稳定缓存，fixture 启动时种子复制进独占 state 根——
  // 本测试后半的干净 check 依赖它（原实现在 daemon 启动后才安装）。
  await ensureChatMachinePackages();
  const fixture: RunnerDaemonFixture = await startRunnerDaemon("isolation-timeout");
  t.after(() => fixture.stop());
  const supervisor = fixture.newSupervisor({ requestTimeoutMs: 300 });
  let firstOutputDir: string | undefined;
  let firstSlotDir: string | undefined;

  // a request that cannot answer in 300ms (engine load takes longer) times out
  // on the client; D-04 语义下在途命令由 daemon 跑完/宽限终止后释放容量 1，
  // 客户端超时本身立即以 runner_unavailable 失败、绝不假等。
  await assert.rejects(
    () => supervisor.withSlot(async (lease) => {
      firstOutputDir = lease.outputDir;
      firstSlotDir = lease.slotDir;
      mkdirSync(lease.outputDir, { recursive: true });
      writeFileSync(join(lease.outputDir, "stale-output.bin"), "previous command artifact");
      await lease.request("status", {}).catch(() => {}); // status is fast; force the timeout on a slow kind
      await lease.request("check", { workspaceRoot: lease.inputDir, entryFile: "chat.svml" });
    }),
    /timed out/,
  );

  assert.ok(firstOutputDir !== undefined && firstSlotDir !== undefined);
  assert.ok(!existsSync(firstSlotDir), "the timed-out lease's slot must be released by the broker side");

  // daemon 侧在途命令收敛（status.busy=false）后才接受下一条命令。
  await fixture.waitIdle();

  const supervisor2 = fixture.newSupervisor({ requestTimeoutMs: 120_000 });
  const clean = prepareChatWorkspace();
  try {
    await supervisor2.withSlot(async (lease) => {
      assert.notEqual(lease.slotDir, firstSlotDir, "each command gets its own slot");
      assert.ok(!existsSync(join(lease.outputDir, "stale-output.bin")), "previous outputs must not leak into the next slot");
      await cpInto(lease.inputDir, clean);
      const result = await lease.request("check", { workspaceRoot: lease.inputDir, entryFile: "chat.svml" }) as { ok: boolean };
      assert.equal(result.ok, true, "the next command must run in a fresh, working runner");
    });
  } finally {
    rmSync(clean, { recursive: true, force: true });
  }
});

async function cpInto(target: string, source: string): Promise<void> {
  cpSync(source, target, { recursive: true, force: true });
}
