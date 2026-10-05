// fix2-c32.test.ts — 107-fix-2 C107F2-32：维护模式写入栅栏与在途执行排空
// （F33/§5 RULE-13、§6.15）。107-fix-3 C107F3-04：原内联的「他人 enter 409 且
// 不泄漏 leaseId」抽成独立具名 TC-F2-32-04 子测试（自起 broker、动态端口），
// 挂在 TC-F3-04-01（C32 第四项真实 lease 竞争）顶级测试下；原 TC-F2-32-03
// 重启 fail-closed 断言全部保留（一字未减）。
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
// TC-F2-32-04 / TC-F3-04-01 E04 真实 broker 中 A 持 L1，B 发相同形状 maintenance
//            enter：B=409、响应（错误文本与整个 body）不泄漏 L1、B 调用后窗口
//            仍由 A 持有（新副作用仍 503），随后 A 仍可显式退出并重新开放；
//            backup.sh 语义：非持有者退出非零（shell 层断言在 deploy 侧冒烟，
//            此处锁端点契约）。
process.env.HYPIT_STATE_HOME ??= join(import.meta.dirname, "../../../../data/hypit/runner-state");

import { spawn, type ChildProcess } from "node:child_process";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createServer as createNetServer, type AddressInfo } from "node:net";
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

// 动态端口（对齐 maintenance.test.ts 的测试卫生）：先保留空口再让给 broker，
// 避免固定端口与同组其他测试/遗留进程相撞。
async function reservePort(): Promise<number> {
  const probe = createNetServer();
  await new Promise<void>((resolve) => probe.listen(0, "127.0.0.1", resolve));
  const port = (probe.address() as AddressInfo).port;
  await new Promise<void>((resolve) => probe.close(() => resolve()));
  return port;
}

// 测试卫生：等子进程真正 exit（10s 后 SIGKILL 兜底）再删独占临时目录，
// 失败路径也不遗留进程/目录。
async function shutdownBroker(child: ChildProcess, dataRoot: string): Promise<void> {
  if (child.exitCode === null && child.signalCode === null) {
    const exited = new Promise<void>((resolve) => child.once("exit", () => resolve()));
    child.kill("SIGTERM");
    const killer = setTimeout(() => child.kill("SIGKILL"), 10_000);
    await exited;
    clearTimeout(killer);
  }
  rmSync(dataRoot, { recursive: true, force: true });
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

test("TC-F3-04-01 C32 fourth item: real lease competition between two maintenance operators", { timeout: 180_000 }, async (t) => {
  const dataRoot = mkdtempSync(join(tmpdir(), "fix2-c32-tc04-"));
  const port = await reservePort();
  const child = spawnBroker(dataRoot, port);
  t.after(() => shutdownBroker(child, dataRoot));
  await waitHealthy(port);

  // 前提：A 取得租约 L1（真实 broker 单槽维护租约）。
  const enterA = await enter(port, { reason: "session-A" });
  assert.equal(enterA.status, 200, "first operator must acquire the lease");
  const leaseA = (await enterA.json() as EnterBody).leaseId;
  assert.ok(leaseA, "holder leaseId must be issued");

  await t.test("TC-F2-32-04 foreign maintenance enter is 409 without leaseId leak", async () => {
    // B 发相同形状的 maintenance enter：单槽租约必须 409，不得当已取得租约，
    // 且响应（错误文本与整个 body）都不得泄漏 A 的 leaseId。
    const enterB = await enter(port, { reason: "backup-B" });
    assert.equal(enterB.status, 409, "second operator must be refused");
    const enterBBody = await enterB.json() as { error?: string };
    assert.ok(!(enterBBody.error ?? "").includes(leaseA), "holder leaseId must not leak in error text");
    assert.ok(!JSON.stringify(enterBBody).includes(leaseA), "holder leaseId must not leak anywhere in the response body");
    // B 调用后查维护状态：窗口仍由 A 持有——B 的失败尝试不得破坏租约，
    // 新副作用仍然被栅栏拒绝（非豁免 plan=503）。
    const probe = await fetch(`http://127.0.0.1:${port}/internal/v1/commands`, {
      method: "POST", headers: auth, body: JSON.stringify({ commandId: "c32-tc04-probe", kind: "plan", payload: {} }),
    });
    assert.equal(probe.status, 503, "window must stay closed after refused foreign enter");
  });

  // 随后 A 退出：持有者仍可显式退出，窗口重新开放。
  const exit = await fetch(`http://127.0.0.1:${port}/internal/v1/maintenance/exit`, {
    method: "POST", headers: auth, body: JSON.stringify({ leaseId: leaseA }),
  });
  assert.equal(exit.status, 200, "original holder must still be able to exit");
  const open = await fetch(`http://127.0.0.1:${port}/internal/v1/commands`, {
    method: "POST", headers: auth, body: JSON.stringify({ commandId: "c32-tc04-open", kind: "plan", payload: {} }),
  });
  assert.equal(open.status, 200, "side effects accepted again after holder exit");
});

test("TC-F2-32-03 maintenance lease survives restart fail-closed (persistent lease)", { timeout: 180_000 }, async (t) => {
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
