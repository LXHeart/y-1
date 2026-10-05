import { execFileSync } from "node:child_process";
import { resolve } from "node:path";

const root = resolve(import.meta.dirname, "../../../..");
const guard = resolve(root, "scripts/local-stack.mjs");

/** Destructive isolation probes require an empty, leased project, even when invoked directly. */
export function requireFreshSession(project: string): void {
  execFileSync(process.execPath, [guard, "session", "--project", project, "--fresh"], {
    cwd: root, stdio: "pipe",
  });
}

export function guardedCompose(project: string, file: string, args: string[],
  env: NodeJS.ProcessEnv = process.env, timeout = 600_000): string {
  return execFileSync(process.execPath, [guard, "compose", "--project-name", project,
    "-f", file, "--", ...args], { cwd: root, encoding: "utf8", timeout, env,
    maxBuffer: 16 * 1024 * 1024 });
}
