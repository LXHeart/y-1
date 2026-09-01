// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import BgmTracksAdminPanel from './BgmTracksAdminPanel.vue'

/**
 * 任务书 #64 卡7：BGM 曲库治理台面板——列表渲染、上传 multipart 载荷、启停、删除降级提示。
 * 弹窗挂载带 teleport stub（happy-dom 铁律）。
 */

const fetchCalls: Array<{ url: string; init?: RequestInit }> = []

function jsonResponse(data: unknown, ok = true, status = 200) {
  return {
    ok,
    status,
    headers: { get: () => 'application/json' },
    json: async () => data,
    text: async () => JSON.stringify(data),
  }
}

const sampleTrack = {
  id: 'track-1',
  name: '清晨轻快',
  moodTags: '["轻快","电子"]',
  contentType: 'audio/mpeg',
  sizeBytes: 3_200_000,
  durationMs: 125_000,
  enabled: true,
}

beforeEach(() => {
  fetchCalls.length = 0
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    fetchCalls.push({ url, init })
    if (url.startsWith('/api/admin/bgm-tracks?')) {
      return jsonResponse({ success: true, data: { items: [sampleTrack], total: 1, page: 1, pageSize: 10 } })
    }
    if (url === '/api/admin/bgm-tracks' && init?.method === 'POST') {
      return jsonResponse({ success: true, data: sampleTrack })
    }
    if (url === '/api/admin/bgm-tracks/track-1' && init?.method === 'PUT') {
      return jsonResponse({ success: true, data: { ...sampleTrack, enabled: false } })
    }
    if (url === '/api/admin/bgm-tracks/track-1' && init?.method === 'DELETE') {
      return jsonResponse({ success: true, data: { deleted: false, disabled: true, referencedBy: 2 } })
    }
    if (url === '/api/admin/bgm-tracks/track-1/preview-url') {
      return jsonResponse({ success: true, data: { previewUrl: 'https://media.example.test/bgm', expiresInSeconds: 300 } })
    }
    return jsonResponse({ success: true, data: {} })
  }))
})

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

enableAutoUnmount(afterEach)

describe('BgmTracksAdminPanel', () => {
  test('挂载拉取列表并渲染曲名/情绪/启停态', async () => {
    const wrapper = mount(BgmTracksAdminPanel, { global: { stubs: { Teleport: true } }, attachTo: document.body })
    await flushPromises()

    expect(fetchCalls[0]?.url).toContain('/api/admin/bgm-tracks?page=1')
    expect(wrapper.text()).toContain('清晨轻快')
    expect(wrapper.text()).toContain('轻快 / 电子')
    expect(wrapper.text()).toContain('3.1 MB')
    expect(wrapper.text()).toContain('2:05')
    expect(wrapper.text()).toContain('启用')
  })

  test('上传：mood 至少一个校验 + multipart 载荷带 file/name/moods', async () => {
    const wrapper = mount(BgmTracksAdminPanel, { global: { stubs: { Teleport: true } }, attachTo: document.body })
    await flushPromises()

    await wrapper.get('[data-action="open-upload"]').trigger('click')
    const form = wrapper.find('#bgm-upload-form')
    expect(form.exists()).toBe(true)

    // 选择情绪 + 填曲名 + 最后挂文件（避免后续重渲染换元素丢 files）
    const moodCheckbox = wrapper.findAll('input[name="moods"]')[0]
    await moodCheckbox.setValue(true)
    await wrapper.get('input[name="name"]').setValue('测试曲')
    const fileInput = wrapper.find('input[name="file"]')
    Object.defineProperty(fileInput.element, 'files', {
      value: [new File([new Uint8Array([0x49, 0x44, 0x33])], 'a.mp3', { type: 'audio/mpeg' })],
      configurable: true,
    })

    // 提交（form submit 事件直触）
    await form.trigger('submit')
    await flushPromises()
    expect((fileInput.element as HTMLInputElement).files?.length ?? 0).toBe(1)

    const upload = fetchCalls.find((call) => call.url === '/api/admin/bgm-tracks' && call.init?.method === 'POST')
    expect(upload).toBeTruthy()
    expect(upload?.init?.body).toBeInstanceOf(FormData)
    const body = upload?.init?.body as FormData
    expect(body.get('name')).toBe('测试曲')
    expect(body.getAll('moods')).toContain('轻快')
    expect(body.get('file')).toBeInstanceOf(File)
  })

  test('启停发 PUT、删除被引用显示降级提示', async () => {
    const wrapper = mount(BgmTracksAdminPanel, { global: { stubs: { Teleport: true } }, attachTo: document.body })
    await flushPromises()

    await wrapper.get('[data-action="toggle-enabled"]').trigger('click')
    await flushPromises()
    const put = fetchCalls.find((call) => call.url === '/api/admin/bgm-tracks/track-1' && call.init?.method === 'PUT')
    expect(JSON.parse(String(put?.init?.body))).toEqual({ enabled: false })

    vi.spyOn(window, 'confirm').mockReturnValue(true)
    await wrapper.get('[data-action="delete"]').trigger('click')
    await flushPromises()
    expect(fetchCalls.some((call) => call.url === '/api/admin/bgm-tracks/track-1' && call.init?.method === 'DELETE'))
      .toBe(true)
    expect(wrapper.text()).toContain('已被成片引用，已改为停用')
  })

  test('试听打开 presign 新窗口', async () => {
    const openSpy = vi.fn()
    vi.stubGlobal('open', openSpy)
    const wrapper = mount(BgmTracksAdminPanel, { global: { stubs: { Teleport: true } }, attachTo: document.body })
    await flushPromises()

    await wrapper.get('[data-action="preview"]').trigger('click')
    await flushPromises()
    expect(openSpy).toHaveBeenCalledWith('https://media.example.test/bgm', '_blank', 'noopener')
  })
})
