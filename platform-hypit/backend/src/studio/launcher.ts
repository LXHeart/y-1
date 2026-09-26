/**
 * C107-12 studio/launcher.ts — launches the upstream Studio server for one
 * session as a detached child (detached Worker pattern from 09.3: page close
 * never kills the session), pinned to the project workspace, with the
 * base-path patch env and the bridge token injected server-side only.
 */
import { spawn } from "node:child_process";
import { resolve } from "node:path";
import { randomUUID } from "node:crypto";
import { DispatchError } from "../commands/dispatcher.ts";

export type StudioLaunchOptions = {
  readonly distributionRoot: string;
  readonly workspaceRoot: string;
  readonly runFile: string;
  readonly port: number;
  readonly basePath: string;
  /** ≥32 chars; handed to the child as HYPIT_STUDIO_BRIDGE_TOKEN, never logged. */
  readonly bridgeToken?: string;
  readonly env?: NodeJS.ProcessEnv;
};

export type StudioProcess = {
  readonly pid: number;
  readonly port: number;
  stop: () => void;
};

export function launchStudio(options: StudioLaunchOptions): Promise<StudioProcess> {
  if (!Number.isInteger(options.port) || options.port <= 0 || options.port > 65535) {
    return Promise.reject(new DispatchError("invalid_input", "studio port must be 1..65535"));
  }
  const entry = resolve(options.distributionRoot, "packages/studio/start.ts");
  const child = spawn(process.execPath, [
    "--experimental-strip-types",
    entry,
    "--run", options.runFile,
    "--workspace", options.workspaceRoot,
    "--port", String(options.port),
  ], {
    cwd: options.workspaceRoot,
    env: {
      ...process.env,
      ...options.env,
      HYPIT_STUDIO_BASE_PATH: options.basePath,
      ...(options.bridgeToken === undefined ? {} : { HYPIT_STUDIO_BRIDGE_TOKEN: options.bridgeToken }),
    },
    stdio: "ignore",
    detached: true,
  }).on("error", (error) => {
    throw new DispatchError("studio_unavailable", `studio launch failed: ${String(error)}`);
  });
  child.unref();
  return Promise.resolve({ pid: child.pid ?? -1, port: options.port, stop: () => child.kill("SIGTERM") });
}

/** Session-scoped bridge credentials are per-launch, never shared across sessions. */
export function bridgeTokenForSession(): string {
  return `bridge-${randomUUID().replace(/-/gu, "")}-${randomUUID().replace(/-/gu, "")}`;
}
