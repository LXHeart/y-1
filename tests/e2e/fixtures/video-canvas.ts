import bcrypt from 'bcryptjs'
import { execFile } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { promisify } from 'node:util'
import { inflateRawSync } from 'node:zlib'
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
 * #102 的计划由独立 loopback 文本 fixture 返回，业务 API 始终经过真实 Java 服务。
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
    await pool.query('DELETE FROM content_asset WHERE owner_account_id = $1', [accountId])
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
export async function seedVideoCanvasFixture(baseURL: string, namespace?: string): Promise<VideoCanvasFixture> {
  const password = process.env.E2E_PASSWORD
  if (!password) throw new Error('E2E_PASSWORD is required for video-canvas fixtures')
  const pool = fixturePool()
  try {
    const passwordHash = await bcrypt.hash(password, 10)
    if (namespace && !/^[a-z0-9-]{1,48}$/.test(namespace)) throw new Error('Invalid task102 fixture namespace')
    const emailA = namespace ? `task102-${namespace}-a@test.invalid` : ACCOUNT_A_EMAIL
    const emailB = namespace ? `task102-${namespace}-b@test.invalid` : ACCOUNT_B_EMAIL
    const accountAId = await upsertUser(pool, emailA, passwordHash)
    const accountBId = await upsertUser(pool, emailB, passwordHash)
    await upsertRecommenderProfile(pool, accountAId)
    await upsertRecommenderProfile(pool, accountBId)
    await ensureSandboxCapabilities(pool)
    await resetVideoData(pool, [accountAId, accountBId])
    const storyboardA = await createStoryboard(pool, accountAId, 'A 店')
    const storyboardB = await createStoryboard(pool, accountBId, 'B 店')
    const storyboardC = await createStoryboard(pool, accountAId, 'C 店')
    await rechargeCredits(baseURL, password, [accountAId, accountBId])
    return {
      accountA: { email: emailA, id: accountAId },
      accountB: { email: emailB, id: accountBId },
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
  return uploadCanvasMedia(baseURL, email, mp4, 'video/mp4')
}

export async function uploadCanvasMedia(baseURL: string, email: string, bytes: Buffer, contentType: string): Promise<string> {
  const password = process.env.E2E_PASSWORD
  if (!password) throw new Error('E2E_PASSWORD is required for uploadOwnMedia')
  const cookie = await loginCookie(baseURL, email, password)
  const ticketResponse = await fetch(`${baseURL}/api/media/upload-tickets`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Origin: baseURL, Cookie: cookie },
    body: JSON.stringify({ contentType, purpose: 'user_upload', sizeBytes: bytes.length }),
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
    body: new Uint8Array(bytes),
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

/** #102 seeds only source inputs. Binding, plans, variants, sources and delivery are created by the UI. */
export async function seedCanvasClosureFixture(baseURL: string, namespace: string, taskSource: boolean) {
  const fixture = await seedVideoCanvasFixture(baseURL, namespace)
  const pool = fixturePool()
  const taskId = `task102-${namespace}`
  const storeId = `store102-${namespace}`
  let snapshotId: string | null = null
  try {
    // Eight spoken characters produce exactly two seconds of sandbox TTS for generated shots.
    await pool.query("UPDATE video_shot SET narration='第'||seq||'镜头欢迎光临' WHERE storyboard_id=$1", [fixture.storyboardA.id])
    await pool.query("UPDATE video_storyboard SET request_payload=request_payload || '{\"platform\":\"douyin\"}'::jsonb WHERE id=$1", [fixture.storyboardA.id])
    if (taskSource) {
      const snapshot = await pool.query<{ id: string }>(
        `INSERT INTO creation_context_snapshot(account_id,task_id,application_id,task_version,
           platform_id,content_form_id,task_snapshot,platform_rules_snapshot,material_snapshot,ai_config_snapshot)
         SELECT $1,$2,$3,7,'douyin','video',$4::jsonb,'{"rule":"task102 frozen platform rule"}'::jsonb,'{}'::jsonb,
           jsonb_build_object('resolutionType','PLATFORM','configId',id::text,'provider',provider,'model',model,
             'platformModelVersion',version,'modelRole',model_role)
         FROM platform_model_config WHERE capability='text' AND enabled=true RETURNING id::text`,
        [fixture.accountA.id, taskId, randomUUID(), JSON.stringify({ storeId, requirements: 'task102 frozen requirement' })])
      if (snapshot.rows.length !== 1) throw new Error('Exactly one enabled text model is required for the frozen source')
      snapshotId = snapshot.rows[0].id
      await pool.query('UPDATE video_storyboard SET context_snapshot_id=$1 WHERE id=$2', [snapshotId, fixture.storyboardA.id])
    }
    return { ...fixture, snapshotId, taskId: taskSource ? taskId : null, storeId: taskSource ? storeId : null }
  } finally { await pool.end() }
}

/** Red 0–2s, blue 2–5s, green 5–10s, with a 440 Hz source track. */
export async function renderCanvasClosureMedia(): Promise<{ video: Buffer; cover: Buffer }> {
  const dir = await mkdtemp(join(tmpdir(), 'task102-media-'))
  try {
    const output = join(dir, 'colors.mp4')
    await execFileAsync('ffmpeg', ['-loglevel', 'error', '-y',
      '-f', 'lavfi', '-i', 'color=c=red:s=540x960:d=2:r=30',
      '-f', 'lavfi', '-i', 'color=c=blue:s=540x960:d=3:r=30',
      '-f', 'lavfi', '-i', 'color=c=lime:s=540x960:d=5:r=30',
      '-f', 'lavfi', '-i', 'sine=frequency=440:duration=10',
      '-filter_complex', '[0:v][1:v][2:v]concat=n=3:v=1:a=0[v]',
      '-map', '[v]', '-map', '3:a', '-shortest', '-pix_fmt', 'yuv420p',
      '-c:v', 'libx264', '-preset', 'ultrafast', '-c:a', 'aac', output])
    const cover = join(dir, 'cover.png')
    await execFileAsync('ffmpeg', ['-loglevel', 'error', '-y', '-i', output, '-frames:v', '1', cover])
    return { video: await readFile(output), cover: await readFile(cover) }
  } finally { await rm(dir, { recursive: true, force: true }) }
}

export interface CanvasProviderCall {
  id: number
  selectedIds: string[]
  mode: string
  instructionHash: string
  contextHash: string
  model: string
  outcome: string
  maxTokens: number
}

async function task102Container(service: 'canvas-text-provider' | 'intelligence-service'): Promise<string> {
  if (process.env.CANVAS_E2E_TEXT_FIXTURE !== '1' || process.env.COMPOSE_PROJECT_NAME !== 'y1-e2e-task102') {
    throw new Error('The isolated task102 model fixture must be enabled; this is a required test, not a skip')
  }
  const containers = await execFileAsync('docker', ['ps', '-q',
    '--filter', 'label=com.docker.compose.project=y1-e2e-task102',
    '--filter', `label=com.docker.compose.service=${service}`])
  const ids = containers.stdout.trim().split(/\s+/).filter(Boolean)
  if (ids.length !== 1) throw new Error(`Exactly one isolated ${service} must be running`)
  return ids[0]
}

/** No host port and no token in arguments/output: inspect from inside the test-only sidecar. */
export async function readCanvasProviderCalls(): Promise<CanvasProviderCall[]> {
  const id = await task102Container('canvas-text-provider')
  const response = await execFileAsync('docker', ['exec', id, 'node', '-e',
    "fetch('http://127.0.0.1:18999/__calls',{headers:{Authorization:'Bearer '+process.env.CANVAS_E2E_PROVIDER_TOKEN}}).then(async r=>{if(!r.ok)throw Error('fixture calls unavailable');process.stdout.write(await r.text())})"])
  return (JSON.parse(response.stdout) as { calls: CanvasProviderCall[] }).calls
}

/** Inter's Latin glyphs alone cannot render the real Chinese subtitle fixture. */
export async function assertCanvasSubtitleRuntime(): Promise<void> {
  const id = await task102Container('intelligence-service')
  const result = await execFileAsync('docker', ['exec', id, 'fc-list', ':lang=zh', 'family'])
  if (!result.stdout.trim()) throw new Error('The media runtime has no Chinese subtitle fallback; a box-glyph MP4 is not a complete delivery')
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

export function walkZipEntries(buffer: Buffer): ZipCentralEntry[] {
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

export function readZipEntry(buffer: Buffer, entryName: string): Buffer {
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
