import { execFileSync } from 'node:child_process'
import { existsSync, readFileSync, statSync } from 'node:fs'
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
// Vendored platform-hypit/upstream also tracks directory symlinks (.claude/skills/hypit);
// only regular files (symlinks resolved) carry scannable text (#107 C01).
//
// platform-hypit/upstream is a byte-exact third-party vendor tree frozen by
// upstream-manifest.json (per-file SHA-256) and guarded by
// scripts/acceptance/verify-107-upstream.sh: any added/modified file fails that check,
// which is the compensating control for skipping it here. Its own test fixtures embed
// fake OAuth credentials that cannot be annotated without editing frozen sources.
const vendoredPrefix = 'platform-hypit/upstream/'
const files = trackedFiles().filter((path) => existsSync(path) && statSync(path).isFile()
  && !path.startsWith(vendoredPrefix))
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
