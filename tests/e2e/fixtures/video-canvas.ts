import bcrypt from 'bcryptjs'
import { execFile } from 'node:child_process'
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { promisify } from 'node:util'
import { Pool } from 'pg'

/**
 * 任务书 #100 C100-08：画布 e2e 隔离合成 fixtures（§12.1）。
 *
 * 账号 A/B 使用 task100-*@test.invalid 测试标识；口令经 E2E_PASSWORD 运行时注入，
 * 不入任务书/日志/截图。数据只写 E2E_DATABASE_URL 指向的隔离栈（15432/本地 e2e 栈），
 * 不借用用户当前未保存页面，也不直连生产或日常开发库。
 *
 * 分镜无法经 AI 生成（隔离栈假端点拒连是既定行为），与 IT 同款 SQL 直推五镜分镜；
 * video_generation/video_tts 落 sandbox 平台模型行（容器内 ffmpeg 合成真实 testsrc
 * MP4 与正弦波配音），积分经治理台 adjust-credits 正规调账入口充值。
 *
 * C100-19 增补：自有素材走真实三步上传（upload-tickets → presigned PUT → confirm，
 * 本地 ffmpeg 产真 MP4）；撤销素材 DB 直造（校验在读对象前拒绝，无需对象）；
 * UI 拦截层计划载荷（真实服务链由 CanvasWorkflowIntegrationIT 承担）。
 */
const ACCOUNT_A_EMAIL = 'task100-a@test.invalid'
const ACCOUNT_B_EMAIL = 'task100-b@test.invalid'
const ADMIN_EMAIL = 'e2e-admin@test.local'
/** 五镜 × 5s（§12.1 基础分镜口径）。 */
const SHOT_COUNT = 5
const SHOT_SECONDS = 5
/** 1×1 像素 JPEG（分镜 request_payload.images 占位——video 模式成片走 take 媒体）。 */
const PLACEHOLDER_IMAGE =
  'data:image/jpeg;base64,/9j/4AAQSkZJRgABAgAAAQABAAD//gAQTGF2YzYwLjI4LjEwMQD/2wBDAAgEBAQEBAUFBQUFBQYGBgYGBgYGBgYGBgYHBwcICAgHBwcGBgcHCAgICAkJCQgICAgJCQoKCgwMCwsODg4RERT/xABNAAEBAAAAAAAAAAAAAAAAAAAABgEBAQEAAAAAAAAAAAAAAAAAAAYHEAEAAAAAAAAAAAAAAAAAAAAAEQEAAAAAAAAAAAAAAAAAAAAA/8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQABPwA8AD//2Q=='
const execFileAsync = promisify(execFile)

export interface CanvasShotSeed {
  id: string
  seq: number
}

export interface CanvasStoryboardSeed {
  id: string
  shots: CanvasShotSeed[]
}

export interface VideoCanvasAccountSeed {
  email: string
  id: string
}

export interface VideoCanvasFixture {
  accountA: VideoCanvasAccountSeed
  accountB: VideoCanvasAccountSeed
  storyboardA: CanvasStoryboardSeed
  storyboardB: CanvasStoryboardSeed
  /** C100-19 组合链分镜（账号 A：派生 B 方案 + 混合自有素材制作）。 */
  storyboardC: CanvasStoryboardSeed
}

function fixturePool(): Pool {
  const databaseUrl = process.env.E2E_DATABASE_URL
  if (!databaseUrl) throw new Error('E2E_DATABASE_URL is required for video-canvas fixtures')
  return new Pool({ connectionString: databaseUrl, max: 2 })
}

async function upsertUser(pool: Pool, email: string, passwordHash: string): Promise<string> {
  const existing = await pool.query<{ id: string }>('SELECT id FROM app_users WHERE email = $1', [email])
  if (existing.rows.length > 0) {
    // 口令不覆盖（e2e-seed 同款约定）；只确保活跃可登录。
    await pool.query("UPDATE app_users SET status = 'active' WHERE email = $1", [email])
    return existing.rows[0].id
  }
  const created = await pool.query<{ id: string }>(
    `INSERT INTO app_users(id, email, password_hash, status, role)
     VALUES (gen_random_uuid(), $1, $2, 'active', 'user') RETURNING id`,
    [email, passwordHash])
  return created.rows[0].id
}

async function upsertRecommenderProfile(pool: Pool, accountId: string): Promise<void> {
  await pool.query(
    `INSERT INTO identity_profile(id, account_id, identity_type, organization_id, status)
     VALUES (gen_random_uuid(), $1, 'recommender', NULL, 'active')
     ON CONFLICT (account_id, identity_type)
     DO UPDATE SET status = 'active'`,
    [accountId])
}

/** sandbox 平台模型两行（幂等）：video_generation + video_tts。与 IT seedCapability 同构。 */
async function ensureSandboxCapabilities(pool: Pool): Promise<void> {
  for (const [capability, model] of [['video_generation', 'sandbox-video-v1'], ['video_tts', 'sandbox-tts-v1']] as const) {
    const existing = await pool.query(
      'SELECT id FROM platform_model_config WHERE capability = $1 AND provider = \'sandbox\' AND model = $2',
      [capability, model])
    if (existing.rows.length > 0) {
      await pool.query(
        "UPDATE platform_model_config SET enabled = true, health_status = 'healthy' WHERE capability = $1 AND model = $2",
        [capability, model])
      continue
    }
    const baseUrl = `https://${capability}.task100-sandbox.invalid`
    const credential = await pool.query<{ id: string }>(
      `INSERT INTO platform_provider_credential(name, provider, base_url, enabled)
       VALUES ($1, 'sandbox', $2, true)
       ON CONFLICT DO NOTHING RETURNING id`,
      [`task100-${capability}`, baseUrl])
    // ON CONFLICT DO NOTHING 无冲突目标时 RETURNING 可能空——按 (name) 唯一性回查。
    const credentialId = credential.rows[0]?.id
      ?? (await pool.query<{ id: string }>(
        'SELECT id FROM platform_provider_credential WHERE name = $1', [`task100-${capability}`])).rows[0].id
    await pool.query(
      `INSERT INTO platform_model_config(capability, model_role, provider, model, base_url,
         health_status, enabled, version, credential_id)
       VALUES ($1, 'primary', 'sandbox', $2, $3, 'healthy', true, 1, $4)`,
      [capability, model, baseUrl, credentialId])
  }
}

/** 清理两个 fixture 账号的旧视频数据（专账号专数据，重跑不残留歧义候选/任务）。 */
async function resetVideoData(pool: Pool, accountIds: string[]): Promise<void> {
  for (const accountId of accountIds) {
    // C100-19 链路表先清（依赖分镜/草稿行存在才能按归属定位）
    await pool.query('DELETE FROM creation_canvas_agent_plan WHERE account_id = $1', [accountId])
    await pool.query(
      `DELETE FROM creation_canvas_document WHERE account_id = $1`, [accountId])
    await pool.query('DELETE FROM video_storyboard_variant WHERE account_id = $1', [accountId])
    await pool.query(
      `DELETE FROM video_shot_media_source WHERE storyboard_id IN (
         SELECT id FROM video_storyboard WHERE account_id = $1)`, [accountId])
    await pool.query(
      `DELETE FROM video_shot_take WHERE shot_id IN (
         SELECT s.id FROM video_shot s JOIN video_storyboard sb ON s.storyboard_id = sb.id
         WHERE sb.account_id = $1)`, [accountId])
    await pool.query(
      `DELETE FROM video_shot_audio WHERE shot_id IN (
         SELECT s.id FROM video_shot s JOIN video_storyboard sb ON s.storyboard_id = sb.id
         WHERE sb.account_id = $1)`, [accountId])
    await pool.query('DELETE FROM video_production_task WHERE storyboard_id IN '
      + '(SELECT id FROM video_storyboard WHERE account_id = $1)', [accountId])
    // 草稿及其快照一并清（歧义候选由本卡测试自造，重跑不留垃圾行）。
    await pool.query(
      `DELETE FROM creation_draft_version WHERE draft_id IN (
         SELECT id FROM creation_draft WHERE owner_account_id = $1)`, [accountId])
    await pool.query('DELETE FROM creation_draft WHERE owner_account_id = $1', [accountId])
    await pool.query('DELETE FROM video_storyboard WHERE account_id = $1', [accountId])
    // 媒体行最后清（object_key 唯一——重跑残留会让自有/撤销素材种子撞唯一约束）
    await pool.query('DELETE FROM media_reference WHERE owner_account_id = $1', [accountId])
  }
}

async function createStoryboard(pool: Pool, accountId: string, label: string): Promise<CanvasStoryboardSeed> {
  const payload = JSON.stringify({ images: [PLACEHOLDER_IMAGE], shopName: label })
  const storyboard = await pool.query<{ id: string }>(
    `INSERT INTO video_storyboard(account_id, target_duration_seconds, request_payload)
     VALUES ($1, $2, CAST($3 AS jsonb)) RETURNING id`,
    [accountId, SHOT_COUNT * SHOT_SECONDS, payload])
  const storyboardId = storyboard.rows[0].id
  const shots: CanvasShotSeed[] = []
  for (let seq = 1; seq <= SHOT_COUNT; seq += 1) {
    const shot = await pool.query<{ id: string }>(
      `INSERT INTO video_shot(storyboard_id, seq, visual, narration, planned_seconds,
         camera_move, anchor_image_index, prompt)
       VALUES (CAST($1 AS uuid), $2, $3, $4, $5, '固定机位', 1, $6) RETURNING id`,
      [storyboardId, seq, `第${seq}镜画面：${label}招牌与出品`, `第${seq}镜旁白：${label}开业第五年`, SHOT_SECONDS,
        `第${seq}镜提示词`])
    shots.push({ id: shot.rows[0].id, seq })
  }
  return { id: storyboardId, shots }
}

/** 治理台正规调账入口充值（有符号 + 幂等键；比直插 credits 表安全）。 */
async function rechargeCredits(baseURL: string, password: string, accountIds: string[]): Promise<void> {
  const response = await fetch(`${baseURL}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Origin: baseURL },
    body: JSON.stringify({ email: ADMIN_EMAIL, password }),
  })
  if (!response.ok) throw new Error(`admin login failed: ${response.status}`)
  const setCookie = response.headers.get('set-cookie')
  if (!setCookie) throw new Error('admin login missing session cookie')
  const cookie = setCookie.split(';')[0]
  for (const accountId of accountIds) {
    // finance 约束：operationId 必须 admin_adjust: 前缀且总长 ≤64；每轮新键避免幂等吞掉充值。
    // 重试 ×3：栈刚起时 identity→finance 断言调用偶发 502（本地长跑负载）。
    let lastError = ''
    for (let attempt = 0; attempt < 3; attempt += 1) {
      const adjusted = await fetch(`${baseURL}/api/admin/adjust-credits`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Origin: baseURL, Cookie: cookie },
        body: JSON.stringify({
          userId: accountId,
          amount: 20_000,
          note: 'task100 C100-08 e2e fixture recharge',
          operationId: `admin_adjust:c100-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`,
        }),
      })
      if (adjusted.ok) { lastError = ''; break }
      lastError = `${adjusted.status} ${await adjusted.text()}`
      await new Promise(resolve => setTimeout(resolve, 5_000))
    }
    if (lastError) throw new Error(`adjust-credits failed for ${accountId}: ${lastError}`)
  }
}

/**
 * 建齐 C100-08 e2e 所需的隔离合成数据（幂等可重跑）：
 * 账号 A/B（含 recommender 档案）、sandbox 两能力行、各一份全新五镜分镜、积分充值。
 */
export async function seedVideoCanvasFixture(baseURL: string): Promise<VideoCanvasFixture> {
  const password = process.env.E2E_PASSWORD
  if (!password) throw new Error('E2E_PASSWORD is required for video-canvas fixtures')
  const pool = fixturePool()
  try {
    const passwordHash = await bcrypt.hash(password, 10)
    const accountAId = await upsertUser(pool, ACCOUNT_A_EMAIL, passwordHash)
    const accountBId = await upsertUser(pool, ACCOUNT_B_EMAIL, passwordHash)
    await upsertRecommenderProfile(pool, accountAId)
    await upsertRecommenderProfile(pool, accountBId)
    await ensureSandboxCapabilities(pool)
    await resetVideoData(pool, [accountAId, accountBId])
    const storyboardA = await createStoryboard(pool, accountAId, 'A 店')
    const storyboardB = await createStoryboard(pool, accountBId, 'B 店')
    const storyboardC = await createStoryboard(pool, accountAId, 'C 店')
    await rechargeCredits(baseURL, password, [accountAId, accountBId])
    return {
      accountA: { email: ACCOUNT_A_EMAIL, id: accountAId },
      accountB: { email: ACCOUNT_B_EMAIL, id: accountBId },
      storyboardA,
      storyboardB,
      storyboardC,
    }
  } finally {
    await pool.end()
  }
}

// ---------------------------------------------------------------------------
// C100-19：素材、方案、AI 与交付组合 fixtures
// ---------------------------------------------------------------------------

/** 本地 ffmpeg 产真实 10s 红色 540×960 MP4（带 440Hz 音轨；IT twoToneMp4 同款形状）。 */
export async function renderOwnMediaMp4(): Promise<Buffer> {
  const dir = await mkdtemp(join(tmpdir(), 'c100-19-own-'))
  const file = join(dir, 'own.mp4')
  try {
    await execFileAsync('ffmpeg', ['-loglevel', 'error', '-y',
      '-f', 'lavfi', '-i', 'color=c=0xFF0000:s=540x960:d=10:r=30',
      '-f', 'lavfi', '-i', 'sine=frequency=440:duration=10',
      '-map', '0:v', '-map', '1:a', '-shortest', '-pix_fmt', 'yuv420p',
      '-c:v', 'libx264', '-preset', 'ultrafast', '-c:a', 'aac', file])
    return await readFile(file)
  } finally {
    await rm(dir, { recursive: true, force: true })
  }
}

/** 会话 cookie（账号登录；fixtures 内部用，口令经 E2E_PASSWORD 注入不落盘）。 */
async function loginCookie(baseURL: string, email: string, password: string): Promise<string> {
  const response = await fetch(`${baseURL}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Origin: baseURL },
    body: JSON.stringify({ email, password }),
  })
  if (!response.ok) throw new Error(`login failed for ${email}: ${response.status}`)
  const setCookie = response.headers.get('set-cookie')
  if (!setCookie) throw new Error('login missing session cookie')
  return setCookie.split(';')[0]
}

/**
 * 真实三步上传自有素材（用户路径）：upload-tickets → presigned PUT → confirm。
 * 返回 active mediaId（可直接用于每镜来源 own-media）。purpose=user_upload 是客户端
 * 直开票据的唯一合法通用用途（store_media 等仅服务断言代开）。
 */
export async function uploadOwnMedia(baseURL: string, email: string, mp4: Buffer): Promise<string> {
  const password = process.env.E2E_PASSWORD
  if (!password) throw new Error('E2E_PASSWORD is required for uploadOwnMedia')
  const cookie = await loginCookie(baseURL, email, password)
  const ticketResponse = await fetch(`${baseURL}/api/media/upload-tickets`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Origin: baseURL, Cookie: cookie },
    body: JSON.stringify({ contentType: 'video/mp4', purpose: 'user_upload', sizeBytes: mp4.length }),
  })
  const ticketBody = await ticketResponse.json() as { success: boolean; data?: {
    id: string; uploadUrl: string; method: string; headers: Record<string, string>
  }; error?: string }
  if (!ticketResponse.ok || !ticketBody.success || !ticketBody.data) {
    throw new Error(`upload ticket failed: ${ticketResponse.status} ${ticketBody.error ?? ''}`)
  }
  const ticket = ticketBody.data
  // 只带票据返回的头部（presign 只签这些——额外 Content-Type 会破坏签名）
  const putResponse = await fetch(ticket.uploadUrl, {
    method: ticket.method,
    headers: ticket.headers,
    body: new Uint8Array(mp4),
  })
  if (!putResponse.ok) throw new Error(`presigned PUT failed: ${putResponse.status}`)
  const confirmResponse = await fetch(`${baseURL}/api/media/${ticket.id}/confirm`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Origin: baseURL, Cookie: cookie },
    body: '{}',
  })
  const confirmBody = await confirmResponse.json() as { success: boolean; data?: { id: string }; error?: string }
  if (!confirmResponse.ok || !confirmBody.success || !confirmBody.data) {
    throw new Error(`media confirm failed: ${confirmResponse.status} ${confirmBody.error ?? ''}`)
  }
  return confirmBody.data.id
}

/** DB 直造已删除素材（TC-023 撤销/删除面：来源校验在读对象前拒绝，无需真实对象。
 *  媒体状态机无 revoked 值——撤销授权的库面即软删：status=deleted + deleted_at。） */
export async function seedRevokedMedia(pool: Pool, accountId: string): Promise<string> {
  // object_key 全局唯一——随机后缀避免重跑撞键（reset 也会清，双保险）
  const objectKey = `media/user_upload/revoked-c100-19-${Math.random().toString(36).slice(2, 10)}`
  const row = await pool.query<{ id: string }>(
    `INSERT INTO media_reference(id, owner_account_id, purpose, object_key, mime_type,
       size_bytes, source, status, deleted_at)
     VALUES (gen_random_uuid(), $1, 'user_upload', $2, 'video/mp4', 1024, 'upload',
       'deleted', now()) RETURNING id::text`,
    [accountId, objectKey])
  return row.rows[0].id
}

/** UI 拦截层计划载荷（CanvasPlanResult 形状；真实服务链由 CanvasWorkflowIntegrationIT 验证）。 */
export interface CanvasPlanFixture {
  id: string
  status: 'preparing' | 'ready' | 'clarify' | 'failed' | 'applied' | 'expired'
  draftId: string
  storyboardId: string
  baseDraftVersion: number
  baseEditVersion: number
  baseCanvasRevision: number
  summary: string
  clarification: string | null
  action: { kind: 'edit'; actions: Array<{ kind: string; patch: Record<string, unknown> }> } | null
  runId: string | null
  errorCode: string | null
  expiresAt: string
}

export function preparingPlanFixture(input: { planId: string; draftId: string; storyboardId: string }): CanvasPlanFixture {
  return {
    id: input.planId, status: 'preparing', draftId: input.draftId, storyboardId: input.storyboardId,
    baseDraftVersion: 1, baseEditVersion: 1, baseCanvasRevision: 1,
    summary: '', clarification: null, action: null, runId: null, errorCode: null,
    expiresAt: new Date(Date.now() + 30 * 60_000).toISOString(),
  }
}

/** ready 计划：单条 update-shot 动作（严格解析认可的形态——与 IT 测试模型桩一致）。 */
export function readyEditPlanFixture(input: {
  planId: string; draftId: string; storyboardId: string; shotId: string; visual?: string
}): CanvasPlanFixture {
  return {
    ...preparingPlanFixture(input),
    status: 'ready',
    summary: '把选中镜头画面改得更抓人',
    action: {
      kind: 'edit',
      actions: [{ kind: 'update-shot', patch: { shotId: input.shotId, visual: input.visual ?? 'AI 改写的画面' } }],
    },
    runId: '00000000-0000-4000-8000-0000000000c1',
  }
}

/** apply 成功结果（ApplyCanvasPlanResult 形状；拦截层专用）。 */
export function appliedResultFixture(input: {
  planId: string; storyboardId: string; draftId: string; shotId: string
}) {
  return {
    planId: input.planId, storyboardId: input.storyboardId, draftId: input.draftId,
    editVersion: 2, affectedShotIds: [input.shotId],
    variant: null, preparedGeneration: null,
  }
}
