// @vitest-environment node
import { describe, expect, it } from 'vitest'
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import {
  checkDigitalHumanContracts, expandGenerated, loadContractFiles, payloadSha256,
  canonicalJson, validateInstance, SEMANTIC_RULES,
  EXPECTED_ADMIN_IDS, EXPECTED_INTERNAL_IDS, EXPECTED_PUBLIC_IDS,
} from '../../scripts/quality/check-digital-human-contracts'
import type { Contract } from '../../scripts/quality/check-digital-human-contracts'

/** 检查器内部 Schema 形状的本地别名（其不导出 Schema；与 checker 的 Record 形态一致）。 */
type Schema = Record<string, unknown>

/**
 * 任务书 #105A-02（TC105A-02-01～04）：共享契约完整性、删改负例点名、边界数值、
 * canonical JSON 幂等摘要跨语言互证；并生成端点覆盖报告（每端点成功/缺字段/未知字段/未授权
 * 四种数据样例，机械合成并逐端点验证）。
 */

const REPO_ROOT = path.resolve(__dirname, '../..')
const REPORT_DIR = path.join(REPO_ROOT, 'test-artifacts/task-105/A02')

function makeFixtureRoot(): string {
  const dir = mkdtempSync(path.join(tmpdir(), 'task-105a02-'))
  mkdirSync(path.join(dir, 'contracts'), { recursive: true })
  writeFileSync(path.join(dir, 'contracts/digital-human.v1.json'),
    readFileSync(path.join(REPO_ROOT, 'contracts/digital-human.v1.json')))
  writeFileSync(path.join(dir, 'contracts/digital-human.v1.examples.json'),
    readFileSync(path.join(REPO_ROOT, 'contracts/digital-human.v1.examples.json')))
  return dir
}

describe('TC105A-02-01 契约完整', () => {
  it('tc105a_02_01 检查器零违规且三空间端点精确齐全', () => {
    const { errors, endpointCount, exampleCount } = checkDigitalHumanContracts(REPO_ROOT)
    expect(errors).toEqual([])
    expect(endpointCount).toBe(59)
    expect(exampleCount).toBeGreaterThan(40)

    const { contract } = loadContractFiles(REPO_ROOT)
    const ids = contract.endpoints.map((e) => e.id)
    expect(new Set(ids).size).toBe(ids.length) // 每id唯一
    const spaces = { public: EXPECTED_PUBLIC_IDS, internal: EXPECTED_INTERNAL_IDS, admin: EXPECTED_ADMIN_IDS }
    for (const [space, expected] of Object.entries(spaces)) {
      const actual = new Set(contract.endpoints.filter((e) => e.space === space).map((e) => e.id))
      expect([...expected].every((id) => actual.has(id)), `${space} 空间必须精确齐全`).toBe(true)
      expect(actual.size, `${space} 端点数`).toBe(expected.length)
    }
    // 每端点 DTO/errors/阶段引用闭合由检查器保证；此处抽核 API20 与 INTERNAL05
    const api20 = contract.endpoints.find((e) => e.id === 'API20')!
    expect(api20.requestType).toBe('InterruptRequest')
    expect(api20.errors).toContain('dh_lease_stale')
    const internal05 = contract.endpoints.find((e) => e.id === 'INTERNAL05')!
    expect(internal05.responseType).toBe('GrantBinding')
  })
})

describe('TC105A-02-02 删契约负例', () => {
  it.each([
    ['delete-api20', /API20/],
    ['delete-leaseepoch', /leaseEpoch/],
  ])('tc105a_02_02 %s 检查器失败且点名', (mutation, marker) => {
    const fixture = makeFixtureRoot()
    const contractPath = path.join(fixture, 'contracts/digital-human.v1.json')
    const contract = JSON.parse(readFileSync(contractPath, 'utf8')) as Contract
    if (mutation === 'delete-api20') {
      contract.endpoints = contract.endpoints.filter((e) => e.id !== 'API20')
    } else {
      const def = contract.definitions.InterruptRequest! as { properties: Record<string, unknown>; required: string[] }
      delete def.properties.leaseEpoch
      def.required = def.required.filter((k) => k !== 'leaseEpoch')
    }
    writeFileSync(contractPath, JSON.stringify(contract))
    const before = readFileSync(contractPath, 'utf8')

    const { errors } = checkDigitalHumanContracts(fixture)
    expect(errors.length).toBeGreaterThan(0)
    expect(errors.join('\n')).toMatch(marker)
    if (mutation === 'delete-api20') {
      expect(errors.join('\n')).toMatch(/缺失端点 API20/)
    }
    // 零修改：检查后 fixture 字节不变
    expect(readFileSync(contractPath, 'utf8')).toBe(before)
    rmSync(fixture, { recursive: true, force: true })
  })
})

describe('TC105A-02-03 边界数值', () => {
  const { contract, examples } = loadContractFiles(REPO_ROOT)
  const defs = contract.definitions

  it('tc105a_02_03 越界数值全部拒绝（逐项参数化）', () => {
    const cases: Array<[string, string, unknown]> = [
      ['Seq', 'Seq', 9007199254740992],           // 2^53
      ['Cents', 'Cents', -1],                      // 负金额
      ['VoiceId', 'VoiceId', 'https://provider.invalid/voices/female-1'], // 任意 URL
      ['Epoch', 'Epoch', -1],
      ['Epoch', 'Epoch', 1.5],
      ['Id', 'Id', 'AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA'], // 大写 UUID
      ['Rfc3339', 'Rfc3339', '2026-09-21T10:00:00+08:00'],  // 非 UTC
      ['Cursor', 'Cursor', 'c'.repeat(513)],
    ]
    for (const [name, type, payload] of cases) {
      const problems = validateInstance(defs, defs[type]!, payload, name)
      expect(problems.length, `${name}=${JSON.stringify(payload)} 必须被拒绝`).toBeGreaterThan(0)
    }
    // 合法上界对照
    expect(validateInstance(defs, defs.Epoch!, 9007199254740991, 'Epoch-max')).toEqual([])
  })

  it('tc105a_02_03 未知用量只允许 null+pending（语义规则）', () => {
    const nullsPending = (examples.valid.find((v) => v.name === 'usage-unknown-null-pending')!).payload
    expect(validateInstance(defs, defs.UsageUnits!, nullsPending, 'u')).toEqual([])
    const zerosConfirmed = (examples.invalid.find((v) => v.name === 'usage-unknown-filled-zero')!).payload
    const nullsConfirmed = (examples.invalid.find((v) => v.name === 'usage-nulls-with-confirmed')!).payload
    expect(SEMANTIC_RULES['usage-confirmed-requires-units']!(zerosConfirmed)).toBe(true)
    expect(SEMANTIC_RULES['usage-confirmed-requires-units']!(nullsConfirmed)).toBe(true)
    expect(SEMANTIC_RULES['usage-confirmed-requires-units']!(nullsPending)).toBe(false)

    const confirmed = (examples.valid.find((v) => v.name === 'usage-confirmed')!).payload
    expect(SEMANTIC_RULES['usage-confirmed-requires-units']!(confirmed)).toBe(false)
  })

  it('tc105a_02_03 全部 invalid 夹具（含 generated 展开）都被拒绝', () => {
    expect(examples.invalid.length).toBeGreaterThan(15)
    for (const sample of examples.invalid) {
      const payload = expandGenerated(sample)
      const problems = validateInstance(defs, defs[sample.type]!, payload, sample.name)
      const semanticRejected = sample.semanticRule
        ? SEMANTIC_RULES[sample.semanticRule]!(payload)
        : false
      expect(
        problems.length > 0 || semanticRejected,
        `invalid 夹具 ${sample.name} 必须被 schema 或语义规则拒绝`,
      ).toBe(true)
    }
  })

  it('tc105a_02_03 代码生成 PCM 帧符合 K07 帧规则', () => {
    const pcm = examples.wsSamples.pcmFrame as Record<string, number>
    const frame = (samples: number): number => 8 + samples * 2
    // 320 样本正弦波（默认 20ms）——由代码合成
    const sine = Array.from({ length: 320 }, (_, n) =>
      Math.round(Math.sin((2 * Math.PI * 220 * n) / pcm.sampleRate) * 1000))
    expect(sine.length).toBe(pcm.sampleCountDefault)
    expect(frame(320)).toBeLessThanOrEqual(pcm.frameBytesMax)
    expect(frame(pcm.sampleCountMax)).toBe(pcm.frameBytesMax)
    expect(frame(pcm.sampleCountMax + 1)).toBeGreaterThan(pcm.frameBytesMax) // 1601 样本必超限
    // 尾包偶数字节约束：任意样本数×2 恒偶
    expect(frame(137) % 2).toBe(0)
  })
})

describe('TC105A-02-04 幂等跨语言样例', () => {
  const { examples } = loadContractFiles(REPO_ROOT)

  it('tc105a_02_04 canonical 摘要与 Python 独立实现互证', () => {
    for (const fixture of examples.canonicalHash) {
      const base = payloadSha256(fixture.variants[0])
      expect(base, `${fixture.name} variants[0]`).toBe(fixture.expectedSha256)
      const diffMarked = new Set((fixture.differentPairs ?? []).flat())
      for (let i = 1; i < fixture.variants.length; i += 1) {
        const hash = payloadSha256(fixture.variants[i])
        if (diffMarked.has(i)) {
          expect(hash, `${fixture.name} variants[${i}] 必须不同`).not.toBe(base)
        } else {
          expect(hash, `${fixture.name} variants[${i}] 同语义同 hash`).toBe(base)
        }
      }
    }
  })

  it('tc105a_02_04 键序无关/中文/换行/null；非法数值抛错', () => {
    const a = { b: 1, a: '中文值', nested: { y: [1, 2], x: null } }
    const b = { nested: { x: null, y: [1, 2] }, a: '中文值', b: 1 }
    expect(canonicalJson(a)).toBe(canonicalJson(b))
    expect(payloadSha256(a)).toBe(payloadSha256(b))
    expect(payloadSha256({ x: 1 })).not.toBe(payloadSha256({ x: 2 }))
    expect(canonicalJson({ s: '你好\n"world"\\😀' })).toBe(JSON.stringify({ s: '你好\n"world"\\😀' }))
    for (const bad of [1.5, Number.NaN, Number.POSITIVE_INFINITY, -0, 9007199254740992]) {
      expect(() => canonicalJson({ n: bad })).toThrow()
    }
  })

  it('tc105a_02_04 幂等载荷不含 requestId（payloadHash 只对业务字段）', () => {
    const idem = examples.idempotency as Record<string, Record<string, unknown>>
    const same = idem.sameKeySamePayload!.payload as Record<string, unknown>
    expect('requestId' in same).toBe(false)
    const diff = idem.sameKeyDiffPayload!
    expect(payloadSha256(diff.payloadA)).not.toBe(payloadSha256((diff as Record<string, unknown>).payloadB))
  })
})

// ---------------------------------------------------------------------------
// 端点覆盖报告：每端点成功/缺字段/未知字段/未授权四种数据样例（机械合成+逐端点验证）
// ---------------------------------------------------------------------------

const VALUE_SAMPLES: Record<string, unknown> = {
  Id: '77777777-7777-4777-8777-777777777777',
  RequestId: '22222222-2222-4222-8222-222222222222',
  Rfc3339: '2026-09-21T02:00:00Z',
  ErrorCode: 'dh_invalid_input',
  Cursor: 'c1',
  VoiceId: 'preset-zh-natural-01',
  Epoch: 1, Seq: 1, Cents: 1, Millis: 1, BytesCount: 1, TokenCount: 1, Version: 1,
}
const UUID_SAMPLE = '77777777-7777-4777-8777-777777777777'
const T0 = '2026-09-21T02:00:00Z'

function synthString(schema: Schema): string {
  const pattern = typeof schema.pattern === 'string' ? new RegExp(schema.pattern) : null
  const minLen = typeof schema.minLength === 'number' ? schema.minLength : 1
  const candidates = [
    ...(UUID_SAMPLE.length >= minLen ? [UUID_SAMPLE] : []),
    ...('a'.repeat(64).length >= minLen ? ['a'.repeat(64)] : []),
    ...(T0.length >= minLen ? [T0] : []),
    'preset-x', 'x'.repeat(Math.max(minLen, 1)), '例'.repeat(Math.max(minLen, 1)),
  ]
  for (const candidate of candidates) {
    if (pattern && !pattern.test(candidate)) continue
    if (typeof schema.maxLength === 'number' && [...candidate].length > schema.maxLength) continue
    return candidate
  }
  return 'x'
}

function synth(definitions: Record<string, Schema>, schema: Schema): unknown {
  if ('$ref' in schema) {
    const name = /^#\/definitions\/(.+)$/.exec(schema.$ref as string)![1]!
    if (name in VALUE_SAMPLES) return VALUE_SAMPLES[name]
    return synth(definitions, definitions[name]!)
  }
  if ('const' in schema) return schema.const
  if ('enum' in schema) return (schema.enum as unknown[])[0]
  if ('oneOf' in schema) return synth(definitions, (schema.oneOf as Schema[])[0]!)
  const types = Array.isArray(schema.type) ? (schema.type as string[]) : [schema.type as string]
  const t = types.find((x) => x !== 'null') ?? 'null'
  switch (t) {
    case 'null': return null
    case 'boolean': return true
    case 'integer': return typeof schema.minimum === 'number' ? schema.minimum : 1
    case 'string': return synthString(schema)
    case 'array': return []
    case 'object': {
      const properties = (schema.properties ?? {}) as Record<string, Schema>
      const out: Record<string, unknown> = {}
      for (const key of (schema.required as string[] | undefined) ?? []) {
        out[key] = synth(definitions, properties[key]!)
      }
      return out
    }
    default: throw new Error(`synth: 未知 type ${t}`)
  }
}


describe('TC105C-05 控制端点矩阵（API07～26 必要项）', () => {
  // 任务书 #105C C105C-05：C 阶段交付的控制面端点在机器契约中必要项齐全（方法/路径/请求体类型）。
  const CONTROL_MATRIX: Array<[string, string, string, string | null]> = [
    ['API07', 'POST', '/preflights', 'PreflightCreateRequest'],
    ['API08', 'POST', '/sessions', 'SessionCreateRequest'],
    ['API10', 'GET', '/sessions/{id}', null],
    ['API11', 'POST', '/sessions/{id}/pause', 'SessionPauseRequest'],
    ['API12', 'POST', '/sessions/{id}/resume', 'SessionResumeRequest'],
    ['API13', 'POST', '/sessions/{id}/heartbeat', 'SessionHeartbeatRequest'],
    ['API14', 'POST', '/sessions/{id}/end', 'SessionEndRequest'],
    ['API18', 'GET', '/sessions/{id}/events', null],
    ['API22', 'PATCH', '/sessions/{id}/transcript-preference', 'TranscriptPreferenceRequest'],
    ['API23', 'POST', '/sessions/{id}/transcript-save', 'TranscriptSaveRequest'],
    ['API24', 'GET', '/sessions/{id}/transcript', null],
    ['API25', 'GET', '/sessions/{id}/transcript/export', null],
    ['API26', 'DELETE', '/sessions/{id}/transcript', 'RequestOnly'],
  ]

  it('tc105c_05 控制端点必要项与契约一致（方法/路径/请求体）', () => {
    const { contract } = loadContractFiles(REPO_ROOT)
    for (const [id, method, path, requestType] of CONTROL_MATRIX) {
      const endpoint = contract.endpoints.find((e) => e.id === id)
      expect(endpoint, `契约缺少 ${id}`).toBeDefined()
      expect(endpoint!.method).toBe(method)
      expect(endpoint!.path.endsWith(path), `${id} 路径应以 ${path} 结尾`).toBe(true)
      if (requestType != null) {
        expect(endpoint!.requestType, `${id} 请求体应为 ${requestType}`).toBe(requestType)
      }
    }
  })
})

describe('端点覆盖报告（tc105a_02_01 附带产出）', () => {
  it('tc105a_02_01 每端点四种数据样例合成并验证后写入报告', () => {
    const { contract } = loadContractFiles(REPO_ROOT)
    const defs = contract.definitions
    const coverage = contract.endpoints.map((endpoint) => {
      const schema = endpoint.requestType ? defs[endpoint.requestType]! : null
      const success = schema ? synth(defs, schema) : null
      const results: Record<string, string> = {}
      if (schema && success && typeof success === 'object') {
        const record = { ...(success as Record<string, unknown>) }
        const firstRequired = ((schema.required as string[] | undefined) ?? [])[0]
        const missing = { ...record }
        delete missing[firstRequired!]
        const unknown = { ...record, orgId: 'o-1' }
        results.successOk = validateInstance(defs, schema, success, endpoint.id).length === 0 ? 'PASS' : 'FAIL'
        results.missingField = validateInstance(defs, schema, missing, endpoint.id).length > 0
          ? `PASS(${firstRequired})` : 'FAIL'
        results.unknownField = validateInstance(defs, schema, unknown, endpoint.id).some((e) => e.includes('orgId'))
          ? 'PASS(orgId)' : 'FAIL'
      } else {
        results.successOk = 'n/a(no request body)'
        results.missingField = 'n/a'
        results.unknownField = 'n/a'
      }
      const envelope = { success: false, error: '未登录', code: 'dh_auth_required' }
      results.unauthorized = validateInstance(defs, defs.ErrorEnvelope!, envelope, '401').length === 0
        ? 'PASS(401 envelope)' : 'FAIL'
      expect(results.successOk.startsWith('PASS') || results.successOk.startsWith('n/a')).toBe(true)
      expect(results.missingField.startsWith('PASS') || results.missingField === 'n/a').toBe(true)
      expect(results.unknownField.startsWith('PASS') || results.unknownField === 'n/a').toBe(true)
      expect(results.unauthorized.startsWith('PASS')).toBe(true)
      return { id: endpoint.id, space: endpoint.space, method: endpoint.method, path: endpoint.path,
               ownerStage: endpoint.ownerStage, requestType: endpoint.requestType, samples: results }
    })
    expect(coverage.length).toBe(59)
    mkdirSync(REPORT_DIR, { recursive: true })
    const reportPath = path.join(REPORT_DIR, 'endpoint-coverage.json')
    writeFileSync(reportPath, JSON.stringify({
      generatedAt: T0, totalEndpoints: coverage.length, kind: 'success/missingField/unknownField/unauthorized',
      coverage,
    }, null, 2))
    expect(existsSync(reportPath)).toBe(true)
  })
})

it('C105D-06 D-stage endpoint set stays closed (API15-21/27-29/40, INTERNAL05-08/14-15)', () => {
    const { contract } = loadContractFiles(REPO_ROOT)
    const matrix: Array<[string, string, string]> = [
      ['API15', 'POST', '/connection-grants'],
      ['API16', 'POST', '/webrtc/offer'],
      ['API17', 'POST', '/media-ready'],
      ['API19', 'POST', '/turns'],
      ['API20', 'POST', '/interrupt'],
      ['API21', 'POST', '/greeting'],
      ['API27', 'POST', '/voice-previews'],
      ['API28', 'GET', '/voice-previews/{id}'],
      ['API29', 'GET', '/audio'],
      ['API40', 'POST', '/playback-reset'],
      ['INTERNAL05', 'POST', '/internal/digital-human/grants/consume'],
      ['INTERNAL06', 'POST', '/internal/digital-human/events'],
      ['INTERNAL07', 'POST', '/internal/digital-human/invocations'],
      ['INTERNAL08', 'POST', '/invocations/{id}/execute'],
    ]
    for (const [id, method, suffix] of matrix) {
      const endpoint = contract.endpoints.find((e) => e.id === id)
      expect(endpoint, `契约缺少 ${id}`).toBeDefined()
      expect(endpoint!.method, `${id} 方法`).toBe(method)
      expect(endpoint!.path.endsWith(suffix), `${id} 路径应以 ${suffix} 结尾`).toBe(true)
    }
    // K14.5：INTERNAL14/15 是 D 阶段新增扩展，机器契约升级属 A 阶段文件（非本书白名单）——
    // 此处显式断言其「待升级」状态，防误删也防提前宣称已入契约。
    expect(contract.endpoints.find((e) => e.id === 'INTERNAL14')).toBeUndefined()
    expect(contract.endpoints.find((e) => e.id === 'INTERNAL15')).toBeUndefined()
    expect(contract.endpoints).toHaveLength(59)
  })

// ---------------------------------------------------------------------------
// 任务书 105-fix-2 C-01（TC-C01-004 机器契约半边）：dh_session_queued 新错误码在
// errors 注册表 / ErrorCode 枚举 / API16 错误集三处同步；合成样例真实校验；
// 删码负例必须被检查器点名。端点数量不变（本任务不新增端点）。
// ---------------------------------------------------------------------------

const QUEUED_CODE = 'dh_session_queued'

describe('TC105F2-C01-004 排队错误机器契约', () => {
  it('dh_session_queued 同步登记于 errors/ErrorCode/API16 且端点数量不变', () => {
    const { contract } = loadContractFiles(REPO_ROOT)
    expect(contract.endpoints, '本任务不新增端点').toHaveLength(59)
    const entry = contract.errors.find((e) => e.code === QUEUED_CODE)
    expect(entry, 'errors 注册表缺 dh_session_queued').toBeDefined()
    expect(entry!.http).toBe(409)
    expect(typeof entry!.action).toBe('string')
    expect((contract.definitions.ErrorCode as { enum: string[] }).enum,
      'ErrorCode 枚举缺 dh_session_queued').toContain(QUEUED_CODE)
    const api16 = contract.endpoints.find((e) => e.id === 'API16')!
    expect(api16.errors, 'API16 错误集缺 dh_session_queued').toContain(QUEUED_CODE)
  })

  it('排队错误合法样例过 schema；未知排队码样例被拒绝', () => {
    const { contract, examples } = loadContractFiles(REPO_ROOT)
    const envelope = contract.definitions.ErrorEnvelope!
    const validSample = examples.valid.find((v) => v.name === 'error-envelope-session-queued')
    expect(validSample, '缺 error-envelope-session-queued 合法样例').toBeDefined()
    expect(validateInstance(contract.definitions, envelope, validSample!.payload, 'queued-valid')).toEqual([])
    expect((validSample!.payload as { code: string }).code).toBe(QUEUED_CODE)

    const invalidSample = examples.invalid.find((v) => v.name === 'error-envelope-unknown-queued-code')
    expect(invalidSample, '缺 error-envelope-unknown-queued-code 非法样例').toBeDefined()
    const problems = validateInstance(contract.definitions, envelope, invalidSample!.payload, 'queued-invalid')
    expect(problems.length, '枚举外排队码必须被 schema 拒绝').toBeGreaterThan(0)
  })

  it.each([
    // 删 errors 注册表条目：API16 错误集仍引用 → 检查器点名该码。
    ['delete-errors-registry', /dh_session_queued/],
    // 删 ErrorCode 枚举值：合法样例不再通过校验 → 检查器点名该码。
    ['delete-errorcode-enum', /dh_session_queued/],
  ])('tc105f2_c01_04 删码负例 %s 检查器失败并点名', (mutation, marker) => {
    const fixture = makeFixtureRoot()
    const contractPath = path.join(fixture, 'contracts/digital-human.v1.json')
    const contract = JSON.parse(readFileSync(contractPath, 'utf8')) as Contract
    if (mutation === 'delete-errors-registry') {
      contract.errors = contract.errors.filter((e) => e.code !== QUEUED_CODE)
    } else {
      const def = contract.definitions.ErrorCode! as { enum: string[] }
      def.enum = def.enum.filter((c) => c !== QUEUED_CODE)
    }
    writeFileSync(contractPath, JSON.stringify(contract))

    const { errors } = checkDigitalHumanContracts(fixture)
    expect(errors.length, '删码后检查器必须失败').toBeGreaterThan(0)
    expect(errors.join('\n')).toMatch(marker)
    rmSync(fixture, { recursive: true, force: true })
  })
})

// ---------------------------------------------------------------------------
// 任务书 105-fix-2 C-03（TC-C03-006 机器契约半边 / W23）：API12 queued 同 owner
// 显式接管说明与错误集保持（不新增错误码、不新增端点）；queued Session 合成样例
// 真实校验（RULE-005：接管成功 state 仍 queued），非法状态样例被 schema 拒绝。
// ---------------------------------------------------------------------------

describe('TC105F2-C03-006 queued resume 机器契约', () => {
  it('API12 说明 queued 显式接管语义且错误集保持既有集合', () => {
    const { contract } = loadContractFiles(REPO_ROOT)
    const api12 = contract.endpoints.find((e) => e.id === 'API12')!
    expect(api12.path).toBe('/api/digital-human/sessions/{id}/resume')
    expect(api12.note, 'API12 缺 105-fix-2 queued 接管说明').toContain('105-fix-2')
    expect(api12.note).toContain('queued')
    expect(api12.note).toContain('RULE-005')
    // 错误集不变：本任务不为 resume 新增错误码（preparing 冲突沿用 dh_state_conflict）。
    expect(api12.errors).toContain('dh_state_conflict')
    expect(api12.errors).toContain('dh_takeover_required')
    expect(api12.errors).toContain('dh_lease_stale')
    expect(new Set(api12.errors).size).toBe(api12.errors.length)
    // SessionState 原枚举保持（不重写状态机）。
    const states = (contract.definitions.SessionState as { enum: string[] }).enum
    expect(states).toEqual(['preparing', 'queued', 'connecting', 'ready', 'listening', 'responding',
      'paused', 'reconnecting', 'ending', 'ended', 'failed'])
  })

  it('queued Session 合法样例过 schema；非枚举状态样例被拒绝', () => {
    const { contract, examples } = loadContractFiles(REPO_ROOT)
    const session = contract.definitions.Session!
    const validSample = examples.valid.find((v) => v.name === 'session-queued')
    expect(validSample, '缺 session-queued 合法样例').toBeDefined()
    expect(validateInstance(contract.definitions, session, validSample!.payload, 'session-queued')).toEqual([])
    expect((validSample!.payload as { state: string }).state).toBe('queued')
    expect((validSample!.payload as { leaseEpoch: number }).leaseEpoch).toBe(2)

    const invalidSample = examples.invalid.find((v) => v.name === 'session-queued-state-non-enum')
    expect(invalidSample, '缺 session-queued-state-non-enum 非法样例').toBeDefined()
    const problems = validateInstance(contract.definitions, session, invalidSample!.payload, 'session-bad')
    expect(problems.length, '枚举外 state 必须被 schema 拒绝').toBeGreaterThan(0)
  })
})
