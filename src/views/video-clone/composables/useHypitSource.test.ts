// @vitest-environment happy-dom
// useHypitSource.test.ts — C107-21 (TC107-21-04 保存语义)：save 允许坏语法、
// validated 未过 check 不动 head、CAS 409 冲突时草稿保留。
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
      return respond(200, { success: true, data: { path: 'main.svml', content: 'saved', baseHash: 'b1' } })
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
