import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { decodeTrackedFile, findTrackedSecrets } from '../lib/tracked-secret-scan.js'

function trackedFiles(): string[] {
  return execFileSync(
    'git',
    ['ls-files', '--cached', '--others', '--exclude-standard', '-z'],
    { encoding: 'utf8' },
  )
    .split('\0')
    .filter(Boolean)
}

const files = trackedFiles()
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
