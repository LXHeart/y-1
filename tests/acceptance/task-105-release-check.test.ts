import { execFileSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { mkdirSync, mkdtempSync, readFileSync, rmSync, utimesSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { resolve } from 'node:path'
import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { evaluateRelease, type ReleaseEvidence } from '../../scripts/acceptance/task-105-release-check'

/**
 * 数字人发布证据核验契约（任务书 #105H C105H-05 / TC105H-05-01～04）。
 *
 * 自动可证部分：全本地证据放行、缺证/伪 PASS/旧报告拒绝、范围与版本矩阵（失败需求不完成、
 * roadmap 不入分母）、发布权限独立（技术全过仍 RELEASE_AUTHORIZED=false）。
 */

const REPOSITORY_ROOT = resolve(import.meta.dirname, '../..')
let work = ''

beforeAll(() => {
  work = mkdtempSync(resolve(tmpdir(), 'dh-release-'))
})
afterAll(() => {
  rmSync(work, { recursive: true, force: true })
})

function writeEvidence(name: string, bytes: string): { path: string; sha256: string } {
  const path = resolve(work, name)
  writeFileSync(path, bytes, 'utf8')
  return { path, sha256: createHash('sha256').update(bytes, 'utf8').digest('hex') }
}

function baseEvidence(): ReleaseEvidence {
  return {
    generatedAt: '2026-09-24T00:00:00Z',
    codeSha: 'a'.repeat(40),
    stage: '105H',
    requirements: [
      ...Array.from({ length: 20 }, (_, index) => `DH-R${index + 1}`).map((id) => ({
        id, status: 'PASS', evidence: [],
      })),
      ...['DH-R21', 'DH-R22', 'DH-R23', 'DH-R24'].map((id) => ({ id, status: 'ROADMAP' })),
    ],
    testCases: [
      { id: 'TC105B-03-01', status: 'PASS', reportPath: 'reports/b.log' },
      { id: 'TC105H-02-01', status: 'PASS', reportPath: 'reports/h.log' },
      { id: 'TC105H-03-01', status: 'NOT_RUN' },
    ],
    commands: [
      { id: 'V105H-02-01', exitCode: 0, logPath: 'reports/h.log', time: '2026-09-24T01:00:00Z', status: 'PASS' },
      { id: 'V105H-03-04', logPath: 'reports/probe.log', time: '2026-09-24T01:00:00Z', status: 'NOT_RUN',
        condition: '无控制面 digital_human_render 配置与服务证据' },
    ],
    realGates: [
      { id: 'third-party-render-config', status: 'NOT_RUN', condition: '未选定真实供应商' },
      { id: 'service-terms-evidence', status: 'NOT_RUN', condition: '无服务条款证据' },
      { id: 'price-table-evidence', status: 'NOT_RUN', condition: '无真实价表证据' },
      { id: 'real-device-evidence', status: 'NOT_RUN', condition: '无实机记录' },
      { id: 'test-quota-evidence', status: 'NOT_RUN', condition: '无测试额度' },
      { id: 'production-authorization', status: 'NOT_RUN', condition: '未获用户授权' },
    ],
  }
}

/** 把需求证据指向真实临时文件（存在且 SHA 相符）——全绿 local 形状。 */
function withResolvedEvidence(evidence: ReleaseEvidence): ReleaseEvidence {
  evidence.requirements = (evidence.requirements ?? []).map((row) => {
    if (row.status !== 'PASS') return row
    const file = writeEvidence(`${row.id}.log`, `evidence for ${row.id}\n`)
    return { ...row, evidence: [{ path: file.path, sha256: file.sha256, status: 'PASS',
      time: '2026-09-24T01:00:00Z', run: 'it/vitest' }] }
  })
  // V 命令的 PASS 报告也落盘（fresh 校验需要文件存在）。
  mkdirSync(resolve(work, 'reports'), { recursive: true })
  writeFileSync(resolve(work, 'reports/h.log'), 'h stage log\n', 'utf8')
  writeFileSync(resolve(work, 'reports/b.log'), 'b stage log\n', 'utf8')
  writeFileSync(resolve(work, 'reports/probe.log'), 'probe log\n', 'utf8')
  return evidence
}

describe('task-105 release evidence check (task #105H C105H-05)', () => {
  it('tc105h_05_01 全本地证据：LOCAL_ACCEPTED=true；真实缺证不影响 local 但 REAL_QUALIFIED 如实为 false', () => {
    const evidence = withResolvedEvidence(baseEvidence())
    const verdict = evaluateRelease(work, evidence, 'local')

    expect(verdict.localAccepted).toBe(true)
    expect(verdict.implementationComplete).toBe(true)
    expect(verdict.requirementCompletion).toEqual({ denominator: 20, pass: 20, notPass: 0, roadmap: 4 })
    // 真实门禁全 NOT_RUN → realQualified false（local 通过≠真实资格，K12 互不替代）。
    expect(verdict.realQualified).toBe(false)
    expect(verdict.releaseAuthorized).toBe(false)
    expect(verdict.blockers).toEqual([])
  })

  it('tc105h_05_02 缺证反例（参数化）：删真实证据/改旧 SHA/报告早于代码 → 非零且列阻塞项', () => {
    // ① 缺 PASS 证据（伪 PASS）。
    const noEvidence = baseEvidence()
    const missing = evaluateRelease(work, noEvidence, 'local')
    expect(missing.localAccepted).toBe(false)
    expect(missing.blockers.join('\n')).toContain('无证据')

    // ② SHA 不符（证据被改动）。
    const tampered = withResolvedEvidence(baseEvidence())
    writeFileSync(resolve(work, 'DH-R1.log'), 'tampered content\n', 'utf8')
    const shaMismatch = evaluateRelease(work, tampered, 'local')
    expect(shaMismatch.localAccepted).toBe(false)
    expect(shaMismatch.blockers.join('\n')).toContain('SHA 不符')

    // ③ 旧报告冒充新验收：报告 mtime 早于代码 mtime。
    const stale = withResolvedEvidence(baseEvidence())
    const logFile = resolve(work, 'reports-h.log')
    writeFileSync(logFile, 'h log\n', 'utf8')
    stale.commands![0] = { ...stale.commands![0], logPath: logFile }
    utimesSync(logFile, new Date('2026-01-01T00:00:00Z'), new Date('2026-01-01T00:00:00Z'))
    const staleVerdict = evaluateRelease(work, stale, 'local', new Date('2026-09-23T00:00:00Z').getTime())
    expect(staleVerdict.localAccepted).toBe(false)
    expect(staleVerdict.blockers.join('\n')).toContain('早于最后代码改动')

    // ④ beta 模式缺真实证据：列阻塞项、不生成虚假全绿。
    const beta = evaluateRelease(work, withResolvedEvidence(baseEvidence()), 'beta')
    expect(beta.localAccepted).toBe(true)
    expect(beta.realQualified).toBe(false)
    for (const gate of ['third-party-render-config', 'service-terms-evidence', 'price-table-evidence',
      'real-device-evidence', 'test-quota-evidence']) {
      expect(beta.blockers.join('\n')).toContain(gate)
    }
  })

  it('tc105h_05_03 范围与版本：失败需求不完成、缺需求项点名、roadmap 不入分母、错误状态拒绝', () => {
    const evidence = withResolvedEvidence(baseEvidence())
    evidence.requirements![4] = { id: 'DH-R5', status: 'FAIL' }
        const verdict = evaluateRelease(work, evidence, 'local')
    expect(verdict.requirementCompletion.pass).toBe(19)
    expect(verdict.requirementCompletion.notPass).toBe(1)
    expect(verdict.localAccepted).toBe(false)

    // 删一项一期需求 → 点名缺项（不悄悄缩分母）。
    const dropped = withResolvedEvidence(baseEvidence())
    dropped.requirements = dropped.requirements!.filter((row) => row.id !== 'DH-R12')
    const droppedVerdict = evaluateRelease(work, dropped, 'local')
    expect(droppedVerdict.blockers.join('\n')).toContain('DH-R12 缺项')
    expect(droppedVerdict.requirementCompletion.denominator).toBe(20)

    // roadmap 标错状态 → 拒绝（不把路线图当完成率）。
    const wrongRoadmap = withResolvedEvidence(baseEvidence())
    wrongRoadmap.requirements = wrongRoadmap.requirements!.map((row) =>
      row.id === 'DH-R21' ? { ...row, status: 'PASS', evidence: [] } : row)
    const roadmapVerdict = evaluateRelease(work, wrongRoadmap, 'local')
    expect(roadmapVerdict.blockers.join('\n')).toContain('DH-R21 必须标 ROADMAP')
  })

  it('tc105h_05_04 发布权限独立：技术全过（含真实门禁）仍 RELEASE_AUTHORIZED=false（无用户授权）', () => {
    const evidence = withResolvedEvidence(baseEvidence())
    evidence.realGates = evidence.realGates!.map((gate) =>
      gate.id === 'production-authorization' ? gate : { ...gate, status: 'PASS' })
    // production-authorization 仍 NOT_RUN（无用户授权）。
    const verdict = evaluateRelease(work, evidence, 'beta')
    expect(verdict.realQualified).toBe(true)
    expect(verdict.localAccepted).toBe(true)
    expect(verdict.releaseAuthorized, '无授权绝不自动放行').toBe(false)

    // 用户给出授权（证据中的 PASS 由用户决策产生，工具只核形状）→ 才可能 true。
    const authorized = structuredClone(evidence)
    authorized.realGates = authorized.realGates!.map((gate) =>
      gate.id === 'production-authorization' ? { ...gate, status: 'PASS' } : gate)
    const authorizedVerdict = evaluateRelease(work, authorized, 'beta')
    expect(authorizedVerdict.releaseAuthorized).toBe(true)
    // beta 工具本身不执行部署：CLI 输出不含任何部署动作（文本面断言）。
    const cli = readFileSync(resolve(REPOSITORY_ROOT, 'scripts/acceptance/task-105-release-check.ts'), 'utf8')
    expect(cli).not.toMatch(/exec|spawn|docker|ssh/)
  })

  it('CLI：local 全绿证据 exit 0；缺证证据 exit 1 并列 blocker（端到端冒烟）', () => {
    const script = resolve(REPOSITORY_ROOT, 'scripts/acceptance/task-105-release-check.ts')
    const good = resolve(work, 'evidence-good.json')
    const goodEvidence = withResolvedEvidence(baseEvidence())
    goodEvidence.commands = goodEvidence.commands!.map((command) => command.logPath
      ? { ...command, logPath: resolve(work, command.logPath) } : command)
    writeFileSync(good, JSON.stringify(goodEvidence, null, 2), 'utf8')
    const bad = resolve(work, 'evidence-bad.json')
    writeFileSync(bad, JSON.stringify(baseEvidence(), null, 2), 'utf8')

    const goodRun = execFileSync('npx', ['--no-install', 'tsx', script, '--evidence', good, '--mode', 'local'],
      { encoding: 'utf8', cwd: REPOSITORY_ROOT })
    expect(goodRun).toContain('"localAccepted": true')

    let badStatus = 0
    try {
      execFileSync('npx', ['--no-install', 'tsx', script, '--evidence', bad, '--mode', 'local'],
        { encoding: 'utf8', cwd: REPOSITORY_ROOT, stdio: 'pipe' })
    } catch (error) {
      badStatus = (error as { status: number }).status
    }
    expect(badStatus).toBe(1)
  })
})
