#!/usr/bin/env node
// hypit-fix3-results.mjs — 107-fix-3 验收报告结构解析与 Gate 判定（W053；C107F3-13）。
//
// 职责（任务书 §12.3 条6 / RULE-015）：
//   - JUnit XML / node:test TAP / Vitest JSON 按真实结构解析 testcase；
//     skipped/todo/failure/error/0test/非法报告（行数与尾部计数不符、XML 无 testcase）
//     一律显式计数，禁止硬填 skipped=0。
//   - expected 子项核验：必需 TC 缺失（被过滤/skip/证据被删）即问题。
//   - run 身份：commit + 在途 diff 摘要 → sourceDigest；续跑（--run 已有标识）必须
//     核验源码摘要一致。
//   - 面向 W049/W050/W052 的 CLI 子命令（同模块导出供 vitest 契约测试直接调用）。
//
// 本模块只解析与判定，不执行任何测试、不启动任何服务。
import { createHash } from 'node:crypto'
import { existsSync, lstatSync, readFileSync, readlinkSync, writeFileSync } from 'node:fs'
import { execFileSync } from 'node:child_process'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const TASK_BOOK_VERSION = '107-fix-3 v1.1.0'
// TC 命名族：fix2=TC-F2-nn-nn、fix3=TC-F3-nn-nn；统一宽松发现，期望值由调用方给定。
const TC_PATTERN = /TC-[A-Z0-9]+-\d{2}-\d{2}/g

export class ReportError extends Error {}

function sha256(text) {
  return createHash('sha256').update(text).digest('hex')
}

export function sha256File(path) {
  return sha256(readFileSync(path))
}

function emptyStats() {
  return { total: 0, passed: 0, failed: 0, skipped: 0, todo: 0, cancelled: 0, executed: 0 }
}

export function discoverTcs(...texts) {
  const found = new Set()
  for (const text of texts) {
    if (!text) continue
    for (const m of String(text).matchAll(TC_PATTERN)) found.add(m[0])
  }
  return [...found].sort()
}

// ── JUnit XML（Gradle / Playwright / 任何 junit 格式） ──────────────────────
// 按 <testcase 开标签切块（不依赖 </testcase> 闭合位置——Playwright 的 failure
// 常出现在 system-out 之后，紧邻式正则会漏检；fix2 round-7 实录）。
export function parseJunit(xmlText) {
  if (typeof xmlText !== 'string' || xmlText.trim().length === 0) {
    throw new ReportError('JUnit 报告为空或不可读')
  }
  const starts = []
  for (const m of xmlText.matchAll(/<testcase\b/g)) starts.push(m.index)
  if (starts.length === 0) {
    throw new ReportError('JUnit 报告无任何 <testcase> 元素（非法报告）')
  }
  const cases = []
  const stats = emptyStats()
  for (let i = 0; i < starts.length; i += 1) {
    const chunk = xmlText.slice(starts[i], i + 1 < starts.length ? starts[i + 1] : undefined)
    const open = chunk.match(/<testcase\b[^>]*>/)
    if (!open) continue
    const name = open[0].match(/\bname="([^"]*)"/)?.[1] ?? ''
    const classname = open[0].match(/\bclassname="([^"]*)"/)?.[1] ?? ''
    let status = 'passed'
    if (/<skipped[\s/>]/.test(chunk)) status = 'skipped'
    else if (/<failure[\s>]/.test(chunk)) status = 'failed'
    else if (/<error[\s>]/.test(chunk)) status = 'error'
    cases.push({ name, classname, status })
    stats.total += 1
    if (status === 'skipped') stats.skipped += 1
    else if (status === 'failed' || status === 'error') stats.failed += 1
    else stats.passed += 1
  }
  // 结构交叉核验：testsuite 声明的 tests 总数与实切块数不符 → 报告被裁剪/篡改。
  const declared = [...xmlText.matchAll(/<testsuite\b[^>]*\btests="(\d+)"/g)]
    .reduce((acc, m) => acc + Number(m[1]), 0)
  if (declared > 0 && declared !== stats.total) {
    throw new ReportError(`JUnit testcase 块数(${stats.total})与 testsuite 声明(${declared})不符（非法报告）`)
  }
  stats.executed = stats.passed + stats.failed
  return { stats, cases, invalid: false }
}

// ── node:test TAP（叶子用例；suite 行不计入 testcase） ─────────────────────
// 结构：ok/not ok 行 + 随后的 YAML 块（type: 'test' | 'suite'）；skip/todo 以
// `# SKIP`/`# TODO` 指示器出现；尾部 `# tests/# pass/# fail/# skipped/# todo`
// 计数与逐行解析交叉核验。
export function parseTap(text) {
  if (typeof text !== 'string' || text.trim().length === 0) {
    throw new ReportError('TAP 报告为空或不可读')
  }
  const lines = text.split('\n')
  const cases = []
  const stats = emptyStats()
  let cancelled = 0
  for (let i = 0; i < lines.length; i += 1) {
    const m = lines[i].match(/^\s*(not )?ok \d+ - (.*?)(?:\s+# (SKIP|TODO)(?:\s+(.*))?)?\s*$/)
    if (!m) continue
    const ok = !m[1]
    const directive = m[3] ?? ''
    // 找该行随后的 YAML 块中的 type/failureType（suite 行是容器，不算 testcase）。
    let type = 'test'
    let failureType = ''
    for (let j = i + 1; j < Math.min(i + 40, lines.length); j += 1) {
      if (/^\s*\.\.\.\s*$/.test(lines[j])) break
      const t = lines[j].match(/^\s*type:\s*'(\w+)'/)
      if (t) type = t[1]
      const f = lines[j].match(/^\s*failureType:\s*'(\w+)'/)
      if (f) failureType = f[1]
    }
    if (type !== 'test') continue
    const name = m[2].trim()
    // skip/todo 指示器后的自由文本=用例自身的跳过原因（C107F3-04：EXTERNAL_BLOCKED
    // 声明的窄豁免依据；原因原文必须可追溯，不吞进无名计数）。
    const skipReason = (m[4] ?? '').trim()
    let status
    if (directive === 'SKIP') { status = 'skipped'; stats.skipped += 1 }
    else if (directive === 'TODO') { status = 'todo'; stats.todo += 1 }
    else if (ok) { status = 'passed'; stats.passed += 1 }
    else if (failureType === 'testCancelled' || failureType === 'cancelled') { status = 'cancelled'; cancelled += 1 }
    else { status = 'failed'; stats.failed += 1 }
    cases.push({ name, status, reason: skipReason })
    stats.total += 1
  }
  if (stats.total === 0) throw new ReportError('TAP 报告无任何叶子用例行（非法报告）')
  const counter = (key) => Number(text.match(new RegExp(`^# ${key} (\\d+)`, 'm'))?.[1] ?? '-1')
  const expected = { tests: stats.total, pass: stats.passed, fail: stats.failed, skipped: stats.skipped, todo: stats.todo }
  for (const [key, value] of Object.entries(expected)) {
    const actual = counter(key)
    if (actual !== -1 && actual !== value) {
      throw new ReportError(`TAP 尾部计数 #${key}=${actual} 与逐行解析(${value})不符（非法报告）`)
    }
  }
  stats.cancelled = cancelled
  stats.executed = stats.total
  return { stats, cases, invalid: false, cancelled }
}

// ── Vitest JSON ─────────────────────────────────────────────────────────────
export function parseVitest(jsonText) {
  let data
  try {
    data = JSON.parse(jsonText)
  } catch (e) {
    throw new ReportError(`Vitest JSON 不可解析：${e.message}`)
  }
  if (!data || typeof data !== 'object' || !Array.isArray(data.testResults)) {
    throw new ReportError('Vitest JSON 缺 testResults（非法报告）')
  }
  const stats = emptyStats()
  const cases = []
  for (const suite of data.testResults) {
    for (const a of suite.assertionResults ?? []) {
      const status = a.status === 'pending' ? 'skipped' : a.status
      cases.push({ name: a.fullName ?? a.title ?? '', status })
      stats.total += 1
      if (status === 'skipped') stats.skipped += 1
      else if (status === 'todo') stats.todo += 1
      else if (status === 'failed') stats.failed += 1
      else if (status === 'passed') stats.passed += 1
      else stats.failed += 1 // 未知状态按失败处理（不静默放行）
    }
  }
  // 交叉核验：顶层计数与逐条状态和不符 → 非法报告。
  const top = {
    total: data.numTotalTests, passed: data.numPassedTests, failed: data.numFailedTests,
    skipped: (data.numPendingTests ?? 0) + (data.numSkippedTests ?? 0), todo: data.numTodoTests ?? 0,
  }
  for (const [key, value] of Object.entries(top)) {
    if (typeof value === 'number' && value !== stats[key]) {
      throw new ReportError(`Vitest 顶层 ${key}=${value} 与逐条解析(${stats[key]})不符（非法报告）`)
    }
  }
  stats.executed = stats.passed + stats.failed
  return { stats, cases, invalid: false }
}

// ── 期望核验 ────────────────────────────────────────────────────────────────
// 必需 TC 缺失 / 失败 / skip / todo / 0 执行 / 非法报告 → 问题列表（不为空即非零）。
// allowDeclaredExternalSkip（C107F3-04 §13.3，默认关）：仅当用例自身以
// EXTERNAL_BLOCKED 声明跳过原因（如 whisperx 真实 ASR——§1.4 范围外、运维工作）
// 且旗标显式开启时，该 skip 计实数 externalBlocked 不记问题；非声明 skip 仍为
// 问题。实数永远如实保留在 stats.skipped 与 cases[].reason。
export function evaluate({ label, stats, cases, expectTcs = [], invalid = null, allowDeclaredExternalSkip = false }) {
  const problems = []
  let externalBlocked = 0
  if (invalid) problems.push(`${label}: 非法报告（${invalid}）`)
  else if (!stats) problems.push(`${label}: 无解析结果`)
  else {
    if (stats.executed <= 0) problems.push(`${label}: executed=0（零用例不能判绿）`)
    if (stats.failed > 0) problems.push(`${label}: failed=${stats.failed}`)
    if (stats.skipped > 0) {
      externalBlocked = allowDeclaredExternalSkip
        ? (cases ?? []).filter((c) => c.status === 'skipped' && /EXTERNAL_BLOCKED/.test(c.reason ?? '')).length
        : 0
      const hardSkips = stats.skipped - externalBlocked
      if (hardSkips > 0) problems.push(`${label}: skipped=${stats.skipped}（其中非EXTERNAL_BLOCKED声明skip=${hardSkips}，仍然拒绝）`)
    }
    if (stats.todo > 0) problems.push(`${label}: todo=${stats.todo}`)
    if ((stats.cancelled ?? 0) > 0) problems.push(`${label}: cancelled=${stats.cancelled}`)
    const found = new Set(discoverTcs((cases ?? []).map((c) => c.name).join(' ')))
    for (const tc of expectTcs) {
      if (!found.has(tc)) problems.push(`${label}: 缺必需TC=${tc}`)
    }
  }
  return { ok: problems.length === 0, problems, found: discoverTcs((cases ?? []).map((c) => c.name).join(' ')), externalBlocked }
}

// ── run 身份 ────────────────────────────────────────────────────────────────
export function repoRoot() {
  return resolve(dirname(fileURLToPath(import.meta.url)), '../..')
}

function git(args, root) {
  return execFileSync('git', ['-C', root, ...args], { encoding: 'utf8', maxBuffer: 128 * 1024 * 1024 })
}

export function runIdentity(root = repoRoot()) {
  const commit = git(['rev-parse', 'HEAD'], root).trim()
  const porcelain = git(['status', '--porcelain'], root).trim()
  const diffSummary = porcelain.split('\n').slice(0, 20).join(';')
  // A second edit to an already-dirty file leaves porcelain unchanged. Include
  // actual staged/unstaged bytes and untracked source, never ignored artifacts.
  const diff = git(['diff', '--binary', '--no-ext-diff', '--no-textconv', 'HEAD', '--'], root)
  const untracked = git(['ls-files', '--others', '--exclude-standard', '-z'], root)
    .split('\0').filter(Boolean).sort().map(path => {
      const absolute = resolve(root, path)
      const stat = lstatSync(absolute)
      const bytes = stat.isSymbolicLink() ? readlinkSync(absolute) : readFileSync(absolute)
      return [path, stat.mode, sha256(bytes)]
    })
  const sourceDigest = sha256(JSON.stringify({ commit, diff, untracked }))
  return { commit, diffSummary, sourceDigest, taskBookVersion: TASK_BOOK_VERSION }
}

export function verifyManifest(manifestPath, baseDir) {
  const problems = []
  let manifest
  try {
    manifest = JSON.parse(readFileSync(manifestPath, 'utf8'))
  } catch (e) {
    return { ok: false, problems: [`manifest 不可读：${e.message}`] }
  }
  const base = baseDir ? resolve(dirname(manifestPath), baseDir) : dirname(manifestPath)
  const samples = manifest.samples ?? []
  if (!Array.isArray(samples) || samples.length === 0) {
    problems.push('manifest 无 samples（缺媒体清单）')
  }
  for (const sample of samples) {
    if (!sample.file || !sample.sha256) {
      problems.push(`sample 缺 file/sha256：${sample.id ?? '?'}`)
      continue
    }
    const abs = resolve(base, sample.file)
    if (!existsSync(abs)) {
      problems.push(`缺媒体=${sample.file}`)
      continue
    }
    const actual = sha256File(abs)
    if (actual !== sample.sha256) {
      problems.push(`媒体hash不符=${sample.file}（manifest=${sample.sha256} 实际=${actual}）`)
    }
    for (const frame of sample.frames ?? []) {
      if (!frame.sha256) problems.push(`frame 缺 sha256：${sample.file}@${frame.t}`)
    }
  }
  return { ok: problems.length === 0, problems, manifest }
}

// ── fix2 分层 results.json 判定（W049/W050 消费） ───────────────────────────
const FIX2_E2E_ENGINES = ['chromium', 'firefox', 'webkit']

function aggregateTests(tests) {
  return {
    executed: tests?.executed ?? 0,
    failed: tests?.failed ?? 0,
    skipped: tests?.skipped ?? 0,
    todo: typeof tests?.todo === 'number' ? tests.todo : null,
  }
}

export function evaluateLayer({ file, layer, expectTcs = [], e2eDir = null }) {
  const problems = []
  let results
  try {
    results = JSON.parse(readFileSync(file, 'utf8'))
  } catch (e) {
    return { ok: false, problems: [`${layer}: 产物不可读（${e.message}）`] }
  }
  if (results.exitCode !== 0) problems.push(`${layer}: exitCode=${results.exitCode}`)
  const agg = aggregateTests(results.tests)
  if (!results.tests) problems.push(`${layer}: 无测试计数`)
  else {
    if (agg.executed <= 0) problems.push(`${layer}: executed=0（零用例不能判绿）`)
    if (agg.failed > 0) problems.push(`${layer}: failed=${agg.failed}`)
    if (agg.skipped > 0) problems.push(`${layer}: skipped=${agg.skipped}`)
  }
  const found = new Set(results.tc?.found ?? [])
  for (const tc of expectTcs) {
    if (!found.has(tc)) problems.push(`${layer}: 缺必需TC=${tc}`)
  }
  // 新写入器（W053 生成，generator=w53）必须带真实 todo 计数；todo>0 恒失败。
  if (results.generator === 'w53') {
    if (agg.todo === null) problems.push(`${layer}: 缺 todo 计数（w53 产物必须真实解析）`)
    else if (agg.todo > 0) problems.push(`${layer}: todo=${agg.todo}`)
  }
  // e2e 层：逐引擎完整性（缺引擎 / 引擎零执行 / 与原始 JUnit 不一致=旧结果重贴）。
  if (layer === 'e2e') {
    const engines = results.engines ?? null
    if (engines) {
      for (const engine of FIX2_E2E_ENGINES) {
        const entry = engines[engine]
        if (!entry) { problems.push(`e2e: 缺引擎=${engine}`); continue }
        const estats = aggregateTests(entry.tests)
        if (!entry.tests) problems.push(`e2e/${engine}: 无测试计数`)
        else {
          if (estats.executed <= 0) problems.push(`e2e/${engine}: executed=0（零用例不能判绿）`)
          if (estats.failed > 0) problems.push(`e2e/${engine}: failed=${estats.failed}`)
          if (estats.skipped > 0) problems.push(`e2e/${engine}: skipped=${estats.skipped}`)
        }
        if (e2eDir) {
          const xmlPath = resolve(e2eDir, `playwright-junit-${engine}.xml`)
          if (!existsSync(xmlPath)) { problems.push(`e2e: 缺引擎报告=${engine}`); continue }
          try {
            const parsed = parseJunit(readFileSync(xmlPath, 'utf8'))
            const rstats = entry.tests ?? {}
            if ((rstats.executed ?? -1) !== parsed.stats.executed
              || (rstats.failed ?? -1) !== parsed.stats.failed
              || (rstats.skipped ?? -1) !== parsed.stats.skipped) {
              problems.push(`e2e/${engine}: results.json 与原始 JUnit 不一致（疑似旧结果重贴）`)
            }
          } catch (e) {
            problems.push(`e2e/${engine}: 原始 JUnit 非法（${e.message}）`)
          }
        }
      }
    }
  }
  return { ok: problems.length === 0, problems, results }
}

// fix2 stage all 的 G1～G7：按验证后层结果与 TC 目标子集推导（不按 exit0 粗赋）。
export function deriveFix2Gates({ base, contractCode }) {
  const readLayer = (p) => {
    try { return JSON.parse(readFileSync(resolve(base, p), 'utf8')) } catch { return null }
  }
  const local = readLayer('local/results.json')
  const e2e = readLayer('e2e/results.json')
  const recovery = readLayer('recovery/results.json')
  const localOk = !!local && local.exitCode === 0 && (local.tests?.executed ?? 0) > 0
    && (local.tests?.failed ?? 1) === 0 && (local.tests?.skipped ?? 1) === 0
  const e2eFound = new Set(e2e?.tc?.found ?? [])
  const has = (tcs) => tcs.every((tc) => e2eFound.has(tc))
  const e2eOk = !!e2e && e2e.exitCode === 0 && (e2e.tests?.executed ?? 0) > 0
    && (e2e.tests?.failed ?? 1) === 0 && (e2e.tests?.skipped ?? 1) === 0
  const recoveryOk = !!recovery && recovery.exitCode === 0 && (recovery.tests?.executed ?? 0) > 0
    && (recovery.tests?.failed ?? 1) === 0 && (recovery.tests?.skipped ?? 1) === 0
  const gates = {
    G1: localOk ? 'PASS' : 'NOT_RUN',
    G2: e2eOk && has(['TC-F2-36-01', 'TC-F2-36-02', 'TC-F2-36-03', 'TC-F2-36-04']) ? 'PASS' : 'NOT_RUN',
    G3: e2eOk && has(['TC-F2-37-01', 'TC-F2-37-02']) ? 'PASS' : 'NOT_RUN',
    G4: e2eOk && has(['TC-F2-37-03']) ? 'PASS' : 'NOT_RUN',
    G5: e2eOk && has(['TC-F2-37-04']) ? 'PASS' : 'NOT_RUN',
    G6: recoveryOk ? 'PASS' : 'NOT_RUN',
    G7: Number(contractCode) === 0 ? 'PASS' : 'NOT_RUN',
  }
  return { gates, local, e2e, recovery }
}

// ── CLI ─────────────────────────────────────────────────────────────────────
function parseArgs(argv) {
  const out = { _: [] }
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i]
    if (a.startsWith('--')) {
      const next = argv[i + 1]
      // 布尔旗标（如 --init）：缺值或下一个参数也是旗标时按 true 处理。
      if (next === undefined || next.startsWith('--')) out[a.slice(2)] = true
      else { out[a.slice(2)] = next; i += 1 }
    } else out._.push(a)
  }
  return out
}

function readStatsFile(path) {
  try { return JSON.parse(readFileSync(path, 'utf8')) } catch { return null }
}

function accumulate(path, entry) {
  let all = { runners: [] }
  if (path && existsSync(path)) {
    try { all = JSON.parse(readFileSync(path, 'utf8')) } catch { all = { runners: [] } }
  }
  all.runners ??= []
  all.runners.push(entry)
  if (path) writeFileSync(path, JSON.stringify(all))
}

function main() {
  const [cmd, ...rest] = process.argv.slice(2)
  const args = parseArgs(rest)
  const expectTcs = (args.expect ?? '').split(',').map((s) => s.trim()).filter(Boolean)
  const jsonOut = args['json-out'] ?? null

  const emitJson = (payload) => {
    if (jsonOut) writeFileSync(jsonOut, JSON.stringify(payload, null, 2) + '\n')
  }

  switch (cmd) {
    case 'junit':
    case 'tap':
    case 'vitest': {
      const file = args.file
      if (!file) { console.error('缺少 --file'); process.exit(2) }
      const runnerExit = Number(args['runner-exit'] ?? 0)
      let parsed = null
      let invalid = null
      try {
        const text = readFileSync(file, 'utf8')
        parsed = cmd === 'junit' ? parseJunit(text) : cmd === 'tap' ? parseTap(text) : parseVitest(text)
      } catch (e) {
        invalid = e instanceof ReportError ? e.message : String(e.message)
      }
      const label = args.label ?? cmd
      const allowExternal = Boolean(args['allow-external-blocked-skip'])
      const ev = evaluate({ label, stats: parsed?.stats, cases: parsed?.cases, expectTcs, invalid, allowDeclaredExternalSkip: allowExternal })
      // 「域执行」TC（C107F3-04 §13.3 增量）：--constitutes 声明本报告本身构成该 TC
      // 的执行证据（如 stage_domain 的 studio/media/video-clone 三域=TC-F3-04-02/03/04，
      // 其断言=本报告 executed>0 且 failed/skipped/todo=0）。仅当本报告无任何问题
      // 且子进程退出 0 时并入 found；任何失败都不登记，由 emit 缺必需TC 兜底非零
      // （RULE-002/015：报告非绿时不得把 TC 字符串计入已发现）。
      const constitutes = (args.constitutes ?? '').split(',').map((s) => s.trim()).filter(Boolean)
      const constituted = ev.problems.length === 0 && runnerExit === 0
        ? [...new Set([...ev.found, ...constitutes])]
        : ev.found
      if (args.accumulate) {
        accumulate(args.accumulate, {
          runner: label, exitCode: runnerExit, stats: parsed?.stats ?? null,
          report: { path: resolve(file), sha256: sha256File(file) },
          tc: { found: constituted }, problems: ev.problems,
          externalBlocked: ev.externalBlocked ?? 0,
        })
      }
      emitJson({ label, exitCode: runnerExit, stats: parsed?.stats ?? null, ...ev, found: constituted })
      for (const p of ev.problems) console.log(p)
      if (runnerExit !== 0) console.log(`${label}: 子进程退出码=${runnerExit}`)
      if (ev.problems.length === 0 && runnerExit === 0) {
        console.log(`OK ${label}: executed=${parsed.stats.executed} passed=${parsed.stats.passed} failed=${parsed.stats.failed} skipped=${parsed.stats.skipped} todo=${parsed.stats.todo} externalBlocked=${ev.externalBlocked ?? 0} tcFound=${constituted.length}`)
        process.exit(0)
      }
      process.exit(1)
    }
    // eslint-disable-next-line no-fallthrough
    case 'layer': {
      const label = args.label ?? args.layer ?? 'layer'
      const runnerExit = Number(args['runner-exit'] ?? 0)
      const ev = evaluateLayer({
        file: args.file, layer: args.layer ?? 'layer', expectTcs,
        e2eDir: args['e2e-dir'] ?? null,
      })
      if (args.accumulate) {
        let tests = null
        try {
          tests = JSON.parse(readFileSync(args.file, 'utf8'))?.tests ?? null
        } catch { /* 产物不可读已在 problems 中 */ }
        accumulate(args.accumulate, {
          runner: label, exitCode: runnerExit, stats: tests,
          report: { path: resolve(args.file) },
          tc: { found: ev.results?.tc?.found ?? [] }, problems: ev.problems,
        })
      }
      emitJson(ev)
      for (const p of ev.problems) console.log(p)
      process.exit(ev.ok ? 0 : 1)
    }
    // eslint-disable-next-line no-fallthrough
    case 'record': {
      // 登记无独立报告的子进程（生成器/校验器/静态检查等）。
      const problems = (args.problems ?? '').split('@@').filter(Boolean)
      accumulate(args.accumulate, {
        runner: args.label ?? 'record', exitCode: Number(args.exit ?? 0),
        stats: null, report: null, tc: { found: [] }, problems,
      })
      process.exit(0)
    }
    // eslint-disable-next-line no-fallthrough
    case 'all-summary': {
      // fix2 stage all 汇总（summary.json 形状与 fix2 契约测试兼容）。
      const base = args.base
      const contractCode = Number(args['contract-code'] ?? 0)
      const missing = (args.missing ?? '').split(' ').filter(Boolean)
      const failed = (args.failed ?? '').split(' ').filter(Boolean)
      const { gates } = deriveFix2Gates({ base, contractCode })
      const readLayer = (p) => {
        try { return JSON.parse(readFileSync(resolve(base, p), 'utf8')) } catch { return null }
      }
      const ok = missing.length === 0 && failed.length === 0
      const summary = {
        taskBook: '107-fix-2 v1.0.0', stage: 'all', card: 'C107F2-39/C107F2-40',
        exitCode: ok ? 0 : 1, commit: args.commit ?? 'unknown',
        startedAt: args.started ?? '', finishedAt: new Date().toISOString(),
        layers: { local: readLayer('local/results.json'), e2e: readLayer('e2e/results.json'), recovery: readLayer('recovery/results.json') },
        gates,
        live: 'NOT_RUN（未授权真实商业 Provider；本地结果不得表述为全平台全量通过）',
        verdict: ok
          ? '107-fix-2 本地交付验收 LOCAL_PASS（LOCAL 全层通过；LIVE NOT_RUN）'
          : 'LOCAL_INCOMPLETE',
        missing, failed,
        gateSource: 'w53:deriveFix2Gates（按验证后层结果与TC目标子集推导）',
      }
      writeFileSync(resolve(base, 'all/summary.json'), JSON.stringify(summary, null, 2) + '\n')
      console.log(`all-summary: exitCode=${summary.exitCode} gates=${JSON.stringify(gates)}`)
      process.exit(summary.exitCode)
    }
    // eslint-disable-next-line no-fallthrough
    case 'verify-manifest': {
      const ev = verifyManifest(args.file, args.base)
      emitJson(ev)
      for (const p of ev.problems) console.log(p)
      process.exit(ev.ok ? 0 : 1)
    }
    // eslint-disable-next-line no-fallthrough
    case 'identity': {
      const identity = runIdentity()
      emitJson(identity)
      console.log(`identity: commit=${identity.commit.slice(0, 12)} digest=${identity.sourceDigest.slice(0, 12)} dirty=${identity.diffSummary ? 'yes' : 'no'}`)
      process.exit(0)
    }
    // eslint-disable-next-line no-fallthrough
    case 'check-continuation': {
      // --run 已有标识续跑：manifest 必须存在且 sourceDigest 与当前一致。
      if (!args.manifest || !existsSync(args.manifest)) {
        console.log(`续跑拒绝：manifest 不存在（${args.manifest ?? '未指定'}）`)
        process.exit(1)
      }
      let manifest = null
      try { manifest = JSON.parse(readFileSync(args.manifest, 'utf8')) } catch (e) {
        console.log(`续跑拒绝：manifest 不可读（${e.message}）`)
        process.exit(1)
      }
      const current = runIdentity()
      if (manifest.sourceDigest !== current.sourceDigest) {
        console.log(`续跑拒绝：源码摘要变化（manifest=${manifest.sourceDigest.slice(0, 12)} 当前=${current.sourceDigest.slice(0, 12)}）——用 --run auto 新开 run`)
        process.exit(1)
      }
      console.log(`continuation ok：digest=${current.sourceDigest.slice(0, 12)}`)
      process.exit(0)
    }
    // eslint-disable-next-line no-fallthrough
    case 'manifest': {
      const file = args.file
      let manifest = null
      if (file && existsSync(file)) {
        try { manifest = JSON.parse(readFileSync(file, 'utf8')) } catch { manifest = null }
      }
      if (args.init) {
        const identity = runIdentity()
        manifest = {
          taskBookVersion: TASK_BOOK_VERSION, runId: args['run-id'] ?? '',
          createdAt: new Date().toISOString(), commit: identity.commit,
          diffSummary: identity.diffSummary, sourceDigest: identity.sourceDigest,
          stages: {},
        }
      }
      if (!manifest) { console.error('manifest 不存在且未给 --init'); process.exit(2) }
      if (args.stage) {
        const stage = args.stage
        manifest.stages[stage] ??= { attempts: [] }
        manifest.stages[stage].attempts.push({
          attempt: Number(args.attempt ?? 1), dir: args['stage-dir'] ?? '',
          exitCode: Number(args.exit ?? 0), finishedAt: new Date().toISOString(),
        })
      }
      writeFileSync(file, JSON.stringify(manifest, null, 2) + '\n')
      process.exit(0)
    }
    // eslint-disable-next-line no-fallthrough
    case 'emit': {
      // 汇总 accumulate 文件 → stage results.json。
      const acc = readStatsFile(args.accumulate) ?? { runners: [] }
      const tests = { total: 0, passed: 0, failed: 0, skipped: 0, todo: 0, executed: 0 }
      const tcFound = new Set(); const problems = []
      let anyRunnerFail = false
      for (const r of acc.runners ?? []) {
        if (r.stats) {
          for (const k of Object.keys(tests)) tests[k] += r.stats[k] ?? 0
        } else if ((r.exitCode ?? 0) !== 0 || (r.problems?.length ?? 0) > 0) {
          anyRunnerFail = true
        }
        for (const t of r.tc?.found ?? []) tcFound.add(t)
        for (const p of r.problems ?? []) problems.push(p)
        if ((r.exitCode ?? 0) !== 0) anyRunnerFail = true
      }
      // tcFailed：失败用例名中的 TC（从 runner problems 无法取到名字，按 problems 缺失数近似；
      // 失败 TC 由各 runner 的 evaluate problems（failed>0）与原始报告共同定位）。
      const expected = (args.expect ?? '').split(',').map((s) => s.trim()).filter(Boolean)
      const missing = expected.filter((tc) => !tcFound.has(tc))
      const exitCode = (problems.length > 0 || anyRunnerFail || missing.length > 0)
        ? 1 : Number(args['child-exit'] ?? 0) !== 0 ? 1 : 0
      const results = {
        taskBook: TASK_BOOK_VERSION, stage: args.stage ?? '', runId: args['run-id'] ?? '',
        attempt: Number(args.attempt ?? 1), generator: 'w53',
        exitCode, commit: args.commit ?? '', sourceDigest: args.digest ?? '',
        startedAt: args.started ?? '', finishedAt: new Date().toISOString(),
        node: process.version, java: args.java ?? null,
        runners: acc.runners ?? [], tests, tc: { expected, found: [...tcFound].sort(), missing },
        verdict: exitCode === 0
          ? 'FIXTURE_TOOLING_PASS（本次仅证明工具/契约测试通过，不构成业务卡通过声明）'
          : 'FAIL',
        live: 'NOT_RUN（本书不含真实商业模型/生产验收）',
        problems,
      }
      writeFileSync(args.out, JSON.stringify(results, null, 2) + '\n')
      console.log(`stage ${args.stage}: exitCode=${exitCode} executed=${tests.executed} failed=${tests.failed} skipped=${tests.skipped} todo=${tests.todo} tcFound=${tcFound.size} problems=${problems.length}`)
      for (const p of problems) console.log(`  - ${p}`)
      for (const tc of missing) console.log(`  - 缺必需TC=${tc}`)
      process.exit(exitCode)
    }
    // eslint-disable-next-line no-fallthrough
    case 'final-gates': {
      // V-15 all：G1～G7（§12.5）按本 run 各 stage 的已验证结果推导。
      const runDir = args['run-dir']
      const readStage = (stage) => {
        try {
          const m = JSON.parse(readFileSync(resolve(runDir, 'manifest.json'), 'utf8'))
          const attempts = m.stages?.[stage]?.attempts ?? []
          const last = attempts[attempts.length - 1]
          if (!last) return null
          const r = JSON.parse(readFileSync(resolve(runDir, stage, `attempt-${last.attempt}`, 'results.json'), 'utf8'))
          return { exitCode: r.exitCode, tcFound: new Set(r.tc?.found ?? []), tests: r.tests }
        } catch { return null }
      }
      const ok = (s) => { const r = readStage(s); return !!r && r.exitCode === 0 }
      const has = (s, tcs) => { const r = readStage(s); return !!r && tcs.every((tc) => r.tcFound.has(tc)) }
      const gates = {
        G1: ok('tooling') && ok('live-contract') && ok('regression') ? 'PASS' : 'NOT_RUN',
        G2: ok('variant') && ok('evidence') && ok('vision') && ok('archive') && ok('context') ? 'PASS' : 'NOT_RUN',
        G3: ok('maintenance') && ok('engine') && ok('domain') && ok('regression') ? 'PASS' : 'NOT_RUN',
        G4: ok('vision') && ok('context') && has('vision', ['TC-F3-08-01']) && has('context', ['TC-F3-10-01']) ? 'PASS' : 'NOT_RUN',
        G5: ok('e2e') ? 'PASS' : 'NOT_RUN',
        G6: has('context', ['TC-F3-10-04']) && has('e2e', ['TC-F3-11-03']) ? 'PASS' : 'NOT_RUN',
        G7: ok('docs') && ok('regression') ? 'PASS' : 'NOT_RUN',
      }
      const allPass = Object.values(gates).every((g) => g === 'PASS')
      const summary = {
        taskBook: TASK_BOOK_VERSION, stage: 'all', runId: args['run-id'] ?? '',
        exitCode: allPass ? 0 : 1, finishedAt: new Date().toISOString(),
        gates,
        verdict: allPass ? '107-fix-3 本地交付 LOCAL_PASS（LIVE/生产 NOT_RUN）' : 'LOCAL_INCOMPLETE',
        live: 'NOT_RUN（本书止于本地集成；无商业模型/生产验收）',
      }
      writeFileSync(resolve(runDir, 'all-summary.json'), JSON.stringify(summary, null, 2) + '\n')
      console.log(`final-gates: ${JSON.stringify(gates)} verdict=${summary.verdict}`)
      process.exit(summary.exitCode)
    }
    // eslint-disable-next-line no-fallthrough
    default:
      console.error(`未知子命令: ${cmd ?? '(空)'}；可用：junit|tap|vitest|layer|all-summary|verify-manifest|identity|check-continuation|manifest|emit|final-gates`)
      process.exit(2)
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main()
}
