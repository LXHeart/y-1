// fix2-c38.test.ts — 107-fix-2 C107F2-38：维护窗崩失在途回收（stale sweep）。
//
// 背景（TC-F2-38-03 round-3 实录）：broker 进程死在 runKind 中途的命令永久停留
// dispatching（无心跳、updated_at 停在派发瞬间、重启后无人重放）——排空永不
// 收敛，backup.sh 被永久阻塞（一条 24h 前的遗留行卡死整条灾备链）。
//
// TC-F2-38-S1 E01 预置一条超龄 dispatching（updated_at 回拨 2h）+ 一条新鲜
//            dispatching → enter 只回收超龄行（unknown + stale_dispatch），
//            新鲜在途照常等待真实完成（drained 不谎报）。
// TC-F2-38-S2 E02 回收后的 unknown 行按 commandId 重放收敛：同 payload 重派
//            执行（不再 unknown），错误留痕被重派清掉，终态如实。
process.env.HYPIT_STATE_HOME ??= join(import.meta.dirname, "../../../../data/hypit/runner-state");

import { spawn } from "node:child_process";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { setTimeout as delay } from "node:timers/promises";
import assert from "node:assert/strict";
import test from "node:test";
import { DatabaseSync } from "node:sqlite";

const backendRoot = join(import.meta.dirname, "../..");
const generatedRoot = join(backendRoot, "../.generated/hypit");
const token = "fix2-c38-maintenance-token-0123456789abcdef";  // secret-scan: allow

const { CommandStore } = await import("../../src/commands/store.ts");

function spawnBroker(dataRoot: string, port: number, extraEnv: Record<string, string> = {}) {
  const child = spawn(process.execPath, ["--import", "tsx", "src/main.mjs"], {
    cwd: backendRoot,
    env: {
      ...process.env,
      HYPIT_BACKEND_PORT: String(port),
      HYPIT_INTERNAL_TOKEN: token,
      HYPIT_DATA_ROOT: join(dataRoot, "host"),
      HYPIT_GENERATED_ROOT: generatedRoot,
      HYPIT_MAINTENANCE_DRAIN_TIMEOUT_MS: "15000",
      HYPIT_MAINTENANCE_STALE_DISPATCH_MS: "60000",
      ...extraEnv,
    },
    stdio: ["ignore", "pipe", "pipe"],
  });
  return child;
}

async function waitHealthy(port: number): Promise<void> {
  let ready = false;
  for (let attempt = 0; attempt < 400 && !ready; attempt += 1) {
    ready = await fetch(`http://127.0.0.1:${port}/healthz`).then((r) => r.ok).catch(() => false);
    if (!ready) await delay(250);
  }
  assert.ok(ready, "server did not become healthy");
}

const auth = { "content-type": "application/json", authorization: `Bearer ${token}` };

interface EnterBody {
  leaseId: string;
  drained: boolean;
  activeCommands: number;
  waitedMs: number;
  sweptStale: string[];
}

/** 回拨一行的 updated_at（transition 恒写 now，超龄行只能直接 SQL 回拨）。 */
function backdate(storeFile: string, commandId: string, msAgo: number): void {
  const db = new DatabaseSync(storeFile);
  db.prepare("UPDATE commands SET updated_at = ? WHERE command_id = ?")
    .run(new Date(Date.now() - msAgo).toISOString(), commandId);
  db.close();
}

test("maintenance enter sweeps only stale in-flight orphans; fresh work still drains honestly", { timeout: 180_000 }, async (t) => {
  const dataRoot = mkdtempSync(join(tmpdir(), "fix2-c38-"));
  const port = 9266;
  // 预置：一条超龄 dispatching（死进程遗留）+ 一条新鲜 dispatching（真实在途）。
  const storeFile = join(dataRoot, "host", "bridge.sqlite");
  const seeder = new CommandStore(storeFile);
  seeder.accept("c38-stale", "plan", CommandStore.hashPayload("plan", { p: 1 }), null);
  seeder.transition("c38-stale", () => ({ state: "dispatching" }));
  seeder.accept("c38-fresh", "workspace.apply", CommandStore.hashPayload("workspace.apply", { p: 2 }), "proj-a");
  seeder.transition("c38-fresh", () => ({ state: "dispatching" }));
  seeder.close();
  backdate(storeFile, "c38-stale", 2 * 3600_000);

  const child = spawnBroker(dataRoot, port);
  t.after(() => {
    child.kill("SIGTERM");
    rmSync(dataRoot, { recursive: true, force: true });
  });
  await waitHealthy(port);

  const enterPromise = fetch(`http://127.0.0.1:${port}/internal/v1/maintenance/enter`, {
    method: "POST", headers: auth, body: JSON.stringify({ reason: "c38-sweep" }),
  }).then((r) => r.json() as Promise<EnterBody>);
  await delay(500);
  // 新鲜在途收敛到终态（模拟真实完成）——enter 必须等到它，而不是把它扫掉。
  const finisher = new CommandStore(storeFile);
  finisher.transition("c38-fresh", () => ({ state: "succeeded" }));
  finisher.close();
  const body = await enterPromise;

  assert.equal(body.drained, true, `drained after fresh completion: ${JSON.stringify(body)}`);
  assert.ok(body.waitedMs >= 400, `must wait for fresh in-flight, waitedMs=${body.waitedMs}`);
  assert.deepEqual(body.sweptStale, ["c38-stale"], `only the stale orphan swept: ${JSON.stringify(body)}`);

  const reader = new CommandStore(storeFile);
  const stale = reader.get("c38-stale");
  const fresh = reader.get("c38-fresh");
  reader.close();
  assert.equal(stale?.state, "unknown", "stale orphan must land in unknown (result unknown)");
  assert.equal(stale?.errorCode, "stale_dispatch");
  assert.equal(fresh?.state, "succeeded", "fresh in-flight must NOT be swept");
  (globalThis as { __c38Lease?: string }).__c38Lease = body.leaseId;

  await t.test("swept unknown command replays by commandId to a real terminal state", async () => {
    const exit = await fetch(`http://127.0.0.1:${port}/internal/v1/maintenance/exit`, {
      method: "POST", headers: auth, body: JSON.stringify({ leaseId: (globalThis as { __c38Lease?: string }).__c38Lease }),
    });
    assert.equal(exit.status, 200, "holder exit must be 200 before replay");
    // 同 commandId + 同 payload 重放：unknown 非终态 → 重新派发执行（plan {p:1}
    // 输入不合法，应如实 failed——关键是走出 unknown 且错误留痕不再是 stale_dispatch）。
    const replay = await fetch(`http://127.0.0.1:${port}/internal/v1/commands`, {
      method: "POST", headers: auth,
      body: JSON.stringify({ commandId: "c38-stale", kind: "plan", payload: { p: 1 } }),
    });
    assert.equal(replay.status, 200);
    const replayed = await replay.json() as { state?: string; errorCode?: string | null };
    assert.equal(replayed.state, "failed", `replay must re-dispatch to a real terminal state: ${JSON.stringify(replayed)}`);
    assert.notEqual(replayed.errorCode, "stale_dispatch", "stale marker must not survive re-dispatch");
  });
});
