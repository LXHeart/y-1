// daemon.mjs — 107-fix-2 C107F2-04（W033）：runner 容器内常驻可信 daemon。
//
// D-04：broker 不再在本容器 spawn 作者代码——daemon 持有唯一执行面：
//   - 常驻 Unix socket（默认 /sockets/runner.sock，与 broker 共享卷）；
//   - 协议 v1 外壳（protocol.ts framing）+ status 握手（protocolVersion/
//     engineDigest/capacity=1，不含敏感路径）；
//   - 每命令一次性子进程（src/runner/server.ts，Node permission 模型限定
//     只读 input/发行版/自身源码，只写本 slot output/scratch）；
//   - slotId/relativeInputRoot 由 broker 生成，daemon 验证 containment
//     （合法 slotId、相对 input 根、payload 不得引用兄弟 slot）；
//   - 容量 1：忙时拒绝 runner_busy；断线回收子进程；SIGTERM 10s 宽限。
//
// 由 Dockerfile.runner 以 `node --import tsx src/runner/daemon.mjs …` 启动；
// 本文件保持 .mjs（无类型语法），通过 tsx 复用 protocol/client 的 TS 模块。
import { spawn } from "node:child_process";
import { createServer } from "node:net";
import { mkdir, readFile, rm } from "node:fs/promises";
import { existsSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { createHash } from "node:crypto";

import { FrameDecoder, encodeFrame, RUNNER_PROTOCOL_VERSION } from "./protocol.ts";
import { RunnerClient, connectWithRetry } from "./client.ts";

// ── argv ─────────────────────────────────────────────────────────────────
function arg(name, fallback = undefined) {
  const index = process.argv.indexOf(`--${name}`);
  if (index === -1 || index + 1 >= process.argv.length) return fallback;
  return process.argv[index + 1];
}

const socketPath = arg("socket", "/sockets/runner.sock");
const distributionRoot = resolve(arg("distribution-root", "/app/platform-hypit/.generated/hypit"));
const slotRoot = resolve(arg("slot-root", "/slots"));
const stateHome = resolve(arg("state-home", "/runner-state"));
const tmpRoot = resolve(arg("tmp", "/tmp"));
const backendRoot = resolve(import.meta.dirname, "../..");
const frameLimitBytes = Number(arg("frame-limit", String(1024 * 1024)));
const childTimeoutMs = Number(arg("child-timeout-ms", "120000"));
const killGraceMs = Number(arg("kill-grace-ms", "10000"));

const EXECUTION_KINDS = new Set(["check", "plan", "compile", "executeLocal", "capture"]);

// engineDigest：发行版构建指纹（patches/manifest.json 的 engine digest；缺失
// 时退化为 manifest 文件 sha256 前 16 位——只作指纹，不暴露路径）。
async function engineDigest() {
  const candidates = [
    join(distributionRoot, "patches.manifest.json"),
    join(backendRoot, "../patches/manifest.json"),
  ];
  for (const path of candidates) {
    if (!existsSync(path)) continue;
    try {
      const parsed = JSON.parse(await readFile(path, "utf8"));
      const digest = parsed?.engineDigest ?? parsed?.digest;
      if (typeof digest === "string" && digest.length >= 8) return digest.slice(0, 32);
      return createHash("sha256").update(await readFile(path)).digest("hex").slice(0, 32);
    } catch { /* try next */ }
  }
  return "unknown";
}

// ── slot containment（§6.8：slotId/relativeInputRoot 只由 broker 生成） ────
function assertSlotId(slotId) {
  if (typeof slotId !== "string" || !/^[a-z0-9][a-z0-9-]{0,127}$/.test(slotId)) {
    throw Object.assign(new Error("invalid slotId"), { code: "invalid_slot" });
  }
}
function assertRelativeInputRoot(value) {
  if (typeof value !== "string" || value.length === 0 || value.startsWith("/") || value.includes("..") || value.includes("\\")) {
    throw Object.assign(new Error("invalid relativeInputRoot"), { code: "invalid_slot" });
  }
}
/** payload 序列化文本不得引用兄弟 slot（路径串绕过防护）。 */
function assertNoSiblingSlotReference(slotId, command) {
  const text = JSON.stringify(command ?? {});
  const marker = `${slotRoot}/`;
  let index = text.indexOf(marker);
  while (index !== -1) {
    const rest = text.slice(index + marker.length);
    const sibling = /^[a-z0-9][a-z0-9-]*/.exec(rest)?.[0] ?? "";
    if (sibling.length > 0 && sibling !== slotId) {
      throw Object.assign(new Error("payload references another slot"), { code: "slot_escape" });
    }
    index = text.indexOf(marker, index + marker.length);
  }
}

// ── 一次性子进程（沿用原 supervisor 的 permission 收敛面） ────────────────
function flipCase(value) {
  let out = "";
  for (const character of value) {
    const code = character.charCodeAt(0);
    if (code >= 97 && code <= 122) out += character.toUpperCase();
    else if (code >= 65 && code <= 90) out += character.toLowerCase();
    else out += character;
  }
  return out;
}

let authorChildSpawnCount = 0;
let busy = null; // { commandId, child, client }

async function spawnOneShot(slotId, commandId, kind, command) {
  const slotDir = join(slotRoot, slotId);
  const childSocket = join(slotDir, "scratch", "cmd.sock");
  const child = spawn(process.execPath, [
    "--import", "./src/runner/guard.mjs",
    "--import", "tsx",
    "--permission",
    "--allow-worker",
    "--allow-child-process",
    "--allow-addons",
    `--allow-fs-read=${distributionRoot}`,
    `--allow-fs-read=${backendRoot}`,
    `--allow-fs-read=${flipCase(backendRoot)}`,
    `--allow-fs-read=${stateHome}`,
    `--allow-fs-read=${tmpRoot}`,
    `--allow-fs-write=${tmpRoot}`,
    `--allow-fs-read=${slotDir}`,
    `--allow-fs-write=${join(slotDir, "output")}`,
    `--allow-fs-write=${join(slotDir, "scratch")}`,
    join(backendRoot, "src/runner/server.ts"),
    "--socket", childSocket,
    "--distribution-root", distributionRoot,
    "--slot-root", slotDir,
    "--slot-output", join(slotDir, "output"),
    "--frame-limit", String(frameLimitBytes),
  ], {
    stdio: ["ignore", "pipe", "pipe"],
    cwd: backendRoot,
    env: {
      PATH: process.env.PATH ?? "/usr/bin:/bin",
      TMPDIR: tmpRoot,
      HYPIT_STATE_HOME: stateHome,
      ...(process.env.HYPIT_RUNNER_DEBUG === undefined ? {} : { HYPIT_RUNNER_DEBUG: process.env.HYPIT_RUNNER_DEBUG }),
    },
  });
  authorChildSpawnCount += 1;
  return { child, childSocket };
}

async function runOneShot(busyEntry, slotId, commandId, kind, command) {
  const { child, childSocket } = await spawnOneShot(slotId, commandId, kind, command);
  busyEntry.child = child;
  let stderr = "";
  child.stdout.on("data", (chunk) => { stderr = (stderr + chunk.toString("utf8")).slice(-8000); });
  child.stderr.on("data", (chunk) => { stderr = (stderr + chunk.toString("utf8")).slice(-8000); });
  const client = new RunnerClient({
    socketPath: childSocket,
    frameLimitBytes,
    requestTimeoutMs: childTimeoutMs,
  });
  const childExit = new Promise((resolveExit) => child.once("exit", () => resolveExit()));
  try {
    await connectWithRetry(client, 50, 100);
    const result = await client.request(commandId, kind, command);
    const marker = { __runner: { container: "runner", pid: child.pid, slotId } };
    const wrapped = result !== null && typeof result === "object" && !Array.isArray(result)
      ? { ...result, ...marker }
      : { result, ...marker };
    return { ok: true, result: wrapped };
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    return { ok: false, error: { code: "runner_execution_failed", message: stderr.length > 0 ? `${message}\nrunner stderr tail:\n${stderr.split("\n").slice(-20).join("\n")}` : message } };
  } finally {
    client.close();
    // 10s SIGTERM 宽限后受控终止（§9.4 runner 阈值）。
    if (child.exitCode === null && child.signalCode === null) {
      child.kill("SIGTERM");
      const timer = new Promise((resolveTimer) => setTimeout(resolveTimer, killGraceMs));
      await Promise.race([childExit, timer]);
      if (child.exitCode === null && child.signalCode === null) child.kill("SIGKILL");
      await childExit;
    }
    if (existsSync(childSocket)) await rm(childSocket, { force: true });
  }
}

// ── daemon 服务面 ────────────────────────────────────────────────────────
// 运行根自建（幂等）：生产拓扑由独立卷挂载点天然存在；共享卷子目录布局
// （runner 与 broker 同挂一卷时）下目录由本进程负责创建，与 broker 侧
// config 的启动 mkdir 对称。socket 是文件，只建其父目录。
await mkdir(dirname(resolve(socketPath)), { recursive: true }).catch(() => {});
for (const dir of [slotRoot, stateHome, tmpRoot]) {
  await mkdir(resolve(dir), { recursive: true }).catch(() => {});
}
const digest = await engineDigest();
const server = createServer((socket) => {
  const decoder = new FrameDecoder(frameLimitBytes);
  socket.on("data", (chunk) => {
    let frames;
    try {
      frames = decoder.push(chunk);
    } catch {
      socket.destroy();
      return;
    }
    for (const frame of frames) {
      if (!("payload" in frame) || "ok" in frame) continue; // 只处理请求
      void (async () => {
        let response;
        try {
          response = { v: RUNNER_PROTOCOL_VERSION, commandId: frame.commandId, requestId: frame.requestId, ok: true, result: await handle(frame) };
        } catch (error) {
          response = {
            v: RUNNER_PROTOCOL_VERSION, commandId: frame.commandId, requestId: frame.requestId, ok: false,
            error: { code: error?.code ?? "runner_error", message: error instanceof Error ? error.message : String(error) },
          };
        }
        try {
          socket.write(encodeFrame(response, frameLimitBytes));
        } catch { /* 对端已断 */ }
      })();
    }
  });
  socket.on("error", () => socket.destroy());
});

async function handle(frame) {
  const kind = frame.kind;
  if (kind === "status") {
    // §6.8：握手返回 protocolVersion/engineDigest/capacity，不含敏感路径。
    return { state: "ready", protocolVersion: RUNNER_PROTOCOL_VERSION, engineDigest: digest, capacity: 1, busy: busy !== null, authorChildSpawnCount };
  }
  if (kind === "cancel") {
    if (busy !== null && busy.commandId === frame.commandId) {
      busy.child?.kill("SIGTERM");
      return { cancelled: true };
    }
    return { cancelled: false, reason: busy === null ? "idle" : "other-command" };
  }
  if (!EXECUTION_KINDS.has(kind)) {
    throw Object.assign(new Error(`unknown runner kind "${kind}"`), { code: "unknown_kind" });
  }
  if (busy !== null) {
    throw Object.assign(new Error("runner capacity is 1 and currently occupied"), { code: "runner_busy" });
  }
  const payload = frame.payload;
  if (typeof payload !== "object" || payload === null) {
    throw Object.assign(new Error("payload must be a daemon envelope object"), { code: "invalid_payload" });
  }
  const { slotId, relativeInputRoot, command } = payload;
  assertSlotId(slotId);
  assertRelativeInputRoot(relativeInputRoot);
  assertNoSiblingSlotReference(slotId, command);
  const slotDir = join(slotRoot, slotId);
  if (!existsSync(slotDir)) {
    throw Object.assign(new Error("slot directory not prepared"), { code: "slot_missing" });
  }
  const busyEntry = { commandId: frame.commandId, child: null };
  busy = busyEntry;
  try {
    const outcome = await runOneShot(busyEntry, slotId, frame.commandId, kind, command);
    if (outcome.ok) return outcome.result;
    throw Object.assign(new Error(outcome.error.message), { code: outcome.error.code });
  } finally {
    busy = null;
  }
}

await rm(socketPath, { force: true });
await new Promise((resolveListen) => server.listen(socketPath, resolveListen));
if (process.env.HYPIT_RUNNER_DEBUG === "1") {
  console.error(`[runner-daemon] listening ${socketPath} digest=${digest}`);
}
// 优雅退出：SIGTERM → 关 socket 并清理 socket 文件；空闲立即退出；在途子进程
// 先宽限终止，等其退出或宽限到期再退。107-fix-3 C107F3-03 生命周期缺陷修复：
// 原实现无条件睡满 killGraceMs——空闲 daemon 每次 SIGTERM 都要 10s 才退
// （测试夹具每个用例的 stop() 都被放大为 10s 空转），在途路径也不等子进程
// 真正退出。仅修退出生命周期，不改隔离架构。
let shuttingDown = false;
process.on("SIGTERM", () => {
  if (shuttingDown) return;
  shuttingDown = true;
  server.close();
  void rm(socketPath, { force: true }).catch(() => {});
  const exitTimer = setTimeout(() => process.exit(0), killGraceMs);
  const terminateAndExit = (child) => {
    child.kill("SIGTERM");
    child.once("exit", () => { clearTimeout(exitTimer); process.exit(0); });
  };
  if (busy === null) process.exit(0);
  if (busy.child) {
    terminateAndExit(busy.child);
    return;
  }
  // spawn 尚未落定（busy.child 为空）：轮询等它出现后再宽限终止；exitTimer 兜底。
  const poll = setInterval(() => {
    const child = busy?.child;
    if (child) {
      clearInterval(poll);
      terminateAndExit(child);
    }
  }, 50);
});
