// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import OrganizationPrefixAdminPanel from './OrganizationPrefixAdminPanel.vue'

enableAutoUnmount(afterEach)
afterEach(() => vi.unstubAllGlobals())

/**
 * 运营台改成员账号前缀（任务书 #51 第 1 条）。这是全平台唯一的改名入口，且动作不可逆
 * （成员旧登录名立即失效），故用例重点锁：强确认必须拦住、影响面必须回显、非法前缀不放行。
 */

const ORG_ROW = {
  id: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
  name: '奶茶铺',
  accountPrefix: 'naicha',
  status: 'active',
  memberCount: 3,
}

function envelopeResponse(payload: unknown, status = 200): Response {
  const body = status >= 400 ? { success: false, error: payload } : { success: true, data: payload }
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

type Handler = (url: string, opts?: { method?: string; body?: string }) => Response | undefined

async function mounted(handler: Handler) {
  const calls: Array<{ url: string; method: string; body?: string }> = []
  const fn = vi.fn(async (input: RequestInfo | URL, opts?: { method?: string; body?: string }) => {
    const url = String(input)
    calls.push({ url, method: opts?.method ?? 'GET', body: opts?.body })
    const res = handler(url, opts)
    if (!res) throw new Error('unexpected request: ' + url)
    return res
  })
  vi.stubGlobal('fetch', fn as unknown as typeof fetch)
  const wrapper = mount(OrganizationPrefixAdminPanel)
  await flushPromises()
  return { wrapper, calls }
}

/** 搜索命中一行（任务 #3 分页信封）；改名成功回三个计数。 */
function baseHandler(url: string, opts?: { method?: string }): Response | undefined {
  const method = opts?.method ?? 'GET'
  if (method === 'GET' && url.includes('/api/admin/organizations?q=')) {
    return envelopeResponse({ items: [ORG_ROW], total: 1, limit: 50, offset: 0 })
  }
  if (method === 'PATCH' && url.includes(`/api/admin/organizations/${ORG_ROW.id}/account-prefix`)) {
    return envelopeResponse({ prefix: 'milkshop', rewrittenAccounts: 3, rewrittenPlaceholderEmails: 2 })
  }
  return undefined
}

describe('OrganizationPrefixAdminPanel · 运营改前缀（任务书 #51）', () => {
  test('搜索 → 选中 → 改名闭环：确认后 PATCH 运营端点并回显影响面', async () => {
    // 显式声明入参：零参签名下 mock.calls[0][0] 过不了类型检查（空元组无下标 0）
    const confirmSpy = vi.fn((_message?: string) => true)
    vi.stubGlobal('confirm', confirmSpy)
    const { wrapper, calls } = await mounted(baseHandler)

    // 搜索：结果行带当前前缀与成员数（= 改名影响面）
    await wrapper.find('input[aria-label="搜索商家主体（名称 / 前缀 / ID）"]').setValue('奶茶')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    const row = wrapper.find('tbody tr')
    expect(row.text()).toContain('奶茶铺')
    expect(row.text()).toContain('naicha')
    expect(row.text()).toContain('3')

    // 选中才出现改名区（危险输入不逐行放）
    expect(wrapper.find('[data-testid="prefix-rename-zone"]').exists()).toBe(false)
    await row.findAll('button').find((b) => b.text() === '改前缀')!.trigger('click')
    const zone = wrapper.find('[data-testid="prefix-rename-zone"]')
    expect(zone.exists()).toBe(true)
    expect(zone.text()).toContain('旧登录名立即失效')

    await zone.find('input[aria-label="新前缀"]').setValue('milkshop')
    await zone.findAll('button').find((b) => b.text().includes('改前缀并重写'))!.trigger('click')
    await flushPromises()

    // 强确认弹窗写明影响面（几个人、旧名失效、需线下通知）
    expect(confirmSpy).toHaveBeenCalledTimes(1)
    const warning = String(confirmSpy.mock.calls[0]![0])
    expect(warning).toContain('3 个成员的登录名')
    expect(warning).toContain('旧登录名立即失效')
    expect(warning).toContain('线下通知')

    // 打的是运营端点，body 只带 prefix
    const patch = calls.find((c) => c.method === 'PATCH')!
    expect(patch.url).toContain(`/api/admin/organizations/${ORG_ROW.id}/account-prefix`)
    expect(patch.body).toBe('{"prefix":"milkshop"}')

    // 回显实际重写行数；改名区收起
    expect(wrapper.text()).toContain('重写 3 个成员登录名')
    expect(wrapper.text()).toContain('2 个未绑邮箱的占位邮箱同步更新')
    expect(wrapper.find('[data-testid="prefix-rename-zone"]').exists()).toBe(false)
  })

  test('确认弹窗点取消：不发 PATCH（不可逆动作必须拦得住）', async () => {
    vi.stubGlobal('confirm', vi.fn(() => false))
    const { wrapper, calls } = await mounted(baseHandler)

    await wrapper.find('input[aria-label="搜索商家主体（名称 / 前缀 / ID）"]').setValue('奶茶')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    await wrapper.find('tbody tr').findAll('button').find((b) => b.text() === '改前缀')!.trigger('click')
    await wrapper.find('input[aria-label="新前缀"]').setValue('milkshop')
    await wrapper.findAll('button').find((b) => b.text().includes('改前缀并重写'))!.trigger('click')
    await flushPromises()

    expect(calls.some((c) => c.method === 'PATCH')).toBe(false)
    // 改名区仍在（用户可继续或取消）
    expect(wrapper.find('[data-testid="prefix-rename-zone"]').exists()).toBe(true)
  })

  test('非法前缀（含连字符）与同值前缀均禁用提交，不等后端 400', async () => {
    vi.stubGlobal('confirm', vi.fn(() => true))
    const { wrapper, calls } = await mounted(baseHandler)

    await wrapper.find('input[aria-label="搜索商家主体（名称 / 前缀 / ID）"]').setValue('奶茶')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    await wrapper.find('tbody tr').findAll('button').find((b) => b.text() === '改前缀')!.trigger('click')

    const submit = wrapper.findAll('button').find((b) => b.text().includes('改前缀并重写'))!
    const input = wrapper.find('input[aria-label="新前缀"]')

    // 连字符是禁例（账号名靠它分段）
    await input.setValue('grass-milk')
    expect((submit.element as HTMLButtonElement).disabled).toBe(true)
    expect(wrapper.text()).toContain('不含连字符')

    // 过短
    await input.setValue('ab')
    expect((submit.element as HTMLButtonElement).disabled).toBe(true)

    // 与当前值相同
    await input.setValue('naicha')
    expect((submit.element as HTMLButtonElement).disabled).toBe(true)
    expect(wrapper.text()).toContain('新前缀与当前前缀相同')

    // 合法值放行
    await input.setValue('milkshop')
    expect((submit.element as HTMLButtonElement).disabled).toBe(false)
    expect(calls.some((c) => c.method === 'PATCH')).toBe(false)
  })

  test('后端 409（前缀被占）呈现错误且不误报成功', async () => {
    vi.stubGlobal('confirm', vi.fn(() => true))
    const { wrapper } = await mounted((url, opts) => {
      const method = opts?.method ?? 'GET'
      if (method === 'PATCH') return envelopeResponse('该前缀已被其他主体使用', 409)
      return baseHandler(url, opts)
    })

    await wrapper.find('input[aria-label="搜索商家主体（名称 / 前缀 / ID）"]').setValue('奶茶')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    await wrapper.find('tbody tr').findAll('button').find((b) => b.text() === '改前缀')!.trigger('click')
    await wrapper.find('input[aria-label="新前缀"]').setValue('taken')
    await wrapper.findAll('button').find((b) => b.text().includes('改前缀并重写'))!.trigger('click')
    await flushPromises()

    expect(wrapper.find('[role="alert"]').text()).toContain('该前缀已被其他主体使用')
    expect(wrapper.find('[role="status"]').exists()).toBe(false)
  })

  test('翻页：点下一页后搜索请求带 offset=10（任务 #3 分页契约）', async () => {
    vi.stubGlobal('confirm', vi.fn(() => true))
    const { wrapper, calls } = await mounted((url, opts) => {
      const method = opts?.method ?? 'GET'
      if (method === 'GET' && url.includes('/api/admin/organizations?q=')) {
        return envelopeResponse({ items: [ORG_ROW], total: 60, limit: 50, offset: 0 })
      }
      return undefined
    })

    await wrapper.find('input[aria-label="搜索商家主体（名称 / 前缀 / ID）"]').setValue('奶茶')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(calls.some((c) => c.url.includes('offset=0'))).toBe(true)

    await wrapper.findAll('button').find((b) => b.text() === '下一页')!.trigger('click')
    await flushPromises()
    expect(calls.some((c) => c.url.includes('offset=10'))).toBe(true)
  })
})
