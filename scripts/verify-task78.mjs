import assert from 'node:assert/strict'
import { randomUUID, createHash } from 'node:crypto'
import { mkdir, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import bcrypt from 'bcryptjs'
import pg from 'pg'

const baseUrl = process.env.TASK78_BASE_URL || 'http://127.0.0.1:8080'
const databaseUrl = process.env.TASK78_DATABASE_URL || 'postgresql://grassland:grassland@127.0.0.1:55432/grassland'
const artifactDir = fileURLToPath(new URL('../test-artifacts/task78/', import.meta.url))
const runId = Date.now().toString(36)
const password = 'test-password-2026'
const checks = []
const fixtures = {}

function requireLocal(rawUrl) {
  assert.ok(['127.0.0.1', 'localhost', '[::1]'].includes(new URL(rawUrl).hostname), 'Only local acceptance environments are allowed')
}

requireLocal(baseUrl)
requireLocal(databaseUrl)
await mkdir(artifactDir, { recursive: true })

async function saveResults() {
  await writeFile(`${artifactDir}/api-results.json`, JSON.stringify({ runId, baseUrl, executedAt: new Date().toISOString(), checks }, null, 2))
}

function record(name, details = {}) {
  checks.push({ name, passed: true, ...details })
  console.log(`PASS ${name}`)
}

async function seed() {
  const client = new pg.Client({ connectionString: databaseUrl })
  await client.connect()
  try {
    await client.query('BEGIN')
    const hash = await bcrypt.hash(password, 10)
    for (const [name, role, tier] of [
      ['personal', null, null], ['merchant', null, 'finance_transaction'],
      ['basic', null, 'basic_publish'], ['kyb', null, 'draft'],
      ['admin', 'platform_admin', null], ['cs', 'customer_service', null],
      ['risk', 'risk', null], ['reviewer', 'content_reviewer', null],
    ]) {
      const id = randomUUID()
      const email = `task78-${runId}-${name}@test.local`
      await client.query(
        "INSERT INTO app_users(id,email,password_hash,display_name,role,status) VALUES($1,$2,$3,$4,$5,'active')",
        [id, email, hash, `任务78验收-${name}`, role === 'platform_admin' ? 'admin' : 'user'],
      )
      fixtures[name] = { id, email }
      if (role) await client.query('INSERT INTO backend_role(account_id,role) VALUES($1,$2)', [id, role])
      if (tier) {
        const orgId = randomUUID()
        fixtures[name].orgId = orgId
        await client.query(
          "INSERT INTO organization(id,owner_account_id,name,status,permission_tier,industry,account_prefix) VALUES($1,$2,$3,'active',$4,'other',$5)",
          [orgId, id, `任务78验收-${name}-${runId}`, tier, `t78${runId}${name}`],
        )
        await client.query("INSERT INTO organization_membership(id,organization_id,account_id,role) VALUES($1,$2,$3,'owner')", [randomUUID(), orgId, id])
        await client.query("INSERT INTO identity_profile(id,account_id,identity_type,organization_id,status) VALUES($1,$2,'merchant',$3,'active')", [randomUUID(), id, orgId])
      } else if (!role) {
        await client.query("INSERT INTO identity_profile(id,account_id,identity_type,status) VALUES($1,$2,'recommender','active')", [randomUUID(), id])
      }
    }
    await client.query('COMMIT')
    await writeFile(`${artifactDir}/fixtures.json`, JSON.stringify({ runId, fixtures }, null, 2))
    record('独立测试账号与四角色夹具创建成功')
  } catch (error) {
    await client.query('ROLLBACK')
    throw error
  } finally {
    await client.end()
  }
}

function session() {
  const cookies = new Map()
  return async function request(path, { method = 'GET', body, expected = 200 } = {}) {
    const response = await fetch(new URL(path, baseUrl), {
      method,
      headers: { Origin: baseUrl, 'Content-Type': 'application/json', Cookie: [...cookies].map(([name, value]) => `${name}=${value}`).join('; ') },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(30000),
      redirect: 'error',
    })
    for (const cookie of response.headers.getSetCookie()) {
      const pair = cookie.split(';', 1)[0]
      const separator = pair.indexOf('=')
      cookies.set(pair.slice(0, separator), pair.slice(separator + 1))
    }
    const text = await response.text()
    assert.ok([expected].flat().includes(response.status), `${method} ${path}: expected ${expected}, got ${response.status}: ${text.slice(0, 500)}`)
    return { status: response.status, text, json: response.headers.get('content-type')?.includes('json') ? JSON.parse(text) : null }
  }
}

async function login(name) {
  const request = session()
  await request('/api/auth/login', { method: 'POST', body: { email: fixtures[name].email, password } })
  return request
}

function png5Mb() {
  const original = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aM1EAAAAASUVORK5CYII=', 'base64')
  const payload = Buffer.alloc(5 * 1024 * 1024 - original.length - 12, 97)
  payload.write('Task78\0')
  const chunk = Buffer.concat([Buffer.from('tEXt'), payload])
  let checksum = 0xffffffff
  for (const byte of chunk) {
    checksum ^= byte
    for (let bit = 0; bit < 8; bit++) checksum = (checksum >>> 1) ^ ((checksum & 1) ? 0xedb88320 : 0)
  }
  const length = Buffer.alloc(4)
  length.writeUInt32BE(payload.length)
  const crc = Buffer.alloc(4)
  crc.writeUInt32BE((checksum ^ 0xffffffff) >>> 0)
  return Buffer.concat([original.subarray(0, -12), length, chunk, crc, original.subarray(-12)])
}

async function verifyModelSource() {
  const anonymous = session()
  await anonymous('/api/ai/preferences', { expected: 401 })
  record('未登录偏好端点返回401')
  const request = await login('personal')
  const initial = (await request('/api/ai/preferences')).json.data
  assert.equal(initial.modelSource, 'platform')
  assert.equal(initial.masterVersion, 0)
  assert.equal(initial.items.length, 4)
  record('新账号默认platform并返回四能力只读项', initial)
  for (const body of [{ modelSource: 'invalid', expectedVersion: 0 }, { modelSource: 'own' }, { modelSource: 'own', expectedVersion: -1 }]) {
    await request('/api/ai/preferences/model-source', { method: 'PUT', body, expected: 400 })
  }
  record('非法来源及缺失/负版本返回400')
  const changed = (await request('/api/ai/preferences/model-source', { method: 'PUT', body: { modelSource: 'own', expectedVersion: initial.masterVersion } })).json.data
  assert.equal(changed.modelSource, 'own')
  assert.ok(changed.masterVersion > initial.masterVersion)
  record('切换own成功且版本递增', changed)
  let syntheticKeyId
  try {
    await request('/api/ai/preferences/model-source', { method: 'PUT', body: { modelSource: 'platform', expectedVersion: initial.masterVersion }, expected: 409 })
    record('旧版本写入409且不覆盖当前来源')
    await request('/api/ai/preferences/text', { method: 'PUT', body: { useOwnKey: true, expectedVersion: 0 }, expected: 404 })
    record('旧per-capability写接口返回404')
    const denied = await request('/api/ai/runs', { method: 'POST', body: { capability: 'text', prompt: '任务78本地验收，不调用真实模型', maxTokens: 16, allowFallback: true }, expected: 422 })
    assert.match(denied.text, /未配置自有模型密钥/)
    record('own缺密钥即使允许回退仍返回422', { status: denied.status, response: denied.json })
    const key = (await request('/api/ai/keys', { method: 'POST', expected: 201, body: { capability: 'text', provider: 'openai-compatible', baseUrl: 'https://example.com/v1', model: 'task78-not-a-real-model', apiKey: `task78-synthetic-${randomUUID()}` } })).json
    syntheticKeyId = key.id
    assert.ok(syntheticKeyId)
    assert.equal(key.enabled, true)
    assert.equal(key.apiKey, undefined)
    await request(`/api/ai/keys/${syntheticKeyId}`, { method: 'PUT', body: { baseUrl: 'https://example.com/v1', model: 'task78-updated-test-model' } })
    await request(`/api/ai/keys/${syntheticKeyId}/key`, { method: 'PUT', body: { apiKey: `task78-rotated-${randomUUID()}` } })
    record('合成个人密钥创建、配置更新、轮换通过；不发起生成')
    const safety = (await request('/api/content-safety/check', { method: 'POST', body: { text: '这是一段任务78本地测试正文，用于确认本地检查保留。'.repeat(20), contentForm: 'article' } })).json.data.safety
    assert.equal(safety.deepCheckSkipped, true)
    assert.equal(safety.deepCheck, false)
    assert.ok(Array.isArray(safety.findings))
    assert.ok(safety.lexiconVersion)
    record('own手动检查跳过L2且保留词库结果', safety)
    const fix = await request('/api/content-safety/fix', { method: 'POST', body: { text: '任务78测试正文', findings: [{ category: 'ad_law', match: '测试', advice: '本地验收' }], contentForm: 'article' } })
    assert.match(fix.text, /"type":"skipped"/)
    assert.match(fix.text, /own_model_source/)
    record('own修复SSE返回skipped而非503', { status: fix.status, response: fix.text })
    const scopes = (await request('/api/me/organization-scopes')).json.data
    assert.deepEqual(scopes, [])
    record('纯个人组织治理范围为空')
  } finally {
    if (syntheticKeyId) {
      await request(`/api/ai/keys/${syntheticKeyId}`, { method: 'DELETE', expected: [200, 204] })
      record('合成个人密钥已停用')
    }
    const current = (await request('/api/ai/preferences')).json.data
    const restored = (await request('/api/ai/preferences/model-source', { method: 'PUT', body: { modelSource: 'platform', expectedVersion: current.masterVersion } })).json.data
    assert.equal(restored.modelSource, 'platform')
    record('切回platform成功', restored)
  }
  const safety = (await request('/api/content-safety/check', { method: 'POST', body: { text: '短文本仅做本地检查，避免产生真实模型费用。' } })).json.data.safety
  assert.notEqual(safety.deepCheckSkipped, true)
  record('platform恢复非绕过检查语义', safety)
  return request
}

async function verifyUpload(request) {
  const png = png5Mb()
  const ticket = (await request('/api/media/upload-tickets', { method: 'POST', body: { purpose: 'content_asset', contentType: 'image/png', sizeBytes: png.length } })).json.data
  requireLocal(ticket.uploadUrl)
  assert.equal(new URL(ticket.uploadUrl).port, '9002')
  const uploaded = await fetch(ticket.uploadUrl, { method: 'PUT', headers: ticket.headers, body: png, signal: AbortSignal.timeout(45000) })
  assert.ok(uploaded.ok, `5MB upload returned ${uploaded.status}: ${(await uploaded.text()).slice(0, 200)}`)
  await request(`/api/media/${ticket.id}/confirm`, { method: 'POST', body: {} })
  const asset = (await request('/api/content-assets', { method: 'POST', expected: [200, 201], body: { libraryType: 'personal', mediaId: ticket.id, category: 'other', title: `任务78-5MB上传验收-${runId}`, tags: ['task78'], source: 'local acceptance fixture', licenseScope: 'private' } })).json.data
  record('5MB PNG经9002上传、confirm及素材入库成功', { bytes: png.length, sha256: createHash('sha256').update(png).digest('hex'), uploadStatus: uploaded.status, mediaId: ticket.id, assetId: asset.id })
  await request('/api/media/upload-tickets', { method: 'POST', body: { purpose: 'content_asset', contentType: 'image/png', sizeBytes: 32 * 1024 * 1024 }, expected: 400 })
  record('超出后端媒体上限的票据请求被拒绝')
}

async function verifyIdentity() {
  const request = await login('kyb')
  const orgPath = `/api/organizations/${fixtures.kyb.orgId}`
  const profile = { legalName: `任务78虚构测试主体-${runId}`, industry: 'other', businessType: 'company', legalPersonName: '测试人', legalPersonIdNumber: '110105491231002', contactPhone: '13800138000', contactEmail: 'task78@test.local' }
  const rejected = await request(`${orgPath}/merchant-profile`, { method: 'POST', body: profile, expected: 400 })
  assert.match(rejected.text, /18/)
  record('15位法人身份证被后端拒绝', { response: rejected.json })
  const accepted = (await request(`${orgPath}/merchant-profile`, { method: 'POST', body: { ...profile, legalPersonIdNumber: '110103194912310027' } })).json.data
  assert.equal(accepted.legalPersonIdNumberMasked, '****0027')
  record('历史区划18位法人身份证通过且仅回显掩码')
  for (const contact of ['letters', '010-12345678', 'test@example.com', '1380013800']) {
    const invalid = await request(`${orgPath}/permission-requests`, { method: 'POST', body: { requestedTier: 'basic_publish', materials: { business_license: 'task78 synthetic fixture', contact_info: contact } }, expected: 400 })
    assert.match(invalid.text, /手机号/)
  }
  record('权限联系方式字母、座机、邮箱、位数错误均被拒绝')
  await request(`${orgPath}/permission-requests`, { method: 'POST', body: { requestedTier: 'basic_publish', materials: { business_license: 'task78 synthetic fixture', contact_info: '13800138000' } }, expected: [200, 201] })
  record('权限申请11位手机号通过')
  const merchant = await login('merchant')
  const scopes = (await merchant('/api/me/organization-scopes')).json.data
  assert.ok(scopes.some(scope => (scope.organizationId === fixtures.merchant.orgId || scope.id === fixtures.merchant.orgId) && scope.role === 'owner'))
  record('商家owner组织治理范围可用')
}

try {
  await seed()
  const personal = await verifyModelSource()
  await verifyUpload(personal)
  await verifyIdentity()
} catch (error) {
  checks.push({ name: '验收异常', passed: false, message: error.message })
  console.error(error.message)
  process.exitCode = 1
} finally {
  await saveResults()
}
