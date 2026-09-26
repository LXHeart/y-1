import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * 工具契约三向一致锁（任务书 #107-fix-1 C107F-02 / TC-F02-05 / AC-F02-05）。
 *
 * 静态解析三侧源文件并断言两两一致：
 *   契约 ids（contracts/hypit-tools.v1.json）
 *   == J 白名单（HypitAssetService.java 的 MEDIA_TOOLS 集）∪ 预注记排除集
 *   ⊆ B dispatcher 真实 kind 集（tools/*.ts 常量正则提取）。
 * 人为漂移任一侧（改契约 id / 删 J 白名单项 / 删 B kind）本测试即红——
 * 漂移自检证据按卡记录于 test-artifacts/task-107/fix1/。纯静态读文件，
 * 不 import backend 模块（CI hypit-contract 层无引擎 G）。
 */

const REPOSITORY_ROOT = resolve(import.meta.dirname, '../..')

/**
 * 契约已声明但不在 owner 级 TOOLS_INVOKE 白名单的预注记排除项：
 * - packages.install / packages.status：operator 专用（/runtime/packages 专端点，§1.4）。
 * 其余全部进 J 白名单（19 项）——含 capture.install-browser（owner 可经
 * TOOLS_INVOKE 触发渲染浏览器预装，与 media.prepare-fetch 同列，D-06）。
 */
const OPERATOR_ONLY = new Set(['packages.install', 'packages.status'])

function read(path: string): string {
  return readFileSync(resolve(REPOSITORY_ROOT, path), 'utf8')
}

function contractToolIds(): string[] {
  const contract = JSON.parse(read('contracts/hypit-tools.v1.json')) as {
    format: string
    tools: Array<{ id: string }>
  }
  expect(contract.format).toBe('y1.hypit-tools@1')
  return contract.tools.map((tool) => tool.id)
}

/** Java Set.of(...) 字符串常量集提取（锚定常量名，跨行）。 */
function javaStringSet(path: string, constant: string): Set<string> {
  const source = read(path)
  const anchor = new RegExp(`static\\s+final\\s+Set<String>\\s+${constant}\\s*=\\s*Set\\.of\\(`)
  const start = anchor.exec(source)
  if (start === null) throw new Error(`${constant} not found in ${path}`)
  const end = source.indexOf(');', start.index)
  const body = source.slice(start.index, end)
  return new Set([...body.matchAll(/"([^"]+)"/gu)].map((match) => match[1]))
}

/** TypeScript kind 常量（数组或 Set）字符串提取（锚定常量名）。 */
function tsKindConstants(path: string, constant: string): Set<string> {
  const source = read(path)
  const anchor = new RegExp(
    `(?:export\\s+)?const\\s+${constant}\\s*=\\s*(?:\\[([^\\]]*)\\]|new Set\\(\\[([^\\]]*)\\]\\))`,
  )
  const match = anchor.exec(source)
  if (match === null) throw new Error(`${constant} not found in ${path}`)
  const body = match[1] ?? match[2] ?? ''
  return new Set([...body.matchAll(/"([^"]+)"/gu)].map((m) => m[1]))
}

describe('工具契约三向一致（TC-F02-05）', () => {
  const contractIds = contractToolIds()

  const javaWhitelist = javaStringSet(
    'platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/asset/HypitAssetService.java',
    'MEDIA_TOOLS',
  )

  const brokerKinds = new Set<string>([
    ...tsKindConstants('platform-hypit/backend/src/tools/media.ts', 'mediaTools'),
    ...tsKindConstants('platform-hypit/backend/src/tools/speech.ts', 'speechTools'),
    ...tsKindConstants('platform-hypit/backend/src/tools/image.ts', 'imageTools'),
    ...tsKindConstants('platform-hypit/backend/src/tools/packages.ts', 'PACKAGE_KINDS'),
    ...tsKindConstants('platform-hypit/backend/src/tools/snapshot.ts', 'snapshotTools'),
    ...tsKindConstants('platform-hypit/backend/src/tools/capture.ts', 'captureTools'),
  ])

  it('契约工具 id 无重复且格式合法', () => {
    expect(new Set(contractIds).size).toBe(contractIds.length)
    for (const id of contractIds) expect(id).toMatch(/^[a-z][a-z0-9.-]*$/u)
  })

  it('契约 ids − 预注记排除集 == Java TOOLS_INVOKE 白名单', () => {
    const declared = new Set(contractIds)
    const expected = [...declared].filter((id) => !OPERATOR_ONLY.has(id))
    expect(new Set(expected)).toEqual(javaWhitelist)
  })

  it('契约 ids ⊆ B dispatcher 真实 kind 集；排除集亦然', () => {
    for (const id of contractIds) {
      expect(brokerKinds.has(id), `contract id "${id}" has no broker kind`).toBe(true)
    }
    for (const id of OPERATOR_ONLY) {
      expect(brokerKinds.has(id), `excluded id "${id}" must still be a real broker kind`).toBe(true)
    }
  })

  it('Java 白名单全部是 B 真实 kind（不允许白名单超前于路由）', () => {
    for (const id of javaWhitelist) {
      expect(brokerKinds.has(id), `whitelisted tool "${id}" is not routed in the broker`).toBe(true)
    }
  })
})
