// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import { defineComponent, ref } from 'vue'
import type { CreationHandoff } from '../../../types/ai-creation'
import type { useWorkspaceAutosave } from './useWorkspaceAutosave'
import { useWorkspaceHandoff } from './useWorkspaceHandoff'

enableAutoUnmount(afterEach)
function handoff(revision: number): CreationHandoff {
  return { revision, platformId: 'zhihu', contentFormId: 'graphic', workflowId: 'longform',
    targetView: 'article', source: { type: 'independent' }, prefill: { topic: `topic-${revision}` } }
}

function harness(initial: CreationHandoff | null = handoff(1), restored = '') {
  const input = ref(initial)
  const apply = vi.fn()
  const startNew = vi.fn().mockResolvedValue(true)
  const saveState = ref('saved')
  const wrapper = mount(defineComponent({ setup() {
    useWorkspaceHandoff({ handoff: () => input.value, target: 'article', apply, cancel: vi.fn(),
      autosave: { startNew, saveState, restoredProjectId: ref(restored), isRestoring: () => false } as unknown as ReturnType<typeof useWorkspaceAutosave> })
    return () => null
  } }))
  return { input, apply, startNew, saveState, wrapper }
}

describe('工作流来源切换', () => {
  test('同一 revision 不覆盖编辑；连续切换等待保存后只应用最新来源', async () => {
    const state = harness()
    expect(state.apply).toHaveBeenCalledTimes(1)
    state.input.value = { ...handoff(1), prefill: { topic: 'duplicate' } }
    await flushPromises()
    expect(state.apply).toHaveBeenCalledTimes(1)
    let finish!: (saved: boolean) => void
    state.startNew.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    state.input.value = handoff(2)
    await flushPromises()
    state.input.value = handoff(3)
    await flushPromises()
    expect(state.apply).toHaveBeenCalledTimes(1)
    finish(true)
    await flushPromises()
    expect(state.startNew).toHaveBeenCalledTimes(1)
    expect(state.apply.mock.lastCall?.[0].revision).toBe(3)
  })

  test('保存冲突保留本地编辑，解决冲突后才接收新来源', async () => {
    const state = harness()
    state.startNew.mockResolvedValueOnce(false)
    state.input.value = handoff(2)
    state.saveState.value = 'conflict'
    await flushPromises()
    expect(state.apply).toHaveBeenCalledTimes(1)
    state.saveState.value = 'saved'
    await flushPromises()
    expect(state.apply.mock.lastCall?.[0].revision).toBe(2)
  })

  test('恢复优先于挂载时的旧 handoff；后续新 revision 可以开始新项目', async () => {
    const state = harness(handoff(1), 'restored-project')
    expect(state.apply).not.toHaveBeenCalled()
    state.input.value = handoff(2)
    await flushPromises()
    expect(state.apply.mock.lastCall?.[0].revision).toBe(2)
  })

  test('初次无 handoff 的恢复页仍接收后来到达的新来源；卸载忽略晚响应', async () => {
    const state = harness(null, 'restored-project')
    let finish!: (saved: boolean) => void
    state.startNew.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    state.input.value = handoff(2)
    await flushPromises()
    expect(state.startNew).toHaveBeenCalledOnce()
    state.wrapper.unmount()
    finish(true)
    await flushPromises()
    expect(state.apply).not.toHaveBeenCalled()
  })
})
