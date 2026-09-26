// server.ts — C107-02 (task-107) isolated runner service (K10.4).
//
// The runner listens on one dedicated Unix domain socket. The broker connects
// and sends whitelisted command kinds; the runner never dials out. Author code
// (project packages) is evaluated only here, inside the slot directory the
// supervisor prepared, under the process-level read scope enforced by the
// supervisor's --permission flags. Errors are sanitized: host paths never leave
// this process in messages.
import { createServer } from "node:net";
import { rm } from "node:fs/promises";
import { realpath, stat } from "node:fs/promises";
import { resolve } from "node:path";

import { FrameDecoder, encodeFrame, isRunnerCommandKind, RUNNER_PROTOCOL_VERSION } from "./protocol.ts";
import { runCheck, runCompile } from "../engine/compile-adapter.ts";
import { planRunInRunner } from "../engine/planning.ts";

export type RunnerServerOptions = {
  readonly socketPath: string;
  readonly distributionRoot: string;
  /** Slot root: every workspace path in a payload must resolve inside this tree. */
  readonly slotRoot: string;
  /** Slot output dir for materialized attachment handles. */
  readonly slotOutputDir: string;
  readonly frameLimitBytes: number;
};

const NOT_IMPLEMENTED_KINDS = new Set(["executeLocal", "capture", "cancel"]);

export async function startRunnerServer(options: RunnerServerOptions): Promise<{
  readonly close: () => Promise<void>;
  readonly socketPath: string;
}> {
  const slotRoot = await realpath(options.slotRoot);
  const server = createServer((socket) => {
    const decoder = new FrameDecoder(options.frameLimitBytes);
    socket.on("data", (chunk: Buffer) => {
      let frames;
      try {
        frames = decoder.push(chunk);
      } catch (error) {
        socket.write(encodeFrame(errorFrame("startup", "protocol", {
          code: "frame_limit",
          message: error instanceof Error ? error.message : String(error),
        }), options.frameLimitBytes));
        socket.destroy();
        return;
      }
      for (const frame of frames) {
        if (!("payload" in frame) || "ok" in frame) continue; // requests only
        void handle(options, slotRoot, frame as { commandId: string; requestId: string; kind: string; payload: unknown })
          .then((result) => {
            socket.write(encodeFrame({
              v: RUNNER_PROTOCOL_VERSION,
              commandId: frame.commandId,
              requestId: frame.requestId,
              ok: true,
              result,
            }, options.frameLimitBytes));
          })
          .catch((error: unknown) => {
            if (process.env.HYPIT_RUNNER_DEBUG === "1") {
              console.error("[runner-debug]", error);
            }
            socket.write(encodeFrame({
              v: RUNNER_PROTOCOL_VERSION,
              commandId: frame.commandId,
              requestId: frame.requestId,
              ok: false,
              error: sanitizeError(error, [slotRoot, options.distributionRoot]),
            }, options.frameLimitBytes));
          });
      }
    });
    socket.on("error", () => socket.destroy());
  });
  await rm(options.socketPath, { force: true });
  await new Promise<void>((resolvePromise) => server.listen(options.socketPath, resolvePromise));
  return {
    socketPath: options.socketPath,
    close: async () => {
      await new Promise<void>((resolvePromise) => server.close(() => resolvePromise()));
      await rm(options.socketPath, { force: true });
    },
  };
}

async function handle(
  options: RunnerServerOptions,
  slotRoot: string,
  frame: { commandId: string; requestId: string; kind: string; payload: unknown },
): Promise<unknown> {
  if (!isRunnerCommandKind(frame.kind)) {
    throw new RunnerError(`unknown runner kind "${frame.kind}"`, "unknown_kind");
  }
  if (NOT_IMPLEMENTED_KINDS.has(frame.kind)) {
    throw new RunnerError(
      `runner kind "${frame.kind}" is recognized but not implemented in this build`,
      "not_implemented",
    );
  }
  if (frame.kind === "status") {
    return { state: "ready", slotRoot: "<slot>", version: RUNNER_PROTOCOL_VERSION };
  }
  const payload = frame.payload;
  if (typeof payload !== "object" || payload === null) {
    throw new RunnerError("payload must be an object", "invalid_payload");
  }
  const record = payload as { readonly workspaceRoot?: unknown; readonly entryFile?: unknown; readonly runFile?: unknown };
  if (typeof record.workspaceRoot !== "string") {
    throw new RunnerError("payload.workspaceRoot must be a string", "invalid_payload");
  }
  const workspaceRoot = await scopedPath(slotRoot, record.workspaceRoot);
  if (frame.kind === "check") {
    if (typeof record.entryFile !== "string") {
      throw new RunnerError("check requires payload.entryFile", "invalid_payload");
    }
    return await runCheck(
      {
        distributionRoot: options.distributionRoot,
        attachmentOutputDir: options.slotOutputDir,
      },
      {
        workspaceRoot,
        entryFile: record.entryFile,
        revision: null,
      },
    );
  }
  if (frame.kind === "plan") {
    if (typeof record.runFile !== "string") {
      throw new RunnerError("plan requires payload.runFile", "invalid_payload");
    }
    return await planRunInRunner(
      { distributionRoot: options.distributionRoot },
      { workspaceRoot, runFile: record.runFile },
    );
  }
  // compile
  if (typeof record.runFile !== "string") {
    throw new RunnerError("compile requires payload.runFile", "invalid_payload");
  }
  return await runCompile(
    {
      distributionRoot: options.distributionRoot,
      attachmentOutputDir: options.slotOutputDir,
    },
    { workspaceRoot, runFile: record.runFile, revision: null },
  );
}

/** Resolve a payload path and require it to stay inside the slot (defense in depth). */
async function scopedPath(slotRoot: string, value: string): Promise<string> {
  const resolved = resolve(slotRoot, value);
  // realpath/stat failures cover both missing paths and paths outside the
  // process read scope (the permission model denies traversal) — both mean
  // the payload cannot name a slot-local workspace.
  let real: string;
  try {
    real = await realpath(resolved);
  } catch {
    const insidePrefix = resolved === slotRoot || resolved.startsWith(slotRoot + "/");
    throw new RunnerError(
      insidePrefix ? "payload path does not exist" : "payload path escapes the runner slot",
      insidePrefix ? "path_missing" : "path_escape",
    );
  }
  if (real !== slotRoot && !real.startsWith(slotRoot + "/")) {
    throw new RunnerError("payload path escapes the runner slot", "path_escape");
  }
  return real;
}

export class RunnerError extends Error {
  readonly code: string;
  constructor(message: string, code: string) {
    super(message);
    this.name = "RunnerError";
    this.code = code;
  }
}

function errorFrame(commandId: string, requestId: string, error: { code: string; message: string }): import("./protocol.ts").RunnerResponse {
  return { v: RUNNER_PROTOCOL_VERSION, commandId, requestId, ok: false, error };
}

/** Replace host-identifying absolute roots before an error crosses the socket. */
export function sanitizeError(error: unknown, privateRoots: readonly string[]): { code: string; message: string } {
  let message = error instanceof Error ? error.message : String(error);
  const code = error instanceof RunnerError ? error.code : "internal";
  for (const root of privateRoots) {
    if (root.length > 1) message = message.split(root).join("<runner>");
  }
  return { code, message };
}

// CLI entry: node --import tsx src/runner/server.ts --socket S --slot-root R
// --slot-output O --distribution-root G [--frame-limit N]
// Configuration arrives as argv, never env: author code evaluated in this
// process must not discover slot or host paths through process.env.
const isEntry = process.argv[1] !== undefined
  && import.meta.url === new URL(`file://${process.argv[1]}`).href;
if (isEntry) {
  function argValue(name: string): string | undefined {
    const index = process.argv.indexOf(name);
    return index >= 0 ? process.argv[index + 1] : undefined;
  }
  const socketPath = argValue("--socket");
  const distributionRoot = argValue("--distribution-root");
  const slotRoot = argValue("--slot-root");
  const slotOutputDir = argValue("--slot-output");
  const frameLimit = Number(argValue("--frame-limit") ?? 1024 * 1024);
  if (socketPath === undefined || distributionRoot === undefined
    || slotRoot === undefined || slotOutputDir === undefined) {
    console.error("runner server requires --socket, --distribution-root, --slot-root and --slot-output");
    process.exit(2);
  }
  const started = await startRunnerServer({
    socketPath,
    distributionRoot,
    slotRoot,
    slotOutputDir,
    frameLimitBytes: Number.isSafeInteger(frameLimit) && frameLimit > 0 ? frameLimit : 1024 * 1024,
  });
  process.on("SIGTERM", () => {
    void started.close().finally(() => process.exit(0));
  });
  process.on("SIGINT", () => {
    void started.close().finally(() => process.exit(0));
  });
}
