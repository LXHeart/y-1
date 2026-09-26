#!/usr/bin/env node
// generate-media.mjs — C107-05 (task-107) K13.1 repeatable media fixtures.
//
// Generates the small video fixtures from parameters (nothing binary checked
// in) and verifies each artifact with ffprobe before writing the verification
// manifest — an 8-byte ftyp placeholder can never pass here. Usage:
//   node platform-hypit/fixtures/generate-media.mjs <output-dir>
import { execFile } from "node:child_process";
import { createHash } from "node:crypto";
import { createReadStream } from "node:fs";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { join, resolve } from "node:path";

const outputRoot = resolve(process.argv[2] ?? "test-artifacts/task-107/C05/fixtures");

function run(file, args, timeoutMs = 180_000) {
  return new Promise((resolvePromise, rejectPromise) => {
    execFile(file, args, { encoding: "utf8", timeout: timeoutMs, maxBuffer: 16 * 1024 * 1024 },
      (error, stdout, stderr) => {
        if (error !== null) rejectPromise(Object.assign(error, { stderr: String(stderr) }));
        else resolvePromise(stdout);
      });
  });
}

async function sha256(path) {
  const hash = createHash("sha256");
  await new Promise((resolvePromise, rejectPromise) => {
    const stream = createReadStream(path);
    stream.on("data", (chunk) => hash.update(chunk));
    stream.on("error", rejectPromise);
    stream.on("end", () => resolvePromise());
  });
  return hash.digest("hex");
}

async function probe(path) {
  return JSON.parse(await run("ffprobe", ["-v", "error", "-print_format", "json", "-show_format", "-show_streams", path]));
}

function assert(condition, message) {
  if (!condition) throw new Error(`fixture verification failed: ${message}`);
}

async function generate() {
  await mkdir(outputRoot, { recursive: true });
  const manifest = { format: "y1.hypit.fixtures@1", generatedAt: new Date().toISOString(), fixtures: {} };

  // silent-cuts: 6s, first 3s red, last 3s blue, 30fps, no audio (K13.1).
  const silentCuts = join(outputRoot, "silent-cuts.mp4");
  await run("ffmpeg", ["-hide_banner", "-loglevel", "error", "-y",
    "-f", "lavfi", "-i", "color=c=red:s=640x360:r=30:d=3",
    "-f", "lavfi", "-i", "color=c=blue:s=640x360:r=30:d=3",
    "-filter_complex", "[0:v][1:v]concat=n=2:v=1:a=0[v]",
    "-map", "[v]", "-c:v", "libx264", "-pix_fmt", "yuv420p", silentCuts]);
  const silentProbe = await probe(silentCuts);
  assert(Number(silentProbe.format.duration) >= 5.8 && Number(silentProbe.format.duration) <= 6.2, "silent-cuts duration ~6s");
  assert(silentProbe.streams.some((s) => s.codec_type === "video"), "silent-cuts has video");
  assert(!silentProbe.streams.some((s) => s.codec_type === "audio"), "silent-cuts has no audio");
  const fps = silentProbe.streams.find((s) => s.codec_type === "video").avg_frame_rate;
  assert(Math.abs(Number(fps.split("/")[0]) / Number(fps.split("/")[1] || 1) - 30) < 1, "silent-cuts ~30fps");
  manifest.fixtures["silent-cuts"] = {
    file: "silent-cuts.mp4", sha256: await sha256(silentCuts),
    spec: "6s red(0-3s)+blue(3-6s), 30fps, no audio", purpose: "probe/boundaries/cut, no-speech path",
    duration: Number(silentProbe.format.duration),
  };

  // av-clock: 8s testsrc with running timecode + 1kHz beeps at 1/3/5s.
  const avClock = join(outputRoot, "av-clock.mp4");
  await run("ffmpeg", ["-hide_banner", "-loglevel", "error", "-y",
    "-f", "lavfi", "-i", "testsrc2=s=640x360:r=30:d=8",
    "-f", "lavfi", "-i", "aevalsrc='if(between(t\\,1\\,1.25)+between(t\\,3\\,3.25)+between(t\\,5\\,5.25)\\,0.7*sin(2*PI*1000*t)\\,0)':s=44100:d=8",
    "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", avClock]);
  const clockProbe = await probe(avClock);
  assert(Number(clockProbe.format.duration) >= 7.8, "av-clock duration ~8s");
  assert(clockProbe.streams.some((s) => s.codec_type === "audio"), "av-clock has audio");
  manifest.fixtures["av-clock"] = {
    file: "av-clock.mp4", sha256: await sha256(avClock),
    spec: "8s testsrc2 (running timecode readout) + 1kHz beep at 1/3/5s", purpose: "frame/audio alignment, seek",
    duration: Number(clockProbe.format.duration),
  };

  // alpha-overlay: 2s rgba moving block (qtrle keeps alpha).
  const alpha = join(outputRoot, "alpha-overlay.mov");
  await run("ffmpeg", ["-hide_banner", "-loglevel", "error", "-y",
    "-f", "lavfi", "-i", "color=c=black@0.0:s=320x180:r=30:d=2",
    "-vf", "format=rgba,drawbox=x=40+80*t:y=60:w=64:h=64:color=red@0.7:t=fill",
    "-c:v", "qtrle", alpha]);
  const alphaProbe = await probe(alpha);
  assert(alphaProbe.streams.some((s) => s.codec_type === "video"), "alpha-overlay has video");
  assert(alphaProbe.streams.every((s) => s.codec_type !== "audio"), "alpha-overlay has no audio");
  manifest.fixtures["alpha-overlay"] = {
    file: "alpha-overlay.mov", sha256: await sha256(alpha),
    spec: "2s rgba moving translucent block", purpose: "alpha normalize/compose",
    duration: Number(alphaProbe.format.duration),
  };

  // multi-stream: main video + attached_pic cover + two audio tracks.
  const multi = join(outputRoot, "multi-stream.mp4");
  const cover = join(outputRoot, "cover.jpg");
  await run("ffmpeg", ["-hide_banner", "-loglevel", "error", "-y",
    "-f", "lavfi", "-i", "color=c=teal:s=640x360:r=30:d=2",
    "-f", "lavfi", "-i", "color=c=gold:s=320x320:d=1", "-frames:v", "1", cover]);
  await run("ffmpeg", ["-hide_banner", "-loglevel", "error", "-y",
    "-f", "lavfi", "-i", "color=c=teal:s=640x360:r=30:d=2",
    "-i", cover,
    "-f", "lavfi", "-i", "sine=frequency=440:duration=2",
    "-f", "lavfi", "-i", "sine=frequency=880:duration=2",
    "-map", "0:v", "-map", "1:v", "-map", "2:a", "-map", "3:a",
    "-c:v:0", "libx264", "-pix_fmt", "yuv420p", "-c:v:1", "mjpeg",
    "-c:a", "aac", "-disposition:v:1", "attached_pic", multi]);
  const multiProbe = await probe(multi);
  const attached = multiProbe.streams.filter((s) => (s.disposition?.attached_pic ?? 0) === 1);
  assert(attached.length === 1, "multi-stream has exactly one attached_pic");
  assert(multiProbe.streams.filter((s) => s.codec_type === "audio").length === 2, "multi-stream has two audio tracks");
  manifest.fixtures["multi-stream"] = {
    file: "multi-stream.mp4", sha256: await sha256(multi),
    spec: "main video + attached_pic cover + 2 audio tracks", purpose: "stream selection keeps cover",
    duration: Number(multiProbe.format.duration),
  };

  // transcript fixture: a phrase that appears twice, deterministic word times.
  const transcript = {
    format: "hypit.transcript@1",
    passages: [{
      words: [
        { text: "打开", start_seconds: 0.2, end_seconds: 0.5 },
        { text: "榜单", start_seconds: 0.5, end_seconds: 0.9 },
        { text: "看", start_seconds: 0.9, end_seconds: 1.1 },
        { text: "第一条", start_seconds: 1.1, end_seconds: 1.6 },
        { text: "然后", start_seconds: 1.6, end_seconds: 1.8 },
        { text: "回到", start_seconds: 3.2, end_seconds: 3.5 },
        { text: "榜单", start_seconds: 3.5, end_seconds: 3.9 },
        { text: "再看", start_seconds: 3.9, end_seconds: 4.2 },
        { text: "第二条", start_seconds: 4.2, end_seconds: 4.7 },
      ],
    }],
  };
  const transcriptPath = join(outputRoot, "phrase-twice.transcript.json");
  await writeFile(transcriptPath, `${JSON.stringify(transcript, undefined, 2)}\n`, "utf8");
  manifest.fixtures["phrase-twice-transcript"] = {
    file: "phrase-twice.transcript.json", sha256: await sha256(transcriptPath),
    spec: "“榜单” at 0.5-0.9s and 3.5-3.9s", purpose: "around/occurrence selection",
  };

  await writeFile(join(outputRoot, "verification-manifest.json"), `${JSON.stringify(manifest, undefined, 2)}\n`, "utf8");
  process.stdout.write(`fixtures generated and ffprobe-verified in ${outputRoot}\n`);
}

generate().catch((error) => {
  process.stderr.write(`${error.stack ?? error}\n`);
  process.exit(1);
});
