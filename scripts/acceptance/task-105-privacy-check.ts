import { readFileSync, readdirSync, statSync, mkdirSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'

/**
 * 任务书 #105G C105G-05（V105G-05-05）：数字人测试产物隐私扫描 CLI。
 *
 * 契约（任务书「目标签名/形状」行）：
 * - `--artifact-root test-artifacts/task-105 --manifest tests/fixtures/digital-human/privacy-manifest.json`
 * - 只读扫描（零外发：本脚本不发起任何网络请求）、命中受禁 canary 退出 1；
 * - 合成 fixture 源文件排除（仅对 prompt 正文豁免），日志永不排除；
 * - 原 mic/key/grant/SDP 永远禁止（fixture 源文件也不豁免）；
 * - 空 artifact 目录不能通过（没有产物可证 ≠ 通过）。
 */

export interface PrivacyManifest {
  version: number
  canaries: Record<string, string>
  grantPattern: string
  alwaysForbidden: string[]
  fixtureExempt: string[]
  fixtureSourceGlobs: string[]
}

export interface Violation {
  kind: 'canary' | 'grant' | 'empty-root' | 'manifest'
  canary?: string
  file?: string
  detail: string
}

export interface ScanReport {
  artifactRoot: string
  scannedFiles: number
  skippedFixtureFiles: number
  violations: Violation[]
}

/** glob 极简匹配：仅支持本 manifest 使用的双星目录前缀、尾部双星（任意深度）与单星段。 */
function globToRegExp(glob: string): RegExp {
  // 哨兵两阶段替换：先吸收双星，避免插入物里的 `*` 被单星规则二次改写
  //（哨兵为可打印且不出现在转义后 glob 中的保留词）。
  const escaped = glob.replace(/[.+^${}()|[\]\\]/g, '\\$&')
    .replace(/\*\*\//g, '__GLOB_DIR_STAR__')
    .replace(/\*\*/g, '__GLOB_ANY_STAR__')
    .replace(/\*/g, '[^/]*')
    .replace(/__GLOB_DIR_STAR__/g, '(?:.*/)?')
    .replace(/__GLOB_ANY_STAR__/g, '.*')
  return new RegExp(`^${escaped}$`)
}

export function loadManifest(path: string): PrivacyManifest {
  const raw = JSON.parse(readFileSync(path, 'utf8')) as PrivacyManifest
  const violations: Violation[] = []
  if (raw?.version !== 1) violations.push({ kind: 'manifest', detail: 'manifest.version 必须为 1' })
  for (const name of ['prompt', 'mic', 'key', 'sdp'] as const) {
    if (typeof raw?.canaries?.[name] !== 'string' || raw.canaries[name].length < 8) {
      violations.push({ kind: 'manifest', detail: `canaries.${name} 缺失或过短` })
    }
  }
  if (typeof raw?.grantPattern !== 'string' || raw.grantPattern.length < 8) {
    violations.push({ kind: 'manifest', detail: 'grantPattern 缺失' })
  }
  if (violations.length > 0) {
    throw new Error(`隐私 manifest 非法：${violations.map((v) => v.detail).join('；')}`)
  }
  return raw
}

function isFixtureSource(relativePath: string, manifest: PrivacyManifest): boolean {
  return manifest.fixtureSourceGlobs.some((glob) => globToRegExp(glob).test(relativePath))
}

/** 单文件扫描：返回该文件的违规（fixture 源文件只对 fixtureExempt 类 marker 豁免）。 */
export function scanBuffer(relativePath: string, buffer: Buffer, manifest: PrivacyManifest): Violation[] {
  const text = buffer.toString('latin1') // 逐字节扫描：UTF-8 marker 字节序列在 latin1 视图下保持原样
  const violations: Violation[] = []
  const fixtureSource = isFixtureSource(relativePath, manifest)
  for (const [name, marker] of Object.entries(manifest.canaries)) {
    if (buffer.includes(Buffer.from(marker, 'utf8'))) {
      const alwaysForbidden = manifest.alwaysForbidden.includes(name)
      const exempted = fixtureSource && manifest.fixtureExempt.includes(name)
      if (alwaysForbidden || !exempted) {
        violations.push({
          kind: 'canary',
          canary: name,
          file: relativePath,
          detail: `${relativePath} 含受禁 ${name} 标记${exempted ? '（该类标记不允许任何豁免）' : fixtureSource ? '' : '（非 fixture 源文件无豁免）'}`,
        })
      }
    }
  }
  const grant = new RegExp(manifest.grantPattern, 'g')
  if (grant.test(text)) {
    violations.push({ kind: 'grant', file: relativePath, detail: `${relativePath} 含 grant 形态凭据值` })
  }
  return violations
}

function walk(root: string, prefix: string): string[] {
  const entries: string[] = []
  for (const name of readdirSync(root)) {
    const abs = resolve(root, name)
    const rel = prefix ? `${prefix}/${name}` : name
    if (statSync(abs).isDirectory()) {
      entries.push(...walk(abs, rel))
    } else {
      entries.push(rel)
    }
  }
  return entries
}

/** 目录树扫描：只读、零外发；空目录=无证据，不能通过。 */
export function scanTree(artifactRoot: string, manifest: PrivacyManifest): ScanReport {
  const violations: Violation[] = []
  let scanned = 0
  let skipped = 0
  let files: string[]
  try {
    files = walk(artifactRoot, '')
  } catch (error) {
    return {
      artifactRoot,
      scannedFiles: 0,
      skippedFixtureFiles: 0,
      violations: [{ kind: 'empty-root', detail: `artifact-root 不可读：${(error as Error).message}` }],
    }
  }
  if (files.length === 0) {
    return {
      artifactRoot,
      scannedFiles: 0,
      skippedFixtureFiles: 0,
      violations: [{ kind: 'empty-root', detail: 'artifact-root 为空：没有可证产物，扫描不能通过' }],
    }
  }
  for (const relativePath of files) {
    if (isFixtureSource(relativePath, manifest)) {
      skipped += 1
    }
    const buffer = readFileSync(resolve(artifactRoot, relativePath))
    scanned += 1
    violations.push(...scanBuffer(relativePath, buffer, manifest))
  }
  return { artifactRoot, scannedFiles: scanned, skippedFixtureFiles: skipped, violations }
}

async function main(): Promise<void> {
  const args = process.argv.slice(2)
  const valueOf = (flag: string): string | undefined => {
    const index = args.indexOf(flag)
    return index >= 0 ? args[index + 1] : undefined
  }
  const artifactRoot = valueOf('--artifact-root') ?? 'test-artifacts/task-105'
  const manifestPath = valueOf('--manifest')
  if (!manifestPath) {
    console.error('用法：task-105-privacy-check.ts --artifact-root <dir> --manifest <privacy-manifest.json>')
    process.exit(2)
  }
  const manifest = loadManifest(manifestPath)
  const report = scanTree(resolve(artifactRoot), manifest)
  const outputDir = resolve('test-artifacts/task-105/G/C105G-05')
  mkdirSync(outputDir, { recursive: true })
  writeFileSync(resolve(outputDir, 'privacy-scan-report.json'), `${JSON.stringify(report, null, 2)}\n`, 'utf8')
  console.log(
    `privacy-scan root=${artifactRoot} files=${report.scannedFiles} fixtureSkipped=${report.skippedFixtureFiles}`
    + ` violations=${report.violations.length}`,
  )
  for (const violation of report.violations) {
    console.log(`  [${violation.kind}] ${violation.detail}`)
  }
  if (report.violations.length > 0) {
    process.exit(1)
  }
}

if (process.argv[1] && resolve(process.argv[1]).endsWith('task-105-privacy-check.ts')) {
  await main()
}
