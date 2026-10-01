// hypit-fix2.ts — C107F2-37（W218）：journey spec 共享夹具。
//
// 只封「与被验收按钮无关」的机械步骤：受控文本模型 fixture 的治理台三件套
// 种子（受信端点→凭据→text/primary 模型行，正式路由/信封加密/计费不变）、
// 12s 无声参考 MP4 生成（ffmpeg testsrc，宿主产物不 mock）、登录/进工作区/
// 建工程、job 终态轮询与 MP4 解码校验。按钮链路由各 TC 驱动真实 UI 完成。
import { execFileSync } from 'node:child_process'
import { mkdirSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { expect, type APIRequestContext, type Page } from '@playwright/test'

export const AI_BASE = process.env.AI_BASE_URL ?? 'http://127.0.0.1:18082'
export const FRONT_BASE = process.env.BASE_URL ?? 'http://127.0.0.1:18080'
export const EVIDENCE = resolve('test-artifacts/task-107/fix2/C37')
export const OWNER_A = 'e2e-merchant@test.local'
export const OWNER_B = 'e2e-cs@test.local'
export const ADMIN_EMAIL = 'e2e-admin@test.local'
export const PASSWORD = process.env.E2E_PASSWORD ?? 'test-password-2026'
export const JOURNEY_TIMEOUT_MS = 15 * 60 * 1000
export const FIXTURE_MODEL = 'fix2-fixture-model'
const PRICE_LABEL = 'fix2-journey-prices'
/** 凭据 apiKey 与 fixture 容器 HYPIT_FIX2_PROVIDER_TOKEN 同源（verify 落盘复用），
 * 证明平台凭据真实流经 BYOK 路径到达 fixture——两边不一致 fixture 会 401。 */
const FIXTURE_TOKEN = process.env.HYPIT_FIX2_PROVIDER_TOKEN ?? 'fix2-journey-fixture-key'

mkdirSync(EVIDENCE, { recursive: true })

/**
 * 受控文本模型 fixture 的治理台三件套（幂等：409 视为成功）。base-url 指向
 * intelligence 网络命名空间内的 loopback fixture（compose override 注入），
 * 正式信封加密/受信端点/模型行全部真实。
 */
export async function seedFix2TextModel(request: APIRequestContext): Promise<void> {
  // 栈冷重建后首个 login 可能 >10s（edge/identity JVM 收敛窗口，round-9 实录
  // 首测被 10s 默认超时打断）。30s×3 次重试；三次全败才判失败。
  let login: Awaited<ReturnType<APIRequestContext['post']>> | null = null
  let lastStatus = -1
  for (let attempt = 0; attempt < 3; attempt += 1) {
    try {
      login = await request.post(`${FRONT_BASE}/api/auth/login`, {
        data: { email: ADMIN_EMAIL, password: PASSWORD }, timeout: 30_000,
      })
      lastStatus = login.status()
      if (lastStatus === 200) break
    } catch {
      lastStatus = -1
    }
    await new Promise((resolve) => setTimeout(resolve, 3000))
  }
  expect(lastStatus, 'admin 登录失败（含冷启动重试后）').toBe(200)
  const cookie = (login!.headers()['set-cookie'] ?? '').split(';')[0]
  const admin = async (method: string, path: string, data?: unknown): Promise<number> => {
    const res = await request.fetch(`${FRONT_BASE}${path}`, {
      method, headers: { cookie }, ...(data === undefined ? {} : { data }),
    })
    return res.status()
  }
  const origin = await admin('POST', '/api/admin/ai/trusted-origins', {
    origin: 'http://localhost:19099', label: 'fix2 journey 受控文本 fixture',
  })
  expect([200, 201, 409], `trusted-origin: ${origin}`).toContain(origin)
  const credentialRes = await request.fetch(`${FRONT_BASE}/api/admin/ai/credentials`, {
    method: 'POST', headers: { cookie },
    data: { name: 'fix2-journey-fixture', provider: 'openai-completions', baseUrl: 'http://localhost:19099/v1', apiKey: FIXTURE_TOKEN },
  })
  const credentialId = credentialRes.ok()
    ? (await credentialRes.json())?.id
    : await existingCredentialId(request, cookie)
  expect(credentialId, `credential: ${credentialRes.status()}`).toBeTruthy()
  const model = await admin('POST', '/api/admin/ai/models', {
    capability: 'text', modelRole: 'primary', credentialId, model: FIXTURE_MODEL,
  })
  expect([200, 201, 409], `model row: ${model}`).toContain(model)
  // V-09 实录：ci-e2e 的 configure_platform_ai 先种了 text/primary=qwen-plus →
  // https://qwen-e2e.invalid（占位死 URL）；(capability,role) 生效行部分唯一使本
  // POST 恒 409——fixture 行从未生效，分析/方案综合全打向死 URL，agent job 恒
  // blocked「AI provider 调用失败」。409 即 PUT 修订生效行指向 fixture（版本 +1，
  // 旧行停用），保证受控模型真正是本栈唯一 text/primary。
  if (model === 409) {
    const revised = await admin('PUT', '/api/admin/ai/models/text/primary', {
      credentialId, model: FIXTURE_MODEL,
    })
    expect([200, 201], `model row revise: ${revised}`).toContain(revised)
  }
  await seedFixturePrice(request, cookie)
}

/**
 * 受控模型专价：真实价目版本链（draft → 明细覆盖 → activate），受控行全 0 =
 * 免费执行——不依赖 e2e 账户积分，也不给其他模型定价。幂等：label 已 active
 * 即返回；已有 draft 则重放明细覆盖（整份覆盖语义，含 copy 自 active 的行）。
 */
async function seedFixturePrice(request: APIRequestContext, cookie: string): Promise<void> {
  const list = await request.fetch(`${FRONT_BASE}/api/admin/ai/price-tables`, { headers: { cookie } })
  expect(list.ok(), `price-tables list: ${list.status()}`).toBeTruthy()
  const listBody = await list.json()
  const versions: Array<{ id: string; label: string; status: string }> = Array.isArray(listBody)
    ? listBody
    : (listBody?.data?.items ?? [])
  const mine = versions.find((version) => version.label === PRICE_LABEL)
  if (mine?.status === 'active') return
  const active = versions.find((version) => version.status === 'active')
  const tableId = mine?.id ?? await (async () => {
    const created = await request.fetch(`${FRONT_BASE}/api/admin/ai/price-tables`, {
      method: 'POST', headers: { cookie },
      data: {
        label: PRICE_LABEL,
        note: '受控文本 fixture 专价（受控行全 0=免费）；明细 copy 自当时 active。',
        ...(active ? { copyFromVersionId: active.id } : {}),
      },
    })
    expect([200, 201], `price-table create: ${created.status()} ${await created.text()}`).toContain(created.status())
    return (await created.json()).id as string
  })()
  const detail = await request.fetch(`${FRONT_BASE}/api/admin/ai/price-tables/${tableId}`, { headers: { cookie } })
  expect(detail.ok(), `price-table detail: ${detail.status()}`).toBeTruthy()
  const detailBody = await detail.json()
  const existing = ((detailBody?.models ?? []) as Array<Record<string, unknown>>)
    .filter((row) => row.modelId !== FIXTURE_MODEL)
    .map((row) => ({
      modelId: row.modelId, capability: row.capability, provider: row.provider,
      centsPer1kInputTokens: row.centsPer1kInputTokens, centsPer1kOutputTokens: row.centsPer1kOutputTokens,
      centsPerImage: row.centsPerImage, centsPerSecond: row.centsPerSecond,
    }))
  const put = await request.fetch(`${FRONT_BASE}/api/admin/ai/price-tables/${tableId}/models`, {
    method: 'PUT', headers: { cookie },
    data: { models: [...existing, {
      modelId: FIXTURE_MODEL, capability: 'text', provider: 'openai-completions',
      centsPer1kInputTokens: 0, centsPer1kOutputTokens: 0, centsPerImage: 0, centsPerSecond: 0,
    }] },
  })
  expect(put.ok(), `price models put: ${put.status()} ${await put.text()}`).toBeTruthy()
  if (mine?.status !== 'active') {
    const activated = await request.fetch(`${FRONT_BASE}/api/admin/ai/price-tables/${tableId}/activate`, {
      method: 'POST', headers: { cookie },
    })
    expect(activated.ok(), `price activate: ${activated.status()} ${await activated.text()}`).toBeTruthy()
  }
}

async function existingCredentialId(request: APIRequestContext, cookie: string): Promise<string | null> {
  const res = await request.fetch(`${FRONT_BASE}/api/admin/ai/credentials`, { headers: { cookie } })
  if (!res.ok()) return null
  const body = await res.json()
  // 列表端点是 Flux → JSON 数组（非 {data:{items}}）；409 的去重键是 (provider, baseUrl)，
  // 与名字无关，两者都要匹配。
  const items: Array<{ id?: string; name?: string; provider?: string; baseUrl?: string }> = Array.isArray(body)
    ? body
    : (body?.data?.items ?? body?.items ?? [])
  const found = items.find((item) => item.name === 'fix2-journey-fixture'
    || (item.provider === 'openai-completions' && item.baseUrl === 'http://localhost:19099/v1'))
  return found?.id ?? null
}

/** 12s 无声参考 MP4（testsrc：可核验的时间码烧录；无声→转写 ABSENT 诚实降级）。 */
export function makeReferenceMp4(): string {
  const path = resolve(EVIDENCE, 'reference-12s.mp4')
  try {
    execFileSync('ffmpeg', ['-y', '-f', 'lavfi', '-i', 'testsrc=duration=12:size=320x240:rate=10',
      '-pix_fmt', 'yuv420p', '-vcodec', 'libx264', path], { stdio: 'pipe' })
  } catch {
    // 宿主无 libx264 时退回 mpeg4（可解码性等价）。
    execFileSync('ffmpeg', ['-y', '-f', 'lavfi', '-i', 'testsrc=duration=12:size=320x240:rate=10',
      '-pix_fmt', 'yuv420p', '-vcodec', 'mpeg4', path], { stdio: 'pipe' })
  }
  return path
}

/** 3s 双色元素成片下载后落盘（供 ffprobe）。 */
export function saveDownload(path: string, bytes: Buffer): void {
  writeFileSync(path, bytes)
}

export async function login(page: Page, email: string): Promise<void> {
  await page.goto(`${AI_BASE}/`)
  await page.getByRole('button', { name: '登录 / 注册' }).click()
  const dialog = page.getByRole('dialog')
  await dialog.locator('#login-email').fill(email)
  await dialog.locator('#login-password').fill(PASSWORD)
  await dialog.locator('button[type="submit"]').click()
  await page.getByTestId('auth-pill').waitFor({ timeout: 30_000 })
}

export async function openWorkspace(page: Page): Promise<void> {
  await page.getByTestId('nav-video-clone').click()
  await page.getByTestId('video-clone-workbench').waitFor({ timeout: 15_000 })
  await page.getByTestId('clone-new-project').waitFor({ state: 'visible', timeout: 15_000 })
  await expect(page.getByTestId('clone-new-project')).toBeEnabled()
}

export async function createProject(page: Page, title: string): Promise<string> {
  await page.getByTestId('clone-new-project').click()
  await page.getByTestId('clone-new-project-dialog').waitFor()
  await page.getByTestId('clone-new-title').fill(title)
  await page.getByTestId('clone-new-submit').click()
  await page.waitForURL(/\/video-clone\/[0-9a-f-]{36}$/, { timeout: 60_000 })
  await page.getByTestId('clone-reference-panel').waitFor({ timeout: 60_000 })
  return page.url().split('/').pop() ?? ''
}

export function ffprobe(path: string): { durationSeconds: number; width: number; height: number; codec: string } {
  const out = execFileSync('ffprobe', ['-v', 'error', '-print_format', 'json', '-show_format', '-show_streams', path],
    { encoding: 'utf8' })
  const parsed = JSON.parse(out)
  const video = parsed.streams.find((s: { codec_type?: string }) => s.codec_type === 'video')
  return {
    durationSeconds: Number(parsed.format?.duration ?? 0),
    width: Number(video?.width ?? 0),
    height: Number(video?.height ?? 0),
    codec: String(video?.codec_name ?? 'unknown'),
  }
}
