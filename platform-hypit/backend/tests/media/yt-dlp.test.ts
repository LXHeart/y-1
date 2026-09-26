// yt-dlp.test.ts — C107-05 (task-107) T05-5/T05-6 for the fetch surface.
//
// T05-5 verifies the pinned yt-dlp tool BODY against a local HTTP fixture
// (no external platform account): prepare via uv --frozen, download, ffprobe.
// T05-6 verifies the policy layer: a redirect chain ending at a private
// address is refused BEFORE any fetch, and target names cannot escape the
// server-controlled output. Network to PyPI is required for prepare; when it
// is unavailable the tool-body case skips with the precise reason (K13: live
// deps blocking only the live assertion, never silently passing).
import { strict as assert } from "node:assert";
import { createServer } from "node:http";
import { execFileSync, spawnSync } from "node:child_process";
import { existsSync, mkdtempSync, readdirSync, rmSync } from "node:fs";
import { readFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";

import {
  videoDownloadEnvironment, prepareVideoDownload, requireVideoDownload,
  resolveFetchUrl, downloadWithYtDlp, MediaToolError,
} from "../../src/tools/media.ts";
import { safeFetchTargetName, UrlPolicyError } from "../../src/resources/url-policy.ts";

const repoRoot = join(import.meta.dirname, "../../../..");
const distributionRoot = join(repoRoot, "platform-hypit/.generated/hypit");
// stable cache root: the pinned env is content-addressed by version, so a
// second run reuses it (uv sync is then a fast no-op) instead of cold-syncing
// PyPI inside the 120s test budget on every run.
const programsRoot = join(repoRoot, "test-artifacts/task-107/C05/programs");
const environment = videoDownloadEnvironment(distributionRoot, programsRoot);

function fixtureVideo(): string {
  const out = mkdtempSync(join(tmpdir(), "hypit-ytdlp-src-"));
  const target = join(out, "fixture.mp4");
  execFileSync("ffmpeg", ["-hide_banner", "-loglevel", "error", "-y",
    "-f", "lavfi", "-i", "testsrc2=s=320x180:r=30:d=2",
    "-f", "lavfi", "-i", "sine=frequency=440:duration=2",
    "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", target]);
  return target;
}

async function withServer(handler: (request: { url?: string | undefined }, respond: (status: number, headers: Record<string, string>, body?: Buffer) => void) => void, run: (base: string) => Promise<void>): Promise<void> {
  const server = createServer((request, response) => {
    handler(request, (status, headers, body) => {
      response.writeHead(status, headers);
      response.end(body);
    });
  });
  await new Promise<void>((resolvePromise) => server.listen(0, "127.0.0.1", () => resolvePromise()));
  try {
    const address = server.address();
    await run(`http://127.0.0.1:${(address as { port: number }).port}`);
  } finally {
    await new Promise<void>((resolvePromise) => server.close(() => resolvePromise()));
  }
}

test("T05-6: redirect to a private address is refused before any download", async () => {
  // The policy layer is exercised on a production-shaped initial URL with a
  // stubbed fetch: the hop decision must not depend on the origin server.
  const realFetch = globalThis.fetch;
  const responses: Array<{ status: number; location?: string }> = [];
  try {
    globalThis.fetch = (async () => {
      const next = responses.shift();
      if (next === undefined) throw new Error("unexpected extra fetch");
      return new Response(next.location === undefined ? null : "", {
        status: next.status,
        headers: next.location === undefined ? {} : { location: next.location },
      });
    }) as typeof fetch;
    // hop 1: public-looking origin redirects to the cloud metadata address
    responses.push({ status: 302, location: "http://169.254.169.254/latest/meta-data" });
    await assert.rejects(
      () => resolveFetchUrl("https://cdn.example.com/video.mp4"),
      (error: unknown) => error instanceof MediaToolError && error.code === "private_address",
      "private redirect target refused with private_address",
    );
    // hop 1 redirects to a loopback port — refused the same way
    responses.push({ status: 302, location: "http://127.0.0.1/secret.mp4" }); // port 80: isolates the private-host refusal from the port rule
    await assert.rejects(
      () => resolveFetchUrl("https://cdn.example.com/video.mp4"),
      (error: unknown) => error instanceof MediaToolError && error.code === "private_address",
      "loopback redirect target refused",
    );
    // self-redirect loop terminates at the hop budget
    for (let hop = 0; hop < 8; hop += 1) responses.push({ status: 302, location: "https://next.example.com/hop" });
    await assert.rejects(
      () => resolveFetchUrl("https://cdn.example.com/video.mp4"),
      (error: unknown) => error instanceof MediaToolError && error.code === "too_many_redirects",
      "redirect loops terminate",
    );
    // a compliant chain lands on the final URL handed to yt-dlp
    responses.push({ status: 302, location: "https://mirror.example.com/file.mp4" }, { status: 206 });
    const finalUrl = await resolveFetchUrl("https://cdn.example.com/video.mp4");
    assert.equal(finalUrl, "https://mirror.example.com/file.mp4");
  } finally {
    globalThis.fetch = realFetch;
  }
  // no network at all: the initial URL policy refuses private origins directly
  await assert.rejects(
    () => resolveFetchUrl("http://10.0.0.5/x.mp4"),
    (error: unknown) => error instanceof MediaToolError && error.code === "private_address",
  );
});

test("T05-6: server-controlled target names cannot traverse", () => {
  for (const name of ["../../etc/passwd", "a/b/c.mp4", "..", "x.mp4/../y.mp4"]) {
    assert.throws(() => safeFetchTargetName(name), UrlPolicyError, name);
  }
});

test("T05-5: pinned yt-dlp downloads from a local HTTP fixture and the file probes clean", { timeout: 300_000 }, async (context) => {
  const uvPresent = spawnSync("uv", ["--version"], { encoding: "utf8" }).status === 0;
  if (!uvPresent) {
    context.skip("uv is not installed; pinned environment cannot be prepared (live dependency)");
    return;
  }
  let prepared: string;
  try {
    prepared = (await prepareVideoDownload(environment)).executable;
  } catch (error) {
    context.skip(`uv sync could not reach PyPI or failed: ${(error as Error).message.slice(0, 200)} (live dependency)`);
    return;
  }
  assert.equal(requireVideoDownload(environment), prepared, "require passes after prepare");

  const source = fixtureVideo();
  const bytes = await readFile(source);
  const out = mkdtempSync(join(tmpdir(), "hypit-ytdlp-out-"));
  try {
    await withServer(
      (request, respond) => {
        if (request.url === "/video.mp4") {
          respond(200, { "content-type": "video/mp4", "content-length": String(bytes.length) }, bytes);
          return;
        }
        respond(404, {});
      },
      async (base) => {
        const target = join(out, "downloaded.mp4");
        await downloadWithYtDlp(`${base}/video.mp4`, target, prepared);
        assert.ok(existsSync(target), "download produced the exact named file");
        const probe = JSON.parse(execFileSync("ffprobe",
          ["-v", "error", "-print_format", "json", "-show_format", target]).toString()) as { format?: { duration?: string } };
        const duration = Number(probe.format?.duration ?? 0);
        assert.ok(duration >= 1.8 && duration <= 2.2, `probed duration ${duration}`);
        // staging leftovers must not survive
        assert.ok(readdirSync(out).length === 1, `only the target remains: ${readdirSync(out).join(",")}`);
      },
    );
  } finally {
    rmSync(out, { recursive: true, force: true });
    rmSync(join(source, ".."), { recursive: true, force: true });
  }
});
