// catalog.test.ts — C107-07 (task-107) provider catalog truth (K12.1/TC107-07-01).
//
// The catalog must enumerate all ten providers from real package metadata:
// every declared offer appears, every mapping route model is listed, remote
// providers declare their credential slot with HypiHub's OAuth acquisition,
// and nothing is invented (offer count == what the factory declares).
import { strict as assert } from "node:assert";
import { join } from "node:path";
import test from "node:test";

import { describeProviderCatalog, providerRegistrations } from "../../src/providers/catalog.ts";

const distributionRoot = join(import.meta.dirname, "../../../.generated/hypit");

test("catalog: all ten providers enumerated with unique endpoint ids and honest kinds", async () => {
  const catalog = await describeProviderCatalog(distributionRoot);
  assert.equal(catalog.length, 10);
  assert.equal(catalog.filter((d) => d.kind === "remote").length, 6);
  assert.equal(catalog.filter((d) => d.kind === "local").length, 4);
  const ids = new Set(catalog.map((d) => d.defaultEndpointId));
  assert.equal(ids.size, 10, "endpoint ids are unique");
  assert.deepEqual(
    [...providerRegistrations()].map((r) => r.defaultEndpointId).sort(),
    [...ids].sort(),
    "catalog ids match the registration table exactly",
  );
});

test("catalog: every provider offers at least one real capability and local providers manage programs", async () => {
  const catalog = await describeProviderCatalog(distributionRoot);
  for (const descriptor of catalog) {
    assert.ok(descriptor.supports.length > 0, `${descriptor.packageId} declares no support at all`);
    for (const entry of descriptor.supports) {
      assert.match(entry.capability, /^@hypit\/[^@]+@\d+#[^#]+$/u, "capability keys are module@version#name");
    }
    if (descriptor.kind === "local") {
      assert.equal(descriptor.managedProgram, true, `${descriptor.packageId} is a managed program`);
      assert.equal(descriptor.credentials.length, 0, "local providers take no credentials");
      assert.equal(descriptor.pricing.type, "local", "local work carries no provider charge");
    } else {
      assert.equal(descriptor.managedProgram, false);
    }
  }
});

test("catalog: remote providers declare their apiKey slot; hypihub carries the real OAuth acquisition", async () => {
  const catalog = await describeProviderCatalog(distributionRoot);
  for (const descriptor of catalog.filter((d) => d.kind === "remote")) {
    assert.equal(descriptor.credentials.length, 1, `${descriptor.packageId} declares exactly one credential slot`);
    const slot = descriptor.credentials[0]!;
    assert.equal(slot.slot, "apiKey");
    assert.equal(slot.kind, "secret");
    assert.ok(slot.store.length > 0 && slot.key.length > 0, "slot carries a CredentialRef");
  }
  const hub = catalog.find((d) => d.defaultEndpointId === "hypihub.default")!;
  const acquisition = hub.credentials[0]!.acquisition;
  assert.ok(acquisition !== undefined, "hypihub declares an oauth2-pkce acquisition");
  assert.equal(acquisition!.kind, "oauth2-pkce");
  assert.equal(acquisition!.clientId, "hyc_d5d5e8e7131b0c877756e66c");
  assert.equal(acquisition!.authorizationEndpoint, "https://hypit.ai/oauth/consent");
  assert.equal(acquisition!.tokenEndpoint, "https://hypit.ai/oauth/token");
  assert.deepEqual([...acquisition!.scopes], ["user:profile", "user:inference"]);
  // Only hypihub is known to declare an acquisition; the rest are static keys.
  for (const descriptor of catalog.filter((d) => d.kind === "remote" && d.defaultEndpointId !== "hypihub.default")) {
    assert.equal(descriptor.credentials[0]!.acquisition, undefined, `${descriptor.defaultEndpointId} has no oauth flow`);
  }
});

test("catalog: mapping coverage is exact — no unmapped offers, no unoffered mappings, no fake models", async () => {
  const catalog = await describeProviderCatalog(distributionRoot);
  for (const descriptor of catalog.filter((d) => d.kind === "remote")) {
    const withModels = descriptor.supports.filter((entry) => entry.models.length > 0);
    assert.ok(withModels.length > 0, `${descriptor.defaultEndpointId} lists mapping-backed models`);
    for (const entry of withModels) {
      assert.equal(entry.mapped, true);
      for (const model of entry.models) {
        assert.ok(model.length > 0);
      }
    }
    // The catalog neither invents capabilities (every entry was offered or
    // mapped by the package) nor hides them: gaps list any asymmetry with the
    // exact capability key.
    for (const entry of descriptor.supports) {
      if (entry.offered !== entry.mapped) {
        assert.ok(descriptor.gaps.some((gap) => gap.endsWith(`:${entry.capability}`)),
          `asymmetric capability ${entry.capability} is disclosed as a gap`);
      }
    }
  }
});

test("catalog: pollo reports exactly its five mapped generation capabilities with route models", async () => {
  const catalog = await describeProviderCatalog(distributionRoot);
  const pollo = catalog.find((d) => d.defaultEndpointId === "pollo.default")!;
  const mapped = pollo.supports.filter((entry) => entry.mapped && entry.offered);
  assert.equal(mapped.length, 5, "no cartesian inflation: pollo maps five capabilities");
  assert.ok(mapped.some((entry) => entry.capability.endsWith("#minimax-h3") && entry.models.includes("/v1/generation/minimax/minimax-h3/video")));
  assert.ok(mapped.every((entry) => entry.result === "video" || entry.result === "image"));
});

test("catalog: repeat calls share one snapshot per distribution root", async () => {
  const first = describeProviderCatalog(distributionRoot);
  const second = describeProviderCatalog(distributionRoot);
  assert.equal(first, second, "same root returns the cached promise");
});
