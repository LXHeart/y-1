import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { effectScope } from 'vue'
import { useCreationDraft } from './useCreationDraft'
import { DRAFT_STATUSES, type CreationDraft } from '../types/creation-assistant'

afterEach(() => {
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

function envelope(data: unknown, status = 200): Response {
  return new Response(JSON.stringify({ success: status < 400, data, error: status >= 400 ? data : undefined }), {
    status, headers: { 'Content-Type': 'application/json' },
  })
}

function errorEnvelope(message: string, status: number): Response {
  return new Response(JSON.stringify({ success: false, error: message }), {
    status, headers: { 'Content-Type': 'application/json' },
  })
}

function draftFixture(overrides: Partial<CreationDraft> = {}): CreationDraft {
  return {
    id: 'd-1', title: '草稿一', sourceType: 'independent', status: 'draft', version: 1,
    createdAt: '2026-08-07T00:00:00Z', updatedAt: '2026-08-07T00:00:00Z',
    content: '原正文', ...overrides,
  }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((next) => { resolve = next })
  return { promise, resolve }
}

describe('useCreationDraft', () => {
  beforeEach(() => vi.useFakeTimers())

  it('草稿状态契约与 intelligence 后端枚举一致', () => {
    expect(DRAFT_STATUSES).toEqual(['draft', 'in_progress', 'completed', 'archived'])
  })

  it('列表解信封取 items', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => envelope({ items: [draftFixture()] })))
    const store = useCreationDraft()
    await store.loadDrafts()

    expect(store.drafts.value).toHaveLength(1)
    expect(store.drafts.value[0].title).toBe('草稿一')
    expect(store.error.value).toBe('')
  })

  it('列表响应缺少 items 时回退为空数组', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => envelope({})))
    const store = useCreationDraft()
    await store.loadDrafts()

    expect(store.drafts.value).toEqual([])
    expect(store.error.value).toBe('')
  })

  it('非 JSON 错误响应使用带状态码的安全文案', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('bad gateway', { status: 502 })))
    const store = useCreationDraft()
    await store.loadDrafts()

    expect(store.drafts.value).toEqual([])
    expect(store.error.value).toBe('请求失败（502）')
  })

  it('debounce 期间多次改动只发一次 PUT，且用服务端回传 version 覆盖本地', async () => {
    const fetchMock = vi.fn(async (_url: string, init?: RequestInit) => {
      if (init?.method === 'POST') return envelope(draftFixture())
      return envelope(draftFixture({ version: 2, content: '第三次', updatedAt: '2026-08-07T01:00:00Z' }))
    })
    vi.stubGlobal('fetch', fetchMock as unknown as typeof fetch)

    const store = useCreationDraft()
    await store.createDraft({ sourceType: 'independent' })
    fetchMock.mockClear()

    store.queueSave({ content: '第一次' })
    store.queueSave({ content: '第二次' })
    store.queueSave({ content: '第三次' })
    expect(store.autosaveState.value).toBe('pending')
    expect(fetchMock).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(1500)

    expect(fetchMock).toHaveBeenCalledTimes(1)
    const body = JSON.parse((fetchMock.mock.calls[0][1] as RequestInit).body as string)
    expect(body.content).toBe('第三次')
    expect(body.expectedVersion).toBe(1)
    expect(store.draft.value?.version).toBe(2)
    expect(store.autosaveState.value).toBe('saved')
  })

  it('慢 PUT 期间的后续编辑会在首个请求完成后继续保存', async () => {
    const firstPut = deferred<Response>()
    const secondPut = deferred<Response>()
    let putCount = 0
    const fetchMock = vi.fn(async (_url: string, init?: RequestInit) => {
      if (init?.method === 'POST') return envelope(draftFixture())
      putCount += 1
      if (putCount === 1) return firstPut.promise
      return secondPut.promise
    })
    vi.stubGlobal('fetch', fetchMock as unknown as typeof fetch)

    const store = useCreationDraft()
    await store.createDraft({ sourceType: 'independent' })
    fetchMock.mockClear()

    store.queueSave({ content: '第一版' })
    await vi.advanceTimersByTimeAsync(1500)
    store.queueSave({ content: '第二版' })
    await vi.advanceTimersByTimeAsync(1500)
    expect(fetchMock).toHaveBeenCalledTimes(1)

    firstPut.resolve(envelope(draftFixture({ version: 2, content: '第一版' })))
    await vi.runAllTimersAsync()
    await Promise.resolve()

    expect(fetchMock).toHaveBeenCalledTimes(2)
    const secondBody = JSON.parse((fetchMock.mock.calls[1][1] as RequestInit).body as string)
    expect(secondBody).toMatchObject({ expectedVersion: 2, content: '第二版' })
    expect(store.draft.value).toMatchObject({ version: 2, content: '第二版' })

    secondPut.resolve(envelope(draftFixture({ version: 3, content: '第二版' })))
    await store.flush()
    expect(store.draft.value).toMatchObject({ version: 3, content: '第二版' })
  })

  it('保存失败时中止草稿切换并保留当前编辑态', async () => {
    const calls: string[] = []
    const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
      calls.push(`${init?.method ?? 'GET'} ${url}`)
      if (init?.method === 'POST') return envelope(draftFixture())
      return errorEnvelope('网络抖动', 500)
    })
    vi.stubGlobal('fetch', fetchMock as unknown as typeof fetch)

    const store = useCreationDraft()
    await store.createDraft({ sourceType: 'independent' })
    calls.length = 0
    store.queueSave({ content: '不能丢的编辑' })
    const opened = await store.openDraft('d-2')

    expect(opened).toBeNull()
    expect(calls).toEqual(['PUT /api/creation-drafts/d-1'])
    expect(store.draft.value?.id).toBe('d-1')
    expect(store.autosaveState.value).toBe('error')
  })

  it('切换草稿前保存 debounce 改动，且慢 PUT 不会迟到覆盖新选择', async () => {
    const firstPut = deferred<Response>()
    const calls: string[] = []
    const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
      calls.push(`${init?.method ?? 'GET'} ${url}`)
      if (init?.method === 'POST') return envelope(draftFixture())
      if (init?.method === 'PUT') return firstPut.promise
      return envelope(draftFixture({ id: 'd-2', title: '草稿二', version: 7, content: '草稿二正文' }))
    })
    vi.stubGlobal('fetch', fetchMock as unknown as typeof fetch)

    const store = useCreationDraft()
    await store.createDraft({ sourceType: 'independent' })
    calls.length = 0
    store.queueSave({ content: '切换前修改' })

    const opening = store.openDraft('d-2')
    await Promise.resolve()
    expect(calls).toEqual(['PUT /api/creation-drafts/d-1'])

    firstPut.resolve(envelope(draftFixture({ version: 2, content: '切换前修改' })))
    await opening

    expect(calls).toEqual([
      'PUT /api/creation-drafts/d-1',
      'GET /api/creation-drafts/d-2',
    ])
    expect(store.draft.value).toMatchObject({ id: 'd-2', version: 7, content: '草稿二正文' })
  })

  it('打开请求等待期间产生的编辑会在切换前再次保存', async () => {
    const getDraft = deferred<Response>()
    const calls: string[] = []
    const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
      calls.push(`${init?.method ?? 'GET'} ${url}`)
      if (init?.method === 'POST') return envelope(draftFixture())
      if (init?.method === 'PUT') {
        return envelope(draftFixture({ version: 2, content: '请求期间修改' }))
      }
      return getDraft.promise
    })
    vi.stubGlobal('fetch', fetchMock as unknown as typeof fetch)

    const store = useCreationDraft()
    await store.createDraft({ sourceType: 'independent' })
    calls.length = 0
    const opening = store.openDraft('d-2')
    await Promise.resolve()
    await Promise.resolve()
    store.queueSave({ content: '请求期间修改' })
    getDraft.resolve(envelope(draftFixture({ id: 'd-2', title: '草稿二' })))
    await opening

    expect(calls).toEqual([
      'GET /api/creation-drafts/d-2',
      'PUT /api/creation-drafts/d-1',
    ])
    expect(store.draft.value?.id).toBe('d-2')
  })

  it('打开请求等待期间的新编辑保存失败时不切换草稿', async () => {
    const getDraft = deferred<Response>()
    const fetchMock = vi.fn(async (_url: string, init?: RequestInit) => {
      if (init?.method === 'POST') return envelope(draftFixture())
      if (init?.method === 'PUT') return errorEnvelope('网络抖动', 500)
      return getDraft.promise
    })
    vi.stubGlobal('fetch', fetchMock as unknown as typeof fetch)

    const store = useCreationDraft()
    await store.createDraft({ sourceType: 'independent' })
    const opening = store.openDraft('d-2')
    await Promise.resolve()
    await Promise.resolve()
    store.queueSave({ content: '请求期间修改' })
    getDraft.resolve(envelope(draftFixture({ id: 'd-2', title: '草稿二' })))

    expect(await opening).toBeNull()
    expect(store.draft.value?.id).toBe('d-1')
    expect(store.autosaveState.value).toBe('error')
  })

  it('新建请求等待期间产生的编辑会在选择新草稿前再次保存', async () => {
    const createNext = deferred<Response>()
    const calls: string[] = []
    let postCount = 0
    const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
      calls.push(`${init?.method ?? 'GET'} ${url}`)
      if (init?.method === 'POST') {
        postCount += 1
        if (postCount === 1) return envelope(draftFixture())
        return createNext.promise
      }
      return envelope(draftFixture({ version: 2, content: '创建期间修改' }))
    })
    vi.stubGlobal('fetch', fetchMock as unknown as typeof fetch)

    const store = useCreationDraft()
    await store.createDraft({ sourceType: 'independent' })
    calls.length = 0
    const creating = store.createDraft({ sourceType: 'independent', title: '草稿二' })
    await Promise.resolve()
    await Promise.resolve()
    store.queueSave({ content: '创建期间修改' })
    createNext.resolve(envelope(draftFixture({ id: 'd-2', title: '草稿二' })))
    await creating

    expect(calls).toEqual([
      'POST /api/creation-drafts',
      'PUT /api/creation-drafts/d-1',
    ])
    expect(store.draft.value?.id).toBe('d-2')
  })

  it('快速打开两份草稿时只采用最后一次请求的响应', async () => {
    const first = deferred<Response>()
    const second = deferred<Response>()
    const fetchMock = vi.fn((url: string) => url.endsWith('/d-1') ? first.promise : second.promise)
    vi.stubGlobal('fetch', fetchMock as unknown as typeof fetch)

    const store = useCreationDraft()
    const openingFirst = store.openDraft('d-1')
    await Promise.resolve()
    const openingSecond = store.openDraft('d-2')
    second.resolve(envelope(draftFixture({ id: 'd-2', title: '草稿二' })))
    await openingSecond
    first.resolve(envelope(draftFixture({ id: 'd-1', title: '草稿一' })))
    await openingFirst

    expect(store.draft.value).toMatchObject({ id: 'd-2', title: '草稿二' })
    expect(store.loading.value).toBe(false)
  })

  it('新建与删除前都会保存当前草稿的 debounce 改动', async () => {
    const calls: string[] = []
    let postCount = 0
    const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
      calls.push(`${init?.method ?? 'GET'} ${url}`)
      if (init?.method === 'PUT') {
        const id = url.split('/').pop()
        return envelope(draftFixture({ id, version: 2, content: postCount > 1 ? '删除前修改' : '新建前修改' }))
      }
      if (init?.method === 'POST') {
        postCount += 1
        return envelope(draftFixture({ id: `d-${postCount}`, version: 1 }))
      }
      return envelope({ deleted: true })
    })
    vi.stubGlobal('fetch', fetchMock as unknown as typeof fetch)

    const store = useCreationDraft()
    await store.createDraft({ sourceType: 'independent' })
    calls.length = 0
    store.queueSave({ content: '新建前修改' })
    await store.createDraft({ sourceType: 'independent', title: '新草稿' })
    expect(calls.map((call) => call.split(' ')[0])).toEqual(['PUT', 'POST'])

    calls.length = 0
    store.queueSave({ content: '删除前修改' })
    await store.removeDraft('d-2')
    expect(calls.map((call) => call.split(' ')[0])).toEqual(['PUT', 'DELETE'])
  })

  it('未变更字段回传当前值（后端 save 是整行覆盖，只发 patch 会清空其余字段）', async () => {
    const fetchMock = vi.fn(async (_url: string, init?: RequestInit) => {
      if (init?.method === 'POST') {
        return envelope(draftFixture({ topic: '原主题', outline: '原大纲' }))
      }
      return envelope(draftFixture({ version: 2 }))
    })
    vi.stubGlobal('fetch', fetchMock as unknown as typeof fetch)

    const store = useCreationDraft()
    await store.createDraft({ sourceType: 'independent' })
    fetchMock.mockClear()

    store.queueSave({ content: '只改正文' })
    await vi.advanceTimersByTimeAsync(1500)

    const body = JSON.parse((fetchMock.mock.calls[0][1] as RequestInit).body as string)
    expect(body.content).toBe('只改正文')
    expect(body.topic).toBe('原主题')
    expect(body.outline).toBe('原大纲')
    expect(body.title).toBe('草稿一')
  })

  it('409 进 conflict 态并停止再排程（重试必然再撞同一版本）', async () => {
    const fetchMock = vi.fn(async (_url: string, init?: RequestInit) => {
      if (init?.method === 'POST') return envelope(draftFixture())
      return errorEnvelope('草稿已被其他设备修改，请刷新后合并', 409)
    })
    vi.stubGlobal('fetch', fetchMock as unknown as typeof fetch)

    const store = useCreationDraft()
    await store.createDraft({ sourceType: 'independent' })
    fetchMock.mockClear()

    store.queueSave({ content: '本地改动' })
    await vi.advanceTimersByTimeAsync(1500)

    expect(store.autosaveState.value).toBe('conflict')
    expect(store.error.value).toContain('重新载入')

    // 冲突后继续编辑不再排程
    store.queueSave({ content: '又改了' })
    await vi.advanceTimersByTimeAsync(3000)
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('非冲突失败把改动放回队列，下次 flush 重试', async () => {
    let putCount = 0
    const fetchMock = vi.fn(async (_url: string, init?: RequestInit) => {
      if (init?.method === 'POST') return envelope(draftFixture())
      putCount += 1
      if (putCount === 1) return errorEnvelope('网络抖动', 500)
      return envelope(draftFixture({ version: 2 }))
    })
    vi.stubGlobal('fetch', fetchMock as unknown as typeof fetch)

    const store = useCreationDraft()
    await store.createDraft({ sourceType: 'independent' })

    store.queueSave({ content: '要保住的改动' })
    await vi.advanceTimersByTimeAsync(1500)
    expect(store.autosaveState.value).toBe('error')

    await store.flush()
    expect(store.autosaveState.value).toBe('saved')
    const calls = fetchMock.mock.calls
    const lastBody = JSON.parse((calls[calls.length - 1][1] as RequestInit).body as string)
    expect(lastBody.content).toBe('要保住的改动')
  })

  it('冲突后重载拉服务端版本并清掉冲突态', async () => {
    const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
      if (init?.method === 'POST') return envelope(draftFixture())
      if (init?.method === 'PUT') return errorEnvelope('冲突', 409)
      return envelope(draftFixture({ version: 5, content: '别处的新正文' }))
    })
    vi.stubGlobal('fetch', fetchMock as unknown as typeof fetch)

    const store = useCreationDraft()
    await store.createDraft({ sourceType: 'independent' })
    store.queueSave({ content: '本地改动' })
    await vi.advanceTimersByTimeAsync(1500)
    expect(store.autosaveState.value).toBe('conflict')

    await store.reloadForConflict()
    expect(store.draft.value?.version).toBe(5)
    expect(store.draft.value?.content).toBe('别处的新正文')
    expect(store.autosaveState.value).toBe('idle')
    expect(store.error.value).toBe('')
  })

  it('没有草稿时 queueSave 不发请求', async () => {
    const fetchMock = vi.fn(async () => envelope(null))
    vi.stubGlobal('fetch', fetchMock as unknown as typeof fetch)

    const store = useCreationDraft()
    store.queueSave({ content: '无处可存' })
    await vi.advanceTimersByTimeAsync(3000)

    expect(fetchMock).not.toHaveBeenCalled()
    expect(store.autosaveState.value).toBe('idle')
  })

  it('删除当前草稿会清空编辑态', async () => {
    const fetchMock = vi.fn(async (_url: string, init?: RequestInit) => {
      if (init?.method === 'POST') return envelope(draftFixture())
      return envelope({ deleted: true })
    })
    vi.stubGlobal('fetch', fetchMock as unknown as typeof fetch)

    const store = useCreationDraft()
    await store.createDraft({ sourceType: 'independent' })
    expect(store.draft.value).not.toBeNull()

    await store.removeDraft('d-1')
    expect(store.draft.value).toBeNull()
    expect(store.drafts.value).toHaveLength(0)
  })

  it('scope 销毁时清掉未触发的定时器（不再发请求）', async () => {
    const fetchMock = vi.fn(async (_url: string, init?: RequestInit) => {
      if (init?.method === 'POST') return envelope(draftFixture())
      return envelope(draftFixture({ version: 2 }))
    })
    vi.stubGlobal('fetch', fetchMock as unknown as typeof fetch)

    const scope = effectScope()
    let store!: ReturnType<typeof useCreationDraft>
    scope.run(() => { store = useCreationDraft() })
    await store.createDraft({ sourceType: 'independent' })
    fetchMock.mockClear()

    store.queueSave({ content: '来不及存' })
    scope.stop()
    await vi.advanceTimersByTimeAsync(3000)

    expect(fetchMock).not.toHaveBeenCalled()
  })
})
