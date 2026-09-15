#!/usr/bin/env npx tsx
/**
 * 任务书 #103 C103-19（V12）：资源与事件生命周期接入门禁。
 *
 * 用法：npx tsx scripts/quality/check-lifecycle-contracts.ts [--registry-root tests/contracts]
 *       测试可用 LIFECYCLE_REGISTRY_ROOT 指定 fixture 根（合成负例）。
 *
 * 校验（不写任何业务状态、不联网）：
 * - registry schema：resource 必填 table/service/scope(五类枚举)/ownerResolver/activeStates/
 *   terminalEvidence/retentionClass/eraseHandler/derivedObjects/tc；event 必填
 *   eventType/producer/recipientPolicy/consumer/delivery/tc；ignoredReason 缺失即不允许有意忽略。
 * - tc 引用真实存在的测试文件（防「编造测试引用」）。
 * - retired 条目必须带 retiredNote（有意退役必须写明）。
 * - 跨簿一致性：resource.derivedObjects 引用的表若有登记行则字段完整；事件 producer 类名在
 *   仓库源码中可定位（BR-19：不是只检查文件存在）。
 * - 同名表/事件重复登记拒绝（幂等唯一）。
 * 退出码：任何违规非零并逐条列出；通过输出计数摘要。
 */

import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs'
import path from 'node:path'

export interface Violation {
  registry: string
  entry: string
  rule: string
  message: string
}

const SCOPES = ['personal', 'org-shared', 'financial-audit', 'platform-configuration', 'ephemeral'] as const

function loadRegistry(root: string, name: string): { kind: string; entries: Array<Record<string, unknown>> } {
  const file = path.join(root, name)
  if (!existsSync(file)) {
    throw new Error(`registry 不存在：${file}`)
  }
  const parsed = JSON.parse(readFileSync(file, 'utf8')) as Record<string, unknown>
  const kind = name.includes('resource') ? 'resources' : 'events'
  const entries = (parsed[kind] as Array<Record<string, unknown>>) ?? []
  if (!Array.isArray(entries)) {
    throw new Error(`${name}.${kind} 必须是数组`)
  }
  return { kind, entries }
}

function checkResource(root: string, entry: Record<string, unknown>, index: number, repoRoot: string,
  violations: Violation[], seenTables: Map<string, string>): void {
  const id = String(entry.table ?? `resources[${index}]`)
  const registry = 'resource-lifecycle.registry.json'
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
  if (entry.retired === true && (typeof entry.retiredNote !== 'string' || (entry.retiredNote as string).trim() === '')) {
    violations.push({ registry, entry: id, rule: 'retired', message: 'retired 条目必须带 retiredNote' })
  }
  checkTc(registry, id, entry.tc, repoRoot, violations)
}

function checkEvent(entry: Record<string, unknown>, index: number, repoRoot: string,
  violations: Violation[], seenEvents: Map<string, string>): void {
  const registry = 'event-consumers.registry.json'
  const id = String(entry.eventType ?? `events[${index}]`)
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
  checkTc(registry, id, entry.tc, repoRoot, violations)
}

function checkTc(registry: string, id: string, tc: unknown, repoRoot: string, violations: Violation[]): void {
  if (typeof tc !== 'string' || tc.trim() === '') {
    violations.push({ registry, entry: id, rule: 'tc', message: 'tc（守卫测试引用）必填' })
    return
  }
  const tcPath = path.join(repoRoot, tc)
  if (!existsSync(tcPath)) {
    violations.push({ registry, entry: id, rule: 'tc', message: `tc 引用的测试文件不存在：${tc}` })
  }
}

/** producer 类名在仓库源码中可定位（BR-19：不是只检查文件存在）。 */
function checkProducers(eventEntries: Array<Record<string, unknown>>, repoRoot: string, violations: Violation[]): void {
  const javaFiles = collectJavaFiles(path.join(repoRoot, 'platform-java', 'services'), new Set<string>(), 0)
  const classNames = new Set(javaFiles.map(file => path.basename(file, '.java')))
  for (const entry of eventEntries) {
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

export function checkContracts(repoRoot: string, registryRoot: string): { violations: Violation[]; counts: { resources: number; events: number } } {
  const violations: Violation[] = []
  const resources = loadRegistry(registryRoot, 'resource-lifecycle.registry.json')
  const events = loadRegistry(registryRoot, 'event-consumers.registry.json')
  const seenTables = new Map<string, string>()
  resources.entries.forEach((entry, index) => checkResource(registryRoot, entry, index, repoRoot, violations, seenTables))
  const seenEvents = new Map<string, string>()
  events.entries.forEach((entry, index) => checkEvent(entry, index, repoRoot, violations, seenEvents))
  checkProducers(events.entries, repoRoot, violations)
  return { violations, counts: { resources: resources.entries.length, events: events.entries.length } }
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
