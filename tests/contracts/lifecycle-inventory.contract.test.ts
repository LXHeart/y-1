// @vitest-environment node
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import {
  buildRealInventory, loadBaseline, resolveJavaBin, runJavaInventory, scanSqlInventory,
  validateBaseline, normalizedSourceDigest,
} from '../../scripts/quality/lifecycle-inventory'
import type { LifecycleBaseline, RealInventory } from '../../scripts/quality/lifecycle-inventory'

/**
 * 任务书 #104 C104-06（§3 D05/D06 / TC104-06-01～07）：真实清单扫描器与历史基线。
 * 合成临时仓库驱动正反例；真实仓库断言覆盖 required 18/31 与 legacy 全量闭环。
 * 门禁脚本不写 repo、不联网；扫描顺序无关、输出确定性排序。
 */

const REPO_ROOT = path.resolve(__dirname, '../..')

let tempDirs: string[] = []

function makeTempDir(): string {
  const dir = mkdtempSync(path.join(tmpdir(), 'task-104-inventory-'))
  tempDirs.push(dir)
  return dir
}

beforeEach(() => {
  tempDirs = []
})

afterEach(() => {
  for (const dir of tempDirs) {
    rmSync(dir, { recursive: true, force: true })
  }
})

function sqlRoot(temp: string): { service: string; dir: string; versioned: boolean } {
  const dir = path.join(temp, 'db', 'migration')
  mkdirSync(dir, { recursive: true })
  return { service: 'identity', dir, versioned: true }
}

describe('TC104-06-01 · SQL 真实新增遗漏', () => {
  it('schema 限定/引号/IF NOT EXISTS/分区的规范键与来源；重复幂等声明合并来源集合', () => {
    const temp = makeTempDir()
    const root = sqlRoot(temp)
    writeFileSync(path.join(root.dir, 'V1__init.sql'), [
      'CREATE TABLE IF NOT EXISTS app_users (id text PRIMARY KEY);',
      'CREATE TABLE audit.events (id text);',
      'CREATE TABLE "Quoted_Table" (id text);',
      "CREATE TABLE measurement (created_at timestamptz NOT NULL) PARTITION BY RANGE (created_at);",
      '-- 注释里的 CREATE TABLE ghost (id text);',
      "SELECT 'CREATE TABLE not_a_table (id text)';",
      'CREATE INDEX idx_app_users ON app_users(id);',
    ].join('\n'))
    let inventory = scanSqlInventory(temp, [root])
    const ids = inventory.tables.map((table) => table.id)
    expect(ids).toContain('public.app_users')
    expect(ids).toContain('audit.events')
    expect(ids).toContain('public.Quoted_Table')
    expect(ids).toContain('public.measurement')
    expect(ids).not.toContain('public.ghost')
    expect(ids).not.toContain('public.not_a_table')
    expect(ids.filter((id) => id === 'public.app_users')).toHaveLength(1)

    // 新增第二张表必进清单；同表幂等声明合并来源。
    writeFileSync(path.join(root.dir, 'V2__more.sql'),
      'CREATE TABLE IF NOT EXISTS app_users (id text PRIMARY KEY);\nCREATE TABLE second_table (id text);')
    inventory = scanSqlInventory(temp, [root])
    expect(inventory.tables.map((table) => table.id)).toContain('public.second_table')
    const users = inventory.tables.find((table) => table.id === 'public.app_users')
    expect(users?.sources.map((source) => path.basename(source.path)).sort()).toEqual(['V1__init.sql', 'V2__more.sql'])
  })
})

describe('TC104-06-02 · SQL 退役与动态', () => {
  it('RENAME/DROP 有证据；DO 内静态 DDL 识别；EXECUTE 动态建表 unsupported；普通字符串/注释不造表', () => {
    const temp = makeTempDir()
    const root = sqlRoot(temp)
    writeFileSync(path.join(root.dir, 'V1__init.sql'),
      'CREATE TABLE t1 (id text);\nCREATE TABLE keep_me (id text);')
    writeFileSync(path.join(root.dir, 'V2__rename.sql'), 'ALTER TABLE t1 RENAME TO t2;')
    writeFileSync(path.join(root.dir, 'V3__drop.sql'), 'DROP TABLE t2;')
    writeFileSync(path.join(root.dir, 'V4__do_block.sql'), [
      'DO $install$',
      'BEGIN',
      '  CREATE TABLE static_in_do (id text);',
      'END',
      '$install$;',
    ].join('\n'))
    writeFileSync(path.join(root.dir, 'V5__dynamic.sql'), [
      'DO $dyn$',
      'BEGIN',
      "  EXECUTE format('CREATE TABLE %I_dynamic (id text)', 'tenant');",
      'END',
      '$dyn$;',
    ].join('\n'))
    const inventory = scanSqlInventory(temp, [root])
    const ids = inventory.tables.map((table) => table.id)
    expect(ids).toContain('public.keep_me')
    expect(ids).toContain('public.static_in_do')
    expect(ids).not.toContain('public.t1')
    expect(ids).not.toContain('public.t2')
    expect(inventory.dropped.map((table) => table.id)).toContain('public.t2')
    expect(inventory.unsupported).toHaveLength(1)
    expect(inventory.unsupported[0]).toMatchObject({ service: 'identity' })
    expect(inventory.unsupported[0].excerpt).toContain('EXECUTE')
  })
})

const ENVELOPE_SOURCE = `package demo;

public record EventEnvelope(String eventId, String eventType) {}
`

const PRODUCER_SOURCE = `package demo;

public class Producer {
    static final String TYPE_CONSTANT = "ConstantType";

    EventEnvelope direct() {
        return new EventEnvelope("id-1", "DirectType");
    }

    EventEnvelope viaConstant() {
        return new EventEnvelope("id-2", TYPE_CONSTANT);
    }

    EventEnvelope viaConcat() {
        return new EventEnvelope("id-3", "Order" + "-" + "Placed");
    }

    EventEnvelope viaTernary(boolean flag) {
        return new EventEnvelope("id-4", flag ? "TernaryA" : "TernaryB");
    }

    EventEnvelope factory(String eventType) {
        return new EventEnvelope("id-5", eventType);
    }

    void callsFactory() {
        append(factory("FactoryType"));
    }

    void append(EventEnvelope envelope) {}
}
`

function javaRoot(temp: string): string {
  const dir = path.join(temp, 'src', 'main', 'java', 'demo')
  mkdirSync(dir, { recursive: true })
  writeFileSync(path.join(dir, 'EventEnvelope.java'), ENVELOPE_SOURCE)
  writeFileSync(path.join(dir, 'Producer.java'), PRODUCER_SOURCE)
  return path.join(temp, 'src', 'main', 'java')
}

function eventTypesOf(inventory: { events: Array<{ eventType: string }> }): string[] {
  return inventory.events.map((event) => event.eventType).sort()
}

describe('TC104-06-03 · Java 生产链', () => {
  it('字面量/常量/拼接/三元/工厂参数沿调用点传播，均关联真实方法符号', { timeout: 120_000 }, () => {
    const temp = makeTempDir()
    const inventory = runJavaInventory(REPO_ROOT, resolveJavaBin(), [javaRoot(temp)])
    expect(eventTypesOf(inventory)).toEqual(
      ['ConstantType', 'DirectType', 'FactoryType', 'Order-Placed', 'TernaryA', 'TernaryB'])
    expect(inventory.unresolved).toEqual([])
    const factory = inventory.events.find((event) => event.eventType === 'FactoryType')
    expect(factory?.sites[0]?.symbol).toContain('callsFactory')
    expect(factory?.sites[0]?.path).toContain('Producer.java')
  })
})

describe('TC104-06-04 · 未知生产与解析失败', () => {
  it('动态拼接 eventType 输出 unresolved（文件/方法/摘要）且非空；坏 Java 明确失败', { timeout: 120_000 }, () => {
    const temp = makeTempDir()
    const root = javaRoot(temp)
    writeFileSync(path.join(root, 'demo', 'Dynamic.java'), `package demo;

public class Dynamic {
    void produce(String tenant) {
        append(new EventEnvelope("id-x", "tenant-" + tenant));
    }

    void append(EventEnvelope envelope) {}
}
`)
    const inventory = runJavaInventory(REPO_ROOT, resolveJavaBin(), [root])
    expect(inventory.unresolved.length).toBeGreaterThan(0)
    const dynamic = inventory.unresolved.find((site) => site.path.includes('Dynamic.java'))
    expect(dynamic?.symbol).toContain('produce')
    expect(dynamic?.digest).toMatch(/^[0-9a-f]{64}$/)

    const broken = makeTempDir()
    const brokenRoot = javaRoot(broken)
    writeFileSync(path.join(brokenRoot, 'demo', 'Broken.java'), 'this is not java {{{')
    expect(() => runJavaInventory(REPO_ROOT, resolveJavaBin(), [brokenRoot]))
      .toThrow(/Java 扫描器失败/)
  })
})

describe('TC104-06-05 · 范围与空仓库', () => {
  it('src/test 与 build 诱饵不进清单；真空仓库返回空；登记根不改变源码根', { timeout: 120_000 }, () => {
    const temp = makeTempDir()
    const root = javaRoot(temp)
    mkdirSync(path.join(root, 'demo', '..', '..', 'test', 'java', 'demo'), { recursive: true })
    writeFileSync(path.join(root, '..', 'test', 'java', 'demo', 'DecoyTest.java'), `package demo;

public class DecoyTest {
    void decoy() {
        new EventEnvelope("t", "DecoyType");
    }
}
`)
    const inventory = runJavaInventory(REPO_ROOT, resolveJavaBin(), [root])
    expect(eventTypesOf(inventory)).not.toContain('DecoyType')

    process.env.LIFECYCLE_REGISTRY_ROOT = '/tmp/does-not-matter'
    try {
      const sqlEmpty = scanSqlInventory(temp, [{ service: 'identity', dir: path.join(temp, 'no-sql'), versioned: true }])
      expect(sqlEmpty.tables).toEqual([])
      const javaEmpty = runJavaInventory(REPO_ROOT, resolveJavaBin(),
        [path.join(makeTempDir(), 'src', 'main', 'java')])
      expect(javaEmpty.events).toEqual([])
      expect(javaEmpty.unresolved).toEqual([])
    } finally {
      delete process.env.LIFECYCLE_REGISTRY_ROOT
    }
  })
})

describe('TC104-06-06 · 历史摘要约束（基线核验）', () => {
  function baselineFixture(anchorPath: string, digest: string): LifecycleBaseline {
    return {
      version: 1,
      baselineHead: 'deadbeef',
      requiredResources: ['public.registered_one'],
      requiredEvents: ['RegisteredEvent'],
      legacyResources: [{
        id: 'public.legacy_one', anchor: { service: 'identity', path: anchorPath, version: '1' },
        sourceSha256: digest, reason: '存量未建生命周期契约：测试fixture表。', followUp: '后续登记。',
      }],
      legacyEvents: [{
        eventType: 'LegacyEvent', producerSites: [{ path: 'x/Y.java', symbol: 'Y#m()' }],
        reason: '域内消费。', followUp: '需要通知时登记。',
      }],
      dynamicEventSites: [],
    }
  }

  function inventoryFixture(): RealInventory {
    return {
      tables: [
        { id: 'public.registered_one', sources: [{ service: 'identity', path: 'a/V1.sql', version: '1' }] },
        { id: 'public.legacy_one', sources: [{ service: 'identity', path: 'a/V1.sql', version: '1' }] },
      ],
      dropped: [],
      unsupportedSql: [],
      events: [{ eventType: 'RegisteredEvent', sites: [] }, { eventType: 'LegacyEvent', sites: [] }],
      unresolvedJava: [],
      declarations: [],
    }
  }

  it('合法基线通过；锚点空白变化不误触发；字符串/代码变化触发漂移', () => {
    const temp = makeTempDir()
    const anchor = path.join(temp, 'V1__legacy.sql')
    writeFileSync(anchor, 'CREATE TABLE legacy_one (id text);')
    const relative = path.relative(process.cwd(), anchor)
    const baseline = baselineFixture(relative, normalizedSourceDigest(readFileSync(anchor, 'utf8')))
    expect(validateBaseline(process.cwd(), inventoryFixture(), baseline)).toEqual([])

    // 空白/注释排版变化：规范化摘要不变 → 不误触发。
    writeFileSync(anchor, 'CREATE   TABLE\n  legacy_one ( id\ttext )\n;')
    expect(validateBaseline(process.cwd(), inventoryFixture(), baseline)).toEqual([])

    // 字符串/结构变化：触发漂移，须重新审阅。
    writeFileSync(anchor, "CREATE TABLE legacy_one (id text, note text DEFAULT 'x');")
    const drift = validateBaseline(process.cwd(), inventoryFixture(), baseline)
    expect(drift.some((violation) => violation.rule === 'digest-drift')).toBe(true)
  })

  it('空理由/重复/required 转豁免/真实多出未入册/过期动态登记均失败', () => {
    const temp = makeTempDir()
    const anchor = path.join(temp, 'V1__legacy.sql')
    writeFileSync(anchor, 'CREATE TABLE legacy_one (id text);')
    const baseline = baselineFixture(path.relative(process.cwd(), anchor),
      normalizedSourceDigest(readFileSync(anchor, 'utf8')))

    const noReason = structuredClone(baseline)
    delete (noReason.legacyResources[0] as Record<string, unknown>).reason
    expect(validateBaseline(process.cwd(), inventoryFixture(), noReason)
      .some((violation) => violation.rule === 'schema')).toBe(true)

    const overlap = structuredClone(baseline)
    ;(overlap.legacyResources[0] as Record<string, unknown>).id = 'public.registered_one'
    expect(validateBaseline(process.cwd(), inventoryFixture(), overlap)
      .some((violation) => violation.rule === 'overlap')).toBe(true)

    const wildcard = structuredClone(baseline)
    ;(wildcard.legacyEvents[0] as Record<string, unknown>).eventType = 'Order*'
    expect(validateBaseline(process.cwd(), inventoryFixture(), wildcard)
      .some((violation) => violation.rule === 'wildcard' || violation.rule === 'schema')).toBe(true)

    const extraTable = structuredClone(inventoryFixture())
    extraTable.tables.push({ id: 'public.brand_new', sources: [] })
    expect(validateBaseline(process.cwd(), extraTable, baseline)
      .some((violation) => violation.rule === 'resource-unregistered')).toBe(true)

    const staleDynamic = structuredClone(baseline)
    staleDynamic.dynamicEventSites = [{
      path: 'a/B.java', symbol: 'B#m()', digest: '0'.repeat(64),
      eventTypes: ['X'], reason: 'r', guardTc: 'package.json',
    }]
    expect(validateBaseline(process.cwd(), inventoryFixture(), staleDynamic)
      .some((violation) => violation.rule === 'dynamic-stale')).toBe(true)

    const unregisteredDynamic = structuredClone(inventoryFixture())
    unregisteredDynamic.unresolvedJava = [{
      path: 'a/B.java', symbol: 'B#m()', detail: 'd', digest: 'f'.repeat(64),
    }]
    expect(validateBaseline(process.cwd(), unregisteredDynamic, baseline)
      .some((violation) => violation.rule === 'dynamic-unregistered')).toBe(true)
  })
})

describe('TC104-06-07 · 确定性与环境', () => {
  it('同输入多次输出一致；文件枚举顺序无关；缺 JDK 明确失败；不写 repo', { timeout: 120_000 }, () => {
    const temp = makeTempDir()
    const root = sqlRoot(temp)
    // 以「乱序」写入模拟不同枚举顺序（V10 与 V2 的字典序与版本序不同）。
    writeFileSync(path.join(root.dir, 'V10__third.sql'), 'CREATE TABLE third (id text);')
    writeFileSync(path.join(root.dir, 'V2__second.sql'), 'CREATE TABLE second (id text);')
    writeFileSync(path.join(root.dir, 'V1__first.sql'), 'CREATE TABLE first (id text);')
    const before = readdirSync(temp)
    const first = scanSqlInventory(temp, [root])
    const second = scanSqlInventory(temp, [root])
    expect(first).toEqual(second)
    // 版本序解释：V2 的 second 在 V10 之前创建；这里只断言集合与确定性排序。
    expect(first.tables.map((table) => table.id)).toEqual(first.tables.map((table) => table.id).sort())
    expect(readdirSync(temp)).toEqual(before)

    expect(() => runJavaInventory(REPO_ROOT, '/nonexistent-java-bin', [javaRoot(makeTempDir())]))
      .toThrow(/无法启动/)
  })
})

describe('TC106-04 · Java 声明清单（#106 D04：AST 声明，非字符串匹配）', () => {
  it('真实方法/显式构造/嵌套类声明收集；重载靠参数区分；注释/字符串同名不算声明；空仓库为 []', { timeout: 120_000 }, () => {
    const temp = makeTempDir()
    const root = path.join(temp, 'src', 'main', 'java')
    mkdirSync(path.join(root, 'demo'), { recursive: true })
    writeFileSync(path.join(root, 'demo', 'Decls.java'), `package demo;

public class Decls {
    public Decls() {}

    public void handle() {}

    public void handle(String payload) {}

    private static class Inner {
        void innerWork(Decls outer) {}
    }
    // void commentOnly() {}
    static final String MENTION = "void inString() {}";
}
`)
    const inventory = runJavaInventory(REPO_ROOT, resolveJavaBin(), [root])
    const decls = inventory.declarations.map((declaration) => declaration.symbol)
    expect(decls).toContain('Decls#Decls()')
    expect(decls).toContain('Decls#handle()')
    expect(decls).toContain('Decls#handle(String)')
    expect(decls).toContain('Inner#innerWork(Decls)')
    expect(decls).not.toContain('Decls#commentOnly()')
    expect(decls).not.toContain('Decls#inString()')
    // 声明带仓库相对路径且确定性排序。
    const handleDecl = inventory.declarations.find((declaration) => declaration.symbol === 'Decls#handle()')
    expect(handleDecl?.path).toContain('demo/Decls.java')
    const sorted = [...inventory.declarations]
      .map((declaration) => `${declaration.path}|${declaration.symbol}`)
      .sort()
    expect(inventory.declarations.map((declaration) => `${declaration.path}|${declaration.symbol}`)).toEqual(sorted)
    // 真空仓库输出空数组（缺字段不允许——类型必填）。
    const empty = runJavaInventory(REPO_ROOT, resolveJavaBin(), [path.join(makeTempDir(), 'none')])
    expect(empty.declarations).toEqual([])
  })

  it('真实仓库声明清单覆盖全部登记 consumer 符号（NotificationEventProcessor#parseEnvelope(ConsumerRecord)）', { timeout: 300_000 }, () => {
    const inventory = buildRealInventory(REPO_ROOT)
    const key = 'platform-java/services/identity-service/src/main/java/com/grassland/identity/notification/NotificationEventProcessor.java|NotificationEventProcessor#parseEnvelope(ConsumerRecord)'
    expect(inventory.declarations.map((declaration) => `${declaration.path}|${declaration.symbol}`)).toContain(key)
  })
})

describe('真实仓库 · 清单与基线闭环（AC-06）', () => {
  it('真实 SQL+Java 清单与基线双向核验零违规；required 18 资源/31 事件全覆盖', { timeout: 300_000 }, () => {
    const inventory = buildRealInventory(REPO_ROOT)
    const baseline = loadBaseline(path.join(REPO_ROOT, 'tests', 'contracts', 'lifecycle-inventory.baseline.json'))
    expect(inventory.unsupportedSql).toEqual([])
    const violations = validateBaseline(REPO_ROOT, inventory, baseline)
    expect(violations).toEqual([])
    expect(baseline.requiredResources).toHaveLength(18)
    expect(baseline.requiredEvents).toHaveLength(31)
    const realEvents = new Set(inventory.events.map((event) => event.eventType))
    for (const eventType of baseline.requiredEvents) {
      expect(realEvents.has(eventType), `登记事件缺生产点：${eventType}`).toBe(true)
    }
  })
})
