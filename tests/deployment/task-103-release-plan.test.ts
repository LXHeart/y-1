import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import {
  buildReleasePlan,
  inputChecksum,
  RELEASE_STEPS,
  validateInput,
  type UpgradeCaseInput,
} from '../../scripts/acceptance/task-103-release-plan.js'

/**
 * 任务书 #103 C103-26（TC103-26-01/02/04）：发布计划 plan-only 与阻断语义。
 * 完整输入输出可执行顺序；缺证据/新操作存量/checksum 差异/未知资金阻断且不部署；
 * 同输入重跑计划确定（TC103-26-02）。
 */

const repositoryRoot = resolve(import.meta.dirname, '../..')

function validCase(): UpgradeCaseInput {
  return {
    buildId: 'b1',
    previousBuildId: 'b0',
    migrations: [{ service: 'identity-service', versions: ['V51'], checksumsRegistered: true }],
    pendingOperations: { exitOperations: 0, erasureManifests: 0, needsReviewCount: 0, unknownFunds: 0 },
    evidence: {
      apiCheck: 'fact-check.json',
      e2e: 'e2e.json',
      upgradeReport: 'upgrade.md',
      backupRestoreProof: 'backup.txt',
    },
  }
}

describe('计划参数与顺序（TC103-26-01）', () => {
  it('完整输入输出 §12.6 六步可执行顺序，READY 无阻断', () => {
    const plan = buildReleasePlan(validCase())
    expect(plan.status).toBe('READY')
    expect(plan.steps.map((step) => step.order)).toEqual([1, 2, 3, 4, 5, 6])
    expect(plan.steps[0].action).toContain('阻止旧写者')
    expect(plan.steps[2].action).toContain('release-migrator')
    expect(plan.blockers).toEqual([])
    expect(plan.mode).toBe('plan-only')
  })

  it('缺任一必需证据阻断：BLOCKED 且步骤截断到停旧写者（不生成部署顺序）', () => {
    for (const key of ['apiCheck', 'e2e', 'upgradeReport', 'backupRestoreProof'] as const) {
      for (const absent of [undefined, '   ']) {
        const missing = validCase()
        missing.evidence[key] = absent
        const plan = buildReleasePlan(missing)
        expect(plan.status).toBe('BLOCKED')
        expect(plan.blockers.map((blocker) => blocker.code)).toContain('EVIDENCE_MISSING')
        expect(plan.steps).toHaveLength(1)
      }
    }
  })

  it('未核对或非法待办计数、空白审核说明不能默认为零风险', () => {
    for (const key of ['exitOperations', 'erasureManifests', 'needsReviewCount', 'unknownFunds'] as const) {
      for (const value of [undefined, null, -1, 0.5, '0', Number.MAX_SAFE_INTEGER + 1]) {
        const invalid = { ...validCase(), pendingOperations: { ...validCase().pendingOperations, [key]: value } }
        expect(() => validateInput(invalid)).toThrow(key)
      }
    }
    expect(() => buildReleasePlan({ ...validCase(), needsReviewExplanations: [{ code: 'x', reason: ' ' }] }))
      .toThrow('needsReviewExplanations')
    const unchecked = validCase()
    delete unchecked.migrations[0].checksumsRegistered
    expect(buildReleasePlan(unchecked).status).toBe('BLOCKED')
  })

  it('非法输入显式失败：缺 buildId / migrations 非数组 / 空对象', () => {
    expect(() => validateInput({})).toThrow(/buildId/)
    expect(() => validateInput({ buildId: 'b1', previousBuildId: 'b0', migrations: 'x' })).toThrow(/migrations/)
    expect(() => validateInput(null)).toThrow(/对象/)
  })
})

describe('回退与接管阻断（TC103-26-03/06 语义）', () => {
  it('未完成新操作存量：禁止旧 worker 接管（PENDING_NEW_OPERATIONS）', () => {
    const pending = validCase()
    pending.pendingOperations = { ...pending.pendingOperations, exitOperations: 3, erasureManifests: 1 }
    const plan = buildReleasePlan(pending)
    expect(plan.status).toBe('BLOCKED')
    expect(plan.blockers.map((blocker) => blocker.code)).toContain('PENDING_NEW_OPERATIONS')
    expect(plan.rollbackNotes.join('\n')).toContain('禁止旧 worker 接管')
  })

  it('checksum 差异：禁止覆盖/repair（CHECKSUM_UNREGISTERED）', () => {
    const drift = validCase()
    drift.migrations = [{ service: 'marketplace-service', versions: ['V59'], checksumsRegistered: false }]
    const plan = buildReleasePlan(drift)
    expect(plan.status).toBe('BLOCKED')
    expect(plan.blockers.map((blocker) => blocker.code)).toContain('CHECKSUM_UNREGISTERED')
  })

  it('未知资金与未解释 needs_review 阻断；解释齐全的 needs_review 不阻断', () => {
    const unknown = validCase()
    unknown.pendingOperations = { ...unknown.pendingOperations, needsReviewCount: 2, unknownFunds: 1 }
    unknown.needsReviewExplanations = [{ code: 'legacy-x', reason: '保留' }]
    const blocked = buildReleasePlan(unknown)
    expect(blocked.status).toBe('BLOCKED')
    expect(blocked.blockers.map((blocker) => blocker.code)).toEqual(
      expect.arrayContaining(['NEEDS_REVIEW_UNEXPLAINED', 'UNKNOWN_FUNDS']))

    const explained = buildReleasePlan({
      ...unknown,
      pendingOperations: { ...unknown.pendingOperations, needsReviewCount: 2, unknownFunds: 0 },
      needsReviewExplanations: [
        { code: 'legacy-x', reason: '保留' },
        { code: 'legacy-y', reason: '裁定' },
      ],
    })
    expect(explained.status).toBe('READY')
  })

  it('回退红线四条在案：不 DROP、旧 worker 不接管、只读兼容≠写回退、迁移失败保持现场', () => {
    const plan = buildReleasePlan(validCase())
    const notes = plan.rollbackNotes.join('\n')
    expect(notes).toContain('禁止 DROP')
    expect(notes).toContain('只读兼容不等于业务写回退安全')
    expect(notes).toContain('前向发布')
  })
})

describe('重复生成与 fixture（TC103-26-02）', () => {
  it('同输入两次生成字节级一致（inputHash 稳定）', () => {
    const input = validCase()
    expect(JSON.stringify(buildReleasePlan(input))).toBe(JSON.stringify(buildReleasePlan(input)))
    expect(inputChecksum(input)).toBe(inputChecksum(JSON.parse(JSON.stringify(input))))
  })

  it('仓库 fixture：四个用例——READY/缺证据/存量+未知资金/checksum 差异', () => {
    const fixture = JSON.parse(
      readFileSync(resolve(repositoryRoot, 'tests/fixtures/task-103/upgrade-cases.json'), 'utf8'),
    ) as { cases: UpgradeCaseInput[] }
    expect(fixture.cases).toHaveLength(4)
    const plans = fixture.cases.map((item) => buildReleasePlan(item))
    expect(plans.map((plan) => plan.status)).toEqual(['READY', 'BLOCKED', 'BLOCKED', 'BLOCKED'])
    expect(plans[0].steps).toEqual(RELEASE_STEPS)
    expect(plans[2].blockers.map((blocker) => blocker.code)).toEqual(
      expect.arrayContaining(['PENDING_NEW_OPERATIONS', 'UNKNOWN_FUNDS', 'NEEDS_REVIEW_UNEXPLAINED']))
  })

  it('计划不含任何部署副作用（无 ssh/docker/SQL 执行语句）', () => {
    const source = readFileSync(resolve(repositoryRoot, 'scripts/acceptance/task-103-release-plan.ts'), 'utf8')
    expect(source).not.toMatch(/execSync|spawn|ssh\s|docker\s+(run|exec|compose up)/)
  })
})
