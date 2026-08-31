import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

type BacklogItem = { id: number; status: 'completed' | 'in_progress' | 'open'; title?: string }
type Status = {
  schemaVersion: number
  updatedAt: string
  backlog: { scope: string; items: BacklogItem[] }
  platform: { edgeIdentityDatabase: string; nodeBackend: string; productionMoney: string }
  quality: { frontendCoverageThresholds: Record<string, number>; changedExecutableLinesThreshold: number }
}

const root = resolve(import.meta.dirname, '../..')
const read = (path: string) => readFileSync(resolve(root, path), 'utf8')
const status = JSON.parse(read('docs/status.yaml')) as Status
const errors: string[] = []
const check = (condition: boolean, message: string) => { if (!condition) errors.push(message) }

check(status.schemaVersion === 1, 'docs/status.yaml schemaVersion must be 1')
check(/^\d{4}-\d{2}-\d{2}$/.test(status.updatedAt), 'updatedAt must be an ISO date')
// 范围由 scope 声明（backlog 是活文档，批次会往后扩）；items 必须无重复、无缺口地连续覆盖 scope。
// 2026-08-28 起硬编码「22-45 共 24 项」与扩容后的 status.yaml 脱节，CI 误红——改为从 scope 派生。
const ids = status.backlog.items.map(item => item.id)
check(new Set(ids).size === ids.length, 'backlog ids must be unique')
const scope = /^(\d+)-(\d+)$/.exec(status.backlog.scope)
check(scope !== null, 'backlog.scope must be formatted as "start-end"')
if (scope) {
  const start = Number(scope[1])
  const end = Number(scope[2])
  check(ids.length === end - start + 1 && ids.every((id, index) => id === start + index),
    `backlog must cover every id from ${start} through ${end} with no gaps`)
}
// in_progress 是如实状态（放行并在结尾汇报）；open（未启动）不允许——收尾批次不留未认领项。
const openIds = status.backlog.items.filter(item => item.status === 'open').map(item => item.id)
check(openIds.length === 0, 'backlog must not contain unstarted (open) items')
const inProgressIds = status.backlog.items.filter(item => item.status === 'in_progress').map(item => item.id)

const overview = read('项目速览.md')
check(overview.includes('`docs/status.yaml`'), '项目速览.md must link to docs/status.yaml')
check(overview.includes('#22–#45 已全部完成'), '项目速览.md completion summary is stale')

const progress = read('docs/草场开发进度与续接指南.md')
check(progress.includes('| ~~33~~ | ~~AI capability 语音与语义检索~~'), 'progress guide must mark #33 completed')
check(!progress.includes('edge-bff 是 WebFlux 透明代理、无 DB'), 'progress guide must describe Edge read-only identity DB access')

const spec = read('docs/superpowers/specs/2026-08-17-document-drift-calibration-design.md')
check(spec.includes('**状态**：已实施'), 'document drift design must be marked implemented')

const vitest = read('vitest.config.ts')
for (const [metric, threshold] of Object.entries(status.quality.frontendCoverageThresholds)) {
  check(new RegExp(`${metric}:\\s*${threshold}(?:,|\\n)`).test(vitest), `vitest ${metric} threshold differs from docs/status.yaml`)
}
const packageJson = JSON.parse(read('package.json')) as { scripts?: Record<string, string> }
check(packageJson.scripts?.['docs:status'] === 'tsx scripts/quality/check-doc-status.ts', 'package docs:status script is missing')

if (errors.length) {
  console.error(errors.map(error => `- ${error}`).join('\n'))
  process.exit(1)
}
console.log(`Document status is consistent (${status.updatedAt}; in progress: ${inProgressIds.map(id => `#${id}`).join(', ') || 'none'}).`)
