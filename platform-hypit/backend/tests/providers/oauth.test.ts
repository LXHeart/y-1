// oauth.test.ts — C107-07 (task-107) auth flows: PKCE + manual code#state handover
// (07.4/TC107-07-04). The fake provider serves the authorize page (recording
// the challenge) and enforces S256(verifier) == challenge at the token step,
// so the PKCE binding is proven end to end against a remote implementation.
import { strict as assert } from "node:assert";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";

import { openCredentialStores } from "../../src/providers/credentials.ts";
import { OAuthFlowService, OAuthFlowError } from "../../src/providers/oauth.ts";

/** The .mjs fixture is plain JavaScript; a non-literal specifier keeps typing local. */
type FakeProvider = {
  readonly url: string;
  close(): Promise<void>;
  readonly state: {
    readonly tokenRequests: URLSearchParams[];
    readonly authorizeQueries: URLSearchParams[];
  };
};

async function startFakeProvider(): Promise<FakeProvider> {
  const specifier = "../../../fixtures/fake-provider-server.mjs";
  const module_ = (await import(specifier)) as {
    startFakeProvider: () => Promise<FakeProvider>;
  };
  return await module_.startFakeProvider();
}

const distributionRoot = join(import.meta.dirname, "../../../.generated/hypit");
const descriptor = {
  kind: "oauth2-pkce",
  authorizationEndpoint: "",
  redirectUri: "https://provider.example/oauth/callback",
  tokenEndpoint: "",
  clientId: "client-1",
  scopes: ["user:profile", "user:inference"],
};

async function fixture(): Promise<{
  readonly service: OAuthFlowService;
  readonly stores: Awaited<ReturnType<typeof openCredentialStores>>;
  readonly fake: Awaited<ReturnType<typeof startFakeProvider>>;
  readonly hostRoot: string;
  readonly clock: { value: number };
}> {
  const hostRoot = mkdtempSync(join(tmpdir(), "hypit-oauth-"));
  const stores = await openCredentialStores(distributionRoot, hostRoot);
  const fake = await startFakeProvider();
  const clock = { value: Date.now() };
  const service = new OAuthFlowService({
    distributionRoot,
    credentials: stores,
    fetch: globalThis.fetch,
    now: () => clock.value,
  });
  return { service, stores, fake, hostRoot, clock };
}

function stateFromAuthorizeUrl(url: string): string {
  return new URL(url).searchParams.get("state") ?? "";
}

test("oauth: full flow — authorize URL carries S256 PKCE, code#state exchanges, envelope persists", async () => {
  const fx = await fixture();
  try {
    const started = await fx.service.start("operator-1", "hypihub.default", "apiKey",
      { store: "file", key: "hypihub.default.apiKey" },
      { ...descriptor, authorizationEndpoint: `${fx.fake.url}/oauth/consent`, tokenEndpoint: `${fx.fake.url}/oauth/token` });

    const url = new URL(started.authorizeUrl);
    assert.equal(url.searchParams.get("response_type"), "code");
    assert.equal(url.searchParams.get("client_id"), "client-1");
    assert.equal(url.searchParams.get("redirect_uri"), descriptor.redirectUri);
    assert.equal(url.searchParams.get("scope"), "user:profile user:inference");
    assert.equal(url.searchParams.get("code_challenge_method"), "S256");
    assert.match(url.searchParams.get("code_challenge") ?? "", /^[\w-]{43}$/u, "S256 challenge is base64url(32 bytes)");

    // The user "visits" the authorize page; the remote side records the challenge.
    const page = await globalThis.fetch(started.authorizeUrl);
    assert.equal(page.status, 200);

    const state = stateFromAuthorizeUrl(started.authorizeUrl);
    const progress = await fx.service.complete("operator-1", started.flowId, `good-code#${state}`);
    assert.equal(progress.state, "completed");
    assert.equal(progress.credential?.credentialType, "oauth2");

    // The fake token endpoint verified S256(verifier) == recorded challenge.
    assert.equal(fx.fake.state.tokenRequests.length, 1);
    assert.equal(fx.fake.state.tokenRequests[0]!.get("code"), "good-code");
    assert.equal(fx.fake.state.tokenRequests[0]!.get("grant_type"), "authorization_code");

    const status = await fx.stores.status({ store: "file", key: "hypihub.default.apiKey" });
    assert.equal(status.configured, true);
    assert.equal(status.credentialType, "oauth2");
    assert.ok((status.expiresAt ?? 0) > Date.now(), "envelope expiry came from expires_in");
    assert.ok(!JSON.stringify(status).includes("fake-access"), "progress/status never leak the token");

    const replay = await fx.service.progress("operator-1", started.flowId);
    assert.equal(replay.state, "completed");
    await assert.rejects(() => fx.service.complete("operator-1", started.flowId, `good-code#${state}`),
      (error: unknown) => (error as OAuthFlowError).code === "flow_used", "code replay is refused");
    assert.equal(fx.fake.state.tokenRequests.length, 1, "no second token exchange");
  } finally {
    await fx.fake.close();
    rmSync(fx.hostRoot, { recursive: true, force: true });
  }
});

test("oauth: wrong state, malformed handover and cross-owner access are refused", async () => {
  const fx = await fixture();
  try {
    const started = await fx.service.start("operator-1", "hypihub.default", "apiKey",
      { store: "file", key: "hypihub.default.apiKey" },
      { ...descriptor, authorizationEndpoint: `${fx.fake.url}/oauth/consent`, tokenEndpoint: `${fx.fake.url}/oauth/token` });
    await assert.rejects(() => fx.service.complete("operator-1", started.flowId, "good-code#wrong-state"),
      (error: unknown) => (error as OAuthFlowError).code === "state_mismatch");
    await assert.rejects(() => fx.service.complete("operator-1", started.flowId, "no-separator"),
      (error: unknown) => (error as OAuthFlowError).code === "invalid_input");
    await assert.rejects(() => fx.service.progress("operator-2", started.flowId),
      (error: unknown) => (error as OAuthFlowError).code === "owner_mismatch");
    await assert.rejects(() => fx.service.complete("operator-2", started.flowId, "good-code#x"),
      (error: unknown) => (error as OAuthFlowError).code === "owner_mismatch");
    assert.equal(fx.fake.state.tokenRequests.length, 0, "no exchange was attempted");
  } finally {
    await fx.fake.close();
    rmSync(fx.hostRoot, { recursive: true, force: true });
  }
});

test("oauth: expiry kills the flow; a failed exchange leaves it retryable; cancel is terminal", async () => {
  const fx = await fixture();
  try {
    const full = { ...descriptor, authorizationEndpoint: `${fx.fake.url}/oauth/consent`, tokenEndpoint: `${fx.fake.url}/oauth/token` };
    const expired = await fx.service.start("operator-1", "hypihub.default", "apiKey", { store: "file", key: "a" }, full);
    await globalThis.fetch(expired.authorizeUrl);
    fx.clock.value += 11 * 60_000;
    await assert.rejects(() => fx.service.complete("operator-1", expired.flowId, `good-code#${stateFromAuthorizeUrl(expired.authorizeUrl)}`),
      (error: unknown) => (error as OAuthFlowError).code === "flow_expired");
    assert.equal((await fx.service.progress("operator-1", expired.flowId)).state, "expired");
    fx.clock.value -= 11 * 60_000;

    // Bad code: exchange fails, flow stays pending and can be completed with
    // the right code afterwards (the operator pastes again).
    const retry = await fx.service.start("operator-1", "hypihub.default", "apiKey", { store: "file", key: "b" }, full);
    await globalThis.fetch(retry.authorizeUrl);
    const state = stateFromAuthorizeUrl(retry.authorizeUrl);
    await assert.rejects(() => fx.service.complete("operator-1", retry.flowId, `bad-code#${state}`),
      (error: unknown) => (error as OAuthFlowError).code === "exchange_failed");
    assert.equal((await fx.service.progress("operator-1", retry.flowId)).state, "pending");
    const done = await fx.service.complete("operator-1", retry.flowId, `good-code#${state}`);
    assert.equal(done.state, "completed");

    const cancelled = await fx.service.start("operator-1", "hypihub.default", "apiKey", { store: "file", key: "c" }, full);
    assert.equal((await fx.service.cancel("operator-1", cancelled.flowId)).state, "cancelled");
    await assert.rejects(() => fx.service.complete("operator-1", cancelled.flowId, `good-code#${stateFromAuthorizeUrl(cancelled.authorizeUrl)}`),
      (error: unknown) => (error as OAuthFlowError).code === "flow_used");
  } finally {
    await fx.fake.close();
    rmSync(fx.hostRoot, { recursive: true, force: true });
  }
});

test("oauth: non-pkce acquisitions and read-only targets are refused at start", async () => {
  const fx = await fixture();
  try {
    await assert.rejects(
      () => fx.service.start("operator-1", "x", "apiKey", { store: "file", key: "k" },
        { ...descriptor, kind: "static-key" }),
      (error: unknown) => (error as OAuthFlowError).code === "unsupported_flow");
    await assert.rejects(
      () => fx.service.start("operator-1", "x", "apiKey", { store: "env", key: "K" }, descriptor),
      (error: unknown) => (error as OAuthFlowError).code === "store_readonly");
  } finally {
    await fx.fake.close();
    rmSync(fx.hostRoot, { recursive: true, force: true });
  }
});
