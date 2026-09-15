import { existsSync, readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'

/**
 * 任务书 #103 C103-27（V12）：任务书规格完整性校验。
 *
 * 校验本书自身：依赖无环（串行编号 ⇒ 前置编号必须更小）、每个 REQ/BR/TC/V/AC 引用有去向、
 * 卡写入组属于 §9.1 白名单、EXISTING 文件真实存在、任务表无空占位。
 * 不替代业务测试——只锁规格可追踪性（TC103-27-01）。
 */

export interface SpecIssue {
  code:
    | 'DEPENDENCY_CYCLE'
    | 'DEPENDENCY_MISSING'
    | 'REQ_DANGLING'
    | 'BR_DANGLING'
    | 'TC_DANGLING'
    | 'V_DANGLING'
    | 'AC_MISSING'
    | 'W_GROUP_DANGLING'
    | 'EXISTING_FILE_MISSING'
    | 'EMPTY_CELL'
    | 'PLACEHOLDER'
  detail: string
}

export interface ParsedSpec {
  cards: Array<{ id: string; deps: string[]; reqs: string[]; wGroups: string[]; tcs: string[]; acs: string[] }>
  wGroups: Map<string, Array<{ type: 'EXISTING' | 'NEW'; repoPath: string | null }>>
  reqs: Set<string>
  brs: Set<string>
  tcs: Set<string>
  vs: Set<string>
}

const CARD_RE = /^### (C103-\d{2}) ·/
const W_GROUP_RE = /^#### (W\d{2}) ·/
const TASK_ROW_RE = /^\| (C103-\d{2}) \|/
const REQ_ROW_RE = /^\| (REQ103-\d{2}) \|/
const BR_ROW_RE = /^\| (BR-\d{2}) \|/
const TC_ROW_RE = /^\| (TC103-\d{2}-\d{2}) \|/
const V_ROW_RE = /^\| (V\d{2}) \|/

function refs(text: string, pattern: RegExp): string[] {
  return [...text.matchAll(pattern)].map((match) => match[1])
}

/** 从 W 表行的第二列取仓库路径：markdown 链接取相对链接，反引号取字面路径。 */
function extractRepoPath(cell: string): string | null {
    const link = cell.match(/\]\((\.\.\/[^)]+)\)/)
  if (link) return link[1]
  const literal = cell.match(/`([^`]+)`/)
  return literal ? literal[1] : null
}

export function parseTaskBook(content: string): ParsedSpec {
  const lines = content.split(/\r?\n/)
  const spec: ParsedSpec = {
    cards: [],
    wGroups: new Map(),
    reqs: new Set(),
    brs: new Set(),
    tcs: new Set(),
    vs: new Set(),
  }

  // 任务表（§10）：| C103-NN | 工作 | 前置 | REQ | W | 状态 |
  for (const line of lines) {
    const task = line.match(TASK_ROW_RE)
    if (task) {
      const cells = line.split('|').map((cell) => cell.trim())
      const id = task[1]
      const deps = cells[3] ? refs(cells[3], /(C103-\d{2})/g) : []
      const reqs = refs(cells[4] ?? '', /(REQ103-\d{2})/g)
      const wGroups = refs(cells[5] ?? '', /(W\d{2})/g)
      spec.cards.push({ id, deps, reqs, wGroups, tcs: [], acs: [] })
    }
    const req = line.match(REQ_ROW_RE)
    if (req) spec.reqs.add(req[1])
    const br = line.match(BR_ROW_RE)
    if (br) spec.brs.add(br[1])
    const tc = line.match(TC_ROW_RE)
    if (tc) spec.tcs.add(tc[1])
    const v = line.match(V_ROW_RE)
    if (v) spec.vs.add(v[1])
  }

  // 卡片正文：REQ/BR/TC/AC 引用归属到卡
  let currentCard: ParsedSpec['cards'][number] | null = null
  let currentWGroup: string | null = null
  for (const line of lines) {
    const card = line.match(CARD_RE)
    if (card) {
      currentCard = spec.cards.find((item) => item.id === card[1]) ?? null
      continue
    }
    const group = line.match(W_GROUP_RE)
    if (group) {
      currentWGroup = group[1]
      if (!spec.wGroups.has(currentWGroup)) spec.wGroups.set(currentWGroup, [])
      continue
    }
    if (currentWGroup && /^\| (EXISTING|NEW) \|/.test(line)) {
      const cells = line.split('|').map((cell) => cell.trim())
      spec.wGroups.get(currentWGroup)!.push({
        type: cells[1] as 'EXISTING' | 'NEW',
        repoPath: extractRepoPath(cells[2] ?? ''),
      })
      continue
    }
    if (currentCard) {
      currentCard.tcs.push(...refs(line, /(TC103-\d{2}-\d{2})/g))
      currentCard.acs.push(...refs(line, /(AC103-\d{2}-[AB])/g))
    }
  }
  return spec
}

export function checkTaskBookSpec(content: string, bookDirectory: string): SpecIssue[] {
  const issues: SpecIssue[] = []
  const spec = parseTaskBook(content)
  const cardIds = new Set(spec.cards.map((card) => card.id))

  // 1) 依赖：存在 + 串行方向（前置编号更小 ⇒ 结构性无环）
  for (const card of spec.cards) {
    const number = Number(card.id.slice(5))
    for (const dep of card.deps) {
      if (!cardIds.has(dep)) {
        issues.push({ code: 'DEPENDENCY_MISSING', detail: `${card.id} 依赖不存在的 ${dep}` })
      } else if (Number(dep.slice(5)) >= number) {
        issues.push({ code: 'DEPENDENCY_CYCLE', detail: `${card.id} 依赖后置/同编号 ${dep}（串行执行下构成环）` })
      }
    }
  }

  // 2) REQ/BR/TC/V/AC 引用有去向
  for (const card of spec.cards) {
    for (const req of card.reqs) {
      if (!spec.reqs.has(req)) issues.push({ code: 'REQ_DANGLING', detail: `${card.id} 引用未登记的 ${req}` })
    }
    for (const tc of new Set(card.tcs)) {
      if (!spec.tcs.has(tc)) issues.push({ code: 'TC_DANGLING', detail: `${card.id} 引用未定义的 ${tc}` })
    }
    if (card.acs.length === 0) {
      issues.push({ code: 'AC_MISSING', detail: `${card.id} 未声明验收 AC` })
    }
    for (const group of card.wGroups) {
      if (!spec.wGroups.has(group)) {
        issues.push({ code: 'W_GROUP_DANGLING', detail: `${card.id} 写入组 ${group} 不在 §9.1 白名单` })
      }
    }
  }

  // §12.2 各 TC 行的验证列（Then 之后）必须指向已定义 V 项；只扫验证列，
  // 不把正文里的迁移版本号（V48/V59/V63…）误当验证项。
  for (const line of content.split(/\r?\n/)) {
    if (TC_ROW_RE.test(line)) {
      const cells = line.split('|').map((cell) => cell.trim())
      for (const v of refs(cells.slice(5).join(' '), /\b(V\d{2})\b/g)) {
        if (!spec.vs.has(v)) {
          issues.push({ code: 'V_DANGLING', detail: `TC 行引用未定义的 ${v}：${line.slice(0, 80)}` })
        }
      }
    }
  }

  // 卡正文里的 BR 引用
  for (const line of content.split(/\r?\n/)) {
    if (line.startsWith('**执行包**')) {
      for (const br of refs(line, /(BR-\d{2})/g)) {
        if (!spec.brs.has(br)) issues.push({ code: 'BR_DANGLING', detail: `卡执行包引用未登记的 ${br}` })
      }
    }
  }

  // 3) EXISTING 文件真实存在（NEW 允许实施后存在——发布时状态语义）
  for (const [group, files] of spec.wGroups) {
    for (const file of files) {
      if (file.type === 'EXISTING' && file.repoPath) {
        const absolute = resolve(bookDirectory, file.repoPath)
        if (!existsSync(absolute)) {
          issues.push({ code: 'EXISTING_FILE_MISSING', detail: `${group} EXISTING 文件不存在：${file.repoPath}` })
        }
      }
    }
  }

  // 4) 任务表无空占位（| id | 工作 | 前置 | 需求 | 可写组 | 状态 |）
  for (const line of content.split(/\r?\n/)) {
    if (TASK_ROW_RE.test(line)) {
      const cells = line.split('|').map((cell) => cell.trim())
      if (cells.slice(2, 7).some((cell) => cell.length === 0)) {
        issues.push({ code: 'EMPTY_CELL', detail: `任务表行存在空单元格：${line.slice(0, 60)}` })
      }
    }
  }

  // 5) 无 TODO/TBD/FIXME 占位
  for (const [index, line] of content.split(/\r?\n/).entries()) {
    if (/\b(TODO|TBD|FIXME|待补占位)\b/.test(line)) {
      issues.push({ code: 'PLACEHOLDER', detail: `第 ${index + 1} 行含占位词：${line.slice(0, 60)}` })
    }
  }

  return issues
}

function main(): void {
  const args = process.argv.slice(2)
  let book = ''
  for (let i = 0; i < args.length; i += 1) {
    if (args[i] === '--book' && args[i + 1]) {
      book = args[i + 1]
      i += 1
    }
  }
  if (!book) {
    book = resolve(import.meta.dirname, '../../docs/任务书/草场任务书-103-全模块业务一致性修复与工程优化.md')
  }
  const content = readFileSync(book, 'utf8')
  const issues = checkTaskBookSpec(content, dirname(book))
  for (const issue of issues) {
    console.error(`[${issue.code}] ${issue.detail}`)
  }
  console.log(`check-task-103-spec: ${issues.length} 个问题（book=${book}）`)
  process.exit(issues.length > 0 ? 1 : 0)
}

if (process.argv[1] && resolve(process.argv[1]).endsWith('check-task-103-spec.ts')) {
  main()
}
