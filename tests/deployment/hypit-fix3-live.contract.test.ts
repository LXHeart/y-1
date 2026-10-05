import { spawn } from 'node:child_process'
import { createServer, type Server, type RequestListener } from 'node:http'
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { afterEach, expect, test } from 'vitest'

const root = resolve(import.meta.dirname, '../..')
const scratch: string[] = []
const servers: Server[] = []
afterEach(async () => {
  for (const server of servers.splice(0)) {
    server.closeAllConnections()
    await new Promise<void>(r => server.close(() => r()))
  }
  for (const dir of scratch.splice(0)) rmSync(dir, { recursive: true, force: true })
})

async function run(catalog: unknown, env: Record<string, string> = {}, missing = false) {
  const dir = mkdtempSync(join(tmpdir(), 'hypit-live-contract-'))
  scratch.push(dir)
  const file = join(dir, 'catalog.json')
  if (!missing) writeFileSync(file, JSON.stringify(catalog))
  const output = join(dir, 'evidence')
  const child = spawn('bash', ['scripts/acceptance/verify-107-live.sh', '--catalog', file, '--out', output], {
    cwd: root, env: { PATH: process.env.PATH, HOME: process.env.HOME,
      HYPIT_LIVE_ENABLED: '1', HYPIT_LIVE_BUDGET_CENTS: '1', HYPIT_LIVE_BASE_URL: '',
      ...env }, stdio: ['ignore', 'pipe', 'pipe'],
  })
  let log = ''
  child.stdout.on('data', chunk => { log += chunk })
  child.stderr.on('data', chunk => { log += chunk })
  const exit = await new Promise<number | null>((r, reject) => { child.on('error', reject); child.on('exit', r) })
  const rows = readFileSync(join(output, 'live-records.jsonl'), 'utf8').trim().split('\n').map(line => JSON.parse(line))
  const summary = JSON.parse(readFileSync(join(output, 'live-summary.json'), 'utf8'))
  return { exit, rows, summary, log, output }
}

async function listen(handler: RequestListener) {
  const server = createServer(handler)
  servers.push(server)
  await new Promise<void>(r => server.listen(0, '127.0.0.1', r))
  return `http://127.0.0.1:${(server.address() as { port: number }).port}`
}

test('TC-F3-12-01 缺授权/目录/内容/base/未知provider均非零，旧证据不覆盖', async () => {
  let requests = 0
  const base = await listen((_req, res) => { requests++; res.end('{}') })
  const cases = [
    await run({ providers: ['hypihub'] }, { HYPIT_LIVE_ENABLED: '0' }),
    await run({}, {}, true),
    await run({ providers: [] }),
    await run({ providers: ['hypihub'] }),
    await run({ providers: ['unknown-provider'] }, { HYPIT_LIVE_BASE_URL: base }),
  ]
  expect(cases[0]!.summary.status).toBe('NOT_ENABLED')
  for (const result of cases) {
    expect(result.exit).not.toBe(0)
    expect(result.rows.length).toBeGreaterThan(0)
    expect(result.rows.every(row => row.reason.length > 0 && row.costCents === null)).toBe(true)
  }
  expect(requests).toBe(0)
  const previous = readFileSync(join(cases[0]!.output, 'live-records.jsonl'), 'utf8')
  const child = spawn('bash', ['scripts/acceptance/verify-107-live.sh', '--out', cases[0]!.output], { cwd: root, stdio: 'ignore' })
  expect(await new Promise(r => child.on('exit', r))).not.toBe(0)
  expect(readFileSync(join(cases[0]!.output, 'live-records.jsonl'), 'utf8')).toBe(previous)
})

test('TC-F3-12-02 HTTP200只计PROBE_PASS，最终UNVERIFIED非零且成本null', async () => {
  let requests = 0
  const base = await listen((_req, res) => { requests++; res.end('{"model":"local-probe"}') })
  const result = await run({ providers: ['hypihub', 'hiapi'] }, { HYPIT_LIVE_BASE_URL: base })
  expect(requests).toBe(2)
  expect(result.rows.map(row => row.status)).toEqual(['PROBE_PASS', 'PROBE_PASS'])
  expect(result.rows.every(row => row.costCents === null && row.samplePath === null)).toBe(true)
  expect(result.summary.status).toBe('UNVERIFIED')
  expect(result.exit).not.toBe(0)
  expect(JSON.stringify(result)).not.toContain('LIVE_PASS')
})

test('TC-F3-12-03 500/超时/拒绝连接逐项留证；错误不泄漏返回体或凭据', async () => {
  const base = await listen((req, res) => {
    if (req.url?.includes('hypihub')) { res.statusCode = 500; res.end('secret-provider-token') }
    else if (req.url?.includes('hiapi')) { /* timeout; closed by afterEach */ }
    else res.end('{}')
  })
  const result = await run({ providers: ['hypihub', 'hiapi', 'pollo'] }, {
    HYPIT_LIVE_BASE_URL: base, HYPIT_LIVE_TIMEOUT_SECONDS: '0.1',
  })
  expect(result.rows.map(row => row.status)).toEqual(['PROBE_FAIL', 'PROBE_FAIL', 'PROBE_PASS'])
  expect(result.exit).not.toBe(0)
  expect(JSON.stringify(result)).not.toContain('secret-provider-token')
  const closed = await listen((_req, res) => res.end('{}'))
  const server = servers.pop()!
  await new Promise<void>(r => server.close(() => r()))
  const refused = await run({ providers: ['hypihub'] }, { HYPIT_LIVE_BASE_URL: closed })
  expect(refused.rows[0].status).toBe('PROBE_FAIL')
  expect(refused.exit).not.toBe(0)
})
