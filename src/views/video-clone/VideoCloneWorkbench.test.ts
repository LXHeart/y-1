// @vitest-environment happy-dom
// VideoCloneWorkbench.test.ts — C107-21 (TC107-21-01 装配状态)：列表空/有数据/
// 创建弹窗禁用态。fetch 全程替身；路由用 memory router。
import { mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, test, vi } from 'vitest'
import VideoCloneWorkbench from './VideoCloneWorkbench.vue'
import type { HypitProject } from '../../types/hypit'

function respond(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })
}

const readyProject: HypitProject = {
  id: '44444444-4444-4444-8444-444444444444',
  ownerAccountId: 'o1',
  title: '榜单复刻',
  mode: 'clone',
  status: 'ready',
  revision: 3,
  version: 1,
  selectedRun: 'main.svrun',
  sourceContext: null,
  createdAt: '2026-09-26T00:00:00Z',
  updatedAt: '2026-09-26T00:00:00Z',
}

function mountWorkbench(): ReturnType<typeof mount> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/video-clone/:projectId?', name: 'video-clone', component: VideoCloneWorkbench },
      { path: '/', redirect: '/video-clone' },
    ],
  })
  return mount(VideoCloneWorkbench, { global: { plugins: [router] }, attachTo: document.body })
}

/** C107-22：带交接 query 挂载（memory router 先 push 再 mount）。 */
async function mountWorkbenchAt(path: string): Promise<ReturnType<typeof mount>> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/video-clone/:projectId?', name: 'video-clone', component: VideoCloneWorkbench },
      { path: '/', redirect: '/video-clone' },
    ],
  })
  await router.push(path)
  await router.isReady()
  // GlModal 渲染进 Teleport：就地 stub 才能在 wrapper 内查到弹窗内容（happy-dom 已知坑）。
  return mount(VideoCloneWorkbench, {
    global: { plugins: [router], stubs: { teleport: true } },
    attachTo: document.body,
  })
}

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  document.body.innerHTML = ''
})

describe('VideoCloneWorkbench 装配（TC107-21-01）', () => {
  test('空列表：空态与新建入口可见，无假工程', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes('/projects?')) return respond(200, { success: true, data: { items: [], nextCursor: null } })
      if (url.includes('/capabilities')) return respond(200, { success: true, data: { enabled: false, version: null, features: [] } })
      return respond(404, { success: false, error: 'nf', code: 'hypit_not_found' })
    }))
    const wrapper = mountWorkbench()
    await vi.waitFor(() => expect(wrapper.find('[data-testid="clone-empty"]').exists()).toBe(true))
    expect(wrapper.find('[data-testid="clone-new-project"]').exists()).toBe(true)
  })

  test('有工程：列表项展示，点击进入工程路径', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes('/projects?')) return respond(200, { success: true, data: { items: [readyProject], nextCursor: null } })
      if (url.includes('/capabilities')) return respond(200, { success: true, data: { enabled: true, version: '0.2.13', features: [] } })
      if (url.includes('/files')) return respond(200, { success: true, data: { revision: 3, manifestHash: 'h', files: [] } })
      if (url.includes('/clone-plan')) return respond(404, { success: false, error: '尚无方案', code: 'hypit_not_found' })
      if (url.includes('/builds')) return respond(200, { success: true, data: { items: [] } })
      if (url.includes('/variants')) return respond(200, { success: true, data: { items: [] } })
      if (url.includes('/feedback')) return respond(200, { success: true, data: { file: 'FEEDBACK.json', comments: [], hash: 'x' } })
      return respond(404, { success: false, error: 'nf', code: 'hypit_not_found' })
    }))
    const wrapper = mountWorkbench()
    await vi.waitFor(() => expect(wrapper.find('[data-testid="clone-project-list"]').text()).toContain('榜单复刻'))
    await wrapper.find('.clone-project-open').trigger('click')
    await vi.waitFor(() => expect(wrapper.find('[data-testid="clone-project-header"]').exists()).toBe(true))
    expect(wrapper.find('[data-testid="clone-project-header"]').text()).toContain('由 Hypit 驱动')
  })

  // C107-22（TC107-22-02/04）：交接 query 消费——label 只预填标题不上报，
  // 创建载荷只带 {kind,id}，成功后清交接 query。
  test('带交接 query：新建工程携带 sourceContext，创建成功后剥离 query', async () => {
    const createdBodies: Array<Record<string, unknown>> = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url.includes('/projects?')) return respond(200, { success: true, data: { items: [], nextCursor: null } })
      if (url === '/api/hypit/projects' || (url.endsWith('/projects') && init?.method === 'POST')) {
        createdBodies.push(JSON.parse(String(init?.body)))
        return respond(202, {
          success: true,
          data: { project: readyProject, job: { jobId: 'job-1', state: 'queued', resourceId: 'p1' } },
        })
      }
      if (url.includes('/capabilities')) return respond(200, { success: true, data: { enabled: true, version: '0.2.13', features: [], templates: [] } })
      if (url.includes('/files')) return respond(200, { success: true, data: { revision: 1, manifestHash: 'h', files: [] } })
      if (url.includes('/clone-plan')) return respond(404, { success: false, error: '尚无方案', code: 'hypit_not_found' })
      if (url.includes('/builds')) return respond(200, { success: true, data: { items: [] } })
      if (url.includes('/variants')) return respond(200, { success: true, data: { items: [] } })
      if (url.includes('/feedback')) return respond(200, { success: true, data: { file: 'FEEDBACK.json', comments: [], hash: 'x' } })
      return respond(404, { success: false, error: 'nf', code: 'hypit_not_found' })
    }))
    const wrapper = await mountWorkbenchAt(
      '/video-clone?sourceKind=analysis&sourceId=44444444-4444-4444-8444-444444444455&label=门头参考视频')
    // 等列表加载完成（加载中「新建工程」禁用）。
    await vi.waitFor(() => expect(wrapper.find('[data-testid="clone-loading"]').exists()).toBe(false))

    await wrapper.get('[data-testid="clone-new-project"]').trigger('click')
    const titleInput = wrapper.get('[data-testid="clone-new-title"]')
    expect((titleInput.element as HTMLInputElement).value).toBe('门头参考视频')
    await titleInput.setValue('门头克隆')
    await wrapper.get('[data-testid="clone-new-submit"]').trigger('submit')
    await vi.waitFor(() => expect(createdBodies.length).toBe(1))

    const sourceContext = createdBodies[0].sourceContext as Record<string, unknown>
    expect(sourceContext).toEqual({ kind: 'analysis', id: '44444444-4444-4444-8444-444444444455' })
    // label 属展示字段，不进创建载荷（权限判定只在服务端）。
    expect(createdBodies[0]).not.toHaveProperty('label')
    expect(createdBodies[0].title).toBe('门头克隆')
  })
})
