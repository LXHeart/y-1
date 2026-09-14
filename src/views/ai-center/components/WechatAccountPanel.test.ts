// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import WechatAccountPanel from './WechatAccountPanel.vue'

/**
 * 任务书 #101 C101-20（AC101-20 / TC101-093~095）：公众号连接面板。
 * 保存与校验分离、secret 只在内存（关闭即清、不落持久状态）、版本冲突提示刷新、
 * 取消断开无请求、焦点归还。弹窗经 Teleport——mount 必带 teleport stub（happy-dom 铁律）。
 */

const fetchMock = vi.fn()
beforeEach(() => {
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
  fetchMock.mockResolvedValue(ok({ items: [], nextCursor: null }))
})
afterEach(() => vi.unstubAllGlobals())
enableAutoUnmount(afterEach)

function ok(data: unknown): Response {
  return new Response(JSON.stringify({ success: true, data }), { headers: { 'Content-Type': 'application/json' } })
}

function account(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    id: 'acct-1', displayName: '主号', appId: 'wxaaaa000000000001', state: 'unverified',
    version: 1, verifiedAt: null, error: null, ...overrides,
  }
}

function mountPanel() {
  // attachTo：焦点断言需要真实文档（脱离文档的元素 focus() 不会成为 activeElement）
  return mount(WechatAccountPanel, { attachTo: document.body, global: { stubs: { teleport: true } } })
}

function postedBody(index: number): Record<string, unknown> {
  return JSON.parse(String(fetchMock.mock.calls[index][1]?.body)) as Record<string, unknown>
}

describe('WechatAccountPanel', () => {
  test('空列表不阻断：明确空态文案 + 提示可继续创作导出', async () => {
    const wrapper = mountPanel()
    await flushPromises()
    expect(wrapper.get('[data-test="wechat-empty"]').text()).toContain('尚未连接公众号')
    expect(wrapper.text()).toContain('不影响本地编辑和导出文件')
  })

  test('保存与校验分离：保存只 POST 加密保存（不 verify），提示需单独校验', async () => {
    fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
      if (String(url).includes('/api/creation-channels/wechat/accounts') && init?.method === 'POST')
        return ok(account())
      return ok({ items: [], nextCursor: null })
    })
    const wrapper = mountPanel()
    await flushPromises()
    await wrapper.get('[data-test="wechat-bind-open"]').trigger('click')
    await wrapper.get('[data-test="wechat-bind-name"]').setValue('主号')
    await wrapper.get('[data-test="wechat-bind-appid"]').setValue('wxaaaa000000000001')
    await wrapper.get('[data-test="wechat-bind-secret"]').setValue('it-secret-plain-0000001')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledTimes(2) // GET list + POST bind，无 verify
    expect(postedBody(1)).toMatchObject({ displayName: '主号', appId: 'wxaaaa000000000001' })
    expect(String(postedBody(1).appSecret)).toBe('it-secret-plain-0000001')
    expect(wrapper.get('[data-test="wechat-action-note"]').text()).toContain('校验')
    // secret 输入是 password 类型（不留明文回显）
    expect(wrapper.find('[data-test="wechat-bind-secret"]').exists()).toBe(false) // 弹窗已关
  })

  test('字段契约前置拦截：非法 appId / 过短 secret 不发请求', async () => {
    const wrapper = mountPanel()
    await flushPromises()
    await wrapper.get('[data-test="wechat-bind-open"]').trigger('click')
    await wrapper.get('[data-test="wechat-bind-name"]').setValue('主号')
    await wrapper.get('[data-test="wechat-bind-appid"]').setValue('not-wx')
    await wrapper.get('[data-test="wechat-bind-secret"]').setValue('it-secret-plain-0000001')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.get('[data-test="wechat-bind-error"]').text()).toContain('wx 加 16 位十六进制')
    expect(fetchMock).toHaveBeenCalledTimes(1) // 仅初始 GET

    await wrapper.get('[data-test="wechat-bind-appid"]').setValue('wxaaaa000000000001')
    await wrapper.get('[data-test="wechat-bind-secret"]').setValue('short')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.get('[data-test="wechat-bind-error"]').text()).toContain('至少 16 位')
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  test('TC101-093：关闭弹窗清空 secret，重开为空；不写持久状态', async () => {
    const wrapper = mountPanel()
    await flushPromises()
    await wrapper.get('[data-test="wechat-bind-open"]').trigger('click')
    const secret = wrapper.get('[data-test="wechat-bind-secret"]')
    expect(secret.attributes('type')).toBe('password')
    expect(secret.attributes('autocomplete')).toBe('new-password')
    await secret.setValue('it-secret-plain-0000001')
    await wrapper.get('[data-test="wechat-bind-cancel"]').trigger('click')
    await flushPromises()
    // 重开：secret 为空
    await wrapper.get('[data-test="wechat-bind-open"]').trigger('click')
    expect((wrapper.get('[data-test="wechat-bind-secret"]').element as HTMLInputElement).value).toBe('')
    expect(window.location.search).toBe('')
    expect(Object.keys(window.localStorage).length).toBe(0)
  })

  test('TC101-095：关闭绑定弹窗焦点归还触发按钮', async () => {
    const wrapper = mountPanel()
    await flushPromises()
    const opener = wrapper.get('[data-test="wechat-bind-open"]')
    ;(opener.element as HTMLButtonElement).focus()
    await opener.trigger('click')
    expect(wrapper.find('[data-test="wechat-bind-cancel"]').exists()).toBe(true)
    await wrapper.get('[data-test="wechat-bind-cancel"]').trigger('click')
    expect(document.activeElement).toBe(opener.element)
  })

  test('校验失败显示 invalid 徽标与可读错误；成功文案不夸大权限', async () => {
    fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
      if (String(url).endsWith('/verify'))
        return ok(account({ state: 'invalid', error: { code: 'WECHAT_40125', message: '连接校验未通过，请核对凭据或重新验证' } }))
      if (String(url).includes('/api/creation-channels/wechat/accounts') && init?.method === 'POST')
        return ok(account())
      return ok({ items: [account()], nextCursor: null })
    })
    const wrapper = mountPanel()
    await flushPromises()
    await wrapper.get('[data-test="wechat-verify-acct-1"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-test="wechat-account-state-acct-1"]').text()).toBe('校验失败')
    expect(wrapper.text()).toContain('校验未通过')
    // 成功路径文案（另挂一个用例验证语义边界）
  })

  test('校验成功文案明确“不代表全部发布权限已验证”', async () => {
    fetchMock.mockImplementation(async (url: string) => {
      if (String(url).endsWith('/verify'))
        return ok(account({ state: 'active', version: 2, verifiedAt: '2026-09-14T00:00:00Z' }))
      return ok({ items: [account()], nextCursor: null })
    })
    const wrapper = mountPanel()
    await flushPromises()
    await wrapper.get('[data-test="wechat-verify-acct-1"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-test="wechat-account-state-acct-1"]').text()).toBe('已验证')
    expect(wrapper.get('[data-test="wechat-action-note"]').text()).toContain('不代表全部内容发布权限已验证')
  })

  test('版本冲突 409：提示刷新并重新拉取列表', async () => {
    fetchMock.mockImplementation(async (url: string) => {
      if (String(url).endsWith('/verify'))
        return new Response(JSON.stringify({ success: false, error: '连接版本已变化', code: 'STUDIO_VERSION_CONFLICT' }), { status: 409 })
      return ok({ items: [account({ version: 3 })], nextCursor: null })
    })
    const wrapper = mountPanel()
    await flushPromises()
    await wrapper.get('[data-test="wechat-verify-acct-1"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-test="wechat-action-error"]').text()).toContain('刷新')
    const listCalls = fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/accounts?limit=20'))
    expect(listCalls.length).toBe(2) // 冲突后重拉
    expect(wrapper.get('[data-test="wechat-account-state-acct-1"]').text()).toBe('未验证')
  })

  test('轮换：POST 新密文 → 回未验证并提示需重新校验', async () => {
    fetchMock.mockImplementation(async (url: string, _init?: RequestInit) => {
      if (String(url).endsWith('/rotate'))
        return ok(account({ version: 2 }))
      return ok({ items: [account()], nextCursor: null })
    })
    const wrapper = mountPanel()
    await flushPromises()
    await wrapper.get('[data-test="wechat-rotate-open-acct-1"]').trigger('click')
    await wrapper.get('[data-test="wechat-rotate-secret"]').setValue('test-secret-rotate-new-01')
    await wrapper.get('[data-test="wechat-rotate-submit"]').trigger('submit')
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledTimes(2) // 初始 GET + POST rotate（行内替换不重拉列表）
    expect(postedBody(1)).toMatchObject({ expectedVersion: 1, appSecret: 'test-secret-rotate-new-01' })
    expect(wrapper.get('[data-test="wechat-action-note"]').text()).toContain('重新校验')
    expect((wrapper.get('[data-test="wechat-account-state-acct-1"]').text())).toBe('未验证')
  })

  test('TC101-095：取消断开无请求；确认断开走 disconnect 且文案真实', async () => {
    fetchMock.mockImplementation(async (url: string) => {
      if (String(url).endsWith('/disconnect'))
        return ok(account({ state: 'disconnected', version: 2 }))
      return ok({ items: [account()], nextCursor: null })
    })
    const wrapper = mountPanel()
    await flushPromises()
    await wrapper.get('[data-test="wechat-disconnect-open-acct-1"]').trigger('click')
    // 弹窗内说明可读
    expect(wrapper.text()).toContain('不会被删除')
    await wrapper.get('[data-test="wechat-disconnect-cancel"]').trigger('click')
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledTimes(1) // 取消：无 disconnect 请求

    await wrapper.get('[data-test="wechat-disconnect-open-acct-1"]').trigger('click')
    await wrapper.get('[data-test="wechat-disconnect-confirm"]').trigger('click')
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(String(fetchMock.mock.calls[1][0])).toContain('/disconnect')
    expect(wrapper.get('[data-test="wechat-action-note"]').text()).toContain('已断开')
  })
})
