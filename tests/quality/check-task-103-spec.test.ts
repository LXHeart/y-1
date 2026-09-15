import { mkdtempSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { checkTaskBookSpec, parseTaskBook } from '../../scripts/quality/check-task-103-spec.js'

/**
 * 任务书 #103 C103-27（TC103-27-01）：spec checker 的正例与合成负例。
 * 真实任务书必须通过；故意造出的环依赖/悬空 REQ/TC/V、白名单外写入组、
 * EXISTING 文件缺失、空占位逐项可复现失败。
 */

const repositoryRoot = resolve(import.meta.dirname, '../..')
const realBook = readFileSync(
  resolve(repositoryRoot, 'docs/任务书/草场任务书-103-全模块业务一致性修复与工程优化.md'),
  'utf8',
)

/** 最小合法规格（配合真实存在的仓库文件路径）。 */
function minimalBook(): string {
  return `# 任务书
| REQ103-01 | 示例需求 | C103-01 |
| BR-01 | 示例规则 | REQ103-01 |

#### W01 · 示例组
| 类型 | 精确文件 |
|---|---|
| EXISTING | [vitest.config.ts](../../vitest.config.ts) |
| NEW | \`scripts/quality/new-tool.ts\` |

| 卡 | 工作 | 前置 | 需求 | 可写组 | 状态 |
|---|---|---|---|---|---|
| C103-01 | 示例卡 | 无 | \`REQ103-01\` | W01 | VERIFIED |

### C103-01 · 示例卡
**执行包**：v1.0；需求 \`REQ103-01\`；规则 \`BR-01\`。
**验收 AC103-01-A**：示例。
E01 场景 TC103-01-01。

#### C103-01 的验收用例
| TC | 场景 | When | Then | 验证 / 状态 |
|---|---|---|---|---|
| TC103-01-01 | 正例 | 执行 | 通过 | V01；NOT_RUN |

| V | 范围 / 命令 | 通过条件 |
|---|---|---|
| V01 | npm run test | 通过 |
`
}

describe('真实任务书 #103（正例）', () => {
  it('解析出 27 卡、所有 W 组、REQ/BR/TC/V 集合', () => {
    const spec = parseTaskBook(realBook)
    expect(spec.cards).toHaveLength(27)
    expect(spec.wGroups.size).toBeGreaterThanOrEqual(27)
    expect(spec.reqs.size).toBeGreaterThanOrEqual(15)
    expect(spec.tcs.size).toBeGreaterThanOrEqual(162)
    expect(spec.vs.size).toBeGreaterThanOrEqual(19)
  })

  it('依赖无环、引用有去向、EXISTING 文件存在、无空占位', () => {
    const issues = checkTaskBookSpec(realBook, resolve(repositoryRoot, 'docs/任务书'))
    // §13.4 已含 R1/R2 修订文字；若规格本身有问题则如实展示
    expect(issues, issues.map((issue) => `${issue.code}: ${issue.detail}`).join('\n')).toEqual([])
  })
})

describe('合成负例逐项失败（TC103-27-01）', () => {
  it('合法最小规格通过', () => {
    const dir = mkdtempSync(join(tmpdir(), 't103-spec-'))
    const bookPath = join(dir, 'book.md')
    writeFileSync(bookPath, minimalBook(), 'utf8')
    // 相对链接 ../../vitest.config.ts 相对 book 目录解析 → 临时目录上跳两级须指向仓库：
    // 用 --book 同目录结构不可移植，这里直接以仓库 docs/任务书 为目录跑同一字符串。
    const issues = checkTaskBookSpec(minimalBook(), resolve(repositoryRoot, 'docs/任务书'))
    expect(issues).toEqual([])
  })

  it('后置依赖（环）、悬空 REQ/TC/V、白名单外写入组、空单元格、占位词逐项报出', () => {
    const broken = minimalBook()
      .replace('| C103-02 | 二卡 | C103-01、C103-03 |', '| C103-02 | 二卡 | C103-01 |') // keep simple
    const content = [
      broken,
      '| C103-02 | 二卡 | C103-03、C103-99 | `REQ103-99` | W99 | NOT_STARTED |',
      '',
      '### C103-02 · 二卡',
      '**验收 AC103-02-A**：示例。',
      '引用 TC103-02-99。',
      '',
      '#### C103-02 的验收用例',
      '| TC103-02-01 | 正例 | 执行 | 通过 | V99；NOT_RUN |',
      '| C103-03 | 空卡 | 无 | | | |',
      'TODO 待补',
    ].join('\n')
    const issues = checkTaskBookSpec(content, resolve(repositoryRoot, 'docs/任务书'))
    const codes = issues.map((issue) => issue.code)
    expect(codes).toContain('DEPENDENCY_CYCLE')        // C103-02 → C103-03（后置）
    expect(codes).toContain('DEPENDENCY_MISSING')      // C103-03 不存在
    expect(codes).toContain('REQ_DANGLING')            // REQ103-99 未登记
    expect(codes).toContain('TC_DANGLING')             // TC103-02-99 未定义
    expect(codes).toContain('V_DANGLING')              // V99 未定义
    expect(codes).toContain('W_GROUP_DANGLING')        // W99 不在白名单
    expect(codes).toContain('EMPTY_CELL')              // C103-03 行空单元格
    expect(codes).toContain('PLACEHOLDER')             // TODO
    expect(codes).toContain('AC_MISSING')              // C103-03 无 AC
  })

  it('EXISTING 文件缺失显式报出', () => {
    const missing = minimalBook().replace(
      '[vitest.config.ts](../../vitest.config.ts)',
      '[不存在文件](../../docs/任务书/definitely-missing-file.ts)',
    )
    const issues = checkTaskBookSpec(missing, resolve(repositoryRoot, 'docs/任务书'))
    expect(issues.map((issue) => issue.code)).toContain('EXISTING_FILE_MISSING')
  })
})
