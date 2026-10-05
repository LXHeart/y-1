// fix3-reference-contract.test.ts — C107F3-07 (task-107 fix3) TC-F3-07-01.
//
// Real broker wire contract over the F-REF fixtures (W64, real FFmpeg):
//   - media.probe returns the NESTED facts object `{probe:{duration,hasVideo,
//     hasAudio,width,height}}` — no legacy flat top-level durationSeconds/hasAudio;
//   - media.frames with explicit times returns `{frames:[{handle,timestampSeconds}],
//     totalTimes}` — receipt times equal the requested six midpoints and every
//     handle resolves to real decodable PNG bytes (320×240, red/green/blue
//     segment colours at 1s/5s/9s);
//   - speech normalization (the tool's defined last-hop ASR receipt shape) keeps
//     word order and converts seconds → 16 kHz sample anchors; words outside the
//     evidence audio lose their window (absent anchors, never zero); empty
//     segments are measured silence, not a failure.
// C107F3-08 (TC-F3-08-01 node leg): the frame handles media.frames returns are
// served by the REAL broker resource route (spawned src/main.mjs, internal
// bearer auth) as byte-stable, decodable PNGs within the RULE-008 4 MiB frame
// budget — the exact wire W14 (Java) consumes via W15.open.
// whisperx.local 真实 ASR 仍按 §1.4 范围外 NOT_RUN（EXTERNAL_BLOCKED，见 TC-F3-04-03）；
// 本用例只断言已定义回执形状的归一化，不声称识别质量。
import { strict as assert } from "node:assert";
import { spawn, spawnSync } from "node:child_process";
import { createHash, randomBytes } from "node:crypto";
import { inflateSync } from "node:zlib";
import { mkdirSync, mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { setTimeout as delay } from "node:timers/promises";
import test from "node:test";

import { runMediaTool, type MediaToolContext } from "../../src/tools/media.ts";
import { standardEvidence } from "../../src/tools/speech.ts";
import { openHandleRegistry, registerResource, resolveResource } from "../../src/resources/handles.ts";
import { CommandStore } from "../../src/commands/store.ts";

const repoRoot = join(import.meta.dirname, "../../../..");
const backendRoot = join(import.meta.dirname, "../..");
const generatedRoot = join(backendRoot, "../.generated/hypit");
const PNG_MAGIC = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];

/** Average RGB of the centre 4×4 crop at `time` — the same truth W64 records in its manifest. */
function centreRgb(video: string, time: number): [number, number, number] {
  const result = spawnSync("ffmpeg", [
    "-hide_banner", "-loglevel", "error",
    "-ss", time.toFixed(3), "-i", video,
    "-vf", "crop=4:4:158:118", "-frames:v", "1",
    "-f", "rawvideo", "-pix_fmt", "rgb24", "-",
  ], { encoding: "buffer", timeout: 60_000, maxBuffer: 1 << 20 });
  assert.equal(result.status, 0, `ffmpeg decode failed at ${time}s: ${result.stderr.toString("utf8")}`);
  const bytes = result.stdout;
  assert.equal(bytes.byteLength, 4 * 4 * 3, "centre crop is 48 rgb24 bytes");
  const sum: [number, number, number] = [0, 0, 0];
  for (let index = 0; index < bytes.byteLength; index += 3) {
    sum[0] += bytes[index]!;
    sum[1] += bytes[index + 1]!;
    sum[2] += bytes[index + 2]!;
  }
  const count = bytes.byteLength / 3;
  return [Math.round(sum[0]! / count), Math.round(sum[1]! / count), Math.round(sum[2]! / count)];
}

test("TC-F3-07-01 real broker wire: nested probe, six-midpoint frames with decodable bytes, speech normalization",
  { timeout: 300_000 }, async () => {
    const scratch = mkdtempSync(join(tmpdir(), "hypit-fix3-ref-"));
    try {
      // F-REF（W64）：单 FFmpeg 串行生成 12s 有声/无声样本＋逐项核验（sha/probe/解码/段色）。
      const fixturesDir = join(scratch, "fixtures");
      const generated = spawnSync(process.execPath,
        [join(repoRoot, "scripts/acceptance/hypit-fix3-fixtures.mjs"), "--out", fixturesDir],
        { encoding: "utf8", timeout: 180_000, maxBuffer: 1 << 20 });
      assert.equal(generated.status, 0, `fixture generation failed: ${generated.stderr}`);

      const resourcesRoot = join(scratch, "resources");
      mkdirSync(resourcesRoot, { recursive: true });
      const store = new CommandStore(join(scratch, "bridge.sqlite"));
      const registry = openHandleRegistry([scratch], join(resourcesRoot, "handles.index.json"), store);
      const context: MediaToolContext = {
        registry,
        distributionRoot: join(scratch, "distribution"),
        programsRoot: join(scratch, "programs"),
        resourcesRoot,
        resolveSource: async (payload) =>
          (await resolveResource(registry, String(payload.handle))).absolutePath,
      };
      const audioPath = join(fixturesDir, "f-ref-audio.mp4");
      const silentPath = join(fixturesDir, "f-ref-silent.mp4");
      const register = (absolutePath: string) => registerResource(registry, {
        absolutePath,
        projectId: "fix3-ref",
        mediaType: "video/mp4",
        role: "reference",
      });

      // ── probe：嵌套 wire，无旧扁平顶层字段 ────────────────────────────
      const audioHandle = (await register(audioPath)).handle;
      const audioProbe = await runMediaTool("media.probe", { handle: audioHandle }, context);
      assert.ok(!("durationSeconds" in audioProbe) && !("hasAudio" in audioProbe),
        "probe wire must not carry legacy flat top-level fields");
      const nested = (audioProbe as { probe: Record<string, unknown> }).probe;
      assert.ok(nested && typeof nested === "object", "probe facts nest under result.probe");
      assert.equal(nested.hasVideo, true);
      assert.equal(nested.hasAudio, true);
      const duration = nested.duration as number;
      assert.ok(Math.abs(duration - 12) <= 0.05, `duration 12s ±0.05, got ${duration}`);
      assert.equal(nested.width, 320);
      assert.equal(nested.height, 240);

      const silentHandle = (await register(silentPath)).handle;
      const silentProbe = await runMediaTool("media.probe", { handle: silentHandle }, context) as
        { probe: Record<string, unknown> };
      assert.equal(silentProbe.probe.hasVideo, true);
      assert.equal(silentProbe.probe.hasAudio, false, "silent sample reports the audio branch honestly");

      // ── frames：六中点、回执时间=请求时间、PNG 字节可解码且段色正确 ────
      const midpoints = [1, 3, 5, 7, 9, 11];
      const frames = await runMediaTool("media.frames", { handle: audioHandle, times: midpoints }, context) as
        { frames: { handle: string; timestampSeconds: number }[]; totalTimes: number };
      assert.equal(frames.frames.length, 6, "six midpoint frames come back");
      assert.equal(frames.totalTimes, 6);
      assert.deepEqual(frames.frames.map((frame) => frame.timestampSeconds), midpoints,
        "receipt times equal the requested midpoints");
      assert.ok(!("aspectRatio" in frames), "frames wire has no aspectRatio — derived from probe");
      for (const frame of frames.frames) {
        assert.match(frame.handle, /^res-[0-9a-f]{16}-[0-9a-z]+$/u);
        const record = await resolveResource(registry, frame.handle);
        const png = readFileSync(record.absolutePath);
        assert.deepEqual([...png.subarray(0, 8)], PNG_MAGIC, "frame bytes are a PNG");
        assert.ok(png.byteLength > 200, `frame carries real image bytes (${png.byteLength})`);
        assert.equal(png.readUInt32BE(16), 320, "IHDR width 320");
        assert.equal(png.readUInt32BE(20), 240, "IHDR height 240");
      }
      // 内容随受控输入变化：@1s 红 / @5s 绿 / @9s 蓝（三段 F-REF）。判定与 W64 的
      // 主导通道规则一致（ffmpeg 命名色 red=#f00 green=#008000 blue=#00f）。
      const dominant = (rgb: [number, number, number]) =>
        rgb[0]! > rgb[1]! + 80 && rgb[0]! > rgb[2]! + 80 ? "red"
        : rgb[1]! > rgb[0]! + 80 && rgb[1]! > rgb[2]! + 80 ? "green"
        : rgb[2]! > rgb[0]! + 80 && rgb[2]! > rgb[1]! + 80 ? "blue" : "unknown";
      assert.equal(dominant(centreRgb(audioPath, 1)), "red", "1s centre is the red segment");
      assert.equal(dominant(centreRgb(audioPath, 5)), "green", "5s centre is the green segment");
      assert.equal(dominant(centreRgb(audioPath, 9)), "blue", "9s centre is the blue segment");

      // ── speech：已定义末跳回执 → 16kHz 样本锚点归一化 ────────────────
      const receipt = {
        language: "zh",
        segments: [{
          start: 0, end: 1,
          words: [
            { text: "你好", start: 0, end: 1, score: 0.9 },
            { text: "草场", start: 4, end: 5 },
          ],
        }],
      };
      const standard = standardEvidence(receipt, 192_000);
      assert.equal(standard.language, "zh");
      assert.deepEqual(standard.passages[0]?.words, [
        { text: "你好", startSample: 0, endSampleExclusive: 16_000, score: 0.9 },
        { text: "草场", startSample: 64_000, endSampleExclusive: 80_000 },
      ], "word order kept; seconds → 16 kHz sample anchors (0..16000 / 64000..80000)");

      // 窗口越界的词：锚点整体缺席（undefined，不是 0/越界值）——缺口不伪造。
      const partial = standardEvidence({
        language: "zh",
        segments: [{ start: 0, end: 1, words: [{ text: "缺口词", start: 99, end: 100 }] }],
      }, 192_000);
      const gapWord = partial.passages[0]?.words[0]!;
      assert.equal(gapWord.text, "缺口词");
      assert.equal(gapWord.startSample, undefined);
      assert.equal(gapWord.endSampleExclusive, undefined);

      // 空语音：passages=[] 是已测无语音，不是失败。
      const empty = standardEvidence({ language: "zh", segments: [] }, 192_000);
      assert.deepEqual(empty.passages, []);
    } finally {
      rmSync(scratch, { recursive: true, force: true });
    }
  });

// ── C107F3-08（TC-F3-08-01 node 腿）：真实 broker 资源路由供帧 ──────────
// W14（Java）经 W15.open 内部认证逐帧 GET /internal/v1/resources/{handle}。本用例
// spawn 真实 src/main.mjs：media.frames 产出的每个帧句柄必须经该真实路由回执
// 字节稳定、可解码（zlib 全解压出完整扫描线流）且在 RULE-008 单帧 4MiB 预算内的
// PNG——即 Java 侧消费的 wire 与工具产物一致，而不是只存在注册表文件里。

/** PNG 帧形状校验：签名/IHDR/无隔行 + IDAT 全解压恰为完整扫描线流（真实可解码）。 */
function assertDecodablePngFrame(png: Buffer, width: number, height: number): void {
  assert.deepEqual([...png.subarray(0, 8)], PNG_MAGIC, "frame bytes are a PNG");
  assert.equal(png.readUInt32BE(12), 0x49484452, "first chunk is IHDR");
  assert.equal(png.readUInt32BE(16), width, `IHDR width ${width}`);
  assert.equal(png.readUInt32BE(20), height, `IHDR height ${height}`);
  const bitDepth = png[24]!;
  const colorType = png[25]!;
  const interlace = png[28]!;
  assert.equal(bitDepth, 8, "8-bit depth");
  assert.ok([0, 2, 3, 4, 6].includes(colorType), `declared color type ${colorType}`);
  assert.equal(interlace, 0, "not interlaced");
  const channelsPerType: Record<number, number> = { 0: 1, 2: 3, 3: 1, 4: 2, 6: 4 };
  const rowBytes = width * channelsPerType[colorType]! + 1; // +1 filter byte per scanline
  const idat: Buffer[] = [];
  let offset = 8;
  while (offset + 8 <= png.byteLength) {
    const length = png.readUInt32BE(offset);
    const type = png.subarray(offset + 4, offset + 8).toString("ascii");
    if (type === "IDAT") idat.push(png.subarray(offset + 8, offset + 8 + length));
    offset += 12 + length;
    if (type === "IEND") break;
  }
  assert.ok(idat.length > 0, "frame carries IDAT chunks");
  const scanlines = inflateSync(Buffer.concat(idat));
  assert.equal(scanlines.byteLength, rowBytes * height,
    `IDAT inflates to the full ${rowBytes * height}-byte scanline stream (real decode)`);
}

test("TC-F3-08-01 real broker resource route serves frame handles as byte-stable decodable PNGs",
  { timeout: 180_000 }, async (t) => {
    const dataRoot = mkdtempSync(join(tmpdir(), "hypit-fix3-route-"));
    const token = randomBytes(32).toString("hex");
    const port = 9259;
    const child = spawn(process.execPath, ["--import", "tsx", "src/main.mjs"], {
      cwd: backendRoot,
      env: {
        ...process.env,
        HYPIT_BACKEND_PORT: String(port),
        HYPIT_INTERNAL_TOKEN: token,
        HYPIT_DATA_ROOT: join(dataRoot, "host"),
        HYPIT_GENERATED_ROOT: generatedRoot,
      },
      stdio: ["ignore", "pipe", "pipe"],
    });
    t.after(() => {
      child.kill("SIGTERM");
      rmSync(dataRoot, { recursive: true, force: true });
    });

    let ready = false;
    for (let attempt = 0; attempt < 400 && !ready; attempt += 1) {
      ready = await fetch(`http://127.0.0.1:${port}/healthz`).then((r) => r.ok).catch(() => false);
      if (!ready) await delay(250);
    }
    assert.ok(ready, "broker did not become healthy");
    const base = { authorization: `Bearer ${token}` };

    // 12s 320×240 纯色样本（单次 ffmpeg 串行生成——与 F-REF 同尺寸，六中点可取帧）。
    const videoPath = join(dataRoot, "red-12s.mp4");
    const rendered = spawnSync("ffmpeg", [
      "-y", "-hide_banner", "-loglevel", "error",
      "-f", "lavfi", "-i", "color=c=0xDD2233:s=320x240:d=12:r=30",
      "-pix_fmt", "yuv420p", videoPath,
    ], { encoding: "utf8", timeout: 120_000, maxBuffer: 1 << 20 });
    assert.equal(rendered.status, 0, `ffmpeg sample render failed: ${rendered.stderr}`);

    // 受控上传面（真实 ingest 路由）→ 分析链同款素材句柄。
    const videoBytes = readFileSync(videoPath);
    const ingest = await fetch(`http://127.0.0.1:${port}/internal/v1/resources`, {
      method: "POST",
      headers: { ...base, "content-type": "video/mp4", "x-hypit-file-name": "red-12s.mp4" },
      body: new Uint8Array(videoBytes),
    });
    assert.equal(ingest.status, 200, "ingest via the real route succeeds");
    const source = await ingest.json() as { handle: string; sha256: string; sizeBytes: number };
    assert.equal(source.sha256, createHash("sha256").update(videoBytes).digest("hex"));

    // media.frames（真实命令路由）→ 六中点帧句柄。
    const midpoints = [1, 3, 5, 7, 9, 11];
    const framesCall = await fetch(`http://127.0.0.1:${port}/internal/v1/commands`, {
      method: "POST",
      headers: { ...base, "content-type": "application/json" },
      body: JSON.stringify({ kind: "media.frames", payload: { handle: source.handle, times: midpoints } }),
    });
    assert.equal(framesCall.status, 200);
    const framesOutcome = await framesCall.json() as {
      state: string;
      result?: { frames?: { handle: string; timestampSeconds: number }[] };
      error?: string;
    };
    assert.equal(framesOutcome.state, "succeeded", `media.frames via real broker: ${framesOutcome.error ?? ""}`);
    const frames = framesOutcome.result?.frames ?? [];
    assert.equal(frames.length, 6, "six midpoint frame handles come back");
    assert.deepEqual(frames.map((frame) => frame.timestampSeconds), midpoints,
      "receipt times equal the requested midpoints");

    // 每个帧句柄经真实 GET 资源路由：字节稳定（两次读取一致）+ 可解码 + 预算内。
    for (const frame of frames) {
      const first = await fetch(`http://127.0.0.1:${port}/internal/v1/resources/${frame.handle}`, { headers: base });
      assert.equal(first.status, 200, `frame ${frame.handle} serves over the real route`);
      assert.match(first.headers.get("content-type") ?? "", /^image\/png/u, "frame route answers image/png");
      const bytes = Buffer.from(await first.arrayBuffer());
      const sha256 = createHash("sha256").update(bytes).digest("hex");
      assert.equal(first.headers.get("etag"), `"${sha256}"`, "strong sha256 ETag matches the served bytes");
      assert.ok(bytes.byteLength > 200 && bytes.byteLength <= 4 * 1024 * 1024,
        `frame bytes within the RULE-008 single-frame budget (got ${bytes.byteLength})`);
      assertDecodablePngFrame(bytes, 320, 240);

      const second = await fetch(`http://127.0.0.1:${port}/internal/v1/resources/${frame.handle}`, { headers: base });
      const again = Buffer.from(await second.arrayBuffer());
      assert.deepEqual(again, bytes, "repeated reads serve byte-identical PNGs (stable wire for W14)");
    }
  });
