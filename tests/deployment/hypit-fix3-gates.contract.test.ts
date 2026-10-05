import { spawn, spawnSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { copyFileSync, existsSync, mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { afterAll, beforeAll, describe, expect, it } from 'vitest'

/**
 * C107F3-13（W054）：可信分层验收的反向门禁契约（TC-F3-13-01～03）。
 *
 * 全部用例在 F-SCRIPT 隔离副本上运行（临时目录、记录式探针、假 ffmpeg）：
 * 不触 Docker、不访问真实商业模型；产物只写 test-artifacts/task-107/fix3/gates-fixture。
 * 断言的是门禁的「拒绝能力」与编排卫生，不是任何业务测试结果——完整成功样本
 * 只证明工具链本身工作，绝不构成业务卡/LOCAL_PASS 声明。
 */

const ROOT = resolve(import.meta.dirname, '../..')
const W52 = resolve(ROOT, 'scripts/acceptance/verify-107-fix-3.sh')
const W53 = resolve(ROOT, 'scripts/acceptance/hypit-fix3-results.mjs')
const W64 = resolve(ROOT, 'scripts/acceptance/hypit-fix3-fixtures.mjs')
const W49 = resolve(ROOT, 'scripts/acceptance/stages/107-fix-2-e2e.sh')
const W50 = resolve(ROOT, 'scripts/acceptance/stages/107-fix-2-all.sh')
const TMP = resolve(ROOT, 'test-artifacts/task-107/fix3/gates-fixture')
const RUNS = resolve(TMP, 'runs')

const E2E_TCS = ['TC-F2-36-01', 'TC-F2-36-02', 'TC-F2-36-03', 'TC-F2-36-04',
  'TC-F2-37-01', 'TC-F2-37-02', 'TC-F2-37-03', 'TC-F2-37-04']

function runCli(file: string, args: string[]): { code: number; stdout: string } {
  const r = spawnSync('node', [file, ...args], { cwd: ROOT, encoding: 'utf8', timeout: 120_000 })
  return { code: r.status ?? -1, stdout: (r.stdout ?? '') + (r.stderr ?? '') }
}

/** 最小 JUnit XML 构造器：cases = [name, status]，status ∈ passed|failed|error|skipped。 */
function junitXml(cases: Array<[string, string]>): string {
  const body = cases.map(([name, status]) => {
    const inner = status === 'failed' ? '<failure message="boom">x</failure>'
      : status === 'error' ? '<error message="err">x</error>'
        : status === 'skipped' ? '<skipped/>' : ''
    return `  <testcase name="${name}" classname="fixture" time="0.01">${inner}</testcase>`
  }).join('\n')
  return `<?xml version="1.0" encoding="UTF-8"?>\n<testsuite name="fixture" tests="${cases.length}" failures="0" errors="0" skipped="0">\n${body}\n</testsuite>\n`
}

interface LayerShape {
  exitCode?: number
  tests?: Record<string, number>
  tc?: { found: string[] }
  engines?: Record<string, { exitCode: number; tests: Record<string, number> }>
  taskBook?: string
}

function layerResults(card: string, opts: LayerShape = {}): string {
  const executed = opts.tests?.executed ?? opts.tc?.found.length ?? 0
  return JSON.stringify({
    taskBook: opts.taskBook ?? '107-fix-2 v1.0.0', stage: 'gate-fixture', card,
    exitCode: opts.exitCode ?? 0, commit: 'fixture', startedAt: '', finishedAt: '',
    tests: opts.tests ?? { total: executed, passed: executed, failed: 0, skipped: 0, executed },
    tc: opts.tc ?? { found: [] },
    engines: opts.engines,
    notRun: [], notes: ['gate-fixture：仅门禁判定输入，非真实验收产物'],
  }, null, 2)
}

function runStageScript(script: string, env: Record<string, string>): { code: number; stdout: string } {
  const r = spawnSync('bash', [script], {
    cwd: ROOT, encoding: 'utf8', timeout: 120_000,
    env: { ...process.env, ...env },
  })
  return { code: r.status ?? -1, stdout: (r.stdout ?? '') + (r.stderr ?? '') }
}

beforeAll(() => {
  rmSync(TMP, { recursive: true, force: true })
  mkdirSync(RUNS, { recursive: true })
})

it('TC-F3-13-01 同一dirty状态下内容变化必须改变源码摘要，忽略验收产物', async () => {
  const { runIdentity } = await import(W53) as {
    runIdentity(root: string): { diffSummary: string; sourceDigest: string }
  }
  const repo = resolve(TMP, 'identity-repo')
  mkdirSync(repo, { recursive: true })
  const git = (...args: string[]) => {
    const result = spawnSync('git', ['-C', repo, ...args], { encoding: 'utf8' })
    expect(result.status, result.stderr).toBe(0)
  }
  git('init', '-q')
  writeFileSync(resolve(repo, '.gitignore'), 'evidence/\n')
  writeFileSync(resolve(repo, 'source.ts'), 'initial\n')
  git('add', '.')
  git('-c', 'user.name=Test Fixture', '-c', 'user.email=fixture@example.invalid',
    '-c', 'commit.gpgsign=false', 'commit', '-qm', 'fixture')
  writeFileSync(resolve(repo, 'source.ts'), 'change-one\n')
  const first = runIdentity(repo)
  writeFileSync(resolve(repo, 'source.ts'), 'change-two\n')
  const second = runIdentity(repo)
  expect(second.diffSummary).toBe(first.diffSummary)
  expect(second.sourceDigest).not.toBe(first.sourceDigest)
  writeFileSync(resolve(repo, 'untracked.ts'), 'one')
  const untracked = runIdentity(repo)
  writeFileSync(resolve(repo, 'untracked.ts'), 'two')
  const changed = runIdentity(repo)
  expect(changed.diffSummary).toBe(untracked.diffSummary)
  expect(changed.sourceDigest).not.toBe(untracked.sourceDigest)
  mkdirSync(resolve(repo, 'evidence'))
  writeFileSync(resolve(repo, 'evidence', 'run.json'), '{"exit":0}')
  expect(runIdentity(repo).sourceDigest).toBe(changed.sourceDigest)
})

afterAll(() => {
  rmSync(TMP, { recursive: true, force: true })
})

describe('TC-F3-13-01 报告反向门禁（缺/跳/败/旧/损/缺媒体逐例拒绝）', () => {
  const LAYER_TMP = resolve(TMP, 'negatives')

  it('TC-F3-13-01 逐负例注入并断言非零与精确定位（exit1/0test/skip/todo/failure/error/损坏JSON/旧run/缺媒体/hash错）', () => {
    // ① 子进程 exit1：layer results.json exitCode=1。
    let dir = resolve(LAYER_TMP, 'exit1')
    mkdirSync(dir, { recursive: true })
    writeFileSync(resolve(dir, 'results.json'), layerResults('C1', { exitCode: 1 }))
    let r = runCli(W53, ['layer', '--file', resolve(dir, 'results.json'), '--layer', 'local'])
    expect(r.code, '① exit1 必须非零').not.toBe(0)
    expect(r.stdout).toContain('exitCode=1')

    // ② 0test：executed=0（零用例不能判绿）。
    dir = resolve(LAYER_TMP, 'zerotest')
    mkdirSync(dir, { recursive: true })
    writeFileSync(resolve(dir, 'results.json'),
      layerResults('C1', { tests: { total: 0, passed: 0, failed: 0, skipped: 0, executed: 0 } }))
    r = runCli(W53, ['layer', '--file', resolve(dir, 'results.json'), '--layer', 'local'])
    expect(r.code, '② 0test 必须非零').not.toBe(0)
    expect(r.stdout).toContain('executed=0')

    // ③ skip：JUnit 含 <skipped> → 解析实数 skipped=1（不硬填 0）。
    dir = resolve(LAYER_TMP, 'skip')
    mkdirSync(dir, { recursive: true })
    const skipXml = junitXml([['TC-F3-13-01 case a', 'passed'], ['TC-F3-13-01 case b', 'skipped']])
    writeFileSync(resolve(dir, 'r.xml'), skipXml)
    r = runCli(W53, ['junit', '--file', resolve(dir, 'r.xml'), '--label', 'fixture'])
    expect(r.code, '③ skip 必须非零').not.toBe(0)
    expect(r.stdout).toContain('skipped=1')

    // ④ todo：TAP 含 # TODO 指示器。
    dir = resolve(LAYER_TMP, 'todo')
    mkdirSync(dir, { recursive: true })
    writeFileSync(resolve(dir, 'r.tap'), [
      'TAP version 13',
      '# Subtest: passing',
      'ok 1 - passing',
      '  ---',
      "  duration_ms: 0.1",
      "  type: 'test'",
      '  ...',
      '# Subtest: future',
      'ok 2 - future # TODO later',
      '  ---',
      "  duration_ms: 0.1",
      "  type: 'test'",
      '  ...',
      '1..2',
      '# tests 2',
      '# suites 0',
      '# pass 1',
      '# fail 0',
      '# cancelled 0',
      '# skipped 0',
      '# todo 1',
    ].join('\n'))
    r = runCli(W53, ['tap', '--file', resolve(dir, 'r.tap'), '--label', 'fixture'])
    expect(r.code, '④ todo 必须非零').not.toBe(0)
    expect(r.stdout).toContain('todo=1')

    // ⑤ JUnit failure。
    dir = resolve(LAYER_TMP, 'failure')
    mkdirSync(dir, { recursive: true })
    writeFileSync(resolve(dir, 'r.xml'), junitXml([['TC-F3-13-01 case', 'failed']]))
    r = runCli(W53, ['junit', '--file', resolve(dir, 'r.xml'), '--label', 'fixture'])
    expect(r.code, '⑤ JUnit failure 必须非零').not.toBe(0)
    expect(r.stdout).toContain('failed=1')

    // ⑥ JUnit error。
    dir = resolve(LAYER_TMP, 'error')
    mkdirSync(dir, { recursive: true })
    writeFileSync(resolve(dir, 'r.xml'), junitXml([['TC-F3-13-01 case', 'error']]))
    r = runCli(W53, ['junit', '--file', resolve(dir, 'r.xml'), '--label', 'fixture'])
    expect(r.code, '⑥ JUnit error 必须非零').not.toBe(0)
    expect(r.stdout).toContain('failed=1')

    // ⑦ 缺引擎：W49 隔离副本（GATE_ONLY）只有 chromium/firefox 报告。
    const e2eDir = resolve(LAYER_TMP, 'missing-engine', 'e2e')
    mkdirSync(e2eDir, { recursive: true })
    for (const engine of ['chromium', 'firefox']) {
      writeFileSync(resolve(e2eDir, `playwright-junit-${engine}.xml`),
        junitXml(E2E_TCS.map((tc) => [`${tc} 用例`, 'passed'])))
    }
    r = runStageScript(W49, {
      E2E_GATE_ONLY: '1',
      FIX2_ART_BASE: resolve(LAYER_TMP, 'missing-engine'),
      E2E_EXPECT_ENGINES: 'chromium firefox webkit',
    })
    expect(r.code, '⑦ 缺引擎必须非零').not.toBe(0)
    expect(r.stdout).toContain('缺引擎报告=webkit')

    // ⑧ 旧 run/过期 source：续跑核验拒绝。
    const staleManifest = resolve(LAYER_TMP, 'stale-manifest.json')
    writeFileSync(staleManifest, JSON.stringify({
      taskBookVersion: '107-fix-3 v1.1.0', runId: 'stale', sourceDigest: '0'.repeat(64),
      commit: 'old', stages: {},
    }))
    r = runCli(W53, ['check-continuation', '--manifest', staleManifest])
    expect(r.code, '⑧ 旧run/过期source 必须非零').not.toBe(0)
    expect(r.stdout).toContain('源码摘要变化')

    // ⑨ 损坏 JSON。
    dir = resolve(LAYER_TMP, 'broken-json')
    mkdirSync(dir, { recursive: true })
    writeFileSync(resolve(dir, 'results.json'), '{not-json')
    r = runCli(W53, ['layer', '--file', resolve(dir, 'results.json'), '--layer', 'local'])
    expect(r.code, '⑨ 损坏JSON 必须非零').not.toBe(0)
    expect(r.stdout).toContain('产物不可读')

    // ⑩ 缺媒体。
    dir = resolve(LAYER_TMP, 'missing-media')
    mkdirSync(dir, { recursive: true })
    writeFileSync(resolve(dir, 'manifest.json'), JSON.stringify({
      params: { seed: 'f-ref-v1' },
      samples: [{ id: 'f-ref-audio', file: 'f-ref-audio.mp4', sha256: 'a'.repeat(64), sizeBytes: 1, frames: [] }],
    }))
    r = runCli(W53, ['verify-manifest', '--file', resolve(dir, 'manifest.json')])
    expect(r.code, '⑩ 缺媒体 必须非零').not.toBe(0)
    expect(r.stdout).toContain('缺媒体')

    // ⑪ 媒体 hash 错。
    dir = resolve(LAYER_TMP, 'bad-hash')
    mkdirSync(dir, { recursive: true })
    writeFileSync(resolve(dir, 'sample.bin'), 'actual-bytes')
    writeFileSync(resolve(dir, 'manifest.json'), JSON.stringify({
      params: { seed: 'f-ref-v1' },
      samples: [{ id: 's', file: 'sample.bin', sha256: 'b'.repeat(64), sizeBytes: 12, frames: [] }],
    }))
    r = runCli(W53, ['verify-manifest', '--file', resolve(dir, 'manifest.json')])
    expect(r.code, '⑪ 媒体hash错 必须非零').not.toBe(0)
    expect(r.stdout).toContain('媒体hash不符')

    // ⑫ 非法 JUnit（空文件/无 testcase）。
    dir = resolve(LAYER_TMP, 'bad-junit')
    mkdirSync(dir, { recursive: true })
    writeFileSync(resolve(dir, 'r.xml'), '<?xml version="1.0"?><testsuite name="x" tests="0"/>')
    r = runCli(W53, ['junit', '--file', resolve(dir, 'r.xml'), '--label', 'fixture'])
    expect(r.code, '⑫ 无testcase的JUnit 必须非零').not.toBe(0)
    expect(r.stdout).toContain('非法报告')
  })

  it('TC-F3-13-01 缺必需TC（被过滤/未发现）由 W052 期望表拒绝；成功样本只证明工具链', () => {
    // 期望 TC 缺失：W052 tooling 对完整 vitest JSON 但缺 TC-F3-13-02 的报告拒绝。
    const dir = resolve(LAYER_TMP, 'missing-tc')
    mkdirSync(dir, { recursive: true })
    const vitestJson = {
      numTotalTests: 1, numPassedTests: 1, numFailedTests: 0, numPendingTests: 0, numTodoTests: 0,
      testResults: [{
        name: 'fixture.test.ts', status: 'passed',
        assertionResults: [{ fullName: 'TC-F3-13-01 完整但其他 TC 缺席', status: 'passed' }],
      }],
    }
    writeFileSync(resolve(dir, 'v.json'), JSON.stringify(vitestJson))
    const r = runCli(W53, ['vitest', '--file', resolve(dir, 'v.json'), '--label', 'fixture',
      '--expect', 'TC-F3-13-01,TC-F3-13-02,TC-F3-13-03'])
    expect(r.code).not.toBe(0)
    expect(r.stdout).toContain('缺必需TC=TC-F3-13-02')
    expect(r.stdout).toContain('缺必需TC=TC-F3-13-03')
  })
})

describe('TC-F3-13-02 信号退出与最小栈编排', () => {
  it('TC-F3-13-02 静态解析：白名单委托、锁边界与 E2E 环境固定', () => {
    const wrapper = readFileSync(W52, 'utf8')
    // 旧入口只允许 card 形态委托（FIX2_ART_BASE），禁止裸调 all/local/recovery。
    expect(wrapper).toContain('FIX2_ART_BASE="${STAGE_DIR}"')
    expect(wrapper).toMatch(/"\$\{FIX2_ENTRY\}" --stage card --card "\$\{card\}"/)
    for (const line of wrapper.split('\n').filter((l) => l.includes('FIX2_ENTRY') || l.includes('verify-107-fix-2'))) {
      expect(line).not.toMatch(/--stage (all|local|recovery)\b/)
    }
    // all 父层不抢锁：stage_all 函数体内不得调用 local_stack_enter。
    const allBody = wrapper.split('stage_all()')[1]?.split('\n}')[0] ?? ''
    expect(allBody).not.toContain('local_stack_enter')
    // 直接 Java/Node 阶段接入守卫：Java IT/regression 用 --docker，maintenance plain。
    expect(wrapper).toMatch(/evidence\|vision\|archive\|context\|regression\)\s*\n?\s*local_stack_enter y1-hypit-fix3 "\$\{SELF\}" --docker --cleanup/)
    expect(wrapper).toMatch(/maintenance\)\s*\n?\s*local_stack_enter y1-hypit-fix3 "\$\{SELF\}" --cleanup/)
    // Java/Node/E2E 固定参数。
    expect(wrapper).toContain('ensure_java_runtime 25')
    expect(wrapper).toContain('--rerun-tasks --no-daemon --no-parallel --max-workers=1')
    expect(wrapper).toContain('--maxWorkers=1 --no-file-parallelism')
    expect(wrapper).toContain("TEST_GROUP='${group}' npx --yes --package=node@24.14.1 -- node scripts/run-tests.mjs")
    expect(wrapper).toContain('E2E_WORKERS=1')
    expect(wrapper).toContain("E2E_ENGINES='chromium firefox webkit'")
    expect(wrapper).toContain('HYPIT_FIX3_EVIDENCE_ROOT="${STAGE_DIR}"')
    expect(wrapper).toContain('TASK103_EVIDENCE_DIR="${STAGE_DIR}/reports"')
    expect(wrapper).toContain("E2E_SPECS='tests/e2e/hypit-fix2-journey.spec.ts tests/e2e/hypit-fix2-c36.spec.ts tests/e2e/hypit-fix3-reference.spec.ts'")
    expect(wrapper).toContain('export HYPIT_E2E=1')
    expect(wrapper).toContain('export DH_E2E=0')
    expect(wrapper).toContain('export DH_FIX2_E2E=0')
    expect(wrapper).toContain('export CANVAS_E2E_TEXT_FIXTURE=0')
    // 续跑核验与 attempt 语义、失败不吞（set -uo pipefail）。
    expect(wrapper).toContain('check-continuation')
    expect(wrapper).toContain('attempt-')
    expect(wrapper).toMatch(/set -uo pipefail/)
  })

  it('TC-F3-13-02 SIGTERM：非零退出、只清本次子进程、无 Docker 调用、现场保留', async () => {
    const binDir = resolve(TMP, 'sig', 'bin')
    const sigRuns = resolve(TMP, 'sig', 'runs')
    const markerFile = resolve(TMP, 'sig', 'ffmpeg-marker.pid')
    mkdirSync(binDir, { recursive: true })
    mkdirSync(sigRuns, { recursive: true })
    // 记录式探针：docker 只记录不执行；ffmpeg 假进程向 $FIXTURE_MARKER 写 PID 后长眠。
    writeFileSync(resolve(binDir, 'docker'), '#!/bin/sh\necho "$@" >> docker-calls.log\nexit 0\n')
    writeFileSync(resolve(binDir, 'fake-ffmpeg'),
      '#!/bin/sh\necho $$ > "$FIXTURE_MARKER"\nexec sleep 300\n')
    for (const f of ['docker', 'fake-ffmpeg']) {
      spawnSync('chmod', ['+x', resolve(binDir, f)])
    }
    // 预存资源（非本次创建）：必须原样保留。
    const preExisting = resolve(sigRuns, 'pre-existing-marker.txt')
    writeFileSync(preExisting, 'kept')

    const child = spawn('bash', [W52, '--stage', 'fixtures', '--run', 'sig-test'], {
      cwd: ROOT,
      env: {
        ...process.env,
        // 隔离自测：清掉外层可能继承的 run 上下文（显式 --run sig-test 必须生效）。
        HYPIT_FIX3_RUN_ID: '',
        HYPIT_FIX3_RUNS_ROOT: sigRuns,
        PATH: `${binDir}:${process.env.PATH ?? ''}`,
        HYPIT_FIX3_FFMPEG_BIN: resolve(binDir, 'fake-ffmpeg'),
        HYPIT_FIX3_FFPROBE_BIN: resolve(binDir, 'fake-ffmpeg'),
        FIXTURE_MARKER: markerFile,
      },
      detached: true,
      stdio: 'ignore',
    })
    const attemptDir = resolve(sigRuns, 'sig-test', 'fixtures', 'attempt-1')
    // 等假 ffmpeg 已启动（marker 出现=生成子进程在睡）。
    let ffmpegPid = 0
    for (let i = 0; i < 600; i += 1) {
      if (existsSync(markerFile)) {
        ffmpegPid = Number(readFileSync(markerFile, 'utf8').trim())
        break
      }
      await new Promise((r) => setTimeout(r, 100))
    }
    expect(ffmpegPid, '假 ffmpeg 应已启动（PID 标记存在）').toBeGreaterThan(0)

    // 对整个进程组发 SIGTERM（等价 CI/Ctrl-C 的组终止）。
    try { process.kill(-child.pid!, 'SIGTERM') } catch { process.kill(child.pid!, 'SIGTERM') }
    const exitCode = await new Promise<number | null>((r) => child.on('exit', (c) => r(c)))

    expect(exitCode, '信号退出必须非零').not.toBe(0)
    // 假 ffmpeg 进程已被回收（5s 宽限）。
    let ffmpegDead = false
    for (let i = 0; i < 50; i += 1) {
      try { process.kill(ffmpegPid, 0); await new Promise((r) => setTimeout(r, 100)) } catch { ffmpegDead = true; break }
    }
    expect(ffmpegDead, '本次子进程（假 ffmpeg）必须已结束').toBe(true)
    // aborted 痕迹 + attempt 保留 + 不覆盖。
    const aborted = resolve(attemptDir, 'aborted.json')
    expect(existsSync(aborted), '必须写 aborted.json').toBe(true)
    expect(JSON.parse(readFileSync(aborted, 'utf8'))).toMatchObject({ aborted: true, stage: 'fixtures' })
    expect(existsSync(resolve(sigRuns, 'sig-test', 'manifest.json'))).toBe(true)
    // 预存资源原样保留；未触 Docker；未进入 vitest 阶段。
    expect(readFileSync(preExisting, 'utf8')).toBe('kept')
    const dockerLog = resolve(binDir, 'docker-calls.log')
    expect(existsSync(dockerLog) ? readFileSync(dockerLog, 'utf8') : '', 'fixtures 阶段不得调用 docker').toBe('')
    expect(existsSync(resolve(attemptDir, 'vitest-fixtures-tc1303.log'))).toBe(false)
  }, 120_000)
})

describe('TC-F3-13-03 确定媒体 fixture 生成（W064/W068 真实 FFmpeg）', () => {
  const outDir = resolve(TMP, 'f-ref')

  it('TC-F3-13-03 固定参数生成：12s/320×240/音轨分支/红绿蓝段/sha 清单对应字节', () => {
    const r = runCli(W64, ['--out', outDir])
    expect(r.code, `W064 生成应成功：${r.stdout}`).toBe(0)
    const manifest = JSON.parse(readFileSync(resolve(outDir, 'manifest.json'), 'utf8')) as {
      ffmpegInvocations: number
      samples: Array<{
        id: string; file: string; sha256: string; sizeBytes: number
        durationSeconds: number; width: number; height: number; hasAudio: boolean
        frames: Array<{ t: number; sha256: string; rgb: [number, number, number] }>
      }>
    }
    expect(manifest.ffmpegInvocations, '单 FFmpeg 进程串行生成').toBe(1)
    const audio = manifest.samples.find((s) => s.id === 'f-ref-audio')
    const silent = manifest.samples.find((s) => s.id === 'f-ref-silent')
    expect(audio, '缺有声样本').toBeTruthy()
    expect(silent, '缺无声样本').toBeTruthy()
    for (const s of [audio!, silent!]) {
      expect(s.durationSeconds, '时长 12s（±0.1）').toBeGreaterThanOrEqual(11.9)
      expect(s.durationSeconds).toBeLessThanOrEqual(12.1)
      expect(s.width).toBe(320)
      expect(s.height).toBe(240)
      expect(s.sizeBytes).toBeGreaterThan(0)
    }
    expect(audio!.hasAudio, '有声样本必须有单音轨').toBe(true)
    expect(silent!.hasAudio, '无声样本必须无音轨').toBe(false)
    expect(audio!.sha256, '音轨必须改变字节').not.toBe(silent!.sha256)
    // sha 清单对应真实字节（重新计算）。
    for (const s of manifest.samples) {
      const bytes = readFileSync(resolve(outDir, s.file))
      expect(createHash('sha256').update(bytes).digest('hex')).toBe(s.sha256)
    }
    // 六中点帧：时间 1/3/5/7/9/11，红/红/绿/绿/蓝/蓝。
    const wantColors: Array<'red' | 'green' | 'blue'> = ['red', 'red', 'green', 'green', 'blue', 'blue']
    expect(audio!.frames.map((f) => f.t)).toEqual([1, 3, 5, 7, 9, 11])
    audio!.frames.forEach((f, i) => {
      const [rC, gC, bC] = f.rgb
      const dominant = rC > gC + 80 && rC > bC + 80 ? 'red'
        : gC > rC + 80 && gC > bC + 80 ? 'green'
          : bC > rC + 80 && bC > gC + 80 ? 'blue' : 'unknown'
      expect(dominant, `中点 ${f.t}s 段颜色`).toBe(wantColors[i])
    })
    // 复核模式（sha/probe/解码/颜色全量重验）也必须通过。
    const v = runCli(W64, ['--verify', outDir])
    expect(v.code, `W064 verify 应成功：${v.stdout}`).toBe(0)
  }, 240_000)

  it('TC-F3-13-03 坏生成必须 exit 非零', () => {
    const badDir = resolve(TMP, 'f-ref-bad')
    const r = spawnSync('node', [W64, '--out', badDir], {
      cwd: ROOT, encoding: 'utf8', timeout: 60_000,
      env: { ...process.env, HYPIT_FIX3_FFMPEG_BIN: '/bin/false', HYPIT_FIX3_FFPROBE_BIN: '/bin/false' },
    })
    expect(r.status, '坏 ffmpeg 必须非零').not.toBe(0)
    rmSync(badDir, { recursive: true, force: true })
  }, 90_000)
})

describe('TC-F3-13-01 成功样本只证明工具链（W050 正向与 Gate 目标化推导）', () => {
  it('TC-F3-13-01 完整合成报告 → W050 exit0 且 Gate 按目标子集推导（不粗赋）', () => {
    const base = resolve(TMP, 'all-good')
    const runsBefore = existsSync(resolve(ROOT, 'test-artifacts/task-107/fix3/runs'))
      ? readdirSync(resolve(ROOT, 'test-artifacts/task-107/fix3/runs')).length : -1
    const e2eDir = resolve(base, 'e2e')
    mkdirSync(e2eDir, { recursive: true })
    mkdirSync(resolve(base, 'local'), { recursive: true })
    mkdirSync(resolve(base, 'recovery'), { recursive: true })
    // 逐引擎 JUnit（每引擎 8 用例全绿）+ 一致的 engines 计数。
    const engineTests = { total: 8, passed: 8, failed: 0, skipped: 0, executed: 8 }
    const engines = Object.fromEntries(['chromium', 'firefox', 'webkit'].map((e) => [e, { exitCode: 0, tests: { ...engineTests } }]))
    for (const e of ['chromium', 'firefox', 'webkit']) {
      writeFileSync(resolve(e2eDir, `playwright-junit-${e}.xml`),
        junitXml(E2E_TCS.map((tc) => [`${tc} 用例`, 'passed'])))
    }
    writeFileSync(resolve(base, 'e2e/results.json'), layerResults('C107F2-37', {
      tests: { total: 24, passed: 24, failed: 0, skipped: 0, executed: 24 },
      tc: { found: E2E_TCS }, engines,
    }))
    writeFileSync(resolve(base, 'local/results.json'),
      layerResults('C107F2-08', { tc: { found: ['TC-F2-08-01', 'TC-F2-08-02', 'TC-F2-08-03', 'TC-F2-08-04'] } }))
    writeFileSync(resolve(base, 'recovery/results.json'),
      layerResults('C107F2-38', { tc: { found: ['TC-F2-38-01', 'TC-F2-38-02', 'TC-F2-38-03', 'TC-F2-38-04'] } }))

    const r = runStageScript(W50, { FIX2_ART_BASE: base, FIX2_ALL_SKIP_CONTRACT: '1' })
    expect(r.code, `完整样本应通过：${r.stdout}`).toBe(0)
    const summary = JSON.parse(readFileSync(resolve(base, 'all/summary.json'), 'utf8')) as {
      verdict: string; live: string; gates: Record<string, string>
    }
    expect(summary.verdict).toContain('LOCAL_PASS')
    expect(summary.live).toContain('NOT_RUN')
    // Gate 按目标子集推导：全绿 → G1～G6 PASS（G7 因防递归跳过如实 NOT_RUN）。
    expect(summary.gates.G1).toBe('PASS')
    expect(summary.gates.G2).toBe('PASS')
    expect(summary.gates.G3).toBe('PASS')
    expect(summary.gates.G4).toBe('PASS')
    expect(summary.gates.G5).toBe('PASS')
    expect(summary.gates.G6).toBe('PASS')
    expect(summary.gates.G7).toBe('NOT_RUN')
    // 不得在 fixture 之外产生任何 fix3 业务 run（成功样本不提升业务卡）。
    const runsRoot = resolve(ROOT, 'test-artifacts/task-107/fix3/runs')
    if (runsBefore >= 0) {
      expect(readdirSync(runsRoot).length).toBe(runsBefore)
    }
    rmSync(base, { recursive: true, force: true })
  })

  it('TC-F3-13-01 缺 TC-F2-37-04 → 仅 G5 转 NOT_RUN（逐目标拒绝，非整层粗判）', () => {
    const base = resolve(TMP, 'all-partial')
    const e2eDir = resolve(base, 'e2e')
    mkdirSync(e2eDir, { recursive: true })
    mkdirSync(resolve(base, 'local'), { recursive: true })
    mkdirSync(resolve(base, 'recovery'), { recursive: true })
    const partialTcs = E2E_TCS.filter((tc) => tc !== 'TC-F2-37-04')
    const engineTests = { total: partialTcs.length, passed: partialTcs.length, failed: 0, skipped: 0, executed: partialTcs.length }
    const engines = Object.fromEntries(['chromium', 'firefox', 'webkit'].map((e) => [e, { exitCode: 0, tests: { ...engineTests } }]))
    for (const e of ['chromium', 'firefox', 'webkit']) {
      writeFileSync(resolve(e2eDir, `playwright-junit-${e}.xml`),
        junitXml(partialTcs.map((tc) => [`${tc} 用例`, 'passed'])))
    }
    writeFileSync(resolve(base, 'e2e/results.json'), layerResults('C107F2-37', {
      tests: { total: partialTcs.length * 3, passed: partialTcs.length * 3, failed: 0, skipped: 0, executed: partialTcs.length * 3 },
      tc: { found: partialTcs }, engines,
    }))
    writeFileSync(resolve(base, 'local/results.json'),
      layerResults('C107F2-08', { tc: { found: ['TC-F2-08-01', 'TC-F2-08-02', 'TC-F2-08-03', 'TC-F2-08-04'] } }))
    writeFileSync(resolve(base, 'recovery/results.json'),
      layerResults('C107F2-38', { tc: { found: ['TC-F2-38-01', 'TC-F2-38-02', 'TC-F2-38-03', 'TC-F2-38-04'] } }))
    const r = runStageScript(W50, { FIX2_ART_BASE: base, FIX2_ALL_SKIP_CONTRACT: '1' })
    expect(r.code, '缺 TC-F2-37-04 必须非零').not.toBe(0)
    expect(r.stdout).toContain('TC-F2-37-04')
    const summary = JSON.parse(readFileSync(resolve(base, 'all/summary.json'), 'utf8')) as { gates: Record<string, string> }
    expect(summary.gates.G5, '缺 TC-F2-37-04 → 仅 G5 NOT_RUN').toBe('NOT_RUN')
    expect(summary.gates.G2).toBe('PASS')
    expect(summary.gates.G6).toBe('PASS')
    rmSync(base, { recursive: true, force: true })
  })
})

/**
 * C107F3-03（W054 §13.3 增量）：TC-F3-03-02 engine 失败传导。
 *
 * F-SCRIPT：真实 wrapper（W003）隔离副本 + 桩 run-tests.mjs——其他组（agent-integration）
 * 成功，仅 engine 子进程 exit1；不执行真实业务测试、不触 Docker 服务。断言三件事：
 * ① 失败版 card 非零且 runner backend:engine FAIL（exit1 不被吞）；
 * ② engine 分派静态接线（W003 C32 登记 backend:engine、W052 stage_engine 分派）不被删；
 * ③ 真实版全 engine 报告（当前 run 证据存在时）全组执行、四类用例齐、skip/todo=0
 *    （由 W053 tap 门禁 CLI 真实解析，非本文件重写解析）。
 */
describe('TC-F3-03-02 engine失败传导（真实wrapper隔离副本C32路径）', () => {
  const PROP_TMP = resolve(TMP, 'engine-propagation')
  const W03 = resolve(ROOT, 'scripts/acceptance/verify-107-fix-2.sh')

  /** 搭隔离沙箱：真实 wrapper/守卫副本 + 桩 backend runner（仅 engine exit1）。 */
  function buildSandbox(): { sandbox: string; art: string } {
    const sandbox = resolve(PROP_TMP, 'sandbox')
    rmSync(sandbox, { recursive: true, force: true })
    for (const rel of [
      'scripts/acceptance/verify-107-fix-2.sh',
      'scripts/lib/local-stack.sh',
      'scripts/lib/local-stack-guard.mjs',
      'scripts/local-stack.mjs',
    ]) {
      mkdirSync(resolve(sandbox, dirname(rel)), { recursive: true })
      copyFileSync(resolve(ROOT, rel), resolve(sandbox, rel))
    }
    const backendScripts = resolve(sandbox, 'platform-hypit/backend/scripts')
    mkdirSync(backendScripts, { recursive: true })
    // 桩：TEST_GROUP=agent-integration → 成功输出 exit0；TEST_GROUP=engine → 一过一败 exit1。
    // 每次注入只破坏一个条件（engine 子进程退出码），其余组保持成功。
    writeFileSync(resolve(backendScripts, 'run-tests.mjs'), [
      '// TC-F3-03-02 fixture stub：仅模拟 TEST_GROUP 维度子进程行为，不执行真实测试。',
      "const group = process.env.TEST_GROUP ?? ''",
      "if (group === 'engine') {",
      "  console.log('✔ fixture engine passing case')",
      "  console.log('✖ fixture engine failing case')",
      '  process.exit(1)',
      '}',
      "console.log('✔ fixture agent-integration passing case')",
      'process.exit(0)',
      '',
    ].join('\n'))
    writeFileSync(resolve(sandbox, 'platform-hypit/backend/package.json'),
      JSON.stringify({ name: 'tc-f3-03-02-fixture-backend', private: true, scripts: { typecheck: 'node -e "process.exit(0)"' } }, null, 2))
    const art = resolve(PROP_TMP, 'art')
    rmSync(art, { recursive: true, force: true })
    mkdirSync(art, { recursive: true })
    return { sandbox, art }
  }

  it('TC-F3-03-02 注入仅engine子进程exit1：真实wrapper C32 card非零、runner engine FAIL、其他组成功、exit1不被吞', () => {
    const { sandbox, art } = buildSandbox()
    const r = spawnSync('bash',
      [resolve(sandbox, 'scripts/acceptance/verify-107-fix-2.sh'), '--stage', 'card', '--card', 'C107F2-32'], {
        cwd: sandbox, encoding: 'utf8', timeout: 240_000,
        env: { ...process.env, FIX2_ART_BASE: art },
      })
    const out = (r.stdout ?? '') + (r.stderr ?? '')
    expect(r.status, `失败版card必须非零（out=${out.slice(-500)}）`).not.toBe(0)
    expect(out, 'card 汇总必须点出存在非零 runner').toContain('存在非零 runner')
    const agg = JSON.parse(readFileSync(resolve(art, 'C107F2-32/aggregate.json'), 'utf8')) as {
      runners: Array<{ runner: string; exitCode: number; executed: number }>
    }
    const engine = agg.runners.find((x) => x.runner === 'backend:engine')
    expect(engine, 'runner 清单必须含 backend:engine（engine 分派不可删）').toBeTruthy()
    expect(engine!.exitCode, 'engine 子进程 exit1 必须原样传导为 FAIL').toBe(1)
    expect(engine!.executed, 'engine 组必须有真实用例计数（零执行不得判绿）').toBeGreaterThan(0)
    const other = agg.runners.find((x) => x.runner === 'backend:agent-integration')
    expect(other, 'runner 清单必须含 backend:agent-integration').toBeTruthy()
    expect(other!.exitCode, '其他组必须保持成功（每次注入只破坏 engine 一个条件）').toBe(0)
    const results = JSON.parse(readFileSync(resolve(art, 'C107F2-32/results.json'), 'utf8')) as {
      exitCode: number; tests: { executed: number; failed: number; skipped: number }
    }
    expect(results.exitCode).not.toBe(0)
    expect(results.tests.failed).toBeGreaterThan(0)
    expect(results.tests.executed).toBeGreaterThan(0)
  }, 300_000)

  it('TC-F3-03-02 静态接线：W003 C32登记backend:engine、W052 stage_engine分派engine组与C32 card（删任一即失败）', () => {
    const fix2Wrapper = readFileSync(W03, 'utf8')
    const c32Block = fix2Wrapper.split(/\n\s*C107F2-32\)/)[1]?.split(/\n\s*;;/)[0] ?? ''
    expect(c32Block, 'W003 C32 卡必须登记 backend:engine（全engine必需门禁，不允许"不登记"出口）')
      .toContain('backend:engine')
    expect(c32Block).toContain('backend:agent-integration')
    const engineBody = readFileSync(W52, 'utf8').split('stage_engine()')[1]?.split('\n}')[0] ?? ''
    expect(engineBody).toContain('run_backend_group engine')
    expect(engineBody).toContain('run_fix2_card C107F2-32')
    // W54 传导复核必须真实运行（整文件，保持 skip/todo=0，不得降为过滤运行）。
    expect(engineBody).toContain('hypit-fix3-gates.contract.test.ts')
  })

  it('TC-F3-03-02 真实版全engine（当前run证据存在时）：全组执行、四类用例齐、skip/todo=0（W053真实解析）', () => {
    const stageDir = process.env.FIX3_STAGE_DIR ?? ''
    const tapPath = stageDir ? resolve(stageDir, 'backend-engine.tap') : ''
    if (!tapPath || !existsSync(tapPath)) {
      // 真实半边由 V-05 stage 自身门禁承担（W052 run_backend_group TAP 解析 skip/todo=0
      // + finish_stage 期望TC全发现核验）；本上下文（如 V-01 工具级）无该证据，不误报。
      console.info('TC-F3-03-02: 当前 stage 无 backend-engine.tap；真实半边由 V-05 stage 门禁核验')
      return
    }
    const r = runCli(W53, ['tap', '--file', tapPath, '--label', 'tc-f3-03-02-real-engine'])
    expect(r.code, `真实全engine TAP 必须过 W053 门禁（executed>0、failed/skip/todo=0）：${r.stdout}`).toBe(0)
    // 四类用例（§12.2 TC-F3-03-01：合法编译/恶意探针/坏源码诊断/超时回收）+ 基础握手。
    const tap = readFileSync(tapPath, 'utf8')
    for (const fragment of [
      'TC-F3-03-01: 真实daemon status握手',
      'TC-F3-03-01: 恶意组件marker真实执行',
      'TC-F3-03-01: 超时回收slot',
      'TC-F3-03-01（原TC-F06-01）: 真实编译',
    ]) {
      expect(tap, `真实报告必须列出四类用例之一：${fragment}`).toContain(fragment)
    }
    // C32 card 真实产物（本 stage 的 fix2card 层）：非零退出=engine 拉低卡退出码。
    const cardResults = resolve(stageDir, 'C107F2-32/results.json')
    if (existsSync(cardResults)) {
      const card = JSON.parse(readFileSync(cardResults, 'utf8')) as {
        exitCode: number; tests?: { executed: number; skipped: number }
      }
      expect(card.exitCode, '真实 C32 card（含 backend:engine 全组）必须 exit0').toBe(0)
      expect(card.tests?.executed ?? 0, '真实 C32 card 必须有真实执行计数').toBeGreaterThan(0)
      expect(card.tests?.skipped ?? 0).toBe(0)
    }
  })
})
