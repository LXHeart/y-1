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


describe('TC105B-04 前端类型与契约对齐', () => {
  // 任务书 #105B C105B-04：src/types/digital-human.ts 的公开 DTO 与机器契约逐字段对齐
  // （required 字段名必须出现在对应 TS interface/type 本体内；漂移即失败）。
  const TS_TYPES = readFileSync(path.join(REPO_ROOT, 'src/types/digital-human.ts'), 'utf8')

  function interfaceBody(name: string): string | null {
    const match = TS_TYPES.match(new RegExp(`export interface ${name}\\b[^{]*\\{([\\s\\S]*?)\\n\\}`))
    if (!match) return null
    // extends 链（如 Profile extends ProfileInput）：本体 + 基类字段合集（required 字段可能在基类）。
    const header = TS_TYPES.match(new RegExp(`export interface ${name}\\b([^{]*)\\{`))![1]
    const base = /extends\s+([A-Za-z0-9_]+)/.exec(header)?.[1]
    return base ? `${interfaceBody(base) ?? ''}\n${match[1]}` : match[1]
  }

  it('tc105b_04 契约 required 字段全部出现在 TS DTO 本体', () => {
    const { contract } = loadContractFiles(REPO_ROOT)
    const core = ['ProfileInput', 'Profile', 'Page', 'AvatarItem', 'VoiceItem', 'BackendItem', 'Catalog',
      'PublicCatalog', 'Preflight', 'Session', 'SessionSnapshot', 'SessionSummary', 'TurnReceipt',
      'InterruptReceipt', 'TranscriptEntry', 'Operation', 'Recording', 'Preview', 'UsageUnits']
    for (const name of core) {
      const schema = contract.definitions[name] as
        | { required?: string[]; properties?: Record<string, unknown> } | undefined
      const body = interfaceBody(name)
      expect(body, `TS 类型 ${name} 必须存在于 digital-human.ts`).not.toBeNull()
      if (!schema?.required) continue
      for (const field of schema.required) {
        expect(body!, `${name}.${field} 缺失于 TS DTO`).toMatch(new RegExp(`\\b${field}\\??\\s*:`))
      }
    }
  })

  it('tc105b_04 金额/序号上界以 JS 安全整数约束（2^53-1）', () => {
    const api = readFileSync(path.join(REPO_ROOT, 'src/composables/useDigitalHumanApi.ts'), 'utf8')
    expect(api).toContain('9007199254740991')
    expect(api).toMatch(/Number\.isSafeInteger/)
  })
})

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
