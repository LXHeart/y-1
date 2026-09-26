/**
 * 数字人 e2e 合成夹具（任务书 #105E C105E-06）。
 *
 * 只做合成账号/会话操作与能力探测——不 mock 后端响应（K12：端到端只证明真实集成）。
 * 合成音轨注入 helper 明确标注 synthetic，真实设备路径留给 H（S2）。
 */
import { execFile } from 'node:child_process'
import { expect, type APIRequestContext, type Page } from '@playwright/test'
import { promisify } from 'node:util'
import * as zlib from 'node:zlib'

export const aiBaseURL = process.env.AI_BASE_URL || 'http://127.0.0.1:18082'
export const e2eEmail = process.env.E2E_EMAIL || 'e2e-ci@test.local'
export const e2ePassword = process.env.E2E_PASSWORD ?? ''

/** 在 AI 应用登录（真实 /api/auth/login，沿用既有 e2e 断言形态）。 */
export async function loginOnAiApp(page: Page, email = e2eEmail, password = e2ePassword): Promise<void> {
  await page.goto(aiBaseURL + '/')
  await page.getByRole('button', { name: '登录 / 注册' }).click()
  const dialog = page.getByRole('dialog')
  await dialog.locator('#login-email').fill(email)
  await dialog.locator('#login-password').fill(password)
  const response = page.waitForResponse((item) =>
    item.request().method() === 'POST' && item.url().endsWith('/api/auth/login'))
  await dialog.locator('button[type="submit"]').click()
  expect((await response).status()).toBe(200)
  await page.getByTestId('auth-pill').waitFor({ timeout: 10_000 })
}

/** 打开数字人工作台（经真实导航链接，不直达 URL 绕过导航语义）。 */
export async function openWorkbench(page: Page): Promise<void> {
  await page.getByTestId('nav-digital-human').click()
  await page.waitForSelector('.dh-page', { timeout: 15_000 })
}

export interface DhCapability {
  /** 登录目录的 enabled/newSessionsAllowed（真实 API01）。 */
  catalogEnabled: boolean
  newSessionsAllowed: boolean
  /**
   * 媒体桥（API16）当前可用性（C105X-03 / #105fix-1 接线后语义）：探测用假会话 id——
   * 401/404/200 都证明「请求可达且已接线」（401=未带登录态、404=会话不存在，均非 503）；
   * 503 dh_runtime_unavailable=未接线或 runtime 不可达（两种都如实判不可用）。
   */
  mediaBridgeAvailable: boolean
  mediaBridgeCode: string | null
}

/** 能力探测：只读目录 + 对会话 offer 的当前状态（不伪造任何一步）。 */
export async function probeCapabilities(request: APIRequestContext): Promise<DhCapability> {
  const catalogResponse = await request.get(aiBaseURL + '/api/digital-human/catalog')
  const catalog = await catalogResponse.json().catch(() => null)
  const data = catalog?.data ?? {}
  let mediaBridgeAvailable = false
  let mediaBridgeCode: string | null = null
  // 媒体桥探测用假会话 id：已接线时预期 401/404（请求本身可达，非 503）而非网络错误。
  const probe = await request.post(
    aiBaseURL + '/api/digital-human/sessions/00000000-0000-4000-8000-000000000000/webrtc/offer',
    { data: { requestId: '00000000-0000-4000-8000-000000000001', leaseEpoch: 1, mediaEpoch: 1, sdp: 'v=0\r\n', type: 'offer' } },
  ).catch(() => null)
  if (probe != null) {
    const body = await probe.json().catch(() => null)
    mediaBridgeCode = body?.code ?? String(probe.status())
    mediaBridgeAvailable = probe.status() !== 503
  }
  return {
    catalogEnabled: data.enabled === true,
    newSessionsAllowed: data.newSessionsAllowed === true,
    mediaBridgeAvailable,
    mediaBridgeCode,
  }
}

/**
 * 合成音轨注入（标注 synthetic）：三引擎无真实麦克风设备时的受控媒体路径。
 * 仅 H（S2 实机）之前的本地冒烟使用；不冒充真实设备验收。
 */
export function syntheticAudioTrackLabel(): string {
  return 'synthetic-audio-track(S1-local-only)'
}

// ---------- C105F-05：合成脸/音频与 ffprobe 检查（TC105F-05-01 fixture 责任） ----------

/**
 * 合成频标（与 tests/test_recording.py 同口径）：节目输出 1kHz、用户 mic 合成标记 2kHz。
 * 隐私断言据此判定「mic 频标不进产物」（频谱证明在 Python 侧真编码做；浏览器侧做容器/SHA/SRT 面）。
 */
export const PROGRAM_AUDIO_MARKER_HZ = 1_000
export const MIC_AUDIO_MARKER_HZ = 2_000

/** 合成单人脸图（有效 PNG，纯色单主体；无外部依赖，zlib+CRC 手工封装）。 */
export function syntheticFacePng(width = 320, height = 240): Buffer {
  function chunk(type: string, data: Buffer): Buffer {
    const body = Buffer.concat([Buffer.from(type, 'ascii'), data])
    const crc = crc32(body)
    return Buffer.concat([writeU32(body.length), body, writeU32(crc)])
  }
  function writeU32(value: number): Buffer {
    const out = Buffer.alloc(4)
    out.writeUInt32BE(value >>> 0, 0)
    return out
  }
  // 扫描行：filter 0 + RGB 三字节（合成肤色调）。
  const row = Buffer.alloc(1 + width * 3)
  for (let x = 0; x < width; x++) {
    row[1 + x * 3] = 0xe6
    row[2 + x * 3] = 0xc5
    row[3 + x * 3] = 0xa8
  }
  const raw = Buffer.concat(Array(height).fill(row) as Buffer[])
  const ihdr = Buffer.concat([writeU32(width), writeU32(height), Buffer.from([8, 2, 0, 0, 0])])
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw)),
    chunk('IEND', Buffer.alloc(0)),
  ])
}

function crc32(data: Buffer): number {
  let crc = 0xffffffff
  for (const byte of data) {
    crc ^= byte
    for (let bit = 0; bit < 8; bit++) {
      crc = (crc >>> 1) ^ (0xedb88320 & -(crc & 1))
    }
  }
  return (crc ^ 0xffffffff) >>> 0
}

/** ffprobe 容器检查（真实子进程；H264/AAC/采样率/时长/音轨在场）。 */
export async function ffprobeJson(path: string): Promise<{
  streams: Array<{ codec_type: string; codec_name: string; sample_rate?: string }>
  format: { duration: string }
}> {
  const run = promisify(execFile)
  const { stdout } = await run('ffprobe',
    ['-v', 'error', '-print_format', 'json', '-show_streams', '-show_format', path], { timeout: 30_000 })
  return JSON.parse(stdout)
}

/** SRT 结构检查（UTF-8 可解码、序号从 1、严格 start<end、时间轴非负）。 */
export function parseSrt(text: string): Array<{ index: number; startMs: number; endMs: number; body: string }> {
  const cues: Array<{ index: number; startMs: number; endMs: number; body: string }> = []
  for (const block of text.trim().split(/\r?\n\r?\n/)) {
    const lines = block.split(/\r?\n/)
    if (lines.length < 3) {
      continue
    }
    const timing = lines[1].match(/^(\d{2}):(\d{2}):(\d{2}),(\d{3}) --> (\d{2}):(\d{2}):(\d{2}),(\d{3})$/)
    if (!timing) {
      throw new Error(`SRT 时间轴格式非法：${lines[1]}`)
    }
    const values = timing.slice(1).map(Number)
    const toMs = (h: number, m: number, s: number, ms: number) => ((h * 60 + m) * 60 + s) * 1000 + ms
    const startMs = toMs(values[0], values[1], values[2], values[3])
    const endMs = toMs(values[4], values[5], values[6], values[7])
    if (startMs >= endMs || startMs < 0) {
      throw new Error(`SRT 时间轴非法（start>=end 或负数）：${lines[1]}`)
    }
    cues.push({ index: Number(lines[0]), startMs, endMs, body: lines.slice(2).join('\n') })
  }
  return cues
}
