#!/usr/bin/env npx tsx
/**
 * 任务书 #104 C104-06（§3 D05）：SQL 与 Java 生产真实清单扫描器。
 *
 * 用法：
 *   npx tsx scripts/quality/lifecycle-inventory.ts [--repo-root <path>] [--java <bin>]
 *        [--baseline-candidates <path>]   # 仅首次基线采集：写候选文件供逐项人工审阅
 *
 * - SQL：扫描 database-bootstrap/src/main/resources/db/bootstrap 与五服务
 *   src/main/resources/db/migration（按迁移版本顺序），识别 CREATE TABLE（IF NOT
 *   EXISTS/引号/schema/分区/PARTITION OF）、ALTER TABLE RENAME、DROP TABLE、
 *   TEMP/UNLOGGED 变体与 DO 块内静态 DDL；EXECUTE format/拼接的动态建表报
 *   unsupported（非静默跳过）。CREATE INDEX/VIEW/FUNCTION/TYPE/TRIGGER 等不是表。
 *   同逻辑表多次幂等声明保留来源集合；规范键 schema.table（缺省 public）；
 *   service 是登记属性，不靠表名前缀猜。
 * - Java：调用 LifecycleJavaInventory.java（JDK 25 自带编译器树 API，只 parse 不
 *   编译不执行业务），抽取事件生产点/工厂链/常量与有限条件分支传播；无法静态解析
 *   的生产点输出 unresolved 并使整体非零。
 * - 不写 repo、不联网；输出确定性排序。正常 quality:lifecycle 绝不写基线文件。
 */
import { spawnSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs'
import path from 'node:path'

export interface SqlRoot {
  service: string
  dir: string
}

export interface TableSource {
  service: string
  path: string
  version: string
}

export interface RealTable {
  id: string
  sources: TableSource[]
  renamedFrom?: string[]
  droppedBy?: TableSource[]
}

export interface UnsupportedSql {
  service: string
  path: string
  detail: string
  excerpt: string
}

export interface SqlInventory {
  tables: RealTable[]
  dropped: Array<{ id: string; sources: TableSource[] }>
  unsupported: UnsupportedSql[]
}

export interface JavaSite {
  path: string
  symbol: string
  eventTypes: string[]
  viaFactory?: string
}

export interface JavaUnresolvedSite {
  path: string
  symbol: string
  detail: string
  digest?: string
}

export interface JavaInventory {
  events: Array<{ eventType: string; sites: JavaSite[] }>
  unresolved: JavaUnresolvedSite[]
}

export interface RealInventory {
  tables: RealTable[]
  dropped: Array<{ id: string; sources: TableSource[] }>
  unsupportedSql: UnsupportedSql[]
  events: JavaInventory['events']
  unresolvedJava: JavaUnresolvedSite[]
}

const SQL_ROOTS: Array<{ service: string; dir: string; versioned: boolean }> = [
  { service: 'bootstrap', dir: 'database-bootstrap/src/main/resources/db/bootstrap', versioned: false },
  { service: 'identity', dir: 'platform-java/services/identity-service/src/main/resources/db/migration', versioned: true },
  { service: 'marketplace', dir: 'platform-java/services/marketplace-service/src/main/resources/db/migration', versioned: true },
  { service: 'finance', dir: 'platform-java/services/finance-service/src/main/resources/db/migration', versioned: true },
  { service: 'trust', dir: 'platform-java/services/trust-service/src/main/resources/db/migration', versioned: true },
  { service: 'intelligence', dir: 'platform-java/services/intelligence-service/src/main/resources/db/migration', versioned: true },
]

const JAVA_ROOTS = [
  'platform-java/services/identity-service/src/main/java',
  'platform-java/services/marketplace-service/src/main/java',
  'platform-java/services/finance-service/src/main/java',
  'platform-java/services/trust-service/src/main/java',
  'platform-java/services/intelligence-service/src/main/java',
  'platform-java/platform-messaging/src/main/java',
]

// ---------- SQL 扫描 ----------

/** 去除 -- 与块注释（字符串/美元引号内的内容不动）。 */
function stripSqlComments(sql: string): string {
  let result = ''
  let index = 0
  let inSingle = false
  let dollarTag: string | null = null
  while (index < sql.length) {
    if (dollarTag) {
      if (sql.startsWith(dollarTag, index)) {
        result += dollarTag
        index += dollarTag.length
        dollarTag = null
        continue
      }
      result += sql[index]
      index += 1
      continue
    }
    const char = sql[index]
    const dollarMatch = /^\$[a-zA-Z][a-zA-Z0-9_]*\$|^\$\$/.exec(sql.slice(index))
    if (!inSingle && char === '$' && dollarMatch) {
      dollarTag = dollarMatch[0]
      result += dollarTag
      index += dollarTag.length
      continue
    }
    if (inSingle) {
      result += char
      if (char === "'") {
        if (sql[index + 1] === "'") {
          result += "'"
          index += 2
          continue
        }
        inSingle = false
      }
      index += 1
      continue
    }
    if (char === "'") {
      inSingle = true
      result += char
      index += 1
      continue
    }
    if (char === '-' && sql[index + 1] === '-') {
      while (index < sql.length && sql[index] !== '\n') index += 1
      result += '\n'
      continue
    }
    if (char === '/' && sql[index + 1] === '*') {
      const end = sql.indexOf('*/', index + 2)
      index = end === -1 ? sql.length : end + 2
      result += ' '
      continue
    }
    result += char
    index += 1
  }
  return result
}

/** 拆分顶层语句：括号/单引号/美元引号内的分号不算边界。 */
function splitStatements(sql: string): string[] {
  const statements: string[] = []
  let current = ''
  let depth = 0
  let inSingle = false
  let dollarTag: string | null = null
  let index = 0
  while (index < sql.length) {
    const char = sql[index]
    if (dollarTag) {
      current += char
      if (char === '$' && sql.startsWith(dollarTag, index)) {
        current += sql.slice(index + 1, index + dollarTag.length)
        index += dollarTag.length
        dollarTag = null
        continue
      }
      index += 1
      continue
    }
    if (inSingle) {
      current += char
      if (char === "'") {
        if (sql[index + 1] === "'") {
          current += "'"
          index += 2
          continue
        }
        inSingle = false
      }
      index += 1
      continue
    }
    if (char === '$' && (sql[index + 1] === '$' || /[a-zA-Z]/.test(sql[index + 1] ?? ''))) {
      const match = /^\$[a-zA-Z0-9_]*\$/.exec(sql.slice(index))
      if (match) {
        dollarTag = match[0]
        current += dollarTag
        index += dollarTag.length
        continue
      }
    }
    if (char === "'") {
      inSingle = true
      current += char
      index += 1
      continue
    }
    if (char === '(') depth += 1
    if (char === ')') depth = Math.max(0, depth - 1)
    if (char === ';' && depth === 0) {
      statements.push(current.trim())
      current = ''
      index += 1
      continue
    }
    current += char
    index += 1
  }
  if (current.trim() !== '') statements.push(current.trim())
  return statements.filter((statement) => statement !== '')
}

/** 规范化标识符：带引号保留原样去引号，裸标识符小写。 */
function normalizeIdentifier(raw: string): string {
  const token = raw.trim()
  if (token.startsWith('"') && token.endsWith('"') && token.length >= 2) {
    return token.slice(1, -1)
  }
  return token.toLowerCase()
}

/** 解析可能的 schema 限定名 → 规范键 schema.table（缺省 public）。 */
function canonicalTable(raw: string): string {
  const parts = raw.trim().split('.')
  if (parts.length >= 2) {
    return `${normalizeIdentifier(parts[0])}.${normalizeIdentifier(parts[1])}`
  }
  return `public.${normalizeIdentifier(parts[0])}`
}

interface TableNameMatch {
  name: string
  index: number
}

function matchName(statement: string, keywordRegex: RegExp): TableNameMatch | null {
  const match = keywordRegex.exec(statement)
  if (!match) return null
  const rest = statement.slice(match.index + match[0].length)
  const name = /^([\s]*((("[^"]+")|([A-Za-z_][\w$]*))(\.("[^"]+")|\.[A-Za-z_][\w$]*)*))/.exec(rest)
  if (!name) return null
  return { name: name[1].trim(), index: match.index }
}

const MIGRATION_VERSION = /^V(\d+)__/i

function listSqlFiles(root: SqlRoot & { versioned: boolean }): Array<{ path: string; version: string }> {
  if (!existsSync(root.dir)) return []
  const files = readdirSync(root.dir).filter((file) => file.toLowerCase().endsWith('.sql'))
  if (root.versioned) {
    return files
      .map((file) => {
        const match = MIGRATION_VERSION.exec(file)
        return { path: path.join(root.dir, file), version: match ? match[1] : '0' }
      })
      .sort((left, right) => Number(left.version) - Number(right.version) || left.path.localeCompare(right.path))
  }
  return files
    .map((file) => ({ path: path.join(root.dir, file), version: 'bootstrap' }))
    .sort((left, right) => left.path.localeCompare(right.path))
}

/** DO/函数体内：静态 CREATE TABLE 识别（去 BEGIN/END 框架锚定、剥字符串字面量防误匹配）；EXECUTE 动态建表报 unsupported。 */
function scanDollarBlock(body: string): { staticCreates: string[]; dynamicCreates: string[] } {
  const creates: string[] = []
  const dynamic: string[] = []
  for (const inner of splitStatements(body)) {
    const normalized = inner.replace(/\s+/g, ' ').trim()
    const unquoted = normalized.replace(/'(?:[^']|'')*'/g, "''")
    const name = matchName(unquoted,
      /CREATE\s+(TEMP\s+|TEMPORARY\s+|UNLOGGED\s+)?TABLE\s+(IF\s+NOT\s+EXISTS\s+)?/i)
    if (name) creates.push(name.name)
    if (/EXECUTE/i.test(normalized) && /CREATE\s+TABLE/i.test(normalized)) {
      dynamic.push(normalized.slice(0, 160))
    }
  }
  return { staticCreates: creates, dynamicCreates: dynamic }
}

export function scanSqlInventory(repoRoot: string, roots: Array<{ service: string; dir: string; versioned?: boolean }> = SQL_ROOTS): SqlInventory {
  const live = new Map<string, RealTable>()
  const dropped = new Map<string, { id: string; sources: TableSource[] }>()
  const unsupported: UnsupportedSql[] = []
  for (const root of roots) {
    for (const file of listSqlFiles({ ...root, versioned: root.versioned ?? (root.service !== 'bootstrap') })) {
      const relative = path.relative(repoRoot, file.path).split(path.sep).join('/')
      const source: TableSource = { service: root.service, path: relative, version: file.version }
      const sql = stripSqlComments(readFileSync(file.path, 'utf8'))
      for (const statement of splitStatements(sql)) {
        const normalized = statement.replace(/\s+/g, ' ').trim()
        // CREATE TABLE（含变体与分区）
        const create = matchName(normalized,
          /^CREATE\s+(GLOBAL\s+|LOCAL\s+)?(TEMP\s+|TEMPORARY\s+|UNLOGGED\s+)?TABLE\s+(IF\s+NOT\s+EXISTS\s+)?/i)
        if (create && !/CREATE\s+(OR\s+REPLACE\s+)?FUNCTION/i.test(normalized.slice(0, create.index + 40))) {
          const id = canonicalTable(create.name)
          const existing = live.get(id)
          if (existing) {
            if (!existing.sources.some((item) => item.path === relative)) existing.sources.push(source)
          } else {
            live.set(id, { id, sources: [source] })
          }
          continue
        }
        const partitionOf = /^CREATE\s+TABLE\s+(IF\s+NOT\s+EXISTS\s+)?([\w".]+)\s+PARTITION\s+OF\s+([\w".]+)/i.exec(normalized)
        if (partitionOf) {
          const id = canonicalTable(partitionOf[2])
          if (!live.has(id)) live.set(id, { id, sources: [source] })
          continue
        }
        if (/^CREATE\s+FOREIGN\s+TABLE/i.test(normalized)) {
          unsupported.push({ service: root.service, path: relative, detail: 'CREATE FOREIGN TABLE 未支持', excerpt: normalized.slice(0, 160) })
          continue
        }
        // RENAME
        const rename = /^ALTER\s+TABLE\s+(IF\s+EXISTS\s+)?([\w".]+)\s+RENAME\s+TO\s+([\w".]+)/i.exec(normalized)
        if (rename) {
          const fromId = canonicalTable(rename[2])
          const toId = canonicalTable(rename[3])
          const from = live.get(fromId)
          if (from) {
            live.delete(fromId)
            const moved: RealTable = {
              id: toId,
              sources: from.sources.some((item) => item.path === relative) ? from.sources : [...from.sources, source],
              renamedFrom: [...(from.renamedFrom ?? []), fromId],
            }
            live.set(toId, moved)
          } else if (!live.has(toId)) {
            live.set(toId, { id: toId, sources: [source], renamedFrom: [fromId] })
          }
          continue
        }
        // DROP TABLE
        const drop = /^DROP\s+TABLE\s+(IF\s+EXISTS\s+)?(.+)$/i.exec(normalized)
        if (drop) {
          for (const raw of drop[2].split(',')) {
            const id = canonicalTable(raw.replace(/CASCADE|RESTRICT/gi, '').trim())
            const existing = live.get(id)
            if (existing) {
              live.delete(id)
              const entry = dropped.get(id) ?? { id, sources: [] }
              if (!entry.sources.some((item) => item.path === relative)) entry.sources.push(source)
              dropped.set(id, entry)
            }
          }
          continue
        }
        // DO 块：静态建表识别；动态建表 unsupported。
        if (/^DO\s+\$/i.test(normalized)) {
          const bodyMatch = /\$[a-zA-Z0-9_]*\$([\s\S]*)\$[a-zA-Z0-9_]*\$\s*$/i.exec(statement)
          if (bodyMatch) {
            const scanned = scanDollarBlock(bodyMatch[1])
            for (const name of scanned.staticCreates) {
              const id = canonicalTable(name)
              if (!live.has(id)) live.set(id, { id, sources: [source] })
            }
            for (const excerpt of scanned.dynamicCreates) {
              unsupported.push({ service: root.service, path: relative, detail: 'DO 块内 EXECUTE 动态建表', excerpt })
            }
          }
          continue
        }
        // 顶层动态建表（EXECUTE format/|| 拼接）
        if (/EXECUTE/i.test(normalized) && /CREATE\s+TABLE/i.test(normalized)) {
          unsupported.push({ service: root.service, path: relative, detail: 'EXECUTE 动态建表', excerpt: normalized.slice(0, 160) })
        }
      }
    }
  }
  const tables = [...live.values()].sort((left, right) => left.id.localeCompare(right.id))
  return {
    tables,
    dropped: [...dropped.values()].sort((left, right) => left.id.localeCompare(right.id)),
    unsupported: unsupported.sort((left, right) => left.path.localeCompare(right.path) || left.detail.localeCompare(right.detail)),
  }
}

// ---------- Java 扫描（进程外 JDK25 编译器树） ----------

/** Java 可执行解析：JAVA_BIN > JAVA_HOME/bin/java > PATH 上的 java。 */
export function resolveJavaBin(): string {
  if (process.env.JAVA_BIN) return process.env.JAVA_BIN
  if (process.env.JAVA_HOME) return path.join(process.env.JAVA_HOME, 'bin', 'java')
  return 'java'
}

export function runJavaInventory(repoRoot: string, javaBin = resolveJavaBin(),
  roots: string[] = JAVA_ROOTS): JavaInventory {
  const script = path.join(repoRoot, 'scripts', 'quality', 'LifecycleJavaInventory.java')
  const args = [script, ...roots.map((root) => (path.isAbsolute(root) ? root : path.join(repoRoot, root)))]
  let result: import('node:child_process').SpawnSyncReturns<string>
  try {
    result = spawnSync(javaBin, args, { cwd: repoRoot, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 })
  } catch (error) {
    const wrapped = new Error(`Java 扫描器无法启动（${javaBin}）：${error instanceof Error ? error.message : String(error)}`)
    ;(wrapped as Error & { cause?: unknown }).cause = error
    throw wrapped
  }
  if (result.error) {
    const wrapped = new Error(`Java 扫描器无法启动（${javaBin}）：${result.error.message}。CI 需要 JDK25（setup-java）后才能跑 quality:lifecycle。`)
    ;(wrapped as Error & { cause?: unknown }).cause = result.error
    throw wrapped
  }
  // exit 4 = 存在 unresolved-production-site（合法输出，交由基线核验豁免/拦截）；
  // 其他非零（2 用法错/3 解析失败）才是扫描器自身失败。
  if (result.status !== 0 && result.status !== 4) {
    throw new Error(`Java 扫描器失败（exit=${result.status}）：\n${String(result.stderr ?? '').slice(0, 4000)}`)
  }
  try {
    return JSON.parse(result.stdout) as JavaInventory
  } catch (error) {
    const wrapped = new Error(`Java 扫描器输出不可解析：${error instanceof Error ? error.message : String(error)}\n${String(result.stdout).slice(0, 800)}`)
    ;(wrapped as Error & { cause?: unknown }).cause = error
    throw wrapped
  }
}

// ---------- 组合 ----------

export function buildRealInventory(repoRoot: string, javaBin?: string): RealInventory {
  const sql = scanSqlInventory(repoRoot)
  const java = runJavaInventory(repoRoot, javaBin)
  return {
    tables: sql.tables,
    dropped: sql.dropped,
    unsupportedSql: sql.unsupported,
    events: java.events,
    unresolvedJava: java.unresolved,
  }
}

// ---------- CLI ----------

async function main(): Promise<void> {
  const args = process.argv.slice(2)
  const repoRoot = args.includes('--repo-root') ? path.resolve(args[args.indexOf('--repo-root') + 1]) : process.cwd()
  const javaBin = args.includes('--java') ? args[args.indexOf('--java') + 1] : undefined
  const inventory = buildRealInventory(repoRoot, javaBin)
  const summary = {
    tables: inventory.tables.length,
    dropped: inventory.dropped.length,
    unsupportedSql: inventory.unsupportedSql.length,
    eventTypes: inventory.events.length,
    unresolvedJava: inventory.unresolvedJava.length,
  }
  if (args.includes('--baseline-candidates')) {
    const target = path.resolve(args[args.indexOf('--baseline-candidates') + 1])
    mkdirSync(path.dirname(target), { recursive: true })
    writeFileSync(target, JSON.stringify({ summary, inventory }, null, 2))
    console.error(`基线候选已写入 ${target}（仅首次采集；逐项人工审阅后入库，正常门禁不写基线）`)
  }
  process.stdout.write(JSON.stringify({ summary, ...inventory }, null, 2))
  if (inventory.unsupportedSql.length > 0 || inventory.unresolvedJava.length > 0) {
    process.exit(1)
  }
}

if (process.argv[1] && path.resolve(process.argv[1]).endsWith('lifecycle-inventory.ts')) {
  await main()
}

// ---------- 基线核验（§3 D06；C104-07 门禁复用） ----------

export interface BaselineEntryViolation {
  scope: string
  entry: string
  rule: string
  message: string
}

export interface LifecycleBaseline {
  version: number
  baselineHead: string
  requiredResources: string[]
  requiredEvents: string[]
  legacyResources: Array<Record<string, unknown>>
  legacyEvents: Array<Record<string, unknown>>
  dynamicEventSites: Array<Record<string, unknown>>
}

/** 文件级规范化摘要：去除全部空白（空白格式变化不误触发；字符串/代码变化触发）。 */
export function normalizedSourceDigest(content: string): string {
  return createHash('sha256').update(content.replace(/\s+/g, '')).digest('hex')
}

export function loadBaseline(baselinePath: string): LifecycleBaseline {
  return JSON.parse(readFileSync(baselinePath, 'utf8')) as LifecycleBaseline
}

/**
 * 基线 vs 真实清单双向核对（D06）：
 * - schema：version=1、逐条 legacy/dynamic 字段完整（空理由/重复/通配符即失败）；
 * - required 与 legacy 互不重叠；required 逐项来自当前登记簿冻结（数量精确相等）；
 * - 资源/事件双向差集：真实多出未入册即失败；册内条目在真实清单不存在（退役/漂移）即失败；
 * - legacy 锚点文件存在且规范化摘要一致（改相关源码→漂移失败；空白变化不误触发）；
 * - dynamicEventSites 与扫描器 unresolved 按 digest 精确互匹配（新增未解析点/源码
 *   已可解析的过期登记都失败）；guardTc 必须是仓库内真实存在的测试文件。
 */
export function validateBaseline(repoRoot: string, inventory: RealInventory,
  baseline: LifecycleBaseline): BaselineEntryViolation[] {
  const violations: BaselineEntryViolation[] = []
  const push = (scope: string, entry: string, rule: string, message: string): void => {
    violations.push({ scope, entry, rule, message })
  }
  if (baseline.version !== 1) push('baseline', 'version', 'schema', 'version 必须为 1')
  if (typeof baseline.baselineHead !== 'string' || baseline.baselineHead.trim() === '') {
    push('baseline', 'baselineHead', 'schema', 'baselineHead 必填')
  }

  const requireStringArray = (scope: string, values: unknown, rule: string): string[] => {
    if (!Array.isArray(values) || values.length === 0 || values.some((value) => typeof value !== 'string' || value.trim() === '')) {
      push(scope, rule, 'schema', `${rule} 必须是非空字符串数组（不得只写数量下限）`)
      return []
    }
    const seen = new Set<string>()
    for (const value of values as string[]) {
      if (seen.has(value)) push(scope, value, 'unique', `${rule} 内重复：${value}`)
      if (value.includes('*')) push(scope, value, 'wildcard', `${rule} 禁止通配符：${value}`)
      seen.add(value)
    }
    return values as string[]
  }

  const requiredResources = requireStringArray('baseline', baseline.requiredResources, 'requiredResources')
  const requiredEvents = requireStringArray('baseline', baseline.requiredEvents, 'requiredEvents')

  // legacy 资源
  const legacyResourceIds: string[] = []
  const seenLegacy = new Set<string>()
  for (const entry of baseline.legacyResources ?? []) {
    const id = String(entry.id ?? '')
    if (!id || id.includes('*')) {
      push('legacyResources', id || '(missing)', 'schema', 'id 必填且禁止通配符')
      continue
    }
    if (seenLegacy.has(id)) push('legacyResources', id, 'unique', '重复的历史豁免条目')
    seenLegacy.add(id)
    legacyResourceIds.push(id)
    const anchor = entry.anchor as Record<string, unknown> | undefined
    if (!anchor || typeof anchor.path !== 'string' || !anchor.path || typeof anchor.service !== 'string') {
      push('legacyResources', id, 'schema', 'anchor{service,path} 必填')
    } else {
      const anchorPath = path.join(repoRoot, anchor.path)
      if (!existsSync(anchorPath)) {
        push('legacyResources', id, 'anchor', `锚点文件不存在：${anchor.path}`)
      } else if (typeof entry.sourceSha256 !== 'string' || !/^[0-9a-f]{64}$/.test(entry.sourceSha256)) {
        push('legacyResources', id, 'schema', 'sourceSha256 必须是 64 位十六进制')
      } else if (normalizedSourceDigest(readFileSync(anchorPath, 'utf8')) !== entry.sourceSha256) {
        push('legacyResources', id, 'digest-drift', `锚点源码摘要漂移：${anchor.path}（须重新人工审阅）`)
      }
    }
    if (typeof entry.reason !== 'string' || entry.reason.trim() === '') {
      push('legacyResources', id, 'schema', 'reason 必填（空理由不允许）')
    }
    if (typeof entry.followUp !== 'string' || entry.followUp.trim() === '') {
      push('legacyResources', id, 'schema', 'followUp（关联跟进说明）必填')
    }
    if (requiredResources.includes(id)) {
      push('legacyResources', id, 'overlap', '原登记条目不得转入历史豁免（BR-07）')
    }
  }

  // legacy 事件
  const legacyEventTypes: string[] = []
  const seenLegacyEvent = new Set<string>()
  for (const entry of baseline.legacyEvents ?? []) {
    const eventType = String(entry.eventType ?? '')
    if (!eventType || eventType.includes('*')) {
      push('legacyEvents', eventType || '(missing)', 'schema', 'eventType 必填且禁止通配符')
      continue
    }
    if (seenLegacyEvent.has(eventType)) push('legacyEvents', eventType, 'unique', '重复的历史豁免条目')
    seenLegacyEvent.add(eventType)
    legacyEventTypes.push(eventType)
    const sites = entry.producerSites
    if (!Array.isArray(sites) || sites.length === 0
      || sites.some((site) => !site || typeof site.path !== 'string' || typeof site.symbol !== 'string')) {
      push('legacyEvents', eventType, 'schema', 'producerSites（真实源码锚点）必填')
    }
    if (typeof entry.reason !== 'string' || entry.reason.trim() === '') {
      push('legacyEvents', eventType, 'schema', 'reason 必填')
    }
    if (typeof entry.followUp !== 'string' || entry.followUp.trim() === '') {
      push('legacyEvents', eventType, 'schema', 'followUp 必填')
    }
    if (requiredEvents.includes(eventType)) {
      push('legacyEvents', eventType, 'overlap', '正式登记事件不得转入历史豁免')
    }
  }

  // 资源双向差集
  const realTableIds = new Set(inventory.tables.map((table) => table.id))
  const droppedIds = new Set(inventory.dropped.map((table) => table.id))
  const knownResources = new Set([...requiredResources, ...legacyResourceIds])
  for (const table of inventory.tables) {
    if (!knownResources.has(table.id)) {
      push('inventory', table.id, 'resource-unregistered', '真实表未入正式登记或历史豁免')
    }
  }
  for (const id of knownResources) {
    if (droppedIds.has(id) && !realTableIds.has(id)) {
      push('baseline', id, 'resource-dropped', '条目指向已 DROP 的表（须移出并给退役证据）')
    } else if (!realTableIds.has(id)) {
      push('baseline', id, 'resource-missing', '登记指向不存在的活跃表')
    }
  }

  // 事件双向差集
  const realEventTypes = new Set(inventory.events.map((event) => event.eventType))
  const knownEvents = new Set([...requiredEvents, ...legacyEventTypes])
  for (const eventType of realEventTypes) {
    if (!knownEvents.has(eventType)) {
      push('inventory', eventType, 'event-unregistered', '真实生产事件未入正式登记或历史豁免')
    }
  }
  for (const eventType of knownEvents) {
    if (!realEventTypes.has(eventType)) {
      push('baseline', eventType, 'event-missing', '登记/豁免的事件在真实源码中无生产点')
    }
  }

  // 动态解析点：digest 与扫描器 unresolved 精确互匹配
  const dynamic = baseline.dynamicEventSites ?? []
  const registeredDigests = new Map<string, Record<string, unknown>>()
  for (const entry of dynamic) {
    const digest = typeof entry.digest === 'string' ? entry.digest : ''
    const site = `${entry.path ?? ''}::${entry.symbol ?? ''}`
    if (!digest || !/^[0-9a-f]{64}$/.test(digest)) {
      push('dynamicEventSites', site, 'schema', 'digest（调用表达式 token 摘要）必填')
      continue
    }
    if (registeredDigests.has(digest)) {
      push('dynamicEventSites', site, 'unique', '重复的动态点登记')
    }
    registeredDigests.set(digest, entry)
    if (!Array.isArray(entry.eventTypes) || entry.eventTypes.length === 0
      || entry.eventTypes.some((value: unknown) => typeof value !== 'string')) {
      push('dynamicEventSites', site, 'schema', 'eventTypes 必须是非空有限数组')
    }
    if (typeof entry.reason !== 'string' || entry.reason.trim() === '') {
      push('dynamicEventSites', site, 'schema', 'reason（依据）必填')
    }
    if (typeof entry.guardTc !== 'string' || !existsSync(path.join(repoRoot, entry.guardTc))) {
      push('dynamicEventSites', site, 'guardTc', `守卫测试文件不存在：${String(entry.guardTc)}`)
    }
  }
  for (const site of inventory.unresolvedJava) {
    if (!registeredDigests.has(site.digest ?? '')) {
      push('inventory', `${site.path}::${site.symbol}`, 'dynamic-unregistered',
        `未解析生产点未登记 dynamicEventSites（digest=${site.digest}）`)
    }
  }
  for (const [digest] of registeredDigests) {
    if (!inventory.unresolvedJava.some((site) => site.digest === digest)) {
      push('dynamicEventSites', digest.slice(0, 12), 'dynamic-stale', '登记的动态点在当前源码中已不存在或已可静态解析（过期登记）')
    }
  }
  return violations
}
