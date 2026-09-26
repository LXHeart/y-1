// asset-transport.ts — C107-07 (task-107) publicAssetUrl transport.
//
// K12.9: the callback the trusted provider factories receive publishes one
// referenced resource (BlobRef) at a stable temporary URL the remote service
// can GET for as long as the request may run. Objects are unguessable
// (256-bit token path), single-purpose, byte-identical to the referenced
// resource, and disappear on close. The listening surface is its own bounded
// HTTP server so the main broker routes never grow a public asset path.
import { createHash, randomBytes } from "node:crypto";
import { createServer, type Server } from "node:http";
import type { AddressInfo } from "node:net";

export type PublicAssetTransport = {
  /** The publicAssetUrl callback handed to provider factories. */
  readonly publish: (
    artifact: { readonly resource: string; readonly size?: number; readonly mediaType?: string },
    artifacts: { get(resource: string): Promise<Uint8Array | undefined> },
    fields?: Readonly<Record<string, string | number | boolean>>,
  ) => Promise<string>;
  /** Base URL the published paths resolve under (fixture/tests override host+port). */
  readonly baseUrl: string;
  readonly close: () => Promise<void>;
  /** Diagnostics: how many objects are published right now. */
  readonly publishedCount: () => number;
};

type StagedObject = {
  readonly bytes: Uint8Array;
  readonly mediaType: string;
  readonly sha256: string;
  expiresAt: number;
};

export type AssetTransportOptions = {
  /** How long each published object stays reachable; refreshed per publish. */
  readonly ttlMs?: number;
  /** Cap staged bytes process-wide; publishing beyond it fails the request. */
  readonly maxTotalBytes?: number;
  readonly host?: string;
  readonly port?: number;
  readonly now?: () => number;
};

const DEFAULT_TTL_MS = 60 * 60_000;
const DEFAULT_MAX_TOTAL_BYTES = 2 * 1024 * 1024 * 1024;

export class AssetTransportError extends Error {
  constructor(readonly code: string, message: string) {
    super(message);
    this.name = "AssetTransportError";
  }
}

/**
 * Start the temporary public-object server. Bind loopback by default: the
 * remote provider is reached from this process, and a public deployment puts
 * a reverse proxy in front instead of exposing the broker directly.
 */
export async function startAssetTransport(options: AssetTransportOptions = {}): Promise<PublicAssetTransport> {
  const ttlMs = options.ttlMs ?? DEFAULT_TTL_MS;
  const maxTotalBytes = options.maxTotalBytes ?? DEFAULT_MAX_TOTAL_BYTES;
  const now = options.now ?? Date.now;
  const objects = new Map<string, StagedObject>();
  const byResource = new Map<string, string>();
  let totalBytes = 0;

  const server: Server = createServer((request, response) => {
    const token = (request.url ?? "").replace(/^\/+/, "").split("?")[0]!;
    reap();
    const staged = objects.get(token);
    if (staged === undefined || staged.expiresAt < now()) {
      response.writeHead(404, { "content-type": "application/json" });
      response.end(JSON.stringify({ error: "not_found" }));
      return;
    }
    if (request.method !== "GET" && request.method !== "HEAD") {
      response.writeHead(405, { allow: "GET, HEAD" });
      response.end();
      return;
    }
    const etag = `"${staged.sha256}"`;
    if (request.headers["if-none-match"] === etag) {
      response.writeHead(304, { etag });
      response.end();
      return;
    }
    const headers = {
      "content-type": staged.mediaType,
      "content-length": String(staged.bytes.byteLength),
      "cache-control": "private, max-age=60",
      etag,
    };
    response.writeHead(200, headers);
    if (request.method === "HEAD") {
      response.end();
      return;
    }
    response.end(staged.bytes);
  });

  await new Promise<void>((resolvePromise, rejectPromise) => {
    server.once("error", rejectPromise);
    server.listen(options.port ?? 0, options.host ?? "127.0.0.1", () => {
      server.removeListener("error", rejectPromise);
      resolvePromise();
    });
  });
  const address = server.address() as AddressInfo;
  const baseUrl = `http://${options.host ?? "127.0.0.1"}:${String(address.port)}`;

  function reap(): void {
    for (const [token, staged] of objects) {
      if (staged.expiresAt < now()) {
        objects.delete(token);
        totalBytes -= staged.bytes.byteLength;
      }
    }
    for (const [resource, token] of byResource) {
      if (!objects.has(token)) {
        byResource.delete(resource);
      }
    }
  }

  const transport: PublicAssetTransport = {
    baseUrl,
    publishedCount: () => objects.size,
    async publish(artifact, artifacts, _fields) {
      const existing = byResource.get(artifact.resource);
      if (existing !== undefined) {
        const staged = objects.get(existing);
        if (staged !== undefined && staged.expiresAt >= now()) {
          // Same reference republished for another request: refresh the window
          // and hand out the same stable URL (upstream dedup semantics).
          staged.expiresAt = now() + ttlMs;
          return `${baseUrl}/${existing}`;
        }
      }
      const bytes = await artifacts.get(artifact.resource);
      if (bytes === undefined) {
        throw new AssetTransportError("resource_unavailable", `resource ${artifact.resource} is not readable by this operation`);
      }
      reap();
      if (totalBytes + bytes.byteLength > maxTotalBytes) {
        throw new AssetTransportError("capacity_exceeded", "public asset staging is at its byte budget");
      }
      const token = randomBytes(32).toString("base64url");
      const sha256 = createHash("sha256").update(bytes).digest("hex");
      objects.set(token, {
        bytes,
        mediaType: artifact.mediaType ?? "application/octet-stream",
        sha256,
        expiresAt: now() + ttlMs,
      });
      byResource.set(artifact.resource, token);
      totalBytes += bytes.byteLength;
      return `${baseUrl}/${token}`;
    },
    async close() {
      objects.clear();
      byResource.clear();
      totalBytes = 0;
      await new Promise<void>((resolvePromise) => {
        server.closeIdleConnections?.();
        server.close(() => resolvePromise());
      });
    },
  };
  return transport;
}
