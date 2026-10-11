// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { ref } from 'vue'
import { useXhsHotList } from './useXhsHotList'
import { useXhsTopicForm } from './useXhsTopicForm'

/**
 * 热榜薄封装（方案 §6）：pick 预填不触发生成、refine 回填与 epoch 作废、
 * 未登录守卫、热榜加载失败/重试。fetch 全 stub（热榜 JSON + topic-from-hot SSE）。
 */

const calls: string[] = []

function sseResponse(frames: object[]): Response {
  const payload = frames
    .map(frame => `data: ${JSON.stringify(frame)}\n\n`)
    .join('') + 'data: [DONE]\n\n'
  return new Response(new ReadableStream({
    start(controller) {
      controller.enqueue(new TextEncoder().encode(payload))
      controller.close()
    },
  }), { status: 200, headers: { 'Content-Type': 'text/event-stream' } })
}

function hotPayload(): object {
  return {
    success: true,
    data: {
      provider: '60s',
      items: [{ rank: 1, title: '热点一' }, { rank: 2, title: '热点二' }],
      fetchedAt: '2026-10-10T08:00:00Z',
    },
  }
}

function stubFetch(impl: (url: string) => Partial<Response> | Response) {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    calls.push(url)
    const result = impl(url)
    if (result instanceof Response) return result
    return { ok: true, json: async () => result } as Response
  }))
}

function createList(authenticated = true) {
  const form = useXhsTopicForm(ref(''))
  const requestLogin = vi.fn()
  const list = useXhsHotList({
    form,
    isAuthenticated: () => authenticated,
    requestLogin,
  })
  return { form, list, requestLogin }
}

beforeEach(() => { calls.length = 0 })

afterEach(() => { vi.unstubAllGlobals() })

describe('pick（选为选题，不触发生成）', () => {
  test('预填表单题目并记录 pickedTitle；不发起任何生成请求', () => {
    stubFetch(() => new Response('{}', { status: 503 }))
    const { form, list, requestLogin } = createList()

    list.pick('热点一')
    expect(form.fields.title).toBe('热点一')
    expect(list.pickedTitle.value).toBe('热点一')
    expect(requestLogin).not.toHaveBeenCalled()
    // pick 本身零网络请求（fetch 全程未被调用）。
    expect(calls).toEqual([])
  })

  test('换热点丢掉上一条结构化选题（structuredTopic 清空）', () => {
    stubFetch(() => new Response('{}', { status: 503 }))
    const { list } = createList()
    list.assistant.structuredTopic.value = { topic: '旧', angle: '', thesis: '', audience: '', entryPoints: [] }
    list.pick('热点二')
    expect(list.assistant.structuredTopic.value).toBeNull()
  })
})

describe('refine（AI 拆解回填 + epoch 守卫）', () => {
  test('成功回填结构化 topic 到表单题目', async () => {
    stubFetch(url => url === '/api/creation-assistant/topic-from-hot'
      ? sseResponse([{ type: 'topic', topic: '拆解后的选题', angle: 'a', thesis: 't', audience: 'u', entryPoints: 'e1；e2' }])
      : hotPayload())
    const { form, list } = createList()

    list.pick('热点一')
    form.fields.title = '热点一'
    await list.refine('')
    expect(calls).toContain('/api/creation-assistant/topic-from-hot')
    expect(form.fields.title).toBe('拆解后的选题')
    expect(list.assistant.structuredTopic.value?.topic).toBe('拆解后的选题')
  })

  test('在途 refine 期间换热点 → 旧结果作废不回填（epoch 守卫）', async () => {
    let resolveStream: ((value: Response) => void) | null = null
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      calls.push(url)
      if (url === '/api/creation-assistant/topic-from-hot') {
        return new Promise<Response>(resolve => { resolveStream = resolve })
      }
      return { ok: true, json: async () => hotPayload() } as Response
    }))
    const { form, list } = createList()

    list.pick('热点一')
    const pending = list.refine('')
    // 请求在途时换了热点（pick 使 epoch 前进、题目换成新热点）。
    list.pick('热点二')
    // fetch 桩同步赋值 resolveStream；用非空断言绕开 TS 对闭包赋值的收窄。
    resolveStream!(sseResponse([{ type: 'topic', topic: '旧热点的拆解', angle: '', thesis: '', audience: '', entryPoints: '' }]))
    await pending
    expect(form.fields.title).toBe('热点二')
  })

  test('未登录：requestLogin 被调、不发拆解请求', async () => {
    stubFetch(() => new Response('{}', { status: 503 }))
    const { form, list, requestLogin } = createList(false)

    list.pick('热点一')
    await list.refine('')
    expect(requestLogin).toHaveBeenCalledTimes(1)
    expect(calls).toEqual([])
    expect(form.fields.title).toBe('热点一')
  })
})

describe('热榜加载与重试', () => {
  test('挂载拉取一次成功后不重复拉；失败不标记 loaded（重试自动再拉）', async () => {
    let failFirst = true
    stubFetch(url => {
      if (url === '/api/homepage/hot-items' && failFirst) {
        failFirst = false
        return new Response('{}', { status: 503 })
      }
      return hotPayload()
    })
    const { list } = createList()

    await list.ensureLoaded()
    expect(list.hot.error.value).not.toBe('')
    expect(list.hot.items.value).toEqual([])
    // 失败不置 loaded：再次 ensureLoaded 会重拉。
    await list.ensureLoaded()
    expect(calls.filter(url => url === '/api/homepage/hot-items')).toHaveLength(2)
    expect(list.hot.items.value).toHaveLength(2)
    expect(list.hot.error.value).toBe('')
    // 成功后 loaded：第三次不重复拉。
    await list.ensureLoaded()
    expect(calls.filter(url => url === '/api/homepage/hot-items')).toHaveLength(2)
  })

  test('refresh 显式刷新重新拉取（成功后 loaded 置位）', async () => {
    stubFetch(() => hotPayload())
    const { list } = createList()
    await list.refresh()
    expect(list.hot.items.value).toHaveLength(2)
    expect(list.hot.loading.value).toBe(false)
  })
})
