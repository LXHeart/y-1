import { readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'

/**
 * 任务书 #103 C103-22：测试耗时/失败分类汇总工具。
 *
 * 只读 Vitest JSON 与 JUnit XML 报告，按 suite/test 分类耗时、timeout、worker error
 * 与业务断言失败，输出无敏感数据的确定性 JSON（同输入必同输出，TC103-22-02）。
 * 坏报告/空文件显式非零退出，不把缺数据当 0 失败（TC103-22-01/E05/E08）。
 * 本工具不修改任何阈值；`--output` 是唯一写副作用。
 */

export type FailureCategory = 'timeout' | 'worker_error' | 'assertion' | 'other'
export type TestCaseStatus = 'passed' | 'failed' | 'skipped'

export interface TestCaseSummary {
  suite: string
  name: string
  status: TestCaseStatus
  durationMs: number
  category?: FailureCategory
  excerpt?: string
}

export interface ReportSummary {
  meta: {
    sources: Array<{ path: string; format: 'vitest-json' | 'junit-xml'; bytes: number }>
    suites: number
  }
  counts: { total: number; passed: number; failed: number; skipped: number }
  /** 总耗时只累加报告里给出的用例时长，缺时长的报告计入 timedUnknown。 */
  durationMs: { total: number; timedUnknown: number; slowest: TestCaseSummary[] }
  failures: Array<{ suite: string; name: string; category: FailureCategory; excerpt: string }>
  /** 资源上下文：timeout/worker 错误单独可见，缺陷失败与资源超时不混算（TC103-22-03）。 */
  resource: { timeout: number; workerError: number }
}

const EXCERPT_LIMIT = 200
const SLOWEST_LIMIT = 10

/** 常见凭据形态脱敏；截断前先脱敏，保证 excerpt 不带密钥。 */
export function redactSecrets(text: string): string {
  return text
    .replace(/sk-[A-Za-z0-9_-]{8,}/g, '[REDACTED]')
    .replace(/Bearer\s+[A-Za-z0-9._~+/=-]{8,}/gi, 'Bearer [REDACTED]')
    .replace(/\b(password|passwd|secret|token|api[-_]?key)\b\s*[:=]\s*\S+/gi,
      (_all, key: string) => `${key}=[REDACTED]`)
}

export function excerpt(text: string): string {
  const firstLine = redactSecrets(text.trim().split(/\r?\n/)[0] ?? '')
  return firstLine.length > EXCERPT_LIMIT ? `${firstLine.slice(0, EXCERPT_LIMIT)}…` : firstLine
}

export function classifyFailure(message: string): FailureCategory {
  if (/timed?\s*out|timeout|exceeded\s+\d+\s*ms|deadline/i.test(message)) return 'timeout'
  if (/unhandled\s+(rejection|error)|worker|segfault|heap out of memory|out\s+of\s+memory|process exited|aborted/i.test(message)) {
    return 'worker_error'
  }
  if (/assertionerror|expected .+ to (be|equal|contain|have|match|throw)|expect\(/i.test(message)) {
    return 'assertion'
  }
  return 'other'
}

function decodeXmlEntities(text: string): string {
  return text
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&#(\d+);/g, (_m, code: string) => String.fromCodePoint(Number(code)))
    .replace(/&amp;/g, '&')
}

function xmlAttribute(tag: string, name: string): string | undefined {
  const match = tag.match(new RegExp(`\\b${name}="([^"]*)"`))
  return match ? decodeXmlEntities(match[1]) : undefined
}

interface RawCase {
  suite: string
  name: string
  status: TestCaseStatus
  durationMs: number
  failureMessage?: string
}

/** Vitest JSON reporter 输出（`vitest run --reporter=json --outputFile=...`）。 */
export function parseVitestJson(content: string): RawCase[] {
  let parsed: unknown
  try {
    parsed = JSON.parse(content)
  } catch (error) {
    throw new Error(`invalid vitest JSON: ${(error as Error).message}`, { cause: error })
  }
  if (typeof parsed !== 'object' || parsed === null || !Array.isArray((parsed as { testResults?: unknown }).testResults)) {
    throw new Error('invalid vitest JSON: missing testResults array')
  }
  const cases: RawCase[] = []
  for (const file of (parsed as { testResults: Array<{ name?: string; assertionResults?: Array<Record<string, unknown>> }> }).testResults) {
    const suite = file.name ?? '(unnamed file)'
    for (const assertion of file.assertionResults ?? []) {
      const rawStatus = String(assertion.status ?? '')
      if (rawStatus !== 'passed' && rawStatus !== 'failed' && rawStatus !== 'skipped' && rawStatus !== 'todo') {
        throw new Error(`invalid vitest JSON: unknown assertion status ${JSON.stringify(rawStatus)}`)
      }
      const messages = Array.isArray(assertion.failureMessages) ? (assertion.failureMessages as string[]) : []
      cases.push({
        suite,
        name: String(assertion.fullName ?? assertion.title ?? '(unnamed test)'),
        status: rawStatus === 'todo' ? 'skipped' : (rawStatus as TestCaseStatus),
        durationMs: Number.isFinite(Number(assertion.duration)) ? Number(assertion.duration) : -1,
        failureMessage: rawStatus === 'failed' ? messages.join('\n') || '(no failure message)' : undefined,
      })
    }
  }
  return cases
}

/** JUnit XML（Gradle `build/test-results` 下的 XML、vitest junit reporter 同构）。 */
export function parseJUnitXml(content: string): RawCase[] {
  if (!/<testsuites?[\s>]/.test(content)) {
    throw new Error('invalid JUnit XML: no testsuite(s) element')
  }
  const cases: RawCase[] = []
  // 逐 testcase 扫描；配对分支禁止属性以 / 结尾（那是自闭合标签，交给第二分支），
  // 否则一个自闭合用例会把后续用例的 </testcase> 吞成自己的结尾。
  const testcaseRe = /<testcase\b([^>/]*)>([\s\S]*?)<\/testcase>|<testcase\b([^>]*)\/>/g
  for (const match of content.matchAll(testcaseRe)) {
    const attrs = match[1] ?? match[3] ?? ''
    const body = match[2] ?? ''
    const name = xmlAttribute(attrs, 'name')
    if (!name) throw new Error('invalid JUnit XML: testcase without name')
    const duration = Number(xmlAttribute(attrs, 'time') ?? '-1')
    const failure = body.match(/<(failure|error)\b([^>]*)>([\s\S]*?)<\/\1>/)
    const skipped = /<skipped\b/.test(body)
    let status: TestCaseStatus = 'passed'
    let failureMessage: string | undefined
    if (failure) {
      status = 'failed'
      const messageAttr = xmlAttribute(failure[2], 'message')
      failureMessage = messageAttr || failure[3].trim() || '(no failure message)'
    } else if (skipped) {
      status = 'skipped'
    }
    cases.push({
      suite: xmlAttribute(attrs, 'classname') ?? '(unknown suite)',
      name,
      status,
      durationMs: Number.isFinite(duration) && duration >= 0 ? Math.round(duration * 1000) : -1,
      failureMessage,
    })
  }
  return cases
}

export function detectFormat(path: string, content: string): 'vitest-json' | 'junit-xml' {
  if (path.endsWith('.json') || content.trimStart().startsWith('{')) return 'vitest-json'
  return 'junit-xml'
}

export function summarize(inputs: Array<{ path: string; content: string }>): ReportSummary {
  if (inputs.length === 0) throw new Error('no report inputs')
  const sources: ReportSummary['meta']['sources'] = []
  const cases: RawCase[] = []
  for (const input of inputs) {
    if (input.content.trim().length === 0) {
      throw new Error(`empty report: ${input.path}`)
    }
    const format = detectFormat(input.path, input.content)
    const parsed = format === 'vitest-json' ? parseVitestJson(input.content) : parseJUnitXml(input.content)
    sources.push({ path: input.path, format, bytes: Buffer.byteLength(input.content, 'utf8') })
    cases.push(...parsed)
  }
  const summaries: TestCaseSummary[] = cases.map((c) => ({
    suite: c.suite,
    name: c.name,
    status: c.status,
    durationMs: c.durationMs,
    ...(c.failureMessage !== undefined
      ? { category: classifyFailure(c.failureMessage), excerpt: excerpt(c.failureMessage) }
      : {}),
  }))
  const failures = summaries.filter((c) => c.status === 'failed')
  return {
    meta: { sources, suites: new Set(summaries.map((c) => c.suite)).size },
    counts: {
      total: summaries.length,
      passed: summaries.filter((c) => c.status === 'passed').length,
      failed: failures.length,
      skipped: summaries.filter((c) => c.status === 'skipped').length,
    },
    durationMs: {
      total: summaries.reduce((sum, c) => (c.durationMs > 0 ? sum + c.durationMs : sum), 0),
      timedUnknown: summaries.filter((c) => c.durationMs < 0).length,
      slowest: [...summaries]
        .filter((c) => c.durationMs > 0)
        .sort((a, b) => b.durationMs - a.durationMs || a.name.localeCompare(b.name))
        .slice(0, SLOWEST_LIMIT),
    },
    failures: failures.map(({ suite, name, category, excerpt: e }) => ({ suite, name, category, excerpt: e ?? '' })),
    resource: {
      timeout: failures.filter((f) => f.category === 'timeout').length,
      workerError: failures.filter((f) => f.category === 'worker_error').length,
    },
  }
}

/** 展开输入路径：目录递归收集 JUnit XML（Gradle `build/test-results/**`），文件原样返回。 */
export function expandInputs(paths: string[]): string[] {
  const expanded: string[] = []
  const walk = (dir: string): void => {
    for (const entry of readdirSync(dir).sort()) {
      const child = `${dir}/${entry}`
      if (statSync(child).isDirectory()) {
        walk(child)
      } else if (entry.endsWith('.xml')) {
        expanded.push(child)
      }
    }
  }
  for (const path of paths) {
    if (statSync(path).isDirectory()) {
      walk(path)
    } else {
      expanded.push(path)
    }
  }
  return expanded
}

function main(): void {
  const args = process.argv.slice(2)
  const inputs: string[] = []
  let output: string | undefined
  for (let i = 0; i < args.length; i += 1) {
    if (args[i] === '--input' && args[i + 1]) {
      inputs.push(args[i + 1])
      i += 1
    } else if (args[i] === '--output' && args[i + 1]) {
      output = args[i + 1]
      i += 1
    } else {
      throw new Error(`usage: tsx scripts/quality/summarize-test-runtime.ts --input <报告路径|目录> [--input ...] --output <JSON路径>`)
    }
  }
  if (inputs.length === 0 || !output) {
    throw new Error('usage: tsx scripts/quality/summarize-test-runtime.ts --input <报告路径|目录> [--input ...] --output <JSON路径>')
  }
  const summary = summarize(expandInputs(inputs).map((path) => ({ path, content: readFileSync(resolve(path), 'utf8') })))
  writeFileSync(resolve(output), `${JSON.stringify(summary, null, 2)}\n`, 'utf8')
  console.log(
    `tests=${summary.counts.total} passed=${summary.counts.passed} failed=${summary.counts.failed} `
    + `skipped=${summary.counts.skipped} timeouts=${summary.resource.timeout} workerErrors=${summary.resource.workerError}`,
  )
}

if (process.argv[1] && resolve(process.argv[1]).endsWith('summarize-test-runtime.ts')) {
  main()
}
