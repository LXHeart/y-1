import { execFileSync, spawn, spawnSync } from 'node:child_process'
import { chmodSync, copyFileSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'

const root = resolve(import.meta.dirname, '../..')
const cli = resolve(root, 'scripts/local-stack.mjs')
let temp: string, statePath: string, env: NodeJS.ProcessEnv
const model = { services: {
  db: { image: 'postgres:test', volumes: [{ type: 'volume', source: 'data' }] },
  api: { build: { context: '.' }, depends_on: { db: { condition: 'service_healthy' } } },
  worker: { build: { context: '.' } },
  monitor: { image: 'monitor:test' },
}, volumes: { data: {} } }
function container(project: string, service = 'db', dir = root, state = 'running') {
  return { id: `${project}-${service}`, name: `/${project}-${service}`, state, image: 'fixture:test', labels: {
    'com.docker.compose.project': project, 'com.docker.compose.service': service,
    'com.docker.compose.project.working_dir': dir, 'com.docker.compose.project.config_files': `${dir}/docker-compose.yml`,
  } }
}
type State = { containers: ReturnType<typeof container>[], volumes: { name: string, project: string }[], networks: { name: string, project: string }[], model: typeof model }
const read = (): State => JSON.parse(readFileSync(statePath, 'utf8'))
const write = (state: State) => writeFileSync(statePath, JSON.stringify(state))
const calls = (): string[][] => existsSync(`${statePath}.log`) ? readFileSync(`${statePath}.log`, 'utf8').trim().split('\n').filter(Boolean).map((line) => JSON.parse(line)) : []
const mutations = () => calls().filter((a) => ['stop', 'rm'].includes(a[0]!) || a.some((v) => ['up', 'build', 'start', 'restart', 'run'].includes(v)))
const compose = (args: string[], project = 'guard-test') => ['compose', '-p', project, '-f', resolve(root, 'docker-compose.yml'), '--', ...args]
function run(args: string[], extra = {}) {
  return spawnSync(process.execPath, [cli, ...args], { cwd: root, env: { ...env, ...extra }, encoding: 'utf8', timeout: 20000 })
}
function session(command: string[], flags: string[] = []) {
  return ['run', '--project', 'guard-test', ...flags, '--', ...command]
}
function child(args: string[], extra = {}) {
  const p = spawn(process.execPath, [cli, ...args], { cwd: root, env: { ...env, ...extra }, stdio: ['ignore', 'pipe', 'pipe'] })
  let output = ''
  p.stdout.on('data', (s) => { output += s }); p.stderr.on('data', (s) => { output += s })
  const done = new Promise<{ code: number | null, output: string }>((resolvePromise) => p.on('close', (code) => resolvePromise({ code, output })))
  return { p, done }
}
async function until(predicate: () => boolean) {
  for (let i = 0; i < 200; i++) { if (predicate()) return; await new Promise((r) => setTimeout(r, 20)) }
  throw new Error('Timed out waiting for fixture')
}

beforeEach(() => {
  temp = mkdtempSync(join(tmpdir(), 'local-stack-guard-'))
  mkdirSync(join(temp, 'bin'))
  copyFileSync(resolve(root, 'tests/fixtures/local-stack/docker.mjs'), join(temp, 'bin/docker'))
  chmodSync(join(temp, 'bin/docker'), 0o755)
  statePath = join(temp, 'state.json')
  write({ containers: [], volumes: [], networks: [], model })
  env = { ...process.env, HOME: temp, PATH: `${join(temp, 'bin')}:${process.env.PATH}`, FAKE_DOCKER_STATE: statePath, FAKE_ROOT: root }
  delete env.LOCAL_STACK_TOKEN; delete env.LOCAL_STACK_ENTRY; delete env.FAKE_FAIL
})
afterEach(() => rmSync(temp, { recursive: true, force: true }))

describe('local stack guard (fake Docker only)', () => {
  it.each(['up', 'build'])('rejects %s without a service before touching Docker', (action) => {
    const result = run(compose([action]))
    expect(result.status).toBe(1); expect(result.stderr).toContain('必须显式列出服务'); expect(calls()).toEqual([])
  })
  it.each(['ps', 'inspect'])('fails closed when Docker %s cannot be read', (operation) => {
    const s = read(); s.containers.push(container('guard-test')); write(s)
    const result = run(compose(['up', 'db']), { FAKE_FAIL: operation })
    expect(result.status).toBe(1); expect(result.stderr).toContain('查询失败'); expect(mutations()).toEqual([])
  })
  it.each(['running', 'restarting', 'paused'])('rejects another stack in %s state despite another name/port', (state) => {
    const s = read(); s.containers.push(container('different-name', 'db', root, state)); write(s)
    const result = run(compose(['up', 'db']))
    expect(result.status).toBe(1); expect(result.stderr).toContain('另一套'); expect(mutations()).toEqual([])
  })
  it('recognizes legacy names even when their worktree no longer exists', () => {
    const s = read(); s.containers.push(container('hypit-verify', 'db', '/missing/checkout')); write(s)
    expect(run(compose(['up', 'db'])).status).toBe(1); expect(mutations()).toEqual([])
  })
  it('does not take over a foreign project with the same name', () => {
    const s = read(); s.containers.push(container('guard-test', 'db', temp)); write(s)
    const r = run(compose(['up', 'db']))
    expect(r.status).toBe(1); expect(r.stderr).toContain('拒绝接管'); expect(mutations()).toEqual([])
  })
  it('builds/starts only the requested dependency closure, one service at a time', () => {
    const r = run(compose(['up', '--build', 'api']))
    expect(r.status, r.stderr).toBe(0)
    expect(calls().filter((a) => a.includes('build')).map((a) => a[a.length - 1])).toEqual(['api'])
    expect(calls().filter((a) => a.includes('up')).map((a) => a[a.length - 1])).toEqual(['db', 'api'])
    expect(read().containers.map((c) => c.labels['com.docker.compose.service'])).toEqual(['db', 'api'])
    expect(calls().filter((a) => a[0] === 'compose').every((a) => a[1] === '--parallel' && a[2] === '1')).toBe(true)
  })
  it('rejects unknown services, wildcard profiles and dependency bypasses', () => {
    expect(run(compose(['up', 'unknown'])).status).toBe(1)
    expect(run(['compose', '-p', 'guard-test', '-f', 'docker-compose.yml', '--profile', '*', '--', 'up', 'db']).status).toBe(1)
    expect(run(compose(['up', '--no-deps', 'api'])).status).toBe(1)
    expect(mutations()).toEqual([])
  })
  it.each(['0', '2'])('checks one-shot completion exit %s before starting dependants', (exit) => {
    const s = read(); s.model.services.api.depends_on.db.condition = 'service_completed_successfully'; write(s)
    const r = run(compose(['up', 'api']), { FAKE_JOB_EXIT: exit })
    expect(r.status, r.stderr).toBe(exit === '0' ? 0 : 1)
    const up = calls().filter((a) => a.includes('up'))
    expect(up[0]).not.toContain('--wait')
    expect(calls().some((a) => a[0] === 'wait')).toBe(true)
    if (exit !== '0') expect(up.map((a) => a[a.length - 1])).toEqual(['db'])
  })
  it('rejects unrelated services already running in the target stack', () => {
    const s = read(); s.containers.push(container('guard-test', 'monitor')); write(s)
    expect(run(compose(['up', 'api'])).stderr).toContain('清单以外'); expect(mutations()).toEqual([])
  })
  it('can stop a known service to resolve two existing stacks without deleting data', () => {
    const s = read(); s.containers.push(container('guard-test'), container('y-1')); write(s)
    const r = run(compose(['stop', 'db']))
    expect(r.status, r.stderr).toBe(0)
    expect(read().containers.map((c) => c.state)).toEqual(['exited', 'running'])
    expect(calls().some((a) => a.includes('rm') || a.includes('down'))).toBe(false)
  })
  it.each(['start', 'restart', 'kill'])('%s never creates missing services', (action) => {
    const r = run(compose([action, 'api']))
    expect(r.status).toBe(1); expect(r.stderr).toContain('已有容器'); expect(mutations()).toEqual([])
  })
  it('releases the lock after a failed startup and stops only newly started services', () => {
    const s = read(); s.containers.push(container('guard-test'), container('unrelated', 'x', temp)); write(s)
    const r = run(compose(['up', 'api']), { FAKE_FAIL: 'up' })
    expect(r.status, r.stderr).toBe(23)
    expect(read().containers.find((c) => c.id === 'guard-test-db')!.state).toBe('running')
    expect(read().containers.find((c) => c.id === 'unrelated-x')!.state).toBe('running')
    expect(existsSync(join(temp, '.cache/grassland-local-stack/lock'))).toBe(false)
    expect(run(compose(['up', 'api'])).status).toBe(0)
  })
  it('stops a partially started new stack on failure but retains its data', () => {
    const r = run(compose(['up', 'api']), { FAKE_FAIL: 'up' })
    expect(r.status, r.stderr).toBe(23)
    expect(read().containers.length).toBeGreaterThan(0)
    expect(read().containers.every((c) => c.state === 'exited')).toBe(true)
    expect(read().volumes.map((v) => v.name)).toEqual(['guard-test_data'])
  })
  it('holds the lease for the whole session, permits nesting, and cleans up on success', () => {
    const r = run(session([process.execPath, cli, ...compose(['up', 'api'])], ['--docker', '--cleanup']))
    expect(r.status, r.stderr).toBe(0)
    expect(read().containers.every((c) => c.state === 'exited')).toBe(true)
    expect(read().volumes.map((v) => v.name)).toEqual(['guard-test_data'])
  })
  it('does not call Docker for offline static verification', () => {
    expect(run(session([process.execPath, '-e', 'process.exit(0)'])).status).toBe(0)
    expect(calls()).toEqual([])
  })
  it('rejects a second session while the first is still working and releases after TERM', async () => {
    const marker = join(temp, 'ready')
    const first = child(session([process.execPath, '-e', `require('fs').writeFileSync(${JSON.stringify(marker)}, '1'); setInterval(()=>{},1000)`]))
    try {
      await until(() => existsSync(marker))
      const second = run(session([process.execPath, '-e', 'process.exit(0)']))
      expect(second.status).toBe(1); expect(second.stderr).toContain('互斥锁已被占用')
    } finally { first.p.kill('SIGTERM'); await first.done }
    expect(run(session([process.execPath, '-e', 'process.exit(0)'])).status).toBe(0)
  })
  it('rejects concurrent Compose operations even among children of one lease', async () => {
    const marker = join(temp, 'ready')
    const first = child(session([process.execPath, '-e', `require('fs').writeFileSync(${JSON.stringify(marker)},'1');setInterval(()=>{},1000)`]))
    let building: ReturnType<typeof child> | undefined
    try {
      await until(() => existsSync(marker))
      const owner = JSON.parse(readFileSync(join(temp, '.cache/grassland-local-stack/lock/owner.json'), 'utf8'))
      building = child(compose(['build', 'api']), { LOCAL_STACK_TOKEN: owner.token, FAKE_BUILD_WAIT_MS: '800' })
      await until(() => calls().some((a) => a.includes('build')))
      const second = run(compose(['build', 'worker']), { LOCAL_STACK_TOKEN: owner.token })
      expect(second.status).toBe(1); expect(second.stderr).toContain('子流程也必须串行')
      expect((await building.done).code).toBe(0)
    } finally { building?.p.kill('SIGTERM'); if (building) await building.done; first.p.kill('SIGTERM'); await first.done }
  })
  it('does not silently steal a stale lock or accept a forged inherited token', () => {
    expect(run(session([process.execPath, '-e', 'process.exit(0)']), { LOCAL_STACK_TOKEN: 'invalid' }).status).toBe(1)
    const lock = join(temp, '.cache/grassland-local-stack/lock'); mkdirSync(lock, { recursive: true })
    writeFileSync(join(lock, 'owner.json'), JSON.stringify({ pid: 99999999, token: 'stale', project: 'previous', root }))
    const r = run(session([process.execPath, '-e', 'process.exit(0)']))
    expect(r.status).toBe(1); expect(r.stderr).toContain('不会自动抢占'); expect(existsSync(lock)).toBe(true)
  })
  it('cleans new containers on interrupt without masking the signal exit code', async () => {
    const marker = join(temp, 'ready')
    const script = join(temp, 'child.cjs')
    writeFileSync(script, `require('child_process').execFileSync(process.execPath, ${JSON.stringify([cli, ...compose(['up', 'api'])])}, {stdio:'inherit'}); require('fs').writeFileSync(${JSON.stringify(marker)},'1');setInterval(()=>{},1000)`)
    const task = child(session([process.execPath, script], ['--docker', '--cleanup']))
    try { await until(() => existsSync(marker)) } finally { task.p.kill('SIGTERM') }
    const result = await task.done
    expect(result.code, result.output).toBe(143)
    expect(read().containers.every((c) => c.state === 'exited')).toBe(true)
  })
  it('requires a fresh session to reset and refuses pre-existing volumes', () => {
    expect(run(['reset']).status).toBe(1)
    const s = read(); s.volumes.push({ name: 'guard-test_data', project: 'guard-test' }); write(s)
    const r = run(session([process.execPath, cli, ...compose(['up', 'api'])], ['--fresh', '--cleanup']))
    expect(r.status).toBe(1); expect(mutations()).toEqual([]); expect(read().volumes).toEqual(s.volumes)
  })
  it('does not reset a different project through an inherited session', () => {
    const r = run(session([process.execPath, cli, 'reset', '--project', 'other'], ['--fresh', '--cleanup']))
    expect(r.status).toBe(1); expect(r.stderr).toContain('项目不一致'); expect(mutations()).toEqual([])
  })
  it('fresh cleanup deletes only this sessions isolated resources', () => {
    const s = read(); s.volumes.push({ name: 'precious', project: 'other' }); write(s)
    const r = run(session([process.execPath, cli, ...compose(['up', 'api'])], ['--fresh', '--cleanup']))
    expect(r.status, r.stderr).toBe(0)
    expect(read().containers).toEqual([]); expect(read().networks).toEqual([]); expect(read().volumes).toEqual(s.volumes)
  })
  it('rejects named shared volumes even without Compose ownership labels', () => {
    const s = read(); s.volumes.push({ name: 'precious', project: '' }); s.model.volumes.data = { name: 'precious' } as never; write(s)
    const r = run(session([process.execPath, cli, ...compose(['up', 'api'])], ['--fresh', '--cleanup']))
    expect(r.status).toBe(1); expect(r.stderr).toContain('既有数据卷'); expect(mutations()).toEqual([])
  })
  it('recognizes a different worktree through the shared git directory', () => {
    // Query-only check using a synthetic .git file pointing at this repository.
    const sibling = join(temp, 'sibling'); mkdirSync(sibling)
    const git = execFileSync('git', ['rev-parse', '--absolute-git-dir'], { cwd: root, encoding: 'utf8' }).trim()
    writeFileSync(join(sibling, '.git'), `gitdir: ${git}\n`)
    const s = read(); s.containers.push(container('renamed-worktree-stack', 'db', sibling)); write(s)
    expect(run(compose(['up', 'db'])).stderr).toContain('另一套'); expect(mutations()).toEqual([])
  })
  it.each([
    ['scripts/ci-e2e.sh', []],
    ['scripts/acceptance/hypit-compose.sh', ['up', 'db']],
    ['scripts/acceptance/verify-107-fix-2.sh', ['--stage', 'card', '--card', 'C107F2-08']],
    ['scripts/acceptance/verify-107-full.sh', []],
    ['scripts/local-observability-smoke.sh', []],
    ['scripts/local-otel-trace-smoke.sh', []],
  ])('%s rejects a second stack before its own startup logic', (script, args) => {
    const s = read(); s.containers.push(container('another-worktree')); write(s)
    const r = spawnSync('bash', [script as string, ...(args as string[])], { cwd: root, env: { ...env, HYPIT_FULL_E2E: '1' }, encoding: 'utf8', timeout: 15000 })
    expect(r.status, r.stderr).toBe(1); expect(r.stderr).toContain('另一套'); expect(mutations()).toEqual([])
  })
  it('Hypit wrapper passes its explicit service selection through the real shell helper', () => {
    // Relative invocation from a subdirectory must re-exec the correct absolute script.
    const r = spawnSync('bash', ['../scripts/acceptance/hypit-compose.sh', 'up', 'api'], { cwd: resolve(root, 'tests'), env, encoding: 'utf8', timeout: 15000 })
    expect(r.status, r.stderr).toBe(0)
    expect(calls().filter((a) => a.includes('up')).map((a) => a[a.length - 1])).toEqual(['db', 'api'])
    expect(calls().filter((a) => a.includes('build')).map((a) => a[a.length - 1])).toEqual(['api'])
  })
})
