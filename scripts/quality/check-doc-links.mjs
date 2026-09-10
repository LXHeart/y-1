import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, readFileSync, statSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { marked } from 'marked'

// Audit local Markdown destinations only. Network URLs and fragments are not fetched.
const root = fileURLToPath(new URL('../../', import.meta.url))
const outputDir = path.join(root, 'test-artifacts/docs-links')
const repositoryFiles = new Set(execFileSync('git', [
  'ls-files', '--cached', '--others', '--exclude-standard', '-z',
], { cwd: root, encoding: 'utf8', maxBuffer: 10 * 1024 * 1024 }).split('\0').filter(Boolean))
const files = [...repositoryFiles].filter(file => file.endsWith('.md')
  && !/^(?:\.claude|\.agents|\.codex)\//.test(file)
  && existsSync(path.join(root, file))).sort()
const fileSet = new Set(files)
const runtimeRoutes = new Set(['/docs/user-agreement', '/docs/privacy-policy'])
const entrypoint = 'docs/README.md'
const inventory = []
const links = []
const errors = []
const notices = []

for (const file of files) {
  const source = readFileSync(path.join(root, file), 'utf8')
  const tokens = marked.lexer(source)
  const occurrences = new Map()
  inventory.push({
    file,
    title: tokens.find(token => token.type === 'heading' && token.depth === 1)?.text ?? path.basename(file, '.md'),
    lines: source.split('\n').length - Number(source.endsWith('\n')),
  })
  marked.walkTokens(tokens, token => {
    if (token.type !== 'link' && token.type !== 'image') return
    const href = token.href
    if (!href || href.startsWith('#')) return
    if (/^(?:[a-z][a-z\d+.-]*:|\/\/)/i.test(href) && !/^file:/i.test(href)) return

    const offset = source.indexOf(token.raw, occurrences.get(token.raw) ?? 0)
    occurrences.set(token.raw, Math.max(0, offset) + token.raw.length)
    const line = source.slice(0, Math.max(0, offset)).split('\n').length
    const reference = { file, line, href }
    let targetPath
    try {
      targetPath = decodeURIComponent(href.split('#')[0].split('?')[0])
    } catch {
      errors.push({ ...reference, kind: 'invalid-url-encoding' })
      return
    }
    if (runtimeRoutes.has(targetPath)) {
      notices.push({ ...reference, kind: 'runtime-route' })
      return
    }
    if (/^file:/i.test(targetPath)) {
      errors.push({ ...reference, kind: 'absolute-path' })
      return
    }

    // Legacy source citations used file.ts:123; resolve them but require portable #L123 links.
    const oldLineSuffix = /:\d+(?:-\d+)?$/.test(targetPath)
    targetPath = targetPath.replace(/:\d+(?:-\d+)?$/, '')
    const target = path.resolve(root, path.dirname(file), targetPath)
    const relativeTarget = path.relative(root, target).split(path.sep).join('/')
    const link = { ...reference, target: relativeTarget }
    links.push(link)
    if (path.isAbsolute(targetPath)) errors.push({ ...link, kind: 'absolute-path' })
    if (oldLineSuffix) errors.push({ ...link, kind: 'legacy-line-link' })
    if (relativeTarget === '..' || relativeTarget.startsWith('../')) {
      errors.push({ ...link, kind: 'outside-repository' })
      return
    }

    // Local evidence remains useful in the author's workspace, but is absent in fresh clones.
    if (/^(?:test-artifacts|scripts\/local)(?:\/|$)/.test(relativeTarget)) {
      notices.push({ ...link, kind: 'local-evidence', available: existsSync(target) })
      return
    }
    if (!existsSync(target)) {
      errors.push({ ...link, kind: 'missing' })
      return
    }
    const included = statSync(target).isDirectory()
      ? [...repositoryFiles].some(candidate => candidate.startsWith(relativeTarget + '/'))
      : repositoryFiles.has(relativeTarget)
    if (!included) errors.push({ ...link, kind: 'ignored-target' })
  })
}

// Directory links do not silently index their children: each document needs a real link.
const outgoing = new Map()
for (const link of links) {
  if (!fileSet.has(link.target)) continue
  if (!outgoing.has(link.file)) outgoing.set(link.file, [])
  outgoing.get(link.file).push(link.target)
}
const reachable = new Set()
const pending = [entrypoint]
while (pending.length > 0) {
  const file = pending.pop()
  if (reachable.has(file) || !fileSet.has(file)) continue
  reachable.add(file)
  pending.push(...(outgoing.get(file) ?? []))
}
const unindexed = files.filter(file => !reachable.has(file))
const summary = {
  markdownFiles: files.length,
  localLinks: links.length,
  indexed: reachable.size,
  unindexed: unindexed.length,
  errors: errors.length,
  localEvidence: notices.filter(notice => notice.kind === 'local-evidence').length,
  runtimeRoutes: notices.filter(notice => notice.kind === 'runtime-route').length,
  outputDirectory: path.relative(root, outputDir),
}
const result = { generatedAt: new Date().toISOString(), entrypoint, summary, unindexed, errors, notices }
mkdirSync(outputDir, { recursive: true })
writeFileSync(path.join(outputDir, 'document-inventory.json'), JSON.stringify(inventory, null, 2) + '\n')
writeFileSync(path.join(outputDir, 'link-audit.json'), JSON.stringify(result, null, 2) + '\n')
console.log(JSON.stringify(summary, null, 2))
for (const error of errors.slice(0, 20)) console.error(`${error.file}:${error.line} [${error.kind}] ${error.href}`)
for (const file of unindexed.slice(0, 20)) console.error(`[unindexed] ${file}`)
if (errors.length > 0 || unindexed.length > 0) process.exitCode = 1
