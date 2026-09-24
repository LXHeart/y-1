/**
 * 数字人阶段发布证据核验 CLI（任务书 #105H C105H-05 / V105H-05-02、V105H-05-03）。
 *
 * 用法：
 *   npx tsx scripts/acceptance/task-105-release-check.ts --evidence <evidence.json> --mode local|beta
 *
 * 语义（任务书 §11 C105H-05 步骤 1～3）：
 * - local：只判必需 Fake/本地证据——A～G/H 本地 TC 真实通过 → LOCAL_ACCEPTED=true；
 *   真实门禁仍 NOT_RUN 不影响 local，但 IMPLEMENTATION/REAL_QUALIFIED 如实分开；
 * - beta：必须在 local 全绿之上，另要求 H03 真实条件证据（第三方配置/服务条款/价表/实机/
 *   测试额度）齐备——任何 NOT_RUN/PARTIAL → 非零并列阻塞项；工具不执行部署，
 *   RELEASE_AUTHORIZED 永远单独由用户授权（自动输出 false）。
 * - 拒绝伪 PASS：报告文件缺失/SHA 不符/测试版本早于代码改动/缺证/任务书勾选框不作证据。
 */

import { createHash } from 'node:crypto'
import { existsSync, readFileSync, statSync } from 'node:fs'
import { resolve } from 'node:path'

export interface EvidenceRef {
  path?: string
  sha256?: string
  run?: string
  time?: string
  status?: string
  condition?: string
}

export interface CommandRecord {
  id: string
  exitCode?: number
  logPath?: string
  time?: string
  status?: string
  condition?: string
}

export interface RequirementRecord {
  id: string
  status: string
  evidence?: EvidenceRef[]
}

export interface RealGateRecord {
  id: string
  status: string
  condition?: string
  evidencePath?: string
}

export interface ReleaseEvidence {
  generatedAt?: string
  codeSha?: string
  stage?: string
  requirements?: RequirementRecord[]
  testCases?: Array<{ id: string; status: string; reportPath?: string; command?: string }>
  commands?: CommandRecord[]
  realGates?: RealGateRecord[]
}

export interface ReleaseVerdict {
  mode: 'local' | 'beta'
  generatedAt: string
  codeSha: string | null
  implementationComplete: boolean
  localAccepted: boolean
  realQualified: boolean
  releaseAuthorized: boolean
  requirementCompletion: { denominator: number; pass: number; notPass: number; roadmap: number }
  blockers: string[]
}

const ROADMAP_IDS = new Set(['DH-R21', 'DH-R22', 'DH-R23', 'DH-R24'])
const FIRST_PHASE_REQUIREMENTS = Array.from({ length: 20 }, (_, index) => `DH-R${index + 1}`)
const REAL_GATE_IDS = [
  'third-party-render-config',
  'service-terms-evidence',
  'price-table-evidence',
  'real-device-evidence',
  'test-quota-evidence',
  'production-authorization',
]

function sha256File(path: string): string | null {
  if (!existsSync(path)) return null
  return createHash('sha256').update(readFileSync(path)).digest('hex')
}

function isIsoTime(value: string | undefined): value is string {
  return typeof value === 'string' && !Number.isNaN(Date.parse(value))
}

/** 证据文件核验：存在、SHA 相符、时间有效；失败返回具体问题。 */
export function verifyEvidenceFile(root: string, evidence: EvidenceRef, label: string): string[] {
  const problems: string[] = []
  if (!evidence.path) {
    return [`${label} 缺 path`]
  }
  const absolute = resolve(root, evidence.path)
  if (!existsSync(absolute)) {
    return [`${label} 证据文件不存在：${evidence.path}`]
  }
  const actual = sha256File(absolute)
  if (evidence.sha256 && actual !== evidence.sha256) {
    problems.push(`${label} 证据 SHA 不符（声明 ${evidence.sha256.slice(0, 12)}… 实际 ${(actual ?? '').slice(0, 12)}…）`)
  }
  if (evidence.time && !isIsoTime(evidence.time)) {
    problems.push(`${label} 证据时间非法：${evidence.time}`)
  }
  return problems
}

/** 测试版本早于最后代码改动 = 旧报告冒充新验收（按 mtime 比较，保守拒绝）。 */
export function verifyReportFresh(root: string, reportPath: string | undefined, codeMtimeMs: number,
  label: string): string[] {
  if (!reportPath) return [`${label} 缺报告路径`]
  const absolute = resolve(root, reportPath)
  if (!existsSync(absolute)) return [`${label} 报告不存在：${reportPath}`]
  if (statSync(absolute).mtimeMs < codeMtimeMs) {
    return [`${label} 报告早于最后代码改动（旧报告不能验收新代码）`]
  }
  return []
}

export function evaluateRelease(root: string, evidence: ReleaseEvidence, mode: 'local' | 'beta',
  codeMtimeMs = 0): ReleaseVerdict {
  const blockers: string[] = []
  const verdict: ReleaseVerdict = {
    mode,
    generatedAt: new Date().toISOString(),
    codeSha: evidence.codeSha ?? null,
    implementationComplete: false,
    localAccepted: false,
    realQualified: false,
    releaseAuthorized: false,
    requirementCompletion: { denominator: 0, pass: 0, notPass: 0, roadmap: 0 },
    blockers,
  }

  // 基础形状：一期 20 项需求逐项在册（ROADMAP 单列，不入分母）。
  const byId = new Map((evidence.requirements ?? []).map((row) => [row.id, row]))
  for (const id of FIRST_PHASE_REQUIREMENTS) {
    const row = byId.get(id)
    if (!row) {
      blockers.push(`需求 ${id} 缺项（一期 20 项必须逐项在册）`)
      verdict.requirementCompletion.denominator += 1
      verdict.requirementCompletion.notPass += 1
      continue
    }
    verdict.requirementCompletion.denominator += 1
    if (row.status === 'PASS') {
      verdict.requirementCompletion.pass += 1
      // PASS 必须有可核验证据（缺证=伪 PASS）。
      if (!row.evidence || row.evidence.length === 0) {
        blockers.push(`需求 ${id} 标 PASS 但无证据（任务书勾选框不作证据）`)
      } else {
        for (const [index, ref] of row.evidence.entries()) {
          blockers.push(...verifyEvidenceFile(root, ref, `${id}[${index}]`))
          if (ref.status && ref.status !== 'PASS') {
            blockers.push(`${id}[${index}] 证据状态为 ${ref.status}，不能支撑 PASS`)
          }
        }
      }
    } else {
      verdict.requirementCompletion.notPass += 1
    }
  }
  for (const row of evidence.requirements ?? []) {
    if (ROADMAP_IDS.has(row.id)) {
      if (row.status !== 'ROADMAP') {
        blockers.push(`${row.id} 必须标 ROADMAP（不计入完成率分母）`)
      } else {
        verdict.requirementCompletion.roadmap += 1
      }
    }
  }

  // TC/命令：任何 FAIL → 实现不完整；NOT_RUN 在 local 允许（真实项）但不能是本地必需项。
  const failedCases = (evidence.testCases ?? []).filter((row) => row.status === 'FAIL')
  for (const failed of failedCases) {
    blockers.push(`${failed.id} FAIL`)
  }
  const localRequiredNotRun = (evidence.testCases ?? []).filter(
    (row) => row.status === 'NOT_RUN' && row.id.startsWith('TC105H-0[1245]'))
  for (const skipped of localRequiredNotRun) {
    blockers.push(`${skipped.id} NOT_RUN（本地必需 TC 不可跳过）`)
  }
  for (const command of evidence.commands ?? []) {
    if (command.status === 'NOT_RUN' && !command.condition) {
      blockers.push(`命令 ${command.id} NOT_RUN 未注明所缺条件`)
    }
    if (command.status !== 'NOT_RUN' && typeof command.exitCode === 'number' && command.exitCode !== 0) {
      blockers.push(`命令 ${command.id} exit=${command.exitCode}`)
    }
    if (command.status === 'PASS' && command.logPath) {
      blockers.push(...verifyReportFresh(root, command.logPath, codeMtimeMs, command.id))
    }
  }

  verdict.implementationComplete = (evidence.testCases ?? []).length > 0
    && failedCases.length === 0 && localRequiredNotRun.length === 0

  // local：一期本地必需全过即 LOCAL_ACCEPTED（真实缺口单列，不影响 local）。
  verdict.localAccepted = verdict.implementationComplete
    && verdict.requirementCompletion.notPass === 0
    && blockers.length === 0

  // 真实门禁（beta 前置）：六项逐项 PASS 才 REAL_QUALIFIED；production-authorization
  // 只能由用户明确给出——工具永不自动放行。
  const gates = new Map((evidence.realGates ?? []).map((row) => [row.id, row]))
  const gateProblems: string[] = []
  for (const gateId of REAL_GATE_IDS) {
    const gate = gates.get(gateId)
    if (!gate) {
      gateProblems.push(`真实门禁 ${gateId} 缺项`)
      continue
    }
    if (gate.status === 'PASS') {
      if (gate.evidencePath && !existsSync(resolve(root, gate.evidencePath))) {
        gateProblems.push(`真实门禁 ${gateId} 证据文件不存在：${gate.evidencePath}`)
      }
    } else if (!gate.condition) {
      gateProblems.push(`真实门禁 ${gateId} 状态 ${gate.status} 未注明所缺条件`)
    }
  }
  const authorizationGate = gates.get('production-authorization')
  verdict.realQualified = REAL_GATE_IDS
    .filter((id) => id !== 'production-authorization')
    .every((id) => gates.get(id)?.status === 'PASS') && gateProblems.length === 0
  verdict.releaseAuthorized = verdict.realQualified && authorizationGate?.status === 'PASS'

  if (mode === 'beta') {
    if (!verdict.localAccepted) {
      blockers.push('beta 要求 local 全绿（当前 local 未通过）')
    }
    if (!verdict.realQualified) {
      blockers.push(...gateProblems)
      for (const id of REAL_GATE_IDS.filter((entry) => entry !== 'production-authorization')) {
        const gate = gates.get(id)
        if (!gate || gate.status !== 'PASS') {
          blockers.push(`真实门禁未通过：${id}（${gate?.condition ?? '缺项'}）`)
        }
      }
    }
    // beta 模式下 RELEASE_AUTHORIZED 仍独立：未经用户授权一律 false。
  }

  return verdict
}

// ---------------------------------------------------------------------------
// CLI
// ---------------------------------------------------------------------------

function main(argv: string[]): number {
  const args = new Map<string, string>()
  for (let index = 0; index < argv.length; index += 2) {
    args.set(argv[index], argv[index + 1] ?? '')
  }
  const evidencePath = args.get('--evidence')
  const mode = args.get('--mode')
  if (!evidencePath || (mode !== 'local' && mode !== 'beta')) {
    console.error('用法：--evidence <path> --mode local|beta')
    return 2
  }
  const root = process.cwd()
  const absolute = resolve(root, evidencePath)
  if (!existsSync(absolute)) {
    console.error(`证据文件不存在：${evidencePath}`)
    return 2
  }
  let evidence: ReleaseEvidence
  try {
    evidence = JSON.parse(readFileSync(absolute, 'utf8')) as ReleaseEvidence
  } catch (error) {
    console.error(`证据文件解析失败：${(error as Error).message}`)
    return 2
  }
  const verdict = evaluateRelease(root, evidence, mode)
  console.log(JSON.stringify(verdict, null, 2))
  const pass = mode === 'local' ? verdict.localAccepted : verdict.localAccepted && verdict.realQualified
  if (!pass) {
    console.error(`未通过（${mode}）：`)
    for (const blocker of verdict.blockers) {
      console.error(`  blocker: ${blocker}`)
    }
    return 1
  }
  console.error(`通过（${mode}）：LOCAL_ACCEPTED=${verdict.localAccepted} REAL_QUALIFIED=${verdict.realQualified}`
    + ` RELEASE_AUTHORIZED=${verdict.releaseAuthorized}（生产授权永远单独由用户给出）`)
  return 0
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(import.meta.filename)) {
  process.exit(main(process.argv.slice(2)))
}
