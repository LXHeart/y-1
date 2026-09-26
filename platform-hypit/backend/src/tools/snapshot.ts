/**
 * C107-11 tools/snapshot.ts — exact-frame and paginated-grid capture from the
 * CURRENT Studio document (step 5). Snapshots photograph the same compiled
 * picture the final encoder photographs — via the native
 * `renderHyperframesFrames` provider path with the render Headless Shell — so
 * capturing never creates a new export Build and exact frames stay exact.
 */
import { renderHyperframesFrames } from "@hypit/provider-hyperframes-local";
import type { HyperframesDocument } from "@hypit/hyperframes";
import { DispatchError } from "../commands/dispatcher.ts";

export type SnapshotRequest = {
  readonly document: HyperframesDocument;
  /** Exact frame indices to photograph (frame 0 = first frame). */
  readonly frames?: readonly number[];
  /** Grid pagination: every grid page samples this many frames. */
  readonly framesPerPage?: number;
  readonly canvas?: { readonly width: number; readonly height: number };
};

export type SnapshotPage = {
  readonly page: number;
  readonly frameIndices: readonly number[];
};

/** Deterministic exact-frame/grid schedule (pure — unit tested without a browser). */
export function snapshotSchedule(request: SnapshotRequest): {
  readonly frames: readonly number[];
  readonly pages: readonly SnapshotPage[];
} {
  const frameCount = request.document.frameCount;
  if (frameCount <= 0) throw new DispatchError("invalid_input", "document has no frames");
  const explicit = request.frames ?? [];
  for (const frame of explicit) {
    if (!Number.isSafeInteger(frame) || frame < 0 || frame >= frameCount) {
      throw new DispatchError("invalid_input", `frame ${frame} is outside 0..${frameCount - 1}`);
    }
  }
  const frames = explicit.length > 0 ? [...explicit] : [0, Math.floor((frameCount - 1) / 2), frameCount - 1];
  const perPage = request.framesPerPage ?? frames.length;
  const pages: SnapshotPage[] = [];
  for (let index = 0; index < frames.length; index += perPage) {
    pages.push({ page: pages.length, frameIndices: frames.slice(index, index + perPage) });
  }
  return { frames, pages };
}

/**
 * Photograph exact frames through the SAME native render pipeline as the final
 * encode (render Headless Shell, chromePath resolved by the provider).
 */
export async function snapshotFrames(
  request: SnapshotRequest,
  options: Parameters<typeof renderHyperframesFrames>[1],
): Promise<Record<number, Uint8Array>> {
  const schedule = snapshotSchedule(request);
  const rendered = await renderHyperframesFrames({
    document: request.document,
    frames: schedule.frames,
  } as Parameters<typeof renderHyperframesFrames>[0], options);
  const byFrame: Record<number, Uint8Array> = {};
  const frames = (rendered as { readonly frames?: readonly { readonly frame: number; readonly bytes: Uint8Array }[] })
    .frames ?? [];
  for (const entry of frames) {
    byFrame[entry.frame] = entry.bytes;
  }
  return byFrame;
}
