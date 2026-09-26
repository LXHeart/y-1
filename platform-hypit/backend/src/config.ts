// config.ts — C107-02 (task-107) backend configuration: env-driven, validated once.
//
// The backend is the trusted Hypit execution broker. It must never receive
// secrets through this config (credentials live in the credential stores behind
// the runtime profile); only paths, ports and internal auth material appear here.
import { mkdirSync } from "node:fs";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

const backendRoot = resolve(fileURLToPath(new URL("..", import.meta.url)));
const repoRoot = resolve(backendRoot, "../..");

export type HypitBackendConfig = {
  readonly host: string;
  readonly port: number;
  /** G: the patched engine build this process may load (exactly one per process). */
  readonly generatedRoot: string;
  /** Host-side state root: bridge journal (C04), runner sockets, slots. */
  readonly dataRoot: string;
  /** Directory holding the per-slot Unix domain sockets. */
  readonly runnerSocketDir: string;
  /** Directory holding per-slot input/output/scratch volumes. */
  readonly runnerSlotRoot: string;
  /** Stable tmp root for runner tsx transform caches (reused across slots). */
  readonly runnerTmpRoot: string;
  /** Bearer token for /internal/v1 endpoints (C03 hardens parity with Java). */
  readonly internalToken: string;
  /** Runner IPC frame limit and timeouts. */
  readonly runnerFrameLimitBytes: number;
  readonly runnerRequestTimeoutMs: number;
  readonly runnerKillTimeoutMs: number;
};

function intEnv(name: string, fallback: number): number {
  const raw = process.env[name];
  if (raw === undefined || raw.trim().length === 0) return fallback;
  const value = Number(raw);
  if (!Number.isSafeInteger(value) || value <= 0) {
    throw new Error(`${name} must be a positive integer (got ${raw})`);
  }
  return value;
}

function pathEnv(name: string, fallback: string): string {
  const raw = process.env[name];
  return resolve(raw === undefined || raw.trim().length === 0 ? fallback : raw);
}

export function loadConfig(): HypitBackendConfig {
  const dataRoot = pathEnv("HYPIT_DATA_ROOT", resolve(repoRoot, "data/hypit/host"));
  const config: HypitBackendConfig = {
    host: process.env.HYPIT_BACKEND_HOST ?? "127.0.0.1",
    port: intEnv("HYPIT_BACKEND_PORT", 9240),
    generatedRoot: pathEnv("HYPIT_GENERATED_ROOT", resolve(repoRoot, "platform-hypit/.generated/hypit")),
    dataRoot,
    runnerSocketDir: resolve(dataRoot, "runner-sockets"),
    runnerSlotRoot: resolve(dataRoot, "runner-slots"),
    runnerTmpRoot: resolve(dataRoot, "../runner-tmp"),
    internalToken: process.env.HYPIT_INTERNAL_TOKEN ?? "",
    runnerFrameLimitBytes: intEnv("HYPIT_RUNNER_FRAME_LIMIT_BYTES", 1024 * 1024),
    runnerRequestTimeoutMs: intEnv("HYPIT_RUNNER_REQUEST_TIMEOUT_MS", 120_000),
    runnerKillTimeoutMs: intEnv("HYPIT_RUNNER_KILL_TIMEOUT_MS", 10_000),
  };
  return config;
}

/** Create the runtime directory layout; safe to call repeatedly. */
export function ensureRuntimeDirs(config: HypitBackendConfig): void {
  for (const dir of [config.dataRoot, config.runnerSocketDir, config.runnerSlotRoot, config.runnerTmpRoot]) {
    mkdirSync(dir, { recursive: true });
  }
  // Packages-only state root shared with the runner (never credentials).
  mkdirSync(resolve(config.dataRoot, "../runner-state"), { recursive: true });
}

export function assertConfigured(config: HypitBackendConfig): void {
  if (config.internalToken.length < 32) {
    throw new Error(
      "HYPIT_INTERNAL_TOKEN must be set to at least 32 characters before enabling /internal endpoints",
    );
  }
}
