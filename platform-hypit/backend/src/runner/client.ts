// client.ts — C107-02 (task-107) broker-side runner IPC client.
//
// Connects to the runner's dedicated Unix domain socket, correlates responses
// by requestId, and enforces a per-request timeout. One client owns one socket;
// the supervisor decides process lifetime.
import { connect } from "node:net";
import { setTimeout as delay } from "node:timers/promises";

import {
  FrameDecoder,
  encodeFrame,
  newRequestId,
  type RunnerCommandKind,
  type RunnerResponse,
} from "./protocol.ts";

export type RunnerClientOptions = {
  readonly socketPath: string;
  readonly frameLimitBytes: number;
  readonly requestTimeoutMs: number;
};

type Pending = {
  readonly commandId: string;
  resolve: (response: RunnerResponse) => void;
  reject: (error: Error) => void;
  timer: NodeJS.Timeout;
};

export class RunnerClient {
  readonly #options: RunnerClientOptions;
  #socket: import("node:net").Socket | undefined;
  #decoder = new FrameDecoder();
  readonly #pending = new Map<string, Pending>();
  #closed = false;

  constructor(options: RunnerClientOptions) {
    this.#options = options;
  }

  get connected(): boolean {
    return this.#socket !== undefined && !this.#socket.destroyed;
  }

  async connect(): Promise<void> {
    if (this.#socket !== undefined) return;
    const socket = await new Promise<import("node:net").Socket>((resolvePromise, reject) => {
      const attempt = connect(this.#options.socketPath);
      const fail = (error: Error): void => {
        attempt.destroy();
        reject(error);
      };
      attempt.once("connect", () => {
        attempt.off("error", fail);
        resolvePromise(attempt);
      });
      attempt.once("error", fail);
    });
    socket.on("data", (chunk: Buffer) => {
      try {
        for (const frame of this.#decoder.push(chunk)) {
          if ("ok" in frame) this.#settle(frame as RunnerResponse);
        }
      } catch (error) {
        this.#failAll(error instanceof Error ? error : new Error(String(error)));
        socket.destroy();
      }
    });
    socket.once("close", () => {
      this.#failAll(new Error("runner socket closed before response"));
      this.#socket = undefined;
    });
    this.#socket = socket;
  }

  request(commandId: string, kind: RunnerCommandKind, payload: unknown): Promise<unknown> {
    if (this.#closed) throw new Error("runner client is closed");
    const socket = this.#socket;
    if (socket === undefined) throw new Error("runner client is not connected");
    const requestId = newRequestId();
    return new Promise((resolvePromise, reject) => {
      const timer = setTimeout(() => {
        this.#pending.delete(requestId);
        reject(new Error(`runner request ${kind} timed out after ${this.#options.requestTimeoutMs}ms`));
      }, this.#options.requestTimeoutMs);
      this.#pending.set(requestId, {
        commandId,
        resolve: (response) => {
          if (response.ok) resolvePromise(response.result);
          else reject(new Error(response.error?.message ?? "runner request failed"));
        },
        reject,
        timer,
      });
      socket.write(encodeFrame({
        v: 1,
        commandId,
        requestId,
        kind,
        payload,
      }, this.#options.frameLimitBytes));
    });
  }

  #settle(response: RunnerResponse): void {
    const pending = this.#pending.get(response.requestId);
    if (pending === undefined) return;
    this.#pending.delete(response.requestId);
    clearTimeout(pending.timer);
    pending.resolve(response);
  }

  #failAll(error: Error): void {
    for (const pending of this.#pending.values()) {
      clearTimeout(pending.timer);
      pending.reject(error);
    }
    this.#pending.clear();
  }

  close(): void {
    this.#closed = true;
    this.#failAll(new Error("runner client closed"));
    this.#socket?.destroy();
    this.#socket = undefined;
  }
}

/** Retry-connect helper: the runner may still be binding its socket. */
export async function connectWithRetry(
  client: RunnerClient,
  attempts = 50,
  intervalMs = 100,
): Promise<void> {
  let lastError: unknown;
  for (let attempt = 0; attempt < attempts; attempt += 1) {
    try {
      await client.connect();
      return;
    } catch (error) {
      lastError = error;
      await delay(intervalMs);
    }
  }
  throw lastError instanceof Error ? lastError : new Error("runner socket never became connectable");
}
