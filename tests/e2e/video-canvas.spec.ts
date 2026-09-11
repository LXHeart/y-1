import { randomUUID } from 'node:crypto'
import { expect, request as playwrightRequest, test, type APIRequestContext, type APIResponse, type Page } from '@playwright/test'
import { Pool } from 'pg'
import { seedVideoCanvasFixture, type VideoCanvasFixture } from './fixtures/video-canvas'

/**
 * 任务书 #100 C100-08：画布双入口 e2e（首个专业模式里程碑的浏览器层验收）。
 *
 * 覆盖（AC100-08 / §12 TC 汇总）：
 *  1. 草场入口全链：storyboard-only 深链绑定（TC-010）→ 发起制作幂等重放（TC-017）→
 *     采用非推荐候选（TC-001/018，UI）→ 真实 FFmpeg 合成（sandbox provider，容器内）→
 *     交付面板 + 导出（TC-019）→ 跨账号 404（TC-004/010）；
 *  2. AI 应用入口：草场入口产出的 draft 深链恢复同一任务与交付（双入口共享会话），
 *     账号 B 打开账号 A 的分镜被 404 接住（TC-011 的账号隔离面）；
 *  3. 绑定决策表（TC-010）：候选歧义 409、指定草稿唯一关联、不匹配草稿 409、原键重放幂等。
 *
 * 环境前置：隔离 e2e 栈（BASE_URL/AI_BASE_URL/E2E_DATABASE_URL/E2E_PASSWORD，
 * ci-e2e.sh 注入；本地跑法见 scripts/local/e2e-98-local.sh 波次拉栈配方）。
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

test.describe.configure({ mode: 'serial' })

let fixture: VideoCanvasFixture

test.describe('任务书 #100 画布双入口集成验收（C100-08）', () => {

  test.beforeAll(async () => {
    test.skip(!process.env.E2E_PASSWORD || !process.env.E2E_DATABASE_URL,
      '隔离栈环境变量（E2E_PASSWORD/E2E_DATABASE_URL）未注入时跳过')
    // 播种含 admin 登录（argon2）+ 调账 ×2，负载下 30s 缺省会假超时
    test.setTimeout(180_000)
    fixture = await seedVideoCanvasFixture(baseURL)
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
