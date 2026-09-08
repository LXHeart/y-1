import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { effectScope, ref, type EffectScope } from 'vue'
import { useWorkbenchTaskDrafts } from './useWorkbenchTaskDrafts'
import type { useGrassland } from '../../../composables/useGrassland'
import type { Task } from '../../../types/grassland'

describe('任务合作条款表单', () => {
  let scope: EffectScope
  beforeEach(() => { setActivePinia(createPinia()); scope = effectScope() })
  afterEach(() => scope.stop())

  function setup() {
    const saved = { id: 'task-1', title: '合作', version: 2, status: 'draft' }
    const api = { createTask: vi.fn().mockResolvedValue(saved), createDraft: vi.fn().mockResolvedValue(saved),
      updateTask: vi.fn().mockResolvedValue(saved), reviseTask: vi.fn().mockResolvedValue(saved) }
    const notice = vi.fn()
    const form = scope.run(() => useWorkbenchTaskDrafts(api as unknown as ReturnType<typeof useGrassland>, notice, {
      activeOrgId: ref('org-1'), selectedStoreId: ref('store-1'), refreshTasks: vi.fn().mockResolvedValue(undefined),
    }))!
    Object.assign(form.taskForm.value, { title: '合作', platform: 'xiaohongshu', applicationDeadline: '2099-01-01T12:00', bountyYuan: '100' })
    return { ...form, api, notice }
  }

  it.each(['createTask', 'createDraft', 'updateTask', 'reviseTask'] as const)('%s 保留未填写阶段的服务端默认比例', async (action) => {
    const state = setup()
    if (action === 'updateTask') state.editingDraft.value = { id: 'task-1', version: 1 }
    if (action === 'reviseTask') state.revisingTask.value = { id: 'task-1', version: 1 }
    Object.assign(state.taskForm.value, { reviewRequired: true, deliveryDeadlineDays: '5', cancelScriptPct: '12.5' })
    await (action === 'createTask' ? state.publishTask() : state.saveDraft())
    const args = state.api[action].mock.calls[0]!
    expect(args[args.length - 1]).toMatchObject({ reviewRequired: true, deliveryDeadlineDays: 5, cancelPolicy: { script: 1250 } })
    expect(args[args.length - 1].cancelPolicy).not.toHaveProperty('deliverable')
    expect(args[args.length - 1].cancelPolicy).not.toHaveProperty('published')
  })

  it('回填后可关闭审稿，并清空期限和取消条款', async () => {
    const state = setup()
    state.editDraft({ id: 'task-1', version: 1, title: '合作', platform: 'xiaohongshu', storeId: 'store-1',
      applicationDeadline: '2099-01-01T12:00:00Z', reviewRequired: true, deliveryDeadlineDays: 5,
      cancelPolicy: { script: 1250 }, minRecommenderLevel: 1, maxSlots: 1 } as Task)
    expect(state.taskForm.value.cancelScriptPct).toBe('12.5')
    expect(state.taskForm.value.reviewRequired).toBe(true)
    state.handleTaskFormUpdate('reviewRequired', false)
    Object.assign(state.taskForm.value, { deliveryDeadlineDays: '', cancelScriptPct: '' })
    await state.saveDraft()
    const payload = state.api.updateTask.mock.calls[0]![1]
    expect(payload.reviewRequired).toBe(false)
    expect(payload.deliveryDeadlineDays).toBeUndefined()
    expect(payload.cancelPolicy).toBeUndefined()
  })

  it.each([
    { deliveryDeadlineDays: '1.5' }, { deliveryDeadlineDays: '-1' },
    { cancelScriptPct: '101' }, { cancelDeliverablePct: '-0.01' }, { cancelPublishedPct: 'NaN' },
  ])('拒绝非法条款 %j，保留表单且不发请求', async (invalid) => {
    const state = setup()
    Object.assign(state.taskForm.value, invalid)
    expect(await state.saveDraft()).toBeNull()
    expect(state.api.createDraft).not.toHaveBeenCalled()
    expect(state.notice).toHaveBeenCalled()
    expect(state.taskForm.value).toMatchObject(invalid)
  })
})
