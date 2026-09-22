// !/usr/bin/env npx tsx
/**
 * 任务书 #105A-02：数字人工作台共享契约机检门禁（V105A-02-02）。
 *
 * 用法：npx --no-install tsx scripts/quality/check-digital-human-contracts.ts [repoRoot]
 *   默认 repoRoot=process.cwd()；测试可指向临时 fixture 根（合成删改负例）。
 *   读取 contracts/digital-human.v1.json 与 contracts/digital-human.v1.examples.json。
 *
 * 校验（纯读取、零修改、不联网）：
 * - version 固定 1；envelopes/rates 结构存在。
 * - 端点三空间完整性：public=API01..API40、internal=INTERNAL01..13、admin=ADMIN01..06
 *   精确齐全（不以非空数组判完整）；id 唯一；method/path 前缀/错误码/阶段/DTO $ref 闭合。
 * - definitions 仅允许本合同实现的关键字集合（$ref/type/required/properties/
 *   additionalProperties/enum/const/oneOf/items/minimum/maximum/minLength/maxLength/
 *   pattern/format/description/x-open）；遇未支持关键字报错——不声称完整实现 Draft 2020-12。
 *   对象类型必须 additionalProperties=false；显式 x-open 的开放 payload 例外（须带说明）。
 *   禁用 type:number（本合同金额/序号一律安全整数）。
 * - 错误码表/事件表/defaults 值域（default∈[min,max]）。
 * - examples：valid 全部通过校验、invalid（含 generated 展开）全部被拒绝、canonical hash
 *   以本实现重算并与夹具期望值一致（期望值由 Python 独立实现计算，形成跨语言互证）、
 *   幂等/WS/账号夹具结构齐全。
 * 退出码：任何违规 1 并逐条列出；通过输出计数摘要。
 */

import { existsSync, readFileSync } from 'node:fs'
import { createHash } from 'node:crypto'
import path from 'node:path'

export interface CheckResult {
  errors: string[]
  endpointCount: number
  exampleCount: number
}

export interface Contract {
  version: number
  envelopes: Record<string, unknown>
  rates: Record<string, unknown>
  defaults: Record<string, { default: number; min: number; max: number; unit: string; note?: string }>
  errors: Array<{ code: string; http: number; action: string }>
  events: Array<{ type: string; payloadType: string; source: string; durable: boolean; note?: string }>
  definitions: Record<string, Record<string, unknown>>
  endpoints: Array<{
    id: string
    space: 'public' | 'internal' | 'admin'
    method: string
    path: string
    requestType: string | null
    responseType: string | null
    errors: string[]
    ownerStage: string
    note?: string
  }>
  canonicalJson?: Record<string, unknown>
}

export interface GeneratedSpec {
  kind: string
  field?: string
  length?: number
  char?: string
  base?: Record<string, unknown>
}

export interface ExamplesFile {
  version: number
  accounts: Record<string, { accountId: string; note?: string }>
  fixedUtcClock: string
  valid: Array<{ name: string; type: string; payload: unknown }>
  invalid: Array<{ name: string; type: string; payload?: unknown; generated?: GeneratedSpec; semanticRule?: string; why: string }>
  idempotency: Record<string, unknown>
  wsSamples: Record<string, unknown>
  canonicalHash: Array<{ name: string; variants: unknown[]; expectedSha256: string; differentPairs?: number[][]; note?: string }>
}

/**
 * 跨字段语义规则（K01/K08 业务约束，超出受检 schema 关键字子集，显式机检、不伪装成 schema 能力）。
 * 每条返回 true = 拒绝该载荷。
 */
export const SEMANTIC_RULES: Record<string, (payload: unknown) => boolean> = {
  'usage-confirmed-requires-units': (payload) => {
    if (typeof payload !== 'object' || payload === null) return true
    const p = payload as Record<string, unknown>
    if (p.quality !== 'confirmed') return false
    const units = ['inputTokens', 'outputTokens', 'audioInputMs', 'audioOutputMs', 'textCodePoints', 'renderMs']
    const values = units.map((k) => p[k])
    // 不适用阶段允许 null；但全 null（无任何实量）或无一项 >0（全 0 冒充）不得标 confirmed
    if (values.every((v) => v === null || v === undefined)) return true
    return !values.some((v) => typeof v === 'number' && v > 0)
  },
  'non-blank-text': (payload) => {
    if (typeof payload !== 'object' || payload === null) return true
    const text = (payload as Record<string, unknown>).text
    return typeof text !== 'string' || text.trim().length === 0
  },
}

const MAX_SAFE = 9007199254740991
const ALLOWED_KEYWORDS = new Set([
  '$ref', 'type', 'required', 'properties', 'additionalProperties', 'enum', 'const',
  'oneOf', 'items', 'minimum', 'maximum', 'minLength', 'maxLength', 'pattern', 'format',
  'description', 'x-open',
])
const ALLOWED_TYPES = new Set(['string', 'integer', 'boolean', 'array', 'object', 'null'])
const ALLOWED_FORMATS = new Set(['uuid', 'date-time'])
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const RFC3339_RE = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$/

export const EXPECTED_PUBLIC_IDS = Array.from({ length: 40 }, (_, i) => `API${String(i + 1).padStart(2, '0')}`)
export const EXPECTED_INTERNAL_IDS = Array.from({ length: 13 }, (_, i) => `INTERNAL${String(i + 1).padStart(2, '0')}`)
export const EXPECTED_ADMIN_IDS = Array.from({ length: 6 }, (_, i) => `ADMIN${String(i + 1).padStart(2, '0')}`)

// ---------------------------------------------------------------------------
// 文件加载与负例 generated 展开（测试复用）
// ---------------------------------------------------------------------------

export function loadContractFiles(repoRoot: string): { contract: Contract; examples: ExamplesFile } {
  const contractPath = path.join(repoRoot, 'contracts', 'digital-human.v1.json')
  const examplesPath = path.join(repoRoot, 'contracts', 'digital-human.v1.examples.json')
  if (!existsSync(contractPath)) throw new Error(`contract 不存在：${contractPath}`)
  if (!existsSync(examplesPath)) throw new Error(`examples 不存在：${examplesPath}`)
  return {
    contract: JSON.parse(readFileSync(contractPath, 'utf8')) as Contract,
    examples: JSON.parse(readFileSync(examplesPath, 'utf8')) as ExamplesFile,
  }
}

export function expandGenerated(entry: { payload?: unknown; generated?: GeneratedSpec }): unknown {
  if (!entry.generated) return entry.payload
  const { field, length, char = 'x', base = {} } = entry.generated
  if (!field || length === undefined) throw new Error(`generated 反例缺少 field/length: ${JSON.stringify(entry.generated)}`)
  return { ...base, [field]: char.repeat(length) }
}

// ---------------------------------------------------------------------------
// canonical JSON（K13.6；与 Python 侧独立实现互证）
// ---------------------------------------------------------------------------

export function canonicalJson(value: unknown): string {
  if (value === null) return 'null'
  if (typeof value === 'boolean') return value ? 'true' : 'false'
  if (typeof value === 'number') {
    if (!Number.isInteger(value) || Object.is(value, -0) || Math.abs(value) > MAX_SAFE) {
      throw new Error(`canonical JSON 只接受安全整数，收到 ${String(value)}`)
    }
    return String(value)
  }
  if (typeof value === 'string') return JSON.stringify(value)
  if (Array.isArray(value)) return `[${value.map((item) => canonicalJson(item)).join(',')}]`
  if (typeof value === 'object') {
    const record = value as Record<string, unknown>
    const keys = Object.keys(record).sort((a, b) => {
      const ca = [...a]
      const cb = [...b]
      for (let i = 0; i < Math.min(ca.length, cb.length); i += 1) {
        const x = ca[i]!.codePointAt(0)!
        const y = cb[i]!.codePointAt(0)!
        if (x !== y) return x - y
      }
      return ca.length - cb.length
    })
    return `{${keys.map((k) => `${JSON.stringify(k)}:${canonicalJson(record[k])}`).join(',')}}`
  }
  throw new Error(`canonical JSON 不支持的类型：${typeof value}`)
}

export function payloadSha256(value: unknown): string {
  return createHash('sha256').update(canonicalJson(value), 'utf8').digest('hex')
}

// ---------------------------------------------------------------------------
// 受限 JSON Schema 子集校验器（不声称完整 Draft 2020-12）
// ---------------------------------------------------------------------------

type Schema = Record<string, unknown>

function resolveRef(definitions: Record<string, Schema>, ref: string): { name: string; schema: Schema } {
  const m = /^#\/definitions\/(.+)$/.exec(ref)
  if (!m) throw new Error(`不支持的 $ref 形式：${ref}`)
  const name = m[1]!
  const schema = definitions[name]
  if (!schema) throw new Error(`$ref 目标不存在：${ref}`)
  return { name, schema }
}

function codePointLength(value: string): number {
  return [...value].length
}

export function validateInstance(
  definitions: Record<string, Schema>,
  schema: Schema,
  value: unknown,
  trail: string,
): string[] {
  const errors: string[] = []
  if ('$ref' in schema) {
    const { schema: target } = resolveRef(definitions, schema.$ref as string)
    return validateInstance(definitions, target, value, trail)
  }
  if ('const' in schema) {
    if (JSON.stringify(value) !== JSON.stringify(schema.const)) {
      errors.push(`${trail}: 必须恒等于 ${JSON.stringify(schema.const)}，实际 ${JSON.stringify(value)}`)
    }
    return errors
  }
  if ('enum' in schema) {
    const options = schema.enum as unknown[]
    if (!options.some((option) => JSON.stringify(option) === JSON.stringify(value))) {
      errors.push(`${trail}: 不在枚举 ${JSON.stringify(options)} 内，实际 ${JSON.stringify(value)}`)
    }
    return errors
  }
  if ('oneOf' in schema) {
    const branches = schema.oneOf as Schema[]
    const matches = branches.filter((branch) => validateInstance(definitions, branch, value, trail).length === 0)
    if (matches.length !== 1) {
      errors.push(`${trail}: oneOf 命中 ${matches.length} 个分支（必须恰好 1）`)
    }
    return errors
  }
  const typeRaw = schema.type
  const types = Array.isArray(typeRaw) ? (typeRaw as string[]) : typeof typeRaw === 'string' ? [typeRaw] : []
  const typeOk = types.length === 0 || types.some((t) => {
    switch (t) {
      case 'string': return typeof value === 'string'
      case 'integer': return typeof value === 'number' && Number.isInteger(value) && !Object.is(value, -0)
      case 'boolean': return typeof value === 'boolean'
      case 'null': return value === null
      case 'array': return Array.isArray(value)
      case 'object': return typeof value === 'object' && value !== null && !Array.isArray(value)
      default: return false
    }
  })
  if (!typeOk) {
    errors.push(`${trail}: 类型应为 ${JSON.stringify(types)}，实际 ${JSON.stringify(value)}`)
    return errors
  }
  if (typeof value === 'number') {
    if (typeof schema.minimum === 'number' && value < schema.minimum) {
      errors.push(`${trail}: ${value} < minimum ${schema.minimum}`)
    }
    if (typeof schema.maximum === 'number' && value > schema.maximum) {
      errors.push(`${trail}: ${value} > maximum ${schema.maximum}`)
    }
  }
  if (typeof value === 'string') {
    if (typeof schema.minLength === 'number' && codePointLength(value) < schema.minLength) {
      errors.push(`${trail}: 码点长度 ${codePointLength(value)} < minLength ${schema.minLength}`)
    }
    if (typeof schema.maxLength === 'number' && codePointLength(value) > schema.maxLength) {
      errors.push(`${trail}: 码点长度 ${codePointLength(value)} > maxLength ${schema.maxLength}`)
    }
    if (typeof schema.pattern === 'string' && !new RegExp(schema.pattern).test(value)) {
      errors.push(`${trail}: 不匹配 pattern ${schema.pattern}`)
    }
    if (typeof schema.format === 'string') {
      if (schema.format === 'uuid' && !UUID_RE.test(value)) errors.push(`${trail}: 不是小写标准 UUID`)
      if (schema.format === 'date-time' && !RFC3339_RE.test(value)) errors.push(`${trail}: 不是 RFC3339 UTC 时间`)
    }
  }
  if (Array.isArray(value) && 'items' in schema) {
    value.forEach((item, index) => {
      errors.push(...validateInstance(definitions, schema.items as Schema, item, `${trail}[${index}]`))
    })
  }
  if (typeof value === 'object' && value !== null && !Array.isArray(value)) {
    const record = value as Record<string, unknown>
    const properties = (schema.properties ?? {}) as Record<string, Schema>
    for (const key of (schema.required as string[] | undefined) ?? []) {
      if (!(key in record)) errors.push(`${trail}: 缺必填字段 ${key}`)
    }
    for (const key of Object.keys(record)) {
      if (!(key in properties)) {
        if (schema.additionalProperties === false) {
          errors.push(`${trail}: 未声明字段 ${key}（additionalProperties=false）`)
        }
        continue
      }
      errors.push(...validateInstance(definitions, properties[key]!, record[key], `${trail}.${key}`))
    }
  }
  return errors
}

// ---------------------------------------------------------------------------
// definitions 结构巡检（关键字白名单 / 闭合性）
// ---------------------------------------------------------------------------

function walkSchemaKeywords(definitions: Record<string, Schema>, schema: Schema, defName: string, trail: string, errors: string[]): void {
  for (const key of Object.keys(schema)) {
    if (!ALLOWED_KEYWORDS.has(key)) {
      errors.push(`definitions.${defName}${trail}: 未支持关键字 ${key}（本检查器只实现合同列出的子集）`)
    }
  }
  if (typeof schema.format === 'string' && !ALLOWED_FORMATS.has(schema.format)) {
    errors.push(`definitions.${defName}${trail}: 未支持 format ${schema.format}`)
  }
  const typeRaw = schema.type
  const types = Array.isArray(typeRaw) ? (typeRaw as string[]) : typeof typeRaw === 'string' ? [typeRaw] : []
  for (const t of types) {
    if (t === 'number') errors.push(`definitions.${defName}${trail}: 禁用 type:number（金额/序号一律安全整数）`)
    else if (!ALLOWED_TYPES.has(t)) errors.push(`definitions.${defName}${trail}: 未知 type ${t}`)
  }
  const isObject = types.includes('object')
  if (isObject && schema.additionalProperties !== false && schema['x-open'] !== true) {
    errors.push(`definitions.${defName}${trail}: 对象必须 additionalProperties=false（开放 payload 需显式 x-open 且带说明）`)
  }
  if (schema['x-open'] === true && typeof schema.description !== 'string') {
    errors.push(`definitions.${defName}${trail}: x-open 必须带 description 说明开放范围`)
  }
  if ('$ref' in schema) {
    try {
      resolveRef(definitions, schema.$ref as string)
    } catch (error) {
      errors.push(`definitions.${defName}${trail}: ${(error as Error).message}`)
    }
    return
  }
  if ('oneOf' in schema) {
    const branches = schema.oneOf as Schema[]
    if (!Array.isArray(branches) || branches.length < 2) {
      errors.push(`definitions.${defName}${trail}: oneOf 至少 2 个分支`)
    } else {
      branches.forEach((branch, index) => walkSchemaKeywords(definitions, branch, defName, `${trail}.oneOf[${index}]`, errors))
    }
    return
  }
  if (isObject) {
    const properties = (schema.properties ?? {}) as Record<string, Schema>
    for (const key of (schema.required as string[] | undefined) ?? []) {
      if (!(key in properties)) errors.push(`definitions.${defName}${trail}: required 字段 ${key} 无 properties 声明`)
    }
    for (const [key, sub] of Object.entries(properties)) {
      walkSchemaKeywords(definitions, sub, defName, `${trail}.properties.${key}`, errors)
    }
  }
  if ('items' in schema && schema.items && typeof schema.items === 'object') {
    walkSchemaKeywords(definitions, schema.items as Schema, defName, `${trail}.items`, errors)
  }
}

// ---------------------------------------------------------------------------
// 主检查
// ---------------------------------------------------------------------------

export function checkDigitalHumanContracts(repoRoot: string): CheckResult {
  const errors: string[] = []
  let contract: Contract
  let examples: ExamplesFile
  try {
    ;({ contract, examples } = loadContractFiles(repoRoot))
  } catch (error) {
    return { errors: [(error as Error).message], endpointCount: 0, exampleCount: 0 }
  }

  if (contract.version !== 1) errors.push(`contract.version 必须固定 1，实际 ${contract.version}`)
  if (examples.version !== 1) errors.push(`examples.version 必须固定 1，实际 ${examples.version}`)

  // 错误码表
  const errorCodes = new Set<string>()
  for (const err of contract.errors ?? []) {
    if (errorCodes.has(err.code)) errors.push(`errors: 重复错误码 ${err.code}`)
    errorCodes.add(err.code)
    if (!Number.isInteger(err.http) || err.http < 400 || err.http > 599) {
      errors.push(`errors.${err.code}: http ${err.http} 不在 4xx/5xx`)
    }
    if (typeof err.action !== 'string' || !err.action) errors.push(`errors.${err.code}: action 必填`)
  }

  // defaults 值域
  for (const [key, d] of Object.entries(contract.defaults ?? {})) {
    if (![d.default, d.min, d.max].every((v) => Number.isInteger(v))) {
      errors.push(`defaults.${key}: default/min/max 必须整数`)
      continue
    }
    if (d.min > d.max || d.default < d.min || d.default > d.max) {
      errors.push(`defaults.${key}: default=${d.default} 不在 [${d.min},${d.max}]`)
    }
    if (typeof d.unit !== 'string' || !d.unit) errors.push(`defaults.${key}: unit 必填`)
  }

  // events
  const seenEventTypes = new Set<string>()
  for (const ev of contract.events ?? []) {
    if (seenEventTypes.has(ev.type)) errors.push(`events: 重复 type ${ev.type}`)
    seenEventTypes.add(ev.type)
    if (!(ev.payloadType in (contract.definitions ?? {}))) {
      errors.push(`events.${ev.type}: payloadType ${ev.payloadType} 不存在于 definitions`)
    }
    if (!['java', 'runtime'].includes(ev.source)) errors.push(`events.${ev.type}: source 必须是 java|runtime`)
    if (typeof ev.durable !== 'boolean') errors.push(`events.${ev.type}: durable 必须布尔`)
  }

  // definitions 巡检
  for (const [name, schema] of Object.entries(contract.definitions ?? {})) {
    walkSchemaKeywords(contract.definitions!, schema, name, '', errors)
  }

  // endpoints：结构 + 三空间完整清单（不以非空数组判完整）
  const endpoints = contract.endpoints ?? []
  const byId = new Map(endpoints.map((e) => [e.id, e]))
  if (endpoints.length !== new Set(endpoints.map((e) => e.id)).size) {
    const seen = new Set<string>()
    for (const e of endpoints) {
      if (seen.has(e.id)) errors.push(`endpoints: 重复 id ${e.id}`)
      seen.add(e.id)
    }
  }
  const expect: Array<[string[], string]> = [
    [EXPECTED_PUBLIC_IDS, 'public'],
    [EXPECTED_INTERNAL_IDS, 'internal'],
    [EXPECTED_ADMIN_IDS, 'admin'],
  ]
  for (const [ids, space] of expect) {
    for (const id of ids) {
      if (!byId.has(id)) errors.push(`endpoints: 缺失端点 ${id}（${space} 空间必须精确齐全）`)
    }
  }
  const validIds = new Set([...EXPECTED_PUBLIC_IDS, ...EXPECTED_INTERNAL_IDS, ...EXPECTED_ADMIN_IDS])
  for (const e of endpoints) {
    if (!validIds.has(e.id)) errors.push(`endpoints: 未定义的端点 id ${e.id}`)
    const spaceOf = e.id.startsWith('API') ? 'public' : e.id.startsWith('INTERNAL') ? 'internal' : 'admin'
    if (e.space !== spaceOf) errors.push(`endpoints.${e.id}: space ${e.space} 与 id 前缀不符`)
    if (!['GET', 'POST', 'PATCH', 'PUT', 'DELETE'].includes(e.method)) {
      errors.push(`endpoints.${e.id}: 非法 method ${e.method}`)
    }
    const prefixOk = spaceOf === 'public' ? e.path.startsWith('/api/digital-human')
      : spaceOf === 'admin' ? e.path.startsWith('/api/admin/digital-human')
        : e.path.startsWith('/internal/')
    if (!prefixOk) errors.push(`endpoints.${e.id}: path ${e.path} 前缀与空间不符`)
    if (!/^[A-H](\/[A-H])*$/.test(e.ownerStage)) errors.push(`endpoints.${e.id}: ownerStage ${e.ownerStage} 非法`)
    for (const rt of [e.requestType, e.responseType]) {
      if (rt !== null && !(rt in (contract.definitions ?? {}))) {
        errors.push(`endpoints.${e.id}: 类型引用 ${rt} 不存在于 definitions`)
      }
    }
    if (!Array.isArray(e.errors) || e.errors.length === 0) {
      errors.push(`endpoints.${e.id}: errors 不能为空`)
    } else {
      for (const code of e.errors) {
        if (!errorCodes.has(code)) errors.push(`endpoints.${e.id}: 错误码 ${code} 不在错误码表`)
      }
    }
  }

  // examples：valid 必过 / invalid 必拒
  for (const sample of examples.valid ?? []) {
    const schema = contract.definitions?.[sample.type]
    if (!schema) {
      errors.push(`examples.valid.${sample.name}: 类型 ${sample.type} 不存在`)
      continue
    }
    const problems = validateInstance(contract.definitions!, schema, sample.payload, `examples.valid.${sample.name}`)
    if (problems.length > 0) errors.push(`valid 样例被拒绝 ${sample.name}: ${problems[0]}`)
  }
  for (const sample of examples.invalid ?? []) {
    const schema = contract.definitions?.[sample.type]
    if (!schema) {
      errors.push(`examples.invalid.${sample.name}: 类型 ${sample.type} 不存在`)
      continue
    }
    let payload: unknown
    try {
      payload = expandGenerated(sample)
    } catch (error) {
      errors.push(`invalid 样例 ${sample.name} 无法展开: ${(error as Error).message}`)
      continue
    }
    const problems = validateInstance(contract.definitions!, schema, payload, `examples.invalid.${sample.name}`)
    let rejected = problems.length > 0
    if (!rejected && sample.semanticRule) {
      const rule = SEMANTIC_RULES[sample.semanticRule]
      if (!rule) {
        errors.push(`invalid 样例 ${sample.name}: 未实现的语义规则 ${sample.semanticRule}`)
      } else {
        rejected = rule(payload)
      }
    }
    if (!rejected) errors.push(`invalid 样例被接受 ${sample.name}（必须被 schema 或声明的语义规则拒绝）`)
  }

  // 账号/时钟/幂等/WS 结构
  for (const key of ['A', 'B']) {
    const account = examples.accounts?.[key]
    if (!account || !UUID_RE.test(account.accountId)) {
      errors.push(`examples.accounts.${key}: 缺失或 accountId 非合法 UUID`)
    }
  }
  if (!RFC3339_RE.test(examples.fixedUtcClock ?? '')) errors.push('examples.fixedUtcClock 非 RFC3339 UTC')
  for (const key of ['sameKeySamePayload', 'sameKeyDiffPayload', 'staleLeaseEpoch', 'mediaEpochOutOfOrder']) {
    if (!(key in (examples.idempotency ?? {}))) errors.push(`examples.idempotency 缺 ${key}`)
  }
  const pcm = (examples.wsSamples?.pcmFrame ?? {}) as Record<string, unknown>
  if (pcm.generate !== 'code' || pcm.sampleCountMax !== 1600 || pcm.frameBytesMax !== 3208) {
    errors.push('examples.wsSamples.pcmFrame 帧规则缺失（generate=code/sampleCountMax=1600/frameBytesMax=3208）')
  }

  // canonical hash：重算并与期望一致（期望值由 Python 独立实现写入，跨语言互证）
  for (const fixture of examples.canonicalHash ?? []) {
    const hashes = fixture.variants.map((v) => {
      try {
        return payloadSha256(v)
      } catch (error) {
        errors.push(`canonicalHash.${fixture.name}: ${(error as Error).message}`)
        return null
      }
    })
    const base = hashes[0] ?? null
    if (base === null || base !== fixture.expectedSha256) {
      errors.push(`canonicalHash.${fixture.name}: variants[0] 重算摘要与期望不一致（Python/TS 实现漂移）`)
    }
    // differentPairs 标记的变体必须彼此不同；未标记的变体必须与期望一致
    const diffMarked = new Set((fixture.differentPairs ?? []).flat())
    for (let i = 1; i < hashes.length; i += 1) {
      if (diffMarked.has(i)) {
        if (hashes[i] !== null && hashes[i] === base) {
          errors.push(`canonicalHash.${fixture.name}: variants[${i}] 与 [0] 摘要必须不同`)
        }
      } else if (hashes[i] !== base) {
        errors.push(`canonicalHash.${fixture.name}: variants[${i}] 重算摘要与期望不一致（Python/TS 实现漂移）`)
      }
    }
  }

  return {
    errors,
    endpointCount: endpoints.length,
    exampleCount: (examples.valid?.length ?? 0) + (examples.invalid?.length ?? 0),
  }
}

// ---------------------------------------------------------------------------
// CLI
// ---------------------------------------------------------------------------

function main(argv: string[]): number {
  const repoRoot = argv[0] ?? process.cwd()
  const { errors, endpointCount, exampleCount } = checkDigitalHumanContracts(repoRoot)
  if (errors.length > 0) {
    console.error(`check-digital-human-contracts: ${errors.length} 处违规`)
    for (const e of errors) console.error(`  - ${e}`)
    return 1
  }
  console.log(`check-digital-human-contracts: OK (endpoints=${endpointCount}, examples=${exampleCount})`)
  return 0
}

if (process.argv[1] && process.argv[1].endsWith('check-digital-human-contracts.ts')) {
  process.exitCode = main(process.argv.slice(2))
}
