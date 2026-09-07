// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { defineComponent, ref } from 'vue'
import { useCreationWorkspace } from '../../../lib/creation-workspace'
import type { CreationProject } from '../../../types/creation'
import { useWorkspaceAutosave } from './useWorkspaceAutosave'

/**
 * 工作区自动保存（任务书 #92 C-04）核心语义：防抖创建/更新、pendingContinue 与 ?draft 恢复、
 * 409 冲突合并重放、失败重试、空表单不创建、卸载 flush、旧字段缺省回退首步（AC-301..303）。
 */

type FetchCall = { url: string; method: string; body?: Record<string, any> }
const calls: FetchCall[] = []
let respond: ((url: string, method: string, body: any) => unknown) = () => ({ success: true, data: {} })

function jsonResponse(payload: unknown, status = 200): Response {
  return new Response(JSON.stringify(payload), { status, headers: { 'Content-Type': 'application/json' } })
}

function projectFixture(overrides: Record<string, unknown> = {}): CreationProject {
  return {
    id: 'draft-1', title: 'fixture 标题', capability: 'article', status: 'draft', version: 3,
    workspace: { capability: 'article', currentStep: 'content', inputs: { topic: '远端主题' } },
    resultAssetIds: [], runIds: [], updatedAt: '2026-09-07T10:00:00Z', ...overrides,
  } as CreationProject
}

beforeEach(() => {
  calls.length = 0
  respond = () => ({ success: true, data: {} })
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method || 'GET'
    const body: Record<string, any> | undefined = init?.body ? JSON.parse(String(init.body)) : undefined
    calls.push({ url, method, body })
    const result = respond(url, method, body)
    if (result instanceof Response) return result
    return jsonResponse(result)
  }))
})
afterEach(() => {
  vi.unstubAllGlobals()
})
enableAutoUnmount(afterEach)

function harness(options: Partial<Parameters<typeof useWorkspaceAutosave>[0]> = {}) {
  const step = ref('topic')
  const topic = ref('')
  const outline = ref('')
  const consumedProject = ref<unknown>(null)
  const Host = defineComponent({
    setup() {
      const autosave = useWorkspaceAutosave({
        capability: 'article',
        steps: ['topic', 'outline', 'content'],
        currentStep: step,
        collectInputs: () => ({ topic: topic.value, outline: outline.value }),
        applyInputs: (inputs) => {
          if (typeof inputs.topic === 'string' && inputs.topic) topic.value = inputs.topic
          if (typeof inputs.outline === 'string' && inputs.outline) outline.value = inputs.outline
        },
        applyProject: (project) => { consumedProject.value = project },
        isValidInput: () => topic.value.trim().length > 0,
        deriveTitle: () => topic.value.trim().slice(0, 30),
        engage: () => true,
        ...options,
      })
      return { autosave, topic, outline, step }
    },
    template: '<div />',
  })
  return mount(Host)
}

describe('useWorkspaceAutosave（C-04）', () => {
  test('TC-C04-001 有效输入 800ms 防抖创建 draft，后续变更走 PUT 乐观锁', async () => {
    vi.useFakeTimers()
    try {
      respond = (url, method) => {
        if (method === 'POST') return { success: true, data: projectFixture({ id: 'draft-new', version: 1 }) }
        return { success: true, data: projectFixture({ id: 'draft-new', version: 2 }) }
      }
      const wrapper = harness()
      const state = () => wrapper.vm.autosave as ReturnType<typeof useWorkspaceAutosave>

      // 空表单不创建（§5.3）
      state().queueSave()
      vi.advanceTimersByTime(900)
      expect(calls.filter((call) => call.method === 'POST')).toHaveLength(0)

      wrapper.vm.topic = '春季上新'
      state().queueSave()
      vi.advanceTimersByTime(799)
      expect(calls.filter((call) => call.method === 'POST')).toHaveLength(0)
      vi.advanceTimersByTime(2)
      await flushPromises()
      const created = calls.find((call) => call.method === 'POST')!
      expect(created.url).toBe('/api/creation-drafts')
      expect(created.body!.capability).toBe('article')
      expect(created.body!.workspace.inputs.topic).toBe('春季上新')
      expect(state().saveState.value).toBe('saved')
      expect(state().draftId.value).toBe('draft-new')

      // 第二次变更走 PUT + expectedVersion
      wrapper.vm.topic = '春季上新二'
      state().queueSave()
      vi.advanceTimersByTime(900)
      await flushPromises()
      const puts0 = calls.filter((call) => call.method === 'PUT')
      const put = puts0[puts0.length - 1]!
      expect(put.url).toBe('/api/creation-drafts/draft-new')
      expect(put.body!.expectedVersion).toBe(1)
    } finally {
      vi.useRealTimers()
    }
  })

  test('TC-C04-002 恢复：pendingContinue 交接优先，字段/步骤/标题回填；?draft 深链次之', async () => {
    respond = () => ({ success: true, data: projectFixture({ id: 'draft-route', workspace: { capability: 'article', currentStep: 'outline', inputs: { topic: '深链主题' } } }) })
    // pendingContinue 交接（C-03 置入）
    useCreationWorkspace().setPendingContinue(projectFixture())
    const wrapper = harness()
    expect(wrapper.vm.topic).toBe('远端主题')
    expect(wrapper.vm.step).toBe('content')
    expect((wrapper.vm.autosave as ReturnType<typeof useWorkspaceAutosave>).draftId.value).toBe('draft-1')
    expect(useCreationWorkspace().pendingContinue.value).toBeNull()

    // 无交接时走 ?draft 深链
    const wrapper2 = harness({ restoreRouteDraftId: () => 'draft-route' })
    await flushPromises()
    expect(wrapper2.vm.topic).toBe('深链主题')
    expect(wrapper2.vm.step).toBe('outline')
  })

  test('TC-C04-003 409 冲突：GET 最新版本后以本地字段合并重放一次', async () => {
    let putCount = 0
    respond = (url, method) => {
      if (method === 'POST') return { success: true, data: projectFixture({ id: 'draft-race', version: 1 }) }
      if (method === 'PUT') {
        putCount += 1
        if (putCount === 1) return jsonResponse({ success: false, error: '冲突' }, 409)
        return { success: true, data: projectFixture({ id: 'draft-race', version: 7 }) }
      }
      if (method === 'GET') return { success: true, data: projectFixture({ id: 'draft-race', version: 5 }) }
      return { success: true, data: {} }
    }
    const wrapper = harness()
    const state = wrapper.vm.autosave as ReturnType<typeof useWorkspaceAutosave>
    wrapper.vm.topic = '第一版'
    await state.flush()          // 创建 draft-race v1
    wrapper.vm.topic = '本地优先主题'
    await state.flush()          // PUT v1 → 409 → GET v5 → 合并重放
    // 首次 PUT 409 → GET v5 → 重放 PUT（本地字段）成功
    const puts = calls.filter((call) => call.method === 'PUT')
    expect(puts.length).toBeGreaterThanOrEqual(2)
    const lastPut = puts[puts.length - 1]!.body!
    expect(lastPut.expectedVersion).toBe(5)
    expect(lastPut.workspace.inputs.topic).toBe('本地优先主题')
    expect(state.saveState.value).toBe('saved')
    expect(state.draftVersion.value).toBe(7)
  })

  test('TC-C04-004 保存失败可重试且不丢编辑；重试成功后转已保存', async () => {
    let failFirst = true
    respond = (url, method) => {
      if (method === 'POST') {
        if (failFirst) {
          failFirst = false
          return jsonResponse({ success: false, error: '网络挂了' }, 500)
        }
        return { success: true, data: projectFixture({ id: 'draft-retry', version: 1 }) }
      }
      return { success: true, data: {} }
    }
    const wrapper = harness()
    const state = wrapper.vm.autosave as ReturnType<typeof useWorkspaceAutosave>
    wrapper.vm.topic = '断网中的编辑'
    await state.flush()
    expect(state.saveState.value).toBe('error')
    expect(wrapper.vm.topic).toBe('断网中的编辑')   // 编辑不丢
    await state.retry()
    expect(state.saveState.value).toBe('saved')
    expect(state.draftId.value).toBe('draft-retry')
  })

  test('AC-303 旧 draft 缺 workspace 字段：步骤归一为首步、inputs 空对象安全回填', async () => {
    respond = () => ({ success: true, data: projectFixture({ workspace: {} }) })
    useCreationWorkspace().setPendingContinue(projectFixture({ workspace: {} }))
    const wrapper = harness()
    expect(wrapper.vm.step).toBe('topic')
    expect(wrapper.vm.topic).toBe('')
  })

  test('卸载 flush 一次：有未保存输入时组件销毁即落库', async () => {
    respond = () => ({ success: true, data: projectFixture({ id: 'draft-unmount', version: 1 }) })
    const wrapper = harness()
    const state = wrapper.vm.autosave as ReturnType<typeof useWorkspaceAutosave>
    wrapper.vm.topic = '临走前的输入'
    state.queueSave()
    wrapper.unmount()
    await flushPromises()
    expect(calls.some((call) => call.method === 'POST' && call.body?.workspace?.inputs?.topic === '临走前的输入')).toBe(true)
  })

  test('engage=false（草场侧共享视图）零请求零恢复', async () => {
    useCreationWorkspace().setPendingContinue(projectFixture())
    const wrapper = harness({ engage: () => false })
    expect(calls).toHaveLength(0)
    expect(wrapper.vm.topic).toBe('')
    const state = wrapper.vm.autosave as ReturnType<typeof useWorkspaceAutosave>
    wrapper.vm.topic = '草场输入'
    state.queueSave()
    await state.flush()
    expect(calls).toHaveLength(0)
  })
})
