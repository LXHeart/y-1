/**
 * C107F2-19 studio/proxy.ts — the broker-side Studio session proxy.
 *
 * F16 代理侧：/studio/<sessionId>/ 请求经 Nginx auth_request（Edge→Java session
 * access）拿到短期 HMAC 断言（X-Hypit-Session-Assertion）后才到本代理；本代理
 * 二次校验断言签名/时效/aud/sid，再受控转发到该会话绑定的子进程：
 *  - 只代理固定会话前缀下的 Studio 资源面与同源 WS 升级，禁止任意 host/path 穿透；
 *  - 首次访问后下发 HttpOnly/SameSite=Strict 会话 cookie（后续请求仍需断言）；
 *  - 写面（/__studio/source、/__studio/mutation）被拦截走 mutation-bridge 的
 *    Java changeset 通道，绝不直通子进程工作区。
 */
import { createHmac, timingSafeEqual } from "node:crypto";
import type { IncomingMessage, ServerResponse } from "node:http";
import { request as httpRequest } from "node:http";
import { DispatchError } from "../commands/dispatcher.ts";
import {
  observeChildRevision, relayArtifactRename, relaySemanticMutation, relaySourceSave,
  type StudioWritebackClient,
} from "./mutation-bridge.ts";
import { requireStudioSession, type StudioSession } from "./sessions.ts";
import { assertProxyOrigin, proxyTargetPath as upstreamSurfacePath } from "./url-policy.ts";

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
  const suffix = studioResourcePath(input.basePath, input.sessionId, input.path);
  return { targetPath: suffix, upgrade: input.upgrade !== undefined };
}

/**
 * Studio resource surface: upstream url-policy surfaces plus the vite dev
 * surfaces the native editor loads (@vite client, /src modules, /assets,
 * node_modules deps). Still strictly same-session — no arbitrary path passes.
 */
export function studioResourcePath(basePath: string, sessionId: string, requestPath: string): string {
  const prefix = `${basePath}/${sessionId}`;
  if (requestPath !== prefix && !requestPath.startsWith(`${prefix}/`)) {
    throw new DispatchError("invalid_input", "request escapes the session base path");
  }
  const suffix = requestPath.slice(prefix.length) || "/";
  const asset = suffix.split("?")[0] ?? suffix;
  if (asset === "/" || asset === "/index.html" || asset === "/favicon.ico"
    || asset.startsWith("/__studio") || asset.startsWith("/@") || asset.startsWith("/src/")
    || asset.startsWith("/assets/") || asset.startsWith("/node_modules/")
    // C107F2-37：工作区 i18n 静态面（locales/*.json）——缺失时 studio UI 的本地化
    // 模块加载 400，整棵 UI 不渲染（iframe body 空）。与 /src/ 同类工作区资源。
    || asset.startsWith("/locales/")) {
    return suffix;
  }
  // url-policy 的更严名单（含写面语义）保持权威：非 vite 资源路径走它裁决。
  return upstreamSurfacePath(basePath, sessionId, requestPath);
}

/** Session assertion claims signed by Java's session-access endpoint (§6.9). */
export type SessionAssertion = {
  readonly sid: string;
  readonly projectId: string;
  readonly ownerAccountId: string;
  readonly revision: number;
  readonly readOnly: boolean;
  readonly exp: number;
  readonly aud: string;
  readonly nonce: string;
};

const ASSERTION_AUDIENCE = "hypit-session-proxy";

function assertionMac(secret: string, payload: string): string {
  return createHmac("sha256", secret).update(`assertion|${payload}`).digest("hex");
}

/** Signed-assertion format: base64url(claims).hmac (purpose-separated from tickets). */
export function signSessionAssertion(secret: string, claims: SessionAssertion, now = Date.now()): string {
  const body = JSON.stringify({ ...claims, exp: claims.exp || now + 30_000 });
  const segment = Buffer.from(body, "utf8").toString("base64url");
  return `${segment}.${assertionMac(secret, segment)}`;
}

export function verifySessionAssertion(secret: string, token: string, now = Date.now()): SessionAssertion {
  const parts = token.split(".");
  if (parts.length !== 2) throw new DispatchError("invalid_input", "malformed session assertion");
  let claims: SessionAssertion;
  try {
    claims = JSON.parse(Buffer.from(parts[0]!, "base64url").toString("utf8")) as SessionAssertion;
  } catch {
    throw new DispatchError("invalid_input", "session assertion claims are unreadable");
  }
  const expected = assertionMac(secret, parts[0]!);
  const given = parts[1] ?? "";
  if (given.length !== expected.length
    || !timingSafeEqual(Buffer.from(given, "utf8"), Buffer.from(expected, "utf8"))) {
    throw new DispatchError("invalid_input", "session assertion failed verification");
  }
  if (claims.aud !== ASSERTION_AUDIENCE) {
    throw new DispatchError("invalid_input", "session assertion audience mismatch");
  }
  if (typeof claims.exp !== "number" || claims.exp < now) {
    throw new DispatchError("invalid_input", "session assertion expired");
  }
  if (typeof claims.sid !== "string" || typeof claims.ownerAccountId !== "string") {
    throw new DispatchError("invalid_input", "session assertion missing sid/owner");
  }
  return claims;
}

const WRITE_SURFACES = ["/__studio/source", "/__studio/mutation", "/__studio/artifact-name"] as const;

export function isWriteSurface(suffix: string): boolean {
  const path = suffix.split("?")[0] ?? suffix;
  return WRITE_SURFACES.includes(path as (typeof WRITE_SURFACES)[number]);
}

function sessionCookieName(sessionId: string): string {
  return `hypit_studio_${sessionId.replace(/[^A-Za-z0-9_-]/gu, "")}`;
}

function readBody(request: IncomingMessage, limitBytes = 8 * 1024 * 1024): Promise<string> {
  return new Promise((resolve, reject) => {
    const chunks: Buffer[] = [];
    let total = 0;
    request.on("data", (chunk: Buffer) => {
      total += chunk.length;
      if (total > limitBytes) {
        reject(new DispatchError("invalid_input", "studio request body exceeds the byte budget"));
        request.destroy();
        return;
      }
      chunks.push(chunk);
    });
    request.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
    request.on("error", (error) => reject(new DispatchError("invalid_input", String(error))));
  });
}

function sendJson(response: ServerResponse, status: number, payload: unknown): void {
  response.writeHead(status, { "content-type": "application/json", "cache-control": "no-store" });
  response.end(JSON.stringify(payload));
}

function pipeToChild(
  session: StudioSession,
  request: IncomingMessage,
  response: ServerResponse,
  suffix: string,
): void {
  const upstream = httpRequest(
    {
      host: "127.0.0.1",
      port: session.child.port,
      path: suffix,
      method: request.method,
      headers: { ...request.headers, host: `127.0.0.1:${session.child.port}` },
    },
    (childResponse) => {
      response.writeHead(childResponse.statusCode ?? 502, childResponse.headers);
      childResponse.pipe(response);
    },
  );
  upstream.on("error", (error) => {
    sendJson(response, 502, { error: `studio child unreachable: ${String(error)}` });
  });
  request.pipe(upstream);
}

function pipeUpgrade(
  session: StudioSession,
  request: IncomingMessage,
  socket: import("node:stream").Duplex,
  head: Buffer,
  suffix: string,
): void {
  const upstream = httpRequest({
    host: "127.0.0.1",
    port: session.child.port,
    path: suffix,
    method: request.method,
    headers: { ...request.headers, host: `127.0.0.1:${session.child.port}` },
  });
  upstream.on("upgrade", (childResponse, childSocket, childHead) => {
    socket.write(
      `HTTP/1.1 101 Switching Protocols\r\n`
      + [...Object.entries(childResponse.headers)].map(([key, value]) => `${key}: ${value}`).join("\r\n")
      + "\r\n\r\n",
    );
    childSocket.pipe(socket);
    socket.pipe(childSocket);
    if (childHead.length > 0) socket.write(childHead);
    if (head.length > 0) childSocket.write(head);
    const drop = () => {
      childSocket.destroy();
      socket.destroy();
    };
    childSocket.on("error", drop);
    socket.on("error", drop);
    childSocket.on("close", () => socket.destroy());
    socket.on("close", () => childSocket.destroy());
  });
  upstream.on("error", () => {
    socket.write("HTTP/1.1 502 Bad Gateway\r\ncontent-type: application/json\r\n\r\n");
    socket.destroy();
  });
  // GET 无 body；直接结束请求头。
  upstream.end(head.length > 0 ? head : undefined);
}

export type StudioProxyOptions = {
  /** Shared with Java's session-access signer (HMAC-SHA256, ≥32 chars). */
  readonly assertionSecret: string;
  readonly writeback: StudioWritebackClient;
};

export function createStudioProxyHandler(options: StudioProxyOptions) {
  return {
    /** True when the request belongs to the /studio/<sid>/ surface (before any auth). */
    matches(pathname: string): boolean {
      return pathname === "/studio" || pathname.startsWith("/studio/");
    },

    /** HTTP entry: assertion gate → session lookup → proxy/intercept. */
    async handle(
      request: IncomingMessage,
      response: ServerResponse,
      url: URL,
    ): Promise<boolean> {
      if (url.pathname === "/studio" || url.pathname === "/studio/") {
        sendJson(response, 404, { error: "studio sessions live under /studio/<sessionId>/" });
        return true;
      }
      const sessionId = url.pathname.split("/")[2] ?? "";
      if (sessionId.length === 0) {
        sendJson(response, 404, { error: "missing studio session id" });
        return true;
      }
      if (options.assertionSecret.length < 32) {
        sendJson(response, 503, { error: "studio session surface disabled: assertion secret not configured" });
        return true;
      }
      let session: StudioSession;
      const assertionHeader = request.headers["x-hypit-session-assertion"];
      const token = Array.isArray(assertionHeader) ? assertionHeader[0] : assertionHeader;
      if (typeof token !== "string" || token.length === 0) {
        sendJson(response, 401, { error: "studio session requires a session-access assertion" });
        return true;
      }
      try {
        const claims = verifySessionAssertion(options.assertionSecret, token);
        if (claims.sid !== sessionId) {
          throw new DispatchError("invalid_input", "assertion is bound to a different session");
        }
        session = requireStudioSession(sessionId);
        if (claims.projectId !== session.projectId || claims.ownerAccountId !== session.ownerAccountId) {
          throw new DispatchError("invalid_input", "assertion does not match the session owner");
        }
      } catch (error) {
        const status = error instanceof DispatchError && error.code === "not_found" ? 404 : 401;
        sendJson(response, status, { error: error instanceof Error ? error.message : String(error) });
        return true;
      }
      // 首次进入（ticket 已由 Java access 核销）下发 HttpOnly/Strict cookie；
      // 后续请求仍每走一次断言（Nginx auth_request），cookie 只绑定浏览器态。
      const cookieName = sessionCookieName(sessionId);
      const cookieHeader = request.headers.cookie ?? "";
      const hasCookie = cookieHeader.split(";").some((part) => part.trim().startsWith(`${cookieName}=`));
      const hasTicket = url.searchParams.has("ticket");
      if (!hasCookie && !hasTicket && !isWriteSurface(url.pathname.replace(`/studio/${sessionId}`, "") || "/")) {
        // 资源请求既无 cookie 也无 ticket：拒绝只凭断言的裸抓取。
        sendJson(response, 403, { error: "studio session cookie missing; open the session ticket first" });
        return true;
      }
      if (!hasCookie) {
        const existing = response.getHeader("set-cookie");
        const prior = Array.isArray(existing) ? existing.map(String)
          : typeof existing === "string" ? [existing] : [];
        const cookie = `${cookieName}=${sessionId}; Path=/studio/${sessionId}/; HttpOnly; SameSite=Strict; Max-Age=3600`;
        response.setHeader("set-cookie", [...prior, cookie]);
      }
      try {
        const suffix = studioResourcePath("/studio", sessionId, url.pathname);
        assertProxyOrigin(request.headers.origin, request.headers.host ?? "127.0.0.1");
        if (isWriteSurface(suffix)) {
          await interceptWriteSurface(session, options.writeback, request, response, suffix);
          return true;
        }
        // 子进程以 base=`/studio/<sid>/` 挂载（vite base 中间件自行剥前缀），
        // 代理转发完整原始路径，绝不剥前缀——否则 base 中间件 302 回环。
        pipeToChild(session, request, response, url.pathname + url.search);
      } catch (error) {
        const code = error instanceof DispatchError ? error.code : "invalid_input";
        const status = code === "not_found" ? 404 : code === "revision_conflict" ? 409 : code === "forbidden" ? 403 : 400;
        sendJson(response, status, { error: error instanceof Error ? error.message : String(error) });
      }
      return true;
    },

    /** WS upgrade entry: same gates, then raw socket piping to the child. */
    handleUpgrade(
      request: IncomingMessage,
      socket: import("node:stream").Duplex,
      head: Buffer,
      url: URL,
    ): boolean {
      const sessionId = url.pathname.split("/")[2] ?? "";
      if (sessionId.length === 0) {
        socket.write("HTTP/1.1 404 Not Found\r\n\r\n");
        socket.destroy();
        return true;
      }
      if (options.assertionSecret.length < 32) {
        socket.write("HTTP/1.1 503 Service Unavailable\r\n\r\n");
        socket.destroy();
        return true;
      }
      const assertionHeader = request.headers["x-hypit-session-assertion"];
      const token = Array.isArray(assertionHeader) ? assertionHeader[0] : assertionHeader;
      if (typeof token !== "string" || token.length === 0) {
        socket.write("HTTP/1.1 401 Unauthorized\r\n\r\n");
        socket.destroy();
        return true;
      }
      try {
        const claims = verifySessionAssertion(options.assertionSecret, token);
        if (claims.sid !== sessionId) {
          throw new DispatchError("invalid_input", "assertion is bound to a different session");
        }
        const session = requireStudioSession(sessionId);
        if (claims.ownerAccountId !== session.ownerAccountId) {
          throw new DispatchError("invalid_input", "assertion does not match the session owner");
        }
        studioResourcePath("/studio", sessionId, url.pathname);
        // WS 同样转发完整路径：HMR 网闸按 pathname === hmrBase（即会话 base）放行。
        pipeUpgrade(session, request, socket, head, url.pathname + url.search);
      } catch {
        socket.write("HTTP/1.1 401 Unauthorized\r\n\r\n");
        socket.destroy();
      }
      return true;
    },
  };
}

/** 写面拦截：readOnly/越界在桥内拒绝；source/mutation 走 Java，artifact-name 中继。 */
async function interceptWriteSurface(
  session: StudioSession,
  writeback: StudioWritebackClient,
  request: IncomingMessage,
  response: ServerResponse,
  suffix: string,
): Promise<void> {
  const surface = suffix.split("?")[0] ?? suffix;
  if (session.readOnly) {
    sendJson(response, 403, { error: "read-only studio sessions cannot mutate" });
    return;
  }
  const raw = await readBody(request);
  let body: Record<string, unknown>;
  try {
    body = JSON.parse(raw) as Record<string, unknown>;
  } catch {
    sendJson(response, 400, { error: "Expected a JSON mutation body." });
    return;
  }
  if (surface === "/__studio/artifact-name") {
    const relayed = await relayArtifactRename(session, body);
    sendJson(response, relayed.status, relayed.body.startsWith("{") ? JSON.parse(relayed.body) : { error: relayed.body });
    return;
  }
  try {
    const outcome = surface === "/__studio/source"
      ? await relaySourceSave(session, writeback, {
        text: String(body.text ?? ""),
        ...(typeof body.path === "string" ? { path: body.path } : {}),
      })
      : await relaySemanticMutation(session, writeback, body);
    const observed = await observeChildRevision(session, outcome.revision);
    const { advanceStudioSessionRevision } = await import("./sessions.ts");
    advanceStudioSessionRevision(session, outcome.revision);
    sendJson(response, surface === "/__studio/source" ? 202 : 200, { revision: observed });
  } catch (error) {
    const code = error instanceof DispatchError ? error.code : "studio_writeback_failed";
    const status = code === "revision_conflict" ? 409 : code === "forbidden" ? 403
      : code === "invalid_input" ? 400 : 502;
    sendJson(response, status, { error: error instanceof Error ? error.message : String(error) });
  }
}
