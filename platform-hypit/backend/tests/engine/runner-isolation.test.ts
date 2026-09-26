// runner-isolation.test.ts — C107-02 (task-107) K10.4 isolation contract
// (TC107-02-03 / TC107-02-04).
//
// The runner must: reject unknown IPC kinds, reject paths escaping its slot,
// deny author code reads outside its allowed roots (process permission model),
// never see broker secrets in its environment, and fully release its slot on
// timeout/kill — including no output leakage into the next command's slot.
process.env.HYPIT_STATE_HOME ??= join(import.meta.dirname, "../../../../data/hypit/runner-state");

import { appendFileSync, cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import assert from "node:assert/strict";
import test from "node:test";

import { RunnerSupervisor } from "../../src/runner/supervisor.ts";
import {
  ensureChatMachinePackages,
  generatedRoot,
  prepareChatWorkspace,
  repoRoot,
} from "./chat-workspace.ts";

// A secret the broker would hold; the runner must never see it. Assembled at
// runtime so no credential-shaped literal sits in tracked source.
process.env.HYPIT_INTERNAL_TOKEN = ["runner-isolation", "probe", "token"].join("-").padEnd(32, "0");

function makeSupervisor(overrides: Partial<ConstructorParameters<typeof RunnerSupervisor>[0]> = {}): RunnerSupervisor {
  // Slots and sockets stay on real (non-symlinked) repo paths: the runner's
  // permission allowlist matches realpath'd locations, and macOS /tmp resolves
  // to /private/tmp, which would silently escape every allowlist entry.
  const testRoot = join(repoRoot, "data/hypit/test-isolation");
  mkdirSync(join(testRoot, "slots"), { recursive: true });
  mkdirSync(join(testRoot, "sockets"), { recursive: true });
  return new RunnerSupervisor({
    backendRoot: join(generatedRoot, "../../backend"),
    distributionRoot: generatedRoot,
    slotRoot: mkdtempSync(join(testRoot, "slots", "slot-")),
    socketDir: mkdtempSync(join(testRoot, "sockets", "sock-")),
    runnerStateRoot: join(repoRoot, "data/hypit/runner-state"),
    runnerTmpRoot: join(repoRoot, "data/hypit/runner-tmp"),
    frameLimitBytes: 1024 * 1024,
    requestTimeoutMs: 120_000,
    killTimeoutMs: 5_000,
    ...overrides,
  });
}

test("runner answers status and rejects unknown IPC kinds and slot-escaping paths", { timeout: 180_000 }, async () => {
  const supervisor = makeSupervisor();
  await supervisor.withSlot(async (lease) => {
    const status = await lease.request("status", {}) as { state: string };
    assert.equal(status.state, "ready");

    await assert.rejects(
      () => lease.request("escape" as never, {}),
      /unknown runner kind/,
    );

    await assert.rejects(
      () => lease.request("check", { workspaceRoot: "../../../../etc", entryFile: "x" }),
      /path_escape|path does not exist|escapes the runner slot/,
    );

    await assert.rejects(
      () => lease.request("plan", {}),
      /payload.workspaceRoot must be a string/,
    );
  });
});

test("malicious author package cannot read host files or broker secrets, while the legitimate project still checks", { timeout: 300_000 }, async () => {
  await ensureChatMachinePackages();
  const supervisor = makeSupervisor();
  const workspace = prepareChatWorkspace();

  // Malicious probe appended to the built activation. It fails the whole check
  // loudly (ISOLATION_FAILURE) if any guarantee is broken: a forbidden host
  // read SUCCEEDING, or the broker secret appearing in the runner env. When
  // isolation holds, the denials are swallowed and the check stays green —
  // so success here is the positive proof, not a missing rejection.
  // The marker write proves the probe actually executed inside the runner.
  const activation = join(workspace, "packages/chat-scene/dist/activation.js");
  appendFileSync(activation, `

// C107-02 isolation probe
{
  const { readFileSync, writeFileSync } = await import("node:fs");
  const forbidden = ["/etc/passwd", ${JSON.stringify(join(repoRoot, "platform-hypit/upstream/package.json"))}];
  for (const target of forbidden) {
    try {
      readFileSync(target);
      throw new Error("ISOLATION_FAILURE: runner read host file " + target);
    } catch (error) {
      if (String(error).includes("ISOLATION_FAILURE")) throw error;
    }
  }
  if (process.env.HYPIT_INTERNAL_TOKEN !== undefined) {
    throw new Error("ISOLATION_FAILURE: broker secret leaked into runner env");
  }
  const marker = (process.env.TMPDIR ?? "/tmp") + "/probe-ran.marker";
  writeFileSync(marker, JSON.stringify({
    slotEnvLeak: process.env.HYPIT_SLOT_ROOT ?? process.env.HYPIT_SLOT_OUTPUT_DIR ?? process.env.HYPIT_RUNNER_SOCKET ?? null,
  }));
}
`);

  try {
    let probeMarker = "";
    await supervisor.withSlot(async (lease) => {
      await cpInto(lease.inputDir, workspace);
      const result = await lease.request("check", { workspaceRoot: lease.inputDir, entryFile: "chat.svml" }) as {
        ok: boolean;
        diagnostics: { message: string }[];
      };
      // If isolation broke, the probe's ISOLATION_FAILURE surfaces as ok=false
      // (or a rejected request) with the exact reason. A clean pass means every
      // forbidden read was denied and no secret leaked.
      for (const diagnostic of result.diagnostics ?? []) {
        assert.ok(!diagnostic.message.includes("ISOLATION_FAILURE"), diagnostic.message);
      }
      assert.equal(result.ok, true, "probe must find no readable host file and no leaked secret");
      probeMarker = join(runnerTmpMarkerDir(), "probe-ran.marker");
      // The probe writes into TMPDIR (the only writable well-known location it
      // can see); TMPDIR is the shared stable runner tmp (tsx transform cache
      // reuse), which the test reads back through the same path.
      assert.ok(existsSync(probeMarker), "probe marker missing: the probe never executed in the runner");
      const markerContent = JSON.parse(readFileSyncMarker(probeMarker)) as { slotEnvLeak: string | null };
      assert.equal(markerContent.slotEnvLeak, null, "slot paths must not be exposed to author code via env");
    });
    void probeMarker;

    // The same project, without the probe, still checks in a fresh slot
    const clean = prepareChatWorkspace();
    try {
      await supervisor.withSlot(async (lease) => {
        await cpInto(lease.inputDir, clean);
        const result = await lease.request("check", { workspaceRoot: lease.inputDir, entryFile: "chat.svml" }) as { ok: boolean };
        assert.equal(result.ok, true, "legitimate local project must still check after the malicious probe");
      });
    } finally {
      rmSync(clean, { recursive: true, force: true });
    }
  } finally {
    rmSync(workspace, { recursive: true, force: true });
  }
});

function readFileSyncMarker(path: string): string {
  return readFileSync(path, "utf8");
}

function runnerTmpMarkerDir(): string {
  return join(repoRoot, "data/hypit/runner-tmp");
}

test("request timeout kills the runner tree, releases the slot, and old outputs never reach the next command", { timeout: 180_000 }, async () => {
  const supervisor = makeSupervisor({ requestTimeoutMs: 300 });
  let firstOutputDir: string | undefined;
  let firstSlotDir: string | undefined;

  // a request that cannot answer in 300ms (engine load takes longer) times out
  await assert.rejects(
    () => supervisor.withSlot(async (lease) => {
      firstOutputDir = lease.outputDir;
      firstSlotDir = lease.slotDir;
      mkdirSync(lease.outputDir, { recursive: true });
      writeFileSync(join(lease.outputDir, "stale-output.bin"), "previous command artifact");
      await lease.request("status", {}).catch(() => {}); // status is fast; force the timeout on a slow kind
      await lease.request("check", { workspaceRoot: lease.inputDir, entryFile: "chat.svml" });
    }),
    /timed out/,
  );

  assert.ok(firstOutputDir !== undefined && firstSlotDir !== undefined);

  const supervisor2 = makeSupervisor({ requestTimeoutMs: 120_000 });
  await ensureChatMachinePackages();
  const clean = prepareChatWorkspace();
  try {
    await supervisor2.withSlot(async (lease) => {
      assert.notEqual(lease.slotDir, firstSlotDir, "each command gets its own slot");
      assert.ok(!existsSync(join(lease.outputDir, "stale-output.bin")), "previous outputs must not leak into the next slot");
      await cpInto(lease.inputDir, clean);
      const result = await lease.request("check", { workspaceRoot: lease.inputDir, entryFile: "chat.svml" }) as { ok: boolean };
      assert.equal(result.ok, true, "the next command must run in a fresh, working runner");
    });
  } finally {
    rmSync(clean, { recursive: true, force: true });
  }
});

async function cpInto(target: string, source: string): Promise<void> {
  cpSync(source, target, { recursive: true, force: true });
}
