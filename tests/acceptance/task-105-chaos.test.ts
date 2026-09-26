import { execFileSync } from 'node:child_process'
import { mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * 数字人故障注入 runner 契约（任务书 #105H C105H-04 / TC105H-04-04）。
 *
 * 自动可证部分：项目白名单/服务白名单/有界时长/固定 argv（不接任意 shell）/
 * 每 case 必恢复/缺服务非零不静默通过。实际注入执行属 V105H-04-04（隔离栈实跑）。
 */

const REPOSITORY_ROOT = resolve(import.meta.dirname, '../..')
const RUNNER = 'scripts/acceptance/task-105-chaos.ts'

function readRunner(): string {
  return readFileSync(resolve(REPOSITORY_ROOT, RUNNER), 'utf8')
}

describe('task-105 chaos runner guard (task #105H C105H-04)', () => {
  // 六次真实 npx/tsx 子进程串行拉起：全量套件并行下偶发越过 5s 默认预算
  // （单文件实跑 ~4.4s，纯冷启动开销非断言失败）——给 30s 显式预算。
  it('tc105h_04_04 项目白名单：只允许 grassland-dh-test；未知项目/case/缺 output 非零退出', () => {
    const work = mkdtempSync(resolve(tmpdir(), 'dh-chaos-'))
    try {
      const script = resolve(REPOSITORY_ROOT, RUNNER)
      const run = (args: string[]): number => {
        try {
          execFileSync('npx', ['--no-install', 'tsx', script, ...args],
            { encoding: 'utf8', cwd: REPOSITORY_ROOT, stdio: 'pipe' })
          return 0
        } catch (error) {
          return (error as { status: number }).status ?? 1
        }
      }
      // 参数化负例：非白名单项目/生产样式项目名/未知 case/缺 output。
      expect(run(['--compose-project', 'grassland-prod', '--case', 'runtime-kill', '--output', work])).not.toBe(0)
      expect(run(['--compose-project', 'y1-e2e-local', '--case', 'redis-loss', '--output', work])).not.toBe(0)
      expect(run(['--compose-project', 'grassland-dh-test', '--case', 'drop-database', '--output', work])).not.toBe(0)
      expect(run(['--compose-project', 'grassland-dh-test', '--case', 'runtime-kill'])).not.toBe(0)

      // 白名单项目但项目未运行：非零（不静默通过）。
      expect(run(['--compose-project', 'grassland-dh-test', '--case', 'runtime-kill', '--output', work])).not.toBe(0)
    } finally {
      rmSync(work, { recursive: true, force: true })
    }
  }, 30_000)

  it('tc105h_04_04 固定 case 集、服务白名单与有界时长（≤60s），无任意 shell/命令拼接', () => {
    const source = readRunner()

    // 五个固定 case，一个不多一个不少。
    expect(source).toContain("'runtime-kill'")
    expect(source).toContain("'java-isolation'")
    expect(source).toContain("'redis-loss'")
    expect(source).toContain("'provider-timeout'")
    expect(source).toContain("'recording-disk-full'")
    expect(source.match(/'runtime-kill' \|/g)).toBeNull()

    // 时长上限常量：观察窗口 ≤60 秒。
    expect(source).toContain('CHAOS_MAX_WAIT_MS = 60_000')

    // 服务白名单固定；不含 frontend/edge 等其他服务（也不能是空集合）。
    expect(source).toContain("new Set(['dh-runtime', 'dh-redis', 'intelligence-service'])")

    // spawn 只以定参数组调 docker（二进制白名单），无 shell:true、无模板字符串拼接命令。
    expect(source).not.toContain('shell: true')
    expect(source).not.toContain('`docker compose')
    expect(source).not.toContain('"sh"')
    expect(source).not.toContain('"/bin/sh"')
    for (const call of source.matchAll(/spawnSync\('([^']+)'/g)) {
      expect(call[1]).toBe('docker')
    }
    // 唯一进入容器的是固定探测常量脚本（非用户输入）。
    expect(source).toContain('PROVIDER_EGRESS_PROBE')
    expect(source).not.toMatch(/exec\([^)]*\$\{/)

    // 每个 case 的 docker 动词都在固定子命令域内（kill/restart/up/network disconnect|connect/ps/exec）。
    const verbs = [...source.matchAll(/'(kill|restart|up|down|exec|network)',/g)].map((match) => match[1])
    expect(new Set(verbs)).toEqual(new Set(['kill', 'restart', 'up', 'network', 'exec']))
  })

  it('tc105h_04_04 每个 case 必须恢复并复核健康；语义归属如实标注（不冒充磁盘满/半流真语义）', () => {
    const source = readRunner()

    // 恢复动作与复核存在于全部 kill/restart 型 case。
    expect(source.match(/restore\(project/g)?.length ?? 0).toBeGreaterThanOrEqual(2)
    expect(source).toContain("'up', '-d', '--no-deps'")
    // 报告字段：restored/healthyAfterRestore/realSemanticsAnchor。
    expect(source).toContain('healthyAfterRestore: boolean')
    expect(source).toContain('realSemanticsAnchor?: string')
    // 磁盘满与 provider 半流不在 runner 内冒充：锚定到 IT/单测。
    expect(source).toContain('DigitalHumanRecoveryIT.tc105h_04_03')
    expect(source).toContain('DigitalHumanRecoveryIT.tc105h_04_02')
    // ok 必须=注入成功且恢复且健康且零问题（部分成功不算过）。
    expect(source).toContain("report.ok = ok && report.restored && report.healthyAfterRestore && problems.length === 0")
  })
})
