// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import AiGovernanceSection from './AiGovernanceSection.vue'
import { useModelSource } from '../../../composables/useModelSource'

/**
 * AI 与治理板块（任务书 #78 卡 C）：
 * platform 态 = 预算卡（自 runs 板块迁入）；own 态 = 密钥面板；
 * 商家主体治理节仅 owner/admin 渲染、多主体下拉切换、纯个人不渲染。
 */
enableAutoUnmount(afterEach)
afterEach(() => vi.unstubAllGlobals())

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
}

const PREFS = { success: true, data: { items: [], modelSource: 'platform', masterVersion: 0 } }

/** 板块挂载并发多请求，按 URL 分发。scopes='fail' 模拟 403。 */
function routedFetch(handlers: {
  scopes?: unknown[] | 'fail'
  prefs?: () => Response
  budget?: () => Response
  orgBudget?: () => Response
  fallback?: (url: string, init?: RequestInit) => Response
}) {
  return vi.fn(async (url: string, init?: RequestInit) => {
    if (url === '/api/ai/preferences') return (handlers.prefs ?? (() => json(PREFS)))()
    if (url === '/api/me/organization-scopes') {
      return handlers.scopes === 'fail'
        ? json({ error: 'forbidden' }, 403)
        : json({ success: true, data: handlers.scopes ?? [] })
    }
    if (url === '/api/ai/me/budget') return (handlers.budget ?? (() => json({ success: true, data: {
      organizationId: 'u:x', version: 0, enabled: true, maxTokensPerRun: null, maxTokensDaily: null,
      maxTokensMonthly: null, maxCentsPerRun: null, maxCentsDaily: null, maxCentsMonthly: null,
      currentDailyTokens: 0, currentDailyCents: 0, currentMonthlyTokens: 0, currentMonthlyCents: 0,
      usage: { dailyTokens: 0, dailyCents: 0, monthlyTokens: 0, monthlyCents: 0 },
      overCurrentUsage: false,
    } })))()
    if (url.startsWith('/api/ai/organizations/')) return (handlers.orgBudget ?? (() => json({ success: true, data: {
      organizationId: 'org-1', version: 0, enabled: true, maxTokensPerRun: null, maxTokensDaily: null,
      maxTokensMonthly: null, maxCentsPerRun: null, maxCentsDaily: null, maxCentsMonthly: null,
      currentDailyTokens: 0, currentDailyCents: 0, currentMonthlyTokens: 0, currentMonthlyCents: 0,
      usage: { dailyTokens: 0, dailyCents: 0, monthlyTokens: 0, monthlyCents: 0 },
      overCurrentUsage: false,
    } })))()
    if (url === '/api/ai/keys') return json([])
    return (handlers.fallback ? handlers.fallback(url, init) : json({}))
  })
}

async function resetSharedState(modelSource: 'platform' | 'own' = 'platform'): Promise<void> {
  const shared = useModelSource()
  shared.reset()
  shared.modelSource.value = modelSource
  shared.masterVersion.value = 0
  shared.loaded.value = true
  await Promise.resolve()
}

describe('AiGovernanceSection（任务书 #78 卡 C）', () => {
  test('platform 态：模型来源卡 + 个人预算卡渲染；own 态：密钥面板渲染且预算卡消失', async () => {
    await resetSharedState('platform')
    vi.stubGlobal('fetch', routedFetch({}))
    const wrapper = mount(AiGovernanceSection)
    await flushPromises()

    expect(wrapper.find('.model-source-card').exists()).toBe(true)
    expect(wrapper.find('.personal-budget-card').exists()).toBe(true)

    // 切 own（绕过 UI 直接改共享态——开关交互在 ModelSourceCard 测试覆盖）
    await resetSharedState('own')
    await flushPromises()
    expect(wrapper.find('.personal-budget-card').exists()).toBe(false)
    expect(wrapper.find('[data-testid="key-availability"]').exists()).toBe(true)
  })

  test('商家 owner：主体治理节渲染、多主体下拉切换、org 面板随动；member 不渲染', async () => {
    await resetSharedState('platform')
    const scopes = [
      { organizationId: 'org-1', organizationName: '示例商家', organizationStatus: 'active',
        permissionTier: 'finance_transaction', role: 'owner' },
      { organizationId: 'org-2', organizationName: '第二主体', organizationStatus: 'active',
        permissionTier: 'basic_publish', role: 'admin' },
      { organizationId: 'org-3', organizationName: '纯成员主体', organizationStatus: 'active',
        permissionTier: 'basic_publish', role: 'member' },
    ]
    const fetchMock = routedFetch({ scopes })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(AiGovernanceSection)
    await flushPromises()

    const governance = wrapper.find('[aria-label="商家主体治理"]')
    expect(governance.exists()).toBe(true)
    // member 主体不进下拉；默认选第一个 owner 主体（只看主体下拉自己的 options）
    const options = governance.get('select[aria-label="商家主体"]').findAll('option')
    expect(options.map((o) => o.text())).toEqual(['示例商家', '第二主体'])
    expect(fetchMock.mock.calls.some(([url]) => String(url).startsWith('/api/ai/organizations/org-1/'))).toBe(true)

    // 切换主体 → org 面板随动（orgBudget 端点带 org-2）
    const orgUrls = () => fetchMock.mock.calls.filter(([url]) => String(url).startsWith('/api/ai/organizations/org-2/'))
    const before = orgUrls().length
    await governance.get('select[aria-label="商家主体"]').setValue('org-2')
    await flushPromises()
    expect(orgUrls().length).toBeGreaterThan(before)
  })

  test('纯个人账号（无 owner/admin 主体）：治理节不渲染', async () => {
    await resetSharedState('platform')
    vi.stubGlobal('fetch', routedFetch({
      scopes: [{ organizationId: 'org-9', organizationName: '成员身份', organizationStatus: 'active',
        permissionTier: 'basic_publish', role: 'member' }],
    }))
    const wrapper = mount(AiGovernanceSection)
    await flushPromises()

    expect(wrapper.find('[aria-label="商家主体治理"]').exists()).toBe(false)
  })

  test('org-scopes 拉取失败静默不渲染治理节（板块其余部分不受影响）', async () => {
    await resetSharedState('platform')
    vi.stubGlobal('fetch', routedFetch({ scopes: 'fail' }))
    const wrapper = mount(AiGovernanceSection)
    await flushPromises()

    expect(wrapper.find('[aria-label="商家主体治理"]').exists()).toBe(false)
    expect(wrapper.find('.model-source-card').exists()).toBe(true)
  })
})
