// oauth.ts — C107-07 (task-107) server-side OAuth flows for provider credentials.
//
// Mirrors the upstream CLI's acquireOAuthCredential semantics (PKCE S256,
// random state, code exchange, upstream OAuth envelope persistence) minus the
// loopback listener: the deployed backend cannot rely on the user's browser
// reaching a container's localhost, so the flow ends with the manual
// code#state handover (§6.2 POST /runtime/auth-flows/{id}/complete). Flow
// state is short-lived, single-use, and bound to its owner + endpoint slot.
import { randomBytes, createHash, timingSafeEqual } from "node:crypto";
import { installEngineResolution } from "../engine/hypit-bootstrap.ts";
import type { CredentialStores } from "./credentials.ts";

export type AuthFlowDescriptor = {
  readonly kind: string;
  readonly authorizationEndpoint: string;
  readonly redirectUri: string;
  readonly tokenEndpoint: string;
  readonly clientId: string;
  readonly scopes: readonly string[];
};

export type StartedAuthFlow = {
  readonly flowId: string;
  readonly authorizeUrl: string;
  /** Manual handover hint shown to the operator. */
  readonly handover: string;
  readonly expiresAt: number;
};

export type AuthFlowProgress = {
  readonly flowId: string;
  readonly state: "pending" | "completed" | "cancelled" | "expired" | "failed";
  readonly credential?: { readonly store: string; readonly key: string; readonly credentialType: "oauth2" };
  readonly detail?: string;
};

export class OAuthFlowError extends Error {
  constructor(readonly code: string, message: string) {
    super(message);
    this.name = "OAuthFlowError";
  }
}

type FlowRecord = {
  readonly owner: string;
  readonly endpoint: string;
  readonly slot: string;
  /** Where the exchanged envelope is persisted (from the endpoint's declared credential slot). */
  readonly target: { readonly store: string; readonly key: string };
  readonly descriptor: AuthFlowDescriptor;
  readonly verifier: string;
  readonly state: string;
  readonly createdAt: number;
  readonly expiresAt: number;
  used: false | "completed" | "cancelled";
};

const FLOW_TTL_MS = 10 * 60_000;

function base64Url(bytes: Buffer): string {
  return bytes.toString("base64url");
}

type EncodeOAuth = (value: { accessToken: string; refreshToken?: string; expiresAt?: number }) => string;

let encodeOAuthCredentialPromise: Promise<EncodeOAuth> | undefined;

async function encodeOAuthCredential(distributionRoot: string): Promise<EncodeOAuth> {
  if (encodeOAuthCredentialPromise === undefined) {
    encodeOAuthCredentialPromise = (async () => {
      await installEngineResolution(distributionRoot);
      const runtime = await import("@hypit/runtime");
      return runtime.encodeOAuth2Credential as EncodeOAuth;
    })();
  }
  return await encodeOAuthCredentialPromise;
}

/**
 * In-memory flow registry. Flow state is ephemeral by design (PKCE verifiers
 * must never outlive their code); one process restart invalidates pending
 * flows, which the operator simply restarts.
 */
export class OAuthFlowService {
  readonly #flows = new Map<string, FlowRecord>();

  constructor(
    private readonly options: {
      readonly distributionRoot: string;
      readonly credentials: CredentialStores;
      readonly now?: () => number;
      readonly fetch?: typeof globalThis.fetch;
    },
  ) {}

  /** Begin a flow for one endpoint slot using the provider's own acquisition descriptor. */
  async start(
    owner: string,
    endpoint: string,
    slot: string,
    target: { readonly store: string; readonly key: string },
    descriptor: AuthFlowDescriptor,
  ): Promise<StartedAuthFlow> {
    if (descriptor.kind !== "oauth2-pkce") {
      throw new OAuthFlowError("unsupported_flow", `acquisition kind "${descriptor.kind}" is not oauth2-pkce`);
    }
    if (!this.options.credentials.writable(target)) {
      throw new OAuthFlowError("store_readonly", `credential store "${target.store}" cannot hold oauth envelopes`);
    }
    const now = this.options.now?.() ?? Date.now();
    const verifier = base64Url(randomBytes(32));
    const challenge = base64Url(createHash("sha256").update(verifier).digest());
    const state = base64Url(randomBytes(24));
    const flowId = base64Url(randomBytes(12));
    const url = new URL(descriptor.authorizationEndpoint);
    url.searchParams.set("response_type", "code");
    url.searchParams.set("client_id", descriptor.clientId);
    url.searchParams.set("redirect_uri", descriptor.redirectUri);
    url.searchParams.set("scope", descriptor.scopes.join(" "));
    url.searchParams.set("state", state);
    url.searchParams.set("code_challenge", challenge);
    url.searchParams.set("code_challenge_method", "S256");
    const record: FlowRecord = {
      owner,
      endpoint,
      slot,
      target,
      descriptor,
      verifier,
      state,
      createdAt: now,
      expiresAt: now + FLOW_TTL_MS,
      used: false,
    };
    this.#flows.set(flowId, record);
    return {
      flowId,
      authorizeUrl: url.toString(),
      handover: `Authorize at the URL, then paste the provider's code#state value into the auth-flow complete endpoint before ${new Date(record.expiresAt).toISOString()}.`,
      expiresAt: record.expiresAt,
    };
  }

  async progress(owner: string, flowId: string): Promise<AuthFlowProgress> {
    const record = this.#record(flowId);
    this.#requireOwner(record, owner, flowId);
    if (record.used === "completed") {
      return {
        flowId,
        state: "completed",
        credential: { store: record.target.store, key: record.target.key, credentialType: "oauth2" },
      };
    }
    if (record.used === "cancelled") {
      return { flowId, state: "cancelled" };
    }
    if (this.#expired(record)) {
      return { flowId, state: "expired" };
    }
    return { flowId, state: "pending" };
  }

  /**
   * Complete a flow with the manually forwarded `code#state` value: exchange
   * the code once for tokens and persist the upstream OAuth envelope into the
   * flow's credential slot. Replay, expiry, wrong state and cross-owner access
   * are all refused without touching the store.
   */
  async complete(owner: string, flowId: string, codeAndState: string): Promise<AuthFlowProgress> {
    const record = this.#record(flowId);
    this.#requireOwner(record, owner, flowId);
    if (record.used !== false) {
      throw new OAuthFlowError("flow_used", `auth flow ${flowId} already ${record.used}`);
    }
    if (this.#expired(record)) {
      // Expiry is time-derived, not a state transition: the record stays
      // pending-but-dead so progress keeps reporting the honest reason.
      throw new OAuthFlowError("flow_expired", `auth flow ${flowId} expired`);
    }
    const separator = codeAndState.lastIndexOf("#");
    const code = separator === -1 ? codeAndState : codeAndState.slice(0, separator);
    const state = separator === -1 ? "" : codeAndState.slice(separator + 1);
    if (code.length === 0 || state.length === 0) {
      throw new OAuthFlowError("invalid_input", "codeAndState must look like <code>#<state>");
    }
    const expected = Buffer.from(record.state, "utf8");
    const actual = Buffer.from(state, "utf8");
    if (expected.length !== actual.length || !timingSafeEqual(expected, actual)) {
      throw new OAuthFlowError("state_mismatch", "state does not match this flow");
    }
    const body = new URLSearchParams({
      grant_type: "authorization_code",
      code,
      redirect_uri: record.descriptor.redirectUri,
      client_id: record.descriptor.clientId,
      code_verifier: record.verifier,
    });
    const response = await (this.options.fetch ?? globalThis.fetch)(record.descriptor.tokenEndpoint, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: body.toString(),
      signal: AbortSignal.timeout(30_000),
    });
    if (!response.ok) {
      throw new OAuthFlowError("exchange_failed", `token endpoint returned ${String(response.status)}`);
    }
    const token = await response.json() as {
      access_token?: string;
      refresh_token?: string;
      expires_in?: number;
    };
    if (typeof token.access_token !== "string" || token.access_token.length === 0) {
      throw new OAuthFlowError("exchange_failed", "token endpoint response missing access_token");
    }
    const now = this.options.now?.() ?? Date.now();
    const envelope = await (await encodeOAuthCredential(this.options.distributionRoot))({
      accessToken: token.access_token,
      ...(token.refresh_token === undefined ? {} : { refreshToken: token.refresh_token }),
      ...(token.expires_in === undefined
        ? {}
        : { expiresAt: now + token.expires_in * 1000 }),
    });
    await this.options.credentials.put(record.target, envelope);
    record.used = "completed";
    return { flowId, state: "completed", credential: { ...record.target, credentialType: "oauth2" } };
  }

  /** Cancel a pending flow; the verifier dies with it. Idempotent for already-ended flows. */
  async cancel(owner: string, flowId: string): Promise<AuthFlowProgress> {
    const record = this.#record(flowId);
    this.#requireOwner(record, owner, flowId);
    if (record.used === false) {
      record.used = "cancelled";
    }
    return { flowId, state: record.used === "completed" ? "completed" : "cancelled" };
  }

  #record(flowId: string): FlowRecord {
    const record = this.#flows.get(flowId);
    if (record === undefined) {
      throw new OAuthFlowError("not_found", `auth flow ${flowId} does not exist`);
    }
    return record;
  }

  #requireOwner(record: FlowRecord, owner: string, flowId: string): void {
    if (record.owner !== owner) {
      throw new OAuthFlowError("owner_mismatch", `auth flow ${flowId} belongs to another operator`);
    }
  }

  #expired(record: FlowRecord): boolean {
    return (this.options.now?.() ?? Date.now()) > record.expiresAt;
  }
}
