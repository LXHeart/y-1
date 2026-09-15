// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import PersonalDataComplianceCard from './PersonalDataComplianceCard.vue'

type Call = { url: string; method: string }

function response(data: unknown) {
  return {
    ok: true,
    status: 200,
    headers: { get: () => 'application/json' },
    json: async () => ({ success: true, data }),
  }
}

function stubFetch(handler: (url: string, method: string) => unknown) {
  const calls: Call[] = []
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method || 'GET'
    calls.push({ url, method })
    return response(handler(url, method))
  }))
  return calls
}

enableAutoUnmount(afterEach)

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('PersonalDataComplianceCard', () => {
  test('生成完成后展示 ZIP 大小和短期下载链接', async () => {
    const calls = stubFetch((url) => {
      if (url === '/api/me/compliance/exports') {
        return {
          id: 'export-1', status: 'completed', format: 'zip',
          createdAt: '2026-08-19T08:00:00Z', completedAt: '2026-08-19T08:01:00Z',
          expiresAt: '2026-08-20T08:01:00Z', sizeBytes: 2048, sha256: 'abc',
          downloadUrl: '/api/me/compliance/exports/export-1/download?token=signed',
        }
      }
      if (url.startsWith('/api/me/compliance/audit')) return { entries: [] }
      throw new Error(`unexpected request: ${url}`)
    })
    const wrapper = mount(PersonalDataComplianceCard)

    await wrapper.findAll('button').find((button) => button.text().includes('生成 ZIP'))!.trigger('click')
    await flushPromises()

    expect(calls).toContainEqual({ url: '/api/me/compliance/exports', method: 'POST' })
    expect(wrapper.text()).toContain('completed')
    expect(wrapper.text()).toContain('2.0 KB')
    expect(wrapper.get('a').attributes('href'))
      .toBe('/api/me/compliance/exports/export-1/download?token=signed')
  })

  test('注销检查展示跨域阻塞项和待提现余额', async () => {
    stubFetch((url) => {
      if (url === '/api/me/compliance/closure-check') {
        return {
          eligible: false,
          blockers: [{
            domain: 'finance', code: 'WALLET_BALANCE', message: '钱包仍有可提现余额',
            count: 1, amountCents: 12345,
          }],
          domains: { identity: true, marketplace: true, finance: true, trust: true, intelligence: true },
        }
      }
      throw new Error(`unexpected request: ${url}`)
    })
    const wrapper = mount(PersonalDataComplianceCard)

    await wrapper.findAll('button').find((button) => button.text().includes('检查条件'))!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('钱包仍有可提现余额')
    expect(wrapper.text()).toContain('¥123.45')
    expect(wrapper.text()).not.toContain('确认注销账号')
  })

  test('注销请求停在 preparing 时提示核对中且不宣称已注销', async () => {
    stubFetch((url, method) => {
      if (url === '/api/me/compliance/closure-check') {
        return { eligible: true, blockers: [], domains: {} }
      }
      if (url === '/api/me/compliance/account-closure' && method === 'POST') {
        return {
          id: 'closure-1', status: 'preparing', blockers: [], retentionUntil: null,
          requestedAt: '2026-09-15T08:00:00Z', completedAt: null, errorCode: null,
        }
      }
      throw new Error(`unexpected request: ${url}`)
    })
    const wrapper = mount(PersonalDataComplianceCard)

    await wrapper.findAll('button').find((button) => button.text().includes('检查条件'))!.trigger('click')
    await flushPromises()
    await wrapper.findAll('button').find((button) => button.text().includes('申请注销'))!.trigger('click')
    await flushPromises()
    await wrapper.findAll('button').find((button) => button.text().includes('确认注销账号'))!.trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="closure-status"]').text())
      .toContain('正在核对并停止新任务，尚未注销账号')
    expect(wrapper.text()).toContain('请稍后重新检查注销条件')
    expect(wrapper.text()).not.toContain('账号已进入注销保留期')
  })

  test('注销被 blocked 时展示阻塞原因且不进入保留期', async () => {
    stubFetch((url, method) => {
      if (url === '/api/me/compliance/closure-check') {
        return { eligible: true, blockers: [], domains: {} }
      }
      if (url === '/api/me/compliance/account-closure' && method === 'POST') {
        return {
          id: 'closure-2', status: 'blocked',
          blockers: [{
            domain: 'intelligence', code: 'RUNNING_AI_JOB',
            message: '仍有执行中或待补偿的 AI任务（ai_run）', count: 1, amountCents: null,
          }],
          retentionUntil: null, requestedAt: '2026-09-15T08:00:00Z', completedAt: null, errorCode: null,
        }
      }
      throw new Error(`unexpected request: ${url}`)
    })
    const wrapper = mount(PersonalDataComplianceCard)

    await wrapper.findAll('button').find((button) => button.text().includes('检查条件'))!.trigger('click')
    await flushPromises()
    await wrapper.findAll('button').find((button) => button.text().includes('申请注销'))!.trigger('click')
    await flushPromises()
    await wrapper.findAll('button').find((button) => button.text().includes('确认注销账号'))!.trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="closure-status"]').text()).toContain('注销检查未通过')
    expect(wrapper.text()).toContain('仍有执行中或待补偿的 AI任务（ai_run）')
    expect(wrapper.text()).not.toContain('账号已进入注销保留期')
  })
})
