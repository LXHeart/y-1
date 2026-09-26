/**
 * C107-12 studio/proxy.ts — reverse-proxy planning for Studio sessions behind
 * the broker/nginx. Requests are admitted only under the session's base path
 * with a same-session suffix; WebSocket upgrades carry the session cookie and
 * are refused on cross-origin handshakes. The proxy never rewrites response
 * bodies — the base-path patch makes the upstream server prefix-agnostic.
 */
import { assertProxyOrigin, proxyTargetPath } from "./url-policy.ts";
import { DispatchError } from "../commands/dispatcher.ts";

export type ProxyDecision = {
  readonly targetPath: string;
  readonly upgrade: boolean;
};

export function planProxy(input: {
  readonly basePath: string;
  readonly sessionId: string;
  readonly method: string;
  readonly path: string;
  readonly upgrade?: string;
  readonly origin?: string;
  readonly host: string;
}): ProxyDecision {
  if (input.upgrade !== undefined && input.upgrade.toLowerCase() !== "websocket") {
    throw new DispatchError("invalid_input", "unsupported upgrade protocol");
  }
  assertProxyOrigin(input.origin, input.host);
  const suffix = proxyTargetPath(input.basePath, input.sessionId, input.path);
  if (suffix.startsWith("/__studio/source") || suffix.startsWith("/__studio/mutation")
    || suffix.startsWith("/__studio/artifact-name")) {
    if (input.method !== "PUT" && input.method !== "POST" && input.method !== "GET") {
      throw new DispatchError("invalid_input", "write surfaces need PUT/POST/GET");
    }
  }
  return { targetPath: suffix, upgrade: input.upgrade !== undefined };
}
