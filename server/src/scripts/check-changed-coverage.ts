import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import {
  buildChangedCoverageDiffArgs,
  calculateChangedLineCoverage,
  coverageComparison,
  parseChangedLines,
  type CoverageData,
} from '../lib/changed-coverage.js'

const repositoryRoot = resolve(import.meta.dirname, '../../..')
const threshold = Number(process.env.CHANGED_COVERAGE_THRESHOLD ?? '80')
const baseRef = process.env.COVERAGE_BASE_REF?.trim()

if (!Number.isFinite(threshold) || threshold < 0 || threshold > 100) {
  throw new Error('CHANGED_COVERAGE_THRESHOLD must be between 0 and 100')
}

function isGatedSource(path: string): boolean {
  const source = /^(?:server\/src|src)\/.+\.(?:ts|vue)$/.test(path)
  const excluded = /(?:^|\/)server\/src\/(?:scripts|types)\/|\.(?:test|spec)\.ts$/.test(path)
  return source && !excluded
}

function git(args: string[]): string {
  return execFileSync('git', args, { cwd: repositoryRoot, encoding: 'utf8' })
}

function changedLines(): Map<string, Set<number>> {
  const comparison = coverageComparison(baseRef)
  const diff = git(buildChangedCoverageDiffArgs(comparison))
  const changed = parseChangedLines(diff)

  if (!baseRef) {
    const untracked = git([
      'ls-files', '--others', '--exclude-standard', '-z', '--', 'server/src', 'src',
    ]).split('\0').filter(Boolean).filter(isGatedSource)
    for (const path of untracked) {
      const lineCount = readFileSync(resolve(repositoryRoot, path), 'utf8').split(/\r?\n/).length
      changed.set(path, new Set(Array.from({ length: lineCount }, (_, index) => index + 1)))
    }
  }

  return new Map([...changed].filter(([path]) => isGatedSource(path)))
}

const coverage = JSON.parse(
  readFileSync(resolve(repositoryRoot, 'coverage/coverage-final.json'), 'utf8'),
) as CoverageData
const result = calculateChangedLineCoverage(repositoryRoot, coverage, changedLines())

if (result.missingFiles.length > 0) {
  console.error(`Changed source missing from coverage data: ${result.missingFiles.join(', ')}`)
  process.exit(1)
}

console.log(
  `Changed executable-line coverage: ${result.percentage.toFixed(2)}% `
  + `(${result.covered}/${result.total}, required ${threshold.toFixed(2)}%)`,
)
if (result.percentage < threshold) {
  for (const item of result.uncovered.slice(0, 20)) {
    console.error(`${item.path}:${item.line} is not covered`)
  }
  process.exit(1)
}
