/**
 * C107F2-22 preview/server.ts — the broker-side preview resource surface (W159).
 *
 * /preview/<sessionId>/ 请求（与 Studio 同一条 session access 链：nginx auth_request
 * → Java access → 30s 断言注入，本面二次校验）受控映射到该会话绑定的不可变快照：
 *  - 资源必须是会话 served 集合内的 manifest 路径（授权集合，跨会话/未授权 404）；
 *  - 视频支持单 Range 字节区间（206 + Content-Range/Accept-Ranges）；
 *  - Content-Type 来自快照 manifest 派生的媒体类型映射；
 *  - 关闭后的会话资源立即 404（撤销），同项目 Build 不受影响。
 */
import { createReadStream } from "node:fs";
import { stat } from "node:fs/promises";
import { join, normalize, sep } from "node:path";
import type { IncomingMessage, ServerResponse } from "node:http";
import { DispatchError } from "../commands/dispatcher.ts";
import { assertSessionMaterial, requireSession } from "./sessions.ts";

export function previewResourcePath(sessionId: string, requestPath: string): string {
  const prefix = `/preview/${sessionId}`;
  if (requestPath !== prefix && !requestPath.startsWith(`${prefix}/`)) {
    throw new DispatchError("invalid_input", "request escapes the preview session base path");
  }
  const suffix = requestPath.slice(prefix.length) || "/";
  const resource = (suffix.split("?")[0] ?? suffix).replace(/^\//u, "");
  if (resource === "") return ""; // 目录根由调用方裁决（不放行内容）。
  const normalized = normalize(resource).replaceAll("\\", "/");
  if (normalized.startsWith("..") || normalized.includes("../") || normalized.startsWith("/")
    || normalized.length === 0) {
    throw new DispatchError("invalid_input", "preview resource path escapes the snapshot");
  }
  return normalized;
}

export type PreviewSurfaceOptions = {
  /** Shared with Java's session-access signer (HMAC-SHA256, ≥32 chars). */
  readonly assertionSecret: string;
};

function sendJson(response: ServerResponse, status: number, payload: unknown): void {
  response.writeHead(status, { "content-type": "application/json", "cache-control": "no-store" });
  response.end(JSON.stringify(payload));
}

export function createPreviewSurfaceHandler(options: PreviewSurfaceOptions) {
  return {
    matches(pathname: string): boolean {
      return pathname === "/preview" || pathname.startsWith("/preview/");
    },

    async handle(request: IncomingMessage, response: ServerResponse, url: URL): Promise<boolean> {
      const sessionId = url.pathname.split("/")[2] ?? "";
      if (sessionId.length === 0) {
        sendJson(response, 404, { error: "missing preview session id" });
        return true;
      }
      if (options.assertionSecret.length < 32) {
        sendJson(response, 503, { error: "preview surface disabled: assertion secret not configured" });
        return true;
      }
      // 与 Studio 同一条 access 链：30 秒断言（nginx auth_request 注入，客户端伪造
      // 的同名头在 nginx proxy_set_header 被覆盖）。
      const header = request.headers["x-hypit-session-assertion"];
      const token = Array.isArray(header) ? header[0] : header;
      if (typeof token !== "string" || token.length === 0) {
        sendJson(response, 401, { error: "preview requires a session-access assertion" });
        return true;
      }
      try {
        const { verifySessionAssertion } = await import("../studio/proxy.ts");
        const claims = verifySessionAssertion(options.assertionSecret, token);
        if (claims.sid !== sessionId) {
          throw new DispatchError("invalid_input", "assertion is bound to a different session");
        }
        const session = requireSession(sessionId);
        if (claims.ownerAccountId !== session.ownerAccountId || claims.projectId !== session.projectId) {
          throw new DispatchError("invalid_input", "assertion does not match the session owner");
        }
        // 目录根（/）不放行内容——预览面只服务授权素材；展示文档由 C23 消息面承载。
        const resource = previewResourcePath(sessionId, url.pathname);
        if (resource === "") {
          sendJson(response, 404, { error: "preview sessions serve authorized materials only" });
          return true;
        }
        const mediaType = assertSessionMaterial(session, resource);
        const absolute = join(session.snapshotDir, resource);
        if (!absolute.startsWith(session.snapshotDir + sep)) {
          throw new DispatchError("invalid_input", "preview resource path escapes the snapshot");
        }
        await serveWithRange(absolute, mediaType, request, response);
      } catch (error) {
        const code = error instanceof DispatchError ? error.code : "invalid_input";
        const status = code === "not_found" ? 404 : code === "revision_conflict" ? 409 : 401;
        sendJson(response, status, { error: error instanceof Error ? error.message : String(error) });
      }
      return true;
    },
  };
}

async function serveWithRange(
  absolute: string,
  mediaType: string,
  request: IncomingMessage,
  response: ServerResponse,
): Promise<void> {
  let size: number;
  try {
    size = (await stat(absolute)).size;
  } catch {
    sendJson(response, 404, { error: "material missing from the frozen snapshot" });
    return;
  }
  const range = request.headers.range;
  const match = typeof range === "string" ? /^bytes=(\d+)-(\d*)$/u.exec(range.trim()) : null;
  if (match !== null) {
    const start = Number(match[1]);
    const end = match[2] === undefined || match[2] === "" ? size - 1 : Math.min(Number(match[2]), size - 1);
    if (!Number.isSafeInteger(start) || start > end || start >= size) {
      response.writeHead(416, { "content-range": `bytes */${size}` });
      response.end();
      return;
    }
    response.writeHead(206, {
      "content-type": mediaType,
      "content-length": String(end - start + 1),
      "content-range": `bytes ${start}-${end}/${size}`,
      "accept-ranges": "bytes",
      "cache-control": "no-store",
    });
    if (request.method === "HEAD") {
      response.end();
      return;
    }
    createReadStream(absolute, { start, end }).pipe(response);
    return;
  }
  response.writeHead(200, {
    "content-type": mediaType,
    "content-length": String(size),
    "accept-ranges": "bytes",
    "cache-control": "no-store",
  });
  if (request.method === "HEAD") {
    response.end();
    return;
  }
  createReadStream(absolute).pipe(response);
}
