// store.ts — C107-04 (task-107) sidecar command persistence (K05 sidecar
// persistence). bridge.sqlite is the trusted broker's OWN durable state — it
// never replaces the Java business tables, never grows upstream Runtime
// schema, and never holds media bytes or secrets. It records command
// idempotency (commandId → kind/payloadHash/state/result), an index over the
// workspace file journals, resource-handle mappings and per-job event
// sequences. Uses node:sqlite with bounded busy retries (K05).
import { createHash } from "node:crypto";
import { mkdirSync } from "node:fs";
import { dirname } from "node:path";
import { DatabaseSync } from "node:sqlite";

export type CommandState = "queued" | "dispatching" | "acknowledged" | "succeeded" | "failed" | "unknown";

export type StoredCommand = {
  readonly commandId: string;
  readonly kind: string;
  readonly payloadHash: string;
  readonly projectId: string | null;
  readonly state: CommandState;
  readonly resultJson: string | null;
  readonly errorCode: string | null;
  readonly errorMessage: string | null;
  readonly engineBuildId: string | null;
  readonly createdAt: string;
  readonly updatedAt: string;
};

export class CommandConflictError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "CommandConflictError";
  }
}

const BUSY_RETRY_DELAYS_MS = [10, 25, 50, 100, 200, 400, 800];

export class CommandStore {
  private readonly db: DatabaseSync;

  constructor(file: string) {
    awaitSyncMkdir(file);
    this.db = new DatabaseSync(file);
    this.db.exec("PRAGMA journal_mode = WAL");
    this.db.exec("PRAGMA synchronous = FULL");
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS commands(
        command_id TEXT PRIMARY KEY,
        kind TEXT NOT NULL,
        payload_hash TEXT NOT NULL,
        project_id TEXT,
        state TEXT NOT NULL,
        result_json TEXT,
        error_code TEXT,
        error_message TEXT,
        engine_build_id TEXT,
        created_at TEXT NOT NULL,
        updated_at TEXT NOT NULL
      );
      CREATE TABLE IF NOT EXISTS file_transactions(
        journal_id TEXT PRIMARY KEY,
        project_id TEXT NOT NULL,
        command_id TEXT,
        state TEXT NOT NULL,
        created_at TEXT NOT NULL,
        updated_at TEXT NOT NULL
      );
      CREATE TABLE IF NOT EXISTS resource_handles(
        handle TEXT PRIMARY KEY,
        project_id TEXT,
        absolute_path TEXT NOT NULL,
        media_type TEXT,
        created_at TEXT NOT NULL
      );
      CREATE TABLE IF NOT EXISTS job_events(
        id TEXT NOT NULL,
        job_id TEXT NOT NULL,
        sequence INTEGER NOT NULL,
        type TEXT NOT NULL,
        payload TEXT,
        created_at TEXT NOT NULL,
        PRIMARY KEY (job_id, sequence)
      );
    `);
  }

  close(): void {
    this.db.close();
  }

  /** SHA-256 over canonical payload JSON (commandId/requestId excluded upstream). */
  static hashPayload(kind: string, payload: unknown): string {
    const canonical = JSON.stringify({ kind, payload: sortKeysDeep(payload) });
    return createHash("sha256").update(canonical, "utf8").digest("hex");
  }

  /**
   * K06 accept algorithm for the sidecar: same commandId + same payload hash
   * returns the recorded row (idempotent replay); same id + different hash is
   * a conflict; new id inserts `queued`.
   */
  accept(commandId: string, kind: string, payloadHash: string, projectId: string | null): StoredCommand {
    return this.withRetry(() => {
      const now = new Date().toISOString();
      const insert = this.db.prepare(
        "INSERT INTO commands(command_id, kind, payload_hash, project_id, state, created_at, updated_at)"
        + " VALUES (?, ?, ?, ?, 'queued', ?, ?) ON CONFLICT(command_id) DO NOTHING RETURNING *",
      ).get(commandId, kind, payloadHash, projectId, now, now);
      if (insert !== undefined) return mapRow(insert);
      const existing = this.get(commandId);
      if (existing === undefined) throw new Error("command disappeared after insert");
      if (existing.payloadHash !== payloadHash) {
        throw new CommandConflictError(
          `command ${commandId} already accepted with a different payload hash`,
        );
      }
      return existing;
    });
  }

  get(commandId: string): StoredCommand | undefined {
    return this.withRetry(() => {
      const row = this.db.prepare("SELECT * FROM commands WHERE command_id = ?").get(commandId);
      return row === undefined ? undefined : mapRow(row);
    });
  }

  transition(commandId: string, mutate: (row: StoredCommand) => Partial<StoredCommand>): StoredCommand {
    return this.withRetry(() => {
      const row = this.get(commandId);
      if (row === undefined) throw new Error(`unknown command: ${commandId}`);
      const patch = mutate(row);
      const next: StoredCommand = { ...row, ...patch, updatedAt: new Date().toISOString() };
      this.db.prepare(
        "UPDATE commands SET state = ?, result_json = ?, error_code = ?, error_message = ?,"
        + " engine_build_id = ?, updated_at = ? WHERE command_id = ?",
      ).run(
        next.state,
        next.resultJson ?? null,
        next.errorCode ?? null,
        next.errorMessage ?? null,
        next.engineBuildId ?? null,
        next.updatedAt,
        commandId,
      );
      return next;
    });
  }

  indexFileTransaction(journalId: string, projectId: string, commandId: string | null, state: string): void {
    this.withRetry(() => {
      const now = new Date().toISOString();
      this.db.prepare(
        "INSERT INTO file_transactions(journal_id, project_id, command_id, state, created_at, updated_at)"
        + " VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT(journal_id) DO UPDATE SET state = excluded.state,"
        + " updated_at = excluded.updated_at",
      ).run(journalId, projectId, commandId, state, now, now);
    });
  }

  registerResourceHandle(handle: string, absolutePath: string, projectId: string | null, mediaType: string | null): void {
    this.withRetry(() => {
      this.db.prepare(
        "INSERT INTO resource_handles(handle, project_id, absolute_path, media_type, created_at)"
        + " VALUES (?, ?, ?, ?, ?) ON CONFLICT(handle) DO NOTHING",
      ).run(handle, projectId, absolutePath, mediaType, new Date().toISOString());
    });
  }

  resolveResourceHandle(handle: string): { readonly absolutePath: string; readonly projectId: string | null } | undefined {
    const row = this.withRetry(() =>
      this.db.prepare("SELECT absolute_path, project_id FROM resource_handles WHERE handle = ?").get(handle) as
        | { absolute_path: string; project_id: string | null }
        | undefined,
    );
    return row === undefined ? undefined : { absolutePath: row.absolute_path, projectId: row.project_id };
  }

  /** Raw read access under the bounded busy-retry policy (events module). */
  withRetryRead<T>(operation: (db: DatabaseSync) => T): T {
    return this.withRetry(() => operation(this.db));
  }

  /** Raw write access under the bounded busy-retry policy (events module). */
  withRetryWrite(operation: (db: DatabaseSync) => void): void {
    this.withRetry(() => operation(this.db));
  }

  private withRetry<T>(operation: () => T): T {
    let lastError: unknown;
    for (let attempt = 0; attempt <= BUSY_RETRY_DELAYS_MS.length; attempt += 1) {
      try {
        return operation();
      } catch (error) {
        if (!isSqliteBusy(error)) throw error;
        lastError = error;
        const delay = BUSY_RETRY_DELAYS_MS[attempt];
        if (delay === undefined) break;
        Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, delay);
      }
    }
    throw lastError;
  }
}

function isSqliteBusy(error: unknown): boolean {
  return error instanceof Error && error.message.includes("SQLITE_BUSY");
}

/** Map a snake_case commands row onto the camelCase StoredCommand shape. */
function mapRow(row: unknown): StoredCommand {
  const raw = row as Record<string, unknown>;
  return {
    commandId: String(raw.command_id),
    kind: String(raw.kind),
    payloadHash: String(raw.payload_hash),
    projectId: raw.project_id === null ? null : String(raw.project_id),
    state: String(raw.state) as StoredCommand["state"],
    resultJson: raw.result_json === null ? null : String(raw.result_json),
    errorCode: raw.error_code === null ? null : String(raw.error_code),
    errorMessage: raw.error_message === null ? null : String(raw.error_message),
    engineBuildId: raw.engine_build_id === null ? null : String(raw.engine_build_id),
    createdAt: String(raw.created_at),
    updatedAt: String(raw.updated_at),
  };
}

function sortKeysDeep(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(sortKeysDeep);
  if (value !== null && typeof value === "object") {
    const sorted: Record<string, unknown> = {};
    for (const key of Object.keys(value as Record<string, unknown>).sort()) {
      sorted[key] = sortKeysDeep((value as Record<string, unknown>)[key]);
    }
    return sorted;
  }
  return value;
}

// node:sqlite constructor is sync; ensure the parent directory exists first.
function awaitSyncMkdir(file: string): void {
  mkdirSync(dirname(file), { recursive: true });
}
