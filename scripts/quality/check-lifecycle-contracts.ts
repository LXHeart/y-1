#!/usr/bin/env npx tsx
/**
 * 任务书 #103 C103-19（V12）→ #104 C104-07（D06/§6.3）：资源与事件生命周期接入门禁。
 *
 * 用法：npx tsx scripts/quality/check-lifecycle-contracts.ts [--registry-root tests/contracts]
 *       测试可用 LIFECYCLE_REGISTRY_ROOT 指定 fixture 根（合成负例），并注入合成清单/基线。
 *
 * 校验（不写任何业务状态、不联网）：
 * - registry schema v1/v2：resource 必填 table/service/scope(五类枚举)/ownerResolver/activeStates/
 *   terminalEvidence/retentionClass/eraseHandler/tc；event 必填 eventType/producer/
 *   recipientPolicy/consumer/delivery/tc；v2 追加 producerRefs/consumerRefs/derivedRefs
 *   结构化机器引用（handle/ignore/conditional；ignore/conditional 必须带非空 ignoredReason）。
 * - tc 引用真实存在的测试文件（防「编造测试引用」；非测试路径拒绝）。
 * - retired 条目必须带 retiredNote，且在真实清单中确有 DROP/生产代码移除证据（D06）。
 * - 真实清单双向核对（#104）：checkContracts 默认现场扫描 SQL+Java 清单并加载
 *   lifecycle-inventory.baseline.json——登记条目必须存在于真实清单、producerRefs 必须命中
 *   该事件的真实生产点、consumerRefs 的源码方法必须存在；baseline required 与登记簿精确
 *   相等（删登记不能靠 baseline 或无关条目补数）；基线核验违规逐条并入。
 * - 同名表/事件重复登记拒绝（幂等唯一）。
 * 退出码：任何违规非零并逐条列出；通过输出计数摘要（含 inventoryCounts/unresolved/exempted）。
 */

import { existsSync, readFileSync, readdirSync, realpathSync, statSync } from 'node:fs'
import path from 'node:path'
import {
  buildRealInventory, loadBaseline, validateBaseline,
} from './lifecycle-inventory'
import type { LifecycleBaseline, RealInventory } from './lifecycle-inventory'

export interface Violation {
  registry: string
  entry: string
  rule: string
  message: string
}

const SCOPES = ['personal', 'org-shared', 'financial-audit', 'platform-configuration', 'ephemeral'] as const

function loadRegistry(root: string, name: string): { kind: string; entries: Array<Record<string, unknown>>; version: unknown } {
  const file = path.join(root, name)
  if (!existsSync(file)) {
    throw new Error(`registry 不存在：${file}`)
  }
  let parsed: Record<string, unknown>
  try {
    parsed = JSON.parse(readFileSync(file, 'utf8')) as Record<string, unknown>
  } catch (error) {
    const wrapped = new Error(`${name} 不是合法 JSON（${file}）：${error instanceof Error ? error.message : String(error)}`)
    ;(wrapped as Error & { cause?: unknown }).cause = error
    throw wrapped
  }
  const kind = name.includes('resource') ? 'resources' : 'events'
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new Error(`${name} 顶层必须是对象`)
  }
  const entries = parsed[kind] as Array<Record<string, unknown>>
  if (!Array.isArray(entries)) {
    throw new Error(`${name}.${kind} 必须是数组`)
  }
  return { kind, entries, version: parsed.version }
}

function checkResource(root: string, entry: Record<string, unknown>, index: number, repoRoot: string,
  violations: Violation[], seenTables: Map<string, string>): void {
  const registry = 'resource-lifecycle.registry.json'
  const fallbackId = `resources[${index}]`
  if (entry === null || typeof entry !== 'object' || Array.isArray(entry)) {
    violations.push({ registry, entry: fallbackId, rule: 'schema', message: '条目必须是对象（null/非对象不吞）' })
    return
  }
  const id = String(entry.table ?? fallbackId)
  if (typeof entry.table !== 'string' || entry.table.trim() === '') {
    violations.push({ registry, entry: id, rule: 'schema', message: 'table 必须非空' })
    return
  }
  if (seenTables.has(entry.table)) {
    violations.push({ registry, entry: id, rule: 'unique', message: `表重复登记（先见于 ${seenTables.get(entry.table)}）` })
  }
  seenTables.set(entry.table, id)
  if (typeof entry.service !== 'string' || entry.service.trim() === '') {
    violations.push({ registry, entry: id, rule: 'schema', message: 'service 必填' })
  }
  if (!SCOPES.includes(entry.scope as typeof SCOPES[number])) {
    violations.push({ registry, entry: id, rule: 'schema', message: `scope 必须是五类枚举之一（${SCOPES.join('|')}）` })
  }
  for (const field of ['ownerResolver', 'terminalEvidence', 'retentionClass', 'eraseHandler'] as const) {
    if (typeof entry[field] !== 'string' || (entry[field] as string).trim() === '') {
      violations.push({ registry, entry: id, rule: 'schema', message: `${field} 必填（不能给未知资源编造归属）` })
    }
  }
  if (!Array.isArray(entry.activeStates) || entry.activeStates.length === 0) {
    violations.push({ registry, entry: id, rule: 'schema', message: 'activeStates 必须是非空数组（BR-15：不能只数 running）' })
  }
  if (!Array.isArray(entry.derivedObjects)) {
    violations.push({ registry, entry: id, rule: 'schema', message: 'derivedObjects 必须是数组' })
  }
  // #106 D04：v2 必填字段——derivedRefs 必须是数组（无依赖允许 []），缺字段不放行。
  if (!Array.isArray(entry.derivedRefs)) {
    violations.push({
      registry, entry: id, rule: 'schema',
      message: 'derivedRefs 必须是数组（v2 必填；无依赖允许 []，不能靠缺字段绕过校验）',
    })
  }
  if (entry.retired === true && (typeof entry.retiredNote !== 'string' || (entry.retiredNote as string).trim() === '')) {
    violations.push({ registry, entry: id, rule: 'retired', message: 'retired 条目必须带 retiredNote' })
  }
  checkTc(registry, id, entry.tc, repoRoot, violations)
}

function checkEvent(entry: Record<string, unknown>, index: number, repoRoot: string,
  violations: Violation[], seenEvents: Map<string, string>): void {
  const registry = 'event-consumers.registry.json'
  const fallbackId = `events[${index}]`
  if (entry === null || typeof entry !== 'object' || Array.isArray(entry)) {
    violations.push({ registry, entry: fallbackId, rule: 'schema', message: '条目必须是对象（null/非对象不吞）' })
    return
  }
  const id = String(entry.eventType ?? fallbackId)
  if (typeof entry.eventType !== 'string' || entry.eventType.trim() === '') {
    violations.push({ registry, entry: id, rule: 'schema', message: 'eventType 必须非空' })
    return
  }
  if (seenEvents.has(entry.eventType)) {
    violations.push({ registry, entry: id, rule: 'unique', message: `事件重复登记（先见于 ${seenEvents.get(entry.eventType)}）` })
  }
  seenEvents.set(entry.eventType, id)
  for (const field of ['producer', 'recipientPolicy', 'consumer', 'delivery'] as const) {
    if (typeof entry[field] !== 'string' || (entry[field] as string).trim() === '') {
      violations.push({ registry, entry: id, rule: 'schema', message: `${field} 必填` })
    }
  }
  // #106 D04：v2 必填字段——producerRefs/consumerRefs 必须是非空数组，缺字段不放行。
  if (!Array.isArray(entry.producerRefs) || entry.producerRefs.length === 0) {
    violations.push({ registry, entry: id, rule: 'schema', message: 'v2 producerRefs 必须是非空数组（真实源码锚点；缺失=未登记真实生产点）' })
  }
  if (!Array.isArray(entry.consumerRefs) || entry.consumerRefs.length === 0) {
    violations.push({ registry, entry: id, rule: 'schema', message: 'v2 consumerRefs 必须是非空数组（缺失=未登记真实消费点）' })
  }
  checkTc(registry, id, entry.tc, repoRoot, violations)
}

function checkTc(registry: string, id: string, tc: unknown, repoRoot: string, violations: Violation[]): void {
  // #106 D04：登记一律 v2——tc 统一按测试文件严格校验。
  checkTcV2(registry, id, tc, repoRoot, violations)
}

/** producer 类名在仓库源码中可定位（BR-19：不是只检查文件存在）。 */
function checkProducers(eventEntries: Array<Record<string, unknown>>, repoRoot: string, violations: Violation[]): void {
  const javaFiles = collectJavaFiles(path.join(repoRoot, 'platform-java', 'services'), new Set<string>(), 0)
  const classNames = new Set(javaFiles.map(file => path.basename(file, '.java')))
  for (const entry of eventEntries) {
    if (!entry || typeof entry !== 'object' || Array.isArray(entry)) continue
    const producer = String(entry.producer ?? '')
    const tokens = producer.split(/\s+/).filter(token => /^[A-Z][A-Za-z0-9]+$/.test(token))
    if (tokens.length === 0) {
      continue
    }
    if (!tokens.some(token => classNames.has(token))) {
      violations.push({
        registry: 'event-consumers.registry.json',
        entry: String(entry.eventType ?? '?'),
        rule: 'producer-exists',
        message: `producer 类名在源码中不可定位：${producer}`,
      })
    }
  }
}

function collectJavaFiles(dir: string, acc: Set<string>, depth: number): string[] {
  if (depth > 14 || !existsSync(dir) || !statSync(dir).isDirectory()) {
    return [...acc]
  }
  for (const name of readdirSync(dir)) {
    const full = path.join(dir, name)
    const isDirectory = statSync(full).isDirectory()
    if (name.endsWith('.java')) {
      acc.add(full)
    } else if (isDirectory) {
      collectJavaFiles(full, acc, depth + 1)
    }
  }
  return [...acc]
}

/** 测试可注入合成清单/基线；缺省现场扫描（真实门禁路径）。 */
export interface CheckOptions {
  inventory?: RealInventory
  baseline?: LifecycleBaseline
}

export interface CheckResult {
  violations: Violation[]
  counts: { resources: number; events: number }
  inventoryCounts?: { tables: number; eventTypes: number; unsupportedSql: number; unresolvedJava: number }
  unresolved?: number
  exempted?: { legacyResources: number; legacyEvents: number; dynamicEventSites: number }
}

/** registry 表名 → 规范键（schema.table；缺省 public）。 */
function canonical(table: string): string {
  return table.includes('.') ? table : `public.${table}`
}

/** tc 必须是仓库内真实存在的测试文件（含测试路径特征；禁止 ../ 越界、绝对路径、符号链接逃逸、目录与生产文件冒充）。 */
function isTestPath(repoRoot: string, tc: string): { exists: boolean; isTest: boolean; reason?: string } {
  const normalized = tc.split('/').join('/')
  if (normalized.startsWith('/') || normalized.startsWith('\\')) {
    return { exists: false, isTest: false, reason: '绝对路径' }
  }
  if (normalized.startsWith('..') || normalized.includes('/../')) {
    return { exists: false, isTest: false, reason: '相对路径越界（../）' }
  }
  const absolute = path.join(repoRoot, normalized)
  if (!existsSync(absolute)) {
    return { exists: false, isTest: false }
  }
  // 符号链接逃逸：真实路径必须仍在仓库内；目录不能冒充测试文件。
  try {
    const realRoot = realpathSync(repoRoot)
    const realPath = realpathSync(absolute)
    if (realPath !== realRoot && !realPath.startsWith(realRoot + path.sep)) {
      return { exists: false, isTest: false, reason: '符号链接逃逸出仓库' }
    }
  } catch {
    return { exists: false, isTest: false, reason: '路径不可解析' }
  }
  if (!statSync(absolute).isFile()) {
    return { exists: false, isTest: false, reason: '不是常规文件（目录/其他）' }
  }
  const isTest = (/\.(test|spec)\.[cm]?[jt]sx?$/.test(normalized)
    && (normalized.startsWith('src/') || normalized.startsWith('tests/')))
    || (normalized.includes('/src/test/java/') && /(?:Test|Tests|IT)\.java$/.test(normalized))
  return { exists: true, isTest }
}

/** 源码引用不能用目录、测试文件或出仓库的符号链接冒充。 */
function isSourcePath(repoRoot: string, source: string): boolean {
  if (!source.trim() || path.isAbsolute(source) || source.includes('\\')
    || source.split('/').includes('..') || !/\.java$/.test(source)
    || !source.includes('/src/main/java/')) return false
  try {
    const realRoot = realpathSync(repoRoot)
    const absolute = path.join(repoRoot, source)
    const real = realpathSync(absolute)
    return real.startsWith(realRoot + path.sep) && statSync(absolute).isFile()
  } catch {
    return false
  }
}

/** v2 结构化引用：producerRefs 命中事件真实生产点；consumerRefs 源码方法经 AST 声明清单核验
 * （#106 D04：Class#method(Type,Type) 精确匹配——路径+属主+方法名+参数，重载不靠同名通过；
 * 注释/字符串不产生声明）。 */
function checkMachineRefs(resources: Array<Record<string, unknown>>, events: Array<Record<string, unknown>>,
  repoRoot: string, inventory: RealInventory | undefined, violations: Violation[]): void {
  const tableIds = new Set(inventory?.tables.map((table) => table.id) ?? [])
  const droppedIds = new Set(inventory?.dropped.map((table) => table.id) ?? [])
  // 声明索引（path|symbol → 出现次数；>1 = 同文件同名歧义，不得任意命中一个）。
  const declarationCounts = new Map<string, number>()
  for (const declaration of inventory?.declarations ?? []) {
    const key = `${declaration.path}|${declaration.symbol}`
    declarationCounts.set(key, (declarationCounts.get(key) ?? 0) + 1)
  }
  const declarationViolation = (registry: string, id: string, rule: string, symbolPath: string, symbol: string, what: string): void => {
    const count = declarationCounts.get(symbolPath)
    if (count === undefined) {
      violations.push({
        registry, entry: id, rule,
        message: `${what}符号在 AST 声明清单中不存在（须为真实声明的 Class#method(Type,Type)）：${symbol}`,
      })
    } else if (count > 1) {
      violations.push({
        registry, entry: id, rule,
        message: `${what}符号在同文件存在同名歧义声明（不任意命中一个）：${symbol}`,
      })
    }
  }
  for (const entry of resources) {
    const id = String(entry?.table ?? '?')
    const registry = 'resource-lifecycle.registry.json'
    if (entry?.retired === true) {
      if (inventory && !droppedIds.has(canonical(id))) {
        violations.push({
          registry, entry: id, rule: 'retired-evidence',
          message: 'retired 条目在真实清单中仍是活跃表（须真实 DROP 或生产代码移除证据，单填 retiredNote 不足以放过）',
        })
      }
      continue
    }
    if (inventory && !tableIds.has(canonical(id))) {
      violations.push({ registry, entry: id, rule: 'resource-real', message: '登记表在真实 SQL 清单中不存在' })
    }
    const derived = entry?.derivedRefs
    if (derived !== undefined) {
      if (!Array.isArray(derived)) {
        violations.push({ registry, entry: id, rule: 'schema', message: 'derivedRefs 必须是数组' })
      } else {
        for (const ref of derived as Array<Record<string, unknown>>) {
          if (ref?.kind === 'table' && typeof ref.id === 'string') {
            if (inventory && !tableIds.has(ref.id)) {
              violations.push({
                registry, entry: id, rule: 'derived-table',
                message: `derived table 在真实清单中不存在：${ref.id}`,
              })
            }
          } else if (ref?.kind === 'note' && typeof ref.text === 'string' && ref.text.trim() !== '') {
            // 仅描述，不伪装已解析依赖。
          } else if ((ref?.kind === 'object-store' || ref?.kind === 'cache')
            && ref.handler && typeof (ref.handler as Record<string, unknown>).path === 'string') {
            const handler = ref.handler as { path: string; symbol?: unknown }
            if (typeof handler.symbol !== 'string' || !handler.symbol.trim()
              || !isSourcePath(repoRoot, handler.path)) {
              violations.push({
                registry, entry: id, rule: 'derived-handler',
                message: `derived handler 必须有非空 symbol 及仓库内真实源码路径：${handler.path}`,
              })
            } else {
              declarationViolation(registry, id, 'derived-handler', `${handler.path}|${handler.symbol}`,
                handler.symbol, 'derived handler ')
            }
          } else {
            violations.push({
              registry, entry: id, rule: 'schema',
              message: `derivedRefs 条目必须是 table{id}/note{text}/object-store|cache{handler{path,symbol}}（note 不冒充已解析依赖）`,
            })
          }
        }
      }
    }
  }
  const eventSites = new Map((inventory?.events ?? []).map((event) => [event.eventType, new Set(event.sites.map((site) => `${site.path}|${site.symbol}`))]))
  for (const entry of events) {
    const id = String(entry?.eventType ?? '?')
    const registry = 'event-consumers.registry.json'
    if (inventory && !eventSites.has(id)) {
      violations.push({ registry, entry: id, rule: 'event-real', message: '登记事件在真实 Java 清单中无生产点' })
    }
    const producerRefs = entry?.producerRefs
    if (producerRefs !== undefined) {
      if (!Array.isArray(producerRefs) || producerRefs.length === 0) {
        violations.push({ registry, entry: id, rule: 'schema', message: 'v2 producerRefs 必须是非空数组（真实源码锚点）' })
      } else {
        const sites = eventSites.get(id) ?? new Set<string>()
        for (const ref of producerRefs as Array<Record<string, unknown>>) {
          if (typeof ref?.path !== 'string' || typeof ref?.symbol !== 'string') {
            violations.push({ registry, entry: id, rule: 'schema', message: 'producerRefs 条目必须是 {path, symbol}' })
            continue
          }
          if (!isSourcePath(repoRoot, ref.path)) {
            violations.push({ registry, entry: id, rule: 'producer-source', message: `producer 必须是仓库内真实源码：${ref.path}` })
          }
          if (inventory && !sites.has(`${ref.path}|${ref.symbol}`)) {
            violations.push({
              registry, entry: id, rule: 'producer-mismatch',
              message: `producerRef 不是该事件的生产点（producer 存在但产另一事件或已漂移）：${ref.symbol}`,
            })
          }
        }
      }
    }
    const consumerRefs = entry?.consumerRefs
    if (consumerRefs !== undefined) {
      if (!Array.isArray(consumerRefs) || consumerRefs.length === 0) {
        violations.push({ registry, entry: id, rule: 'schema', message: 'v2 consumerRefs 必须是非空数组' })
      } else {
        for (const ref of consumerRefs as Array<Record<string, unknown>>) {
          const source = ref?.source as Record<string, unknown> | undefined
          if (!source || typeof source.path !== 'string' || typeof source.symbol !== 'string') {
            violations.push({ registry, entry: id, rule: 'schema', message: 'consumerRefs.source 必须是 {path, symbol}' })
            continue
          }
          const disposition = String(ref.disposition ?? '')
          if (!['handle', 'ignore', 'conditional'].includes(disposition)) {
            violations.push({ registry, entry: id, rule: 'schema', message: `disposition 必须是 handle|ignore|conditional（当前：${disposition || '(缺失)'}）` })
          }
          if ((disposition === 'ignore' || disposition === 'conditional')
            && (typeof ref.ignoredReason !== 'string' || (ref.ignoredReason as string).trim() === '')) {
            violations.push({ registry, entry: id, rule: 'ignored-reason', message: 'ignore/conditional 必须带非空 ignoredReason' })
          }
          // #106 D04：consumerRefs.tc 与条目级 tc 同一严格校验（真实仓库内测试文件）。
          checkTcV2(registry, id, ref.tc, repoRoot, violations)
          // 源码路径必须仓库内相对（绝对/越界拒绝）；符号经 AST 声明清单精确核验。
          if (!isSourcePath(repoRoot, source.path)) {
            violations.push({
              registry, entry: id, rule: 'consumer-source',
              message: `consumer 源码路径必须是仓库内相对路径：${source.path}`,
            })
            continue
          }
          declarationViolation(registry, id, 'consumer-method', `${source.path}|${source.symbol}`,
            source.symbol, 'consumer ')
        }
      }
    }
  }
}

/** tc 必须是测试文件（#106 D04 收紧：所有登记一律按 v2 严格校验，顶层 version=2 已另行强制）。 */
function checkTcV2(registry: string, id: string, tc: unknown, repoRoot: string, violations: Violation[]): void {
  if (typeof tc !== 'string' || tc.trim() === '') {
    violations.push({ registry, entry: id, rule: 'tc', message: 'tc（守卫测试引用）必填' })
    return
  }
  const { exists, isTest, reason } = isTestPath(repoRoot, tc)
  if (!exists) {
    violations.push({
      registry, entry: id, rule: 'tc',
      message: reason
        ? `tc 引用非法（${reason}）：${tc}`
        : `tc 引用的测试文件不存在：${tc}`,
    })
  } else if (!isTest) {
    violations.push({ registry, entry: id, rule: 'tc', message: `tc 必须指向测试文件（当前是生产/非测试路径）：${tc}` })
  }
}

export function checkContracts(repoRoot: string, registryRoot: string, options: CheckOptions = {}): CheckResult {
  const violations: Violation[] = []
  const resources = loadRegistry(registryRoot, 'resource-lifecycle.registry.json')
  const events = loadRegistry(registryRoot, 'event-consumers.registry.json')
  // #106 D04/F06：顶层 version 必须为数值 2（缺失/1/字符串/未知均失败，不从条目字段推断）。
  for (const { name, version } of [
    { name: 'resource-lifecycle.registry.json', version: resources.version },
    { name: 'event-consumers.registry.json', version: events.version },
  ]) {
    if (version !== 2) {
      violations.push({
        registry: name,
        entry: '(top-level)',
        rule: 'version',
        message: `顶层 version 必须为数值 2（当前：${version === undefined ? '缺失' : JSON.stringify(version)}）；不从条目字段推断版本`,
      })
    }
  }
  const seenTables = new Map<string, string>()
  resources.entries.forEach((entry, index) => checkResource(registryRoot, entry, index, repoRoot, violations, seenTables))
  const seenEvents = new Map<string, string>()
  events.entries.forEach((entry, index) => checkEvent(entry, index, repoRoot, violations, seenEvents))
  checkProducers(events.entries, repoRoot, violations)

  // 真实清单 + 基线（默认现场扫描；测试可注入合成）。
  let inventory = options.inventory
  let baseline = options.baseline
  if (inventory === undefined) {
    inventory = buildRealInventory(repoRoot)
  }
  if (baseline === undefined) {
    const baselinePath = path.join(registryRoot, 'lifecycle-inventory.baseline.json')
    if (!existsSync(baselinePath)) {
      violations.push({
        registry: 'lifecycle-inventory.baseline.json', entry: '(missing)', rule: 'baseline',
        message: '登记根缺少 lifecycle-inventory.baseline.json（D06：required 冻结与历史豁免载体）',
      })
    } else {
      baseline = loadBaseline(baselinePath)
    }
  }
  if (baseline) {
    for (const violation of validateBaseline(repoRoot, inventory, baseline)) {
      violations.push({
        registry: 'lifecycle-inventory.baseline.json',
        entry: `${violation.scope}:${violation.entry}`,
        rule: violation.rule,
        message: violation.message,
      })
    }
    // required 与登记簿精确相等（双向；删登记不能靠 baseline/无关条目补数）。
    const registryTables = new Set(resources.entries.map((entry) => canonical(String(entry?.table ?? ''))))
    const registryEvents = new Set(events.entries.map((entry) => String(entry?.eventType ?? '')))
    for (const id of baseline.requiredResources) {
      if (!registryTables.has(id)) {
        violations.push({ registry: 'resource-lifecycle.registry.json', entry: id, rule: 'required-frozen', message: 'baseline required 资源在登记簿中缺失（required 冻结不可单独删登记）' })
      }
    }
    for (const id of baseline.requiredEvents) {
      if (!registryEvents.has(id)) {
        violations.push({ registry: 'event-consumers.registry.json', entry: id, rule: 'required-frozen', message: 'baseline required 事件在登记簿中缺失（required 冻结不可单独删登记）' })
      }
    }
    for (const id of registryTables) {
      if (!baseline.requiredResources.includes(id)) {
        violations.push({ registry: 'resource-lifecycle.registry.json', entry: id, rule: 'required-frozen', message: '登记簿新增资源未冻结进 baseline.requiredResources' })
      }
    }
    for (const id of registryEvents) {
      if (!baseline.requiredEvents.includes(id)) {
        violations.push({ registry: 'event-consumers.registry.json', entry: id, rule: 'required-frozen', message: '登记簿新增事件未冻结进 baseline.requiredEvents' })
      }
    }
  }
  checkMachineRefs(resources.entries, events.entries, repoRoot, inventory, violations)
  return {
    violations,
    counts: { resources: resources.entries.length, events: events.entries.length },
    inventoryCounts: {
      tables: inventory.tables.length,
      eventTypes: inventory.events.length,
      unsupportedSql: inventory.unsupportedSql.length,
      unresolvedJava: inventory.unresolvedJava.length,
    },
    unresolved: inventory.unresolvedJava.length,
    exempted: baseline
      ? { legacyResources: baseline.legacyResources.length, legacyEvents: baseline.legacyEvents.length, dynamicEventSites: baseline.dynamicEventSites.length }
      : undefined,
  }
}

async function main(): Promise<void> {
  const repoRoot = process.cwd()
  const registryRoot = process.env.LIFECYCLE_REGISTRY_ROOT
    ?? (process.argv.includes('--registry-root')
      ? process.argv[process.argv.indexOf('--registry-root') + 1]
      : path.join('tests', 'contracts'))
  const { violations, counts } = checkContracts(repoRoot, registryRoot)
  if (violations.length > 0) {
    console.error(`生命周期接入门禁失败（${violations.length} 项违规）：`)
    for (const violation of violations) {
      console.error(` - [${violation.registry}] ${violation.entry} 违反 ${violation.rule}：${violation.message}`)
    }
    process.exit(1)
  }
  console.log(`生命周期接入门禁通过：${counts.resources} 资源 + ${counts.events} 事件已登记且引用真实`)
}

if (process.argv[1] && path.resolve(process.argv[1]).endsWith('check-lifecycle-contracts.ts')) {
  await main()
}
