import { execFileSync } from 'node:child_process'
import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * Hypit 全量核销契约（任务书 #107-3 C107-24 / TC107-24-01～02 的静态守护）。
 *
 * 覆盖：coverage 契约逐卡登记完整（C107-01～24，状态与 tc/证据齐全且路径真实
 * 存在）、验收脚本四件套在场且支持 --help、CI 四层 hypit job 在场。真实启动/
 * live 层由 verify-107-full.sh（HYPIT_FULL_E2E）与 verify-107-live.sh
 * （HYPIT_LIVE_*）的 opt-in 阶段承接，缺环境如实非零退出。
 */

const REPOSITORY_ROOT = resolve(import.meta.dirname, '../..')

function read(path: string): string {
  return readFileSync(resolve(REPOSITORY_ROOT, path), 'utf8')
}

const ALL_CARDS = Array.from({ length: 24 }, (_, index) => `C107-${String(index + 1).padStart(2, '0')}`)

describe('coverage 契约全量核销（TC107-24-01/02）', () => {
  const coverage = JSON.parse(read('contracts/hypit-coverage.v1.json')) as {
    cards: Record<string, { status: string; tc?: Record<string, string>; evidence?: string }>
  }

  it('三书 24 卡全部登记且状态为 IMPLEMENTED', () => {
    for (const card of ALL_CARDS) {
      const entry = coverage.cards[card]
      expect(entry, `${card} 缺少 coverage 登记`).toBeDefined()
      // VERIFIED（前期书已复核）与 IMPLEMENTED（本批落地）均为收口态。
      expect(['IMPLEMENTED', 'VERIFIED'], `${card} 状态未收口`).toContain(entry.status)
    }
  })

  it('每卡 tc 条目与 evidence 路径真实存在（vendor 路径不算实现证据）', () => {
    for (const card of ALL_CARDS) {
      const entry = coverage.cards[card]
      const tcKeys = Object.keys(entry.tc ?? {})
      expect(tcKeys.length, `${card} 无 tc 条目`).toBeGreaterThan(0)
      for (const [tc, verdict] of Object.entries(entry.tc ?? {})) {
        expect(verdict.length, `${card}/${tc} 判据为空`).toBeGreaterThan(0)
      }
      if (entry.evidence) {
        // 只核验形如仓库相对路径的证据 token（剥离括号/冒号等行文标点）。
        // test-artifacts/ 是本地验收产物目录（.gitignore 不入库，§14 协议留在验收机），
        // 只校验形状（必须落在 test-artifacts/task-107/<卡号>/ 下），不做存在性断言——
        // 否则 CI fresh clone 必挂；仓库内前缀（platform-* 等）才做存在性校验。
        const repoPrefix = /(?:platform-hypit|platform-java|tests|docs|contracts|scripts|deploy)\//
        const localEvidenceRoot = `test-artifacts/task-107/${card.replace('C107-', 'C')}/`
        for (const match of entry.evidence.matchAll(
          /(?:platform-hypit|platform-java|tests|test-artifacts|docs|contracts|scripts|deploy)\/[A-Za-z0-9._/-]+\.(?:json|txt|log|ts|java|md)/g,
        )) {
          const relative = match[0]
          if (repoPrefix.test(relative)) {
            expect(existsSync(resolve(REPOSITORY_ROOT, relative)), `${card} 证据缺失：${relative}`).toBe(true)
          } else {
            expect(relative.startsWith(localEvidenceRoot), `${card} 证据路径越界：${relative}`).toBe(true)
          }
        }
        // 目录 token（尾斜杠形式）同规则：仓库内前缀须真实存在；test-artifacts 校验卡目录形状。
        for (const match of entry.evidence.matchAll(
          /(?:platform-hypit|platform-java|tests|test-artifacts|docs|contracts|scripts|deploy)\/[A-Za-z0-9._-]+(?:\/[A-Za-z0-9._-]+)*\//g,
        )) {
          const relative = match[0]
          if (repoPrefix.test(relative)) {
            expect(existsSync(resolve(REPOSITORY_ROOT, relative)), `${card} 证据目录缺失：${relative}`).toBe(true)
          } else {
            expect(relative.startsWith(localEvidenceRoot), `${card} 证据目录越界：${relative}`).toBe(true)
          }
        }
      }
    }
  })
})

describe('验收脚本与 CI 分层（TC107-24-03/04 静态面）', () => {
  it('四件套验收脚本在场且 --help 可用（无网络即可执行）', () => {
    for (const script of [
      'scripts/acceptance/verify-107-upstream.sh',
      'scripts/acceptance/ci-e2e-107.sh',
      'scripts/acceptance/verify-107-full.sh',
      'scripts/acceptance/verify-107-live.sh',
    ]) {
      expect(existsSync(resolve(REPOSITORY_ROOT, script)), `${script} 缺失`).toBe(true)
    }
    const help = execFileSync('bash', ['scripts/acceptance/verify-107-live.sh', '--help'], {
      cwd: REPOSITORY_ROOT,
      encoding: 'utf8',
    })
    expect(help).toContain('HYPIT_LIVE_ENABLED')
    expect(help).toContain('test-artifacts/task-107/C24')
  })

  it('verify-107-full.sh 缺环境时非零退出并列出缺项（不空 skip）', () => {
    const script = read('scripts/acceptance/verify-107-full.sh')
    expect(script).toContain('NOT_RUN[1]')
    expect(script).toContain('exit 2')
    expect(script).toContain('GREEN-WITH-NOT-RUN')
  })

  it('ci.yml 四层 hypit job 在场（contract / local-e2e / full-local / live）', () => {
    const ci = read('.github/workflows/ci.yml')
    for (const job of ['hypit-contract:', 'hypit-local-e2e:', 'hypit-full-local:', 'hypit-live:']) {
      expect(ci, `缺 job ${job}`).toContain(job)
    }
    // opt-in 双闸：live 需 secret + 预算，且绝不静默通过。
    expect(ci).toContain('HYPIT_LIVE_ENABLED')
    expect(ci).toContain('never a silent PASS')
  })
})
