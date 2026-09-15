/**
 * 任务书 #103 C103-19（V12）：生命周期登记门禁合约测试。
 * 真实登记簿必须通过；合成负例（漏字段/悬空 TC/非法枚举/缺 retiredNote/重复登记/producer 不可定位）
 * 必须逐条失败——证明新增持久资源/事件漏登记有可复现失败。
 */
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { expect, test } from 'vitest'
import { checkContracts } from '../../scripts/quality/check-lifecycle-contracts'

const REPO_ROOT = process.cwd()
const REAL_ROOT = path.join('tests', 'contracts')

const RESOURCE_OK = {
  table: 't_x', service: 'svc', scope: 'personal', ownerResolver: 'account_id',
  activeStates: ['running'], terminalEvidence: 'done', retentionClass: 'erase-with-owner',
  eraseHandler: 'handler', derivedObjects: [], tc: 'tests/contracts/lifecycle-registration.contract.test.ts',
}

const EVENT_OK = {
  eventType: 'EvtX', producer: 'ApplicationLifecycleService', recipientPolicy: 'R', consumer: 'c',
  delivery: 'inbox+notification+mail 同事务', tc: 'tests/contracts/lifecycle-registration.contract.test.ts',
}

function fixtureRoot(resources: unknown, events: unknown): string {
  const dir = mkdtempSync(path.join(tmpdir(), 'lifecycle-reg-'))
  mkdirSync(dir, { recursive: true })
  writeFileSync(path.join(dir, 'resource-lifecycle.registry.json'), JSON.stringify({ resources }))
  writeFileSync(path.join(dir, 'event-consumers.registry.json'), JSON.stringify({ events }))
  return dir
}

test('TC103-19-01 真实登记簿通过：全部条目 schema 合法、tc 引用真实、producer 可定位', () => {
  const { violations, counts } = checkContracts(REPO_ROOT, REAL_ROOT)
  expect(violations).toEqual([])
  expect(counts.resources).toBeGreaterThanOrEqual(17)
  expect(counts.events).toBeGreaterThanOrEqual(30)
})

test('TC103-19-01/E13 合成负例：缺 ownerResolver/非法 scope/空 activeStates 逐条失败', () => {
  const bad = { ...RESOURCE_OK } as Record<string, unknown>
  delete bad.ownerResolver
  const badScope = { ...RESOURCE_OK, scope: 'public' }
  const noStates = { ...RESOURCE_OK, activeStates: [] }
  const { violations } = checkContracts(REPO_ROOT, fixtureRoot([bad, badScope, noStates], [EVENT_OK]))
  const rules = violations.map(v => v.rule)
  expect(rules).toContain('schema')
  expect(violations.some(v => v.message.includes('ownerResolver'))).toBe(true)
  expect(violations.some(v => v.message.includes('五类枚举'))).toBe(true)
  expect(violations.some(v => v.message.includes('activeStates'))).toBe(true)
})

test('TC103-19-01 悬空 TC 引用失败（防编造测试引用）', () => {
  const dangling = { ...RESOURCE_OK, tc: 'platform-java/no/such/Test.java' }
  const { violations } = checkContracts(REPO_ROOT, fixtureRoot([dangling], [EVENT_OK]))
  expect(violations.some(v => v.rule === 'tc' && v.message.includes('不存在'))).toBe(true)
})

test('retired 无说明失败；有 retiredNote 通过', () => {
  const silent = { ...RESOURCE_OK, retired: true }
  const noted = { ...RESOURCE_OK, retired: true, retiredNote: '随 #49 邀请流下线退役' }
  const first = checkContracts(REPO_ROOT, fixtureRoot([silent], [EVENT_OK]))
  expect(first.violations.some(v => v.rule === 'retired')).toBe(true)
  const second = checkContracts(REPO_ROOT, fixtureRoot([noted], [EVENT_OK]))
  expect(second.violations).toEqual([])
})

test('TC103-19-02/E03 重复登记（同名表/同名事件）拒绝；同输入重跑确定', () => {
  const dup = [RESOURCE_OK, { ...RESOURCE_OK }]
  const first = checkContracts(REPO_ROOT, fixtureRoot(dup, [EVENT_OK, { ...EVENT_OK }]))
  expect(first.violations.filter(v => v.rule === 'unique')).toHaveLength(2)
  const second = checkContracts(REPO_ROOT, fixtureRoot(dup, [EVENT_OK, { ...EVENT_OK }]))
  expect(second.violations).toEqual(first.violations)
})

test('TC103-19-05/BR-19 producer 类名不可定位失败（不只查文件存在）', () => {
  const ghost = { ...EVENT_OK, producer: 'GhostProducerClass' }
  const { violations } = checkContracts(REPO_ROOT, fixtureRoot([RESOURCE_OK], [ghost]))
  expect(violations.some(v => v.rule === 'producer-exists')).toBe(true)
})

test('TC103-19-01/E08 空登记输出空计数不误报（元信息如实）', () => {
  const { violations, counts } = checkContracts(REPO_ROOT, fixtureRoot([], []))
  expect(violations).toEqual([])
  expect(counts).toEqual({ resources: 0, events: 0 })
})
