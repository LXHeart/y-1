// events.ts — C107-04 (task-107) per-job event persistence (§6.3 / 04.9).
//
// Events append with a per-job strictly increasing sequence inside one SQLite
// write transaction (single-writer broker ⇒ the "lock the job, then bump the
// sequence" rule of K05 collapses to a transactional INSERT). Readers get
// monotonic ids usable as Last-Event-ID cursors; SSE snapshots and resume are
// the Java consumer's concern — this module only guarantees the durable,
// gap-free sequence and idempotent terminal frames.
import type { CommandStore } from "./store.ts";

export type JobEventType =
  | "snapshot"
  | "progress"
  | "checkpoint"
  | "output"
  | "diagnostic"
  | "terminal"
  | "heartbeat";

export type StoredJobEvent = {
  readonly id: string;
  readonly jobId: string;
  readonly sequence: number;
  readonly type: JobEventType;
  readonly data: unknown;
  readonly createdAt: string;
};

const TERMINAL_TYPES = new Set<JobEventType>(["terminal"]);

export function appendJobEvent(
  store: CommandStore,
  jobId: string,
  type: JobEventType,
  data: unknown,
): StoredJobEvent {
  const now = new Date().toISOString();
  const sequence = nextSequence(store, jobId);
  insertEvent(store, { id: `evt-${jobId}-${sequence}`, jobId, sequence, type, data, createdAt: now });
  return { id: `evt-${jobId}-${sequence}`, jobId, sequence, type, data, createdAt: now };
}

/**
 * Terminal frames are idempotent: a repeated terminal append with identical
 * data returns the existing frame instead of growing the sequence (§6.3 终帧
 * 幂等). Non-terminal repeats always append — progress heartbeats may repeat.
 */
export function appendTerminalJobEvent(
  store: CommandStore,
  jobId: string,
  data: unknown,
): StoredJobEvent {
  const existing = readJobEvents(store, jobId, 0);
  for (let index = existing.length - 1; index >= 0; index -= 1) {
    const event = existing[index];
    if (event === undefined || !TERMINAL_TYPES.has(event.type)) break;
    if (JSON.stringify(event.data) === JSON.stringify(sortKeys(data))) return event;
  }
  return appendJobEvent(store, jobId, "terminal", data);
}

export function readJobEvents(store: CommandStore, jobId: string, afterSequence: number): readonly StoredJobEvent[] {
  return store.withRetryRead((db) =>
    db.prepare("SELECT * FROM job_events WHERE job_id = ? AND sequence > ? ORDER BY sequence")
      .all(jobId, afterSequence) as {
        id: string;
        job_id: string;
        sequence: number;
        type: string;
        payload: string | null;
        created_at: string;
      }[],
  ).map((row) => ({
    id: row.id,
    jobId: row.job_id,
    sequence: row.sequence,
    type: row.type as JobEventType,
    data: row.payload === null ? null : JSON.parse(row.payload),
    createdAt: row.created_at,
  }));
}

export function lastEventSequence(store: CommandStore, jobId: string): number {
  return store.withRetryRead((db) =>
    (db.prepare("SELECT MAX(sequence) AS max FROM job_events WHERE job_id = ?").get(jobId) as
      | { max: number | null }
      | undefined)?.max ?? 0,
  );
}

function nextSequence(store: CommandStore, jobId: string): number {
  return lastEventSequence(store, jobId) + 1;
}

function insertEvent(store: CommandStore, event: StoredJobEvent): void {
  store.withRetryWrite((db) => {
    db.prepare(
      "INSERT INTO job_events(id, job_id, sequence, type, payload, created_at) VALUES (?, ?, ?, ?, ?, ?)"
      + " ON CONFLICT(job_id, sequence) DO NOTHING",
    ).run(event.id, event.jobId, event.sequence, event.type, JSON.stringify(sortKeys(event.data)), event.createdAt);
  });
}

function sortKeys(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(sortKeys);
  if (value !== null && typeof value === "object") {
    const sorted: Record<string, unknown> = {};
    for (const key of Object.keys(value as Record<string, unknown>).sort()) {
      sorted[key] = sortKeys((value as Record<string, unknown>)[key]);
    }
    return sorted;
  }
  return value;
}
