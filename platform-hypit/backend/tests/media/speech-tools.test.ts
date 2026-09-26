// speech-tools.test.ts — C107-06 (task-107) speech tools (K08 transcribe/
// measure rows). measure and align run the native upstream libraries against
// deterministic inputs; transcribe runs the real conversion + wire protocol
// against a local stub that mirrors the WhisperX service contract exactly
// (loopback /health identity + /transcribe seconds-domain words), plus a
// REAL-service case gated on prepared models (EXTERNAL_BLOCKED otherwise).
import { strict as assert } from "node:assert";
import { createServer } from "node:http";
import { execFileSync } from "node:child_process";
import { mkdirSync, mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";

process.env.HYPIT_STATE_HOME ??= join(import.meta.dirname, "../../../../data/hypit/runner-state");
// The catalog reads the port at module import: point it at the stub's fixed
// test port before importing the production modules.
process.env.HYPIT_WHISPERX_PORT = "8791";

import { runSpeechTool, standardEvidence, evidenceDiagnostics, SpeechToolError } from "../../src/tools/speech.ts";
import { openHandleRegistry, resolveResource } from "../../src/resources/handles.ts";
import { CommandStore } from "../../src/commands/store.ts";
import { installEngineResolution } from "../../src/engine/hypit-bootstrap.ts";

const repoRoot = join(import.meta.dirname, "../../../..");
const distributionRoot = join(repoRoot, "platform-hypit/.generated/hypit");
const programsRoot = join(repoRoot, "test-artifacts/task-107/C06/programs");
const fixtures = join(repoRoot, "platform-hypit/fixtures/speech");
const catalog = JSON.parse(readFileSync(join(fixtures, "catalog.json"), "utf8")) as {
  readonly alignmentEvidence: {
    readonly en: { readonly language: string; readonly words: Array<{ text: string; start: number; end: number }> };
    readonly zh: { readonly language: string; readonly words: Array<{ text: string; start: number; end: number }> };
  };
};

const STUB_PORT = 8791;
const IDENTITY = {
  ok: true,
  protocol: "hypit.whisperx-service@1",
  serviceVersion: "0.1.0",
  model: "small",
  device: "cpu",
  compute: "int8",
  batchSize: 8,
};

type Stub = {
  readonly server: import("node:http").Server;
  transcribe: (request: { audio_path: string; language: string | null }) => unknown;
};

async function startStub(): Promise<Stub> {
  const stub: Stub = {
    server: undefined as unknown as Stub["server"],
    transcribe: () => ({ language: "en", segments: [] }),
  };
  const server = createServer((request, response) => {
    const send = (status: number, body: unknown) => {
      const bytes = JSON.stringify(body);
      response.writeHead(status, { "content-type": "application/json", "content-length": String(bytes.length) });
      response.end(bytes);
    };
    if (request.method === "GET" && request.url === "/health") {
      send(200, IDENTITY);
      return;
    }
    if (request.method === "POST" && request.url === "/transcribe") {
      const chunks: Buffer[] = [];
      request.on("data", (chunk: Buffer) => chunks.push(chunk));
      request.on("end", () => {
        const parsed = JSON.parse(Buffer.concat(chunks).toString("utf8")) as { audio_path: string; language?: string };
        send(200, stub.transcribe({ audio_path: parsed.audio_path, language: parsed.language ?? null }));
      });
      return;
    }
    send(404, { error: { code: "NOT_FOUND", message: "not found" } });
  });
  await new Promise<void>((resolvePromise) => server.listen(STUB_PORT, "127.0.0.1", () => resolvePromise()));
  return { server, get transcribe() { return stub.transcribe; }, set transcribe(value) { stub.transcribe = value; } };
}

function toolContext(scratch: string) {
  const resourcesRoot = join(scratch, "resources");
  mkdirSync(resourcesRoot, { recursive: true });
  mkdirSync(join(programsRoot, "whisperx.local", "input"), { recursive: true });
  const store = new CommandStore(join(scratch, "bridge.sqlite"));
  const registry = openHandleRegistry([resourcesRoot], join(resourcesRoot, "handles.index.json"), store);
  return {
    registry,
    programsRoot,
    resolveSource: async (payload: Record<string, unknown>) => String(payload.__sourcePath),
  };
}

test("speech.measure: native estimate stack, deterministic and marked estimated", { timeout: 60_000 }, async () => {
  await installEngineResolution(distributionRoot);
  const en = await runSpeechTool("speech.measure", { text: "Join us this season.", language: "en" }, undefined as never);
  assert.equal(en.estimated, true);
  assert.equal(en.language, "en");
  assert.ok(typeof en.units === "number" && en.units >= 4, `en syllable units: ${String(en.units)}`);
  assert.ok(typeof en.seconds === "number" && en.seconds > 0.5 && en.seconds < 10);

  const zh = await runSpeechTool("speech.measure", { text: "草场把牧场带到每一块屏幕。", language: "zh", pace: "slow" }, undefined as never) as { estimated: boolean; language: string; units: number; seconds: number };
  assert.equal(zh.estimated, true);
  assert.equal(zh.language, "zh");
  // CJK units count characters: 12 Han characters => 12 units.
  assert.equal(zh.units, 12);
  assert.ok(zh.seconds > 0);
  // pace=slow is slower than the same text at fast: estimate scales with rate.
  const zhFast = await runSpeechTool("speech.measure", { text: "草场把牧场带到每一块屏幕。", language: "zh", pace: "fast" }, undefined as never) as { seconds: number };
  assert.ok(zhFast.seconds < zh.seconds);

  await assert.rejects(
    () => runSpeechTool("speech.measure", { text: "hello", pace: "slow", rate: 5 }, undefined as never),
    (error: unknown) => error instanceof SpeechToolError && error.code === "invalid_input",
    "pace and rate are mutually exclusive",
  );
  await assert.rejects(
    () => runSpeechTool("speech.measure", { text: "   " }, undefined as never),
    (error: unknown) => error instanceof SpeechToolError,
    "blank text is rejected",
  );
});

test("standardEvidence: seconds→samples conversion clamps and never manufactures windows", () => {
  const evidence = standardEvidence({
    language: "en",
    segments: [
      {
        start: 0.1, end: 1.0,
        words: [
          { word: "Grassland", start: 0.1, end: 0.86, score: 0.9 },
          { word: "brings", start: 0.9, end: 1.2 },           // beyond 1s evidence: clamped out
          { word: "", start: 0.0, end: 0.1 },                  // empty text dropped
          { word: "the", start: -0.5, end: 0.05 },             // negative start dropped
        ],
      },
      { start: 2, end: 3, words: [] },                         // empty passage dropped
    ],
  }, 16_000);
  assert.equal(evidence.language, "en");
  assert.equal(evidence.passages.length, 1);
  // Windowless words keep their text but never get manufactured times
  // (upstream: missing/partial acoustic evidence stays missing).
  assert.equal(evidence.passages[0]!.words.length, 3);
  const [kept, noWindow, negative] = evidence.passages[0]!.words;
  assert.equal(kept!.text, "Grassland");
  assert.equal(kept!.startSample, 1_600);
  assert.equal(kept!.endSampleExclusive, 13_760);
  assert.equal(noWindow!.text, "brings");
  assert.equal(noWindow!.startSample, undefined, "out-of-range window is dropped, not clamped");
  assert.equal(negative!.text, "the");
  assert.equal(negative!.startSample, undefined, "negative start loses its window");
  const diagnostics = evidenceDiagnostics(evidence.passages, 16_000);
  assert.ok(diagnostics.some((line) => line.includes("no acoustic window")), "windowless words are diagnosed");
  const outOfOrder = evidenceDiagnostics([
    { startSample: 0, endSampleExclusive: 10_000, words: [
      { text: "a", startSample: 2_000, endSampleExclusive: 3_000 },
      { text: "b", startSample: 2_500, endSampleExclusive: 4_000 },
    ] },
  ], 16_000);
  assert.ok(outOfOrder.some((line) => line.includes("not monotonic")));
});

test("speech.transcribe: canonical conversion + wire protocol against a service stub", { timeout: 90_000 }, async () => {
  const stub = await startStub();
  const scratch = mkdtempSync(join(tmpdir(), "hypit-speech-"));
  try {
    // 44.1 kHz stereo source: the tool must extract canonical 16 kHz mono once.
    const source = join(scratch, "speech-44k.wav");
    execFileSync("ffmpeg", ["-hide_banner", "-loglevel", "error", "-y",
      "-f", "lavfi", "-i", "sine=frequency=440:duration=2",
      "-ar", "44100", "-ac", "2", "-c:a", "pcm_s16le", source]);
    let observedPath = "";
    stub.transcribe = (request) => {
      observedPath = request.audio_path;
      return {
        language: "en",
        segments: [{
          start: 0.0, end: 1.5,
          words: [
            { word: "tone", start: 0.25, end: 0.75, score: 0.8 },
            { word: "only", start: 0.8, end: 1.4 },
          ],
        }],
      };
    };
    const result = await runSpeechTool("speech.transcribe", {
      __sourcePath: source, language: "en",
    } as unknown as Record<string, unknown>, toolContext(scratch));
    assert.equal(result.extracted, true);
    assert.equal(result.sampleFrames, 32_000);
    assert.equal(result.durationSec, 2);
    assert.equal(result.language, "en");
    assert.equal((result.passages as unknown[]).length, 1);
    // The staged path must be inside the program's input roots (its allowlist).
    assert.ok(observedPath.startsWith(join(programsRoot, "whisperx.local", "input")),
      `staged path must be inside input roots: ${observedPath}`);
    // Evidence artifact is registered and resolvable.
    assert.match(String(result.evidenceHandle), /^res-[0-9a-f]{16}-[0-9a-z]+$/u);
    const artifact = await resolveResource(toolContext(scratch).registry, String(result.evidenceHandle));
    const stored = JSON.parse(readFileSync(artifact.absolutePath, "utf8")) as { sampleFrames: number; passages: unknown[] };
    assert.equal(stored.sampleFrames, 32_000);
    assert.equal(stored.passages.length, 1);
  } finally {
    await new Promise<void>((resolvePromise) => stub.server.close(() => resolvePromise()));
    rmSync(scratch, { recursive: true, force: true });
  }
});

test("speech.transcribe: silence returns an explicit empty result, never fake cues", { timeout: 90_000 }, async () => {
  const stub = await startStub();
  const scratch = mkdtempSync(join(tmpdir(), "hypit-speech-"));
  try {
    const silence = join(scratch, "silence.wav");
    execFileSync("ffmpeg", ["-hide_banner", "-loglevel", "error", "-y",
      "-f", "lavfi", "-i", "anullsrc=r=16000:cl=mono", "-t", "1", "-c:a", "pcm_s16le", silence]);
    stub.transcribe = () => ({ language: "en", segments: [] });
    const result = await runSpeechTool("speech.transcribe", {
      __sourcePath: silence, language: "en",
    } as unknown as Record<string, unknown>, toolContext(scratch));
    assert.equal(result.extracted, false, "already-canonical silence passes through untouched");
    assert.deepEqual(result.passages, []);
    assert.equal(result.sampleFrames, 16_000);
  } finally {
    await new Promise<void>((resolvePromise) => stub.server.close(() => resolvePromise()));
    rmSync(scratch, { recursive: true, force: true });
  }
});

test("speech.transcribe: a warm service with the wrong identity is refused before work", { timeout: 60_000 }, async () => {
  // Rebind the stub's /health to a mismatched model; the tool must refuse with
  // program_not_ready naming the identity difference (never submit work).
  const stub = await startStub();
  const scratch = mkdtempSync(join(tmpdir(), "hypit-speech-"));
  try {
    await new Promise<void>((resolvePromise) => stub.server.close(() => resolvePromise()));
    const mismatch = createServer((request, response) => {
      const bytes = JSON.stringify({ ...IDENTITY, model: "large-v3" });
      response.writeHead(200, { "content-type": "application/json", "content-length": String(bytes.length) });
      response.end(bytes);
    });
    await new Promise<void>((resolvePromise) => mismatch.listen(STUB_PORT, "127.0.0.1", () => resolvePromise()));
    try {
      const silence = join(scratch, "silence.wav");
      execFileSync("ffmpeg", ["-hide_banner", "-loglevel", "error", "-y",
        "-f", "lavfi", "-i", "anullsrc=r=16000:cl=mono", "-t", "1", "-c:a", "pcm_s16le", silence]);
      await assert.rejects(
        () => runSpeechTool("speech.transcribe", { __sourcePath: silence } as unknown as Record<string, unknown>, toolContext(scratch)),
        (error: unknown) => error instanceof SpeechToolError && error.code === "program_not_ready"
          && (error.message.includes("身份不匹配") || error.message.includes("identity")),
        "identity mismatch must refuse before any transcription",
      );
    } finally {
      await new Promise<void>((resolvePromise) => mismatch.close(() => resolvePromise()));
    }
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
});

test("speech.align: native alignment maps evidence words to script tokens", { timeout: 60_000 }, async () => {
  await installEngineResolution(distributionRoot);
  const enEvidence = {
    passages: [{
      startSample: Math.round(0.30 * 16_000),
      endSampleExclusive: Math.round(4.42 * 16_000),
      words: catalog.alignmentEvidence.en.words.map((word) => ({
        text: word.text,
        startSample: Math.round(word.start * 16_000),
        endSampleExclusive: Math.round(word.end * 16_000),
      })),
    }],
  };
  const result = await runSpeechTool("speech.align", {
    evidence: enEvidence,
    script: [
      { id: "s1", text: "Grassland brings the meadow to every screen." },
      { id: "s2", text: "Join us this season." },
    ],
  }, undefined as never) as { segments: Array<{ id: string; startSample?: number; tokens: Array<{ text: string; relation: string }> }>; relations: Record<string, number> };
  assert.equal(result.segments.length, 2);
  const first = result.segments[0]!;
  const second = result.segments[1]!;
  assert.ok(first.startSample !== undefined && second.startSample !== undefined);
  assert.ok(second.startSample! > first.startSample!, "sequential script segments get later evidence windows");
  assert.ok(first.tokens.length >= 7, `segment tokens: ${String(first.tokens.length)}`);
  assert.ok(first.tokens.every((token) => token.relation !== undefined));
  assert.ok(Object.keys(result.relations).length > 0);

  // Word-order change is explainable: with the two segments swapped, the
  // short "Join us" segment still resolves a window (now the first script
  // segment), and the long segment's window starts later in the evidence.
  const swapped = await runSpeechTool("speech.align", {
    evidence: enEvidence,
    script: [
      { id: "now-first", text: "Join us this season." },
      { id: "now-second", text: "Grassland brings the meadow to every screen." },
    ],
  }, undefined as never) as { segments: Array<{ id: string; startSample?: number }> };
  assert.ok(swapped.segments[0]!.startSample !== undefined && swapped.segments[1]!.startSample !== undefined);
  assert.ok(swapped.segments[1]!.startSample! < swapped.segments[0]!.startSample! + 160_000,
    "the long segment still lands in the evidence window region");

  // Chinese: per-character tokens.
  const zhEvidence = {
    passages: [{
      startSample: Math.round(0.32 * 16_000),
      endSampleExclusive: Math.round(4.46 * 16_000),
      words: catalog.alignmentEvidence.zh.words.map((word) => ({
        text: word.text,
        startSample: Math.round(word.start * 16_000),
        endSampleExclusive: Math.round(word.end * 16_000),
      })),
    }],
  };
  const zh = await runSpeechTool("speech.align", {
    evidence: zhEvidence,
    script: [{ id: "zh1", text: "草场把牧场带到每一块屏幕" }],
  }, undefined as never) as { segments: Array<{ tokens: Array<{ text: string }> }> };
  const zhTokens = zh.segments[0]!.tokens.map((token) => token.text).join("");
  assert.ok(zhTokens.includes("草场") && zhTokens.includes("屏幕"), "zh characters survive tokenization");
});

test("speech.transcribe: real WhisperX over committed speech fixtures (live models)", { timeout: 120_000 }, async (t) => {
  const probe = await (await import("../../src/programs/health.ts")).whisperxHealth(
    (await import("../../src/programs/catalog.ts")).PROGRAM_CATALOG["whisperx.local"]);
  if (probe.state !== "ready") {
    t.skip(`whisperx.local not prepared/running (${probe.detail ?? "down"}): run deploy/hypit/prepare-programs.sh and programs up — EXTERNAL_BLOCKED (model download is operator work)`);
    return;
  }
  for (const name of ["speech-en.wav", "speech-zh.wav"] as const) {
    const language = name.includes("zh") ? "zh" : "en";
    const result = await runSpeechTool("speech.transcribe", {
      __sourcePath: join(fixtures, name), language,
    } as unknown as Record<string, unknown>, toolContext(mkdtempSync(join(tmpdir(), "hypit-speech-real-"))));
    const passages = result.passages as Array<{ words: Array<{ text: string; startSample?: number }> }>;
    const words = passages.flatMap((passage) => passage.words);
    assert.ok(words.length > 3, `${name}: real transcription returned words (${String(words.length)})`);
    const durationSec = (result.sampleFrames as number) / 16_000;
    for (const word of words) {
      assert.ok(word.startSample === undefined || (word.startSample >= 0 && word.startSample <= durationSec * 16_000),
        `${name}: word times stay inside the audio`);
    }
    if (language === "zh") {
      assert.ok(words.some((word) => /\p{Script=Han}/u.test(word.text)), "zh transcription keeps Han characters");
    }
  }
});
