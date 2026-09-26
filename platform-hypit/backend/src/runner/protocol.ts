// protocol.ts — C107-02 (task-107) runner IPC protocol (K10.4).
//
// Frames are length-prefixed UTF-8 JSON over the dedicated Unix domain socket:
//   [u32be length][length bytes of JSON]
// A frame larger than the negotiated limit (default 1 MiB) is a protocol error
// and closes the connection — big media bytes travel as slot-local file handles,
// never as base64 inside frames.
import { randomUUID } from "node:crypto";

export const RUNNER_PROTOCOL_VERSION = 1;
export const DEFAULT_FRAME_LIMIT_BYTES = 1024 * 1024;

/** Kinds the broker may ask of the runner. Anything else is rejected. */
export const RUNNER_COMMAND_KINDS = [
  "check",
  "plan",
  "compile",
  "executeLocal",
  "capture",
  "status",
  "cancel",
] as const;
export type RunnerCommandKind = (typeof RUNNER_COMMAND_KINDS)[number];

/** Events the runner may emit on its own (progress reporting). */
export const RUNNER_EVENT_KINDS = ["progress", "log"] as const;
export type RunnerEventKind = (typeof RUNNER_EVENT_KINDS)[number];

export type RunnerRequest = {
  readonly v: typeof RUNNER_PROTOCOL_VERSION;
  readonly commandId: string;
  readonly requestId: string;
  readonly kind: RunnerCommandKind;
  readonly payload: unknown;
};

export type RunnerResponse = {
  readonly v: typeof RUNNER_PROTOCOL_VERSION;
  readonly commandId: string;
  readonly requestId: string;
  readonly ok: boolean;
  readonly result?: unknown;
  readonly error?: RunnerError;
};

export type RunnerEvent = {
  readonly v: typeof RUNNER_PROTOCOL_VERSION;
  readonly commandId: string;
  readonly requestId: string;
  readonly kind: RunnerEventKind;
  readonly payload: unknown;
};

export type RunnerError = {
  readonly code: string;
  readonly message: string;
};

export type RunnerFrame = RunnerRequest | RunnerResponse | RunnerEvent;

export function isRunnerCommandKind(value: unknown): value is RunnerCommandKind {
  return typeof value === "string" && (RUNNER_COMMAND_KINDS as readonly string[]).includes(value);
}

export function newRequestId(): string {
  return randomUUID();
}

/** Encode one frame with its length prefix. */
export function encodeFrame(frame: RunnerFrame, limit = DEFAULT_FRAME_LIMIT_BYTES): Buffer {
  const json = Buffer.from(JSON.stringify(frame), "utf8");
  if (json.length > limit) {
    throw new Error(`runner frame exceeds ${limit} bytes (${json.length}); use slot file handles for large payloads`);
  }
  const head = Buffer.alloc(4);
  head.writeUInt32BE(json.length, 0);
  return Buffer.concat([head, json]);
}

/** Incremental frame decoder: feed socket chunks, receive complete frames. */
export class FrameDecoder {
  #buffer: Buffer = Buffer.alloc(0);
  readonly #limit: number;

  constructor(limit = DEFAULT_FRAME_LIMIT_BYTES) {
    this.#limit = limit;
  }

  push(chunk: Buffer): readonly RunnerFrame[] {
    this.#buffer = this.#buffer.length === 0 ? chunk : Buffer.concat([this.#buffer, chunk]);
    const frames: RunnerFrame[] = [];
    while (this.#buffer.length >= 4) {
      const length = this.#buffer.readUInt32BE(0);
      if (length > this.#limit) {
        throw new Error(`runner frame exceeds ${this.#limit} bytes (${length})`);
      }
      if (this.#buffer.length < 4 + length) break;
      const json = this.#buffer.subarray(4, 4 + length).toString("utf8");
      this.#buffer = this.#buffer.subarray(4 + length);
      frames.push(parseFrame(json));
    }
    return frames;
  }
}

function parseFrame(json: string): RunnerFrame {
  const value: unknown = JSON.parse(json);
  if (typeof value !== "object" || value === null || !("v" in value)) {
    throw new Error("runner frame is not a recognized message");
  }
  const frame = value as unknown as { v: unknown; commandId: unknown; requestId: unknown };
  if (frame.v !== RUNNER_PROTOCOL_VERSION) {
    throw new Error(`runner frame version ${String(frame.v)} is not ${RUNNER_PROTOCOL_VERSION}`);
  }
  if (typeof frame.commandId !== "string" || typeof frame.requestId !== "string") {
    throw new Error("runner frame is missing commandId/requestId");
  }
  // requests and events carry `kind`; responses carry `ok`
  if (!("kind" in frame) && !("ok" in frame)) {
    throw new Error("runner frame is neither a request/event nor a response");
  }
  return value as RunnerFrame;
}
