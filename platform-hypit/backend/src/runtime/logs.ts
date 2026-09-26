// logs.ts — C107-09 (task-107) merged, sanitized Build logs (card step 09.9).
//
// Two native sources: the active runtime execution log (control.logs — a
// bounded tail) and the durable Result manifest's executionLog file (the
// repository copy). The merged view is stable-ordered (durable records
// first, live tail appended, consecutive duplicates removed), cursor paged
// and SANITIZED: absolute host paths fold to workspace-relative markers so
// operator/user views never leak private directories, signed URLs or keys.
import type { BuildResultFileRef, BuildResultRepository } from "@hypit/build-result";

export type LogRecord = {
  readonly at: string | null;
  readonly level: string | null;
  readonly message: string;
};

export type LogPage = {
  readonly records: readonly LogRecord[];
  readonly nextCursor: number | null;
  readonly total: number;
  readonly sources: readonly ("runtime" | "result")[];
};

export type RuntimeLogsReader = (build: string, lines: number) =>
  Promise<{ readonly records: readonly unknown[]; readonly total: number } | undefined>;

const MAX_LIMIT = 1000;
const DEFAULT_LIMIT = 100;

/** Fold absolute paths under the workspace root into `<workspace>/…` markers. */
export function sanitizeLine(line: string, workspaceRoot: string | null): string {
  let out = line;
  if (workspaceRoot !== null && workspaceRoot.length > 0) {
    out = out.split(workspaceRoot).join("<workspace>");
  }
  // Signed URLs and long bearer tokens never belong in user-visible logs.
  out = out.replace(/https?:\/\/[^\s"'<>]+(?:X[-_][A-Za-z-]*[Ss]ignature|signature|token|key)=[^\s&"'<>]+/g,
    "<redacted-url>");
  out = out.replace(/\b(Bearer|token|secret|api[_-]?key)\s*[:=]\s*\S{8,}/gi, "$1=<redacted>");
  return out;
}

/**
 * Read one page of merged logs. `cursor` indexes the merged, deduplicated
 * record list; `nextCursor=null` marks the current end (live tail may still
 * grow — the client re-polls).
 */
export async function readBuildLogs(options: {
  readonly repository: Pick<BuildResultRepository, "read" | "openFile">;
  readonly runtimeLogs: RuntimeLogsReader | undefined;
  readonly engineBuildId: string;
  readonly workspaceRoot: string | null;
  readonly cursor?: number | undefined;
  readonly limit?: number | undefined;
}): Promise<LogPage> {
  const cursor = Math.max(0, Math.trunc(options.cursor ?? 0));
  const limit = Math.min(MAX_LIMIT, Math.max(1, Math.trunc(options.limit ?? DEFAULT_LIMIT)));

  const durable: LogRecord[] = [];
  const manifest = await options.repository.read(options.engineBuildId);
  if (manifest?.executionLog !== undefined) {
    const text = await readRefText(options.repository, options.engineBuildId, manifest.executionLog);
    durable.push(...parseLogLines(text, options.workspaceRoot));
  }

  const live: LogRecord[] = [];
  if (options.runtimeLogs !== undefined) {
    const view = await options.runtimeLogs(options.engineBuildId, MAX_LIMIT);
    if (view !== undefined) {
      live.push(...view.records.map((record) => toRecord(record, options.workspaceRoot)));
    }
  }

  const merged = dedupeConcat(durable, live);
  const page = merged.slice(cursor, cursor + limit);
  const nextCursor = cursor + page.length;
  return {
    records: page,
    nextCursor: nextCursor < merged.length ? nextCursor : null,
    total: merged.length,
    sources: [
      ...(durable.length > 0 ? (["result"] as const) : []),
      ...(live.length > 0 ? (["runtime"] as const) : []),
    ],
  };
}

function toRecord(raw: unknown, workspaceRoot: string | null): LogRecord {
  const value = (raw ?? {}) as Record<string, unknown>;
  const at = typeof value.at === "number" ? new Date(value.at).toISOString()
    : typeof value.at === "string" ? value.at
    : null;
  const level = typeof value.level === "string" ? value.level : null;
  const message = typeof value.message === "string" ? value.message : JSON.stringify(value);
  return { at, level, message: sanitizeLine(message, workspaceRoot) };
}

function parseLogLines(text: string, workspaceRoot: string | null): LogRecord[] {
  const out: LogRecord[] = [];
  for (const line of text.split("\n")) {
    if (line.trim().length === 0) continue;
    try {
      out.push(toRecord(JSON.parse(line), workspaceRoot));
    } catch {
      out.push({ at: null, level: null, message: sanitizeLine(line, workspaceRoot) });
    }
  }
  return out;
}

/** Durable records first, live tail appended; consecutive duplicates removed. */
function dedupeConcat(durable: readonly LogRecord[], live: readonly LogRecord[]): LogRecord[] {
  const merged: LogRecord[] = [];
  let previousFingerprint: string | null = null;
  for (const record of [...durable, ...live]) {
    const fingerprint = `${record.at ?? ""}|${record.level ?? ""}|${record.message}`;
    if (fingerprint === previousFingerprint) continue;
    merged.push(record);
    previousFingerprint = fingerprint;
  }
  return merged;
}

async function readRefText(
  repository: Pick<BuildResultRepository, "openFile">,
  build: string,
  ref: BuildResultFileRef,
): Promise<string> {
  const stream = await repository.openFile(build, ref);
  if (stream === undefined) return "";
  const chunks: Uint8Array[] = [];
  let size = 0;
  for await (const chunk of stream) {
    size += chunk.byteLength;
    if (size > 8 * 1024 * 1024) break; // bounded: logs, never media bytes
    chunks.push(chunk);
  }
  return new TextDecoder().decode(concat(chunks));
}

function concat(chunks: readonly Uint8Array[]): Uint8Array {
  const total = chunks.reduce((sum, chunk) => sum + chunk.byteLength, 0);
  const out = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    out.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return out;
}
