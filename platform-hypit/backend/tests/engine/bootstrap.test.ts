// bootstrap.test.ts — C107-02 (task-107) engine bootstrap contract (TC107-02-01 part).
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import assert from "node:assert/strict";
import test from "node:test";

import { installEngineResolution, loadHypit } from "../../src/engine/hypit-bootstrap.ts";

const generatedRoot = resolveGeneratedRoot();

function resolveGeneratedRoot(): string {
  const root = process.env.HYPIT_GENERATED_ROOT;
  if (root !== undefined && root.length > 0) return root;
  return join(import.meta.dirname, "../../../.generated/hypit");
}

test("loadHypit bootstraps the distribution and exposes the video assembly", async () => {
  const engine = await loadHypit(generatedRoot);
  assert.equal(engine.distributionRoot, await real(generatedRoot));
  assert.ok(engine.videoCliDistribution.packageRoot!.endsWith(".generated/hypit"));
  assert.equal(typeof engine.loadDiscoveredSourcePackages, "function");
  assert.equal(typeof engine.checkRunFile, "function");
  assert.equal(typeof engine.createCatalogDescriptor, "function");
  assert.equal(typeof engine.parseSourceHeader, "function");
});

test("loadHypit caches one root and refuses to switch roots in a live process", async () => {
  const again = await loadHypit(generatedRoot);
  assert.equal(again, await loadHypit(generatedRoot));
  const other = mkdtempSync(join(tmpdir(), "hypit-other-root-"));
  await assert.rejects(
    () => loadHypit(other),
    /refusing/,
  );
});

test("the resolution hooks resolve @hypit packages from G after bootstrap", async () => {
  await installEngineResolution(generatedRoot);
  const protocol = await import("@hypit/protocol");
  assert.equal(typeof protocol.orderedBuildId, "function");
  // Deterministic id: same timestamp+nonce => same id, no random hidden state.
  const nonce = "AAAAAAAAAA";
  assert.equal(protocol.orderedBuildId(0, nonce), protocol.orderedBuildId(0, nonce));
});

async function real(value: string): Promise<string> {
  const { realpathSync } = await import("node:fs");
  return realpathSync(value);
}
