#!/usr/bin/env node
// hypit-fix2-fixtures.mjs — 任务书 107-fix-2 C107F2-01（W004）。
//
// 用固定 seed（默认 10702，§9.3）生成可在干净克隆上重建的合成验收数据：
//   media/    合成 PNG（纯 Node 编码）、有声/无声 WAV、3 秒移动元素片与
//             12 秒参考片（ffmpeg 可用时真实编码，否则占位字节并在 manifest
//             标注 encoder=null；--require-encoder 时缺 ffmpeg 非零退出）
//   package/  字节确定的工程包 ZIP（stored、无压缩、固定时间戳）
//   accounts/ 合成 owner A/B 与 operator 元数据（UUID 由 seed 决定；
//             密码运行期注入，绝不写入 fixture/日志）
//   manifest.json  每个产物的 sha256/size/kind；两次运行同机同 seed 必须逐字节一致。
//
// 只生成合成数据：不访问网络、不调用 Provider（manifest.providerCalls 恒 0）。
// 后续卡（C08/C37/C38）在本生成器之上扩展真实媒体断言，不在本文件引入运行期依赖。
import { execFileSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { deflateSync } from 'node:zlib'

const TOOL_VERSION = '1.0.0'
const TASK_BOOK_VERSION = '107-fix-2 v1.0.0'
const FIXED_TIMESTAMP = '2026-09-27T00:00:00Z' // manifest 时间戳固定，保证可重现
const DEFAULT_SEED = 10702

// ── 命令行 ────────────────────────────────────────────────────────────────
function usage(out = console.error) {
  out(`用法: node scripts/acceptance/fixtures/hypit-fix2-fixtures.mjs --dest <目录> [选项]
  --seed <n>           固定随机 seed（默认 ${DEFAULT_SEED}，§9.3）
  --require-encoder    视频必须真实编码：缺 ffmpeg 时非零退出（C08/C37 媒体验收用）
  --skip-video         跳过视频产物（快速路径）
  --help               本说明
产物: <dest>/manifest.json、media/、package/、accounts/；同机同 seed 两次运行 hash 逐字节一致。`)
}

const argv = process.argv.slice(2)
let dest = ''
let seed = DEFAULT_SEED
let requireEncoder = false
let skipVideo = false
for (let i = 0; i < argv.length; i += 1) {
  const arg = argv[i]
  if (arg === '--help' || arg === '-h') { usage(console.log); process.exit(0) }
  else if (arg === '--dest') { dest = argv[++i] ?? '' }
  else if (arg === '--seed') { const v = argv[++i]; if (v === undefined || !/^\d+$/.test(v)) { usage(); process.exit(2) } seed = Number(v) }
  else if (arg === '--require-encoder') requireEncoder = true
  else if (arg === '--skip-video') skipVideo = true
  else { console.error(`未知参数: ${arg}`); usage(); process.exit(2) }
}
if (!dest) { usage(); process.exit(2) }
dest = resolve(dest)
mkdirSync(dest, { recursive: true })

// ── 确定性原语 ────────────────────────────────────────────────────────────
function mulberry32(a0) {
  let a = a0 >>> 0
  return () => {
    a |= 0; a = (a + 0x6d2b79f5) | 0
    let t = Math.imul(a ^ (a >>> 15), 1 | a)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}
const rand = mulberry32(seed)

// RFC4122 v5（sha1）固定派生 UUID：同 seed 同 name → 同 UUID，无随机性。
const NAMESPACE = Buffer.from('6ba7b8119dad11d180b400c04fd430c8'.replace(/(..)(?!$)/g, '$1:').split(':').map((h) => parseInt(h, 16)))
function uuidV5(name) {
  const hash = createHash('sha1').update(NAMESPACE).update(name).digest()
  const b = hash.subarray(0, 16)
  b[6] = (b[6] & 0x0f) | 0x50
  b[8] = (b[8] & 0x3f) | 0x80
  const hex = b.toString('hex')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

// ── 纯 Node 编码器（跨机器确定性） ───────────────────────────────────────
const CRC_TABLE = (() => {
  const t = new Uint32Array(256)
  for (let n = 0; n < 256; n += 1) {
    let c = n
    for (let k = 0; k < 8; k += 1) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
    t[n] = c >>> 0
  }
  return t
})()
function crc32(buf) {
  let c = 0xffffffff
  for (const byte of buf) c = CRC_TABLE[(c ^ byte) & 0xff] ^ (c >>> 8)
  return (c ^ 0xffffffff) >>> 0
}

function pngChunk(type, data) {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length)
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data])
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(body))
  return Buffer.concat([len, body, crc])
}
/** 8bit RGB PNG；pixelFn(x, y) → [r, g, b]。 */
function encodePng(width, height, pixelFn) {
  const raw = Buffer.alloc((width * 3 + 1) * height)
  let o = 0
  for (let y = 0; y < height; y += 1) {
    raw[o++] = 0 // filter: none
    for (let x = 0; x < width; x += 1) {
      const [r, g, b] = pixelFn(x, y)
      raw[o++] = r; raw[o++] = g; raw[o++] = b
    }
  }
  const ihdr = Buffer.alloc(13)
  ihdr.writeUInt32BE(width, 0); ihdr.writeUInt32BE(height, 4)
  ihdr[8] = 8; ihdr[9] = 2; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    pngChunk('IHDR', ihdr),
    pngChunk('IDAT', deflateSync(raw, { level: 6 })),
    pngChunk('IEND', Buffer.alloc(0)),
  ])
}

/** 16bit 单声道 PCM WAV；sampleFn(t 秒) → [-1, 1]。 */
function encodeWav(durationSeconds, sampleRate, sampleFn) {
  const total = Math.floor(durationSeconds * sampleRate)
  const data = Buffer.alloc(total * 2)
  for (let i = 0; i < total; i += 1) {
    const v = Math.max(-1, Math.min(1, sampleFn(i / sampleRate)))
    data.writeInt16LE(Math.round(v * 32767), i * 2)
  }
  const fmt = Buffer.alloc(16)
  fmt.writeUInt16LE(1, 0); fmt.writeUInt16LE(1, 2)
  fmt.writeUInt32LE(sampleRate, 4); fmt.writeUInt32LE(sampleRate * 2, 8)
  fmt.writeUInt16LE(2, 12); fmt.writeUInt16LE(16, 14)
  const header = Buffer.alloc(36)
  header.write('RIFF', 0, 'ascii'); header.writeUInt32LE(36 + data.length, 4)
  header.write('WAVEfmt ', 8, 'ascii'); header.writeUInt32LE(16, 16)
  return Buffer.concat([header, fmt, Buffer.from('data', 'ascii'), (() => { const b = Buffer.alloc(4); b.writeUInt32LE(data.length); return b })(), data])
}

/** 字节确定的 ZIP（stored 不压缩、DOS 时间固定 1980-01-01 00:00）。 */
function buildZip(entries) {
  const locals = []
  const centrals = []
  let offset = 0
  for (const [name, content] of entries) {
    const nameBuf = Buffer.from(name, 'utf8')
    const crc = crc32(content)
    const local = Buffer.alloc(30)
    local.writeUInt32LE(0x04034b50, 0); local.writeUInt16LE(20, 4); local.writeUInt16LE(0, 6)
    local.writeUInt16LE(0, 8); local.writeUInt16LE(0x0021, 10); local.writeUInt16LE(0, 12) // date=1980-01-01 time=00:00
    local.writeUInt32LE(crc, 14); local.writeUInt32LE(content.length, 18); local.writeUInt32LE(content.length, 22)
    local.writeUInt16LE(nameBuf.length, 26); local.writeUInt16LE(0, 28)
    locals.push(local, nameBuf, content)
    const central = Buffer.alloc(46)
    central.writeUInt32LE(0x02014b50, 0); central.writeUInt16LE(20, 4); central.writeUInt16LE(20, 6)
    central.writeUInt16LE(0, 8); central.writeUInt16LE(0, 10); central.writeUInt16LE(0x0021, 12); central.writeUInt16LE(0, 14)
    central.writeUInt32LE(crc, 16); central.writeUInt32LE(content.length, 20); central.writeUInt32LE(content.length, 24)
    central.writeUInt16LE(nameBuf.length, 28); central.writeUInt16LE(0, 30); central.writeUInt16LE(0, 32)
    central.writeUInt16LE(0, 34); central.writeUInt16LE(0, 36); central.writeUInt32LE(0, 38)
    central.writeUInt32LE(offset, 42)
    centrals.push(central, nameBuf)
    offset += 30 + nameBuf.length + content.length
  }
  const cd = Buffer.concat(centrals)
  const eocd = Buffer.alloc(22)
  eocd.writeUInt32LE(0x06054b50, 0); eocd.writeUInt16LE(entries.length, 8); eocd.writeUInt16LE(entries.length, 10)
  eocd.writeUInt32LE(cd.length, 12); eocd.writeUInt32LE(offset, 16)
  return Buffer.concat([...locals, cd, eocd])
}

// ── ffmpeg（可选真实编码） ───────────────────────────────────────────────
function ffmpegVersion() {
  try {
    return execFileSync('ffmpeg', ['-version'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).split('\n')[0].trim()
  } catch { return null }
}
function encodeClip(file, durationSeconds) {
  execFileSync('ffmpeg', [
    '-nostdin', '-loglevel', 'error', '-y',
    '-f', 'lavfi', '-i', `testsrc2=duration=${durationSeconds}:size=320x240:rate=12`,
    '-pix_fmt', 'yuv420p', '-threads', '1', '-flags', '+bitexact', '-fflags', '+bitexact',
    '-movflags', '+faststart', file,
  ], { stdio: ['ignore', 'ignore', 'pipe'] })
}

// ── 产物清单 ─────────────────────────────────────────────────────────────
const entries = []
function emit(relative, bytes, kind) {
  const file = join(dest, relative)
  mkdirSync(dirname(file), { recursive: true })
  writeFileSync(file, bytes)
  entries.push({
    path: relative,
    kind,
    sha256: createHash('sha256').update(bytes).digest('hex'),
    sizeBytes: bytes.length,
  })
}

// 媒体：PNG / WAV 纯 Node；MP4 尽量真实编码。
emit('media/frame.png', encodePng(64, 64, (x, y) => [Math.floor(rand() * 256), (x * 4) % 256, (y * 4) % 256]), 'png')
emit('media/tone-3s.wav', encodeWav(3, 8000, (t) => 0.4 * Math.sin(2 * Math.PI * 440 * t)), 'wav')
emit('media/silent-3s.wav', encodeWav(3, 8000, () => 0), 'wav')
emit('media/font-sample.bin', Buffer.from(Array.from({ length: 1024 }, () => Math.floor(rand() * 256))), 'binary')

const encoder = ffmpegVersion()
if (skipVideo) {
  // 显式跳过：不产占位文件，manifest 记录 omitted 原因。
  entries.push({ path: 'media/element-3s.mp4', kind: 'mp4', omitted: 'skip-video' })
  entries.push({ path: 'media/reference-12s.mp4', kind: 'mp4', omitted: 'skip-video' })
} else if (encoder) {
  encodeClip(join(dest, 'media/element-3s.mp4'), 3)
  encodeClip(join(dest, 'media/reference-12s.mp4'), 12)
  for (const name of ['media/element-3s.mp4', 'media/reference-12s.mp4']) {
    const bytes = readFileSync(join(dest, name))
    entries.push({ path: name, kind: 'mp4', sha256: createHash('sha256').update(bytes).digest('hex'), sizeBytes: bytes.length })
  }
} else if (requireEncoder) {
  console.error('缺少 ffmpeg 且 --require-encoder 已指定：真实媒体为必需环境，不能以占位字节通过（§13 缺必需环境如实非零）')
  process.exit(1)
} else {
  // 占位字节：仅用于 hash 可重现性验收，不可冒充可解码媒体（C08/C37 用 --require-encoder）。
  const mk = (label, n) => Buffer.from(Array.from({ length: n }, (_, i) => (i * 31 + seed) % 256))
  emit('media/element-3s.mp4', mk('element', 4096), 'placeholder-video')
  emit('media/reference-12s.mp4', mk('reference', 8192), 'placeholder-video')
}

// 工程包：二进制保真样本（C28 导入导出往返的确定性输入）。
const binaryAsset = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0xff, 0x80, 0x00])
const zipEntries = [
  ['hypit-project.json', Buffer.from(JSON.stringify({
    format: 'y1.hypit-project@1',
    sourceCommit: '0'.repeat(40),
    project: { title: `fix2-fixture-${seed}`, revision: 1, selectedRun: 'main.svrun' },
    files: [
      { path: 'main.svrun', sha256: createHash('sha256').update('fixture-run').digest('hex'), sizeBytes: 12, role: 'run' },
      { path: 'reference.png', sha256: createHash('sha256').update(binaryAsset).digest('hex'), sizeBytes: binaryAsset.length, role: 'asset' },
    ],
    packages: [], results: [], omitted: [],
  }, null, 2), 'utf8')],
  ['main.svrun', Buffer.from('fixture-run', 'utf8')],
  ['reference.png', binaryAsset],
]
emit('package/project-package.zip', buildZip(zipEntries), 'zip')

// 账号元数据：UUID 确定性派生；密码运行期注入（§9.3），fixture 不含任何口令。
const accounts = {
  seed,
  passwordPolicy: 'runtime-injected (§9.3)；fixture 永不落口令',
  accounts: [
    { role: 'owner-a', username: `fix2-owner-a-${seed}`, accountId: uuidV5(`107-fix-2:owner-a:${seed}`) },
    { role: 'owner-b', username: `fix2-owner-b-${seed}`, accountId: uuidV5(`107-fix-2:owner-b:${seed}`) },
    { role: 'operator', username: `fix2-operator-${seed}`, accountId: uuidV5(`107-fix-2:operator:${seed}`) },
  ],
}
emit('accounts/accounts.json', Buffer.from(JSON.stringify(accounts, null, 2), 'utf8'), 'json')

// manifest：hash 汇总 + 边界声明。
const manifest = {
  format: 'y1.hypit-fix2-fixtures@1',
  toolVersion: TOOL_VERSION,
  taskBook: TASK_BOOK_VERSION,
  seed,
  generatedAt: FIXED_TIMESTAMP,
  cwdSeedNote: `确定性：同机同 seed 两次运行产物逐字节一致；时间戳固定 ${FIXED_TIMESTAMP}`,
  videoEncoder: skipVideo ? 'skipped' : (encoder ?? 'placeholder (ffmpeg missing)'),
  providerCalls: 0,
  networkAccess: 'none',
  entries,
  boundaries: [
    'PNG/WAV/ZIP/账号为纯 Node 确定性编码，任何环境逐字节一致。',
    'MP4 在有 ffmpeg 的机器上真实编码（同构建可重现）；无 ffmpeg 时为占位字节（kind=placeholder-video），只能用于 hash 可重现验收，不能用于可解码媒体断言。',
    '所有 UUID/媒体均为合成数据（§9.3 seed=10702），不是真实账号/素材；外部模型 fixture 只替最后一跳，不证明商业模型质量（D-12）。',
  ],
}
writeFileSync(join(dest, 'manifest.json'), JSON.stringify(manifest, null, 2))
if (!existsSync(join(dest, 'manifest.json'))) process.exit(1)
console.log(JSON.stringify({ ok: true, dest, seed, entries: entries.length, videoEncoder: manifest.videoEncoder, providerCalls: 0 }, null, 2))
