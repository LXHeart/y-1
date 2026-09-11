import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { effectScope, ref, type EffectScope } from 'vue'
import { flushPromises } from '@vue/test-utils'
import { useWorkbenchTaskPreview } from './useWorkbenchTaskPreview'
import { useAccountSessionStore } from '../../../stores/account-session'
import type { TaskPreview } from '../../../types/grassland'

describe('合作条款预览请求归属', () => {
  let scope: EffectScope
  beforeEach(() => { setActivePinia(createPinia()); scope = effectScope() })
  afterEach(() => scope.stop())

  it('任务切换、账号 epoch 变化后旧回包不能覆盖当前预览', async () => {
    const responses: Array<(value: TaskPreview | null) => void> = []
    const getTaskPreview = vi.fn(() => new Promise<TaskPreview | null>((resolve) => responses.push(resolve)))
    const taskId = ref('task-1')
    const state = scope.run(() => useWorkbenchTaskPreview({ getTaskPreview, error: ref('') }, () => taskId.value))!
    taskId.value = 'task-2'
    useAccountSessionStore().epoch += 1
    responses[1]!({ taskId: 'task-2', title: '旧账号' } as TaskPreview)
    responses[0]!({ taskId: 'task-1' } as TaskPreview)
    await flushPromises()
    expect(state.preview.value).toBeNull()
    expect(state.loading.value).toBe(true)
    responses[2]!({ taskId: 'task-2', title: '当前账号' } as TaskPreview)
    await flushPromises()
    expect(state.preview.value?.title).toBe('当前账号')
    expect(state.loading.value).toBe(false)
  })

  it('加载失败可重试；组件卸载后不回写', async () => {
    const getTaskPreview = vi.fn().mockResolvedValueOnce(null).mockResolvedValueOnce({ taskId: 'task-1' })
    const state = scope.run(() => useWorkbenchTaskPreview({ getTaskPreview, error: ref('暂不可用') }, () => 'task-1'))!
    await flushPromises()
    expect(state.error.value).toBe('暂不可用')
    await state.load()
    expect(state.preview.value?.taskId).toBe('task-1')
    let resolve!: (value: TaskPreview) => void
    getTaskPreview.mockImplementation(() => new Promise<TaskPreview>((done) => { resolve = done }))
    const pending = state.load()
    scope.stop()
    resolve({ taskId: 'task-1', title: '卸载后的回包' } as TaskPreview)
    await pending
    expect(state.preview.value).toBeNull()
  })

  it('原始技术错误文案（如网关/Spring 默认 404 的 "Not Found"）不直接给用户看', async () => {
    // run() 包装器吞错置 grassland.error 后返回 null 的路径
    const swallowed = scope.run(() => useWorkbenchTaskPreview(
      { getTaskPreview: vi.fn().mockResolvedValue(null), error: ref('Not Found') }, () => 'task-1'))!
    await flushPromises()
    expect(swallowed.error.value).toBe('合作条款暂时无法加载')

    // 抛 GrasslandHttpError（message 为原始 "Not Found"）的路径
    const thrown = scope.run(() => useWorkbenchTaskPreview(
      { getTaskPreview: vi.fn().mockRejectedValue(new Error('Not Found')), error: ref('') }, () => 'task-1'))!
    await flushPromises()
    expect(thrown.error.value).toBe('合作条款暂时无法加载')

    // 中文领域错误（后端信封 error）原样保留
    const domain = scope.run(() => useWorkbenchTaskPreview(
      { getTaskPreview: vi.fn().mockRejectedValue(new Error('任务不存在')), error: ref('') }, () => 'task-1'))!
    await flushPromises()
    expect(domain.error.value).toBe('任务不存在')
  })
})
