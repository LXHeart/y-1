// catalog.ts — C107-06 (task-107) managed Python program catalog (K12).
//
// Two trusted program identities, one per upstream service tree, each with its
// own uv environment (never shared: WhisperX torch stack vs OpenCV runtime).
// The catalog carries only facts from the checked-in upstream metadata: pinned
// Python ranges, package names, prepare/check entry points and the loopback
// service protocol. Nothing here downloads at inference time.
import { join } from "node:path";

export type ProgramId = "whisperx.local" | "image.opencv.local";

export type ManagedProgramSpec = {
  readonly id: ProgramId;
  /** uv project directory inside the distribution (pyproject + uv.lock, never modified). */
  readonly serviceProject: (distributionRoot: string) => string;
  /** uv environment location inside the broker's programs root. */
  readonly environment: (programsRoot: string) => string;
  readonly kind: "service" | "runtime";
  /** Executable that starts the daemon (service kind only). */
  readonly serviceExecutable?: (programsRoot: string) => string;
  /** Explicit resource preparation entry (service kind only); logs progress, may download. */
  readonly prepareExecutable?: (programsRoot: string) => string;
  /** Interpreters/runtimes used by per-need bounded processes. */
  readonly pythonExecutable: (programsRoot: string) => string;
  readonly loopback: { readonly host: "127.0.0.1"; readonly port: number; readonly healthPath: string };
  /** Identity the /health endpoint must report before any work is accepted. */
  readonly expectedIdentity: {
    readonly protocol: string;
    readonly model?: string;
    readonly device?: string;
    readonly compute?: string;
    readonly batchSize?: number;
  };
  /** Inputs the service may read (its own defense-in-depth allowlist must agree). */
  readonly inputRoots: (programsRoot: string) => string[];
  readonly requiresPython: string;
};

/** Env read lazily (test processes set HYPIT_WHISPERX_PORT before first use). */
function envNumber(name: string, fallback: number): number {
  const raw = process.env[name];
  const parsed = raw === undefined ? Number.NaN : Number(raw);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}

export const PROGRAM_CATALOG: Readonly<Record<ProgramId, ManagedProgramSpec>> = {
  "whisperx.local": {
    id: "whisperx.local",
    serviceProject: (root) => join(root, "services/whisperx"),
    environment: (programsRoot) => join(programsRoot, "whisperx.local", ".venv"),
    kind: "service",
    serviceExecutable: (programsRoot) => join(programsRoot, "whisperx.local", ".venv/bin/hypit-whisperx-service"),
    prepareExecutable: (programsRoot) => join(programsRoot, "whisperx.local", ".venv/bin/hypit-whisperx-prepare"),
    pythonExecutable: (programsRoot) => join(programsRoot, "whisperx.local", ".venv/bin/python"),
    // Getters read env lazily: test processes override the port before first
    // use (ESM import order would otherwise freeze the default at load time).
    loopback: {
      host: "127.0.0.1" as const,
      get port() { return envNumber("HYPIT_WHISPERX_PORT", 8765); },
      healthPath: "/health",
    },
    expectedIdentity: {
      protocol: "hypit.whisperx-service@1",
      get model() { return process.env.HYPIT_WHISPERX_MODEL ?? "small"; },
      get device() { return process.env.HYPIT_WHISPERX_DEVICE ?? "cpu"; },
      get compute() { return process.env.HYPIT_WHISPERX_COMPUTE ?? "int8"; },
      get batchSize() { return envNumber("HYPIT_WHISPERX_BATCH_SIZE", 8); },
    },
    inputRoots: (programsRoot) => [join(programsRoot, "whisperx.local", "input")],
    requiresPython: ">=3.10,<3.14",
  },
  "image.opencv.local": {
    id: "image.opencv.local",
    serviceProject: (root) => join(root, "services/image-opencv"),
    environment: (programsRoot) => join(programsRoot, "image.opencv.local", ".venv"),
    kind: "runtime",
    pythonExecutable: (programsRoot) => join(programsRoot, "image.opencv.local", ".venv/bin/python"),
    // No daemon: one bounded process per admitted Need (upstream provider contract).
    loopback: { host: "127.0.0.1", port: 0, healthPath: "" },
    expectedIdentity: { protocol: "cv4+numpy2" },
    inputRoots: () => [],
    requiresPython: ">=3.13,<3.14",
  },
};

export function isProgramId(value: string): value is ProgramId {
  return value === "whisperx.local" || value === "image.opencv.local";
}

/** Alignment languages prepared for WhisperX; zh+en covers the committed fixtures. */
export function alignmentLanguages(): string[] {
  const raw = process.env.HYPIT_WHISPERX_ALIGNMENT_LANGUAGES ?? "zh en";
  return raw.split(/\s+/u).filter((item) => item.length > 0);
}
