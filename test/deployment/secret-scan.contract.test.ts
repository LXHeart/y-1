import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const REPOSITORY_ROOT = resolve(import.meta.dirname, '../..')

function readRepositoryFile(path: string): string {
  return readFileSync(resolve(REPOSITORY_ROOT, path), 'utf8')
}

describe('CI tracked-secret scan contract', () => {
  it('runs secret and dependency checks before compilation and tests in the Node CI job', () => {
    const workflow = readRepositoryFile('.github/workflows/ci.yml')
    const secretScan = workflow.indexOf('npm run security:secrets')
    const dependencyAudit = workflow.indexOf('npm audit --audit-level=high')
    const typecheck = workflow.indexOf('npm run typecheck')

    expect(secretScan).toBeGreaterThan(0)
    expect(dependencyAudit).toBeGreaterThan(secretScan)
    expect(dependencyAudit).toBeLessThan(typecheck)
    expect(secretScan).toBeLessThan(typecheck)
    expect(readRepositoryFile('package.json')).toContain(
      '"security:secrets": "node --import tsx scripts/security/check-tracked-secrets.ts"',
    )
    expect(readRepositoryFile('.gitignore')).toContain('tmp/')
  })

  it('keeps local E2E observation artifacts out of the Git index', () => {
    const trackedArtifacts = execFileSync(
      'git',
      ['ls-files', 'tmp'],
      { cwd: REPOSITORY_ROOT, encoding: 'utf8' },
    ).trim()

    expect(trackedArtifacts).toBe('')
  })
})
