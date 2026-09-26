// @vitest-environment node
/**
 * 任务书 #103 C103-19（V12）→ #104 C104-07（D06/§6.3）：生命周期登记门禁合约测试。
 * 真实登记簿+真实清单+基线必须整体通过；合成负例（漏字段/悬空 TC/非法枚举/缺 retiredNote/
 * 重复登记/producer 漂移/consumer 方法缺失/tc 越界/derived 悬空/required 冻结被删/
 * 豁免逃逸/retired 无证据）必须逐条失败——证明漏登记有可复现失败，空登记不再恒通过。
 */
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { afterEach, expect, test } from 'vitest'
import { checkContracts } from '../../scripts/quality/check-lifecycle-contracts'
import type { CheckOptions } from '../../scripts/quality/check-lifecycle-contracts'
import {
  buildRealInventory, loadBaseline, normalizedSourceDigest,
} from '../../scripts/quality/lifecycle-inventory'
import type { LifecycleBaseline, RealInventory } from '../../scripts/quality/lifecycle-inventory'

const REPO_ROOT = process.cwd()
const REAL_ROOT = path.join('tests', 'contracts')
const SELF = 'tests/contracts/lifecycle-registration.contract.test.ts'

let tempDirs: string[] = []

afterEach(() => {
  for (const dir of tempDirs) rmSync(dir, { recursive: true, force: true })
  tempDirs = []
})

function makeTempDir(): string {
  const dir = mkdtempSync(path.join(tmpdir(), 'lifecycle-reg-'))
  tempDirs.push(dir)
  return dir
}

// ---------- 合成清单/基线 fixture（v2 注入路径） ----------

const PRODUCER_PATH = 'platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/compliance/PersonalDataErasureService.java'
const CONSUMER_DECL = { path: 'platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/compliance/PersonalDataErasureRepository.java', symbol: 'Consumer#handleEvt()' }

function syntheticInventory(): RealInventory {
  return {
    tables: [
      { id: 'public.t_x', sources: [{ service: 'svc', path: 'db/V1.sql', version: '1' }] },
      { id: 'public.t_derived', sources: [{ service: 'svc', path: 'db/V1.sql', version: '1' }] },
    ],
    dropped: [{ id: 'public.t_old', sources: [{ service: 'svc', path: 'db/V2.sql', version: '2' }] }],
    unsupportedSql: [],
    events: [{ eventType: 'EvtX', sites: [{ path: PRODUCER_PATH, symbol: 'Foo#emit()', eventTypes: ['EvtX'] }] }],
    unresolvedJava: [],
    declarations: [
      CONSUMER_DECL,
      { path: PRODUCER_PATH, symbol: 'Foo#emit()' },
    ],
  }
}

function syntheticBaseline(): LifecycleBaseline {
  return {
    version: 1,
    baselineHead: 'fixture-head',
    requiredResources: ['public.t_x'],
    requiredEvents: ['EvtX'],
    legacyResources: [{
      id: 'public.t_derived',
      anchor: { service: 'svc', path: SELF, version: '1' },
      sourceSha256: normalizedSourceDigest(readFileSync(path.join(REPO_ROOT, SELF), 'utf8')),
      reason: 'fixture 存量表。', followUp: 'fixture。',
    }],
    legacyEvents: [],
    dynamicEventSites: [],
  }
}

const RESOURCE_OK = {
  table: 't_x', service: 'svc', scope: 'personal', ownerResolver: 'account_id',
  activeStates: ['running'], terminalEvidence: 'done', retentionClass: 'erase-with-owner',
  eraseHandler: 'handler', derivedObjects: [], derivedRefs: [], tc: SELF,
}

const EVENT_OK = {
  eventType: 'EvtX', producer: 'ApplicationLifecycleService', recipientPolicy: 'R', consumer: 'c',
  delivery: 'inbox', tc: SELF,
  producerRefs: [{ path: PRODUCER_PATH, symbol: 'Foo#emit()' }],
  consumerRefs: [{
    source: CONSUMER_DECL,
    disposition: 'handle', tc: SELF,
  }],
}

function consumerSourceFile(tempRepo: string): string {
  const file = path.join(tempRepo, CONSUMER_DECL.path)
  mkdirSync(path.dirname(file), { recursive: true })
  writeFileSync(file, 'public class Consumer { void handleEvt() {} }\n')
  return file
}

function fixtureRoot(resources: unknown, events: unknown, baseline?: unknown): string {
  const dir = makeTempDir()
  writeFileSync(path.join(dir, 'resource-lifecycle.registry.json'), JSON.stringify({ version: 2, resources }))
  writeFileSync(path.join(dir, 'event-consumers.registry.json'), JSON.stringify({ version: 2, events }))
  if (baseline) {
    writeFileSync(path.join(dir, 'lifecycle-inventory.baseline.json'), JSON.stringify(baseline))
  }
  return dir
}

function optionsFor(overrides: Partial<CheckOptions> = {}): CheckOptions {
  const inventory = syntheticInventory()
  return { inventory, baseline: syntheticBaseline(), ...overrides }
}

// ---------- 真实簿整体通过 ----------

test('TC104-07-07/AC-07 真实登记簿+真实清单+基线整体通过（含 inventoryCounts/exempted 摘要）', { timeout: 120_000 }, () => {
  const result = checkContracts(REPO_ROOT, REAL_ROOT)
  expect(result.violations).toEqual([])
  expect(result.counts).toEqual({ resources: 49, events: 31 })
  expect(result.inventoryCounts).toMatchObject({ unsupportedSql: 0, unresolvedJava: 3 })
  expect(result.exempted).toEqual({ legacyResources: 187, legacyEvents: 100, dynamicEventSites: 3 })
})

// ---------- v1 既有 schema 负例（合成清单注入后仍逐条失败） ----------

test('C103-19 负例回归：缺 ownerResolver/非法 scope/空 activeStates/悬空 tc/重复登记逐条失败', () => {
  const bad = { ...RESOURCE_OK } as Record<string, unknown>
  delete bad.ownerResolver
  const badScope = { ...RESOURCE_OK, scope: 'public' }
  const noStates = { ...RESOURCE_OK, activeStates: [] }
  const dangling = { ...RESOURCE_OK, table: 't_x2', tc: 'platform-java/no/such/Test.java' }
  const dup = [RESOURCE_OK, { ...RESOURCE_OK }]
  const { violations } = checkContracts(REPO_ROOT, fixtureRoot([bad, badScope, noStates, dangling, ...dup],
    [{ ...EVENT_OK }, { ...EVENT_OK }]), optionsFor())
  const rules = violations.map((violation) => violation.rule)
  expect(rules).toContain('schema')
  expect(rules).toContain('unique')
  expect(rules).toContain('tc')
})

// ---------- TC104-07-01 真实整条删除 ----------

test('TC104-07-01 复制真实簿删除 ai_run / DeliveryDeadlineExpiring：均失败，添无关条目不能补数', { timeout: 120_000 }, () => {
  function withRealRegistries(mutate: (dir: string) => void): string {
    const dir = makeTempDir()
    writeFileSync(path.join(dir, 'resource-lifecycle.registry.json'),
      readFileSync(path.join(REPO_ROOT, 'tests/contracts/resource-lifecycle.registry.json')))
    writeFileSync(path.join(dir, 'event-consumers.registry.json'),
      readFileSync(path.join(REPO_ROOT, 'tests/contracts/event-consumers.registry.json')))
    writeFileSync(path.join(dir, 'lifecycle-inventory.baseline.json'),
      readFileSync(path.join(REPO_ROOT, 'tests/contracts/lifecycle-inventory.baseline.json')))
    mutate(dir)
    return dir
  }
  const deletedResource = withRealRegistries((dir) => {
    const parsed = JSON.parse(readFileSync(path.join(dir, 'resource-lifecycle.registry.json'), 'utf8'))
    parsed.resources = parsed.resources.filter((entry: { table: string }) => entry.table !== 'ai_run')
    // 添无关条目试图补数：
    parsed.resources.push({ ...parsed.resources[0], table: 'unrelated_fill' })
    writeFileSync(path.join(dir, 'resource-lifecycle.registry.json'), JSON.stringify(parsed))
  })
  const first = checkContracts(REPO_ROOT, deletedResource, {
    inventory: buildRealInventory(REPO_ROOT),
    baseline: loadBaseline(path.join(REPO_ROOT, 'tests/contracts/lifecycle-inventory.baseline.json')),
  })
  expect(first.violations.some((violation) => violation.rule === 'required-frozen'
    && violation.entry === 'public.ai_run')).toBe(true)
  expect(first.violations.some((violation) => violation.entry.includes('unrelated_fill'))).toBe(true)

  const deletedEvent = withRealRegistries((dir) => {
    const parsed = JSON.parse(readFileSync(path.join(dir, 'event-consumers.registry.json'), 'utf8'))
    parsed.events = parsed.events.filter((entry: { eventType: string }) => entry.eventType !== 'DeliveryDeadlineExpiring')
    parsed.events.push({ ...parsed.events[0], eventType: 'UnrelatedFillEvent' })
    writeFileSync(path.join(dir, 'event-consumers.registry.json'), JSON.stringify(parsed))
  })
  const second = checkContracts(REPO_ROOT, deletedEvent, {
    inventory: buildRealInventory(REPO_ROOT),
    baseline: loadBaseline(path.join(REPO_ROOT, 'tests/contracts/lifecycle-inventory.baseline.json')),
  })
  expect(second.violations.some((violation) => violation.rule === 'required-frozen'
    && violation.entry === 'DeliveryDeadlineExpiring')).toBe(true)
})

// ---------- TC104-07-02 新资源/事件漏记 ----------

test('TC104-07-02 临时仓库真实新增表/事件不入簿失败；补登记+冻结后通过；空簿对非空源失败', () => {
  const tempRepo = makeTempDir()
  mkdirSync(path.join(tempRepo, 'db'), { recursive: true })
  writeFileSync(path.join(tempRepo, 'db', 'V1__new.sql'),
    'CREATE TABLE public.brand_new (id text);\nCREATE TABLE public.t_x (id text);')
  const inventory: RealInventory = {
    tables: [
      { id: 'public.brand_new', sources: [{ service: 'svc', path: 'db/V1__new.sql', version: '1' }] },
      { id: 'public.t_x', sources: [{ service: 'svc', path: 'db/V1__new.sql', version: '1' }] },
      { id: 'public.t_derived', sources: [{ service: 'svc', path: 'db/V1__new.sql', version: '1' }] },
    ],
    dropped: [], unsupportedSql: [],
    events: [
      { eventType: 'EvtX', sites: [{ path: PRODUCER_PATH, symbol: 'Foo#emit()', eventTypes: ['EvtX'] }] },
      { eventType: 'FreshEvent', sites: [{ path: PRODUCER_PATH, symbol: 'Foo#fresh()', eventTypes: ['FreshEvent'] }] },
    ],
    unresolvedJava: [],
    declarations: [
      CONSUMER_DECL,
      { path: PRODUCER_PATH, symbol: 'Foo#emit()' },
      { path: PRODUCER_PATH, symbol: 'Foo#fresh()' },
    ],
  }
  const missing = checkContracts(REPO_ROOT, fixtureRoot([RESOURCE_OK], [EVENT_OK]),
    { inventory, baseline: syntheticBaseline() })
  expect(missing.violations.some((violation) => violation.rule === 'resource-unregistered'
    && violation.entry.includes('public.brand_new'))).toBe(true)
  expect(missing.violations.some((violation) => violation.rule === 'event-unregistered'
    && violation.entry.includes('FreshEvent'))).toBe(true)

  // 合法补登记（登记 + 冻结进 required）后通过。
  const registered = checkContracts(REPO_ROOT, fixtureRoot(
    [RESOURCE_OK, { ...RESOURCE_OK, table: 'brand_new' }],
    [EVENT_OK, { ...EVENT_OK, eventType: 'FreshEvent', producerRefs: [{ path: PRODUCER_PATH, symbol: 'Foo#fresh()' }], consumerRefs: EVENT_OK.consumerRefs }],
    { ...syntheticBaseline(), requiredResources: ['public.t_x', 'public.brand_new'], requiredEvents: ['EvtX', 'FreshEvent'] }),
    { inventory })
  expect(registered.violations).toEqual([])

  // 空登记配非空源：失败（修正「空登记总通过」）。
  const empty = checkContracts(REPO_ROOT, fixtureRoot([], [], syntheticBaseline()), { inventory })
  expect(empty.violations.some((violation) => violation.rule === 'resource-unregistered')).toBe(true)
  expect(empty.violations.some((violation) => violation.rule === 'event-unregistered')).toBe(true)
})

// ---------- TC104-07-03 引用错配 ----------

test('TC104-07-03 producer 产另一事件/consumer 方法不存在/tc 越界或非测试分别失败', () => {
  const tempRepo = makeTempDir()
  consumerSourceFile(tempRepo)
  const wrongProducer = { ...EVENT_OK, producerRefs: [{ path: PRODUCER_PATH, symbol: 'Foo#otherEventOnly()' }] }
  const ghostConsumer = {
    ...EVENT_OK,
    consumerRefs: [{ ...EVENT_OK.consumerRefs[0], source: { path: CONSUMER_DECL.path, symbol: 'Consumer#missingMethod()' } }],
  }
  const nonTestTc = { ...EVENT_OK, tc: 'scripts/quality/check-lifecycle-contracts.ts' }
  const escapingTc = { ...RESOURCE_OK, table: 't_x', tc: '../outside/repo/Test.java' }
  const { violations } = checkContracts(tempRepo,
    fixtureRoot([escapingTc], [wrongProducer, ghostConsumer, nonTestTc]), optionsFor())
  expect(violations.some((violation) => violation.rule === 'producer-mismatch')).toBe(true)
  expect(violations.some((violation) => violation.rule === 'consumer-method')).toBe(true)
  expect(violations.some((violation) => violation.rule === 'tc'
    && (violation.message.includes('非测试') || violation.message.includes('不存在')))).toBe(true)
})

// ---------- TC104-07-04 派生类型 ----------

test('TC104-07-04 derived 表不存在失败；合法真实表与纯 note 通过', () => {
  const dangling = { ...RESOURCE_OK, derivedRefs: [{ kind: 'table', id: 'public.ghost_derived' }] }
  const legalTable = { ...RESOURCE_OK, derivedRefs: [{ kind: 'table', id: 'public.t_derived' }] }
  const noteOnly = { ...RESOURCE_OK, derivedRefs: [{ kind: 'note', text: '结果资产引用' }] }
  const result = checkContracts(REPO_ROOT, fixtureRoot([dangling, legalTable, noteOnly], [EVENT_OK]), optionsFor())
  expect(result.violations.some((violation) => violation.rule === 'derived-table'
    && violation.message.includes('ghost_derived'))).toBe(true)
  expect(result.violations.filter((violation) => violation.rule === 'derived-table')).toHaveLength(1)
})

// ---------- TC104-07-05 忽略与退役 ----------

test('TC104-07-05 ignore 缺 reason 失败；retired 仍活跃失败、有真实 DROP 证据通过', () => {
  const silentIgnore = {
    ...EVENT_OK, ignoredReason: undefined,
    consumerRefs: [{ ...EVENT_OK.consumerRefs[0], disposition: 'ignore' }],
  }
  const activeRetired = { ...RESOURCE_OK, retired: true, retiredNote: '假退役' }
  const droppedRetired = { ...RESOURCE_OK, table: 't_old', retired: true, retiredNote: 'V2 已 DROP' }
  const result = checkContracts(REPO_ROOT, fixtureRoot([activeRetired, droppedRetired], [silentIgnore]), optionsFor())
  expect(result.violations.some((violation) => violation.rule === 'ignored-reason')).toBe(true)
  expect(result.violations.some((violation) => violation.rule === 'retired-evidence'
    && violation.entry === 't_x')).toBe(true)
  expect(result.violations.some((violation) => violation.rule === 'retired-evidence'
    && violation.entry === 't_old')).toBe(false)
})

// ---------- TC104-07-06 豁免逃逸与不写基线 ----------

test('TC104-07-06 required 转 legacy/重复豁免/摘要漂移经 checkContracts 入口失败；运行不改基线文件', { timeout: 120_000 }, () => {
  const inventory = syntheticInventory()
  const overlap = syntheticBaseline()
  overlap.legacyResources.push({
    id: 'public.t_x', anchor: { service: 'svc', path: SELF, version: '1' },
    sourceSha256: '0'.repeat(64), reason: '试图豁免正式登记。', followUp: 'x',
  })
  const first = checkContracts(REPO_ROOT, fixtureRoot([RESOURCE_OK], [EVENT_OK]), { inventory, baseline: overlap })
  expect(first.violations.some((violation) => violation.rule === 'overlap')).toBe(true)

  const drifted = syntheticBaseline()
  drifted.legacyResources[0].sourceSha256 = 'f'.repeat(64)
  const second = checkContracts(REPO_ROOT, fixtureRoot([RESOURCE_OK], [EVENT_OK]), { inventory, baseline: drifted })
  expect(second.violations.some((violation) => violation.rule === 'digest-drift')).toBe(true)

  // 正常真实路径不写基线（quality:lifecycle 绝不写基线）。
  const baselinePath = path.join(REPO_ROOT, 'tests/contracts/lifecycle-inventory.baseline.json')
  const before = readFileSync(baselinePath, 'utf8')
  checkContracts(REPO_ROOT, REAL_ROOT)
  expect(readFileSync(baselinePath, 'utf8')).toBe(before)
})

// ---------- TC104-07-07 坏 JSON 有上下文失败 ----------

test('TC104-07-07 坏 JSON 明确失败且带文件上下文', () => {
  const dir = makeTempDir()
  writeFileSync(path.join(dir, 'resource-lifecycle.registry.json'), '{broken json')
  writeFileSync(path.join(dir, 'event-consumers.registry.json'), JSON.stringify({ events: [] }))
  expect(() => checkContracts(REPO_ROOT, dir, optionsFor()))
    .toThrow(/resource-lifecycle\.registry\.json/)
})

test('TC104-07-05 补充：retired 有 DROP 证据且 dropped 表不在活跃清单——登记根文件集完整性', () => {
  // fixture 根不含 baseline 时给合成注入仍可校验；真实 CLI 路径缺基线文件必须失败。
  const dir = makeTempDir()
  writeFileSync(path.join(dir, 'resource-lifecycle.registry.json'), JSON.stringify({ resources: [RESOURCE_OK] }))
  writeFileSync(path.join(dir, 'event-consumers.registry.json'), JSON.stringify({ events: [EVENT_OK] }))
  const result = checkContracts(REPO_ROOT, dir, { inventory: syntheticInventory() })
  expect(result.violations.some((violation) => violation.rule === 'baseline')).toBe(true)
  expect(existsSync(path.join(REPO_ROOT, 'tests/contracts/lifecycle-inventory.baseline.json'))).toBe(true)
})

// ---------- 任务书 #106 C106-04（TC106-04-01～05）：v2 严格结构与 AST 声明引用 ----------

function registryRootWith(overrides: {
  resourceVersion?: unknown
  eventVersion?: unknown
  resources?: unknown
  events?: unknown
  baseline?: unknown
}): string {
  const dir = makeTempDir()
  const resourceVersion = 'resourceVersion' in overrides ? overrides.resourceVersion : 2
  const eventVersion = 'eventVersion' in overrides ? overrides.eventVersion : 2
  writeFileSync(path.join(dir, 'resource-lifecycle.registry.json'),
    JSON.stringify({ version: resourceVersion, resources: overrides.resources ?? [RESOURCE_OK] }))
  writeFileSync(path.join(dir, 'event-consumers.registry.json'),
    JSON.stringify({ version: eventVersion, events: overrides.events ?? [EVENT_OK] }))
  writeFileSync(path.join(dir, 'lifecycle-inventory.baseline.json'),
    JSON.stringify(overrides.baseline ?? syntheticBaseline()))
  return dir
}

test.each([undefined, '', 7])('#106 复核：handler.symbol=%s 不能放过', symbol => {
  const resource = { ...RESOURCE_OK, derivedRefs: [{ kind: 'object-store', handler: { path: CONSUMER_DECL.path, symbol } }] }
  const result = checkContracts(REPO_ROOT, fixtureRoot([resource], [EVENT_OK]), optionsFor())
  expect(result.violations.some(v => v.rule === 'derived-handler' || v.rule === 'schema')).toBe(true)
})

test('#106 复核：null event 返回可定位违规，不能抛 TypeError', () => {
  const result = checkContracts(REPO_ROOT, fixtureRoot([RESOURCE_OK], [null]), optionsFor())
  expect(result.violations.some(v => v.rule === 'schema' && v.entry === 'events[0]')).toBe(true)
})

test('#106 复核：tests 下 JSON fixture 不能冒充测试文件', () => {
  const resource = { ...RESOURCE_OK, tc: 'tests/contracts/resource-lifecycle.registry.json' }
  const result = checkContracts(REPO_ROOT, fixtureRoot([resource], [EVENT_OK]), optionsFor())
  expect(result.violations.some(v => v.rule === 'tc')).toBe(true)
})

test('#106 复核：源码符号存在也不能用出仓库的符号链接放行', () => {
  const repo = makeTempDir()
  const external = path.join(makeTempDir(), 'Consumer.java')
  writeFileSync(external, 'class Consumer { void handleEvt() {} }')
  const source = path.join(repo, CONSUMER_DECL.path)
  mkdirSync(path.dirname(source), { recursive: true })
  symlinkSync(external, source)
  const resource = { ...RESOURCE_OK, derivedRefs: [{ kind: 'object-store', handler: CONSUMER_DECL }] }
  const result = checkContracts(repo, fixtureRoot([resource], [EVENT_OK]), optionsFor())
  expect(result.violations.some(v => v.rule === 'consumer-source')).toBe(true)
  expect(result.violations.some(v => v.rule === 'derived-handler')).toBe(true)
})

test('TC106-04-01/F06：顶层 version 缺失/1/字符串/未知均失败（不降 v1、不按条目推断）；删除全部机器引用也失败', () => {
  for (const bad of [undefined, 1, '2', 3]) {
    const { violations } = checkContracts(REPO_ROOT,
      registryRootWith({ resourceVersion: bad, eventVersion: bad }), optionsFor())
    expect(violations.some((violation) => violation.rule === 'version'), `version=${String(bad)}`).toBe(true)
  }
  // 保留 version=2 但删除全部机器引用（v2 缺字段不再默默放行）。
  const strippedResources = { ...RESOURCE_OK } as Record<string, unknown>
  delete strippedResources.derivedRefs
  const strippedEvents = { ...EVENT_OK } as Record<string, unknown>
  delete strippedEvents.producerRefs
  delete strippedEvents.consumerRefs
  const { violations } = checkContracts(REPO_ROOT,
    registryRootWith({ resources: [strippedResources], events: [strippedEvents] }), optionsFor())
  expect(violations.some((violation) => violation.rule === 'schema' && violation.message.includes('derivedRefs'))).toBe(true)
  expect(violations.some((violation) => violation.rule === 'schema' && violation.message.includes('producerRefs'))).toBe(true)
  expect(violations.some((violation) => violation.rule === 'schema' && violation.message.includes('consumerRefs'))).toBe(true)
})

test('TC106-04-02：consumerRefs.tc 缺失/空/不存在/目录/生产路径/绝对路径/符号链接越界分别违规', () => {
  const tempRepo = makeTempDir()
  // 把 SELF 复制到 tempRepo 同路径：entry 级 tc 合法，失败只能来自 consumerRefs.tc 探针。
  mkdirSync(path.join(tempRepo, 'tests', 'contracts'), { recursive: true })
  writeFileSync(path.join(tempRepo, SELF), readFileSync(path.join(REPO_ROOT, SELF)))
  mkdirSync(path.join(tempRepo, 'tests', 'contracts', 'dir-tc'), { recursive: true })
  const outsideDir = mkdtempSync(path.join(tmpdir(), 'lifecycle-tc-outside-'))
  tempDirs.push(outsideDir)
  writeFileSync(path.join(outsideDir, 'outside.test.ts'), 'export {}')
  symlinkSync(path.join(outsideDir, 'outside.test.ts'), path.join(tempRepo, 'tests', 'contracts', 'link-tc.test.ts'))
  const consumerWithTc = (tc: unknown): unknown => ({
    ...EVENT_OK,
    consumerRefs: [{ ...EVENT_OK.consumerRefs[0], tc }],
  })
  const probes: Array<[string, unknown]> = [
    ['缺失', undefined],
    ['空串', ''],
    ['不存在', 'tests/contracts/no-such-file.test.ts'],
    ['目录', 'tests/contracts/dir-tc'],
    ['生产路径', 'scripts/quality/check-lifecycle-contracts.ts'],
    ['绝对路径', '/etc/hosts'],
    ['符号链接越界', 'tests/contracts/link-tc.test.ts'],
  ]
  const { violations } = checkContracts(tempRepo, registryRootWith({
    events: probes.map(([label, tc]) => {
      const event = consumerWithTc(tc) as { eventType: string }
      return { ...event, eventType: `EvtTc_${label}` }
    }),
  }), optionsFor())
  const tcViolations = violations.filter((violation) => violation.rule === 'tc')
  for (const [label] of probes) {
    expect(tcViolations.some((violation) => violation.entry === `EvtTc_${label}`), `probe=${label}`).toBe(true)
  }
})

test('TC106-04-03：假类/错误重载的 consumer 符号失败（AST 声明核验）；真实属主+参数通过', () => {
  const fakeClass = {
    ...EVENT_OK,
    eventType: 'EvtFake',
    consumerRefs: [{ ...EVENT_OK.consumerRefs[0], source: { path: CONSUMER_DECL.path, symbol: 'Ghost#handleEvt()' } }],
  }
  const wrongOverload = {
    ...EVENT_OK,
    eventType: 'EvtOverload',
    consumerRefs: [{ ...EVENT_OK.consumerRefs[0], source: { path: CONSUMER_DECL.path, symbol: 'Consumer#handleEvt(String)' } }],
  }
  // producerRefs 对应新事件类型须给真实生产点（合成清单 sites 固定 Foo#emit() → EvtX）：
  // 此两例 eventType 不同，会另有 event-real/producer 违规，与本断言无关。
  const real = { ...EVENT_OK, eventType: 'EvtReal', producerRefs: EVENT_OK.producerRefs }
  const { violations } = checkContracts(REPO_ROOT, registryRootWith({ events: [fakeClass, wrongOverload, real] }),
    optionsFor())
  expect(violations.some((violation) => violation.rule === 'consumer-method'
    && violation.entry === 'EvtFake')).toBe(true)
  expect(violations.some((violation) => violation.rule === 'consumer-method'
    && violation.entry === 'EvtOverload')).toBe(true)
  expect(violations.some((violation) => violation.rule === 'consumer-method'
    && violation.entry === 'EvtReal')).toBe(false)
})

test('TC106-04-04：derivedRefs 坏条目/假 handler、null 条目明确失败（不吞不崩）', () => {
  const badEntries = {
    ...RESOURCE_OK, table: 't_x',
    derivedRefs: [null, { kind: 'table' }, { kind: 'table', id: 'public.ghost_derived' },
      { kind: 'object-store', handler: { path: 'scripts/quality/no-such-handler.ts', symbol: 'X#y()' } }],
  }
  const { violations } = checkContracts(REPO_ROOT, registryRootWith({
    resources: [badEntries, null],
  }), optionsFor())
  expect(violations.some((violation) => violation.rule === 'schema' && violation.entry === 't_x'
    && violation.message.includes('derivedRefs 条目'))).toBe(true)
  expect(violations.some((violation) => violation.rule === 'derived-table'
    && violation.message.includes('ghost_derived'))).toBe(true)
  expect(violations.some((violation) => violation.rule === 'derived-handler')).toBe(true)
  expect(violations.some((violation) => violation.rule === 'schema'
    && violation.entry === 'resources[1]')).toBe(true)
})

test('TC106-04-05：真实簿经严格门禁整体通过、基线零 diff；真实簿剥离机器引用失败', { timeout: 120_000 }, () => {
  const baselinePath = path.join(REPO_ROOT, 'tests/contracts', 'lifecycle-inventory.baseline.json')
  const before = readFileSync(baselinePath, 'utf8')
  const result = checkContracts(REPO_ROOT, REAL_ROOT)
  expect(result.violations).toEqual([])
  expect(readFileSync(baselinePath, 'utf8')).toBe(before)

  // 真实簿副本剥离全部机器引用（F06 主反例）：必须失败。
  const dir = makeTempDir()
  const resources = JSON.parse(readFileSync(path.join(REPO_ROOT, 'tests/contracts/resource-lifecycle.registry.json'), 'utf8'))
  const events = JSON.parse(readFileSync(path.join(REPO_ROOT, 'tests/contracts/event-consumers.registry.json'), 'utf8'))
  for (const entry of resources.resources) delete entry.derivedRefs
  for (const entry of events.events) {
    delete entry.producerRefs
    delete entry.consumerRefs
  }
  writeFileSync(path.join(dir, 'resource-lifecycle.registry.json'), JSON.stringify(resources))
  writeFileSync(path.join(dir, 'event-consumers.registry.json'), JSON.stringify(events))
  writeFileSync(path.join(dir, 'lifecycle-inventory.baseline.json'),
    readFileSync(path.join(REPO_ROOT, 'tests/contracts/lifecycle-inventory.baseline.json')))
  const stripped = checkContracts(REPO_ROOT, dir, {
    inventory: buildRealInventory(REPO_ROOT),
    baseline: loadBaseline(path.join(REPO_ROOT, 'tests/contracts/lifecycle-inventory.baseline.json')),
  })
  expect(stripped.violations.some((violation) => violation.rule === 'schema'
    && violation.message.includes('derivedRefs'))).toBe(true)
  expect(stripped.violations.some((violation) => violation.rule === 'schema'
    && violation.message.includes('producerRefs'))).toBe(true)
})
