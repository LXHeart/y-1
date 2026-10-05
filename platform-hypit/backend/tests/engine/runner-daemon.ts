// runner-daemon.ts — C107F2-40 集成复核处置（C107F2-32 行移交的基线失败）；
// 107-fix-3 C107F3-03 步骤2：fixture 独占 slot/state/tmp、启动失败 cleanup、
// 就绪握手与退出生命周期收敛（沿现有 daemon/client 架构，不另造 daemon）。
//
// D-04（C107F2-04）后执行面移入常驻 runner daemon：broker 不再在本进程派生
// 作者代码执行，RunnerSupervisor 只连接既有 daemon socket。engine 组的
// runner-isolation / package-build 测试仍假设 supervisor 自带执行面，基线
// 失败（RunnerUnavailableError: connect ENOENT …/runner.sock）。
//
// 本夹具按 Dockerfile.runner 的启动式（node --import tsx src/runner/daemon.mjs
// --socket … --slot-root …）在临时 socket/slot 目录上拉起一个真实 daemon，
// 供测试内的 RunnerSupervisor 连接——隔离语义（slot 逃逸/宿主读取/env 泄漏/
// 超时回收）全部经真实 daemon 验证，不做桩替。
import { spawn, type ChildProcess } from "node:child_process";
import { cpSync, existsSync, mkdirSync, mkdtempSync, rmSync } from "node:fs";
import { join } from "node:path";
import { setTimeout as delay } from "node:timers/promises";

import { RunnerClient, connectWithRetry } from "../../src/runner/client.ts";
import { RUNNER_PROTOCOL_VERSION } from "../../src/runner/protocol.ts";
import { RunnerSupervisor } from "../../src/runner/supervisor.ts";

const backendRoot = join(import.meta.dirname, "../..");
const repoRoot = join(backendRoot, "../..");
const generatedRoot = join(repoRoot, "platform-hypit/.generated/hypit");
/** 稳定机器包缓存（chat-workspace 的 ensureChatMachinePackages 只装一次的种子源）。 */
const stableRunnerStateRoot = join(repoRoot, "data/hypit/runner-state");

export type RunnerDaemonFixture = {
  supervisor: RunnerSupervisor;
  /** 同一 daemon 上再开一个 supervisor（如超时语义与正常语义共用一个执行面）。 */
  newSupervisor(overrides?: Partial<ConstructorParameters<typeof RunnerSupervisor>[0]>): RunnerSupervisor;
  /**
   * 等 daemon 报告空闲（status.busy=false）。宿主语义下客户端超时只断开请求方，
   * 在途命令由 daemon 跑完/宽限终止后才释放容量 1——下一条命令前先等空闲。
   */
  waitIdle(timeoutMs?: number): Promise<void>;
  /** 本 fixture 独占的 runner state 根（F-RUNNER：每测试独占 slot/state/tmp）。 */
  readonly stateRoot: string;
  /**
   * 本 fixture 独占的 runner tmp 根（daemon 子进程 TMPDIR）。runner 内写入的
   * marker 等证据只可能来自本次子进程——共享稳定目录里的陈旧文件不再构成
   * 「探针未执行仍假阳」的通道。
   */
  readonly tmpRoot: string;
  stop(): Promise<void>;
};

// slot/socket 根必须在真实（非 symlink）且足够短的路径上：runner 子进程的
// cmd.sock 落在 <slotRoot>/cmd-<id>/scratch/ 下，macOS sun_path 上限 104 字节，
// 仓库内长路径会 listen EINVAL（容器内 /slots 短路径不复现）。/private/tmp
// 是 /tmp 的真实路径（无 symlink 穿越），permission allowlist 的 realpath
// 比对不受影响。
const SHORT_ROOT = "/private/tmp/hypit-fix2-runner";

export async function startRunnerDaemon(
  prefix: string,
  overrides: Partial<ConstructorParameters<typeof RunnerSupervisor>[0]> = {},
): Promise<RunnerDaemonFixture> {
  mkdirSync(SHORT_ROOT, { recursive: true });
  const slotRoot = mkdtempSync(join(SHORT_ROOT, `${prefix}-slots-`));
  const socketDir = mkdtempSync(join(SHORT_ROOT, `${prefix}-sock-`));
  const socketPath = join(socketDir, "runner.sock");
  // 每测试独占 state/tmp：state 由稳定机器包缓存做只读种子复制（字体等机器包），
  // tmp 全新空目录；两者随 stop()/启动失败一并回收，测试之间零共享。
  const stateRoot = mkdtempSync(join(SHORT_ROOT, `${prefix}-state-`));
  const tmpRoot = mkdtempSync(join(SHORT_ROOT, `${prefix}-tmp-`));
  const seedPackages = join(stableRunnerStateRoot, "packages");
  if (existsSync(seedPackages)) {
    cpSync(seedPackages, join(stateRoot, "packages"), { recursive: true, force: true });
  }
  const ownedDirs = [slotRoot, socketDir, stateRoot, tmpRoot];
  const cleanupOwnedDirs = () => {
    for (const dir of ownedDirs) rmSync(dir, { recursive: true, force: true });
  };

  const child: ChildProcess = spawn(process.execPath, [
    "--import", "tsx", "src/runner/daemon.mjs",
    "--socket", socketPath,
    "--distribution-root", generatedRoot,
    "--slot-root", slotRoot,
    "--state-home", stateRoot,
    "--tmp", tmpRoot,
  ], { cwd: backendRoot, stdio: ["ignore", "pipe", "pipe"] });
  let daemonLog = "";
  child.stdout?.setEncoding("utf8").on("data", (chunk: string) => { daemonLog += chunk; });
  child.stderr?.setEncoding("utf8").on("data", (chunk: string) => { daemonLog += chunk; });

  const statusProbe = async (): Promise<{ state?: string; protocolVersion?: number; busy?: boolean } | null> => {
    const client = new RunnerClient({ socketPath, frameLimitBytes: 1024 * 1024, requestTimeoutMs: 5_000 });
    try {
      await connectWithRetry(client, 10, 100);
      return await client.request("idle-probe", "status", {}) as { state?: string; protocolVersion?: number; busy?: boolean };
    } catch {
      return null;
    } finally {
      client.close();
    }
  };

  try {
    // 冷启动（tsx 载入 TS 模块+发行版指纹）需要数十秒；socket 文件出现即已监听，
    // 握手交给下面的 status 探测。
    const deadline = Date.now() + 180_000;
    while (!existsSync(socketPath)) {
      if (child.exitCode !== null || child.signalCode !== null) {
        throw new Error(`runner daemon 提前退出（exit=${child.exitCode} signal=${child.signalCode}）：${daemonLog.slice(-2000)}`);
      }
      if (Date.now() > deadline) {
        child.kill("SIGKILL");
        throw new Error(`runner daemon socket 未在窗口内出现：${daemonLog.slice(-2000)}`);
      }
      await delay(200);
    }
    // 就绪握手：status 必须回报 ready + 本协议版本（与生产 supervisor 每次 lease
    // 的握手语义一致）；daemon 早期坏死的等待收敛为带日志的显式失败，不留给
    // 后续命令报模糊的 connect 错误。
    const handshakeDeadline = Date.now() + 60_000;
    for (;;) {
      const status = await statusProbe();
      if (status?.state === "ready" && status.protocolVersion === RUNNER_PROTOCOL_VERSION) break;
      if (child.exitCode !== null || child.signalCode !== null) {
        throw new Error(`runner daemon 握手前退出（exit=${child.exitCode} signal=${child.signalCode}）：${daemonLog.slice(-2000)}`);
      }
      if (Date.now() > handshakeDeadline) {
        throw new Error(`runner daemon 握手未就绪（status=${JSON.stringify(status)}）：${daemonLog.slice(-2000)}`);
      }
      await delay(200);
    }
  } catch (error) {
    // 启动/握手失败：先回收子进程，再清掉本次独占目录（不留临时根泄漏）。
    if (child.exitCode === null && child.signalCode === null) child.kill("SIGKILL");
    cleanupOwnedDirs();
    throw error;
  }

  const supervisorDefaults = {
    backendRoot,
    distributionRoot: generatedRoot,
    slotRoot,
    socketDir,
    runnerStateRoot: stateRoot,
    runnerTmpRoot: tmpRoot,
    frameLimitBytes: 1024 * 1024,
    requestTimeoutMs: 120_000,
    killTimeoutMs: 5_000,
    ...overrides,
  };

  return {
    supervisor: new RunnerSupervisor(supervisorDefaults),
    newSupervisor: (more = {}) => new RunnerSupervisor({ ...supervisorDefaults, ...more }),
    async waitIdle(timeoutMs = 90_000) {
      const until = Date.now() + timeoutMs;
      for (;;) {
        const status = await statusProbe();
        if (status?.busy === false) return;
        if (child.exitCode !== null || child.signalCode !== null) {
          throw new Error(`runner daemon 在等闲时退出（exit=${child.exitCode} signal=${child.signalCode}）：${daemonLog.slice(-2000)}`);
        }
        if (Date.now() > until) throw new Error(`runner daemon 未在 ${timeoutMs}ms 内回到空闲（status=${JSON.stringify(status)}）`);
        await delay(500);
      }
    },
    stateRoot,
    tmpRoot,
    async stop() {
      if (child.exitCode === null && child.signalCode === null) {
        child.kill("SIGTERM");
        await new Promise<void>((resolve) => {
          const killTimer = setTimeout(() => { child.kill("SIGKILL"); }, 10_000);
          child.on("exit", () => { clearTimeout(killTimer); resolve(); });
        });
      }
      cleanupOwnedDirs();
    },
  };
}
