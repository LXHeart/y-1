import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const REPOSITORY_ROOT = resolve(import.meta.dirname, '../../..')

function readRepositoryFile(path: string): string {
  return readFileSync(resolve(REPOSITORY_ROOT, path), 'utf8')
}

describe('changed coverage CI contract', () => {
  it('generates statement coverage data and gates changed executable lines at 80%', () => {
    const workflow = readRepositoryFile('.github/workflows/ci.yml')
    const coverage = workflow.indexOf('npm run test:coverage')
    const changedGate = workflow.indexOf('npm run coverage:changed')

    expect(readRepositoryFile('vitest.config.ts')).toContain("'json'")
    expect(readRepositoryFile('package.json')).toContain(
      '"coverage:changed": "tsx server/src/scripts/check-changed-coverage.ts"',
    )
    expect(workflow).toContain('fetch-depth: 0')
    expect(workflow).toContain('COVERAGE_BASE_REF:')
    expect(changedGate).toBeGreaterThan(coverage)
  })
})
