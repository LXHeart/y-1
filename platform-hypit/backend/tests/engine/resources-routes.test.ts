// resources-routes.test.ts — C107-05 (task-107) internal resource routes (K07/K09).
//
// POST /internal/v1/resources must stream an upload into the controlled root
// and return {handle, sha256, sizeBytes}; GET /internal/v1/resources/{handle}
// must implement RFC 9110 single-range serving with a strong sha256 ETag,
// including If-Range downgrade to a full 200 on validator mismatch.
process.env.HYPIT_STATE_HOME ??= join(import.meta.dirname, "../../../../data/hypit/runner-state");

import { spawn } from "node:child_process";
import { createHash } from "node:crypto";
import { existsSync, mkdtempSync, readFileSync, readdirSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { setTimeout as delay } from "node:timers/promises";
import assert from "node:assert/strict";
import test from "node:test";

const backendRoot = join(import.meta.dirname, "../..");
const generatedRoot = join(backendRoot, "../.generated/hypit");
const dataRoot = mkdtempSync(join(tmpdir(), "hypit-resources-"));
const resourcesRoot = join(dataRoot, "resources");
const token = "internal-resources-test-token-0123456789";
const port = 9257;

const PAYLOAD = Buffer.from("Hypit resource route fixture — Range serving bytes 0..63\n".repeat(2));
const SHA256 = createHash("sha256").update(PAYLOAD).digest("hex");

test("internal resource routes: ingest + range serve + If-Range", { timeout: 120_000 }, async (t) => {
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
  t.after(() => {
    child.kill("SIGTERM");
    rmSync(dataRoot, { recursive: true, force: true });
  });

  let ready = false;
  for (let attempt = 0; attempt < 400 && !ready; attempt += 1) {
    ready = await fetch(`http://127.0.0.1:${port}/healthz`).then((r) => r.ok).catch(() => false);
    if (!ready) await delay(250);
  }
  assert.ok(ready, "server did not become healthy");
  const base = { authorization: `Bearer ${token}` };

  await t.test("ingest streams to uploads and registers a handle", async () => {
    const response = await fetch(`http://127.0.0.1:${port}/internal/v1/resources`, {
      method: "POST",
      headers: { ...base, "content-type": "text/plain", "x-hypit-file-name": "fixture.txt" },
      body: new Uint8Array(PAYLOAD),
    });
    assert.equal(response.status, 200);
    const receipt = await response.json() as { handle: string; sha256: string; sizeBytes: number };
    assert.match(receipt.handle, /^res-[0-9a-f]{16}-[0-9a-z]+$/u);
    assert.equal(receipt.sha256, SHA256);
    assert.equal(receipt.sizeBytes, PAYLOAD.length);
    const uploads = join(resourcesRoot, "uploads");
    const stored = readdirSync(uploads).filter((name) => !name.startsWith("."));
    assert.equal(stored.length, 1, "exactly one uploaded file");
    assert.ok(stored[0]!.endsWith("fixture.txt"), "server-controlled name keeps the provided basename");
    assert.deepEqual(readFileSync(join(uploads, stored[0]!)), PAYLOAD);
  });

  let handle = "";
  {
    const response = await fetch(`http://127.0.0.1:${port}/internal/v1/resources`, {
      method: "POST",
      headers: { ...base, "content-type": "text/plain" },
      body: new Uint8Array(PAYLOAD),
    });
    const receipt = await response.json() as { handle: string };
    handle = receipt.handle;
  }

  await t.test("GET serves the full body with ETag and Accept-Ranges", async () => {
    const response = await fetch(`http://127.0.0.1:${port}/internal/v1/resources/${handle}`, { headers: base });
    assert.equal(response.status, 200);
    assert.equal(response.headers.get("accept-ranges"), "bytes");
    assert.equal(response.headers.get("etag"), `"${SHA256}"`);
    assert.equal(response.headers.get("content-length"), String(PAYLOAD.length));
    assert.deepEqual(Buffer.from(await response.arrayBuffer()), PAYLOAD);
  });

  await t.test("single range returns 206 with exact bytes", async () => {
    const response = await fetch(`http://127.0.0.1:${port}/internal/v1/resources/${handle}`, {
      headers: { ...base, range: "bytes=4-17" },
    });
    assert.equal(response.status, 206);
    assert.equal(response.headers.get("content-range"), `bytes 4-17/${PAYLOAD.length}`);
    assert.deepEqual(Buffer.from(await response.arrayBuffer()), PAYLOAD.subarray(4, 18));
  });

  await t.test("suffix and open-ended range forms are served", async () => {
    const suffix = await fetch(`http://127.0.0.1:${port}/internal/v1/resources/${handle}`, {
      headers: { ...base, range: "bytes=-16" },
    });
    assert.equal(suffix.status, 206);
    assert.deepEqual(Buffer.from(await suffix.arrayBuffer()), PAYLOAD.subarray(-16));
    const open = await fetch(`http://127.0.0.1:${port}/internal/v1/resources/${handle}`, {
      headers: { ...base, range: `bytes=${PAYLOAD.length - 8}-` },
    });
    assert.equal(open.status, 206);
    assert.deepEqual(Buffer.from(await open.arrayBuffer()), PAYLOAD.subarray(-8));
  });

  await t.test("out-of-bounds range is 416, multi-range is refused", async () => {
    const beyond = await fetch(`http://127.0.0.1:${port}/internal/v1/resources/${handle}`, {
      headers: { ...base, range: `bytes=${PAYLOAD.length}-` },
    });
    assert.equal(beyond.status, 416);
    assert.equal(beyond.headers.get("content-range"), `bytes */${PAYLOAD.length}`);
    const multi = await fetch(`http://127.0.0.1:${port}/internal/v1/resources/${handle}`, {
      headers: { ...base, range: "bytes=0-1,4-5" },
    });
    assert.ok(multi.status === 416 || multi.status === 200, "multi-range degrades, never mis-serves 206");
    if (multi.status === 200) {
      assert.deepEqual(Buffer.from(await multi.arrayBuffer()), PAYLOAD, "degraded multi-range must be the full body");
    }
  });

  await t.test("If-Range matching ETag honors the range; mismatch downgrades to 200", async () => {
    const honored = await fetch(`http://127.0.0.1:${port}/internal/v1/resources/${handle}`, {
      headers: { ...base, range: "bytes=0-3", "if-range": `"${SHA256}"` },
    });
    assert.equal(honored.status, 206);
    const downgraded = await fetch(`http://127.0.0.1:${port}/internal/v1/resources/${handle}`, {
      headers: { ...base, range: "bytes=0-3", "if-range": '"stale-etag"' },
    });
    assert.equal(downgraded.status, 200);
    assert.deepEqual(Buffer.from(await downgraded.arrayBuffer()), PAYLOAD);
  });

  await t.test("empty body is rejected and leaves no file", async () => {
    const before = existsSync(join(resourcesRoot, "uploads"))
      ? readdirSync(join(resourcesRoot, "uploads")).filter((name) => !name.startsWith(".")).length
      : 0;
    const response = await fetch(`http://127.0.0.1:${port}/internal/v1/resources`, {
      method: "POST",
      headers: { ...base, "content-type": "text/plain", "x-hypit-file-name": "empty.txt" },
      body: new Uint8Array(0),
    });
    assert.equal(response.status, 400);
    const after = readdirSync(join(resourcesRoot, "uploads")).filter((name) => !name.startsWith(".")).length;
    assert.equal(after, before, "no partial or empty file may survive a rejected upload");
  });

  await t.test("unknown/malformed handles are 404/400", async () => {
    const unknown = await fetch(`http://127.0.0.1:${port}/internal/v1/resources/res-0000000000000000-0`, { headers: base });
    assert.equal(unknown.status, 404);
    const malformed = await fetch(`http://127.0.0.1:${port}/internal/v1/resources/../../etc/passwd`, { headers: base });
    assert.ok(malformed.status === 400 || malformed.status === 404, "path traversal must never resolve to a file");
  });
});
