import { execFileSync } from 'node:child_process'
import { existsSync, readFileSync } from 'node:fs'
import { decodeTrackedFile, findTrackedSecrets } from './tracked-secret-scan.js'

function trackedFiles(): string[] {
  return execFileSync(
    'git',
    ['ls-files', '--cached', '--others', '--exclude-standard', '-z'],
    { encoding: 'utf8' },
  )
    .split('\0')
    .filter(Boolean)
}

// `git ls-files --cached` still lists index entries deleted in the working tree.
// They have no current bytes to scan and must not make the scan crash before commit.
const files = trackedFiles().filter((path) => existsSync(path))
const findings = files.flatMap((path) => {
  const content = decodeTrackedFile(readFileSync(path))
  return findTrackedSecrets(path, content).map((finding) => ({ path, ...finding }))
})

if (findings.length > 0) {
  console.error('Potential secrets found in tracked files:')
  for (const finding of findings) {
    console.error(`${finding.path}:${finding.line} (${finding.rule})`)
  }
  process.exit(1)
}

console.log(`Tracked-secret scan passed (${files.length} files checked).`)
