// manager.ts — C107-06 (task-107) managed program lifecycle (K12).
//
// prepare/up/down/status/logs for the two trusted Python program identities.
// State survives broker restarts in <programsRoot>/<id>/state.json so `status`
// stays truthful instead of restarting processes behind the operator's back.
// prepare runs uv sync against the checked-in frozen locks plus the explicit
// WhisperX resource preparation (models/NLTK); every step streams into
// install.log. `up` observes PID and health separately: a health timeout keeps
// the process running and records `starting`, so a later status can still see
// it instead of spawning a duplicate.
import { spawn } from "node:child_process";
import { open } from "node:fs/promises";
import { existsSync } from "node:fs";
import { mkdir, readFile, rename, rm, writeFile } from "node:fs/promises";
import { join } from "node:path";

import { PROGRAM_CATALOG, alignmentLanguages, type ManagedProgramSpec, type ProgramId } from "./catalog.ts";
import { probeProgram, type ProbeState } from "./health.ts";

export type ProgramPhase =
  | "unprepared" | "preparing" | "prepared" | "starting" | "up" | "down" | "mismatch" | "failed";

export type ProgramStatus = {
  readonly id: ProgramId;
  readonly phase: ProgramPhase;
  readonly pid: number | null;
  readonly detail: string | null;
  readonly updatedAt: string;
  readonly identity: Readonly<Record<string, unknown>> | null;
};

type PersistedState = Omit<ProgramStatus, "id">;

export class ProgramsError extends Error {
  constructor(readonly code: string, message: string) {
    super(message);
    this.name = "ProgramsError";
  }
}

function statePath(programsRoot: string, id: ProgramId): string {
  return join(programsRoot, id, "state.json");
}

async function readState(programsRoot: string, id: ProgramId): Promise<PersistedState> {
  try {
    return JSON.parse(await readFile(statePath(programsRoot, id), "utf8")) as PersistedState;
  } catch {
    return { phase: "unprepared", pid: null, detail: null, updatedAt: new Date().toISOString(), identity: null };
  }
}

async function writeState(programsRoot: string, id: ProgramId, state: PersistedState): Promise<void> {
  await mkdir(join(programsRoot, id), { recursive: true });
  const target = statePath(programsRoot, id);
  const staging = `${target}.tmp-${Date.now().toString(36)}`;
  await writeFile(staging, `${JSON.stringify(state, undefined, 2)}\n`, "utf8");
  await rename(staging, target);
}

function pidAlive(pid: number | null): boolean {
  if (pid === null) return false;
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
}

/** Append one progress line to the program's install.log (bounded tail use only). */
async function appendLog(programsRoot: string, id: ProgramId, line: string): Promise<void> {
  const handle = await open(join(programsRoot, id, "install.log"), "a");
  try {
    await handle.write(`${new Date().toISOString()} ${line}\n`);
  } finally {
    await handle.close();
  }
}

export type ProgramsManagerOptions = {
  readonly programsRoot: string;
  readonly distributionRoot: string;
  /** Health wait budget for `up` (default 120s); a model-heavy warm-up can exceed quick checks. */
  readonly upTimeoutMs?: number;
};

export class ProgramsManager {
  constructor(private readonly options: ProgramsManagerOptions) {}

  spec(id: ProgramId): ManagedProgramSpec {
    return PROGRAM_CATALOG[id];
  }

  async status(id: ProgramId): Promise<ProgramStatus> {
    const spec = PROGRAM_CATALOG[id];
    const state = await readState(this.options.programsRoot, id);
    if (state.phase === "up" && !pidAlive(state.pid)) {
      const dead = { phase: "down" as ProgramPhase, pid: null, detail: "process exited", updatedAt: new Date().toISOString(), identity: state.identity };
      await writeState(this.options.programsRoot, id, dead);
      return { id, ...dead };
    }
    if (state.phase === "up" || state.phase === "starting") {
      const probe = spec.kind === "service" ? await probeProgram(spec, this.options.programsRoot) : null;
      if (probe !== null) {
        const phase: ProgramPhase = probe.state === "ready" ? "up" : probe.state === "mismatch" ? "mismatch" : "starting";
        const next = { phase, pid: state.pid, detail: probe.detail ?? null, updatedAt: new Date().toISOString(), identity: probe.identity ?? null };
        await writeState(this.options.programsRoot, id, next);
        return { id, ...next };
      }
    }
    return { id, ...state };
  }

  /**
   * uv sync (frozen, into the broker's programs root) plus — for WhisperX — the
   * explicit resource preparation entry. Returns the final phase; failures keep
   * the log path so `logs` can show exactly which step broke.
   */
  async prepare(id: ProgramId): Promise<ProgramStatus> {
    const spec = PROGRAM_CATALOG[id];
    await mkdir(join(this.options.programsRoot, id, "input"), { recursive: true });
    await writeState(this.options.programsRoot, id, { phase: "preparing", pid: null, detail: "uv sync running", updatedAt: new Date().toISOString(), identity: null });
    await appendLog(this.options.programsRoot, id, `prepare begin: uv sync --frozen (${spec.requiresPython})`);
    try {
      await this.runLogged("uv", [
        "sync", "--project", spec.serviceProject(this.options.distributionRoot), "--frozen", "--no-dev",
      ], { UV_PROJECT_ENVIRONMENT: spec.environment(this.options.programsRoot) }, id);
      if (spec.prepareExecutable !== undefined) {
        await appendLog(this.options.programsRoot, id, `prepare resources: hypit-whisperx-prepare languages=${alignmentLanguages().join("+")}`);
        await this.runLogged(spec.prepareExecutable(this.options.programsRoot), [], {
          HYPIT_WHISPERX_ALIGNMENT_LANGUAGES: alignmentLanguages().join(" "),
          HYPIT_WHISPERX_INPUT_ROOTS: spec.inputRoots(this.options.programsRoot).join(":"),
          HYPIT_WHISPERX_MODEL_CACHE: join(this.options.programsRoot, "whisperx.local", "model-cache"),
        }, id, 30 * 60_000);
      }
      const probe = spec.kind === "runtime"
        ? await probeProgram(spec, this.options.programsRoot)
        : { state: "ready" as const, detail: undefined, identity: undefined };
      const phase: ProgramPhase = probe.state === "ready" ? "prepared" : "unprepared";
      await writeState(this.options.programsRoot, id, { phase, pid: null, detail: probe.detail ?? null, updatedAt: new Date().toISOString(), identity: probe.identity ?? null });
      return { id, phase, pid: null, detail: probe.detail ?? null, updatedAt: new Date().toISOString(), identity: probe.identity ?? null };
    } catch (error) {
      const detail = `prepare failed: ${(error as Error).message.slice(0, 300)}`;
      await appendLog(this.options.programsRoot, id, detail);
      await writeState(this.options.programsRoot, id, { phase: "failed", pid: null, detail, updatedAt: new Date().toISOString(), identity: null });
      throw new ProgramsError("prepare_failed", detail);
    }
  }

  /**
   * Start the daemon (service programs only). An already-live PID is observed,
   * not duplicated; a health timeout leaves the process running in `starting`
   * so status remains queryable.
   */
  async up(id: ProgramId): Promise<ProgramStatus> {
    const spec = PROGRAM_CATALOG[id];
    if (spec.kind !== "service" || spec.serviceExecutable === undefined) {
      throw new ProgramsError("not_a_service", `${id} runs per-Need bounded processes; use prepare + status`);
    }
    const state = await readState(this.options.programsRoot, id);
    if ((state.phase === "up" || state.phase === "starting") && pidAlive(state.pid)) {
      const probe = await probeProgram(spec, this.options.programsRoot);
      const phase: ProgramPhase = probe.state === "ready" ? "up" : probe.state === "mismatch" ? "mismatch" : "starting";
      const next = { phase, pid: state.pid, detail: probe.detail ?? null, updatedAt: new Date().toISOString(), identity: probe.identity ?? null };
      await writeState(this.options.programsRoot, id, next);
      return { id, ...next };
    }
    if (!existsSync(spec.serviceExecutable(this.options.programsRoot))) {
      throw new ProgramsError("unprepared", `${id} has no prepared environment; run prepare first`);
    }
    const log = await open(join(this.options.programsRoot, id, "service.log"), "a");
    const child = spawn(spec.serviceExecutable(this.options.programsRoot), [], {
      stdio: ["ignore", log.fd, log.fd],
      detached: false,
      env: {
        ...process.env,
        HYPIT_WHISPERX_INPUT_ROOTS: spec.inputRoots(this.options.programsRoot).join(":"),
        HYPIT_WHISPERX_NLTK_DATA: join(this.options.programsRoot, "whisperx.local", "nltk-data"),
        HYPIT_WHISPERX_MODEL_CACHE: join(this.options.programsRoot, "whisperx.local", "model-cache"),
      },
    });
    child.unref();
    await log.close();
    const pid = child.pid ?? null;
    await appendLog(this.options.programsRoot, id, `up: spawned pid=${pid}, waiting for /health`);
    await writeState(this.options.programsRoot, id, { phase: "starting", pid, detail: "waiting for /health", updatedAt: new Date().toISOString(), identity: null });
    const budget = this.options.upTimeoutMs ?? 120_000;
    const deadline = Date.now() + budget;
    let probe: ProbeState = { state: "down" };
    while (Date.now() < deadline) {
      probe = await probeProgram(spec, this.options.programsRoot);
      if (probe.state !== "down") break;
      await new Promise((resolvePromise) => setTimeout(resolvePromise, 1_000));
    }
    if (probe.state === "ready") {
      const next = { phase: "up" as ProgramPhase, pid, detail: null, updatedAt: new Date().toISOString(), identity: probe.identity ?? null };
      await writeState(this.options.programsRoot, id, next);
      return { id, ...next };
    }
    // Timeout or mismatch: keep the process; status stays queryable (no duplicate spawn).
    const phase: ProgramPhase = probe.state === "mismatch" ? "mismatch" : "starting";
    const detail = probe.state === "mismatch"
      ? probe.detail ?? "identity mismatch" : `health not ready after ${Math.round(budget / 1000)}s; process kept running`;
    const next = { phase, pid, detail, updatedAt: new Date().toISOString(), identity: probe.identity ?? null };
    await writeState(this.options.programsRoot, id, next);
    return { id, ...next };
  }

  async down(id: ProgramId): Promise<ProgramStatus> {
    const spec = PROGRAM_CATALOG[id];
    const state = await readState(this.options.programsRoot, id);
    if (state.pid !== null && pidAlive(state.pid)) {
      process.kill(state.pid, "SIGTERM");
      const deadline = Date.now() + 10_000;
      while (Date.now() < deadline && pidAlive(state.pid)) {
        await new Promise((resolvePromise) => setTimeout(resolvePromise, 250));
      }
      if (pidAlive(state.pid)) {
        process.kill(state.pid, "SIGKILL");
      }
      await appendLog(this.options.programsRoot, id, `down: stopped pid=${state.pid}`);
    }
    const next = { phase: "down" as ProgramPhase, pid: null, detail: null, updatedAt: new Date().toISOString(), identity: null };
    await writeState(this.options.programsRoot, id, next);
    return { id, ...next };
  }

  /** Tail install.log (and service.log for service programs). */
  async logs(id: ProgramId, maxBytes = 64 * 1024): Promise<Record<string, string | null>> {
    const result: Record<string, string | null> = {};
    for (const name of spec0(id)) {
      try {
        const raw = await readFile(join(this.options.programsRoot, id, name), "utf8");
        result[name] = raw.length <= maxBytes ? raw : raw.slice(raw.length - maxBytes);
      } catch {
        result[name] = null;
      }
    }
    return result;
  }

  private async runLogged(executable: string, args: readonly string[], env: Record<string, string>,
      id: ProgramId, timeoutMs = 20 * 60_000): Promise<void> {
    await new Promise<void>((resolvePromise, rejectPromise) => {
      const child = spawn(executable, [...args], {
        env: { ...process.env, ...env },
        stdio: ["ignore", "pipe", "pipe"],
      });
      let settled = false;
      const timer = setTimeout(() => {
        if (!settled) {
          settled = true;
          child.kill("SIGKILL");
          rejectPromise(new ProgramsError("prepare_timeout", `${executable} exceeded ${timeoutMs}ms`));
        }
      }, timeoutMs);
      const out = (chunk: Buffer) => {
        for (const line of chunk.toString("utf8").split("\n")) {
          if (line.trim().length > 0) void appendLog(this.options.programsRoot, id, line.slice(0, 500));
        }
      };
      child.stdout?.on("data", out);
      child.stderr?.on("data", out);
      child.on("error", (error) => {
        if (!settled) { settled = true; clearTimeout(timer); rejectPromise(error); }
      });
      child.on("exit", (code) => {
        if (!settled) {
          settled = true;
          clearTimeout(timer);
          if (code === 0) resolvePromise();
          else rejectPromise(new ProgramsError("prepare_failed", `${executable} exited ${code}`));
        }
      });
    });
  }
}

function spec0(id: ProgramId): string[] {
  return PROGRAM_CATALOG[id].kind === "service" ? ["install.log", "service.log"] : ["install.log"];
}

/** Remove one program's runtime footprint (test hygiene; never touches the distribution). */
export async function removeProgram(programsRoot: string, id: ProgramId): Promise<void> {
  await rm(join(programsRoot, id), { recursive: true, force: true }).catch(() => {});
}
