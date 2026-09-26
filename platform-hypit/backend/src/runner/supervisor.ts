// supervisor.ts — C107-02 (task-107) runner process supervision (K10.4).
//
// One fixed execution slot, owned by one command at a time; other tasks queue.
// The runner child is spawned under Node's permission model: it may read the
// engine build (G), the backend's own trusted code and its slot; it may write
// only its slot and socket directory; no child processes, no addons. Between
// commands the previous process tree is fully terminated and the slot directory
// is recreated empty — outputs never leak into the next command.
import { spawn } from "node:child_process";
import { mkdir, rm } from "node:fs/promises";
import { existsSync } from "node:fs";
import { join, resolve } from "node:path";

import { RunnerClient, connectWithRetry } from "./client.ts";
import type { RunnerCommandKind } from "./protocol.ts";

export type SupervisorOptions = {
  readonly backendRoot: string;
  readonly distributionRoot: string;
  readonly slotRoot: string;
  readonly socketDir: string;
  /**
   * Packages-only state root visible to the runner (HYPIT_STATE_HOME). It is
   * deliberately separate from the broker's own state root: the broker state
   * holds platform credentials, which must never be readable inside the runner;
   * machine packages installed for author code land here instead.
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

export class RunnerSupervisor {
  readonly #options: SupervisorOptions;
  #queue: Promise<unknown> = Promise.resolve();

  constructor(options: SupervisorOptions) {
    this.#options = options;
  }

  /** Serialize slot usage: exactly one runner process exists at any time. */
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
    const socketPath = join(this.#options.socketDir, `${commandId}.sock`);

    const child = spawn(process.execPath, [
      // containment preload first, then tsx, then the entry (see guard.mjs)
      "--import", "./src/runner/guard.mjs",
      "--import", "tsx",
      "--permission",
      // tsx's ESM loader runs in an in-process worker thread; worker threads
      // share this process's permission scope, so this grants no new reach.
      "--allow-worker",
      // tsx's esbuild transform needs exactly one service subprocess.
      "--allow-child-process",
      // sharp and other engine tooling are native addons; guard.mjs restricts
      // dlopen to the engine's own node_modules.
      "--allow-addons",
      `--allow-fs-read=${resolve(this.#options.distributionRoot)}`,
      `--allow-fs-read=${resolve(this.#options.backendRoot)}`,
      // get-tsconfig probes case-flipped paths to detect case sensitivity
      `--allow-fs-read=${flipCase(resolve(this.#options.backendRoot))}`,
      `--allow-fs-read=${resolve(this.#options.runnerStateRoot)}`,
      `--allow-fs-read=${resolve(this.#options.runnerTmpRoot)}`,
      `--allow-fs-write=${resolve(this.#options.runnerTmpRoot)}`,
      `--allow-fs-read=${slotDir}`,
      `--allow-fs-write=${join(slotDir, "output")}`,
      `--allow-fs-write=${join(slotDir, "scratch")}`,
      `--allow-fs-read=${resolve(this.#options.socketDir)}`,
      `--allow-fs-write=${resolve(this.#options.socketDir)}`,
      join(this.#options.backendRoot, "src/runner/server.ts"),
      "--socket", socketPath,
      "--distribution-root", resolve(this.#options.distributionRoot),
      "--slot-root", slotDir,
      "--slot-output", outputDir,
      "--frame-limit", String(this.#options.frameLimitBytes),
    ], {
      stdio: ["ignore", "pipe", "pipe"],
      // cwd stays on trusted backend code so the bare `tsx` import resolves;
      // engine operations all take explicit absolute roots, never cwd.
      cwd: resolve(this.#options.backendRoot),
      // Minimal env: author code evaluated in the runner must not discover
      // slot/host paths or any broker secrets. TMPDIR and HYPIT_STATE_HOME are
      // engine-level conventions (transform cache; packages-only state root).
      env: {
        PATH: process.env.PATH ?? "/usr/bin:/bin",
        TMPDIR: resolve(this.#options.runnerTmpRoot),
        HYPIT_STATE_HOME: resolve(this.#options.runnerStateRoot),
        ...(process.env.HYPIT_RUNNER_DEBUG === undefined
          ? {}
          : { HYPIT_RUNNER_DEBUG: process.env.HYPIT_RUNNER_DEBUG }),
      },
    });
    let stdout = "";
    let stderr = "";
    child.stdout.on("data", (chunk: Buffer) => {
      stdout = (stdout + chunk.toString("utf8")).slice(-8000);
    });
    child.stderr.on("data", (chunk: Buffer) => {
      stderr = (stderr + chunk.toString("utf8")).slice(-8000);
    });

    const client = new RunnerClient({
      socketPath,
      frameLimitBytes: this.#options.frameLimitBytes,
      requestTimeoutMs: this.#options.requestTimeoutMs,
    });
    try {
      await connectWithRetry(client);
      return await operation({
        commandId,
        slotDir,
        inputDir,
        outputDir,
        scratchDir,
        request: (kind, payload) => client.request(commandId, kind, payload),
      });
    } catch (error) {
      if (error instanceof Error && stderr.length > 0) {
        error.message = `${error.message}\nrunner stderr tail:\n${stderr.split("\n").slice(-20).join("\n")}`;
      }
      throw error;
    } finally {
      client.close();
      await this.#terminate(child);
      if (existsSync(socketPath)) await rm(socketPath, { force: true });
    }
  }

  async #terminate(child: import("node:child_process").ChildProcess): Promise<void> {
    if (child.exitCode !== null || child.signalCode !== null) return;
    const exited = new Promise<void>((resolvePromise) => child.once("exit", () => resolvePromise()));
    child.kill("SIGTERM");
    const timer = new Promise<"timeout">((resolvePromise) =>
      setTimeout(() => resolvePromise("timeout"), this.#options.killTimeoutMs));
    if ((await Promise.race([exited.then(() => "exit" as const), timer])) === "timeout") {
      child.kill("SIGKILL");
      await exited;
    }
  }
}

/** Swap the case of every ASCII letter: get-tsconfig's sensitivity probe path. */
function flipCase(value: string): string {
  let out = "";
  for (const character of value) {
    const code = character.charCodeAt(0);
    if (code >= 97 && code <= 122) out += character.toUpperCase();
    else if (code >= 65 && code <= 90) out += character.toLowerCase();
    else out += character;
  }
  return out;
}
