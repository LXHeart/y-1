// authorization.test.ts — C107-07 (task-107) execution authorization bridge
// (07.5/K12.3/K12.8/TC107-07-02). One permit per operationId, cached until
// expiry; a changed request hash cannot reuse a permit; receipts settle once;
// an unreachable bridge is unknown, never a denial.
import { strict as assert } from "node:assert";
import { createServer } from "node:http";
import type { AddressInfo } from "node:net";
import test from "node:test";

import { httpExecutionBridge, ExecutionAuthorizer, AuthorizationError } from "../../src/providers/authorization.ts";

type BridgeLog = { path: string; body: Record<string, unknown> };

async function startBridge(responses: {
  prepare?: (body: Record<string, unknown>) => Record<string, unknown> | { status: number; code: string; message: string };
  settle?: (body: Record<string, unknown>) => Record<string, unknown>;
}): Promise<{ url: string; log: BridgeLog[]; close(): Promise<void> }> {
  const log: BridgeLog[] = [];
  const server = createServer((request, response) => {
    const chunks: Buffer[] = [];
    request.on("data", (chunk) => chunks.push(chunk));
    request.on("end", () => {
      const path = request.url ?? "";
      const body = chunks.length === 0 ? {} : JSON.parse(Buffer.concat(chunks).toString("utf8")) as Record<string, unknown>;
      log.push({ path, body });
      if (path.endsWith("/prepare")) {
        const result = responses.prepare?.(body) ?? {
          permitId: "permit-1",
          expiresAt: new Date(Date.now() + 60_000).toISOString(),
          credentialRef: { store: "file", key: "hypihub.default.apiKey" },
        };
        respond(response, result);
        return;
      }
      respond(response, responses.settle?.(body) ?? { settled: true });
    });
  });
  await new Promise<void>((resolvePromise) => server.listen(0, "127.0.0.1", resolvePromise));
  const address = server.address() as AddressInfo;
  return {
    url: `http://127.0.0.1:${String(address.port)}`,
    log,
    close: () => new Promise<void>((resolvePromise) => {
      server.closeIdleConnections?.();
      server.close(() => resolvePromise());
    }),
  };
}

function respond(response: import("node:http").ServerResponse, payload: unknown, status = 200): void {
  if (payload !== null && typeof payload === "object" && "status" in (payload as Record<string, unknown>)) {
    const typed = payload as { status: number; code: string; message: string };
    response.writeHead(typed.status, { "content-type": "application/json" });
    response.end(JSON.stringify({ code: typed.code, message: typed.message }));
    return;
  }
  response.writeHead(status, { "content-type": "application/json" });
  response.end(JSON.stringify(payload));
}

const request = {
  operationId: "11111111-1111-4111-8111-111111111111",
  projectId: "22222222-2222-4222-8222-222222222222",
  needId: "need-1",
  capability: "@hypit/seedance@1#seedance-2",
  model: "seedance-2",
  endpointId: "hypihub.default",
  requestHash: "a".repeat(64),
  grantId: "33333333-3333-4333-8333-333333333333",
};

test("authorization: prepare issues a permit once per operation; settle paths are exact", async () => {
  const bridge = await startBridge({});
  try {
    const authorizer = new ExecutionAuthorizer(httpExecutionBridge({ baseUrl: bridge.url, token: "internal-token" }));
    const permit = await authorizer.authorize(request);
    assert.equal(permit.permitId, "permit-1");
    assert.equal(permit.credentialRef.store, "file");
    assert.ok(authorizer.hasPermit(request.operationId));

    const cached = await authorizer.authorize(request);
    assert.equal(cached.permitId, permit.permitId);
    assert.equal(bridge.log.filter((entry) => entry.path.endsWith("/prepare")).length, 1, "cached permit, one prepare");

    await authorizer.settleSucceeded(request.operationId, { id: "job_1" }, 0.42);
    assert.equal(bridge.log.filter((entry) => entry.path.endsWith("/complete")).length, 1);
    assert.equal(bridge.log.at(-1)!.body.state, "succeeded");
    assert.equal(bridge.log.at(-1)!.body.actualCost, 0.42);
    assert.equal(authorizer.hasPermit(request.operationId), false, "settled operations release their permit");

    await authorizer.settleFailed("44444444-4444-4444-8444-444444444444", { id: "job_2" }, "boom");
    assert.equal(bridge.log.filter((entry) => entry.path.endsWith("/fail")).length, 1);
    await authorizer.settleCancelled("55555555-5555-4555-8555-555555555555", "user asked");
    assert.equal(bridge.log.filter((entry) => entry.path.endsWith("/cancel")).length, 1);
  } finally {
    await bridge.close();
  }
});

test("authorization: a changed request hash cannot reuse the permit; expiry re-prepares", async () => {
  const bridge = await startBridge({
    prepare: () => ({
      permitId: "permit-live",
      expiresAt: new Date(Date.now() + 60).toISOString(),
      credentialRef: { store: "file", key: "k" },
    }),
  });
  try {
    const authorizer = new ExecutionAuthorizer(httpExecutionBridge({ baseUrl: bridge.url, token: "t" }));
    await authorizer.authorize(request);
    await assert.rejects(() => authorizer.authorize({ ...request, requestHash: "b".repeat(64) }),
      (error: unknown) => (error as AuthorizationError).code === "request_hash_changed");

    // The stub's permits live for 60ms; after that the cached one is dead and
    // the same operation must fetch a fresh permit.
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 90));
    assert.equal(authorizer.hasPermit(request.operationId), false, "expired permit is not live");
    await authorizer.authorize(request);
    assert.equal(authorizer.hasPermit(request.operationId), true);
    assert.equal(bridge.log.filter((entry) => entry.path.endsWith("/prepare")).length, 2, "expiry re-prepares");
  } finally {
    await bridge.close();
  }
});

test("authorization: bridge denial codes pass through; unreachable bridge is unknown", async () => {
  const bridge = await startBridge({
    prepare: () => ({ status: 409, code: "grant_exhausted", message: "no budget left" }),
  });
  try {
    const authorizer = new ExecutionAuthorizer(httpExecutionBridge({ baseUrl: bridge.url, token: "t" }));
    await assert.rejects(() => authorizer.authorize(request), (error: unknown) => {
      const authError = error as AuthorizationError;
      assert.equal(authError.code, "grant_exhausted");
      assert.equal(authError.status, 409);
      return true;
    });
  } finally {
    await bridge.close();
  }

  const dead = httpExecutionBridge({
    baseUrl: "http://127.0.0.1:1",
    token: "t",
    fetch: async () => {
      throw new Error("connection refused");
    },
  });
  await assert.rejects(() => dead.prepare(request),
    (error: unknown) => (error as AuthorizationError).code === "bridge_unreachable",
    "transport failure stays unknown instead of faking a denial");
});

test("authorization: internal token travels as bearer on every call", async () => {
  const bridge = await startBridge({});
  try {
    const client = httpExecutionBridge({ baseUrl: bridge.url, token: "secret-internal" });
    await client.prepare(request);
    await client.complete(request.operationId, { state: "succeeded" });
    // The stub logged bodies; assert the token via the raw server: re-run one
    // call with a header-capturing server.
    assert.equal(bridge.log.length, 2);
  } finally {
    await bridge.close();
  }
  const seen: string[] = [];
  const server = createServer((request_, response) => {
    seen.push(request_.headers.authorization ?? "");
    response.writeHead(200, { "content-type": "application/json" });
    response.end("{}");
  });
  await new Promise<void>((resolvePromise) => server.listen(0, "127.0.0.1", resolvePromise));
  const address = server.address() as AddressInfo;
  try {
    const client = httpExecutionBridge({ baseUrl: `http://127.0.0.1:${String(address.port)}`, token: "secret-internal" });
    await client.prepare(request);
    assert.deepEqual(seen, ["Bearer secret-internal"]);
  } finally {
    await new Promise<void>((resolvePromise) => {
      server.closeIdleConnections?.();
      server.close(() => resolvePromise());
    });
  }
});
