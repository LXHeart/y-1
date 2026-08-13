import { execFileSync } from 'node:child_process'
import {
  chmodSync,
  mkdtempSync,
  mkdirSync,
  readFileSync,
  realpathSync,
  rmSync,
  writeFileSync,
} from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'

const REPOSITORY_ROOT = resolve(import.meta.dirname, '../..')
const JAVA_RUNTIME_HELPER = resolve(REPOSITORY_ROOT, 'scripts/lib/java-runtime.sh')
const temporaryDirectories: string[] = []

function fakeJavaHome(version: string): string {
  const home = mkdtempSync(join(tmpdir(), 'grassland-java-'))
  const bin = join(home, 'bin')
  mkdirSync(bin)
  writeFileSync(
    join(bin, 'java'),
    `#!/usr/bin/env bash\nprintf 'openjdk version "${version}" 2026-03-17\\n' >&2\n`,
  )
  chmodSync(join(bin, 'java'), 0o755)
  temporaryDirectories.push(home)
  return home
}

function resolveJavaEnvironment(options: {
  currentJavaHome: string
  candidates?: string
  disableDefaults?: boolean
  pathJavaHome?: string
}): { javaHome: string; path: string } {
  const output = execFileSync(
    '/bin/bash',
    [
      '-c',
      [
        'set -Eeuo pipefail',
        'source "$1"',
        'ensure_java_runtime 25',
        'printf \'%s\\n%s\\n\' "${JAVA_HOME:-}" "$PATH"',
      ].join('\n'),
      'java-runtime-test',
      JAVA_RUNTIME_HELPER,
    ],
    {
      encoding: 'utf8',
      env: {
        ...process.env,
        JAVA_HOME: options.currentJavaHome,
        JAVA_RUNTIME_CANDIDATES: options.candidates ?? '',
        JAVA_RUNTIME_DISABLE_DEFAULTS: options.disableDefaults ? '1' : '0',
        PATH: `${options.pathJavaHome ?? options.currentJavaHome}/bin:/usr/bin:/bin`,
      },
    },
  )
  const [javaHome, path] = output.trim().split('\n')
  return { javaHome, path }
}

afterEach(() => {
  for (const directory of temporaryDirectories.splice(0)) {
    rmSync(directory, { recursive: true, force: true })
  }
})

describe('CI E2E Java runtime selection', () => {
  it('preserves a caller-provided Java runtime that meets the minimum version', () => {
    const currentJavaHome = fakeJavaHome('25.0.1')

    expect(resolveJavaEnvironment({ currentJavaHome })).toEqual({
      javaHome: currentJavaHome,
      path: `${currentJavaHome}/bin:/usr/bin:/bin`,
    })
  })

  it('selects an installed JDK when the current Java is too old', () => {
    const currentJavaHome = fakeJavaHome('1.8.0_391')
    const candidateJavaHome = fakeJavaHome('25.0.1')

    const result = resolveJavaEnvironment({
      currentJavaHome,
      candidates: candidateJavaHome,
    })

    expect(result.javaHome).toBe(candidateJavaHome)
    expect(result.path.startsWith(`${candidateJavaHome}/bin:`)).toBe(true)
  })

  it('repairs a stale JAVA_HOME when PATH already points to a newer runtime', () => {
    const staleJavaHome = fakeJavaHome('1.8.0_391')
    const pathJavaHome = fakeJavaHome('25.0.1')

    const result = resolveJavaEnvironment({
      currentJavaHome: staleJavaHome,
      pathJavaHome,
    })

    const resolvedPathJavaHome = realpathSync(pathJavaHome)
    expect(result.javaHome).toBe(resolvedPathJavaHome)
    expect(result.path.startsWith(`${resolvedPathJavaHome}/bin:`)).toBe(true)
  })

  it('fails clearly when no Java 17+ runtime is available', () => {
    const currentJavaHome = fakeJavaHome('1.8.0_391')

    expect(() => resolveJavaEnvironment({ currentJavaHome, disableDefaults: true })).toThrow(
      /Java 25 or later is required/,
    )
  })

  it('requires the Gradle toolchain and complete startup environment in the E2E runner', () => {
    const runner = readFileSync(resolve(REPOSITORY_ROOT, 'scripts/ci-e2e.sh'), 'utf8')

    expect(runner).toContain('ensure_java_runtime 25')
    expect(runner).toContain("export QWEN_BASE_URL='https://qwen-e2e.invalid/v1'")
    expect(runner).toContain('export QWEN_API_KEY="$(openssl rand -hex 32)"')
    expect(runner).not.toContain('${QWEN_BASE_URL:-')
    expect(runner).not.toContain('${QWEN_API_KEY:-')
    expect(runner).toContain('wait_for_postgres 60')
    expect(runner).toContain('wait_for_public_endpoint /health 200')
    expect(runner).toContain('wait_for_public_endpoint /api/tasks/feed 401')
    expect(runner).toContain('wait_for_public_endpoint /api/finance/wallets/me 401')
    expect(runner).toContain('wait_for_public_endpoint /api/trust/disputes 405')
    expect(runner).toContain('wait_for_public_endpoint /api/media/media 404')
  })
})
