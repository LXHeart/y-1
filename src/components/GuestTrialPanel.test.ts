// @vitest-environment happy-dom
import { mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import GuestTrialPanel from './GuestTrialPanel.vue'

/** 任务书 #36 B3：游客体验面板——条件渲染、SSE 帧消费、额度 UI 状态机、登录引导。 */

const fetchMock = vi.fn()

function sseResponse(frames: string[], status = 200): Response {
  const encoder = new TextEncoder()
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const frame of frames) controller.enqueue(encoder.encode(frame))
      controller.close()
    },
  })
  return new Response(stream, { status, headers: { 'Content-Type': 'text/event-stream' } })
}

function runButton(wrapper: ReturnType<typeof mount>) {
  const button = wrapper.findAll('button').find((b) => b.text().includes('免费生成'))
  if (!button) throw new Error('免费生成按钮未渲染')
  return button
}

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock)
  fetchMock.mockReset()
})

afterEach(() => {
  vi.unstubAllGlobals()
})

function stubQuota(remaining = 3): void {
  fetchMock.mockImplementationOnce(async () => new Response(JSON.stringify({
    success: true,
    data: {
      capabilities: {
        'article-titles': { used: 3 - remaining, limit: 3, remaining },
        'content-score': { used: 0, limit: 3, remaining: 3 },
        'image-review': { used: 0, limit: 3, remaining: 3 },
      },
      signupBonusCredits: 50,
    },
  }), { status: 200 }))
}

describe('GuestTrialPanel（任务书 #36）', () => {
  test('渲染三个能力页签与额度徽标', async () => {
    stubQuota(2)
    const wrapper = mount(GuestTrialPanel)
    await vi.waitFor(() => expect(wrapper.text()).toContain('今日剩余 2/3 次'))
    expect(wrapper.text()).toContain('起标题')
    expect(wrapper.text()).toContain('文案评分')
    expect(wrapper.text()).toContain('探店点评')
  })

  test('SSE result 帧聚合渲染标题列表，并刷新额度', async () => {
    stubQuota(3)
    fetchMock.mockImplementationOnce(async () => sseResponse([
      'data: {"progress":"正在生成…"}\n\n',
      'data: {"result":{"titles":[{"title":"巷子里的宝藏咖啡","hook":"本地人才知道"}]}}\n\n',
      'data: [DONE]\n\n',
    ]))
    const wrapper = mount(GuestTrialPanel)
    await vi.waitFor(() => expect(wrapper.text()).toContain('今日剩余 3/3 次'))
    await wrapper.find('input').setValue('citywalk 咖啡店')
    await runButton(wrapper).trigger('click')
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('巷子里的宝藏咖啡')
      expect(wrapper.text()).toContain('本地人才知道')
    })
  })

  test('额度为 0 按钮禁用；quota_exhausted 帧触发登录引导（文案含注册赠送积分）', async () => {
    // 额度为 0：免费生成按钮禁用（前端软闸，硬闸在后端）
    stubQuota(0)
    const exhausted = mount(GuestTrialPanel)
    await vi.waitFor(() => expect(exhausted.text()).toContain('今日剩余 0/3 次'))
    expect(runButton(exhausted).attributes('disabled')).toBeDefined()

    // 有额度但后端判用尽（并发场景）→ error 帧驱动登录引导
    stubQuota(1)
    fetchMock.mockImplementationOnce(async () => sseResponse([
      'data: {"error":"今日免费体验次数已用完","code":"quota_exhausted"}\n\n',
      'data: [DONE]\n\n',
    ]))
    const wrapper = mount(GuestTrialPanel)
    await vi.waitFor(() => expect(wrapper.text()).toContain('今日剩余 1/3 次'))
    await wrapper.find('input').setValue('探店主题')
    await runButton(wrapper).trigger('click')
    await vi.waitFor(() => expect(wrapper.find('.trial-login').exists()).toBe(true))
    expect(wrapper.text()).toContain('注册即送 50 积分')
    await wrapper.find('.trial-login button').trigger('click')
    expect(wrapper.emitted('request-login')?.[0]).toEqual([])
  })

  test('provider_error 帧显示错误提示，不弹登录引导', async () => {
    stubQuota(3)
    fetchMock.mockImplementationOnce(async () => sseResponse([
      'data: {"error":"生成失败，请稍后再试（本次不消耗次数）","code":"provider_error"}\n\n',
      'data: [DONE]\n\n',
    ]))
    const wrapper = mount(GuestTrialPanel)
    await vi.waitFor(() => expect(wrapper.text()).toContain('今日剩余 3/3 次'))
    await wrapper.find('input').setValue('探店主题')
    await runButton(wrapper).trigger('click')
    await vi.waitFor(() => expect(wrapper.text()).toContain('生成失败'))
    expect(wrapper.find('.trial-login').exists()).toBe(false)
  })

  test('HTTP 429（IP 限流在 SSE 前判）按错误提示呈现', async () => {
    stubQuota(3)
    fetchMock.mockImplementationOnce(async () => new Response(null, { status: 429 }))
    const wrapper = mount(GuestTrialPanel)
    await vi.waitFor(() => expect(wrapper.text()).toContain('今日剩余 3/3 次'))
    await wrapper.find('input').setValue('探店')
    await runButton(wrapper).trigger('click')
    await vi.waitFor(() => expect(wrapper.text()).toContain('尝试过于频繁'))
  })

  test('切页签切换表单与提示文案', async () => {
    stubQuota(3)
    const wrapper = mount(GuestTrialPanel)
    await vi.waitFor(() => expect(wrapper.text()).toContain('今日剩余 3/3 次'))
    expect(wrapper.text()).toContain('生成 5 个种草标题')
    await wrapper.findAll('[role="tab"]')[1].trigger('click')
    expect(wrapper.text()).toContain('5 维评分')
    expect(wrapper.find('textarea').exists()).toBe(true)
  })
})
