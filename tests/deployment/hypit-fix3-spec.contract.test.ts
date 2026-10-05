import { existsSync, readFileSync, readdirSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * C107F3-13（W048）：107-fix-3 规格契约——本书需求/TC/W/状态与验收结果对应，
 * 缺项拒绝、不将未开始卡标绿（AC-013 / §9.1 W048；C06 复用）。
 *
 * 只做结构与状态一致性核验（读任务书与 W052 分派表、runs 证据目录），
 * 不冒充业务验收：任何卡的 VERIFIED 必须能在 runs 中找到覆盖其 stage 期望 TC
 * 的 exit0 结果，否则本测试失败。
 */

const ROOT = resolve(import.meta.dirname, '../..')
const BOOK_PATH = 'docs/任务书/草场任务书-107-fix-3-reviews.md'
const W52_PATH = 'scripts/acceptance/verify-107-fix-3.sh'
const RUNS_ROOT = resolve(ROOT, 'test-artifacts/task-107/fix3/runs')
const CARDS = ['01', '02', '03', '04', '05', '06', '07', '08', '09', '10', '11', '12', '13']
const STATUSES = ['NOT_STARTED', 'IN_PROGRESS', 'IMPLEMENTED', 'VERIFIED', 'BLOCKED']
const STAGES = ['tooling', 'fixtures', 'variant', 'maintenance', 'engine', 'domain',
  'evidence', 'vision', 'archive', 'context', 'e2e', 'live-contract', 'docs', 'regression', 'all']
const TASK_VERSION = '107-fix-3 v1.1.0'

function read(path: string): string {
  return readFileSync(resolve(ROOT, path), 'utf8')
}

const book = read(BOOK_PATH)
const wrapper = read(W52_PATH)

// ── 纯解析函数（供缺项拒绝负例复用） ────────────────────────────────────────
export function parseCardSections(text: string): string[] {
  const heads = [...text.matchAll(/^### 卡 C107F3-(\d{2})：/gm)].map((m) => m[1])
  if (new Set(heads).size !== heads.length) throw new Error('卡章节标题重复')
  return heads
}

export function parseBookStatuses(text: string): Map<string, string> {
  const seg = text.split('## 10. 开发计划与任务总表')[1]?.split('### 10.1')[0]
  if (!seg) throw new Error('缺 §10 总表')
  const statuses = new Map<string, string>()
  for (const card of CARDS) {
    const row = seg.split('\n').find((l) => l.includes(`| C107F3-${card} `))
    if (!row) throw new Error(`§10 缺行 C107F3-${card}`)
    const status = (row.split('|').slice(-2)[0] ?? '').trim()
    statuses.set(card, status)
  }
  return statuses
}

/** §10 验收列的 TC 规格（如 TC-F3-13-01～03）展开为完整编号。 */
export function parseCardTcSpecs(text: string): Map<string, string[]> {
  const seg = text.split('## 10. 开发计划与任务总表')[1]?.split('### 10.1')[0]
  if (!seg) throw new Error('缺 §10 总表')
  const out = new Map<string, string[]>()
  for (const card of CARDS) {
    const row = seg.split('\n').find((l) => l.includes(`| C107F3-${card} `))
    if (!row) throw new Error(`§10 缺行 C107F3-${card}`)
    const tcs: string[] = []
    for (const m of row.matchAll(/TC-F3-(\d{2})-(\d{2})～(\d{2})/g)) {
      for (let i = Number(m[2]); i <= Number(m[3]); i += 1) {
        tcs.push(`TC-F3-${m[1]}-${String(i).padStart(2, '0')}`)
      }
    }
    for (const m of row.matchAll(/TC-F3-(\d{2})-(\d{2})(?![～\d])/g)) tcs.push(m[0])
    out.set(card, [...new Set(tcs)].sort())
  }
  return out
}

export function parseVStages(text: string): Map<string, string> {
  const seg = text.split('### 12.3 固定验证入口与命令')[1]?.split('### 12.4')[0]
    ?? text.split('### 12.3 固定验证入口与命令')[1]?.split('所有新证据')[0]
  if (!seg) throw new Error('缺 §12.3 命令表')
  const map = new Map<string, string>()
  for (const m of seg.matchAll(/\| (V-\d{2}) \| [^|]*?--stage ([a-z0-9-]+) --run auto/g)) {
    map.set(m[1], m[2])
  }
  return map
}

export function parseWIds(text: string): string[] {
  const seg = text.split('### 9.1 文件白名单 / 黑名单')[1]?.split('### 9.2')[0]
  if (!seg) throw new Error('缺 §9.1 W 表')
  return [...seg.matchAll(/^\| (W\d{2}) \|/gm)].map((m) => m[1])
}

/** W052 stage_expect 分派表：stage → 期望 TC 列表。 */
export function parseWrapperExpect(wrapperText: string): Map<string, string[]> {
  const seg = wrapperText.split('stage_expect() {')[1]?.split('\n}')[0] ?? ''
  const map = new Map<string, string[]>()
  for (const m of seg.matchAll(/^\s*([a-z0-9-]+)\)\s*echo\s+'([^']*)'/gm)) {
    map.set(m[1], m[2].split(',').map((s) => s.trim()).filter(Boolean))
  }
  return map
}

/** §12.1 卡→V 映射（证据 stage 归属）。 */
export function parseCardVMap(text: string): Map<string, string[]> {
  const seg = text.split('### 12.1 追踪与实施状态')[1]?.split('### 12.2')[0]
  if (!seg) throw new Error('缺 §12.1 追踪表')
  const map = new Map<string, string[]>()
  for (const m of seg.matchAll(/^\| (\d{2}) \| [^|]+\| ([^|]+) \|/gm)) {
    map.set(m[1], [...m[2].matchAll(/V-\d{2}/g)].map((x) => x[0]))
  }
  return map
}

/** runs 证据扫描：任一 run 中 stage 最近 attempt exit0 且期望 TC 全发现。 */
export function findStageEvidence(stage: string, expectTcs: string[]): { ok: boolean; runId?: string; why?: string } {
  if (!existsSync(RUNS_ROOT)) return { ok: false, why: 'runs 根不存在' }
  for (const runId of readdirSync(RUNS_ROOT)) {
    const manifestPath = resolve(RUNS_ROOT, runId, 'manifest.json')
    if (!existsSync(manifestPath)) continue
    let manifest: { stages?: Record<string, { attempts?: Array<{ attempt: number; exitCode: number }> }> }
    try {
      manifest = JSON.parse(readFileSync(manifestPath, 'utf8'))
    } catch { continue }
    const attempts = manifest.stages?.[stage]?.attempts ?? []
    for (const att of [...attempts].reverse()) {
      if (att.exitCode !== 0) continue
      const resultsPath = resolve(RUNS_ROOT, runId, stage, `attempt-${att.attempt}`, 'results.json')
      if (!existsSync(resultsPath)) continue
      let results: { exitCode?: number; tc?: { found?: string[] }; taskBook?: string }
      try { results = JSON.parse(readFileSync(resultsPath, 'utf8')) } catch { continue }
      if (results.exitCode !== 0) continue
      if (results.taskBook !== TASK_VERSION) continue
      const found = new Set(results.tc?.found ?? [])
      if (expectTcs.every((tc) => found.has(tc))) return { ok: true, runId }
      return { ok: false, runId, why: `exit0 但缺期望 TC（${expectTcs.filter((t) => !found.has(t)).join(',')}）` }
    }
  }
  return { ok: false, why: '无 exit0 且期望 TC 全发现的 stage 结果' }
}

/** 判定：VERIFIED 卡必须有真实 stage 证据（纯函数，供负例注入）。 */
export function judgeStatusEvidence(
  statuses: Map<string, string>,
  cardStages: Map<string, string[]>,
  expectByStage: Map<string, string[]>,
): { ok: boolean; blocking: string[] } {
  const blocking: string[] = []
  for (const [card, status] of statuses) {
    if (status !== 'VERIFIED') continue
    const stages = cardStages.get(card) ?? []
    if (stages.length === 0) { blocking.push(card); continue }
    for (const stage of stages) {
      const ev = findStageEvidence(stage, expectByStage.get(stage) ?? [])
      if (!ev.ok) { blocking.push(card); break }
    }
  }
  return { ok: blocking.length === 0, blocking }
}

// 卡 → 验收 stage（§12.1 V→stage 反查；卡片编号→V 见 §12.1 表）。
const V_TO_STAGE: Record<string, string> = Object.fromEntries(STAGES.map((s, i) => [`V-${String(i + 1).padStart(2, '0')}`, s]))

function cardStagesFromBook(text: string): Map<string, string[]> {
  const vMap = parseCardVMap(text)
  const map = new Map<string, string[]>()
  for (const [card, vs] of vMap) {
    map.set(card, vs.map((v) => V_TO_STAGE[v]).filter(Boolean))
  }
  return map
}

describe('107-fix-3 任务书结构（W048 规格契约）', () => {
  it('任务书版本与规格状态行在位', () => {
    expect(book).toContain(`任务书版本：1.1.0`)
    expect(book).toMatch(/规格状态：READY_FOR_IMPLEMENTATION/)
  })

  it('13 张卡章节齐全且编号集合恰为 01～13（缺卡拒绝）', () => {
    expect([...parseCardSections(book)].sort()).toEqual([...CARDS].sort())
    // 缺项拒绝负例：删掉一张卡章节标题后，结构解析必须能发现缺失。
    const mutated = book.replace('### 卡 C107F3-06：', '### 卡已删除：')
    expect([...parseCardSections(mutated)].sort(), '删卡必须被解析发现').not.toEqual([...CARDS].sort())
  })

  it('§10 总表 13 行状态词义合法；验收列 AC/TC 与 §12.2 索引对应', () => {
    const statuses = parseBookStatuses(book)
    const tcSpecs = parseCardTcSpecs(book)
    const seg122 = book.split('### 12.2 用例定义')[1]?.split('### 12.3')[0] ?? ''
    expect(seg122.length, '缺 §12.2 用例定义').toBeGreaterThan(1000)
    for (const card of CARDS) {
      expect(STATUSES, `C107F3-${card} 状态非法`).toContain(statuses.get(card))
      const acId = `AC-${card.padStart(3, '0')}`
      expect(book, `C107F3-${card} 缺 ${acId}`).toContain(acId)
      for (const tc of tcSpecs.get(card) ?? []) {
        // 高风险 TC 有 #### 详情标题；文档/计数类 TC 至少登记在 §12.2 表内。
        expect(seg122.includes(tc), `§12.2 缺 ${tc}`).toBe(true)
      }
    }
  })

  it('§12.3 命令表 V-01…V-15 齐全且 stage 与 W052 分派一致；期望 TC 均在本书索引内', () => {
    const vStages = parseVStages(book)
    expect([...vStages.keys()].sort()).toEqual(Array.from({ length: 15 }, (_, i) => `V-${String(i + 1).padStart(2, '0')}`))
    const bookStages = new Set([...vStages.values()])
    for (const stage of STAGES) expect(bookStages, `§12.3 缺 stage ${stage}`).toContain(stage)
    // W052 stage_expect 的期望 TC 必须都登记在本书 §10/§12.2。
    const expectByStage = parseWrapperExpect(wrapper)
    for (const stage of STAGES) {
      expect(expectByStage.has(stage), `W052 缺 stage ${stage} 期望表`).toBe(true)
    }
    // 期望 TC 双书核验：TC-F3-* 必须登记在本书；TC-F2-*（委托旧入口的原有 TC，
    // §12.3 条5「不能减少其原有TC」）必须登记在 fix2 任务书。
    const fix3Tcs = new Set([...book.matchAll(/TC-F3-\d{2}-\d{2}/g)].map((m) => m[0]))
    for (const tcs of parseCardTcSpecs(book).values()) {
      for (const tc of tcs) fix3Tcs.add(tc)
    }
    const fix2Book = read('docs/任务书/草场任务书-107-fix-2-Hypit全链路缺陷修复与真实交付验收.md')
    const fix2Tcs = new Set([...fix2Book.matchAll(/TC-F2-\d{2}-\d{2}/g)].map((m) => m[0]))
    for (const [stage, tcs] of expectByStage) {
      for (const tc of tcs) {
        if (tc.startsWith('TC-F3-')) {
          expect(fix3Tcs.has(tc), `W052 stage ${stage} 期望 ${tc} 不在本书 TC 索引`).toBe(true)
        } else {
          expect(fix2Tcs.has(tc), `W052 stage ${stage} 期望 ${tc} 不在 fix2 任务书索引`).toBe(true)
        }
      }
    }
    // 缺项拒绝负例：删掉 §12.3 的 V-07 命令行必须被解析发现。
    const mutated = book.replace(/\| V-07 \| `bash[^|\n]*\|[^\n]*\n/, '')
    expect(parseVStages(mutated).has('V-07'), '删 V 命令行必须被解析发现').toBe(false)
  })

  it('§9.1 W01…W82 全量登记且责任卡集合不越界（缺 W 拒绝）', () => {
    // C107F3-07 §13.3 增量（登记二/三）：W72 为已登记合法增量行，全量清单随书同步 71→72。
    // C107F3-11 §13.3 增量：W73/W74（补丁 0005 的补丁文件与 manifest 登记行）为已登记合法增量，
    // 全量清单随书同步 72→74；W75/W76（变体重试链补全：服务端 planVariant 闸与
    // UI retry 续走 build 流）、W77（结果面板装载路径补下载签名 enrich）、W78
    //（markQueued WHERE 放行 retry 复位行——smoke12/13 确定性 502 的实证根因）、
    // W79（再生成按钮 regenerateGate——V-11 门禁首跑暴露 UI-03 前置拒绝面
    // 被分析忙碌全闸禁用）、W80（insertPlan 幂等键收窄 (project, plan_hash,
    // revision)——V-11 门禁 TC-F2-37-01 builds 409 hypit_plan_stale 同内容
    // 永久死锁的实证根因，trace 复现锁定）与 W81（useHypitSource open() 迟到
    // 解析回踩在途保存链——W80 后仍失败的真因：保存按钮中途复活→生成在
    // revision 推进前 plan，毫秒级 trace 双时间线实证）与 W82（工作台 fire-
    // and-forget 调用丢弃 rethrow promise→浮动拒绝 pageError 打脏控制台，
    // smoke34 trace pageError 栈实证）同步 74→82（负例同步 71→73→75→76→
    // 77→78→79→80→81，沿用 W72 同步先例）。
    const wIds = parseWIds(book)
    expect(wIds.length).toBe(82)
    const expected = Array.from({ length: 82 }, (_, i) => `W${String(i + 1).padStart(2, '0')}`)
    expect([...wIds].sort()).toEqual([...expected].sort())
    const seg = book.split('### 9.1 文件白名单 / 黑名单')[1].split('### 9.2')[0]
    for (const m of seg.matchAll(/^\| (W\d{2}) \| [^|]+ \| [^|]+ \| [^|]+ \| ([^|]+) \|$/gm)) {
      for (const card of m[2].matchAll(/C107F3-(\d{2})/g)) {
        expect(CARDS, `W${m[1]} 责任卡越界 C107F3-${card[1]}`).toContain(card[1])
      }
    }
    // 缺项拒绝负例：删掉一条 W 行必须被解析拒绝。
    const mutated = book.replace(/\| W64 \|[^\n]*\n/, '')
    expect(parseWIds(mutated).length).toBe(81)
    expect(parseWIds(mutated)).not.toContain('W64')
  })
})

describe('状态-证据一致（W048：不将未开始卡标绿；VERIFIED 必须有当前 run 证据）', () => {
  it('当前书表状态与 runs 证据一致；注入 VERIFIED 于无证据卡必须被拒绝', () => {
    const statuses = parseBookStatuses(book)
    const cardStages = cardStagesFromBook(book)
    const expectByStage = parseWrapperExpect(wrapper)
    // 正向：真实书表判定（当前全部 NOT_STARTED → ok；一旦标 VERIFIED 必须有证据）。
    const verdict = judgeStatusEvidence(statuses, cardStages, expectByStage)
    expect(verdict.ok, `状态与证据不一致：${verdict.blocking.join(',')}`).toBe(true)
    // 反向：把「无证据」卡标成 VERIFIED → 必须拒绝并点名。C107F3-11 §13.3 修复
    // 静态负例的过期性：前序卡（01/07/08/10 等）的 stage 证据随实施真实累积后，
    // 把固定编号（如 '01'）标 VERIFIED 是「合法有证据」而非应被拒绝的注入——
    // 出书时写的静态负例在 C01 的 variant exit0 run 之后恒假红（上轮 tooling
    // 11:09 复验已实录）。改为运行时选取真实无证据卡；当全部卡均有证据（很晚
    // 期才可能）时退化到无 stage 的合成卡，机制断言（无证据的 VERIFIED 必拒）
    // 不随实施进度过期。
    const pickNoEvidenceCard = (): string => {
      for (const [card, stageList] of cardStages) {
        const hasEvidence = (stageList ?? []).every(
          (stage) => findStageEvidence(stage, expectByStage.get(stage) ?? []).ok,
        )
        if (!hasEvidence) return card
      }
      return '99' // 全部卡均有证据：合成无 stage 卡（judgeStatusEvidence 对空 stage 直接拒）。
    }
    const target = pickNoEvidenceCard()
    const mutated = new Map(statuses)
    mutated.set(target, 'VERIFIED')
    const bad = judgeStatusEvidence(mutated, cardStages, expectByStage)
    expect(bad.ok, `把无证据卡 ${target} 标 VERIFIED 必须被拒绝`).toBe(false)
    expect(bad.blocking).toContain(target)
    // 反向二：合成卡（书表不存在、无 stage）标 VERIFIED → 同样必须拒绝。
    const mutated2 = new Map(statuses)
    mutated2.set('99', 'VERIFIED')
    const bad2 = judgeStatusEvidence(mutated2, cardStages, expectByStage)
    expect(bad2.ok).toBe(false)
    expect(bad2.blocking).toContain('99')
  })

  it('C107F3-13 的 VERIFIED 只能由 tooling+fixtures 双 stage 证据支撑', () => {
    const statuses = parseBookStatuses(book)
    const cardStages = cardStagesFromBook(book)
    const expectByStage = parseWrapperExpect(wrapper)
    expect(cardStages.get('13')).toEqual(['tooling', 'fixtures'])
    const st = statuses.get('13') ?? ''
    if (st === 'VERIFIED') {
      const tooling = findStageEvidence('tooling', expectByStage.get('tooling') ?? [])
      const fixtures = findStageEvidence('fixtures', expectByStage.get('fixtures') ?? [])
      expect(tooling.ok, `tooling 证据缺失：${tooling.why ?? ''}`).toBe(true)
      expect(fixtures.ok, `fixtures 证据缺失：${fixtures.why ?? ''}`).toBe(true)
    } else {
      expect(STATUSES).toContain(st)
    }
  })
})
