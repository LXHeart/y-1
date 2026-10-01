import { spawn, spawnSync } from 'node:child_process'
import { createServer, type IncomingMessage, type ServerResponse } from 'node:http'
import { mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { afterAll, beforeAll, describe, expect, it } from 'vitest'

/**
 * C107F2-34（107-fix-2 / §6.15、§7.5）：恢复目标映射、数据库核验和失败判定。
 *
 * hermetic：W203 fixture（两 revision + 指向被删除 snapshot 的第三行 + 受控
 * pg_dump/pg_restore/psql 桩）+ 进程内 stub broker。真实 PG/真实栈的浏览器
 * 打开工程与结果演练（owner/revision/hash 一致、零 Provider 调用）归 C38；
 * 本面锁脚本契约：显式目标映射、必传 DSN、PARTIAL 语义、失败判定非零。
 */

const REPOSITORY_ROOT = resolve(import.meta.dirname, '../..')
const FIXTURE_SCRIPT = 'scripts/acceptance/hypit-backup-fixture.sh'
const RESTORE_SCRIPT = 'deploy/hypit/restore.sh'

let workRoot: string
let fixtureDir: string
let backupDir: string
let stubServer: ReturnType<typeof createServer> | undefined

beforeAll(async () => {
  workRoot = mkdtempSync(join(tmpdir(), 'hypit-c34-'))
  fixtureDir = join(workRoot, 'fixture')
  backupDir = join(workRoot, 'backup')
  const built = spawnSyncBash([resolve(REPOSITORY_ROOT, FIXTURE_SCRIPT), fixtureDir])
  expect(built.code, built.stderr).toBe(0)

  stubServer = createServer((request: IncomingMessage, response: ServerResponse) => {
    let _body = ''
    request.on('data', (chunk: Buffer) => { _body += String(chunk) })
    request.on('end', () => {
      if (request.url === '/internal/v1/maintenance/enter') {
        response.writeHead(200, { 'content-type': 'application/json' })
        response.end(JSON.stringify({ leaseId: 'c34-stub', drained: true, inflight: [] }))
      } else if (request.url === '/internal/v1/maintenance/exit') {
        response.writeHead(200, { 'content-type': 'application/json' })
        response.end(JSON.stringify({ ok: true }))
      } else if (request.url === '/healthz') {
        response.writeHead(200, { 'content-type': 'application/json' })
        response.end(JSON.stringify({ ok: true, engineDigest: 'c34' }))
      } else {
        response.writeHead(404)
        response.end()
      }
    })
  })
  await new Promise<void>((resolveListen) => {
    stubServer!.listen(0, '127.0.0.1', () => resolveListen())
  })
  const port = (stubServer!.address() as { port: number }).port
  // READY 路径清单（无 |99| 缺失行）由测试派生；fixture 自带清单含缺失行供 TC-03。
  writeReadyTsv(fixtureDir)
  const backup = await runScript({}, {
    HYPIT_DATA_ROOT: join(fixtureDir, 'data', 'hypit'),
    HYPIT_PG_DSN: 'postgresql://fixture@127.0.0.1:1/fixture',
    HYPIT_BACKEND_URL: `http://127.0.0.1:${port}`,
    HYPIT_INTERNAL_TOKEN: 'c34-contract-token-0123456789abcdef',  // secret-scan: allow
  }, resolve(REPOSITORY_ROOT, 'deploy/hypit/backup.sh'), [backupDir])
  expect(backup.code, backup.stderr).toBe(0)
}, 180_000)

afterAll(() => {
  stubServer?.close()
  if (workRoot !== undefined) rmSync(workRoot, { recursive: true, force: true })
})

function spawnSyncBash(args: string[]): { code: number | null; stdout: string; stderr: string } {
  const result = spawnSyncDry('bash', args)
  return result
}

function spawnSyncDry(command: string, args: string[]): { code: number | null; stdout: string; stderr: string } {
  // fixture 构建不依赖 stub broker，可同步。
  const done = spawnSync(command, args, { encoding: 'utf8' })
  return { code: done.status, stdout: done.stdout ?? '', stderr: done.stderr ?? '' }
}

function writeReadyTsv(fixture: string): void {
  const full = readFileSync(join(fixture, 'pg-fixture', 'revisions.tsv'), 'utf8')
  const ready = full.split('\n').filter((line) => line.trim().length > 0 && !line.includes('|99|'))
  writeFileSync(join(fixture, 'pg-fixture', 'revisions-ready.tsv'), `${ready.join('\n')}\n`)
}

interface RunResult { code: number | null; stdout: string; stderr: string }

const FAKE_DSN = 'postgresql://fixture@127.0.0.1:1/fixture'

async function runScript(extraEnv: Record<string, string>, env: Record<string, string>,
  script: string, args: string[]): Promise<RunResult> {
  return await new Promise((resolveRun) => {
    const child = spawn('bash', [script, ...args], {
      env: {
        ...process.env,
        PATH: `${join(fixtureDir, 'bin')}:${process.env.PATH}`,
        ...env,
        ...extraEnv,
      },
    })
    let stdout = ''
    let stderr = ''
    child.stdout.on('data', (chunk) => { stdout += String(chunk) })
    child.stderr.on('data', (chunk) => { stderr += String(chunk) })
    child.on('close', (code) => resolveRun({ code, stdout, stderr }))
  })
}

function restore(target: string, extraEnv: Record<string, string> = {},
  args: string[] = []): Promise<RunResult> {
  return runScript(extraEnv, { HYPIT_PG_DSN: FAKE_DSN }, resolve(REPOSITORY_ROOT, RESTORE_SCRIPT),
    [backupDir, target, ...args])
}

function report(): { status: string; newGenerationsTriggered: number; filesOnly: boolean;
  notes: { check: string; count: number }[] } {
  return JSON.parse(readFileSync(join(backupDir, 'restore-report.json'), 'utf8'))
}

describe('C107F2-34 恢复目标映射、数据库核验和失败判定（TC-F2-34 契约面）', () => {
  it('TC-F2-34-01 归档顶层显式映射 TARGET_ROOT：内容在 restore-a 内，无旁边误落目录', async () => {
    const target = join(workRoot, 'restore-a')
    const run = await restore(target, {
      HYPIT_FIXTURE_PSQL_TSV: join(fixtureDir, 'pg-fixture', 'revisions-ready.tsv'),
    })
    expect(run.code, `${run.stdout}\n${run.stderr}`.slice(-2000)).toBe(0)

    // 内容全部位于 TARGET_ROOT 之内。
    const inside = readdirSync(target).sort()
    expect(inside).toEqual(['credentials', 'hypit', 'projects', 'resources', 'results'])
    // fixture 根旁边不得出现解包副作用目录（restore-a 之外零新目录）。
    const siblings = readdirSync(workRoot).sort()
    expect(siblings.filter((name) => name.startsWith('restore'))).toEqual(['restore-a'])
    expect(siblings).not.toContain('hypit')
    expect(siblings).not.toContain('projects')

    const summary = report()
    expect(summary.status).toBe('READY')
    expect(summary.newGenerationsTriggered).toBe(0)
    expect(summary.notes.every((note) => note.count === 0)).toBe(true)
  }, 180_000)

  it('TC-F2-34-02 正式恢复必传 DSN；--files-only=PARTIAL 非零；SQL 失败非零不冒充通过', async () => {
    // (a) 正式恢复缺 DSN → 非零。
      const noDsn = spawnSync('bash', [resolve(REPOSITORY_ROOT, RESTORE_SCRIPT), backupDir,
      join(workRoot, 'restore-nodsn')], {
      encoding: 'utf8',
      env: { ...process.env, PATH: `${join(fixtureDir, 'bin')}:${process.env.PATH}`,
        HYPIT_PG_DSN: '', DATABASE_URL: '' },
    })
    expect(noDsn.status, 'formal restore without PG DSN must fail').not.toBe(0)

    // (b) --files-only 诊断：PARTIAL（退出 3），不构成 full 通过。
    const partial = await restore(join(workRoot, 'restore-files-only'), {}, ['--files-only'])
    expect(partial.code, '--files-only must exit PARTIAL (3)').toBe(3)
    expect(report().status).toBe('PARTIAL')
    expect(report().filesOnly).toBe(true)

    // (c) 核验 SQL 失败注入：非零 + FAILED，绝不输出 missing=0 冒充通过。
    const sqlFail = await restore(join(workRoot, 'restore-sqlfail'), { HYPIT_FIXTURE_PSQL_FAIL: '1' })
    expect(sqlFail.code, 'verification SQL failure must exit non-zero').toBe(5)
    expect(report().status).toBe('FAILED')
    expect(runFailProbe(sqlFail)).toBe(false)
  }, 180_000)

  it('TC-F2-34-03 manifest 指向被删除 snapshot：缺失类别+计数明确，禁止 READY', async () => {
    const target = join(workRoot, 'restore-missing')
    const run = await restore(target)
    expect(run.code, 'missing snapshot must exit non-zero (4)').toBe(4)
    const summary = report()
    expect(summary.status).toBe('FAILED')
    const missing = summary.notes.find((note) => note.check === 'revision_snapshot_missing')
    expect(missing?.count).toBe(1)
    expect(run.stdout).not.toContain('READY：')
  }, 180_000)

  it('TC-F2-34-04 恢复零 generation 触发；报告 v2 可审计（真实栈浏览器演练归 C38）', async () => {
    // 恢复脚本面：报告 newGenerationsTriggered=0；脚本无 provider/上游调用。
    const summary = report()
    expect(summary.newGenerationsTriggered).toBe(0)
    const script = readFileSync(resolve(REPOSITORY_ROOT, RESTORE_SCRIPT), 'utf8')
    expect(script).not.toMatch(/provider|baseUrl|generate/i)
    // 报告格式 v2（§7.5：schema 历史/退出码可审计）。
    expect((JSON.parse(readFileSync(join(backupDir, 'restore-report.json'), 'utf8')) as
      { format: string }).format).toBe('y1.hypit-restore-report@2')
    // 只读打开与隔离副本修改的真实栈验证归 C38 演练；此处如实不宣称。
  }, 120_000)
})

function runFailProbe(run: RunResult): boolean {
  // 「missing=0 冒充通过」探针：SQL 失败路径的 stdout 不得出现 READY。
  return run.stdout.includes('READY')
}

