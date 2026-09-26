// fake-provider-server.mjs — C107-07 (task-107) fixture remote provider.
//
// A standalone HTTP server speaking the HypiHub wire contract closely enough
// to drive the REAL createHypiHubProvider lifecycle (model catalogue → submit
// with idempotency-key → job polling → asset download), plus the OAuth
// token endpoint used by the auth-flow tests. It records every call and —
// like a real remote generation service — fetches reference URLs from submit
// bodies so the publicAssetUrl transport is proven by the remote side
// actually GETting the same bytes (K12.9).
import { createHash } from "node:crypto";
import { createServer } from "node:http";


/**
 * @typedef {{ method: string, path: string, authorization?: string, idempotencyKey?: string, body?: unknown }} FakeProviderCall
 * @typedef {{ fetchedReferenceHashes: string[], submittedBodies: Object<string, unknown>[], authorizeQueries: URLSearchParams[], tokenRequests: URLSearchParams[], bearerTokens: string[] }} FakeProviderState
 * @typedef {{ url: string, port: number, calls: FakeProviderCall[], state: FakeProviderState, failBearerCalls(count: number): void, setPollsBeforeDone(count: number): void, close(): Promise<void> }} FakeProviderHandle
 */

export async function startFakeProvider(options = {}) {
  const assetBytes = options.assetBytes ?? new Uint8Array([0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07]);
  const assetMediaType = options.assetMediaType ?? "video/mp4";
  const calls = [];
  const state = {
    fetchedReferenceHashes: [],
    submittedBodies: [],
    authorizeQueries: [],
    tokenRequests: [],
    bearerTokens: [],
  };
  let failBearerRemaining = 0;
  let pollsBeforeDone = 1;
  const pollsByJob = new Map();
  let jobCounter = 0;
  const jobs = new Map(); // id -> status

  const server = createServer((request, response) => {
    const chunks = [];
    request.on("data", (chunk) => chunks.push(chunk));
    request.on("end", () => {
      const bodyBytes = Buffer.concat(chunks);
      const url = new URL(request.url ?? "/", "http://fake.provider");
      // HypiHub normalizes its API base to <origin>/v1; accept both forms.
      const path = url.pathname.replace(/^\/v1(?=\/)/u, "");
      const rawBody = bodyBytes.length === 0 ? undefined : parseBody(request.headers["content-type"], bodyBytes);
      const call = {
        method: request.method ?? "GET",
        path,
        authorization: header(request.headers.authorization),
        idempotencyKey: header(request.headers["idempotency-key"]),
        body: rawBody,
      };
      calls.push(call);

      const send = (status, payload, headers = {}) => {
        response.writeHead(status, headers);
        response.end(typeof payload === "string" ? payload : JSON.stringify(payload));
      };

      // ---- OAuth (auth-flow tests) ----
      if (path === "/oauth/consent") {
        state.authorizeQueries.push(url.searchParams);
        send(200, "<html>consent</html>", { "content-type": "text/html" });
        return;
      }
      if (path === "/oauth/token") {
        const form = new URLSearchParams(bodyBytes.toString("utf8"));
        state.tokenRequests.push(form);
        if (form.get("grant_type") === "refresh_token") {
          // Provider-side refresh (createHypiHubAuth): no PKCE involved.
          if (form.get("refresh_token") !== "fake-refresh") {
            send(400, { error: "invalid_grant" });
            return;
          }
          send(200, { access_token: "fake-access", refresh_token: "fake-refresh", expires_in: 3600 });
          return;
        }
        if (options.enforcePkce !== false) {
          const verifier = form.get("code_verifier") ?? "";
          const lastChallenge = state.authorizeQueries.at(-1)?.get("code_challenge") ?? "";
          const computed = createHash("sha256").update(verifier).digest("base64url");
          if (verifier.length < 40 || lastChallenge.length === 0 || computed !== lastChallenge) {
            send(400, { error: "invalid_grant", error_description: "PKCE verification failed" });
            return;
          }
        }
        if (form.get("code") !== "good-code") {
          send(400, { error: "invalid_grant" });
          return;
        }
        send(200, { access_token: "fake-access", refresh_token: "fake-refresh", expires_in: 3600 });
        return;
      }

      // ---- Authenticated provider API ----
      const authorization = header(request.headers.authorization);
      if (authorization !== undefined) {
        state.bearerTokens.push(authorization);
        if (failBearerRemaining > 0) {
          failBearerRemaining -= 1;
          send(401, { error: "invalid_token" });
          return;
        }
      }
      if (path.startsWith("/models/")) {
        send(200, { endpoints: ["videos", "images", "image_edits"] });
        return;
      }
      if (path === "/videos" || path === "/images/generations" || path === "/images/edits") {
        const body = rawBody ?? {};
        state.submittedBodies.push(body);
        // A real remote service fetches reference media by URL before queueing.
        const referenceUrls = collectReferenceUrls(body);
        void (async () => {
          for (const reference of referenceUrls) {
            try {
              const fetched = await globalThis.fetch(reference);
              const bytes = Buffer.from(await fetched.arrayBuffer());
              state.fetchedReferenceHashes.push(createHash("sha256").update(bytes).digest("hex"));
            } catch {
              state.fetchedReferenceHashes.push(`fetch-failed:${reference}`);
            }
          }
        })();
        jobCounter += 1;
        const id = `job_${String(jobCounter)}`;
        jobs.set(id, "queued");
        send(200, { id, status: "queued" });
        return;
      }
      const jobMatch = /^\/jobs\/([^/]+)$/.exec(path);
      if (jobMatch !== null && request.method === "GET") {
        const id = jobMatch[1];
        const seen = pollsByJob.get(id) ?? 0;
        pollsByJob.set(id, seen + 1);
        if (seen < pollsBeforeDone) {
          jobs.set(id, "running");
          send(200, { id, status: "running" });
          return;
        }
        jobs.set(id, "succeeded");
        send(200, { id, status: "succeeded" });
        return;
      }
      const assetsMatch = /^\/jobs\/([^/]+)\/assets$/.exec(path);
      if (assetsMatch !== null && request.method === "GET") {
        const origin = `http://${request.headers.host ?? "127.0.0.1"}`;
        send(200, { items: [{ url: `${origin}/assets/product.mp4` }] });
        return;
      }
      if (path.startsWith("/assets/")) {
        response.writeHead(200, { "content-type": assetMediaType, "content-length": String(assetBytes.byteLength) });
        response.end(assetBytes);
        return;
      }
      if (path === "/pricing") {
        send(200, { model: url.searchParams.get("model"), currency: "USD", unit: "per-second" });
        return;
      }
      send(404, { error: "not_found", path });
    });
  });

  await new Promise((resolvePromise, rejectPromise) => {
    server.once("error", rejectPromise);
    server.listen(options.port ?? 0, options.host ?? "127.0.0.1", () => {
      server.removeListener("error", rejectPromise);
      resolvePromise();
    });
  });
  const address = server.address();

  return {
    url: `http://127.0.0.1:${String(address.port)}`,
    port: address.port,
    calls,
    state,
    failBearerCalls(count) {
      failBearerRemaining = count;
    },
    setPollsBeforeDone(count) {
      pollsBeforeDone = count;
    },
    close() {
      return new Promise((resolvePromise) => {
        server.closeIdleConnections?.();
        server.close(() => resolvePromise());
      });
    },
  };
}

function header(value) {
  if (Array.isArray(value)) return value[0];
  return value;
}

function parseBody(contentType, bytes) {
  const text = contentType === "application/x-www-form-urlencoded"
    ? bytes.toString("utf8")
    : bytes.toString("utf8");
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}

function collectReferenceUrls(body) {
  const urls = [];
  for (const value of Object.values(body)) {
    if (typeof value === "string" && value.startsWith("http")) urls.push(value);
    if (Array.isArray(value)) {
      for (const item of value) {
        if (typeof item === "string" && item.startsWith("http")) urls.push(item);
      }
    }
  }
  return urls;
}
