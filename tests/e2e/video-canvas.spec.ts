import { randomUUID } from 'node:crypto'
import { inflateRawSync } from 'node:zlib'
import { expect, request as playwrightRequest, test, type APIRequestContext, type APIResponse, type Page } from '@playwright/test'
import { Pool } from 'pg'
import {
  appliedResultFixture,
  readyEditPlanFixture,
  renderOwnMediaMp4,
  seedRevokedMedia,
  seedVideoCanvasFixture,
  uploadOwnMedia,
  type VideoCanvasFixture,
} from './fixtures/video-canvas'

/**
 * 任务书 #100 画布 e2e。
 *
 * C100-08（首个专业模式里程碑）覆盖：
 *  1. 草场入口全链：storyboard-only 深链绑定（TC-010）→ 发起制作幂等重放（TC-017）→
 *     采用非推荐候选（TC-001/018，UI）→ 真实 FFmpeg 合成（sandbox provider，容器内）→
 *     交付面板 + 导出（TC-019）→ 跨账号 404（TC-004/010）；
 *  2. AI 应用入口：草场入口产出的 draft 深链恢复同一任务与交付（双入口共享会话），
 *     账号 B 打开账号 A 的分镜被 404 接住（TC-011 的账号隔离面）；
 *  3. 绑定决策表（TC-010）：候选歧义 409、指定草稿唯一关联、不匹配草稿 409、原键重放幂等。
 *
 * C100-19（素材/方案/AI/交付组合，AC100-19）覆盖：
 *  1. 真实链（无模型参与）：绑定 → 派生方案 B（真实服务）→ 自有素材真实三步上传 →
 *     撤销素材来源拒绝（TC-023）→ 混合来源制作（真实 FFmpeg）→ 联合导出 zip 溯源
 *     （manifest 实际采用源/own 截取/音轨策略；master 为真 MP4；字幕不编造）→
 *     A 不变（内容/版本/任务/来源）→ 跨账号全拒绝；
 *  2. UI 层：方案页签 A↔B 切换（真实 API）、own 镜候选面板来源徽标、AI 助手面板
 *     真实错误态（隔离栈模型拒连 → 计划失败可见）+ 拦截层 ready→apply 状态机
 *     （浏览器拦截模型返回——该层验证范围仅 UI 状态机；真实计划/应用链在
 *     CanvasWorkflowIntegrationIT 用测试模型桩覆盖）。
 *
 * 环境前置：隔离 e2e 栈（BASE_URL/AI_BASE_URL/E2E_DATABASE_URL/E2E_PASSWORD，
 * ci-e2e.sh 注入；本地跑法见 scripts/local/e2e-c100-08-local.sh 波次拉栈配方）。
 * 媒体链路真实：sandbox video_generation/video_tts 行 → 容器内 ffmpeg 合成真 MP4；
 * 无真实外部调用（qwen-e2e.invalid 拒连为既定行为）。
 */
const baseURL = process.env.BASE_URL || 'http://127.0.0.1:18080'
const aiBaseURL = process.env.AI_BASE_URL || 'http://127.0.0.1:18082'
const password = process.env.E2E_PASSWORD || 'test-password-2026'

interface Envelope<T> {
  success: boolean
  data: T
  error?: string
  code?: string
}

async function data<T>(response: APIResponse, expectedStatus: number | number[] = [200, 201]): Promise<T> {
  const expected = Array.isArray(expectedStatus) ? expectedStatus : [expectedStatus]
  expect(expected, await response.text()).toContain(response.status())
  const body = await response.json() as Envelope<T>
  expect(body.success, JSON.stringify(body)).toBe(true)
  return body.data
}

interface TaskTakeJson {
  id: string
  takeNo: number
  status: string
  selectable: boolean
}

interface TaskShotJson {
  id: string
  seq: number
  takes: TaskTakeJson[]
}

interface VideoTaskJson {
  id: string
  storyboardId: string
  phase: string
  progress: number
  unitPriceCents: number
  actualCostCents: number | null
  actualDurationSeconds: number | null
  selection: Record<string, string>
  selectionVersion?: number
  finalUrl: string | null
  shots: TaskShotJson[]
}

async function pollUntil<T>(description: string, read: () => Promise<T>, ok: (value: T) => boolean,
  timeoutMs = 240_000, intervalMs = 3_000): Promise<T> {
  const deadline = Date.now() + timeoutMs
  let last: T
  for (;;) {
    last = await read()
    if (ok(last)) return last
    if (Date.now() >= deadline) throw new Error(`pollUntil 超时（${description}）：${JSON.stringify(last)}`)
    await new Promise(resolve => setTimeout(resolve, intervalMs))
  }
}

async function loginApi(email: string): Promise<APIRequestContext> {
  const context = await playwrightRequest.newContext({
    baseURL, timeout: 30_000, extraHTTPHeaders: { Origin: baseURL },
  })
  await data(await context.post('/api/auth/login', { data: { email, password } }))
  return context
}

async function uiLoginOnGrassland(page: Page, email: string): Promise<void> {
  await page.goto('/')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: /登录草场/ })
  await dialog.locator('#login-email').fill(email)
  await dialog.locator('#login-password').fill(password)
  await dialog.locator('button[type="submit"]').click()
  // 30s：本地长跑环境 argon2 登录偶发超 10s（task98 同款惯例）
  await page.getByTestId('auth-pill').waitFor({ timeout: 30_000 })
}

async function uiLoginOnAiApp(page: Page, email: string): Promise<void> {
  await page.goto(aiBaseURL + '/')
  await page.getByRole('button', { name: '登录 / 注册' }).click()
  const dialog = page.getByRole('dialog')
  await dialog.locator('#login-email').fill(email)
  await dialog.locator('#login-password').fill(password)
  await dialog.locator('button[type="submit"]').click()
  await page.getByTestId('auth-pill').waitFor({ timeout: 30_000 })
}

/** AC-98-30 同款双主题截图（E2E_SHOT_DIR 传入才落盘，缺省零开销）。 */
async function dualThemeShot(page: Page, name: string, ready: () => Promise<void>): Promise<void> {
  const dir = process.env.E2E_SHOT_DIR
  if (!dir) return
  await ready()
  await page.screenshot({ path: `${dir}/${name}-dark.png`, fullPage: true })
  await page.evaluate(() => localStorage.setItem('theme-preference', 'light'))
  await page.reload()
  await ready()
  await page.screenshot({ path: `${dir}/${name}-light.png`, fullPage: true })
  await page.evaluate(() => localStorage.setItem('theme-preference', 'dark'))
  await page.reload()
  await ready()
}

interface TaskRow {
  id: string
  phase: string
  selection: string | null
  selection_version: number
  final_media_id: string | null
  recompose_seq: number
  actual_duration_seconds: number | null
  actual_cost_cents: number | null
  unit_price_cents: number
}

function dbPool(): Pool {
  const databaseUrl = process.env.E2E_DATABASE_URL
  expect(databaseUrl, 'E2E_DATABASE_URL is required').toBeTruthy()
  return new Pool({ connectionString: databaseUrl!, max: 2 })
}

async function taskRow(pool: Pool, taskId: string): Promise<TaskRow> {
  const result = await pool.query<TaskRow>(
    `SELECT id::text, phase, selection::text AS selection, selection_version, final_media_id::text,
       recompose_seq, actual_duration_seconds, actual_cost_cents, unit_price_cents
     FROM video_production_task WHERE id = $1`, [taskId])
  expect(result.rows.length, `task ${taskId} must exist`).toBe(1)
  return result.rows[0]
}

/** 播种共享 fixture（serial 文件内 C100-08/C100-19 共用；grep 单跑任一 describe 也能自举）。 */
async function ensureFixture(): Promise<VideoCanvasFixture> {
  fixture ??= await seedVideoCanvasFixture(baseURL)
  return fixture
}

// ---- 最小 zip 读取器（C100-19 联合导出断言；stored/deflate，无第三方依赖） ----

function locateZipCentralDirectory(buffer: Buffer): { entries: number; offset: number } {
  for (let i = buffer.length - 22; i >= Math.max(0, buffer.length - 22 - 65_536); i -= 1) {
    if (buffer.readUInt32LE(i) === 0x06054b50) {
      return { entries: buffer.readUInt16LE(i + 10), offset: buffer.readUInt32LE(i + 16) }
    }
  }
  throw new Error('zip EOCD not found')
}

interface ZipCentralEntry {
  name: string
  method: number
  compressedSize: number
  localOffset: number
}

function walkZipEntries(buffer: Buffer): ZipCentralEntry[] {
  const { entries, offset } = locateZipCentralDirectory(buffer)
  const result: ZipCentralEntry[] = []
  let cursor = offset
  for (let index = 0; index < entries; index += 1) {
    if (buffer.readUInt32LE(cursor) !== 0x02014b50) throw new Error('bad zip central directory')
    const nameLength = buffer.readUInt16LE(cursor + 28)
    result.push({
      method: buffer.readUInt16LE(cursor + 10),
      compressedSize: buffer.readUInt32LE(cursor + 20),
      localOffset: buffer.readUInt32LE(cursor + 42),
      name: buffer.subarray(cursor + 46, cursor + 46 + nameLength).toString('utf8'),
    })
    cursor += 46 + nameLength + buffer.readUInt16LE(cursor + 30) + buffer.readUInt16LE(cursor + 32)
  }
  return result
}

function readZipEntry(buffer: Buffer, entryName: string): Buffer {
  for (const entry of walkZipEntries(buffer)) {
    if (entry.name !== entryName) continue
    const nameLength = buffer.readUInt16LE(entry.localOffset + 26)
    const extraLength = buffer.readUInt16LE(entry.localOffset + 28)
    const start = entry.localOffset + 30 + nameLength + extraLength
    const raw = buffer.subarray(start, start + entry.compressedSize)
    return entry.method === 8 ? inflateRawSync(raw) : Buffer.from(raw)
  }
  throw new Error(`zip entry not found: ${entryName}`)
}

test.describe.configure({ mode: 'serial' })

let fixture: VideoCanvasFixture

test.describe('任务书 #100 画布双入口集成验收（C100-08）', () => {

  test.beforeAll(async () => {
    test.skip(!process.env.E2E_PASSWORD || !process.env.E2E_DATABASE_URL,
      '隔离栈环境变量（E2E_PASSWORD/E2E_DATABASE_URL）未注入时跳过')
    // 播种含 admin 登录（argon2）+ 调账 ×2，负载下 30s 缺省会假超时
    test.setTimeout(180_000)
    await ensureFixture()
  })

  test('AC100-08 主链（草场入口）：深链绑定→制作幂等→采用→真实合成→导出→跨账号拒绝', async ({ browser }) => {
    test.setTimeout(600_000)
    const storyboardId = fixture.storyboardA.id
    const pool = dbPool()

    const context = await browser.newContext({ baseURL })
    const page = await context.newPage()
    await uiLoginOnGrassland(page, fixture.accountA.email)

    // ---- TC-010：storyboard-only 旧深链 → 服务端唯一补关联，URL 回写 draft ----
    await page.goto(`/video-canvas?storyboard=${storyboardId}`)
    await expect(page.locator('[data-test="canvas-node-5"]')).toBeVisible({ timeout: 30_000 })
    await page.waitForURL(new RegExp(`draft=`), { timeout: 15_000 })
    const draftId = new URL(page.url()).searchParams.get('draft')!
    expect(draftId).toBeTruthy()
    // 无副本草稿：分镜唯一绑定表恰一行
    const bindings = await pool.query(
      'SELECT draft_id::text FROM video_storyboard_workspace WHERE storyboard_id = $1', [storyboardId])
    expect(bindings.rows.length).toBe(1)
    expect(bindings.rows[0].draft_id).toBe(draftId)
    // 分镜头显示五镜口径（§12.1 基础分镜）
    await expect(page.getByText('5 镜 · 目标 25s')).toBeVisible()

    // ---- TC-017：UI 发起制作 + 同键重放 = 一任务一链 ----
    // 权益查询 3s 超时在本地长跑负载下偶发 502「声誉权益服务暂不可用」（#62 冒烟同款坑）；
    // 同 operationId 幂等重放安全，最多重试 3 次。
    let taskId = ''
    for (let attempt = 0; attempt < 3 && !taskId; attempt += 1) {
      const createResponsePromise = page.waitForResponse((item) =>
        item.request().method() === 'POST' && item.url().endsWith('/api/video-production/tasks'), { timeout: 30_000 })
      await page.locator('[data-test="canvas-run-begin"]').click()
      const createResponse = await createResponsePromise
      const body = await createResponse.json() as { success?: boolean; data?: { id?: string }; error?: string }
      if (createResponse.status() === 200 && body.data?.id) {
        taskId = body.data.id
        break
      }
      // 失败后 RunBar 回到「发起制作」；等 UI 就绪再重试
      await expect(page.locator('[data-test="canvas-run-begin"], [data-test="canvas-run-error"]').first())
        .toBeVisible({ timeout: 15_000 })
      if (attempt === 2) {
        expect(createResponse.status(), await createResponse.text()).toBe(200)
      }
    }
    expect(taskId).toBeTruthy()
    // 断线重试同款：同 operationId（web-{storyboardId}）重放返回同一任务
    const replay = await page.request.post('/api/video-production/tasks', {
      data: { storyboardId, operationId: `web-${storyboardId}` },
    })
    expect(replay.status()).toBe(200)
    expect(((await replay.json()).data as { id: string }).id).toBe(taskId)
    const taskCount = await pool.query(
      'SELECT COUNT(*)::int AS n FROM video_production_task WHERE storyboard_id = $1', [storyboardId])
    expect(taskCount.rows[0].n).toBe(1)

    // ---- 等待 sandbox 候选就绪（每镜 2 条可选；容器内 ffmpeg 逐条产真 MP4）----
    const api = await loginApi(fixture.accountA.email)
    await pollUntil('每镜 2 条可选候选', async () =>
      data<VideoTaskJson>(await api.get(`/api/video-production/tasks/${taskId}`)),
      task => task.shots.length === 5 && task.shots.every(shot =>
        shot.takes.filter(take => take.selectable).length === 2))

    // ---- TC-001/018：UI 采用 S1 非推荐候选（第 2 条）并等待服务端确认 ----
    await page.locator('[data-test="canvas-node-1"]').click()
    await page.locator('[data-test="director-tab-takes"]').click()
    await page.locator('[data-test="canvas-take-adopt-2"]').click()
    await expect(page.locator('[data-test="canvas-take-saved"]')).toContainText('候选 2', { timeout: 15_000 })
    // 其余四镜走同一真实端点补齐（UI 单镜已证；批量等长点击不增价值）
    const afterAdopt = await data<VideoTaskJson>(await api.get(`/api/video-production/tasks/${taskId}`))
    for (const shot of afterAdopt.shots.filter(shot => shot.seq > 1)) {
      const take2 = shot.takes.find(take => take.takeNo === 2)!
      await data(await api.post(`/api/video-production/tasks/${taskId}/takes/select`, {
        data: { selections: [{ shotId: shot.id, takeId: take2.id }] },
      }))
    }
    await pollUntil('选择完整落库（5 镜全第 2 候选）', async () => taskRow(pool, taskId),
      row => row.selection_version >= 5
        && Object.keys(JSON.parse(row.selection ?? '{}')).length === 5)

    // ---- TC-018：选齐后合成入口可用；真实 FFmpeg 合成至 succeeded ----
    await expect(page.locator('[data-test="canvas-run-compose"]')).toBeVisible({ timeout: 20_000 })
    await page.locator('[data-test="canvas-run-compose"]').click()
    await pollUntil('合成完成（真实 ffmpeg）', async () => taskRow(pool, taskId), row =>
      row.phase === 'succeeded', 420_000)
    await expect(page.locator('[data-test="canvas-run-phase"]')).toHaveText('已完成', { timeout: 60_000 })
    await expect(page.locator('[data-test="canvas-delivery"]')).toBeVisible({ timeout: 60_000 })

    // ---- 结算口径：实际秒 × 单价（一口价多退少补），选择真实影响输出 ----
    const done = await taskRow(pool, taskId)
    expect(done.actual_duration_seconds).toBeGreaterThan(0)
    expect(done.actual_cost_cents).toBe(done.actual_duration_seconds! * done.unit_price_cents)
    const selectionMap = JSON.parse(done.selection!) as Record<string, string>
    expect(selectionMap[fixture.storyboardA.shots[0].id]).toBe(
      afterAdopt.shots.find(shot => shot.seq === 1)!.takes.find(take => take.takeNo === 2)!.id)
    expect(done.final_media_id).toBeTruthy()

    // ---- TC-019：导出取真实产物（MP4 魔数）+ 字幕授权；改配文不触发重生成 ----
    const taskDetail = await data<VideoTaskJson>(await api.get(`/api/video-production/tasks/${taskId}`))
    expect(taskDetail.finalUrl).toBeTruthy()
    const master = await api.get(taskDetail.finalUrl!)
    expect(master.status()).toBe(200)
    const masterBytes = Buffer.from(await master.body())
    expect(masterBytes.length).toBeGreaterThan(10_000)
    expect(masterBytes.subarray(4, 8).toString('ascii')).toBe('ftyp')
    const subtitle = await data<{ downloadUrl: string }>(
      await api.get(`/api/video-production/tasks/${taskId}/subtitle`))
    const srt = await api.get(subtitle.downloadUrl)
    expect(srt.status()).toBe(200)
    expect((await srt.body()).length).toBeGreaterThan(0)

    // 改配文只写草稿 workspace.delivery：任务 recomposeSeq/成片不变。载荷在现有
    // workspace 上做部分修补（同真实 UI 的 onUpdateDelivery）——从零重建会抹掉
    // inputs.video.productionTaskId（恢复链）与 videoCanvas 布局。
    // 版本 CAS 重试一次：画布布局自动保存（800ms debounce）可能与本 PUT 竞争提升版本。
    for (let attempt = 0; ; attempt += 1) {
      const draftNow = await data<{ version: number; workspace?: Record<string, unknown> }>(
        await api.get(`/api/creation-drafts/${draftId}`))
      const saved = await api.put(`/api/creation-drafts/${draftId}`, {
        data: {
          expectedVersion: draftNow.version,
          workspace: {
            ...(draftNow.workspace ?? {}),
            delivery: { version: 1, contentForm: 'video', summary: 'C100-08 e2e 配文修改' },
          },
        },
      })
      if (saved.status() === 200) {
        expect(((await saved.json()) as Envelope<unknown>).success).toBe(true)
        break
      }
      expect(saved.status(), await saved.text()).toBe(409)
      if (attempt >= 2) throw new Error('配文保存连续版本冲突')
    }
    const taskAfterDeliveryEdit = await taskRow(pool, taskId)
    expect(taskAfterDeliveryEdit.recompose_seq).toBe(done.recompose_seq)
    expect(taskAfterDeliveryEdit.final_media_id).toBe(done.final_media_id)

    // ---- TC-020（桌面双主题截图，E2E_SHOT_DIR 传入才落盘）----
    await dualThemeShot(page, 'c100-08-grassland-canvas-delivery', async () => {
      await expect(page.locator('[data-test="canvas-delivery"]')).toBeVisible({ timeout: 60_000 })
    })

    // ---- TC-004/010：跨账号 404（任务/分镜/绑定三口径，不泄露存在性）----
    const intruder = await loginApi(fixture.accountB.email)
    expect((await intruder.get(`/api/video-production/tasks/${taskId}`)).status()).toBe(404)
    expect((await intruder.get(`/api/video-production/storyboards/${storyboardId}`)).status()).toBe(404)
    expect((await intruder.post(`/api/video-production/storyboards/${storyboardId}/workspace`, {
      data: { operationId: randomUUID() },
    })).status()).toBe(404)

    await context.close()
    await pool.end()

    // ---- 供后续用例：AI 应用入口恢复同一交付（draft 深链）----
    test.info().annotations.push({ type: 'fixture', description: JSON.stringify({ taskId, draftId, storyboardId }) })
  })

  test('AC100-08 双入口：AI 应用恢复草场产出的任务与交付；跨账号 UI 被错误态接住', async ({ browser }) => {
    test.setTimeout(180_000)
    const storyboardId = fixture.storyboardA.id
    // 从上一用例的绑定行取 draft（serial 模式下已存在）
    const pool = dbPool()
    const binding = await pool.query<{ draft_id: string }>(
      'SELECT draft_id::text FROM video_storyboard_workspace WHERE storyboard_id = $1', [storyboardId])
    expect(binding.rows.length).toBe(1)
    const draftId = binding.rows[0].draft_id

    // ---- 账号 A 在 AI 应用入口恢复：任务/交付与草场入口同源 ----
    const context = await browser.newContext({ baseURL: aiBaseURL })
    const page = await context.newPage()
    await uiLoginOnAiApp(page, fixture.accountA.email)
    await page.goto(`${aiBaseURL}/video-canvas?storyboard=${storyboardId}&draft=${draftId}`)
    await expect(page.locator('[data-test="canvas-node-5"]')).toBeVisible({ timeout: 30_000 })
    await expect(page.locator('[data-test="canvas-run-phase"]')).toHaveText('已完成', { timeout: 60_000 })
    await expect(page.locator('[data-test="canvas-delivery"]')).toBeVisible({ timeout: 30_000 })
    await dualThemeShot(page, 'c100-08-ai-app-canvas-delivery', async () => {
      await expect(page.locator('[data-test="canvas-delivery"]')).toBeVisible({ timeout: 60_000 })
    })
    await context.close()

    // ---- 账号 B 打开 A 的分镜：404 被绑定失败态接住（不白屏、不泄露内容）----
    const intruderContext = await browser.newContext({ baseURL: aiBaseURL })
    const intruderPage = await intruderContext.newPage()
    await uiLoginOnAiApp(intruderPage, fixture.accountB.email)
    await intruderPage.goto(`${aiBaseURL}/video-canvas?storyboard=${storyboardId}`)
    await expect(intruderPage.locator('[data-test="canvas-binding-failed"], [data-test="canvas-error"]')
      .first()).toBeVisible({ timeout: 30_000 })
    await expect(intruderPage.locator('[data-test="canvas-node-1"]')).toHaveCount(0)
    await intruderContext.close()
    await pool.end()
  })

  test('AC100-08 绑定决策表（TC-010）：候选歧义 409、指定唯一关联、不匹配 409、原键重放幂等', async () => {
    test.setTimeout(120_000)
    const storyboardId = fixture.storyboardB.id
    const accountId = fixture.accountB.id
    const pool = dbPool()
    // 两份引用同分镜的存量草稿（DB 直造候选；绑定行尚不存在）
    const workspace = JSON.stringify({
      schemaVersion: 1, capability: 'video', currentStep: 'storyboard',
      inputs: { video: { storyboardId } },
    })
    const draftIds: string[] = []
    for (const suffix of ['甲', '乙']) {
      const row = await pool.query<{ id: string }>(
        `INSERT INTO creation_draft(owner_account_id, title, source_type, status, version, workspace_json)
         VALUES ($1, $2, 'independent', 'draft', 1, CAST($3 AS jsonb)) RETURNING id::text`,
        [accountId, `B 店歧义草稿${suffix}`, workspace])
      draftIds.push(row.rows[0].id)
    }

    const api = await loginApi(fixture.accountB.email)
    const bind = (operationId: string, draftId?: string, expectedDraftVersion?: number) =>
      api.post(`/api/video-production/storyboards/${storyboardId}/workspace`, {
        data: { operationId, ...(draftId ? { draftId, expectedDraftVersion } : {}) },
      })

    // 多个存量匹配 → 409 歧义
    const ambiguous = await bind(randomUUID())
    expect(ambiguous.status()).toBe(409)
    expect(((await ambiguous.json()) as Envelope<unknown>).code).toBe('WORKSPACE_BINDING_AMBIGUOUS')

    // 指定草稿 → 唯一可信关联（版本 CAS）
    const chosen = await bind(randomUUID(), draftIds[0], 1)
    expect(((await chosen.json()).data as { project: { id: string } }).project.id).toBe(draftIds[0])

    // 不匹配草稿（换绑尝试）→ 409 冲突，不迁移关联
    const mismatch = await bind(randomUUID(), draftIds[1], 1)
    expect(mismatch.status()).toBe(409)
    expect(((await mismatch.json()) as Envelope<unknown>).code).toBe('WORKSPACE_BINDING_CONFLICT')

    // 原键重放 → 同一关联（幂等；无副本草稿/任务）
    const replayOperation = randomUUID()
    const first = await data<{ project: { id: string } }>(await bind(replayOperation, draftIds[0], 1))
    const replay = await data<{ project: { id: string } }>(await bind(replayOperation, draftIds[0], 1))
    expect(replay.project.id).toBe(draftIds[0])
    expect(first.project.id).toBe(draftIds[0])
    const bindingCount = await pool.query(
      'SELECT COUNT(*)::int AS n FROM video_storyboard_workspace WHERE storyboard_id = $1', [storyboardId])
    expect(bindingCount.rows[0].n).toBe(1)
    const taskCount = await pool.query(
      'SELECT COUNT(*)::int AS n FROM video_production_task WHERE storyboard_id = $1', [storyboardId])
    expect(taskCount.rows[0].n).toBe(0)
    await pool.end()
  })
})

/** C100-19 组合链跨用例共享态（serial 文件内按用例顺序填充）。 */
const c19 = {
  storyboardId: '',
  draftIdA: '',
  storyboardB: '',
  draftB: '',
  bShot2: '',
  taskId: '',
  ownMediaId: '',
}

test.describe('任务书 #100 C100-19 素材、方案、AI 与交付组合（AC100-19）', () => {

  test.beforeAll(async () => {
    test.skip(!process.env.E2E_PASSWORD || !process.env.E2E_DATABASE_URL,
      '隔离栈环境变量（E2E_PASSWORD/E2E_DATABASE_URL）未注入时跳过')
    test.setTimeout(180_000)
    await ensureFixture()
  })

  test('AC100-19 真实链：绑定→派生B→撤销素材拒绝→混合来源制作→联合导出溯源→A 不变→跨账号拒绝', async () => {
    test.setTimeout(600_000)
    const pool = dbPool()
    const api = await loginApi(fixture.accountA.email)
    c19.storyboardId = fixture.storyboardC.id

    // ---- 绑定（真实服务：storyboard-only 补关联草稿）----
    const bound = await data<{ project: { id: string } }>(await api.post(
      `/api/video-production/storyboards/${c19.storyboardId}/workspace`,
      { data: { operationId: randomUUID() } }))
    c19.draftIdA = bound.project.id

    // ---- 派生方案 B（真实服务：独立 sb/draft/shot ID；TC-032 服务端面）----
    const sb = await data<{ editVersion: number; shots: Array<{ id: string; seq: number }> }>(
      await api.get(`/api/video-production/storyboards/${c19.storyboardId}`))
    const draftA = await data<{ version: number }>(await api.get(`/api/creation-drafts/${c19.draftIdA}`))
    const variant = await data<{
      variant: { storyboardId: string; draftId: string }
      project: { id: string }
      shotIdMap: Record<string, string>
    }>(await api.post(`/api/video-production/storyboards/${c19.storyboardId}/variants`, {
      data: {
        operationId: randomUUID(),
        expectedEditVersion: sb.editVersion,
        expectedDraftVersion: draftA.version,
        title: 'C100-19 方案B',
        shotIds: sb.shots.map(shot => shot.id),
      },
    }))
    c19.storyboardB = variant.variant.storyboardId
    c19.draftB = variant.project.id
    expect(c19.storyboardB).not.toBe(c19.storyboardId)
    expect(new Set(Object.values(variant.shotIdMap)).size).toBe(sb.shots.length)
    c19.bShot2 = variant.shotIdMap[sb.shots[1].id]!

    // ---- 自有素材：真实三步上传（本地 ffmpeg 产真 MP4）+ 撤销授权行（DB 直造）----
    c19.ownMediaId = await uploadOwnMedia(baseURL, fixture.accountA.email, await renderOwnMediaMp4())
    const revokedMediaId = await seedRevokedMedia(pool, fixture.accountA.id)

    // ---- 撤销素材不可用作来源（TC-023：校验在读对象前拒绝，无对象信息泄露）----
    const revoked = await api.patch(`/api/video-production/storyboards/${c19.storyboardB}/sources`, {
      data: { sources: [{ shotId: c19.bShot2, source: {
        kind: 'own-media', mediaId: revokedMediaId, trimStartMs: 0, trimEndMs: 5000, audioMode: 'mute',
      } }] },
    })
    expect(revoked.status()).toBe(400)
    expect(((await revoked.json()) as Envelope<unknown>).code).toBe('CANVAS_MEDIA_UNAVAILABLE')

    // ---- B 镜2 保存 own 来源（真实校验 + 服务端 ffprobe 实测 [1000,6000)）----
    await data(await api.patch(`/api/video-production/storyboards/${c19.storyboardB}/sources`, {
      data: { sources: [{ shotId: c19.bShot2, source: {
        kind: 'own-media', mediaId: c19.ownMediaId, trimStartMs: 1000, trimEndMs: 6000,
        audioMode: 'source',
      } }] },
    }))

    // ---- B 真实制作（混合：4 generated × 2 候选 + 镜2 own 零候选）----
    const task = await data<{ id: string }>(await api.post('/api/video-production/tasks', {
      data: { storyboardId: c19.storyboardB, operationId: `c100-19-${c19.storyboardB}` } }))
    c19.taskId = task.id
    // 任务 id 回写草稿（与真实 UI 发起制作后的回写同构——深链恢复全靠它）。
    // 版本 CAS 重试：与画布布局自动保存竞争提升版本时 409 重读再试。
    for (let attempt = 0; ; attempt += 1) {
      const draftNow = await data<{ version: number; workspace?: Record<string, unknown> }>(
        await api.get(`/api/creation-drafts/${c19.draftB}`))
      const workspaceNow = draftNow.workspace ?? {}
      const inputs = (workspaceNow.inputs ?? {}) as { video?: Record<string, unknown> }
      const saved = await api.put(`/api/creation-drafts/${c19.draftB}`, {
        data: {
          expectedVersion: draftNow.version,
          workspace: {
            ...workspaceNow,
            inputs: {
              ...inputs,
              video: { ...(inputs.video ?? {}), productionTaskId: c19.taskId },
            },
          },
        },
      })
      if (saved.status() === 200) {
        expect(((await saved.json()) as Envelope<unknown>).success).toBe(true)
        break
      }
      expect(saved.status(), await saved.text()).toBe(409)
      if (attempt >= 2) throw new Error('任务回写草稿连续版本冲突')
    }
    await pollUntil('4 个 generated 镜各 2 条可选候选（own 镜零候选）', async () =>
      data<VideoTaskJson>(await api.get(`/api/video-production/tasks/${c19.taskId}`)),
      detail => detail.shots.length === 5
        && detail.shots.filter(shot => shot.takes.length > 0).length === 4
        && detail.shots.filter(shot => shot.takes.length > 0)
          .every(shot => shot.takes.filter(take => take.selectable).length === 2))

    // 选片（generated 镜全取第 2 候选；UI 单镜采用已在 C100-08 验证）
    const detail = await data<VideoTaskJson>(await api.get(`/api/video-production/tasks/${c19.taskId}`))
    const selections = detail.shots
      .filter(shot => shot.takes.length > 0)
      .map(shot => ({ shotId: shot.id, takeId: shot.takes.find(take => take.takeNo === 2)!.id }))
    await data(await api.post(`/api/video-production/tasks/${c19.taskId}/takes/select`, { data: { selections } }))

    // 合成（真实 FFmpeg：own 段取素材 [1000,6000) 红色窗口）
    await data(await api.post(`/api/video-production/tasks/${c19.taskId}/compose`, { data: {} }))
    await pollUntil('混合合成完成（真实 ffmpeg）', () => taskRow(pool, c19.taskId),
      row => row.phase === 'succeeded', 420_000)
    const done = await taskRow(pool, c19.taskId)
    expect(done.actual_duration_seconds!).toBeGreaterThan(0)
    // 一口价多退少补（TC-029）：实际秒 × 单价
    expect(done.actual_cost_cents).toBe(done.actual_duration_seconds! * done.unit_price_cents)

    // ---- 联合导出（真实 zip）：manifest 声明实际采用源（own 截取/音轨策略可追溯）----
    const exported = await data<{ kind: string; downloadUrl: string }>(
      await api.get(`/api/video-production/tasks/${c19.taskId}/export/bundle`))
    expect(exported.kind).toBe('bundle')
    const zipResponse = await api.get(exported.downloadUrl)
    expect(zipResponse.status()).toBe(200)
    const zip = Buffer.from(await zipResponse.body())
    expect(zip.subarray(0, 2).toString('ascii')).toBe('PK')
    const entryNames = walkZipEntries(zip).map(entry => entry.name)
    expect(entryNames).toContain('bundle/manifest.json')
    expect(entryNames).toContain('bundle/master.mp4')
    expect(entryNames).toContain('bundle/subtitle.srt')
    for (let seq = 1; seq <= 5; seq += 1) {
      expect(entryNames).toContain(`bundle/segments/shot-${seq}.mp4`)
    }
    const manifest = JSON.parse(readZipEntry(zip, 'bundle/manifest.json').toString('utf8')) as {
      shots: Array<{ shotId: string; source: {
        kind: string; mediaId?: string; trimStartMs?: number; trimEndMs?: number; audioMode?: string
      } }>
    }
    const ownEntry = manifest.shots.find(shot => shot.shotId === c19.bShot2)!
    expect(ownEntry.source).toMatchObject({
      kind: 'own-media', mediaId: c19.ownMediaId, trimStartMs: 1000, trimEndMs: 6000,
      audioMode: 'source',
    })
    expect(manifest.shots.filter(shot => shot.source.kind === 'generated')).toHaveLength(4)
    // 成片为真实 MP4（>10KB）；字幕只含 narration 镜文本——own-source 镜无 TTS 行不编造
    expect(readZipEntry(zip, 'bundle/master.mp4').length).toBeGreaterThan(10_000)
    const srt = readZipEntry(zip, 'bundle/subtitle.srt').toString('utf8')
    expect(srt).toContain('第1镜旁白')
    expect(srt).not.toContain('第2镜旁白')

    // ---- A 不变（内容/版本/任务/来源四口径全冻结）----
    const aShots = await pool.query<{ seq: number; visual: string }>(
      'SELECT seq, visual FROM video_shot WHERE storyboard_id = $1 ORDER BY seq', [c19.storyboardId])
    expect(aShots.rows).toHaveLength(5)
    for (const shot of aShots.rows) {
      expect(shot.visual).toBe(`第${shot.seq}镜画面：C 店招牌与出品`)
    }
    const aVersion = await pool.query<{ edit_version: string }>(
      'SELECT edit_version::text FROM video_storyboard WHERE id = $1', [c19.storyboardId])
    expect(BigInt(aVersion.rows[0].edit_version)).toBe(BigInt(sb.editVersion))
    const aTasks = await pool.query<{ n: number }>(
      'SELECT COUNT(*)::int AS n FROM video_production_task WHERE storyboard_id = $1', [c19.storyboardId])
    expect(aTasks.rows[0].n).toBe(0)
    const aSources = await pool.query<{ n: number }>(
      'SELECT COUNT(*)::int AS n FROM video_shot_media_source WHERE storyboard_id = $1', [c19.storyboardId])
    expect(aSources.rows[0].n).toBe(0)

    // ---- 跨账号全拒绝（B 分镜/方案谱系/导出三口径）----
    const intruder = await loginApi(fixture.accountB.email)
    expect((await intruder.get(`/api/video-production/storyboards/${c19.storyboardB}`)).status()).toBe(404)
    expect((await intruder.get(`/api/video-production/storyboards/${c19.storyboardB}/variants`)).status()).toBe(404)
    expect((await intruder.get(`/api/video-production/tasks/${c19.taskId}/export/bundle`)).status()).toBe(404)
    await pool.end()

    test.info().annotations.push({
      type: 'fixture',
      description: JSON.stringify({ ...c19, revokedMediaId }),
    })
  })

  test('AC100-19 UI 层：方案切换 A↔B、own 镜来源徽标、AI 面板真实错误态与拦截成功路径', async ({ browser }) => {
    test.setTimeout(300_000)
    expect(c19.storyboardB, '依赖上一用例产出（serial）').toBeTruthy()
    const context = await browser.newContext({ baseURL })
    const page = await context.newPage()
    await uiLoginOnGrassland(page, fixture.accountA.email)

    // ---- 打开 B 方案：交付态 + 方案页签 + own 镜候选面板徽标 ----
    await page.goto(`/video-canvas?storyboard=${c19.storyboardB}&draft=${c19.draftB}`)
    await expect(page.locator('[data-test="canvas-node-5"]')).toBeVisible({ timeout: 30_000 })
    await expect(page.locator('[data-test="canvas-run-phase"]')).toHaveText('已完成', { timeout: 60_000 })
    await expect(page.locator('[data-test="canvas-delivery"]')).toBeVisible({ timeout: 60_000 })
    await page.locator('[data-test="canvas-node-2"]').click()
    await page.locator('[data-test="director-tab-takes"]').click()
    await expect(page.locator('[data-test="canvas-take-own-source"]')).toBeVisible()
    await expect(page.locator('[data-test="canvas-take-own-source"]')).toContainText('1000–6000 ms')
    await expect(page.locator('[data-test="canvas-take-own-source"]')).toContainText('保留原音')

    // ---- 方案页签（C100-19 装配）：B 当前、A 可切；切换后 A 无任务显示发起制作 ----
    await page.locator('[data-test="director-tab-variants"]').click()
    await expect(page.locator('[data-test="canvas-variants-panel"]')).toBeVisible()
    await expect(page.locator(`[data-test="canvas-variant-current-${c19.storyboardB}"]`)).toBeVisible()
    await page.locator(`[data-test="canvas-variant-switch-${c19.storyboardId}"]`).click()
    await page.waitForURL(new RegExp(`storyboard=${c19.storyboardId}`), { timeout: 30_000 })
    await expect(page.locator('[data-test="canvas-node-5"]')).toBeVisible({ timeout: 30_000 })
    await expect(page.locator('[data-test="canvas-run-begin"]')).toBeVisible({ timeout: 30_000 })
    // 整页态切换后面板页签重置回「镜头属性」——重新打开方案页签再断言当前标记
    await page.locator('[data-test="director-tab-variants"]').click()
    await expect(page.locator(`[data-test="canvas-variant-current-${c19.storyboardId}"]`)).toBeVisible()

    // ---- 切回 B（交付态恢复——两方案各自的内容与任务独立，TC-034 UI 面）----
    await page.locator('[data-test="director-tab-variants"]').click()
    await page.locator(`[data-test="canvas-variant-switch-${c19.storyboardB}"]`).click()
    await page.waitForURL(new RegExp(`storyboard=${c19.storyboardB}`), { timeout: 30_000 })
    await expect(page.locator('[data-test="canvas-delivery"]')).toBeVisible({ timeout: 60_000 })

    // ---- AI 助手真实错误态：提交 → 隔离栈模型拒连（DNS pin → 127.0.0.1）→ 失败可见 ----
    await page.locator('[data-test="canvas-toggle-assistant"]').click()
    await expect(page.locator('[data-test="canvas-assistant-panel"]')).toBeVisible()
    await page.locator('[data-test="canvas-node-1"]').click()
    await page.locator('[data-test="canvas-assistant-instruction"]').fill('把第一镜改得更抓人')
    await page.locator('[data-test="canvas-assistant-submit"]').click()
    await expect(page.locator('[data-test="canvas-assistant-error"]')).toBeVisible({ timeout: 120_000 })
    // 瞬态面板态不做双主题 reload 截图（reload 即丢会话态）；明暗双主题的助手视觉面
    // 由 verify-video-canvas.mjs 交互矩阵覆盖（其按主题逐组合截图）
    {
      const dir = process.env.E2E_SHOT_DIR
      if (dir) {
        await page.screenshot({ path: `${dir}/c100-19-grassland-assistant-error-dark.png`, fullPage: true })
      }
    }

    // ---- AI 助手拦截层（UI 状态机）：ready → apply → applied ----
    // 验证范围声明：浏览器拦截 /plans 与 /apply 响应——只驱动 UI 状态机与预览渲染；
    // 真实计划生成/应用链路在 CanvasWorkflowIntegrationIT 以测试模型桩覆盖。
    const planId = randomUUID()
    let interceptedShotId = ''
    await page.route('**/api/creation-assistant/canvas/plans', async route => {
      const body = route.request().postDataJSON() as { selectedNodeIds?: string[] }
      interceptedShotId = body.selectedNodeIds?.[0]?.replace('shot:', '') ?? ''
      await route.fulfill({ contentType: 'application/json', body: JSON.stringify({
        success: true, data: readyEditPlanFixture({
          planId, draftId: c19.draftB, storyboardId: c19.storyboardB, shotId: interceptedShotId,
        }),
      }) })
    })
    await page.route(`**/api/creation-assistant/canvas/plans/${planId}/apply`, async route => {
      await route.fulfill({ contentType: 'application/json', body: JSON.stringify({
        success: true, data: appliedResultFixture({
          planId, storyboardId: c19.storyboardB, draftId: c19.draftB, shotId: interceptedShotId,
        }),
      }) })
    })
    await page.locator('[data-test="canvas-assistant-submit"]').click()
    await expect(page.locator('[data-test="canvas-assistant-status-ready"]')).toBeVisible({ timeout: 30_000 })
    await expect(page.locator('[data-test="canvas-plan-preview"]')).toBeVisible()
    await page.locator('[data-test="canvas-assistant-apply"]').click()
    await expect(page.locator('[data-test="canvas-assistant-status-applied"]')).toBeVisible({ timeout: 30_000 })
    {
      const dir = process.env.E2E_SHOT_DIR
      if (dir) {
        await page.screenshot({ path: `${dir}/c100-19-grassland-assistant-applied-dark.png`, fullPage: true })
      }
    }
    await context.close()
  })

  test('AC100-19 双入口：AI 应用入口恢复 B 方案交付与方案谱系', async ({ browser }) => {
    test.setTimeout(180_000)
    const context = await browser.newContext({ baseURL: aiBaseURL })
    const page = await context.newPage()
    await uiLoginOnAiApp(page, fixture.accountA.email)
    await page.goto(`${aiBaseURL}/video-canvas?storyboard=${c19.storyboardB}&draft=${c19.draftB}`)
    await expect(page.locator('[data-test="canvas-node-5"]')).toBeVisible({ timeout: 30_000 })
    await expect(page.locator('[data-test="canvas-delivery"]')).toBeVisible({ timeout: 60_000 })
    await page.locator('[data-test="director-tab-variants"]').click()
    await expect(page.locator('[data-test="canvas-variants-panel"]')).toBeVisible()
    await expect(page.locator(`[data-test="canvas-variant-current-${c19.storyboardB}"]`)).toBeVisible()
    await dualThemeShot(page, 'c100-19-ai-app-variants', async () => {
      // reload 后面板页签重置——幂等重开再断言
      if (!(await page.locator('[data-test="canvas-variants-panel"]').isVisible().catch(() => false))) {
        await page.locator('[data-test="canvas-node-5"]').waitFor({ timeout: 30_000 })
        await page.locator('[data-test="director-tab-variants"]').click()
      }
      await expect(page.locator('[data-test="canvas-variants-panel"]')).toBeVisible({ timeout: 30_000 })
    })
    await context.close()
  })
})
