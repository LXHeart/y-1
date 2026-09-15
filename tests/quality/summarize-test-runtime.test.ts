import { mkdtempSync, mkdirSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { execFileSync } from 'node:child_process'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import {
  classifyFailure,
  excerpt,
  expandInputs,
  parseJUnitXml,
  parseVitestJson,
  redactSecrets,
  summarize,
} from '../../scripts/quality/summarize-test-runtime.js'

/**
 * 任务书 #103 C103-22（TC103-22-01/02/03）：汇总工具的解析、分类与确定性。
 * 坏报告必须显式失败，不把缺数据当 0 失败；同输入重复汇总输出字节级一致。
 */

const VITEST_REPORT = JSON.stringify({
  numTotalTests: 3,
  testResults: [
    {
      name: '/repo/src/stores/notifications.test.ts',
      status: 'failed',
      assertionResults: [
        {
          fullName: 'notifications 登录后自动拉未读数',
          status: 'passed',
          duration: 12,
        },
        {
          fullName: 'notifications 轮询失败要静默',
          status: 'failed',
          duration: 5005,
          failureMessages: ['Error: Test timed out in 5000ms.'],
        },
        {
          fullName: 'notifications 未登记的 linkPath 不猜落点',
          status: 'skipped',
          duration: 0,
        },
      ],
    },
  ],
})

const JUNIT_REPORT = `<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="com.grassland.ReleaseMigratorMigrationTest" tests="2" failures="1" errors="0" skipped="0" time="1.25">
  <testcase name="migratesInReleaseOrder" classname="com.grassland.ReleaseMigratorMigrationTest" time="0.75"/>
  <testcase name="waitsForDatabase" classname="com.grassland.ReleaseMigratorMigrationTest" time="0.5">
    <failure message="AssertionError: expected 2 to be 3">java.lang.AssertionError: expected 2 to be 3&#10;	at com.grassland.Example.test(Example.java:8)</failure>
  </testcase>
</testsuite>
`

describe('classifyFailure 资源失败与缺陷失败分开（TC103-22-03）', () => {
  it('timeout / worker error / 断言 / 其他四类互不混算', () => {
    expect(classifyFailure('Error: Test timed out in 5000ms.')).toBe('timeout')
    expect(classifyFailure('Unhandled rejection during test execution')).toBe('worker_error')
    expect(classifyFailure('worker process exited with code 1')).toBe('worker_error')
    expect(classifyFailure('AssertionError: expected 2 to be 3')).toBe('assertion')
    expect(classifyFailure('Connection refused')).toBe('other')
  })
})

describe('report 解析（TC103-22-01）', () => {
  it('Vitest JSON：状态/时长/失败消息齐全', () => {
    const cases = parseVitestJson(VITEST_REPORT)
    expect(cases.map((c) => c.status)).toEqual(['passed', 'failed', 'skipped'])
    expect(cases[1].failureMessage).toContain('timed out')
    expect(cases[0].suite).toContain('notifications.test.ts')
  })

  it('JUnit XML：time 秒转毫秒、实体解码、失败可见', () => {
    const cases = parseJUnitXml(JUNIT_REPORT)
    expect(cases).toHaveLength(2)
    expect(cases[0].durationMs).toBe(750)
    expect(cases[1].status).toBe('failed')
    expect(cases[1].failureMessage).toContain('expected 2 to be 3')
  })

  it('空报告与损坏报告显式失败，不当作 0 失败（E01/E05/E08）', () => {
    expect(() => summarize([{ path: 'empty.json', content: '' }])).toThrow(/empty report/)
    expect(() => summarize([{ path: 'broken.json', content: '{not json' }])).toThrow(/invalid vitest JSON/)
    expect(() => summarize([{ path: 'broken.xml', content: '<html>not junit</html>' }])).toThrow(/invalid JUnit XML/)
    expect(() => parseVitestJson('{"no":"testResults"}')).toThrow(/missing testResults/)
    expect(() => summarize([])).toThrow(/no report inputs/)
  })

  it('非法断言状态被拒绝而不是静默归为通过（E13）', () => {
    const bad = JSON.stringify({
      testResults: [{ name: 'f', assertionResults: [{ fullName: 't', status: 'vaporized' }] }],
    })
    expect(() => parseVitestJson(bad)).toThrow(/unknown assertion status/)
  })
})

describe('汇总与确定性（TC103-22-02）', () => {
  it('counts/耗时/slowest/失败分类正确，缺时长计入 timedUnknown', () => {
    const summary = summarize([
      { path: 'vitest.json', content: VITEST_REPORT },
      { path: 'junit.xml', content: JUNIT_REPORT },
    ])
    expect(summary.counts).toEqual({ total: 5, passed: 2, failed: 2, skipped: 1 })
    expect(summary.resource).toEqual({ timeout: 1, workerError: 0 })
    expect(summary.failures.map((f) => f.category).sort()).toEqual(['assertion', 'timeout'])
    expect(summary.durationMs.total).toBe(12 + 5005 + 750 + 500)
    expect(summary.durationMs.slowest[0].name).toContain('轮询失败要静默')
  })

  it('同输入重复汇总字节级一致（输出不含时间戳等易变字段）', () => {
    const inputs = [{ path: 'junit.xml', content: JUNIT_REPORT }]
    expect(JSON.stringify(summarize(inputs))).toBe(JSON.stringify(summarize(inputs)))
  })

  it('CLI 入口：有效报告退出 0，坏报告退出非零', () => {
    const dir = mkdtempSync(join(tmpdir(), 'summarize-runtime-'))
    const reportPath = join(dir, 'junit.xml')
    const outputPath = join(dir, 'summary.json')
    writeFileSync(reportPath, JUNIT_REPORT, 'utf8')
    const run = (args: string[]) =>
      execFileSync('npx', ['tsx', 'scripts/quality/summarize-test-runtime.ts', ...args], {
        cwd: resolve(import.meta.dirname, '../..'),
        encoding: 'utf8',
      })
    expect(run(['--input', reportPath, '--output', outputPath])).toContain('tests=2 passed=1 failed=1')
    const corrupt = join(dir, 'corrupt.xml')
    writeFileSync(corrupt, '<nope/>', 'utf8')
    expect(() => run(['--input', corrupt, '--output', outputPath])).toThrow()
  })

  it('目录输入递归收集 JUnit XML（Gradle test-results 布局）', () => {
    const dir = mkdtempSync(join(tmpdir(), 'summarize-dir-'))
    mkdirSync(join(dir, 'test', 'binary'), { recursive: true })
    writeFileSync(join(dir, 'test', 'a.xml'), JUNIT_REPORT, 'utf8')
    writeFileSync(join(dir, 'test', 'binary', 'output.bin'), 'x', 'utf8')
    const expanded = expandInputs([dir])
    expect(expanded).toHaveLength(1)
    expect(expanded[0]).toContain('a.xml')
    expect(expanded[0]).not.toContain('output.bin')
  })
})

describe('脱敏（§9.4 输出无敏感数据）', () => {
  it('密钥形态被替换且 excerpt 只保留首行', () => {
    expect(redactSecrets('sk-abcdefghijklmnop123 key')).toBe('[REDACTED] key')
    expect(redactSecrets('Authorization: Bearer abcdefghijklmn')).toBe('Authorization: Bearer [REDACTED]')
    expect(redactSecrets('api_key = supersecret1')).toBe('api_key=[REDACTED]')
    const long = `${'x'.repeat(300)}\nsecond line`
    expect(excerpt(long)).toHaveLength(201)
    expect(excerpt(long)).not.toContain('second line')
  })
})
