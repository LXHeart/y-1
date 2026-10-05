#!/usr/bin/env node
// hypit-fix3-fixtures.mjs — F-REF 确定性媒体 fixture 生成与核验（W064；C107F3-13）。
//
// 任务书 §9.3 F-REF / TC-F3-13-03：合成 12 秒、320×240、0/4/8 秒三段红/绿/蓝视频，
// 无声版与带单音轨版；记录源 sha、六中点帧时间/sha（t=duration*(2i+1)/12 → 1,3,5,7,9,11s）。
// 单个 FFmpeg 进程串行生成两个样本（一次调用、两个输出），随后 ffprobe + 完整解码 +
// 中点帧提取核验；坏生成 exit 非零。
//
// 用法：
//   node scripts/acceptance/hypit-fix3-fixtures.mjs --out <dir>            生成＋核验＋写 manifest.json
//   node scripts/acceptance/hypit-fix3-fixtures.mjs --verify <dir>         对既有产物全量复核（sha/probe/解码/颜色）
//
// 可覆盖环境（测试注入用）：HYPIT_FIX3_FFMPEG_BIN / HYPIT_FIX3_FFPROBE_BIN。
// 无外部网络、无 Docker；FFmpeg 本地二进制串行执行。
import { spawnSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'

const FFMPEG_BIN = process.env.HYPIT_FIX3_FFMPEG_BIN ?? 'ffmpeg'
const FFPROBE_BIN = process.env.HYPIT_FIX3_FFPROBE_BIN ?? 'ffprobe'

const PARAMS = Object.freeze({
  seed: 'f-ref-v1',
  width: 320,
  height: 240,
  fps: 25,
  durationSeconds: 12,
  segments: Object.freeze([
    { color: 'red', from: 0, to: 4 },
    { color: 'green', from: 4, to: 8 },
    { color: 'blue', from: 8, to: 12 },
  ]),
  // RULE-006 六中点采样：t = duration*(2i+1)/12，i=0..5。
  midpoints: Object.freeze([1, 3, 5, 7, 9, 11]),
  audio: Object.freeze({ type: 'sine', frequency: 440, sampleRate: 44100 }),
  // 中点帧哈希取画面中心 4×4 像素（均匀场中心，编码噪声可忽略），rgb24 原始字节。
  frameCrop: Object.freeze({ w: 4, h: 4, x: 158, y: 118 }),
  durationTolerance: 0.1,
})

function fail(message) {
  console.error(`FAIL ${message}`)
  process.exit(1)
}

function run(bin, args, { captureStdout = false } = {}) {
  const r = spawnSync(bin, args, {
    encoding: 'buffer',
    timeout: 180_000,
    maxBuffer: 64 * 1024 * 1024,
  })
  return {
    status: r.status ?? -1,
    stdout: captureStdout ? r.stdout : r.stdout?.toString('utf8') ?? '',
    stderr: r.stderr?.toString('utf8') ?? '',
    error: r.error?.message ?? null,
  }
}

function sha256Buf(buf) {
  return createHash('sha256').update(buf).digest('hex')
}

function parseArgs(argv) {
  const out = {}
  for (let i = 0; i < argv.length; i += 1) {
    if (argv[i] === '--out') out.out = argv[++i]
    else if (argv[i] === '--verify') out.verify = argv[++i]
    else if (argv[i] === '--json-out') out.jsonOut = argv[++i]
    else fail(`未知参数 ${argv[i]}`)
  }
  return out
}

function ffmpegVersion() {
  const r = run(FFMPEG_BIN, ['-version'])
  if (r.status !== 0) fail(`ffmpeg 不可用（exit=${r.status} error=${r.error ?? ''}）`)
  return r.stdout.split('\n')[0].trim()
}

// 单个 FFmpeg 进程生成两个样本：lavfi 三段色源 concat + 正弦音源；一次调用多输出。
function generate(outDir) {
  const silent = resolve(outDir, 'f-ref-silent.mp4')
  const audio = resolve(outDir, 'f-ref-audio.mp4')
  const { width, height, fps } = PARAMS
  const inputArgs = [
    '-f', 'lavfi', '-i', `color=c=red:size=${width}x${height}:rate=${fps}:duration=4`,
    '-f', 'lavfi', '-i', `color=c=green:size=${width}x${height}:rate=${fps}:duration=4`,
    '-f', 'lavfi', '-i', `color=c=blue:size=${width}x${height}:rate=${fps}:duration=4`,
    '-f', 'lavfi', '-i', `sine=frequency=${PARAMS.audio.frequency}:sample_rate=${PARAMS.audio.sampleRate}:duration=${PARAMS.durationSeconds}`,
  ]
  const vEnc = ['-c:v', 'libx264', '-preset', 'veryfast', '-crf', '28', '-g', String(fps),
    '-pix_fmt', 'yuv420p', '-video_track_timescale', '90000']
  const args = [
    '-y', '-nostdin',
    ...inputArgs,
    '-filter_complex', '[0:v][1:v][2:v]concat=n=3:v=1:a=0,format=yuv420p,split=2[v1][v2]',
    // 输出1：无声样本（仅视频轨）。
    '-map', '[v1]', ...vEnc, '-movflags', '+faststart', silent,
    // 输出2：带单音轨样本（同一视频轨 split 副本 + aac 音轨），同进程内顺序写出。
    '-map', '[v2]', '-map', '3:a', ...vEnc, '-c:a', 'aac', '-b:a', '64k',
    '-ar', String(PARAMS.audio.sampleRate), '-movflags', '+faststart', audio,
  ]
  const r = run(FFMPEG_BIN, args)
  if (r.status !== 0 || !existsSync(silent) || !existsSync(audio)) {
    fail(`FFmpeg 生成失败（exit=${r.status}）\n${r.stderr.slice(-2000)}`)
  }
  return 1
}

function probeSample(file) {
  const r = run(FFPROBE_BIN, [
    '-v', 'error', '-show_entries', 'format=duration',
    '-show_entries', 'stream=codec_type,codec_name,width,height',
    '-of', 'json', file,
  ])
  if (r.status !== 0) fail(`ffprobe 失败（${file} exit=${r.status}）`)
  let data
  try { data = JSON.parse(r.stdout) } catch (e) { fail(`ffprobe JSON 不可解析：${e.message}`) }
  const duration = Number(data.format?.duration)
  const streams = data.streams ?? []
  const video = streams.find((s) => s.codec_type === 'video')
  const audioStreams = streams.filter((s) => s.codec_type === 'audio')
  if (!Number.isFinite(duration) || duration <= 0) fail(`probe 缺有效时长（${file}）：${data.format?.duration}`)
  if (!video) fail(`probe 缺视频轨（${file}）`)
  return {
    durationSeconds: Math.round(duration * 1000) / 1000,
    width: video.width, height: video.height,
    hasAudio: audioStreams.length > 0,
    audioCodec: audioStreams[0]?.codec_name ?? null,
  }
}

function fullDecode(file) {
  const r = run(FFMPEG_BIN, ['-v', 'error', '-i', file, '-f', 'null', '-'])
  if (r.status !== 0) fail(`完整解码失败（${file} exit=${r.status}）\n${r.stderr.slice(-1000)}`)
  if (r.stderr.trim().length > 0) fail(`解码产生错误输出（${file}）：${r.stderr.slice(-500)}`)
  return true
}

// 提取中点帧：中心 4×4 crop 的 rgb24 原始字节 → sha256 + 平均 RGB。
function extractFrame(file, t) {
  const { w, h, x, y } = PARAMS.frameCrop
  const r = run(FFMPEG_BIN, [
    '-y', '-nostdin', '-ss', String(t), '-i', file,
    '-frames:v', '1', '-vf', `crop=${w}:${h}:${x}:${y}`,
    '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-',
  ], { captureStdout: true })
  if (r.status !== 0 || r.stdout.length !== w * h * 3) {
    fail(`中点帧提取失败（${file}@${t}s exit=${r.status} bytes=${r.stdout.length}）\n${r.stderr.slice(-500)}`)
  }
  const bytes = r.stdout
  let rr = 0, gg = 0, bb = 0
  const n = w * h
  for (let i = 0; i < n; i += 1) {
    rr += bytes[i * 3]; gg += bytes[i * 3 + 1]; bb += bytes[i * 3 + 2]
  }
  return { t, sha256: sha256Buf(bytes), rgb: [Math.round(rr / n), Math.round(gg / n), Math.round(bb / n)] }
}

function expectedSegmentColor(t) {
  const seg = PARAMS.segments.find((s) => t >= s.from && t < s.to)
  if (!seg) fail(`中点 ${t}s 不在任何色段内`)
  return seg.color
}

// 红绿蓝段正确性：中点帧主色通道必须显著占优（均匀色场，编码后偏差远小于阈值）。
function assertSegmentColor(frame) {
  const [r, g, b] = frame.rgb
  const want = expectedSegmentColor(frame.t)
  const dominant = r > g + 80 && r > b + 80 ? 'red'
    : g > r + 80 && g > b + 80 ? 'green'
      : b > r + 80 && b > g + 80 ? 'blue' : 'unknown'
  if (dominant !== want) {
    fail(`中点 ${frame.t}s 颜色不符：期望 ${want}，实际 rgb=${JSON.stringify(frame.rgb)}`)
  }
}

function sampleFacts(file, id) {
  const probe = probeSample(file)
  if (Math.abs(probe.durationSeconds - PARAMS.durationSeconds) > PARAMS.durationTolerance) {
    fail(`${id} 时长 ${probe.durationSeconds}s 偏离 ${PARAMS.durationSeconds}s 超容差`)
  }
  if (probe.width !== PARAMS.width || probe.height !== PARAMS.height) {
    fail(`${id} 分辨率 ${probe.width}x${probe.height} ≠ ${PARAMS.width}x${PARAMS.height}`)
  }
  const decodeClean = fullDecode(file)
  const frames = PARAMS.midpoints.map((t) => {
    const frame = extractFrame(file, t)
    assertSegmentColor(frame)
    return frame
  })
  const bytes = readFileSync(file)
  return {
    id, file: `${id}.mp4`, sha256: sha256Buf(bytes), sizeBytes: bytes.length,
    durationSeconds: probe.durationSeconds, width: probe.width, height: probe.height,
    hasAudio: probe.hasAudio, audioCodec: probe.audioCodec, decodeClean,
    frames,
  }
}

function verifySample(entry, dir) {
  const abs = resolve(dir, entry.file)
  if (!existsSync(abs)) fail(`缺媒体=${entry.file}`)
  const bytes = readFileSync(abs)
  if (sha256Buf(bytes) !== entry.sha256) fail(`媒体hash不符=${entry.file}（manifest 与实际字节不一致）`)
  if (bytes.length !== entry.sizeBytes) fail(`媒体尺寸漂移=${entry.file}`)
  const probe = probeSample(abs)
  if (Math.abs(probe.durationSeconds - PARAMS.durationSeconds) > PARAMS.durationTolerance) {
    fail(`${entry.id} 复核时长偏离：${probe.durationSeconds}`)
  }
  if (probe.width !== PARAMS.width || probe.height !== PARAMS.height) fail(`${entry.id} 复核分辨率不符`)
  if (probe.hasAudio !== entry.hasAudio) fail(`${entry.id} 复核音轨不符`)
  fullDecode(abs)
  for (const frame of entry.frames ?? []) {
    const got = extractFrame(abs, frame.t)
    if (got.sha256 !== frame.sha256) fail(`帧hash不符=${entry.file}@${frame.t}s`)
    assertSegmentColor(got)
  }
  return true
}

function main() {
  const args = parseArgs(process.argv.slice(2))
  if (!args.out && !args.verify) fail('必须提供 --out <dir>（生成）或 --verify <dir>（复核）')
  if (args.out && args.verify) fail('--out 与 --verify 互斥')

  const dir = resolve(args.out ?? args.verify)

  if (args.verify) {
    const manifestPath = resolve(dir, 'manifest.json')
    if (!existsSync(manifestPath)) fail(`缺 manifest.json（${manifestPath}）`)
    let manifest
    try { manifest = JSON.parse(readFileSync(manifestPath, 'utf8')) } catch (e) { fail(`manifest 不可读：${e.message}`) }
    if (manifest.params?.seed !== PARAMS.seed) fail(`manifest seed 漂移：${manifest.params?.seed}`)
    const audio = (manifest.samples ?? []).find((s) => s.id === 'f-ref-audio')
    const silent = (manifest.samples ?? []).find((s) => s.id === 'f-ref-silent')
    if (!audio || !silent) fail('manifest 缺 audio/silent 样本')
    verifySample(audio, dir)
    verifySample(silent, dir)
    if (audio.sha256 === silent.sha256) fail('有声/无声样本 sha 相同（音轨未生效）')
    if (audio.hasAudio !== true || silent.hasAudio !== false) fail('音轨分支不符（audio 必须有轨、silent 必须无轨）')
    const frameTimes = JSON.stringify(audio.frames.map((f) => f.t))
    if (frameTimes !== JSON.stringify(PARAMS.midpoints)) fail(`中点帧时间不符：${frameTimes}`)
    console.log(`OK fixtures verify：samples=2 frames=${audio.frames.length} audioSha=${audio.sha256.slice(0, 12)} silentSha=${silent.sha256.slice(0, 12)}`)
    if (args.jsonOut) writeFileSync(args.jsonOut, JSON.stringify({ ok: true, manifest }, null, 2) + '\n')
    process.exit(0)
  }

  // 生成路径：空输出目录 → 单 FFmpeg 串行生成 → 逐项核验 → manifest。
  let ffmpegInvocations = 0
  mkdirSync(dir, { recursive: true })
  for (const name of ['f-ref-audio.mp4', 'f-ref-silent.mp4', 'manifest.json']) {
    rmSync(resolve(dir, name), { force: true })
  }
  ffmpegInvocations += generate(dir)
  const version = ffmpegVersion()
  const audio = sampleFacts(resolve(dir, 'f-ref-audio.mp4'), 'f-ref-audio')
  const silent = sampleFacts(resolve(dir, 'f-ref-silent.mp4'), 'f-ref-silent')
  if (audio.hasAudio !== true) fail('有声样本无音轨')
  if (silent.hasAudio !== false) fail('无声样本出现音轨')
  if (audio.sha256 === silent.sha256) fail('有声/无声样本 sha 相同')
  const manifest = {
    generator: 'scripts/acceptance/hypit-fix3-fixtures.mjs',
    seed: PARAMS.seed,
    generatedAt: new Date().toISOString(),
    ffmpegVersion: version,
    ffmpegInvocations,
    params: PARAMS,
    samples: [audio, silent],
  }
  writeFileSync(resolve(dir, 'manifest.json'), JSON.stringify(manifest, null, 2) + '\n')
  console.log(`OK fixtures generate：audio=${audio.sizeBytes}B silent=${silent.sizeBytes}B frames=${audio.frames.length} ffmpegInvocations=${ffmpegInvocations}`)
  if (args.jsonOut) writeFileSync(args.jsonOut, JSON.stringify({ ok: true, manifest }, null, 2) + '\n')
  process.exit(0)
}

main()
