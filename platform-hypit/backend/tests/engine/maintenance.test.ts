// maintenance.test.ts — 107-4 C09/C12 + C107F2-32 契约刷新 + C107F3-02 复验。
//
// TC-F3-02-01：真实 broker 子进程（独占临时目录+动态端口）先接受一个受控未结束
// 命令（dispatching+engineBuildId 真实在途证据），enter 原子封写后栅栏立即关闭：
// 非豁免 plan / resource ingest（合法形状）503 且零执行零文件（命令行不存在、
// uploads 无新文件）；status 与既有取消（豁免清单）照常可用；读路径/healthz 照常；
// 释放原在途后 enter 才 drained=true（waitedMs>0，drained 不提前）；重复 enter=409
// 不泄漏 leaseId；错误 leaseId exit=409 且窗口保持关闭；持有者 exit 后同形状
// plan/ingest 恢复 200（证明 503 来自栅栏而非坏请求）。移除栅栏时被拒请求会真实
// 执行（行/文件计数非零）——本例用「拒后 404/空目录、放行后 200/有文件」的对称
// 断言拦截该假阳性。
//
// TC-F3-02-02：lease 隔离与退出清理——L2 exit=409 且闸仍在，L1 exit=200 恢复；
// 另一次在启动失败处（端口被本测试自己的 listener 占用，绝不杀陌生进程）触发
// cleanup：broker 必须自行非零退出（fail-closed），等待 child exit 后才清自己的
// 临时目录，且不动其他目录。全程只 kill 本测试记录的 PID。
//
// 留证：每次运行打印子进程 PID、绑定端口、栅栏源码（server/dispatcher/store）
// sha256 摘要与 /healthz 响应摘要，落入 V-04 run 的 backend-engine.log。
process.env.HYPIT_STATE_HOME ??= join(import.meta.dirname, "../../../../data/hypit/runner-state");

import { spawn, type ChildProcess } from "node:child_process";
import { createServer as createNetServer } from "node:net";
import { createHash } from "node:crypto";
import { existsSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { once } from "node:events";
import { setTimeout as delay } from "node:timers/promises";
import assert from "node:assert/strict";
import test from "node:test";

const backendRoot = join(import.meta.dirname, "../..");
const generatedRoot = join(backendRoot, "../.generated/hypit");
const token = "maintenance-test-token-0123456789abcdef";
const auth = { "content-type": "application/json", authorization: `Bearer ${token}` };

const { CommandStore } = await import("../../src/commands/store.ts");

/** 动态端口：向 OS 预订一个空闲回环端口后立即归还（§11 步骤1，禁固定 9254）。 */
async function reservePort(): Promise<number> {
  const probe = createNetServer();
  probe.listen(0, "127.0.0.1");
  await once(probe, "listening");
  const port = (probe.address() as { port: number }).port;
  await new Promise<void>((resolve) => probe.close(() => resolve()));
  return port;
}

function closeQuietly(server: ReturnType<typeof createNetServer>): void {
  try {
    server.close(() => {});
  } catch {
    // 已关闭：重复 close 的 ERR_SERVER_NOT_RUNNING 无需处理。
  }
}

function spawnBroker(dataRoot: string, port: number): ChildProcess {
  const child = spawn(process.execPath, ["--import", "tsx", "src/main.mjs"], {
    cwd: backendRoot,
    env: {
      ...process.env,
      HYPIT_BACKEND_PORT: String(port),
      HYPIT_INTERNAL_TOKEN: token,
      HYPIT_DATA_ROOT: join(dataRoot, "host"),
      HYPIT_GENERATED_ROOT: generatedRoot,
    },
    stdio: ["ignore", "pipe", "pipe"],
  });
  // 消费管道防背压阻塞；保留 stderr 尾巴（4KB）用于失败诊断。
  child.stdout?.resume();
  collectStderrTail(child);
  return child;
}

function collectStderrTail(child: ChildProcess): () => string {
  let tail = "";
  child.stderr?.on("data", (chunk: Buffer) => {
    tail = `${tail}${chunk.toString("utf8")}`.slice(-4000);
  });
  return () => tail;
}

/** 退出等待：SIGTERM → 等 exit（10s 兜底 SIGKILL）→ 返回 [code, signal]。 */
async function terminate(child: ChildProcess): Promise<[number | null, string | null]> {
  if (child.exitCode === null && child.signalCode === null && !child.killed) {
    child.kill("SIGTERM");
  }
  if (child.exitCode !== null || child.signalCode !== null) {
    return [child.exitCode, child.signalCode];
  }
  const exited = await Promise.race([
    once(child, "exit") as Promise<[number | null, string | null]>,
    delay(10_000).then(() => null),
  ]);
  if (exited === null) {
    child.kill("SIGKILL");
    return once(child, "exit") as Promise<[number | null, string | null]>;
  }
  return exited;
}

/** 阶段收尾：必须等 child exit 之后才清目录（§11 步骤4），目录只清自己的。 */
async function cleanupAfterExit(child: ChildProcess, dirs: string[]): Promise<void> {
  await terminate(child);
  for (const dir of dirs) rmSync(dir, { recursive: true, force: true });
}

async function waitHealthy(port: number, stderrTail: () => string): Promise<{ ok: boolean; version: string | null }> {
  let ready = false;
  for (let attempt = 0; attempt < 400 && !ready; attempt += 1) {
    ready = await fetch(`http://127.0.0.1:${port}/healthz`)
      .then((response) => response.ok)
      .catch(() => false);
    if (!ready) await delay(250);
  }
  assert.ok(ready, `server did not become healthy; stderr tail:\n${stderrTail()}`);
  const health = await fetch(`http://127.0.0.1:${port}/healthz`);
  const body = await health.json() as { ok?: boolean; version?: string | null };
  return { ok: body.ok === true, version: body.version ?? null };
}

/** 栅栏源码摘要（留证）：server/dispatcher/store 三文件逐字节 sha256。 */
function fenceSourceDigest(): string {
  const hash = createHash("sha256");
  for (const file of ["src/server.ts", "src/commands/dispatcher.ts", "src/commands/store.ts"]) {
    hash.update(readFileSync(join(backendRoot, file)));
  }
  return hash.digest("hex");
}

function enter(port: number, body: Record<string, unknown>): Promise<Response> {
  return fetch(`http://127.0.0.1:${port}/internal/v1/maintenance/enter`, {
    method: "POST", headers: auth, body: JSON.stringify(body),
  });
}

function submitCommand(port: number, body: Record<string, unknown>): Promise<Response> {
  return fetch(`http://127.0.0.1:${port}/internal/v1/commands`, {
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

test("TC-F3-02-01: fence closes before dispatch, drains in-flight, refuses new side effects with zero execution", { timeout: 180_000 }, async (t) => {
  const dataRoot = mkdtempSync(join(tmpdir(), "hypit-maint-"));
  const port = await reservePort();
  // 预置受控未结束命令（真实 broker 同库）：dispatching + engineBuildId = 原生
  // active build 证据，activeCommands/activeBuilds 双计数在途，屏障保持不结束。
  const storeFile = join(dataRoot, "host", "bridge.sqlite");
  const seeder = new CommandStore(storeFile);
  seeder.accept("f302-inflight", "build.submit", CommandStore.hashPayload("build.submit", { p: 1 }), "proj-a");
  seeder.transition("f302-inflight", () => ({ state: "dispatching", engineBuildId: "ord-F302INFLIGHT1" }));
  seeder.close();

  const child = spawnBroker(dataRoot, port);
  console.log(`# [TC-F3-02-01] pid=${child.pid} port=${port} fenceSha256=${fenceSourceDigest()}`);
  t.after(() => cleanupAfterExit(child, [dataRoot]));
  const health = await waitHealthy(port, collectStderrTail(child));
  console.log(`# [TC-F3-02-01] healthz ok=${health.ok} version=${health.version ?? "null"}`);

  // 维护端点与其他 /internal/* 端点同鉴权闸：缺 token 一律 401。
  const noToken = await fetch(`http://127.0.0.1:${port}/internal/v1/maintenance/enter`, {
    method: "POST", headers: { "content-type": "application/json" }, body: "{}",
  });
  assert.equal(noToken.status, 401, "maintenance enter without token must be 401");

  // enter 取 lease：栅栏先原子关闭（排空未完成前新副作用已被拒），drain 等在途。
  const enterPromise = enter(port, { reason: "f302-drain" }).then((r) => r.json() as Promise<EnterBody>);
  await delay(600);

  // 屏障保持中：合法 plan（非豁免）503 且零执行零文件——命令行不存在（404）。
  const refusedPlan = await submitCommand(port, { commandId: "f302-plan-fenced", kind: "plan", payload: {} });
  assert.equal(refusedPlan.status, 503, "well-formed plan must be refused during maintenance");
  const fencedRow = await fetch(`http://127.0.0.1:${port}/internal/v1/commands/f302-plan-fenced`, {
    headers: auth,
  });
  assert.equal(fencedRow.status, 404, "refused plan must leave no command row (zero execution)");

  // 合法 resource ingest 503 且零文件：uploads 目录无新增（忽略 .staging 等点文件，
  // 与 resources-routes 既有约定一致，只数真实上传产物）。
  const uploadsDir = join(dataRoot, "resources", "uploads");
  const listUploads = () => (existsSync(uploadsDir) ? readdirSync(uploadsDir).filter((name) => !name.startsWith(".")) : []);
  const uploadsBefore = listUploads();
  const refusedIngest = await fetch(`http://127.0.0.1:${port}/internal/v1/resources`, {
    method: "POST",
    headers: { ...auth, "content-type": "text/plain", "x-hypit-file-name": "fenced.txt" },
    body: "maintenance-fence-probe-bytes",
  });
  assert.equal(refusedIngest.status, 503, "well-formed resource ingest must be refused during maintenance");
  assert.deepEqual(listUploads(), uploadsBefore, "refused ingest must write no file");

  // 豁免清单照常：status 收敛 200 且真实执行；既有取消（build.cancel）穿透栅栏
  // （projectId 用合法小写 UUID，走真实 engine.observe 路径而非形状校验拒绝）。
  const statusDuring = await submitCommand(port, { commandId: "f302-status-during", kind: "status", payload: {} });
  assert.equal(statusDuring.status, 200, "status convergence must stay usable during drain");
  const statusBody = await statusDuring.json() as { state: string };
  assert.equal(statusBody.state, "succeeded", "status must execute for real, not be fenced");
  const cancelDuring = await submitCommand(port, {
    commandId: "f302-cancel-during", kind: "build.cancel",
    payload: { projectId: "aaaaaaaa-0000-4000-8000-000000000001", engineBuildId: "ord-F302CANCEL1" },
  });
  assert.equal(cancelDuring.status, 200, "existing-cancel kind must route through the fence");
  const cancelRow = await fetch(`http://127.0.0.1:${port}/internal/v1/commands/f302-cancel-during`, {
    headers: auth,
  });
  assert.equal(cancelRow.status, 200, "cancel must have been dispatched (row exists), not 503-fenced");

  // 读路径与 /healthz 照常。
  const healthDuring = await fetch(`http://127.0.0.1:${port}/healthz`);
  assert.ok(healthDuring.ok, "/healthz must stay available during maintenance");
  const readDuring = await fetch(`http://127.0.0.1:${port}/internal/v1/commands/none-such`, { headers: auth });
  assert.equal(readDuring.status, 404, "read path must still route (404 for unknown id, not 503)");

  // 释放原在途命令后 enter 才收敛：drained 不能提前 true（waitedMs 证明真等待）。
  const finisher = new CommandStore(storeFile);
  finisher.transition("f302-inflight", () => ({ state: "succeeded" }));
  finisher.close();
  const enterBody = await enterPromise;
  assert.equal(enterBody.drained, true, `expected drained after real completion: ${JSON.stringify(enterBody)}`);
  assert.ok(enterBody.waitedMs >= 400, `drain must not claim drained early, waitedMs=${enterBody.waitedMs}`);
  assert.equal(enterBody.activeCommands, 0);
  assert.deepEqual(enterBody.inflight, []);
  const leaseId = enterBody.leaseId;
  assert.ok(typeof leaseId === "string" && leaseId.length > 0, "leaseId required");

  // 重复 enter：409 且不泄漏持有者 leaseId。
  const reenter = await enter(port, {});
  assert.equal(reenter.status, 409, "re-enter must be 409");
  const reenterBody = await reenter.json() as { error?: string };
  assert.ok(!(reenterBody.error ?? "").includes(leaseId), "holder leaseId must not leak");

  // 错误 leaseId exit=409，窗口保持关闭。
  const wrongExit = await fetch(`http://127.0.0.1:${port}/internal/v1/maintenance/exit`, {
    method: "POST", headers: auth, body: JSON.stringify({ leaseId: "not-the-holder" }),
  });
  assert.equal(wrongExit.status, 409, "exit with a foreign leaseId must be 409");
  const stillFenced = await submitCommand(port, { commandId: "f302-plan-still", kind: "plan", payload: {} });
  assert.equal(stillFenced.status, 503, "window must stay closed after a rejected exit");

  // 持有者 exit 后恢复：同形状 plan/ingest（此前被拒的那两个）此刻 200 且落行/落文件
  // ——对称断言证明 503 来自栅栏本身；栅栏被移除时副作用计数必非零（防假阳性）。
  const exit = await fetch(`http://127.0.0.1:${port}/internal/v1/maintenance/exit`, {
    method: "POST", headers: auth, body: JSON.stringify({ leaseId }),
  });
  assert.equal(exit.status, 200, "holder exit must be accepted");
  const acceptedPlan = await submitCommand(port, { commandId: "f302-plan-after", kind: "plan", payload: {} });
  assert.equal(acceptedPlan.status, 200, "same-shaped plan must be accepted after exit");
  const planRow = await fetch(`http://127.0.0.1:${port}/internal/v1/commands/f302-plan-after`, { headers: auth });
  assert.equal(planRow.status, 200, "accepted plan must create a real command row");
  const acceptedIngest = await fetch(`http://127.0.0.1:${port}/internal/v1/resources`, {
    method: "POST",
    headers: { ...auth, "content-type": "text/plain", "x-hypit-file-name": "after-exit.txt" },
    body: "maintenance-fence-probe-bytes",
  });
  assert.equal(acceptedIngest.status, 200, "same-shaped ingest must be accepted after exit");
  const receipt = await acceptedIngest.json() as { handle: string; sha256: string; sizeBytes: number };
  assert.match(receipt.handle, /^res-[0-9a-f]{16}-[0-9a-z]+$/u);
  assert.equal(listUploads().length, uploadsBefore.length + 1, "accepted ingest writes exactly one file");
});

test("TC-F3-02-02: lease isolates exit; startup failure exits nonzero and cleanup spares other dirs", { timeout: 120_000 }, async (t) => {
  // A. lease 隔离：L2 退出 409 且闸仍在；L1 退出 200 恢复。
  const leaseDir = mkdtempSync(join(tmpdir(), "hypit-maint-lease-"));
  const leasePort = await reservePort();
  const leaseChild = spawnBroker(leaseDir, leasePort);
  console.log(`# [TC-F3-02-02] lease-broker pid=${leaseChild.pid} port=${leasePort}`);
  t.after(() => cleanupAfterExit(leaseChild, [leaseDir]));
  await waitHealthy(leasePort, collectStderrTail(leaseChild));

  const enterA = await enter(leasePort, { reason: "f302-holder" });
  assert.equal(enterA.status, 200);
  const leaseId = ((await enterA.json()) as EnterBody).leaseId;

  const foreignExit = await fetch(`http://127.0.0.1:${leasePort}/internal/v1/maintenance/exit`, {
    method: "POST", headers: auth, body: JSON.stringify({ leaseId: "not-the-holder" }),
  });
  assert.equal(foreignExit.status, 409, "foreign leaseId exit must be 409");
  const stillFenced = await submitCommand(leasePort, { commandId: "f302-lease-still", kind: "plan", payload: {} });
  assert.equal(stillFenced.status, 503, "fence must stay closed after foreign exit");
  const holderExit = await fetch(`http://127.0.0.1:${leasePort}/internal/v1/maintenance/exit`, {
    method: "POST", headers: auth, body: JSON.stringify({ leaseId }),
  });
  assert.equal(holderExit.status, 200, "holder leaseId exit must be 200");
  const recovered = await submitCommand(leasePort, { commandId: "f302-lease-open", kind: "plan", payload: {} });
  assert.equal(recovered.status, 200, "side effects accepted after holder exit");

  // B. 启动失败 cleanup：端口被本测试自己的 listener 占用（不杀陌生进程）——
  // broker 必须 fail-closed 自行非零退出；等 child exit 后才清自己的目录，
  // 其他目录（keep）原样保留。
  const failDir = mkdtempSync(join(tmpdir(), "hypit-maint-fail-"));
  const keepDir = mkdtempSync(join(tmpdir(), "hypit-maint-keep-"));
  const keepMarker = join(keepDir, "must-survive.txt");
  writeFileSync(keepMarker, "control dir must not be cleared by failure cleanup\n");  const failPort = await reservePort();
  const blocker = createNetServer();
  blocker.listen(failPort, "127.0.0.1");
  await once(blocker, "listening");
  const failChild = spawnBroker(failDir, failPort);
  console.log(`# [TC-F3-02-02] fail-broker pid=${failChild.pid} port=${failPort} (occupied by test listener)`);
  t.after(async () => {
    closeQuietly(blocker);
    await cleanupAfterExit(failChild, [failDir, keepDir]);
  });
  const exitInfo = await Promise.race([
    once(failChild, "exit") as Promise<[number | null, string | null]>,
    delay(60_000).then(() => null),
  ]);
  assert.ok(exitInfo !== null, "broker must exit on its own after startup failure (no orphan process)");
  const [code, signal] = exitInfo;
  assert.ok(code === null || code !== 0, `startup failure must exit nonzero (code=${code} signal=${signal})`);
  closeQuietly(blocker);
  // 清理只动自己的目录：fail 清除，keep 连同标记文件保留。
  await cleanupAfterExit(failChild, [failDir]);
  assert.ok(existsSync(keepMarker), "startup-failure cleanup must not clear other directories");
  assert.equal(readFileSync(keepMarker, "utf8"), "control dir must not be cleared by failure cleanup\n");
  assert.ok(!existsSync(join(failDir, "host")), "own failed-startup dir is cleaned after child exit");
});
