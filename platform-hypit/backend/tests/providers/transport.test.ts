// transport.test.ts — C107-07 (task-107) publicAssetUrl transport (07.7/K12.9).
//
// Published objects are byte-identical, unguessable, ETagged by content hash,
// deduplicated per resource, bounded by the byte budget, and expire.
import { createHash } from "node:crypto";
import { strict as assert } from "node:assert";
import test from "node:test";

import { startAssetTransport, AssetTransportError } from "../../src/providers/asset-transport.ts";

type MemoryStore = {
  readonly data: Map<string, Uint8Array>;
  get(resource: string): Promise<Uint8Array | undefined>;
};

function memoryStore(entries: Record<string, Uint8Array> = {}): MemoryStore {
  const data = new Map(Object.entries(entries));
  return { data, async get(resource) { return data.get(resource); } };
}

test("transport: publish serves the exact bytes with sha256 ETag; tokens are unguessable", async () => {
  const bytes = new Uint8Array([9, 8, 7, 6, 5]);
  const store = memoryStore({ res_one: bytes });
  const transport = await startAssetTransport();
  try {
    const url = await transport.publish({ resource: "res_one", mediaType: "image/png" }, store);
    assert.ok(url.startsWith(`${transport.baseUrl}/`));
    assert.ok(url.length > transport.baseUrl.length + 40, "path carries a long random token");

    const response = await globalThis.fetch(url);
    assert.equal(response.status, 200);
    assert.equal(response.headers.get("content-type"), "image/png");
    assert.equal(response.headers.get("etag"), `"${createHash("sha256").update(bytes).digest("hex")}"`);
    assert.deepEqual(new Uint8Array(await response.arrayBuffer()), bytes, "remote side GETs the same bytes");

    const head = await globalThis.fetch(url, { method: "HEAD" });
    assert.equal(head.status, 200);
    assert.equal(head.headers.get("content-length"), "5");

    const same = await globalThis.fetch(url, { headers: { "if-none-match": response.headers.get("etag")! } });
    assert.equal(same.status, 304);

    const rejected = await globalThis.fetch(`${transport.baseUrl}/${"a".repeat(43)}`, { method: "POST" });
    assert.ok(rejected.status === 404 || rejected.status === 405);
  } finally {
    await transport.close();
  }
});

test("transport: same resource republishes the same URL; different resources differ", async () => {
  const store = memoryStore({ res_a: new Uint8Array([1]), res_b: new Uint8Array([2]) });
  const transport = await startAssetTransport();
  try {
    const first = await transport.publish({ resource: "res_a" }, store);
    const second = await transport.publish({ resource: "res_a" }, store, { personReference: true });
    assert.equal(first, second, "stable URL per resource (upstream dedup semantics)");
    assert.equal(transport.publishedCount(), 1);
    const other = await transport.publish({ resource: "res_b" }, store);
    assert.notEqual(other, first);
    assert.equal(transport.publishedCount(), 2);
  } finally {
    await transport.close();
  }
});

test("transport: unreadable references and the byte budget are enforced", async () => {
  const store = memoryStore({ res_big: new Uint8Array(2048) });
  const transport = await startAssetTransport({ maxTotalBytes: 2048, ttlMs: 60_000 });
  try {
    await assert.rejects(() => transport.publish({ resource: "res_missing" }, store),
      (error: unknown) => (error as AssetTransportError).code === "resource_unavailable");
    await transport.publish({ resource: "res_big" }, store);
    await assert.rejects(() => transport.publish({ resource: "res_other" }, memoryStore({ res_other: new Uint8Array(2048) })),
      (error: unknown) => (error as AssetTransportError).code === "capacity_exceeded");
  } finally {
    await transport.close();
  }
});

test("transport: objects disappear after their TTL", async () => {
  const clock = { value: Date.now() };
  const store = memoryStore({ res_x: new Uint8Array([42]) });
  const transport = await startAssetTransport({ ttlMs: 1_000, now: () => clock.value });
  try {
    const url = await transport.publish({ resource: "res_x" }, store);
    assert.equal((await globalThis.fetch(url)).status, 200);
    clock.value += 2_000;
    assert.equal((await globalThis.fetch(url)).status, 404, "expired objects are gone");
    assert.equal(transport.publishedCount(), 0, "reaping drops them from the index");
    const revived = await transport.publish({ resource: "res_x" }, store);
    assert.notEqual(revived, url, "a fresh publish mints a fresh token");
  } finally {
    await transport.close();
  }
});
