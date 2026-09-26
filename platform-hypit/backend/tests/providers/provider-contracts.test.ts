// provider-contracts.test.ts — C107-07 (task-107) real request adapters against
// a fixture remote provider (07.9/TC107-07-02). CONTRACT_PASS runs fully local
// against fixtures/fake-provider-server.mjs; LIVE_PASS stays a separate honest
// gate (no live credentials in this environment).
import { createHash } from "node:crypto";
import { strict as assert } from "node:assert";
import { createServer } from "node:http";
import type { AddressInfo } from "node:net";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";

import type { EndpointRegistry, MemoryResourceStore } from "@hypit/driver-node";
import type { Need } from "@hypit/protocol";

import { installEngineResolution } from "../../src/engine/hypit-bootstrap.ts";
import { describeProviderCatalog } from "../../src/providers/catalog.ts";
import { openCredentialStores } from "../../src/providers/credentials.ts";
import { startAssetTransport } from "../../src/providers/asset-transport.ts";
import { activateTrustedEndpoint, withAuthorizationGuard } from "../../src/providers/activation-hooks.ts";
import { httpExecutionBridge, ExecutionAuthorizer } from "../../src/providers/authorization.ts";

export const CONTRACT_PASS = true;

const distributionRoot = join(import.meta.dirname, "../../../.generated/hypit");

type FakeProvider = {
  readonly url: string;
  close(): Promise<void>;
  failBearerCalls(count: number): void;
  setPollsBeforeDone(count: number): void;
  readonly calls: ReadonlyArray<{ readonly path: string; readonly method: string; readonly idempotencyKey?: string; readonly authorization?: string; readonly body?: unknown }>;
  readonly state: {
    readonly fetchedReferenceHashes: string[];
    readonly submittedBodies: Record<string, unknown>[];
    readonly bearerTokens: string[];
  };
};

async function startFakeProvider(): Promise<FakeProvider> {
  const specifier = "../../../fixtures/fake-provider-server.mjs";
  const module_ = (await import(specifier)) as { startFakeProvider: () => Promise<FakeProvider> };
  return await module_.startFakeProvider();
}

type Engine = {
  readonly EndpointRegistry: new () => EndpointRegistry;
  readonly MemoryResourceStore: new () => MemoryResourceStore;
  readonly sealSeedanceRequest: typeof import("@hypit/seedance").sealSeedanceRequest;
  readonly seedanceEndpoints: typeof import("@hypit/seedance").seedanceEndpoints;
};

let engineLoading: Promise<Engine> | undefined;

function engine(): Promise<Engine> {
  if (engineLoading === undefined) {
    engineLoading = (async () => {
      await installEngineResolution(distributionRoot);
      const driver = await import("@hypit/driver-node");
      const seedance = await import("@hypit/seedance");
      return {
        EndpointRegistry: driver.EndpointRegistry,
        MemoryResourceStore: driver.MemoryResourceStore,
        sealSeedanceRequest: seedance.sealSeedanceRequest,
        seedanceEndpoints: seedance.seedanceEndpoints,
      } as Engine;
    })();
  }
  return engineLoading;
}

function seedanceNeed(mod: Engine, reference: import("@hypit/protocol").BlobRef): Need {
  return {
    id: "need:contract-test",
    capability: mod.seedanceEndpoints.mini!.capability,
    returns: mod.seedanceEndpoints.mini!.returns,
    constraints: mod.sealSeedanceRequest("seedance-2-mini", {
      prompt: ["A presenter turns toward camera."],
      referenceImage: [{ role: "image", artifact: reference, fields: { personReference: true } }],
      resolution: ["720p"],
      aspectRatio: ["16:9"],
      duration: [5],
      generateAudio: [false],
      webSearch: [false],
    }) as unknown as Need["constraints"],
    result: "record:contract-test",
  } as Need;
}

type ProviderCatalogEntry = Awaited<ReturnType<typeof describeProviderCatalog>>[number];

async function testBed(): Promise<{
  readonly mod: Engine;
  readonly hostRoot: string;
  readonly stores: Awaited<ReturnType<typeof openCredentialStores>>;
  readonly fake: FakeProvider;
  readonly transport: Awaited<ReturnType<typeof startAssetTransport>>;
  readonly hubDescriptor: ProviderCatalogEntry;
}> {
  const mod = await engine();
  const hostRoot = mkdtempSync(join(tmpdir(), "hypit-contract-"));
  const stores = await openCredentialStores(distributionRoot, hostRoot);
  const fake = await startFakeProvider();
  const transport = await startAssetTransport();
  const catalog = await describeProviderCatalog(distributionRoot);
  const hubDescriptor = catalog.find((d) => d.defaultEndpointId === "hypihub.default")!;
  return { mod, hostRoot, stores, fake, transport, hubDescriptor };
}

type AsyncRegistration = {
  readonly kind: "asynchronous";
  readonly endpoint: {
    start(context: unknown): Promise<{ status: string; handle?: unknown; failure?: { message?: string } }>;
    poll(context: unknown): Promise<{ status: string; handle?: unknown }>;
    collect?(context: unknown): Promise<{ status: string; result?: unknown }>;
  };
};

function asyncEndpointOf(registry: EndpointRegistry, request: Need): AsyncRegistration {
  const resolution = registry.resolve(request);
  assert.equal(resolution.status, "resolved", "capability resolves against the provider");
  assert.equal(resolution.registration.kind, "asynchronous");
  return resolution.registration as AsyncRegistration;
}

test("provider contract: authorized HypiHub lifecycle — public references, idempotent submit, one prepare", async () => {
  const bed = await testBed();
  try {
    await bed.stores.put({ store: "file", key: "hypihub.default.apiKey" }, "test-key");
    const activation = await activateTrustedEndpoint({
      distributionRoot,
      descriptor: bed.hubDescriptor,
      config: {
        endpointId: "hypihub.default",
        baseUrl: bed.fake.url,
        credential: { store: "file", key: "hypihub.default.apiKey" },
      },
      credentials: bed.stores,
      publicAssetUrl: bed.transport.publish as never,
    });

    const resources = new bed.mod.MemoryResourceStore();
    const referenceBytes = new Uint8Array([21, 22, 23, 24, 25, 26, 27, 28]);
    const reference = await resources.put(referenceBytes, "image/png");
    const request = seedanceNeed(bed.mod, reference);

    const registry = new bed.mod.EndpointRegistry();
    const bridge = await stubBridge();
    try {
      const authorizer = new ExecutionAuthorizer(httpExecutionBridge({ baseUrl: bridge.url, token: "t" }));
      const operationId = "op-contract-1";
      const guarded = withAuthorizationGuard(registry, authorizer, (operation) => operation === operationId
        ? {
            operationId,
            needId: "need:contract-test",
            capability: "@hypit/seedance@1#seedance-2-mini",
            model: "seedance-2-mini",
            endpointId: "hypihub.default",
            requestHash: "f".repeat(64),
            grantId: "g-1",
          }
        : undefined);
      await activation.pkg.install(guarded);

      const registration = asyncEndpointOf(registry, request);
      const credentials = await activation.invocationCredentials();
      assert.ok("apiKey" in credentials, "trusted side resolves the declared slot");
      assert.equal(credentials.apiKey!.secret, "test-key");

      bed.fake.setPollsBeforeDone(0);
      const started = await registration.endpoint.start({
        command: { kind: "fulfill-need", id: "command:1", need: request },
        need: request,
        resources,
        credentials,
        operation: operationId,
      });
      assert.equal(started.status, "pending", "queued job wakes for polling");
      assert.ok(started.handle !== undefined);

      // The fake "remote service" fetched the reference URL and got the same bytes.
      await waitFor(() => bed.fake.state.fetchedReferenceHashes.length >= 1);
      assert.equal(bed.fake.state.fetchedReferenceHashes[0], createHash("sha256").update(referenceBytes).digest("hex"),
        "remote GET of the publicAssetUrl returns the identical bytes (K12.9)");

      const submitCall = bed.fake.calls.find((call) => call.path === "/videos")!;
      assert.equal(submitCall.idempotencyKey, operationId, "stable operation idempotency-key");
      assert.equal(submitCall.authorization, "Bearer test-key");
      assert.equal((submitCall.body as Record<string, unknown>).model, "seedance-2-mini");
      assert.equal(bed.fake.calls.filter((call) => call.path.includes("/files/uploads")).length, 0,
        "publicAssetUrl replaces the native uploader entirely");

      const pollContext = {
        command: { kind: "fulfill-need", id: "command:1", need: request },
        need: request,
        resources,
        credentials,
        operation: operationId,
        handle: started.handle,
      };
      const polled = await registration.endpoint.poll(pollContext);
      assert.equal(polled.status, "ready");

      const collected = await registration.endpoint.collect!(pollContext);
      assert.equal(collected.status, "completed");
      assert.equal(bridge.prepared.length, 1, "exactly one prepare for the whole lifecycle");
      assert.equal(bridge.settled.length, 0, "settlement is the caller's explicit step");
      // Polling did not book anything new: still one prepare.
      assert.equal(bridge.prepared.length, 1);
    } finally {
      await bridge.close();
    }
  } finally {
    await bed.transport.close();
    await bed.fake.close();
    rmSync(bed.hostRoot, { recursive: true, force: true });
  }
});

test("provider contract: without a permit the real submit count is zero", async () => {
  const bed = await testBed();
  try {
    await bed.stores.put({ store: "file", key: "hypihub.default.apiKey" }, "test-key");
    const activation = await activateTrustedEndpoint({
      distributionRoot,
      descriptor: bed.hubDescriptor,
      config: { endpointId: "hypihub.default", baseUrl: bed.fake.url, credential: { store: "file", key: "hypihub.default.apiKey" } },
      credentials: bed.stores,
      publicAssetUrl: bed.transport.publish as never,
    });
    const resources = new bed.mod.MemoryResourceStore();
    const reference = await resources.put(new Uint8Array([1, 2]), "image/png");
    const request = seedanceNeed(bed.mod, reference);

    const denied = await stubBridge({ deny: true });
    try {
      const authorizer = new ExecutionAuthorizer(httpExecutionBridge({ baseUrl: denied.url, token: "t" }));
      const registry = new bed.mod.EndpointRegistry();
      const guarded = withAuthorizationGuard(registry, authorizer, () => ({
        operationId: "op-denied",
        needId: "n",
        capability: "c",
        model: "m",
        endpointId: "hypihub.default",
        requestHash: "0".repeat(64),
        grantId: "g",
      }));
      await activation.pkg.install(guarded);
      const registration = asyncEndpointOf(registry, request);
      const credentials = await activation.invocationCredentials();
      await assert.rejects(() => registration.endpoint.start({
        command: { kind: "fulfill-need", id: "c1", need: request },
        need: request,
        resources,
        credentials,
        operation: "op-denied",
      }));
      assert.equal(bed.fake.calls.filter((call) => call.path === "/videos").length, 0,
        "unauthorized operations never reach the provider (submit=0)");
      assert.equal(bed.fake.calls.filter((call) => call.path.startsWith("/models/")).length, 0,
        "not even the catalogue probe runs without authorization");
    } finally {
      await denied.close();
    }
  } finally {
    await bed.transport.close();
    await bed.fake.close();
    rmSync(bed.hostRoot, { recursive: true, force: true });
  }
});

test("provider contract: expired OAuth envelope refreshes through the trusted replace authority", async () => {
  const bed = await testBed();
  try {
    const ref = { store: "file", key: "hypihub.default.apiKey" };
    await bed.stores.put(ref, JSON.stringify({
      format: "hypit.oauth2-credential@1",
      accessToken: "stale",
      refreshToken: "fake-refresh",
      expiresAt: 1,
    }));

    const activation = await activateTrustedEndpoint({
      distributionRoot,
      descriptor: bed.hubDescriptor,
      config: { endpointId: "hypihub.default", baseUrl: bed.fake.url, credential: ref },
      credentials: bed.stores,
      publicAssetUrl: bed.transport.publish as never,
    });
    const credentials = await activation.invocationCredentials();
    assert.ok(typeof credentials.apiKey!.replace === "function", "writable slots expose the narrow replace authority");

    // Pre-emptive refresh (expiresAt in the past) plus one bounced API call:
    // both refresh paths run against the fake /oauth/token, replace persists
    // the rotated envelope, and every generation API call carries the fresh token.
    bed.fake.failBearerCalls(1);
    const registry = new bed.mod.EndpointRegistry();
    await activation.pkg.install(registry);
    const resources = new bed.mod.MemoryResourceStore();
    const reference = await resources.put(new Uint8Array([9, 9, 9]), "image/png");
    const request = seedanceNeed(bed.mod, reference);
    const registration = asyncEndpointOf(registry, request);
    const started = await registration.endpoint.start({
      command: { kind: "fulfill-need", id: "c2", need: request },
      need: request,
      resources,
      credentials,
      operation: "op-oauth",
    });
    assert.equal(started.status, "pending", "refresh unblocks the submit");
    assert.ok(bed.fake.state.bearerTokens.length > 0);
    assert.ok(bed.fake.state.bearerTokens.every((token) => token === "Bearer fake-access"),
      "no stale token ever reached the generation API");

    const stored = await bed.stores.composite.resolve(ref);
    assert.ok(stored!.secret.includes("fake-access"), "replace wrote the rotated envelope back to the store");
    const status = await bed.stores.status(ref);
    assert.equal(status.credentialType, "oauth2");
  } finally {
    await bed.transport.close();
    await bed.fake.close();
    rmSync(bed.hostRoot, { recursive: true, force: true });
  }
});

test("provider contract: unsupported capability never resolves", async () => {
  const bed = await testBed();
  try {
    await bed.stores.put({ store: "file", key: "hypihub.default.apiKey" }, "test-key");
    const activation = await activateTrustedEndpoint({
      distributionRoot,
      descriptor: bed.hubDescriptor,
      config: { endpointId: "hypihub.default", baseUrl: bed.fake.url, credential: { store: "file", key: "hypihub.default.apiKey" } },
      credentials: bed.stores,
    });
    const registry = new bed.mod.EndpointRegistry();
    await activation.pkg.install(registry);
    const bogus = {
      id: "need:bogus",
      capability: { module: { name: "@hypit/not-a-model", version: "1" }, name: "nope" },
      returns: { module: { name: "@hypit/generation", version: "1" }, name: "GeneratedVideoSet" },
      constraints: {},
      result: "record:bogus",
    } as unknown as Need;
    const resolution = registry.resolve(bogus);
    assert.notEqual(resolution.status, "resolved", "no fake support for unlisted capabilities");
  } finally {
    await bed.transport.close();
    await bed.fake.close();
    rmSync(bed.hostRoot, { recursive: true, force: true });
  }
});

test("provider contract: LIVE_PASS — real HypiHub needs operator credentials (EXTERNAL_BLOCKED)", async (t) => {
  const live = process.env.HYPIT_LIVE_HYPIHUB_BASE_URL;
  const key = process.env.HYPIT_LIVE_HYPIHUB_API_KEY;
  if (live === undefined || key === undefined) {
    t.skip("LIVE_PASS requires HYPIT_LIVE_HYPIHUB_BASE_URL and HYPIT_LIVE_HYPIHUB_API_KEY (operator-provided; CONTRACT_PASS above is the local gate)");
    return;
  }
  const bed = await testBed();
  try {
    await bed.stores.put({ store: "file", key: "hypihub.default.apiKey" }, key);
    const activation = await activateTrustedEndpoint({
      distributionRoot,
      descriptor: bed.hubDescriptor,
      config: { endpointId: "hypihub.default", baseUrl: live, credential: { store: "file", key: "hypihub.default.apiKey" } },
      credentials: bed.stores,
      publicAssetUrl: bed.transport.publish as never,
    });
    const registry = new bed.mod.EndpointRegistry();
    await activation.pkg.install(registry);
    const resources = new bed.mod.MemoryResourceStore();
    const reference = await resources.put(new Uint8Array([1, 2, 3]), "image/png");
    const request = seedanceNeed(bed.mod, reference);
    const resolution = registry.resolve(request);
    assert.equal(resolution.status, "resolved");
  } finally {
    await bed.transport.close();
    await bed.fake.close();
    rmSync(bed.hostRoot, { recursive: true, force: true });
  }
});

async function stubBridge(options: { deny?: boolean } = {}): Promise<{
  readonly url: string;
  readonly prepared: Record<string, unknown>[];
  readonly settled: Record<string, unknown>[];
  close(): Promise<void>;
}> {
  const prepared: Record<string, unknown>[] = [];
  const settled: Record<string, unknown>[] = [];
  const server = createServer((request, response) => {
    const chunks: Buffer[] = [];
    request.on("data", (chunk) => chunks.push(chunk));
    request.on("end", () => {
      const path = request.url ?? "";
      const body = chunks.length === 0 ? {} : JSON.parse(Buffer.concat(chunks).toString("utf8")) as Record<string, unknown>;
      if (path.endsWith("/prepare")) {
        prepared.push(body);
        if (options.deny) {
          response.writeHead(409, { "content-type": "application/json" });
          response.end(JSON.stringify({ code: "grant_exhausted", message: "no budget" }));
        } else {
          response.writeHead(200, { "content-type": "application/json" });
          response.end(JSON.stringify({
            permitId: "permit-contract",
            expiresAt: new Date(Date.now() + 300_000).toISOString(),
            credentialRef: { store: "file", key: "hypihub.default.apiKey" },
          }));
        }
        return;
      }
      settled.push(body);
      response.writeHead(200, { "content-type": "application/json" });
      response.end("{\"settled\":true}");
    });
  });
  await new Promise<void>((resolvePromise) => server.listen(0, "127.0.0.1", resolvePromise));
  const address = server.address() as AddressInfo;
  return {
    url: `http://127.0.0.1:${String(address.port)}`,
    prepared,
    settled,
    close: () => new Promise<void>((resolvePromise) => {
      server.closeIdleConnections?.();
      server.close(() => resolvePromise());
    }),
  };
}

async function waitFor(condition: () => boolean, attempts = 100): Promise<void> {
  for (let index = 0; index < attempts; index += 1) {
    if (condition()) return;
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 25));
  }
  assert.ok(condition(), "condition never became true");
}
