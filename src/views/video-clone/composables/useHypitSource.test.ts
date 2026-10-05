// @vitest-environment happy-dom
// useHypitSource.test.ts — C107-21 (TC107-21-04 保存语义)：save 允许坏语法、
// validated 未过 check 不动 head、CAS 409 冲突时草稿保留。
// C107F3-11（W81 / TC-F2-37-01 编辑保存段）：迟到的 open 解析不得回踩在途
// 保存链——e2e trace 实证 open 的 GET file 在保存链 apply 落定前解析会把
// saveState 置回 idle（保存按钮中途复活），紧随的生成在 revision 推进前
// plan → builds 409 plan_stale，旅程静默卡死。
import { beforeEach, describe, expect, test, vi } from 'vitest'
import { useHypitSource } from './useHypitSource'

type FetchHandler = (url: string, init: RequestInit) => Response | null
let handler: FetchHandler | null = null

function respond(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })
}

function route(url: string): Response | null {
  if (handler !== null) return handler(url, { method: 'GET' })
  return null
}

let headRevision = 1

beforeEach(() => {
  handler = null
  headRevision = 1
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    const override = route(url)
    if (override !== null) return override
    if (url.endsWith('/files')) {
      return respond(200, { success: true, data: { revision: headRevision, manifestHash: 'h', files: [{ path: 'main.svml', sizeBytes: 1, sha256: 'a' }] } })
    }
    if (url.includes('/file?path=')) {
      // C107F2-10 §6.4：正式字段 hash/revision（旧 baseHash 别名不再是权威）。
      return respond(200, { success: true, data: { path: 'main.svml', content: 'saved', hash: 'b1', revision: headRevision } })
    }
    if (url.endsWith('/changesets')) {
      const body = JSON.parse(String(init?.body)) as { applyMode: string }
      return respond(202, { success: true, data: { changesetId: 'c1', baseRevision: 1, applyMode: body.applyMode, checkStatus: body.applyMode === 'validated' ? 'failed' : 'not_checked', state: 'draft' } })
    }
    if (url.includes('/apply')) {
      headRevision = 2
      return respond(200, { success: true, data: { revision: 2, manifestHash: 'h2', appliedPaths: ['main.svml'] } })
    }
    return respond(404, { success: false, error: 'not found', code: 'hypit_not_found' })
  }))
})

describe('useHypitSource 保存语义（TC107-21-04）', () => {
  test('save 模式保存坏语法：head 前进，状态 saved', async () => {
    const source = useHypitSource()
    await source.refresh('p1')
    await source.open('p1', 'main.svml')
    source.edit('<?svml broken')
    const ok = await source.save('p1', 'save')
    expect(ok).toBe(true)
    expect(source.saveState.value).toBe('saved')
    expect(source.revision.value).toBe(2)
  })

  test('validated 未过 check：不 apply，草稿保留，诊断可见', async () => {
    const source = useHypitSource()
    await source.refresh('p1')
    await source.open('p1', 'main.svml')
    source.edit('broken line')
    const ok = await source.save('p1', 'validated')
    expect(ok).toBe(false)
    expect(source.saveState.value).toBe('error')
    expect(source.diagnostics.value.length).toBeGreaterThan(0)
    // head 不动
    expect(source.revision.value).toBe(1)
  })

  test('CAS 409：状态 conflict，本地草稿保留', async () => {
    handler = (url: string, _init: RequestInit) => {
      if (url.includes('/apply')) return respond(409, { success: false, error: '基线修订已过期', code: 'hypit_revision_conflict' })
      return null
    }
    const source = useHypitSource()
    await source.refresh('p1')
    await source.open('p1', 'main.svml')
    source.edit('my precious draft')
    const ok = await source.save('p1', 'save')
    expect(ok).toBe(false)
    expect(source.saveState.value).toBe('conflict')
    expect(source.draft.value).toBe('my precious draft')
  })
})

/** 受控延迟响应：open 的 GET file 挂起，保存链推进到 apply 后再释放。 */
function deferredResponse() {
  let resolve!: (response: Response) => void
  const promise = new Promise<Response>((done) => { resolve = done })
  return { promise, resolve }
}

async function until(cond: () => boolean): Promise<void> {
  for (let i = 0; i < 200; i += 1) {
    if (cond()) return
    await new Promise((done) => setTimeout(done, 5))
  }
  throw new Error('条件在等待窗口内未成立')
}

describe('TC-F2-37-01 编辑保存链：迟到的 open 解析不得回踩在途保存态（W81）', () => {
  test('open 的 GET file 在 apply 落定前解析：saveState 仍在途、草稿不被服务器旧内容覆盖，链终态正常收敛', async () => {
    const openRead = deferredResponse()
    const applyResp = deferredResponse()
    let mainReads = 0
    let headRevision = 1
    const applyCalls: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url.includes('/file?path=main.svml')) {
        mainReads += 1
        if (mainReads === 1) return openRead.promise // open() 的读取挂起
        return respond(200, { success: true, data: { path: 'main.svml', content: 'saved', hash: 'b1', revision: headRevision } })
      }
      if (url.includes('/apply')) {
        applyCalls.push(String(init?.body))
        headRevision = 2 // 服务器 apply 落定后 head 前进（保存链末尾 refresh 读到新值）
        return applyResp.promise
      }
      if (url.endsWith('/changesets')) {
        return respond(202, { success: true, data: { changesetId: 'c1', baseRevision: 1, applyMode: 'save', checkStatus: 'not_checked', state: 'draft' } })
      }
      if (url.endsWith('/files')) {
        return respond(200, { success: true, data: { revision: headRevision, manifestHash: 'h', files: [] } })
      }
      return respond(404, { success: false, error: 'not found', code: 'hypit_not_found' })
    }))
    const source = useHypitSource()
    await source.refresh('p1')
    const opening = source.open('p1', 'main.svml')
    source.edit('USER-EDIT')
    const saving = source.save('p1', 'save')
    // 保存链推进到 apply 挂起（changeset 已建、基线已读）。
    await until(() => applyCalls.length > 0)
    // 迟到的 open 解析（trace 实证时序：apply 事务提交前）。
    openRead.resolve(respond(200, { success: true, data: { path: 'main.svml', content: 'saved', hash: 'b1', revision: 1 } }))
    await opening
    // 反例断言：保存按钮的 disabled 依据是 saveState==='saving'——此处被置回
    // idle 即「保存中复活」，用例/用户可在 revision 推进前发起生成（409 死锁面）。
    expect(source.saveState.value).toBe('saving')
    expect(source.draft.value).toBe('USER-EDIT')
    applyResp.resolve(respond(200, { success: true, data: { revision: 2, manifestHash: 'h2', appliedPaths: ['main.svml'] } }))
    await expect(saving).resolves.toBe(true)
    expect(source.saveState.value).toBe('saved')
    expect(source.revision.value).toBe(2)
  })

  test('保存中跨文件 open：视图换到新文件草稿，但保存链仍在途不复活', async () => {
    const applyResp = deferredResponse()
    const applyCalls: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, _init?: RequestInit) => {
      const url = String(input)
      if (url.includes('/file?path=other.svml')) {
        return respond(200, { success: true, data: { path: 'other.svml', content: 'OTHER', hash: 'h-other', revision: 1 } })
      }
      if (url.includes('/file?path=')) {
        return respond(200, { success: true, data: { path: 'main.svml', content: 'saved', hash: 'b1', revision: 1 } })
      }
      if (url.includes('/apply')) {
        applyCalls.push('apply')
        return applyResp.promise
      }
      if (url.endsWith('/changesets')) {
        return respond(202, { success: true, data: { changesetId: 'c1', baseRevision: 1, applyMode: 'save', checkStatus: 'not_checked', state: 'draft' } })
      }
      if (url.endsWith('/files')) {
        return respond(200, { success: true, data: { revision: 1, manifestHash: 'h', files: [] } })
      }
      return respond(404, { success: false, error: 'not found', code: 'hypit_not_found' })
    }))
    const source = useHypitSource()
    await source.refresh('p1')
    await source.open('p1', 'main.svml')
    source.edit('USER-EDIT')
    const saving = source.save('p1', 'save')
    await until(() => applyCalls.length > 0)
    await source.open('p1', 'other.svml')
    // 跨文件打开换草稿（视图跟随新文件），但保存链不被打断为 idle。
    expect(source.draft.value).toBe('OTHER')
    expect(source.saveState.value).toBe('saving')
    applyResp.resolve(respond(200, { success: true, data: { revision: 2, manifestHash: 'h2', appliedPaths: ['main.svml'] } }))
    await expect(saving).resolves.toBe(true)
    // 终态按既有语义（W81 不改）：save() 末尾无条件 savedContent=submitted（被保存
    // 文件的内容），而草稿已随跨文件 open 换成新文件内容——两者不等记 dirty。
    // 本修复的关键断言在上：在途段不被置回 idle（按钮不复活）、链正常收敛不悬挂。
    expect(source.saveState.value).toBe('dirty')
  })
})
