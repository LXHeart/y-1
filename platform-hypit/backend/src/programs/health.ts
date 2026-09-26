// health.ts — C107-06 (task-107) program health probes.
//
// WhisperX health compares the live /health identity against the expected
// deployment identity BEFORE any work is submitted (an incompatible warm
// process must not accept work). OpenCV has no daemon: readiness is the
// interpreter's own version report, kept equal to the upstream probe.
import { execFile } from "node:child_process";

import type { ManagedProgramSpec } from "./catalog.ts";

export type ProbeState = {
  readonly state: "ready" | "down" | "mismatch" | "unprepared";
  readonly detail?: string;
  readonly identity?: Readonly<Record<string, unknown>>;
};

const PROBE_PROGRAM = "import cv2, numpy, json; print(json.dumps({'cv2': cv2.__version__, 'numpy': numpy.__version__}))";

function runOnce(executable: string, args: readonly string[], timeoutMs: number): Promise<{ ok: boolean; output: string }> {
  return new Promise((resolve) => {
    execFile(executable, [...args], { timeout: timeoutMs, shell: false }, (error, stdout, stderr) => {
      resolve(error === null
        ? { ok: true, output: stdout.trim() }
        : { ok: false, output: ((stderr || "").trim() || error.message).split("\n").at(-1) ?? "" });
    });
  });
}

/** GET the WhisperX /health document with a short bounded timeout. */
export async function whisperxHealth(spec: ManagedProgramSpec): Promise<ProbeState> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 2_500);
  try {
    const response = await fetch(
      `http://${spec.loopback.host}:${spec.loopback.port}${spec.loopback.healthPath}`,
      { signal: controller.signal, headers: { accept: "application/json" } },
    );
    if (!response.ok) {
      return { state: "down", detail: `/health answered ${response.status}` };
    }
    const identity = await response.json() as Record<string, unknown>;
    const mismatches: string[] = [];
    const expected = spec.expectedIdentity as Readonly<Record<string, unknown>>;
    for (const [key, value] of Object.entries(expected)) {
      if (String(identity[key]) !== String(value)) {
        mismatches.push(`${key}: expected ${String(value)}, live ${String(identity[key])}`);
      }
    }
    return mismatches.length === 0
      ? { state: "ready", identity }
      : { state: "mismatch", detail: `warm service identity differs — ${mismatches.join("; ")}`, identity };
  } catch (error) {
    return { state: "down", detail: error instanceof Error ? error.message : String(error) };
  } finally {
    clearTimeout(timer);
  }
}

/** The upstream OpenCV interpreter probe: majors must be cv2 4.x / numpy 2.x. */
export async function opencvProbe(spec: ManagedProgramSpec, programsRoot: string): Promise<ProbeState> {
  const python = spec.pythonExecutable(programsRoot);
  const result = await runOnce(python, ["-c", PROBE_PROGRAM], 15_000);
  if (!result.ok) {
    return { state: "unprepared", detail: `${python} cannot import cv2 and numpy: ${result.output}` };
  }
  let found: Record<string, string>;
  try {
    found = JSON.parse(result.output) as Record<string, string>;
  } catch {
    return { state: "down", detail: `${python} answered something other than a version report` };
  }
  const differs = Object.entries({ cv2: 4, numpy: 2 })
    .filter(([name, major]) => Number.parseInt(found[name] ?? "", 10) !== major)
    .map(([name, major]) => `${name} is ${found[name] ?? "absent"}, expected ${major}.x`);
  return differs.length === 0
    ? { state: "ready", identity: found }
    : { state: "mismatch", detail: differs.join("; "), identity: found };
}

/** Dispatch the right probe for a program kind. */
export async function probeProgram(spec: ManagedProgramSpec, programsRoot: string): Promise<ProbeState> {
  return spec.kind === "service" ? whisperxHealth(spec) : opencvProbe(spec, programsRoot);
}
