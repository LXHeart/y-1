// supervisor.ts — C107-02 (task-107) runner slot supervision; 107-fix-2 C04 改造。
//
// D-04（F02 修复）：broker 不再在本容器 spawn 作者代码。执行面整体移入独立
// 无网络 runner 容器的常驻 daemon（src/runner/daemon.mjs）——本类只负责：
//   - 在共享 /slots 卷上为每命令准备一次性 slot（input/output/scratch）；
//   - 通过共享 /sockets 卷上的 daemon socket（runner.sock）派发命令
//     （协议 v1 外壳 + {slotId, relativeInputRoot, command} 信封，slotId 只由
//     本类生成、daemon 验证 containment）；
//   - status 握手（protocolVersion/engineDigest/capacity=1）；容量排队仍由
//     withSlot 串行保证，daemon 侧 runner_busy 是第二道闸；
//   - daemon 不可用/超时 → 明确失败（runner_unavailable），绝不回退本进程执行。
//
// authorProcessSpawnCount 恒 0：broker 进程内不存在作者代码执行路径
// （TC-F2-04-01/04 的进程归属断言依据）。
import { mkdir, rm } from "node:fs/promises";
import { existsSync } from "node:fs";
import { join, resolve } from "node:path";

import { RunnerClient, connectWithRetry } from "./client.ts";
import { RUNNER_PROTOCOL_VERSION, type RunnerCommandKind } from "./protocol.ts";

export type SupervisorOptions = {
  readonly backendRoot: string;
  readonly distributionRoot: string;
  readonly slotRoot: string;
  readonly socketDir: string;
  /**
   * Packages-only state root visible to the runner (HYPIT_STATE_HOME). It is
   * deliberately separate from the broker's own state root: the broker state
   * holds platform credentials, which must never be readable inside the runner.
   */
  readonly runnerStateRoot: string;
  /** Stable tmp dir for the runner's tsx transform cache (read+write). */
  readonly runnerTmpRoot: string;
  readonly frameLimitBytes: number;
  readonly requestTimeoutMs: number;
  readonly killTimeoutMs: number;
};

export type RunnerLease = {
  readonly commandId: string;
  readonly slotDir: string;
  readonly inputDir: string;
  readonly outputDir: string;
  readonly scratchDir: string;
  request(kind: RunnerCommandKind, payload: unknown): Promise<unknown>;
};

/** 信封校验失败/守护进程拒绝的业务错误（区别于连接层错误）。 */
export class RunnerUnavailableError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "RunnerUnavailableError";
  }
}

export class RunnerSupervisor {
  readonly #options: SupervisorOptions;
  #queue: Promise<unknown> = Promise.resolve();
  #authorProcessSpawnCount = 0;
  #daemonStatus: { protocolVersion?: number; engineDigest?: string; capacity?: number } | null = null;

  constructor(options: SupervisorOptions) {
    this.#options = options;
  }

  /** broker 进程内作者代码执行计数：本实现恒 0（执行面在 runner 容器）。 */
  get authorProcessSpawnCount(): number {
    return this.#authorProcessSpawnCount;
  }

  /** 最近一次 daemon 握手结果（healthz 暴露给运维/验收；不含路径）。 */
  get daemonStatus(): { protocolVersion?: number; engineDigest?: string; capacity?: number } | null {
    return this.#daemonStatus;
  }

  private get daemonSocketPath(): string {
    return join(this.#options.socketDir, "runner.sock");
  }

  /** Serialize slot usage: broker 侧一次一条排队（daemon 容量 1 是硬闸）。 */
  withSlot<T>(operation: (lease: RunnerLease) => Promise<T>): Promise<T> {
    const run = this.#queue.then(async () => await this.#runOne(operation));
    this.#queue = run.catch(() => {});
    return run;
  }

  async #runOne<T>(operation: (lease: RunnerLease) => Promise<T>): Promise<T> {
    const commandId = `cmd-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
    const slotDir = resolve(this.#options.slotRoot, commandId);
    const inputDir = join(slotDir, "input");
    const outputDir = join(slotDir, "output");
    const scratchDir = join(slotDir, "scratch");
    if (existsSync(slotDir)) {
      await rm(slotDir, { recursive: true, force: true });
    }
    await mkdir(inputDir, { recursive: true });
    await mkdir(outputDir, { recursive: true });
    await mkdir(scratchDir, { recursive: true });

    const client = new RunnerClient({
      socketPath: this.daemonSocketPath,
      frameLimitBytes: this.#options.frameLimitBytes,
      requestTimeoutMs: this.#options.requestTimeoutMs,
    });
    try {
      await connectWithRetry(client, 20, 100);
      // 每次租约先做 status 握手（§6.8：protocolVersion/capacity；2 秒探测语义
      // 由 connectWithRetry 的 20×100ms 覆盖）。
      const status = await client.request(commandId, "status", {}) as {
        protocolVersion?: number; capacity?: number; engineDigest?: string;
      };
      if (status.protocolVersion !== RUNNER_PROTOCOL_VERSION) {
        throw new RunnerUnavailableError(
          `runner daemon protocol mismatch: expected v${RUNNER_PROTOCOL_VERSION}, got ${String(status.protocolVersion)}`);
      }
      if (typeof status.capacity === "number" && status.capacity < 1) {
        throw new RunnerUnavailableError("runner daemon reports zero capacity");
      }
      this.#daemonStatus = status;
      return await operation({
        commandId,
        slotDir,
        inputDir,
        outputDir,
        scratchDir,
        request: (kind, payload) => client.request(commandId, kind, {
          slotId: commandId,
          relativeInputRoot: "input",
          command: payload,
        }),
      });
    } catch (error) {
      // daemon socket 不在/拒绝 = 执行面不可用：明确失败，不回退本进程执行。
      if (error instanceof Error && /connect|ENOENT|EACCES|timed out|protocol mismatch|zero capacity/.test(error.message)) {
        throw new RunnerUnavailableError(`runner daemon unavailable: ${error.message}`);
      }
      throw error;
    } finally {
      client.close();
      await rm(slotDir, { recursive: true, force: true });
    }
  }
}
