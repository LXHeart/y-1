/**
 * C107F2-19 studio/launcher.ts — launches the upstream Studio server for one
 * session as a real supervised child process.
 *
 * F16 修复：不再 Promise 立即返回伪 pid——spawn 后轮询本机端口直到真实 HTTP
 * 应答（ready 探测），子进程提前退出/超时/端口不可用都 reject 并清理半启动
 * 进程；成功才 resolve。子进程环境走白名单（E04：不带 broker/Provider 秘密），
 * 作者编译/预览仍走 runner 边界，本进程只加载可信 Studio 服务。
 */
import { spawn, type ChildProcess } from "node:child_process";
import { request } from "node:http";
import { resolve } from "node:path";
import { randomUUID } from "node:crypto";
import { DispatchError } from "../commands/dispatcher.ts";

export type StudioLaunchOptions = {
  readonly distributionRoot: string;
  readonly workspaceRoot: string;
  readonly runFile: string;
  readonly port: number;
  /** Per-session mount prefix, e.g. /studio/<sessionId>; drives patch 0001. */
  readonly basePath: string;
  /** ≥32 chars; handed to the child as HYPIT_STUDIO_BRIDGE_TOKEN, never logged. */
  readonly bridgeToken?: string;
  /** Ready probe budget; defaults to 45s (vite cold boot includes tsx compile). */
  readonly readyTimeoutMs?: number;
  /** Test seam: override the child entry resolution without touching spawn(). */
  readonly nodeArgs?: readonly string[];
};

export type StudioProcess = {
  readonly pid: number;
  readonly port: number;
  stop: () => void;
  /** Resolves when the child has actually exited (best-effort wait). */
  readonly exited: Promise<void>;
  /** Secret-free stderr tail for failure diagnostics. */
  readonly stderrTail: readonly string[];
};

/**
 * E04：子进程只继承白名单环境。任何 broker 内部 token、票据/断言秘密、
 * Provider 凭据都不得进入 Studio 进程（作者代码在其中求值）。
 */
const CHILD_ENV_ALLOWLIST = [
  "PATH", "HOME", "LANG", "LC_ALL", "LC_CTYPE", "TMPDIR", "TZ", "NODE_OPTIONS",
];

function childEnv(options: StudioLaunchOptions): NodeJS.ProcessEnv {
  const env: NodeJS.ProcessEnv = {};
  for (const key of CHILD_ENV_ALLOWLIST) {
    if (process.env[key] !== undefined && process.env[key] !== "") env[key] = process.env[key];
  }
  env.HYPIT_STUDIO_BASE_PATH = options.basePath;
  if (options.bridgeToken !== undefined) env.HYPIT_STUDIO_BRIDGE_TOKEN = options.bridgeToken;
  return env;
}

/** Test/audit seam for the allowlist itself (E04: no broker or provider secrets). */
export function childEnvironmentFor(options: StudioLaunchOptions): NodeJS.ProcessEnv {
  return childEnv(options);
}

/** One HTTP probe against the child; any response (even 404/500) proves the listener is up. */
function probe(port: number, timeoutMs: number): Promise<boolean> {
  return new Promise((resolveProbe) => {
    const req = request({ host: "127.0.0.1", port, path: "/", method: "GET", timeout: timeoutMs }, (res) => {
      res.resume();
      resolveProbe(true);
    });
    req.on("timeout", () => { req.destroy(); resolveProbe(false); });
    req.on("error", () => resolveProbe(false));
    req.end();
  });
}

async function waitForReady(
  port: number,
  budgetMs: number,
  child: ChildProcess,
  spawnError: () => string | null,
): Promise<void> {
  const deadline = Date.now() + budgetMs;
  let exitedEarly: number | null = null;
  const onExit = (code: number | null) => { exitedEarly = code ?? -1; };
  child.once("exit", onExit);
  try {
    while (Date.now() < deadline) {
      const failure = spawnError();
      if (failure !== null) {
        throw new DispatchError("studio_unavailable", `studio launch failed: ${failure}`);
      }
      if (exitedEarly !== null) {
        throw new DispatchError("studio_unavailable", `studio process exited before ready (code ${exitedEarly})`);
      }
      if (await probe(port, 2_000)) return;
      await new Promise((sleep) => setTimeout(sleep, 250));
    }
    throw new DispatchError("studio_unavailable", `studio did not become ready within ${budgetMs}ms`);
  } finally {
    child.removeListener("exit", onExit);
  }
}

export async function launchStudio(options: StudioLaunchOptions): Promise<StudioProcess> {
  if (!Number.isInteger(options.port) || options.port <= 0 || options.port > 65535) {
    throw new DispatchError("invalid_input", "studio port must be 1..65535");
  }
  // 上游真实入口：bin/hypit.mjs studio（负责 tsx register 与发行版解析钩，
  // start.ts 只导出 runStudio，直接 spawn 它会静默退出——F16 实锄）。
  const entry = resolve(options.distributionRoot, "bin/hypit.mjs");
  const args = [
    entry,
    "studio",
    "--run", options.runFile,
    "--workspace", options.workspaceRoot,
    "--port", String(options.port),
    ...(options.nodeArgs ?? []),
  ];
  let child: ChildProcess;
  try {
    child = spawn(process.execPath, args, {
      // cwd 必须是发行版根：--import tsx 从 cwd 解析发行版内 node_modules。
      cwd: options.distributionRoot,
      env: childEnv(options),
      stdio: ["ignore", "ignore", "pipe"],
    });
  } catch (error) {
    throw new DispatchError("studio_unavailable", `studio spawn failed: ${String(error)}`);
  }
  const pid = child.pid ?? -1;
  const exited = new Promise<void>((resolveExit) => {
    child.once("exit", () => resolveExit());
  });
  const stderrTail: string[] = [];
  child.stderr?.on("data", (chunk: Buffer) => {
    const line = chunk.toString("utf8").trim();
    if (line.length > 0 && stderrTail.length < 20) stderrTail.push(line);
  });
  let spawnFailure: string | null = null;
  child.on("error", (error) => {
    if (spawnFailure === null) spawnFailure = error instanceof Error ? error.message : String(error);
  });
  const stop = (): void => {
    if (child.exitCode === null && !child.killed) child.kill("SIGTERM");
  };
  try {
    await waitForReady(options.port, options.readyTimeoutMs ?? 45_000, child, () => spawnFailure);
  } catch (error) {
    stop();
    const tail = stderrTail.slice(-5).join(" | ");
    if (error instanceof DispatchError && tail.length > 0) {
      throw new DispatchError(error.code, `${error.message}; stderr: ${tail}`);
    }
    throw error;
  }
  return { pid, port: options.port, stop, exited, stderrTail };
}

/** Session-scoped bridge credentials are per-launch, never shared across sessions. */
export function bridgeTokenForSession(): string {
  return `bridge-${randomUUID().replace(/-/gu, "")}-${randomUUID().replace(/-/gu, "")}`;
}
