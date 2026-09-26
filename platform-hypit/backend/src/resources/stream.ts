// stream.ts — C107-05 (task-107) HTTP Range semantics over registered files.
//
// Single-range `bytes=` parsing per RFC 9110 (what a media element sends for
// <video> scrubbing). Multi-range requests are refused with 416 rather than
// silently served wrong; suffix and open-ended forms are supported.

export type RangeSpec =
  | { readonly kind: "full" }
  | { readonly kind: "range"; readonly start: number; readonly end: number };

export type RangeDecision =
  | { readonly kind: "full" }
  | { readonly kind: "range"; readonly start: number; readonly end: number; readonly total: number }
  | { readonly kind: "unsatisfiable"; readonly total: number }
  | { readonly kind: "invalid" };

/**
 * Parse a Range header against a known file size.
 * - absent / `bytes=` with wrong unit → serve full (200)
 * - `bytes=a-b`, `bytes=a-`, `bytes=-suffix` → 206 with inclusive bounds
 * - multi-range, non-numeric, end<start, start>=size, suffix of 0 → 416/invalid
 */
export function resolveRange(header: string | undefined, sizeBytes: number): RangeDecision {
  if (typeof header !== "string" || header.trim() === "") return { kind: "full" };
  if (sizeBytes <= 0) return { kind: "unsatisfiable", total: sizeBytes };
  const match = /^bytes=(.*)$/u.exec(header.trim());
  if (match === null) return { kind: "invalid" };
  const spec = match[1]!;
  if (spec.includes(",")) return { kind: "invalid" }; // single range only
  const range = /^(\d*)-(\d*)$/u.exec(spec);
  if (range === null || (range[1] === "" && range[2] === "")) return { kind: "invalid" };
  const [, rawStart, rawEnd] = range;
  let start: number;
  let end: number;
  if (rawStart === "") {
    const suffix = Number(rawEnd);
    if (!Number.isSafeInteger(suffix) || suffix <= 0) return { kind: "invalid" };
    if (suffix > sizeBytes) return { kind: "range", start: 0, end: sizeBytes - 1, total: sizeBytes };
    start = sizeBytes - suffix;
    end = sizeBytes - 1;
  } else {
    start = Number(rawStart);
    if (!Number.isSafeInteger(start) || start < 0) return { kind: "invalid" };
    if (start >= sizeBytes) return { kind: "unsatisfiable", total: sizeBytes };
    if (rawEnd === "") {
      end = sizeBytes - 1;
    } else {
      end = Number(rawEnd);
      if (!Number.isSafeInteger(end) || end < start) return { kind: "invalid" };
      if (end >= sizeBytes) end = sizeBytes - 1; // clamp, per RFC
    }
  }
  return { kind: "range", start, end, total: sizeBytes };
}

export type ServedRange = {
  readonly status: 200 | 206 | 416;
  readonly headers: Readonly<Record<string, string>>;
  readonly offset: number;
  readonly length: number;
};

/** Materialize the serve decision (status + headers + byte window). */
export function planRangeServe(sizeBytes: number, mediaType: string, range: RangeDecision): ServedRange {
  const common = { "Accept-Ranges": "bytes", "Content-Type": mediaType };
  if (range.kind === "range") {
    const length = range.end - range.start + 1;
    return {
      status: 206,
      headers: {
        ...common,
        "Content-Range": `bytes ${range.start}-${range.end}/${range.total}`,
        "Content-Length": String(length),
      },
      offset: range.start,
      length,
    };
  }
  if (range.kind === "unsatisfiable") {
    return {
      status: 416,
      headers: { ...common, "Content-Range": `bytes */${range.total}`, "Content-Length": "0" },
      offset: 0,
      length: 0,
    };
  }
  return {
    status: 200,
    headers: { ...common, "Content-Length": String(sizeBytes) },
    offset: 0,
    length: sizeBytes,
  };
}
