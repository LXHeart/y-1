// fix2-c32.test.ts — 107-fix-2 C107F2-32：维护模式写入栅栏与在途执行排空
// （F33/§5 RULE-13、§6.15）。
//
// TC-F2-32-01 E01 预置 dispatching + acknowledged（其中之一带 engineBuildId=原生
//            active build 证据）→ enter 原子封写后等待真实完成（waitedMs>0），
//            在途清零才 drained=true；绝不立即宣称 drained。
// TC-F2-32-02 E02 enter 与新 submit 并发过屏障：栅栏关闭前入场的计入排空并等到
//            终态；栅栏关闭后的新副作用（commands/resources/transfers PUT）
//            一律 503；取消类（build.cancel/status）豁免照常。
// TC-F2-32-03 E03 exit 只认自己的 leaseId（他人 leaseId=409 窗口保持关闭）；
//            正确 leaseId 释放；重启 fail-closed——租约持久化，重启后新副作用
//            仍 503 直到持有者显式 exit。
// TC-F2-32-04 E04 他人持有窗口时 enter=409（不泄漏 leaseId、不得当已取得租约）；
//            backup.sh 语义：非持有者退出非零（shell 层断言在 deploy 侧冒烟，
//            此处锁端点契约）。
process.env.HYPIT_STATE_HOME ??= join(import.meta.dirname, "../../../../data/hypit/runner-state");

import { spawn } from "node:child_process";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { setTimeout as delay } from "node:timers/promises";
import assert from "node:assert/strict";
import test from "node:test";

const backendRoot = join(import.meta.dirname, "../..");
const generatedRoot = join(backendRoot, "../.generated/hypit");
const token = "fix2-c32-maintenance-token-0123456789abcdef";  // secret-scan: allow

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

function enter(port: number, body: Record<string, unknown>) {
  return fetch(`http://127.0.0.1:${port}/internal/v1/maintenance/enter`, {
    method: "POST", headers: auth, body: JSON.stringify(body),
  });
}

interface EnterBody {
  leaseId: string;
  drained: boolean;
  activeCommands: number;
  activeBuilds: number;
  activeWrites: number;
  waitedMs: number;
  inflight: { commandId: string; kind: string; state: string }[];
}

test("maintenance fence drains real in-flight work and never lies about drained", { timeout: 180_000 }, async (t) => {
  const dataRoot = mkdtempSync(join(tmpdir(), "fix2-c32-"));
  const port = 9262;
  // 预置真实在途证据：直接写 broker 的 bridge.sqlite（与服务端同库）——
  // 一条 dispatching（带 engineBuildId=原生 active build 证据）、一条 acknowledged。
  const storeFile = join(dataRoot, "host", "bridge.sqlite");
  const seeder = new CommandStore(storeFile);
  seeder.accept("c32-dispatching", "build.submit", CommandStore.hashPayload("build.submit", { p: 1 }), "proj-a");
  seeder.transition("c32-dispatching", () => ({ state: "dispatching", engineBuildId: "ord-C32BUILD01" }));
  seeder.accept("c32-acknowledged", "workspace.apply", CommandStore.hashPayload("workspace.apply", { p: 2 }), "proj-a");
  seeder.transition("c32-acknowledged", () => ({ state: "acknowledged" }));
  seeder.close();

  const child = spawnBroker(dataRoot, port);
  t.after(() => {
    child.kill("SIGTERM");
    rmSync(dataRoot, { recursive: true, force: true });
  });
  await waitHealthy(port);

  await t.test("TC-F2-32-01 enter waits for actual completion (not instant drained)", async () => {
    // 在途未清零前 enter 阻塞等待；500ms 后从测试侧把在途收敛到终态，
    // enter 应观察到真实完成（waitedMs>=400 且 drained=true，activeCommands=0）。
    const enterPromise = enter(port, { reason: "it-drain" }).then((r) => r.json() as Promise<EnterBody>);
    await delay(500);
    const finisher = new CommandStore(storeFile);
    finisher.transition("c32-dispatching", () => ({ state: "succeeded" }));
    finisher.transition("c32-acknowledged", () => ({ state: "failed", errorCode: "it", errorMessage: "it" }));
    finisher.close();
    const body = await enterPromise;
    assert.equal(body.drained, true, `expected drained after real completion: ${JSON.stringify(body)}`);
    assert.ok(body.waitedMs >= 400, `must have waited for in-flight work, waitedMs=${body.waitedMs}`);
    assert.equal(body.activeCommands, 0);
    assert.deepEqual(body.inflight, []);
    (globalThis as { __c32Lease?: string }).__c32Lease = body.leaseId;
  });

  await t.test("TC-F2-32-02 fence refuses new side effects; cancel/status exempt", async () => {
    const refused = await fetch(`http://127.0.0.1:${port}/internal/v1/commands`, {
      method: "POST", headers: auth, body: JSON.stringify({ commandId: "c32-new", kind: "plan", payload: {} }),
    });
    assert.equal(refused.status, 503, "new side-effecting command must be fenced");

    const exempt = await fetch(`http://127.0.0.1:${port}/internal/v1/commands`, {
      method: "POST", headers: auth, body: JSON.stringify({ commandId: "c32-cancel", kind: "build.cancel", payload: { projectId: "proj-a", engineBuildId: "ord-X" } }),
    });
    assert.ok(exempt.status < 500, `cancel must route through the fence (got ${exempt.status})`);

    const statusKind = await fetch(`http://127.0.0.1:${port}/internal/v1/commands`, {
      method: "POST", headers: auth, body: JSON.stringify({ commandId: "c32-status", kind: "status", payload: {} }),
    });
    assert.equal(statusKind.status, 200, "status convergence must stay usable during drain");

    const ingest = await fetch(`http://127.0.0.1:${port}/internal/v1/resources`, {
      method: "POST", headers: auth, body: "x",
    });
    assert.equal(ingest.status, 503, "resource ingest must be fenced");

    const transfer = await fetch(`http://127.0.0.1:${port}/internal/v1/package-transfers/t-c32/content`, {
      method: "PUT", headers: { ...auth, "content-type": "application/octet-stream" }, body: "x",
    });
    assert.equal(transfer.status, 503, "transfer upload must be fenced");

    const health = await fetch(`http://127.0.0.1:${port}/healthz`);
    assert.ok(health.ok, "read path stays available");
  });

  await t.test("TC-F2-32-03 exit honors own leaseId only; restart stays fail-closed", async () => {
    const wrongExit = await fetch(`http://127.0.0.1:${port}/internal/v1/maintenance/exit`, {
      method: "POST", headers: auth, body: JSON.stringify({ leaseId: "someone-else" }),
    });
    assert.equal(wrongExit.status, 409, "foreign leaseId must be 409");
    const still = await fetch(`http://127.0.0.1:${port}/internal/v1/commands`, {
      method: "POST", headers: auth, body: JSON.stringify({ commandId: "c32-still", kind: "plan", payload: {} }),
    });
    assert.equal(still.status, 503, "window stays closed after rejected exit");

    const exit = await fetch(`http://127.0.0.1:${port}/internal/v1/maintenance/exit`, {
      method: "POST", headers: auth, body: JSON.stringify({ leaseId: (globalThis as { __c32Lease?: string }).__c32Lease }),
    });
    assert.equal(exit.status, 200, "holder exit must be 200");
    const after = await fetch(`http://127.0.0.1:${port}/internal/v1/commands`, {
      method: "POST", headers: auth, body: JSON.stringify({ commandId: "c32-after", kind: "plan", payload: {} }),
    });
    assert.equal(after.status, 200, "side effects accepted again after holder exit");
  });
});

test("maintenance lease survives restart fail-closed; foreign enter is 409 without leak", { timeout: 180_000 }, async (t) => {
  const dataRoot = mkdtempSync(join(tmpdir(), "fix2-c32-restart-"));
  const port = 9263;
  const storeFile = join(dataRoot, "host", "bridge.sqlite");
  const child = spawnBroker(dataRoot, port);
  t.after(() => {
    child.kill("SIGTERM");
    rmSync(dataRoot, { recursive: true, force: true });
  });
  await waitHealthy(port);

  // A 取得租约；重启（模拟崩溃）后窗口必须保持 fail-closed。
  const enterA = await enter(port, { reason: "session-A" });
  assert.equal(enterA.status, 200);
  const leaseA = (await enterA.json() as EnterBody).leaseId;

  // TC-F2-32-04：他人（B）enter=409，不得当已取得租约，不泄漏 A 的 leaseId。
  const enterB = await enter(port, { reason: "backup-B" });
  assert.equal(enterB.status, 409, "second operator must be refused");
  const enterBBody = await enterB.json() as { error?: string };
  assert.ok(!(enterBBody.error ?? "").includes(leaseA), "holder leaseId must not leak");

  child.kill("SIGTERM");
  await delay(500);
  const child2 = spawnBroker(dataRoot, port);
  t.after(() => child2.kill("SIGTERM"));
  await waitHealthy(port);

  const fenced = await fetch(`http://127.0.0.1:${port}/internal/v1/commands`, {
    method: "POST", headers: auth, body: JSON.stringify({ commandId: "c32-restart", kind: "plan", payload: {} }),
  });
  assert.equal(fenced.status, 503, "restart inside a window must stay fail-closed (persistent lease)");

  // 持有者 A 用原 leaseId 显式退出后恢复。
  const exit = await fetch(`http://127.0.0.1:${port}/internal/v1/maintenance/exit`, {
    method: "POST", headers: auth, body: JSON.stringify({ leaseId: leaseA }),
  });
  assert.equal(exit.status, 200, "original holder must be able to exit after restart");
  const open = await fetch(`http://127.0.0.1:${port}/internal/v1/commands`, {
    method: "POST", headers: auth, body: JSON.stringify({ commandId: "c32-open", kind: "plan", payload: {} }),
  });
  assert.equal(open.status, 200, "side effects accepted after persistent-lease exit");
});
