import { describe, expect, it } from 'vitest'
import {
  buildChangedCoverageDiffArgs,
  calculateChangedLineCoverage,
  coverageComparison,
  EMPTY_TREE_SHA,
  parseChangedLines,
} from './changed-coverage.js'

describe('changed line coverage gate', () => {
  it('parses added line numbers from multiple zero-context hunks', () => {
    const changed = parseChangedLines(`diff --git a/src/example.ts b/src/example.ts
--- a/src/example.ts
+++ b/src/example.ts
@@ -1 +1,2 @@
-old
+new
+added
@@ -8,0 +10,2 @@
+ten
+eleven
`)

    expect([...changed.get('src/example.ts') ?? []]).toEqual([1, 2, 10, 11])
  })

  it('requires every statement on a changed executable line to run', () => {
    const changed = new Map([['src/example.ts', new Set([2, 4, 5])]])
    const coverage = {
      '/repo/src/example.ts': {
        statementMap: {
          0: { start: { line: 2 } },
          1: { start: { line: 4 } },
          2: { start: { line: 4 } },
        },
        s: { 0: 1, 1: 1, 2: 0 },
      },
    }

    expect(calculateChangedLineCoverage('/repo', coverage, changed)).toEqual({
      covered: 1,
      total: 2,
      percentage: 50,
      missingFiles: [],
      uncovered: [{ path: 'src/example.ts', line: 4 }],
    })
  })

  it('reports changed source files that are absent from the coverage map', () => {
    const changed = new Map([['src/missing.ts', new Set([1])]])

    expect(calculateChangedLineCoverage('/repo', {}, changed).missingFiles)
      .toEqual(['src/missing.ts'])
  })

  it('counts a changed continuation line against its multi-line statement', () => {
    const changed = new Map([['src/example.ts', new Set([3])]])
    const coverage = {
      '/repo/src/example.ts': {
        statementMap: {
          0: { start: { line: 2 }, end: { line: 4 } },
        },
        s: { 0: 0 },
      },
    }

    expect(calculateChangedLineCoverage('/repo', coverage, changed)).toEqual({
      covered: 0,
      total: 1,
      percentage: 0,
      missingFiles: [],
      uncovered: [{ path: 'src/example.ts', line: 3 }],
    })
  })

  it('uses the empty tree for an all-zero push base', () => {
    expect(coverageComparison(undefined)).toBe('HEAD')
    expect(coverageComparison('')).toBe('HEAD')
    expect(coverageComparison('0'.repeat(40))).toBe(`${EMPTY_TREE_SHA}..HEAD`)
    expect(coverageComparison('abc123')).toBe('abc123...HEAD')
  })

  it('keeps non-ASCII paths readable and treats renames as delete plus add', () => {
    expect(buildChangedCoverageDiffArgs('HEAD')).toEqual([
      '-c',
      'core.quotePath=false',
      'diff',
      '--unified=0',
      '--no-color',
      '--no-renames',
      '--diff-filter=ACMR',
      'HEAD',
      '--',
      'server/src',
      'src',
    ])

    const changed = parseChangedLines(`+++ b/src/门店.ts
@@ -0,0 +1 @@
+export const store = true
`)
    expect([...changed.get('src/门店.ts') ?? []]).toEqual([1])
  })
})
