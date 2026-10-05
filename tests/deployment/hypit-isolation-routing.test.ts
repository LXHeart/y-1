import { spawnSync } from 'node:child_process'
import { copyFileSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { afterAll, beforeAll, expect, it } from 'vitest'

const root = resolve(import.meta.dirname, '../..')
const backend = join(root, 'platform-hypit/backend')
let fixture: string

beforeAll(() => {
  const parent = join(root, 'test-artifacts/task-107/isolation-routing')
  mkdirSync(parent, { recursive: true })
  fixture = mkdtempSync(join(parent, 'fixture-'))
  mkdirSync(join(fixture, 'scripts'), { recursive: true })
  mkdirSync(join(fixture, 'tests/agent-integration'), { recursive: true })
  copyFileSync(join(backend, 'scripts/run-tests.mjs'), join(fixture, 'scripts/run-tests.mjs'))
  // Routing fixtures only: no Docker, broker or real business test is substituted.
  for (const [file, name] of [['host.test.ts', 'host-route'], ['fix2-c03.test.ts', 'image-route'], ['fix2-c04.test.ts', 'runner-route']]) {
    writeFileSync(join(fixture, 'tests/agent-integration', file!), `import test from 'node:test'; test('${name}', () => {});\n`)
  }
})
afterAll(() => rmSync(fixture, { recursive: true, force: true }))

function route(group?: string) {
  const env = { ...process.env }
  delete env.TEST_GROUP
  if (group !== undefined) env.TEST_GROUP = group
  return spawnSync(process.execPath, ['scripts/run-tests.mjs'], { cwd: fixture, env, encoding: 'utf8', timeout: 30_000 })
}

it('host default and agent group leave Docker gates explicit, without implicitly starting either stack', () => {
  for (const group of [undefined, 'agent-integration']) {
    const result = route(group)
    expect(result.status, result.stderr).toBe(0)
    expect(result.stdout).toContain('host-route')
    expect(result.stdout).not.toContain('image-route')
    expect(result.stdout).not.toContain('runner-route')
    expect(result.stdout).toContain('required Docker gates run separately')
  }
})

it('dedicated image and runner gates select exactly their own test file; unknown groups fail', () => {
  for (const [group, expected, absent] of [['image-isolation', 'image-route', 'runner-route'], ['runner-isolation', 'runner-route', 'image-route']]) {
    const result = route(group)
    expect(result.status, result.stderr).toBe(0)
    expect(result.stdout).toContain(expected)
    expect(result.stdout).not.toContain(absent)
    expect(result.stdout).not.toContain('host-route')
  }
  expect(route('unknown-isolation').status).not.toBe(0)
})

it('real C03/C04 files reject direct invocation before any Docker call without a guarded fresh session', () => {
  const bin = join(fixture, 'bin')
  const marker = join(fixture, 'docker-called')
  mkdirSync(bin)
  writeFileSync(join(bin, 'docker'), '#!/bin/sh\ntouch "$DOCKER_CALL_MARKER"\nexit 99\n', { mode: 0o755 })
  for (const file of ['fix2-c03.test.ts', 'fix2-c04.test.ts']) {
    const result = spawnSync(process.execPath, ['--import', 'tsx', `tests/agent-integration/${file}`], {
      cwd: backend, encoding: 'utf8', timeout: 30_000,
      env: { ...process.env, LOCAL_STACK_TOKEN: '', PATH: `${bin}:${process.env.PATH}`, DOCKER_CALL_MARKER: marker },
    })
    expect(result.status).not.toBe(0)
    expect(result.stderr).toContain('local-stack')
    expect(existsSync(marker)).toBe(false)
  }
})

it('card registry binds all three destructive gates to fresh cleanup sessions and dedicated groups', () => {
  const script = readFileSync(join(root, 'scripts/acceptance/verify-107-fix-2.sh'), 'utf8')
  for (const card of ['02', '03', '04']) {
    expect(script).toMatch(new RegExp(`card:C107F2-${card}\\) local_stack_enter[^\\n]+--fresh --cleanup`))
  }
  expect(script).toMatch(/C107F2-03\)\s+CARD_TESTS=\(backend:image-isolation\)/)
  expect(script).toMatch(/C107F2-04\)\s+CARD_TESTS=\(backend:runner-isolation\)/)
})
