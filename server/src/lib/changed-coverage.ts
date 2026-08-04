import { isAbsolute, relative } from 'node:path'

interface StatementLocation {
  start: { line: number }
  end?: { line: number }
}

export const EMPTY_TREE_SHA = '4b825dc642cb6eb9a060e54bf8d69288fbee4904'

export function coverageComparison(baseRef?: string): string {
  const normalized = baseRef?.trim()
  if (!normalized) return 'HEAD'
  return /^0+$/.test(normalized) ? `${EMPTY_TREE_SHA}..HEAD` : `${normalized}...HEAD`
}

export function buildChangedCoverageDiffArgs(comparison: string): string[] {
  return [
    '-c',
    'core.quotePath=false',
    'diff',
    '--unified=0',
    '--no-color',
    '--no-renames',
    '--diff-filter=ACMR',
    comparison,
    '--',
    'server/src',
    'src',
  ]
}

interface FileCoverage {
  statementMap: Record<string, StatementLocation>
  s: Record<string, number>
}

export type CoverageData = Record<string, FileCoverage>

export interface ChangedCoverageResult {
  covered: number
  total: number
  percentage: number
  missingFiles: string[]
  uncovered: Array<{ path: string; line: number }>
}

function normalizePath(path: string): string {
  return path.replaceAll('\\', '/').replace(/^\.\//, '')
}

export function parseChangedLines(diff: string): Map<string, Set<number>> {
  const changed = new Map<string, Set<number>>()
  let currentPath: string | null = null

  for (const line of diff.split(/\r?\n/)) {
    const file = line.match(/^\+\+\+ b\/(.+)$/)?.[1]
    if (file) {
      currentPath = normalizePath(file)
      continue
    }
    if (line === '+++ /dev/null') {
      currentPath = null
      continue
    }

    const hunk = line.match(/^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@/)
    if (!currentPath || !hunk) continue
    const start = Number(hunk[1])
    const count = hunk[2] === undefined ? 1 : Number(hunk[2])
    const lines = changed.get(currentPath) ?? new Set<number>()
    for (let offset = 0; offset < count; offset += 1) lines.add(start + offset)
    changed.set(currentPath, lines)
  }

  return changed
}

function lineCounts(file: FileCoverage): Map<number, number> {
  const counts = new Map<number, number>()
  for (const [statementId, location] of Object.entries(file.statementMap)) {
    const count = file.s[statementId] ?? 0
    const endLine = Math.max(location.start.line, location.end?.line ?? location.start.line)
    for (let line = location.start.line; line <= endLine; line += 1) {
      counts.set(line, Math.min(counts.get(line) ?? count, count))
    }
  }
  return counts
}

export function calculateChangedLineCoverage(
  repositoryRoot: string,
  coverage: CoverageData,
  changed: Map<string, Set<number>>,
): ChangedCoverageResult {
  const coverageByPath = new Map(
    Object.entries(coverage).map(([path, value]) => [
      normalizePath(isAbsolute(path) ? relative(repositoryRoot, path) : path),
      value,
    ]),
  )
  const missingFiles: string[] = []
  const uncovered: Array<{ path: string; line: number }> = []
  let covered = 0
  let total = 0

  for (const [path, changedLines] of changed) {
    const file = coverageByPath.get(normalizePath(path))
    if (!file) {
      missingFiles.push(path)
      continue
    }
    const counts = lineCounts(file)
    for (const line of [...changedLines].sort((a, b) => a - b)) {
      const count = counts.get(line)
      if (count === undefined) continue
      total += 1
      if (count > 0) covered += 1
      else uncovered.push({ path, line })
    }
  }

  return {
    covered,
    total,
    percentage: total === 0 ? 100 : Number(((covered / total) * 100).toFixed(2)),
    missingFiles: missingFiles.sort(),
    uncovered,
  }
}
