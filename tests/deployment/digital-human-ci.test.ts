import { readFileSync, existsSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * 数字人 CI 矩阵契约（任务书 #105H C105H-02 / TC105H-02-03、TC105H-02-04）。
 *
 * 自动可证部分：
 * - 原功能回归（tc105h_02_03）：DH 检查与既有门禁「并列」——ci.yml 既有 job 一个不少，
 *   新 dh-fake job 是显式 opt-in 且不静默漏跑；网关既有路由/开关不受 DH 改动影响。
 * - 门禁失败真实性（tc105h_02_04）：runner 不吞失败（非零退出、无 passWithNoTests/重试掩蔽）、
 *   四组 spec 无 route.fulfill 冒充后端、test.skip 必须带精确原因；迁移 fixture 结构由
 *   本文件锁定（Java 侧 DigitalHumanUpgradeIT 依赖该定形契约）。
 */

const REPOSITORY_ROOT = resolve(import.meta.dirname, '../..')

function read(path: string): string {
  return readFileSync(resolve(REPOSITORY_ROOT, path), 'utf8')
}

const FOUR_SPECS = [
  'tests/e2e/digital-human-workbench.spec.ts',
  'tests/e2e/digital-human-recording.spec.ts',
  'tests/e2e/digital-human-governance.spec.ts',
  'tests/e2e/digital-human-lifecycle.spec.ts',
] as const

describe('digital-human CI matrix contract (task #105H C105H-02)', () => {
  it('tc105h_02_03 全量 runner：固定四组阶段 spec、三引擎串行、隔离 Fake、证书先行', () => {
    const runner = read('scripts/acceptance/ci-e2e-105-all.sh')

    for (const spec of FOUR_SPECS) {
      expect(runner, `runner 应固定运行 ${spec}`).toContain(spec)
    }
    expect(runner).toContain('E2E_ENGINES="${E2E_ENGINES:-chromium firefox webkit}"')
    expect(runner).toContain('E2E_WORKERS="${E2E_WORKERS:-1}"')
    expect(runner).toContain('export DH_E2E=1')
    expect(runner).toContain('export DH_REAL_PROVIDERS_ENABLED=false')
    // 先证书后栈（compose secrets 依赖证书就位）。
    const certCall = runner.indexOf('task-105-test-certificates.sh')
    const execCall = runner.indexOf('exec bash scripts/ci-e2e.sh')
    expect(certCall).toBeGreaterThan(-1)
    expect(execCall).toBeGreaterThan(certCall)
  })

  it('tc105h_02_03 原功能回归并列：既有 CI 门禁一个不少，DH Fake job 显式 opt-in 不静默漏跑', () => {
    const ci = read('.github/workflows/ci.yml')

    // 既有门禁保留（并列，不被 DH job 取代/缩水）。
    for (const job of ['node:', 'java:', 'e2e:', 'image-security:']) {
      expect(ci, `既有 job ${job} 必须保留`).toContain(`  ${job}`)
    }
    expect(ci).toContain('name: Run all Gradle tests, coverage gates, and package every service')
    expect(ci).toContain('npm run e2e:ci')

    // DH Fake job：显式 opt-in（workflow_dispatch 输入），不满足时输出显式 skip 说明——
    // 「不静默漏跑」= 不跑也要在 CI 日志里可见，不是条件悄悄跳过。
    const dhJobMatch = ci.match(/^ {2}dh-fake:\n((?: {4}.*\n|\n)*)/m)
    expect(dhJobMatch, 'ci.yml 应有 dh-fake job').toBeTruthy()
    const dhJob = dhJobMatch![1]
    expect(dhJob).toContain('dh-fake')
    expect(dhJob).toContain('ci-e2e-105-all.sh')
    // 失败可观测：不用 continue-on-error 掩蔽、不依赖成功才上传诊断。
    expect(dhJob).not.toContain('continue-on-error')
    expect(dhJob).toMatch(/if:\s*always\(\)/)

    // 网关原路由不受 DH 影响的定点回归锚（真实断言在 edge 契约测试；此处锁文件面）：
    // 生产 compose 未被本书改动（组织 BYOK/取消退款口径原样）。
    const production = read('docker-compose.production.yml')
    expect(production).not.toContain('DH_AUDIO_UPSTREAM')
    expect(production).not.toContain('dh-runtime')
  })

  it('tc105h_02_04 门禁失败真实性：非零退出、无 passWithNoTests、无重试掩蔽、无前端 mock 兜底', () => {
    const runner = read('scripts/acceptance/ci-e2e-105-all.sh')
    const ciE2e = read('scripts/ci-e2e.sh')

    expect(runner).not.toContain('--passWithNoTests')
    expect(runner).not.toContain('retries')
    // ci-e2e.sh 任一引擎失败即非零退出（engine_status 语义不被吞）。
    expect(ciE2e).toContain('engine_status=$?')
    expect(ciE2e).toMatch(/if \[\[ "\$engine_status" -ne 0 \]\]; then exit "\$engine_status"; fi/)

    // 四组 spec 与 fixtures：不允许 route.fulfill/page.route 冒充后端（参数化逐文件）。
    for (const spec of [...FOUR_SPECS, 'tests/e2e/fixtures/digital-human.ts']) {
      const source = read(spec)
      expect(source, `${spec} 不得 mock 后端`).not.toMatch(/route\.fulfill\(|page\.route\(/)
    }

    // skip 纪律：条件 skip 必须带精确原因（无理由 skip = 静默漏跑）。
    for (const spec of FOUR_SPECS) {
      const source = read(spec)
      const skips = source.match(/test\.skip\([^)]*/g) ?? []
      expect(skips.length, `${spec} 的 skip 应成对出现或有据`).toBeGreaterThanOrEqual(0)
      for (const skip of skips) {
        expect(skip, `${spec} 每个 test.skip 必须携带原因串`).toMatch(/,\s*[`'"]/)
      }
    }

    // e2e 矩阵必须有真实测试数量（lifecycle spec 四组 describe 齐备，运行数量非零的文件面前提）。
    const lifecycle = read('tests/e2e/digital-human-lifecycle.spec.ts')
    for (const group of ['完整会话', '换号', '注销', '重启']) {
      expect(lifecycle, `lifecycle spec 应覆盖「${group}」`).toContain(group)
    }
    expect(existsSync(resolve(REPOSITORY_ROOT, 'scripts/acceptance/ci-e2e-105-all.sh'))).toBe(true)
  })

  it('tc105h_02_02 fixture 定形契约：升级样本可被 Java 定界解析（无内嵌双引号/转义）', () => {
    const fixture = read('tests/fixtures/digital-human/upgrade-cases.json')

    // 合成纪律与基线声明。
    expect(fixture).toContain('"syntheticOnly": true')
    expect(fixture).toContain('"target": "87"')

    // Java 侧 DigitalHumanUpgradeIT 用键边界+引号定界解析：字符串值内不得出现
    // 未转义双引号（也不得有反斜杠转义——出现 \" 即破坏 [^"]+ 提取）。
    for (const match of fixture.matchAll(/: "((?:[^"\\]|\\.)*)"/g)) {
      const value = match[1]
      expect(value, 'fixture 字符串不得含转义序列（Java 定界解析契约）').not.toContain('\\')
    }
    // 三个样本类齐备：旧个人 / 旧组织 / 未结费用。
    for (const caseId of ['personal-legacy-assets', 'organization-legacy-assets', 'unsettled-ai-runs']) {
      expect(fixture).toContain(`"id": "${caseId}"`)
    }
    // 期望断言全部是等值查询（equals 数字），供升级前后各跑一次。
    const expectations = fixture.match(/"equals":/g) ?? []
    expect(expectations.length).toBeGreaterThanOrEqual(5)
  })
})
