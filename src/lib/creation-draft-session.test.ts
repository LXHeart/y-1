// @vitest-environment happy-dom
import { afterEach, expect, test, vi } from 'vitest'
import { defineComponent } from 'vue'
import { mount } from '@vue/test-utils'
import { createPinia, disposePinia } from 'pinia'
import { useCreationDraftSessions } from './creation-draft-session'

afterEach(() => vi.unstubAllGlobals())

test('异步工作流和助手打开同一草稿时共享串行队列与版本', async () => {
  const pinia = createPinia()
  const wrappers: ReturnType<typeof mount>[] = []
  const handles: ReturnType<typeof useCreationDraftSessions>[] = []
  const Host = defineComponent({ setup() { handles.push(useCreationDraftSessions()); return {} }, template: '<div />' })
  wrappers.push(mount(Host, { global: { plugins: [pinia] } }), mount(Host, { global: { plugins: [pinia] } }))
  await Promise.resolve()
  const workflow = handles[0]('shared')
  const assistant = handles[1]('shared')
  expect(workflow).toBe(assistant)
  workflow.adopt({ id: 'shared', sourceType: 'independent', title: '文章', status: 'draft',
    version: 1, content: '正文', createdAt: '', updatedAt: '', workspace: { schemaVersion: 1 } })
  const versions: number[] = []
  vi.stubGlobal('fetch', vi.fn(async (_url: string, init?: RequestInit) => {
    const body = JSON.parse(String(init?.body))
    versions.push(body.expectedVersion)
    return new Response(JSON.stringify({ success: true, data: { ...workflow.draft.value, ...body, version: body.expectedVersion + 1 } }))
  }))
  workflow.queueSave({ outline: '主流程大纲' })
  assistant.queueSave({ content: '助手修订正文' })
  await Promise.all([workflow.flush(), assistant.flush()])
  expect(versions).toEqual([1])
  expect(workflow.draft.value).toMatchObject({ outline: '主流程大纲', content: '助手修订正文', version: 2 })
  wrappers.forEach(wrapper => wrapper.unmount())
  disposePinia(pinia)
})
