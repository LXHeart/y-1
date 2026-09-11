import bcrypt from 'bcryptjs'
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
    await rechargeCredits(baseURL, password, [accountAId, accountBId])
    return {
      accountA: { email: ACCOUNT_A_EMAIL, id: accountAId },
      accountB: { email: ACCOUNT_B_EMAIL, id: accountBId },
      storyboardA,
      storyboardB,
    }
  } finally {
    await pool.end()
  }
}
