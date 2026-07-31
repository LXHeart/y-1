// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import EngagementSubmissionPanel from './EngagementSubmissionPanel.vue'

/**
 * 履约交付物面板。两个视角共用一份数据，重点锁：
 * - 列表端点返回 `{submissions: [...]}` 而非裸数组（与其它列表端点不一致，最容易接错）；
 * - 已有待核验的一份时不能再提交（后端 409，前端先禁用）；
 * - 商家侧才有「退回补交」，推荐官侧才有提交表单。
 */

const SUBMITTED = {
  id: 's1', applicationId: 'app-1', recommenderAccountId: 'rec-1',
  contentUrl: 'https://example.com/post/1', note: '已按要求发布',
  status: 'submitted', reviewNote: null, reviewedAt: null, createdAt: '2026-07-27T10:00:00Z',
}

const REJECTED = {
  ...SUBMITTED, id: 's0', status: 'rejected', reviewNote: '缺少门店实拍',
  reviewedAt: '2026-07-27T09:00:00Z', createdAt: '2026-07-27T08:00:00Z',
}

function stubFetch(listByCall: unknown[][]): { calls: { url: string; method: string; body?: string }[] } {
  const calls: { url: string; method: string; body?: string }[] = []
  let listIndex = 0
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method || 'GET'
    calls.push({ url, method, body: init?.body as string | undefined })
    const isList = method === 'GET'
    const data = isList
      ? { submissions: listByCall[Math.min(listIndex++, listByCall.length - 1)] }
      : SUBMITTED
    return {
      ok: true,
      headers: { get: () => 'application/json' },
      json: async () => ({ success: true, data }),
    }
  }))
  return { calls }
}

enableAutoUnmount(afterEach)
afterEach(() => vi.unstubAllGlobals())

function mountPanel(role: 'merchant' | 'recommender') {
  return mount(EngagementSubmissionPanel, {
    props: { taskId: 'task-1', applicationId: 'app-1', role },
  })
}

/**
 * 按 placeholder 取输入框而非下标。
 *
 * 附件功能（Slice 11 S4）在链接与说明之间插了个 `input[type=file]`，
 * 原先的 `findAll('input')[1]` 会取到它 → happy-dom 抛
 * 「Input elements of type "file" may only programmatically set the value to empty string」。
 * 下标选择器对异构输入列表本来就脆，这里改成按意图取。
 */
const urlInput = (w: ReturnType<typeof mountPanel>) => w.find('input[placeholder^="发布链接"]')
const noteInput = (w: ReturnType<typeof mountPanel>) => w.find('input[placeholder^="补充说明"]')

describe('EngagementSubmissionPanel 列表', () => {
  /** 响应是 {submissions:[...]}；直接当数组用会渲染成空列表。 */
  test('从 submissions 字段取数组并展示链接与状态', async () => {
    const { calls } = stubFetch([[SUBMITTED]])
    const wrapper = mountPanel('recommender')
    await flushPromises()

    expect(calls[0]).toMatchObject({ url: '/api/tasks/task-1/applications/app-1/submissions', method: 'GET' })
    expect(wrapper.text()).toContain('https://example.com/post/1')
    expect(wrapper.text()).toContain('待商家核验')
    expect(wrapper.text()).not.toContain('尚未提交履约凭证')
  })

  test('退回的交付物展示退回原因', async () => {
    stubFetch([[REJECTED]])
    const wrapper = mountPanel('recommender')
    await flushPromises()

    expect(wrapper.text()).toContain('已退回')
    expect(wrapper.text()).toContain('退回原因：缺少门店实拍')
  })

  test('商家侧空列表明确说明「在此之前无法确认履约」', async () => {
    stubFetch([[]])
    const wrapper = mountPanel('merchant')
    await flushPromises()

    expect(wrapper.text()).toContain('无法确认履约')
  })
})

describe('EngagementSubmissionPanel 提交', () => {
  test('推荐官提交发送 contentUrl + note 并重拉列表', async () => {
    const { calls } = stubFetch([[], [SUBMITTED]])
    const wrapper = mountPanel('recommender')
    await flushPromises()

    await urlInput(wrapper).setValue('https://example.com/post/1')
    await noteInput(wrapper).setValue('已按要求发布')
    await wrapper.findAll('button').find((b) => b.text() === '提交履约')!.trigger('click')
    await flushPromises()

    const post = calls.find((c) => c.method === 'POST')!
    expect(post.url).toBe('/api/tasks/task-1/applications/app-1/submissions')
    expect(JSON.parse(post.body!)).toEqual({ contentUrl: 'https://example.com/post/1', note: '已按要求发布' })
    expect(wrapper.text()).toContain('已提交，等待商家核验')
  })

  /** 已有待核验的一份 → 后端 409；前端先把按钮禁掉并说明原因。 */
  test('已有待核验时提交按钮禁用', async () => {
    stubFetch([[SUBMITTED]])
    const wrapper = mountPanel('recommender')
    await flushPromises()

    await urlInput(wrapper).setValue('https://example.com/another')
    const button = wrapper.findAll('button').find((b) => b.text() === '提交履约')!

    expect(button.attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('已有一份待商家核验')
  })

  test('推荐官侧没有「退回补交」', async () => {
    stubFetch([[SUBMITTED]])
    const wrapper = mountPanel('recommender')
    await flushPromises()

    expect(wrapper.findAll('button').some((b) => b.text() === '退回补交')).toBe(false)
  })
})

describe('EngagementSubmissionPanel 退回', () => {
  test('商家退回发送原因并重拉列表', async () => {
    const { calls } = stubFetch([[SUBMITTED], [REJECTED]])
    const wrapper = mountPanel('merchant')
    await flushPromises()

    await wrapper.find('input[placeholder^="退回原因"]').setValue('缺少门店实拍')
    await wrapper.findAll('button').find((b) => b.text() === '退回补交')!.trigger('click')
    await flushPromises()

    // 商家打开面板会懒触发核验 POST，故按 URL 取退回那条，而非「第一个 POST」。
    const post = calls.find((c) => c.method === 'POST'
      && c.url === '/api/tasks/task-1/applications/app-1/submissions/s1/reject')!
    expect(JSON.parse(post.body!)).toEqual({ note: '缺少门店实拍' })
    expect(wrapper.text()).toContain('已退回，推荐官可修改后重新提交')
  })

  test('商家侧没有提交表单', async () => {
    stubFetch([[SUBMITTED]])
    const wrapper = mountPanel('merchant')
    await flushPromises()

    expect(wrapper.findAll('button').some((b) => b.text() === '提交履约')).toBe(false)
  })
})

describe('EngagementSubmissionPanel 附件（Slice 11 S4）', () => {
  const WITH_ATTS = {
    ...SUBMITTED,
    attachments: [
      { mediaId: 'm-1', mimeType: 'image/png', sizeBytes: 2048 },
      { mediaId: 'm-2', mimeType: 'video/mp4', sizeBytes: 5 * 1024 * 1024 },
    ],
  }

  test('商家侧展示附件类型与大小', async () => {
    stubFetch([[WITH_ATTS]])
    const wrapper = mountPanel('merchant')
    await flushPromises()

    expect(wrapper.text()).toContain('图片附件')
    expect(wrapper.text()).toContain('2 KB')
    expect(wrapper.text()).toContain('视频附件')
    expect(wrapper.text()).toContain('5.0 MB')
  })

  /** 签名 URL 只有 5 分钟有效期，不能提前渲染进 href 等用户点——必须点击时才换。 */
  test('点下载走 marketplace 中转路径并在新标签打开签名 URL', async () => {
    const calls: { url: string; method: string }[] = []
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      const method = init?.method || 'GET'
      calls.push({ url, method })
      const data = url.includes('/download-url')
        ? { downloadUrl: 'https://minio.test/signed?sig=x', expiresAt: null }
        : { submissions: [WITH_ATTS] }
      return { ok: true, headers: { get: () => 'application/json' }, json: async () => ({ success: true, data }) }
    }))
    const open = vi.fn()
    vi.stubGlobal('open', open)
    const wrapper = mountPanel('merchant')
    await flushPromises()

    await wrapper.findAll('button').find((b) => b.text() === '下载')!.trigger('click')
    await flushPromises()

    expect(calls.some((c) => c.url
      === '/api/tasks/task-1/applications/app-1/submissions/s1/attachments/m-1/download-url')).toBe(true)
    expect(open).toHaveBeenCalledWith('https://minio.test/signed?sig=x', '_blank', 'noopener,noreferrer')
    // href 不能提前挂签名 URL
    expect(wrapper.html()).not.toContain('minio.test/signed')
  })

  test('无附件的交付物不渲染附件区（旧数据回归）', async () => {
    stubFetch([[SUBMITTED]])
    const wrapper = mountPanel('merchant')
    await flushPromises()

    expect(wrapper.find('.sub-atts').exists()).toBe(false)
    expect(wrapper.findAll('button').some((b) => b.text() === '下载')).toBe(false)
  })

  test('选中文件走三步上传，提交时带上 confirm 后的 mediaId', async () => {
    const calls: { url: string; method: string; body?: unknown }[] = []
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      const method = init?.method || 'GET'
      calls.push({ url, method, body: init?.body })
      if (url === 'http://localhost:9002/b/tmp') return { ok: true, status: 200 }
      let data: unknown = { submissions: [] }
      if (url === '/api/media/upload-tickets') {
        data = {
          id: 'm-new', objectKey: 'k', uploadUrl: 'http://localhost:9002/b/tmp',
          method: 'PUT', headers: { 'Content-Type': 'image/png' }, expiresAt: null,
        }
      } else if (url.includes('/confirm')) {
        data = { id: 'm-new', status: 'active' }
      } else if (method === 'POST') {
        data = SUBMITTED
      }
      return { ok: true, headers: { get: () => 'application/json' }, json: async () => ({ success: true, data }) }
    }))
    const wrapper = mountPanel('recommender')
    await flushPromises()

    const file = new File([new Uint8Array(4)], 'shot.png', { type: 'image/png' })
    const fileInput = wrapper.find('input[type="file"]')
    Object.defineProperty(fileInput.element, 'files', { value: [file], configurable: true })
    await fileInput.trigger('change')
    await flushPromises()

    // 暂存项按真实文件名展示
    expect(wrapper.text()).toContain('shot.png')
    expect(calls.map((c) => c.url)).toEqual(expect.arrayContaining([
      '/api/media/upload-tickets', 'http://localhost:9002/b/tmp', '/api/media/m-new/confirm',
    ]))

    await urlInput(wrapper).setValue('https://example.com/post/1')
    await wrapper.findAll('button').find((b) => b.text() === '提交履约')!.trigger('click')
    await flushPromises()

    const submitCall = calls.find((c) => c.url === '/api/tasks/task-1/applications/app-1/submissions'
      && c.method === 'POST')!
    expect(JSON.parse(submitCall.body as string)).toEqual({
      contentUrl: 'https://example.com/post/1', mediaIds: ['m-new'],
    })
  })

  test('超过 20MB 的文件本地就挡掉，不发上传请求', async () => {
    const { calls } = stubFetch([[]])
    const wrapper = mountPanel('recommender')
    await flushPromises()

    const big = new File(['x'], 'huge.mp4', { type: 'video/mp4' })
    Object.defineProperty(big, 'size', { value: 21 * 1024 * 1024 })
    const fileInput = wrapper.find('input[type="file"]')
    Object.defineProperty(fileInput.element, 'files', { value: [big], configurable: true })
    await fileInput.trigger('change')
    await flushPromises()

    expect(wrapper.text()).toContain('超过')
    expect(calls.some((c) => c.url === '/api/media/upload-tickets')).toBe(false)
  })

  test('商家侧没有附件上传入口', async () => {
    stubFetch([[SUBMITTED]])
    const wrapper = mountPanel('merchant')
    await flushPromises()

    expect(wrapper.find('input[type="file"]').exists()).toBe(false)
  })
})

describe('EngagementSubmissionPanel 履约核验（Verification v1）', () => {
  const VERIFY_PASSED = {
    submissionId: 's1', status: 'passed',
    checks: [{ type: 'link_reachability', status: 'passed', detail: 'HTTP 200', checkedAt: '2026-07-31T00:00:00Z' }],
    lastCheckedAt: '2026-07-31T00:00:00Z',
  }

  /** 路由：GET→交付物列表；POST /verification/checks→核验结果。 */
  function stubFetchVerify(opts: { list?: unknown[]; verify?: unknown }) {
    const calls: { url: string; method: string }[] = []
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      const method = init?.method || 'GET'
      calls.push({ url, method })
      const data = url.includes('/verification/checks')
        ? (opts.verify ?? VERIFY_PASSED)
        : { submissions: opts.list ?? [{ ...SUBMITTED }] }
      return { ok: true, headers: { get: () => 'application/json' }, json: async () => ({ success: true, data }) }
    }))
    return { calls }
  }

  /** 商家打开面板 → 未核验的待核验交付物懒触发自动核验（GET 保持纯读，核验是付费 POST）。 */
  test('商家打开面板懒触发未核验交付物的自动核验', async () => {
    const { calls } = stubFetchVerify({ list: [{ ...SUBMITTED }] })  // 无 verification → 触发
    const wrapper = mountPanel('merchant')
    await flushPromises()
    await flushPromises()

    expect(calls.some((c) => c.method === 'POST'
      && c.url === '/api/tasks/task-1/applications/app-1/submissions/s1/verification/checks')).toBe(true)
    expect(wrapper.text()).toContain('核验通过')
  })

  /** 已有核验记录的不重跑——避免商家每次打开面板都触发付费 AI 核验。 */
  test('已核验的交付物不重复懒触发', async () => {
    const { calls } = stubFetchVerify({ list: [{ ...SUBMITTED, verification: VERIFY_PASSED }] })
    mountPanel('merchant')
    await flushPromises()

    expect(calls.some((c) => c.url.includes('/verification/checks'))).toBe(false)
  })

  test('tri-state 徽章：failed → 核验未过', async () => {
    const failed = {
      ...VERIFY_PASSED, status: 'failed',
      checks: [{ type: 'link_reachability', status: 'failed', detail: 'HTTP 404', checkedAt: '2026-07-31T00:00:00Z' }],
    }
    stubFetchVerify({ list: [{ ...SUBMITTED, verification: failed }] })
    const wrapper = mountPanel('merchant')
    await flushPromises()

    expect(wrapper.text()).toContain('核验未过')
  })

  test('tri-state 徽章：inconclusive → 核验存疑', async () => {
    const inconclusive = {
      ...VERIFY_PASSED, status: 'inconclusive',
      checks: [{ type: 'ai_visual', status: 'inconclusive', detail: 'AI 视觉核验暂不可用', checkedAt: '2026-07-31T00:00:00Z' }],
    }
    stubFetchVerify({ list: [{ ...SUBMITTED, verification: inconclusive }] })
    const wrapper = mountPanel('merchant')
    await flushPromises()

    expect(wrapper.text()).toContain('核验存疑')
  })

  /** 手动「重新核验」强制重跑（即便已有结论），就地刷新徽章。 */
  test('商家点「重新核验」重跑并刷新徽章', async () => {
    const { calls } = stubFetchVerify({
      list: [{ ...SUBMITTED, verification: VERIFY_PASSED }],  // 已核验 → 不懒触发
      verify: {
        ...VERIFY_PASSED, status: 'failed',
        checks: [{ type: 'link_reachability', status: 'failed', detail: 'HTTP 404', checkedAt: '2026-07-31T00:00:00Z' }],
      },
    })
    const wrapper = mountPanel('merchant')
    await flushPromises()

    await wrapper.findAll('button').find((b) => b.text() === '重新核验')!.trigger('click')
    await flushPromises()

    const recheckPosts = calls.filter((c) => c.url.includes('/verification/checks') && c.method === 'POST')
    expect(recheckPosts.length).toBeGreaterThanOrEqual(1)
    expect(wrapper.text()).toContain('核验未过')
  })

  /** 推荐官侧既不懒触发核验（付费），也没有「重新核验」按钮（商家动作）。 */
  test('推荐官侧不懒触发核验、也无重新核验按钮', async () => {
    const { calls } = stubFetchVerify({ list: [{ ...SUBMITTED }] })
    const wrapper = mountPanel('recommender')
    await flushPromises()

    expect(calls.some((c) => c.url.includes('/verification/checks'))).toBe(false)
    expect(wrapper.findAll('button').some((b) => b.text() === '重新核验')).toBe(false)
  })
})
