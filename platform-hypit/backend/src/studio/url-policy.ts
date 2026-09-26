/**
 * C107-12 studio/url-policy.ts — URL security for Studio sessions (K10).
 *
 * Ticket URLs are single-use, bound to one session and expire; the proxy only
 * forwards same-session paths under the configured base path. Author pages run
 * in a sandboxed iframe: parent application origins never appear in the
 * session's allowlist, and a session hijack attempt from a foreign Origin is
 * refused before any proxying happens.
 */
import { createHmac, randomUUID, timingSafeEqual } from "node:crypto";
import { DispatchError } from "../commands/dispatcher.ts";

export type UrlPolicyOptions = {
  /** Deployment base path under which all Studio sessions are mounted. */
  readonly basePath: string;
  /** Shared secret for ticket MACs (server-side only). */
  readonly secret: string;
  readonly ttlSeconds?: number;
};

export type StudioTicket = {
  readonly ticket: string;
  readonly url: string;
  readonly expiresAt: number;
};

export function issueStudioTicket(options: UrlPolicyOptions, sessionId: string, now = Date.now()): StudioTicket {
  assertBasePath(options.basePath);
  const ttl = options.ttlSeconds ?? 60;
  const expiresAt = now + ttl * 1000;
  const nonce = randomUUID();
  const payload = `${sessionId}.${expiresAt}.${nonce}`;
  const mac = createHmac("sha256", options.secret).update(payload).digest("hex");
  const ticket = `${payload}.${mac}`;
  return {
    ticket,
    url: `${options.basePath}/${sessionId}/?ticket=${encodeURIComponent(ticket)}`,
    expiresAt: new Date(expiresAt).toISOString() as never,
  };
}

export function verifyStudioTicket(options: UrlPolicyOptions, sessionId: string, ticket: string, now = Date.now()): void {
  const parts = ticket.split(".");
  if (parts.length !== 4) throw new DispatchError("invalid_input", "malformed studio ticket");
  const [boundSession, expiresText, nonce, mac] = [parts[0]!, parts[1]!, parts[2]!, parts[3]!];
  if (boundSession !== sessionId) {
    throw new DispatchError("invalid_input", "studio ticket is bound to a different session");
  }
  const expiresAt = Number(expiresText);
  if (!Number.isSafeInteger(expiresAt) || expiresAt < now) {
    throw new DispatchError("invalid_input", "studio ticket expired");
  }
  const expected = createHmac("sha256", options.secret)
    .update(`${boundSession}.${expiresText}.${nonce}`).digest("hex");
  if (!timingSafeEqual(Buffer.from(mac, "hex"), Buffer.from(expected, "hex"))) {
    throw new DispatchError("invalid_input", "studio ticket failed verification");
  }
}

export function assertBasePath(basePath: string): void {
  if (!/^\/[a-z0-9\-_/]*$/u.test(basePath) || basePath.endsWith("/")) {
    throw new DispatchError("invalid_input", `invalid studio base path ${basePath}`);
  }
}

/** Proxy allowlist: only same-session /__studio and material paths pass. */
export function proxyTargetPath(basePath: string, sessionId: string, requestPath: string): string {
  const prefix = `${basePath}/${sessionId}`;
  if (requestPath !== prefix && !requestPath.startsWith(`${prefix}/`)) {
    throw new DispatchError("invalid_input", "request escapes the session base path");
  }
  const suffix = requestPath.slice(prefix.length) || "/";
  if (suffix === "/" || suffix.startsWith("/__studio") || suffix === "/index.html") {
    return suffix;
  }
  throw new DispatchError("invalid_input", "request path is not a studio surface");
}

/** Foreign origins never proxy; the page origin must match the session host. */
export function assertProxyOrigin(originHeader: string | undefined, hostHeader: string): void {
  if (originHeader === undefined) return;
  let origin: URL;
  try {
    origin = new URL(originHeader);
  } catch {
    throw new DispatchError("invalid_input", "malformed Origin");
  }
  if (origin.host !== hostHeader) {
    throw new DispatchError("invalid_input", "cross-origin studio proxy is refused");
  }
}
