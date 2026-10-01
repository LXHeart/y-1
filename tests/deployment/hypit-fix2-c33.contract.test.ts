import { execFileSync, spawn, spawnSync } from 'node:child_process'
import { createServer, type IncomingMessage, type ServerResponse } from 'node:http'
import { chmodSync, existsSync, mkdtempSync, readFileSync, readdirSync, rmSync, statSync } from 'node:fs'
import { createHash, randomUUID } from 'node:crypto'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { afterAll, beforeAll, describe, expect, it } from 'vitest'

/**
 * C107F2-33（107-fix-2 / §6.15、§7.4）：完整备份 PG 与所有持久文件，校验备份清单。
 *
 * hermetic：W203 fixture（scripts/acceptance/hypit-backup-fixture.sh）提供隔离数据伞
 * （projects 两 revision / 二进制 resources / results / credentials / 临时 slot+cache）
 * 与受控 pg_dump/psql 桩（确定性字节，不触真实 PG/用户卷）；维护窗经进程内 stub broker
 * 记录 enter/exit 顺序。真实 PG 与真实 broker 的演练归 C38。
 */

const REPOSITORY_ROOT = resolve(import.meta.dirname, '../..')
const FIXTURE_SCRIPT = 'scripts/acceptance/hypit-backup-fixture.sh'
const BACKUP_SCRIPT = 'deploy/hypit/backup.sh'

let workRoot: string
let fixtureDir: string
let stubLog: { path: string; at: number }[]
let stubServer: ReturnType<typeof createServer>
let stubPort: number

beforeAll(async () => {
  workRoot = mkdtempSync(join(tmpdir(), 'hypit-c33-'))
  fixtureDir = join(workRoot, 'fixture')
  const built = spawnSync('bash', [resolve(REPOSITORY_ROOT, FIXTURE_SCRIPT), fixtureDir], {
    encoding: 'utf8',
  })
  expect(built.status, built.stderr).toBe(0)

  stubLog = []
  stubServer = createServer((request: IncomingMessage, response: ServerResponse) => {
    let _body = ''
    request.on('data', (chunk: Buffer) => { _body += String(chunk) })
    request.on('end', () => {
      stubLog.push({ path: request.url ?? '', at: Date.now() })
      if (request.url === '/internal/v1/maintenance/enter') {
        response.writeHead(200, { 'content-type': 'application/json' })
        response.end(JSON.stringify({ leaseId: `stub-${randomUUID()}`, drained: true, inflight: [] }))
      } else if (request.url === '/internal/v1/maintenance/exit') {
        response.writeHead(200, { 'content-type': 'application/json' })
        response.end(JSON.stringify({ ok: true }))
      } else if (request.url === '/healthz') {
        response.writeHead(200, { 'content-type': 'application/json' })
        response.end(JSON.stringify({ ok: true, engineDigest: 'c33-contract-digest' }))
      } else {
        response.writeHead(404)
        response.end()
      }
    })
  })
  await new Promise<void>((resolveListen) => {
    stubServer.listen(0, '127.0.0.1', () => {
      stubPort = (stubServer.address() as { port: number }).port
      resolveListen()
    })
  })
})

afterAll(() => {
  stubServer?.close()
  if (workRoot !== undefined) rmSync(workRoot, { recursive: true, force: true })
})

function archiveNames(tarball: string): string[] {
  const listing = execFileSync('python3', ['-c',
    'import sys,tarfile;[print(e.name) for e in tarfile.open(sys.argv[1])]',
    tarball], { encoding: 'utf8' })
  return listing.split('\n').map((line) => line.replace(/^\.\//, '').trim()).filter(Boolean)
}

function sha256File(path: string): string {
  return createHash('sha256').update(readFileSync(path)).digest('hex')
}

interface BackupRun {
  status: number | null
  stdout: string
  stderr: string
}

// 必须异步 spawn：stub broker 与测试同线程——spawnSync 会卡死事件循环，
// stub 无法应答 curl，备份脚本永远等不到维护租约。
async function runBackup(outputDir: string, extraEnv: Record<string, string> = {}): Promise<BackupRun> {
  return await new Promise((resolveRun) => {
    const child = spawn('bash', [resolve(REPOSITORY_ROOT, BACKUP_SCRIPT), outputDir], {
      env: {
        ...process.env,
        PATH: `${join(fixtureDir, 'bin')}:${process.env.PATH}`,
        HYPIT_DATA_ROOT: join(fixtureDir, 'data', 'hypit'),
        HYPIT_PG_DSN: 'postgresql://fixture@127.0.0.1:1/fixture',
        HYPIT_BACKEND_URL: `http://127.0.0.1:${stubPort}`,
        HYPIT_INTERNAL_TOKEN: 'c33-contract-token-0123456789abcdef',  // secret-scan: allow
        HYPIT_SOURCE_COMMIT: 'c33contract',
        ...extraEnv,
      },
    })
    let stdout = ''
    let stderr = ''
    child.stdout.on('data', (chunk) => { stdout += String(chunk) })
    child.stderr.on('data', (chunk) => { stderr += String(chunk) })
    child.on('close', (code) => resolveRun({ status: code, stdout, stderr }))
  })
}

describe('C107F2-33 完整备份与清单校验（TC-F2-33 契约面）', () => {
  it('TC-F2-33-01 manifest 覆盖 PG dump 与全部伞内文件，逐 hash 一致', async () => {
    const outputDir = join(workRoot, 'out-01')
    const run = await runBackup(outputDir)
    expect(run.status, `${run.stdout}\n${run.stderr}`).toBe(0)

    const { default: fs } = await import('node:fs')
    const manifest = JSON.parse(fs.readFileSync(join(outputDir, 'manifest.json'), 'utf8'))
    expect(manifest.format).toBe('y1.hypit-backup@2')
    expect(manifest.complete).toBe(true)
    expect(manifest.sourceCommit).toBe('c33contract')
    expect(manifest.engineDigest).toBe('c33-contract-digest')

    expect(manifest.pgDumpSha256).toBe(sha256File(join(outputDir, 'pg', 'hypit.dump')))
    const tarball = join(outputDir, 'data', manifest.dataTarball)
    expect(manifest.dataSha256).toBe(sha256File(tarball))
    const filesByPath = new Map((manifest.files as { path: string; sha256: string }[]).map((f) => [f.path, f]))
    expect(filesByPath.get('pg/hypit.dump')?.sha256).toBe(manifest.pgDumpSha256)
    expect(filesByPath.get(`data/${manifest.dataTarball}`)?.sha256).toBe(manifest.dataSha256)

    // 归档内容与伞严格一致（相对路径）：两 revision、二进制资源、结果、broker 状态、凭据。
    const names = new Set<string>(archiveNames(tarball))
    for (const required of [
      'hypit/bridge/bridge.sqlite',
      'projects/p-0001/revisions/1/main.svrun',
      'projects/p-0001/revisions/1/manifest.json',
      'projects/p-0001/revisions/2/main.svrun',
      'projects/p-0001/revisions/2/manifest.json',
      'projects/p-0001/work/asset.bin',
      'resources/res-0123456789abcdef-0.png',
      'results/b-0001/final.video',
      'credentials/provider-key.enc',
    ]) {
      expect(names.has(required), `archive must contain ${required}`).toBe(true)
    }
    const roles = new Map(manifest.roots.map((r: { relativePath: string; role: string }) => [r.relativePath, r.role]))
    for (const root of ['hypit', 'projects', 'resources', 'results', 'credentials']) {
      expect(roles.has(root), `manifest roots must include ${root}`).toBe(true)
    }
  }, 120_000)

  it('TC-F2-33-02 临时面明确 omitted、归档 0600、日志无凭据内容', async () => {
    const outputDir = join(workRoot, 'out-02')
    const run = await runBackup(outputDir)
    expect(run.status).toBe(0)

    const { default: fs } = await import('node:fs')
    const manifest = JSON.parse(fs.readFileSync(join(outputDir, 'manifest.json'), 'utf8'))
    const omittedPaths = manifest.omitted.map((o: { path: string }) => o.path)
    expect(omittedPaths).toContain('runner-slots')
    expect(omittedPaths).toContain('cache')

    const tarball = join(outputDir, 'data', manifest.dataTarball)
    const names: string[] = archiveNames(tarball)
    expect(names.some((n) => n.includes('runner-slots'))).toBe(false)
    expect(names.some((n) => n.includes('/cache'))).toBe(false)

    // 归档件与清单权限受限（0600）；凭据内容绝不进日志。
    for (const artifact of [tarball, join(outputDir, 'pg', 'hypit.dump'), join(outputDir, 'manifest.json')]) {
      expect((statSync(artifact).mode & 0o777)).toBe(0o600)
    }
    const secretSample = fs.readFileSync(join(fixtureDir, 'data', 'credentials', 'provider-key.enc'), 'utf8')
    expect(run.stdout).not.toContain('ENVELOPED-PLACEHOLDER')
    expect(run.stderr).not.toContain('ENVELOPED-PLACEHOLDER')
    expect(secretSample.length).toBeGreaterThan(0)
  }, 120_000)

  it('TC-F2-33-03 归档进程注入失败：非零、无 complete 清单、维护租约已释放', async () => {
    const outputDir = join(workRoot, 'out-03')
    const fakeBin = join(fixtureDir, 'bin-fail')
    rmSync(fakeBin, { recursive: true, force: true })
    const { mkdirSync, writeFileSync, copyFileSync } = await import('node:fs')
    mkdirSync(fakeBin, { recursive: true })
    for (const shim of readdirSync(join(fixtureDir, 'bin'))) {
      copyFileSync(join(fixtureDir, 'bin', shim), join(fakeBin, shim))
    }
    writeFileSync(join(fakeBin, 'tar'), '#!/usr/bin/env bash\necho "injected failure" >&2\nexit 42\n')
    chmodSync(join(fakeBin, 'tar'), 0o755)

    const stubCallsBefore = stubLog.length
    const run = await runBackup(outputDir, { PATH: `${fakeBin}:${join(fixtureDir, 'bin')}:${process.env.PATH}` })
    expect(run.status, 'injected archive failure must exit non-zero').not.toBe(0)
    expect(existsSync(join(outputDir, 'manifest.json')), 'no complete manifest on failure').toBe(false)

    // 维护租约已释放：enter 后必有本进程的 exit（backup 仍完成一次握手退出）。
    const calls = stubLog.slice(stubCallsBefore)
    expect(calls.some((c) => c.path === '/internal/v1/maintenance/enter')).toBe(true)
    expect(calls.some((c) => c.path === '/internal/v1/maintenance/exit')).toBe(true)
  }, 120_000)

  it('TC-F2-33-04 PG dump 与文件快照在同一维护窗口内（enter 先于快照、exit 收尾）', async () => {
    const outputDir = join(workRoot, 'out-04')
    const stubCallsBefore = stubLog.length
    const run = await runBackup(outputDir)
    expect(run.status).toBe(0)

    const calls = stubLog.slice(stubCallsBefore)
    const enter = calls.find((c) => c.path === '/internal/v1/maintenance/enter')
    const exit = calls.find((c) => c.path === '/internal/v1/maintenance/exit')
    expect(enter, 'backup must take a maintenance lease').toBeDefined()
    expect(exit, 'backup must release its own lease at the end').toBeDefined()
    expect(enter!.at).toBeLessThan(exit!.at)

    // 排空确认发生在窗口内（dump/tar 的产物时间戳夹在 enter/exit 之后生成完毕）。
    const { default: fs } = await import('node:fs')
    const manifest = JSON.parse(fs.readFileSync(join(outputDir, 'manifest.json'), 'utf8'))
    expect(manifest.complete).toBe(true)
    // 窗口内一致性由 C32 栅栏拒绝窗口期新写保证；此处锁备份脚本顺序：
    // 没有任何快照步骤发生在 enter 之前（脚本结构断言——pg_dump 调用在租约获取之后）。
    const script = fs.readFileSync(resolve(REPOSITORY_ROOT, BACKUP_SCRIPT), 'utf8')
    expect(script.indexOf('maintenance/enter')).toBeLessThan(script.indexOf('pg_dump --format=custom'))
    expect(script.indexOf('tar -c -C')).toBeGreaterThan(script.indexOf('maintenance/enter'))
  }, 120_000)
})
