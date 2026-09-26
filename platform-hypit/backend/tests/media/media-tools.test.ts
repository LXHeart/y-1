// media-tools.test.ts — C107-05 (task-107) T05-1/T05-3/T05-4 + range/unit
// expectations for the broker-side media tools over REAL generated fixtures
// (K13.1, providerMode=fixture/local): probe facts, mutual exclusion, phrase
// occurrence windows, everyFrame pagination continuity, scene boundaries, and
// HTTP Range planning (T05-2 semantics at the resource layer).
import { strict as assert } from "node:assert";
import { execFileSync } from "node:child_process";
import { existsSync, mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";

import {
  probeMedia, cutClip, extractFrames, intervalSamples, tileSampleTimes,
  readTranscript, phraseRanges, resolveTileWindow, visualBoundaries, composeGrid,
} from "../../src/tools/media.ts";
import { resolveRange, planRangeServe } from "../../src/resources/stream.ts";
import { assertFetchableUrl, UrlPolicyError, safeFetchTargetName } from "../../src/resources/url-policy.ts";
import { assertWithinRoots, HandleError } from "../../src/resources/handles.ts";

const fixturesRoot = join(tmpdir(), "hypit-media-fixtures");
const repoRoot = join(import.meta.dirname, "../../../..");

function ensureFixtures(): void {
  if (!existsSync(join(fixturesRoot, "verification-manifest.json"))) {
    execFileSync(process.execPath, [join(repoRoot, "platform-hypit/fixtures/generate-media.mjs"), fixturesRoot],
      { stdio: "inherit" });
  }
}

function fixturePath(name: string): string {
  return join(fixturesRoot, name);
}

test.before(() => ensureFixtures());

test("T05-1: probe reports real duration/fps/streams from generated fixtures", async () => {
  const silent = await probeMedia(fixturePath("silent-cuts.mp4"));
  assert.ok(silent.duration >= 5.8 && silent.duration <= 6.2, `silent-cuts duration ${silent.duration}`);
  assert.ok(silent.hasVideo);
  assert.ok(!silent.hasAudio);
  assert.ok(silent.frameRate !== null && Math.abs(silent.frameRate - 30) < 1, `fps ${silent.frameRate}`);

  const multi = await probeMedia(fixturePath("multi-stream.mp4"));
  assert.equal(multi.attachedPicStreams.length, 1, "attached_pic cover kept in stream facts");
  assert.equal(multi.audioStreams.length, 2, "two audio tracks reported");

  const clock = await probeMedia(fixturePath("av-clock.mp4"));
  assert.ok(clock.hasVideo && clock.hasAudio, "av-clock carries both modalities");
});

test("T05-1 unit: reject is mutual and targets are never overwritten", async () => {
  const out = mkdtempSync(join(tmpdir(), "hypit-cut-"));
  try {
    await assert.rejects(
      cutClip({ source: fixturePath("silent-cuts.mp4"), to: join(out, "a.mp4"), at: 1, start: 0, end: 2 }),
      /mutually exclusive/u,
    );
    await assert.rejects(
      cutClip({ source: fixturePath("silent-cuts.mp4"), to: join(out, "b.mp4"), start: 2 }),
      /either at or both/u,
    );
    const first = await cutClip({ source: fixturePath("silent-cuts.mp4"), to: join(out, "clip.mp4"), start: 1, end: 2 });
    // stream-copy cuts land on keyframes (lavfi default gop 250): assert the
    // honest keyframe-aligned window, not a fake exact 1.000s.
    assert.ok(first.durationSeconds >= 0.85 && first.durationSeconds <= 1.35, `cut duration ${first.durationSeconds}`);
    await assert.rejects(
      cutClip({ source: fixturePath("silent-cuts.mp4"), to: join(out, "clip.mp4"), at: 0 }),
      /already exists/u,
    );
  } finally {
    rmSync(out, { recursive: true, force: true });
  }
});

test("T05-3: around + occurrence 2 selects the SECOND phrase window", async () => {
  const words = await readTranscript(fixturePath("phrase-twice.transcript.json"));
  const ranges = phraseRanges(words, "榜单");
  assert.equal(ranges.length, 2, "phrase occurs twice");
  const window = resolveTileWindow({ transcriptWords: words, around: "榜单", occurrence: 2, padding: 0.25 });
  assert.ok(window.start >= 3.2 && window.end <= 4.15, `second-occurrence window ${JSON.stringify(window)}`);
  // frames sampled inside that window all carry timestamps belonging to it
  const times = tileSampleTimes(window.start, window.end, 4);
  assert.ok(times.every((time) => time >= window.start - 1e-6 && time <= window.end + 1e-6),
    "sample times stay inside the occurrence window");
  assert.throws(() => resolveTileWindow({ transcriptWords: words, around: "榜单", occurrence: 3 }),
    /occurrence 3/u);
});

test("T05-4: everyFrame over two ranges keeps timestamps continuous; pagination has no gap or duplicate", async () => {
  const first = intervalSamples(1, 2, 0.5);
  const second = intervalSamples(4, 5, 0.5);
  const all = [...first, ...second];
  assert.deepEqual(all, [1, 1.5, 2, 4, 4.5, 5]);
  const out = mkdtempSync(join(tmpdir(), "hypit-frames-"));
  try {
    const pages: number[] = [];
    let offset = 0;
    // paginate with limit 4: 2 pages for 6 samples, none repeated or dropped
    for (let page = 0; page < 2; page += 1) {
      const result = await extractFrames({ source: fixturePath("av-clock.mp4"), toDir: join(out, `p${page}`), times: all, offset, limit: 4 });
      pages.push(...result.frames.map((frame) => frame.timestampSeconds));
      offset += 4;
    }
    assert.deepEqual(pages, all, "pagination preserves every timestamp exactly once, in order");
  } finally {
    rmSync(out, { recursive: true, force: true });
  }
});

test("T05-1: scene boundaries find the red→blue cut near 3s", async () => {
  const boundaries = await visualBoundaries({ source: fixturePath("silent-cuts.mp4"), threshold: 0.3 });
  assert.ok(boundaries.length >= 1, "at least one scene boundary");
  assert.ok(boundaries.some((boundary) => boundary.at > 2.5 && boundary.at < 3.6),
    `boundary near 3s: ${JSON.stringify(boundaries)}`);
});

test("grids compose from extracted frames and carry per-frame time metadata", async () => {
  const out = mkdtempSync(join(tmpdir(), "hypit-grid-"));
  try {
    const times = tileSampleTimes(0.5, 2.5, 4);
    const extracted = await extractFrames({ source: fixturePath("silent-cuts.mp4"), toDir: out, times });
    const grid = await composeGrid(extracted.frames, join(out, "grid.png"), 2);
    assert.ok(existsSync(grid.grid));
    assert.deepEqual(grid.frames.map((frame) => frame.timestampSeconds), times);
  } finally {
    rmSync(out, { recursive: true, force: true });
  }
});

test("T05-2 unit: single-range planning returns 206/416 with exact bounds", () => {
  const size = 1000;
  const partial = planRangeServe(size, "video/mp4", resolveRange("bytes=0-9", size));
  assert.equal(partial.status, 206);
  assert.equal(partial.headers["Content-Range"], "bytes 0-9/1000");
  assert.equal(partial.length, 10);
  const openEnded = planRangeServe(size, "video/mp4", resolveRange("bytes=990-", size));
  assert.equal(openEnded.status, 206);
  assert.equal(openEnded.headers["Content-Range"], "bytes 990-999/1000");
  const suffix = planRangeServe(size, "video/mp4", resolveRange("bytes=-10", size));
  assert.equal(suffix.status, 206);
  assert.equal(suffix.offset, 990);
  const unsatisfiable = planRangeServe(size, "video/mp4", resolveRange("bytes=1000-", size));
  assert.equal(unsatisfiable.status, 416);
  assert.equal(unsatisfiable.headers["Content-Range"], "bytes */1000");
  const full = planRangeServe(size, "video/mp4", resolveRange(undefined, size));
  assert.equal(full.status, 200);
  assert.equal(resolveRange("bytes=0-1,5-9", size).kind, "invalid", "multi-range refused");
  assert.equal(resolveRange("chunks=0-9", size).kind, "invalid", "wrong unit refused");
});

test("T05-6 unit: URL policy refuses private hosts, credentials, bad ports, traversal targets", () => {
  const refused = [
    "http://127.0.0.1/x.mp4",
    "http://10.1.2.3/x.mp4",
    "http://169.254.169.254/latest/meta-data",
    "http://[::1]/x.mp4",
    "http://localhost/x.mp4",
    "http://fixture.internal/x.mp4",
    "ftp://example.com/x.mp4",
    "http://user:pass@example.com/x.mp4",
    "http://example.com:8080/x.mp4",
    "http://example.com/ x.mp4",
  ];
  for (const url of refused) {
    assert.throws(() => assertFetchableUrl(url), UrlPolicyError, url);
  }
  const allowed = assertFetchableUrl("https://example.com/a/b.mp4");
  assert.equal(allowed.hostname, "example.com");
  for (const name of ["../escape.mp4", "a/b.mp4", ".hidden", "", "x".repeat(200)]) {
    assert.throws(() => safeFetchTargetName(name), UrlPolicyError, name);
  }
  assert.equal(safeFetchTargetName("reference.mp4"), "reference.mp4");
});

test("resource containment: paths outside the allowed roots are refused", () => {
  const roots = ["/tmp/hypit-resources"];
  assert.doesNotThrow(() => assertWithinRoots(roots, "/tmp/hypit-resources/fetch/x.mp4"));
  assert.throws(() => assertWithinRoots(roots, "/tmp/other/x.mp4"), HandleError);
  assert.throws(() => assertWithinRoots(roots, "/tmp/hypit-resources/../escape.mp4"), HandleError);
});
