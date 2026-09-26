// internal-auth.test.ts — C107-03 (task-107) internal token boundary (TC107-03-01/E06).
//
// The backend's /internal endpoints must reject missing and wrong bearer
// tokens, accept the configured one, and never log the authorization header.
process.env.HYPIT_STATE_HOME ??= join(import.meta.dirname, "../../../../data/hypit/runner-state");

import { spawn } from "node:child_process";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { setTimeout as delay } from "node:timers/promises";
import assert from "node:assert/strict";
import test from "node:test";

const backendRoot = join(import.meta.dirname, "../..");
const generatedRoot = join(backendRoot, "../.generated/hypit");
const dataRoot = mkdtempSync(join(tmpdir(), "hypit-auth-"));
const token = "internal-auth-test-token-0123456789abcdef";
const port = 9253;

test("internal endpoints reject missing/wrong tokens and never log them", { timeout: 120_000 }, async (t) => {
  const logChunks: Buffer[] = [];
  const child = spawn(process.execPath, ["--import", "tsx", "src/main.mjs"], {
    cwd: backendRoot,
    env: {
      ...process.env,
      HYPIT_BACKEND_PORT: String(port),
      HYPIT_INTERNAL_TOKEN: token,
      HYPIT_DATA_ROOT: join(dataRoot, "host"),
      HYPIT_GENERATED_ROOT: generatedRoot,
    },
    stdio: ["ignore", "pipe", "pipe"],
  });
  const logs: string[] = [];
  child.stdout.on("data", (chunk: Buffer) => logChunks.push(chunk));
  child.stderr.on("data", (chunk: Buffer) => logChunks.push(chunk));
  t.after(() => {
    child.kill("SIGTERM");
    rmSync(dataRoot, { recursive: true, force: true });
  });

  let ready = false;
  // Engine bootstrap under full-suite parallel load can exceed 20s (C107-05:
  // two server-spawning engine tests raised it past the old 50s window); keep
  // the assertion identical, widen the readiness window (standalone ~14s).
  for (let attempt = 0; attempt < 400 && !ready; attempt += 1) {
    ready = await fetch(`http://127.0.0.1:${port}/healthz`)
      .then((response) => response.ok)
      .catch(() => false);
    if (!ready) await delay(250);
  }
  assert.ok(ready, "server did not become healthy");

  const noToken = await fetch(`http://127.0.0.1:${port}/internal/v1/commands`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ commandId: "c1", kind: "status", payload: {} }),
  });
  assert.equal(noToken.status, 401, "missing token must be 401");

  const wrongToken = await fetch(`http://127.0.0.1:${port}/internal/v1/commands`, {
    method: "POST",
    headers: { "content-type": "application/json", authorization: "Bearer definitely-wrong-token" },
    body: JSON.stringify({ commandId: "c1", kind: "status", payload: {} }),
  });
  assert.equal(wrongToken.status, 401, "wrong token must be 401");

  const accepted = await fetch(`http://127.0.0.1:${port}/internal/v1/commands`, {
    method: "POST",
    headers: { "content-type": "application/json", authorization: `Bearer ${token}` },
    body: JSON.stringify({ commandId: "c1", kind: "status", payload: {} }),
  });
  assert.equal(accepted.status, 200, "configured token must be accepted");
  const command = await accepted.json() as { state: string };
  assert.equal(command.state, "succeeded");

  // give logs a moment, then assert the secret never appears in process output
  await delay(500);
  const allLogs = Buffer.concat(logChunks).toString("utf8");
  assert.ok(!allLogs.includes(token), "internal token must never appear in logs");
  assert.ok(!allLogs.includes("Bearer "), "authorization values must never appear in logs");
});
