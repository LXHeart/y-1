// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import AdminView from './AdminView.vue'

enableAutoUnmount(afterEach)

afterEach(() => {
  vi.unstubAllGlobals()
})

function response(data: unknown, envelope = true): Response {
  return {
    ok: true,
    headers: { get: () => 'application/json' },
    json: async () => envelope ? { success: true, data } : data,
  } as unknown as Response
}

/** 分页信封（任务 #3 契约）：`data: { items, total, limit, offset }`。 */
function paged(items: unknown[], total = items.length, offset = 0) {
  return { items, total, limit: 50, offset }
}

describe('AdminView KYB 审核', () => {
  test('用户搜索会对关键词编码并透传 q 参数', async () => {
    const fetchMock = vi.fn().mockImplementation(async (url: string) => {
      if (url.startsWith('/api/admin/users')) return response(paged([]))
      if (url.startsWith('/api/admin/kyb-requests?')) return response(paged([]))
      throw new Error(`unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(AdminView, { global: { stubs: { Teleport: true } } })
    await flushPromises()
    await wrapper.get('input[placeholder="搜索邮箱、昵称或账号 ID"]').setValue('alice+ops')
    await wrapper.get('.admin-panel form.search-toolbar').trigger('submit')
    await flushPromises()

    expect(fetchMock.mock.calls.map(([url]) => String(url)))
      .toContain('/api/admin/users?limit=10&offset=0&q=alice%2Bops')
  })

  test('管理 tab 完整显示等级与信任治理入口，AI 模型面板仍懒挂载', async () => {
    const fetchMock = vi.fn().mockImplementation(async (url: string) => {
      if (url.startsWith('/api/admin/users')) return response(paged([]))
      if (url.startsWith('/api/admin/kyb-requests?')) return response(paged([]))
      throw new Error(`unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(AdminView, {
      global: {
        stubs: {
          Teleport: true,
          AiPlatformModelsPanel: { template: '<div data-testid="ai-models-panel">AI 模型配置</div>' },
        },
      },
    })
    await flushPromises()
    const tabs = wrapper.findAll('[role="tab"]')

    expect(tabs.map((tab) => tab.text().trim())).toEqual(
      ['用户与积分', 'KYB 审核', '主体更名', '推荐官认证', '任务审核', '等级与权益', '审判官准入', '财务对账',
        '风险调查', '积分套餐', '经营分析', '订单核销', 'AI 模型', '首页热点', '统一审计', '公共素材', '门店媒体',
        // 任务书 #51：账号前缀改名（末尾追加——本文件多处按下标点页签）
        '账号前缀',
        // 任务书 #57：创作风格 skill 库（admin-only）
        '创作风格',
        // 任务书 #61：去AI味规则库（admin-only）
        '去AI味',
        // 任务书 #64 卡7：BGM 曲库（DOM 末尾追加——本文件多处按下标点页签）
        'BGM 曲库',
        // 任务书 #65 卡7：视频任务监控（只读指标，DOM 末尾追加）
        '视频任务'])
    expect(wrapper.find('[data-testid="ai-models-panel"]').exists()).toBe(false)

    const aiModelsTab = tabs.find((tab) => tab.text().trim() === 'AI 模型')!
    await aiModelsTab.trigger('click')

    expect(wrapper.find('[data-testid="ai-models-panel"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('待审核申请')
    expect(aiModelsTab.attributes('aria-selected')).toBe('true')
  })

  test('加载待审队列并携带拒绝备注提交，成功后移出队列', async () => {
    const request = {
      id: 'request-1', organizationId: 'org-1', requesterAccountId: 'account-1',
      verificationType: 'store_profile', targetId: 'store-1', materials: null,
      status: 'pending', reviewerAccountId: null, reviewNote: null,
      reviewDeadline: '2099-01-02T00:00:00Z', createdAt: '2099-01-01T00:00:00Z',
    }
    const detail = {
      request,
      subject: {
        type: 'store_profile', storeId: 'store-1', address: '{"address":"南京西路 8 号"}',
        phone: '13800000000', businessHours: null, description: '旗舰店', status: 'pending',
      },
      attachments: [],
    }
    // 审核成功后后端会把该申请移出待审队列——mock 同步这层状态，二次拉取返回空页
    let pendingKyb = [request]
    const fetchMock = vi.fn().mockImplementation(async (url: string, init?: RequestInit) => {
      if (url.startsWith('/api/admin/users')) return response(paged([]))
      if (url.startsWith('/api/admin/kyb-requests?')) return response(paged(pendingKyb))
      if (url === '/api/admin/kyb-requests/request-1' && !init?.method) return response(detail)
      if (url === '/api/admin/kyb-requests/request-1/reject' && init?.method === 'POST') {
        pendingKyb = []
        return response({ ...request, status: 'rejected', reviewNote: '地址无法核验' })
      }
      throw new Error(`unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(AdminView, { global: { stubs: { Teleport: true } } })
    await flushPromises()
    await wrapper.findAll('[role="tab"]')[1].trigger('click')
    expect(wrapper.text()).toContain('门店资料')
    expect(wrapper.text()).toContain('org-1')

    await wrapper.find('.reject-btn').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('南京西路 8 号')
    expect(wrapper.text()).toContain('13800000000')
    await wrapper.find('textarea').setValue('地址无法核验')
    await wrapper.find('.btn-confirm.danger').trigger('click')
    await flushPromises()

    const reviewCall = fetchMock.mock.calls.find(([url]) =>
      url === '/api/admin/kyb-requests/request-1/reject')
    expect(JSON.parse((reviewCall?.[1] as RequestInit).body as string)).toEqual({ note: '地址无法核验' })
    expect(wrapper.text()).toContain('暂无待审核申请')
  })

  test('拒绝时必须填写原因，校验失败不发送审核请求', async () => {
    const request = {
      id: 'request-2', organizationId: 'org-2', requesterAccountId: 'account-2',
      verificationType: 'merchant_profile', targetId: 'org-2', materials: null,
      status: 'pending', reviewerAccountId: null, reviewNote: null,
      reviewDeadline: null, createdAt: null,
    }
    const detail = {
      request,
      subject: {
        type: 'merchant_profile', organizationId: 'org-2', legalName: '草场商贸',
        unifiedSocialCreditCode: '91310000TEST000001', businessType: 'company', industry: 'retail',
        legalPersonName: '张三', legalPersonIdNumberMasked: '****1234',
        registeredCapitalCents: null, establishmentDate: null, businessAddress: null,
        contactPhone: null, contactEmail: null, status: 'pending',
      },
      attachments: [],
    }
    const fetchMock = vi.fn().mockImplementation(async (url: string) => {
      if (url.startsWith('/api/admin/users')) return response(paged([]))
      if (url.startsWith('/api/admin/kyb-requests?')) return response(paged([request]))
      return response(detail)
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(AdminView, { global: { stubs: { Teleport: true } } })
    await flushPromises()
    await wrapper.findAll('[role="tab"]')[1].trigger('click')
    await wrapper.find('.reject-btn').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('行业类型')
    expect(wrapper.text()).toContain('零售')
    await wrapper.find('.btn-confirm.danger').trigger('click')

    expect(wrapper.text()).toContain('请填写拒绝原因')
    expect(fetchMock).toHaveBeenCalledTimes(3)
  })

  test('审核详情加载失败时禁止盲审', async () => {
    const request = {
      id: 'request-3', organizationId: 'org-3', requesterAccountId: 'account-3',
      verificationType: 'merchant_profile', targetId: 'org-3', materials: '["attachment-1"]',
      status: 'pending', reviewerAccountId: null, reviewNote: null,
      reviewDeadline: null, createdAt: null,
    }
    const fetchMock = vi.fn().mockImplementation(async (url: string) => {
      if (url.startsWith('/api/admin/users')) return response(paged([]))
      if (url.startsWith('/api/admin/kyb-requests?')) return response(paged([request]))
      if (url === '/api/admin/kyb-requests/request-3') {
        return {
          ok: false, status: 503, headers: { get: () => 'application/json' },
          json: async () => ({ success: false, error: '审核材料暂不可用' }),
        } as unknown as Response
      }
      throw new Error(`unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(AdminView, { global: { stubs: { Teleport: true } } })
    await flushPromises()
    await wrapper.findAll('[role="tab"]')[1].trigger('click')
    await wrapper.find('.approve-btn').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('审核材料暂不可用')
    expect(wrapper.find('.btn-confirm').attributes('disabled')).toBeDefined()
    expect(fetchMock).toHaveBeenCalledTimes(3)
  })
})

describe('AdminView 等级权益治理', () => {
  const policy = {
    version: 7,
    updatedAt: '2026-08-07T08:00:00Z',
    levels: Array.from({ length: 5 }, (_, index) => ({
      levelNumber: index + 1,
      level: `Lv${index + 1}`,
      title: ['新手草友', '活跃草友', '优质草友', '金牌草友', '草场达人'][index],
      minCompleted: [0, 6, 21, 51, 100][index],
      minCompletionRate: [0, 0.8, 0.85, 0.9, 0.95][index],
      minAverageScore: [null, null, 4, 4.5, 4.8][index],
      inviteOnly: index === 4,
      judgeEligible: index === 4,
      taskPriorityWeight: [100, 110, 120, 140, 160][index],
      settlementDelayDays: index === 4 ? 1 : 2,
      commissionBonusBps: [0, 0, 300, 500, 1000][index],
      aiQuotaMultiplierBps: [10000, 10000, 15000, 15000, 15000][index],
      premiumSupport: index >= 3,
      benefits: index === 4 ? ['审判官资格', 'T+1 优先结算'] : ['基础任务'],
    })),
  }

  test('按服务端版本保存五级策略', async () => {
    const fetchMock = vi.fn().mockImplementation(async (url: string, init?: RequestInit) => {
      if (url.startsWith('/api/admin/users')) return response(paged([]))
      if (url.startsWith('/api/admin/kyb-requests?')) return response(paged([]))
      if (url === '/api/admin/reputation-config' && init?.method === 'PUT') return response({ ...policy, version: 8 })
      if (url === '/api/admin/reputation-config') return response(policy)
      throw new Error(`unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(AdminView, { global: { stubs: { Teleport: true } } })
    await flushPromises()

    await wrapper.findAll('[role="tab"]')[5].trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('策略版本 7')
    expect(wrapper.findAll('.reputation-level-row')).toHaveLength(5)

    await wrapper.find('[data-testid="level-2-min-completed"]').setValue(8)
    await wrapper.findAll('.reputation-level-row')[1].find('textarea').setValue('更多任务\n优先展示\n')
    await wrapper.find('[data-testid="save-reputation-policy"]').trigger('click')
    await flushPromises()

    const saveCall = fetchMock.mock.calls.find(([, init]) => (init as RequestInit | undefined)?.method === 'PUT')
    const body = JSON.parse((saveCall?.[1] as RequestInit).body as string)
    expect(body.expectedVersion).toBe(7)
    expect(body.levels).toHaveLength(5)
    expect(body.levels[1].minCompleted).toBe(8)
    expect(body.levels[1].benefits).toEqual(['更多任务', '优先展示'])
    expect(wrapper.text()).toContain('策略版本 8')
  })

  test('版本冲突时保留本地编辑内容', async () => {
    const fetchMock = vi.fn().mockImplementation(async (url: string, init?: RequestInit) => {
      if (url.startsWith('/api/admin/users')) return response(paged([]))
      if (url.startsWith('/api/admin/kyb-requests?')) return response(paged([]))
      if (url === '/api/admin/reputation-config' && init?.method === 'PUT') {
        return {
          ok: false, status: 409, headers: { get: () => 'application/json' },
          json: async () => ({ success: false, error: '等级策略版本已变化，请刷新后重试' }),
        } as unknown as Response
      }
      if (url === '/api/admin/reputation-config') return response(policy)
      throw new Error(`unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(AdminView, { global: { stubs: { Teleport: true } } })
    await flushPromises()

    await wrapper.findAll('[role="tab"]')[5].trigger('click')
    await flushPromises()
    const input = wrapper.find('[data-testid="level-2-min-completed"]')
    await input.setValue(9)
    await wrapper.find('[data-testid="save-reputation-policy"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('等级策略版本已变化')
    expect((input.element as HTMLInputElement).value).toBe('9')
  })

  test('查询账号声誉后以 admissionVersion 授予 Lv5', async () => {
    const accountId = '11111111-1111-4111-8111-111111111111'
    const reputation = {
      accountId, level: 'Lv4', levelTitle: '金牌草友', calculatedLevel: 'Lv5', effectiveLevel: 'Lv4',
      levelNumber: 4, judgeEligible: false, policyVersion: 7, taskPriorityWeight: 140,
      settlementDelayDays: 2, commissionBonusBps: 500, aiQuotaMultiplierBps: 15000,
      premiumSupport: true, benefits: ['专属支持'], acceptedCount: 110, completedCount: 105,
      merchantCancelledCount: 0, rejectedCount: 2, withdrawnCount: 3, terminalCount: 107,
      completionRate: 0.98, ratingCount: 20, averageScore: 4.9, averageResponseSeconds: 600,
      lv5Admitted: false, admissionVersion: 2, admissionUpdatedBy: null, admissionNote: null,
      admissionUpdatedAt: null,
    }
    let reputationReads = 0
    const fetchMock = vi.fn().mockImplementation(async (url: string, init?: RequestInit) => {
      if (url.startsWith('/api/admin/users')) return response(paged([]))
      if (url.startsWith('/api/admin/kyb-requests?')) return response(paged([]))
      if (url === `/api/admin/reputation/${accountId}/lv5-admission` && init?.method === 'PUT') {
        return response({ accountId, admitted: true, version: 3, note: '签约邀请' })
      }
      if (url === `/api/admin/reputation/${accountId}`) {
        reputationReads += 1
        return response(reputationReads === 1
          ? reputation
          : { ...reputation, lv5Admitted: true, admissionVersion: 3, effectiveLevel: 'Lv5' })
      }
      if (url === '/api/admin/reputation-config') return response(policy)
      throw new Error(`unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(AdminView, { global: { stubs: { Teleport: true } } })
    await flushPromises()

    await wrapper.findAll('[role="tab"]')[5].trigger('click')
    await flushPromises()
    await wrapper.find('[data-testid="reputation-account-id"]').setValue(accountId)
    await wrapper.find('[data-testid="load-admin-reputation"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('指标已达 Lv5')

    await wrapper.find('[data-testid="lv5-admission-note"]').setValue('签约邀请')
    await wrapper.find('[data-testid="grant-lv5"]').trigger('click')
    await flushPromises()

    const grantCall = fetchMock.mock.calls.find(([url]) => url === `/api/admin/reputation/${accountId}/lv5-admission`)
    expect(JSON.parse((grantCall?.[1] as RequestInit).body as string)).toEqual({
      admitted: true, expectedVersion: 2, note: '签约邀请',
    })
    expect(wrapper.text()).toContain('当前生效 Lv5')
  })

  test('较慢的旧账号查询不会覆盖较新的查询结果', async () => {
    const olderAccount = '11111111-1111-4111-8111-111111111111'
    const newerAccount = '22222222-2222-4222-8222-222222222222'
    let resolveOlder: ((value: Response) => void) | undefined
    const olderResponse = new Promise<Response>((resolve) => { resolveOlder = resolve })
    const reputation = (accountId: string, levelTitle: string) => ({
      accountId, level: 'Lv4', levelTitle, calculatedLevel: 'Lv4', effectiveLevel: 'Lv4',
      levelNumber: 4, judgeEligible: false, policyVersion: 7, taskPriorityWeight: 140,
      settlementDelayDays: 2, commissionBonusBps: 500, aiQuotaMultiplierBps: 15000,
      premiumSupport: true, benefits: ['专属支持'], acceptedCount: 60, completedCount: 55,
      merchantCancelledCount: 0, rejectedCount: 2, withdrawnCount: 3, terminalCount: 60,
      completionRate: 0.92, ratingCount: 20, averageScore: 4.7, averageResponseSeconds: 600,
      lastActiveAt: '2026-08-07T08:00:00Z', inactiveDowngraded: false,
      lv5Admitted: false, admissionVersion: 0, admissionUpdatedBy: null, admissionNote: null,
      admissionUpdatedAt: null,
    })
    const fetchMock = vi.fn().mockImplementation(async (url: string) => {
      if (url.startsWith('/api/admin/users')) return response(paged([]))
      if (url.startsWith('/api/admin/kyb-requests?')) return response(paged([]))
      if (url === '/api/admin/reputation-config') return response(policy)
      if (url === `/api/admin/reputation/${olderAccount}`) return olderResponse
      if (url === `/api/admin/reputation/${newerAccount}`) {
        return response(reputation(newerAccount, '较新账号'))
      }
      throw new Error(`unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(AdminView, { global: { stubs: { Teleport: true } } })
    await flushPromises()
    await wrapper.findAll('[role="tab"]')[5].trigger('click')
    await flushPromises()

    const accountInput = wrapper.find('[data-testid="reputation-account-id"]')
    await accountInput.setValue(olderAccount)
    await wrapper.find('[data-testid="load-admin-reputation"]').trigger('click')
    await accountInput.setValue(newerAccount)
    await accountInput.trigger('keyup.enter')
    await flushPromises()
    expect(wrapper.text()).toContain(newerAccount)
    expect(wrapper.text()).toContain('较新账号')

    resolveOlder?.(response(reputation(olderAccount, '较旧账号')))
    await flushPromises()

    expect(wrapper.text()).toContain(newerAccount)
    expect(wrapper.text()).not.toContain('较旧账号')
  })
})

describe('AdminView 审判官运营准入', () => {
  test('理由必填且按候选人版本授权', async () => {
    const accountId = '22222222-2222-4222-8222-222222222222'
    const judge = {
      id: 'judge-1', accountId, organizationId: null, eligibilityTier: 5, active: true,
      opsAdmitted: false, version: 4, opsAdmittedAt: null, opsAdmittedBy: null,
      createdAt: '2026-08-07T08:00:00Z',
    }
    const fetchMock = vi.fn().mockImplementation(async (url: string, init?: RequestInit) => {
      if (url.startsWith('/api/admin/users')) return response(paged([]))
      if (url.startsWith('/api/admin/kyb-requests?')) return response(paged([]))
      if (url.startsWith('/api/admin/trust/judges?') && !init?.method) {
        return response({ items: [judge], nextCursor: null, hasMore: false })
      }
      if (url === `/api/admin/trust/judges/${accountId}/admission` && init?.method === 'PUT') {
        return response({ ...judge, opsAdmitted: true, version: 5 })
      }
      throw new Error(`unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(AdminView, { global: { stubs: { Teleport: true } } })
    await flushPromises()

    await wrapper.findAll('[role="tab"]')[6].trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain(accountId)
    await wrapper.find('[data-testid="judge-admission-toggle"]').trigger('click')
    expect(wrapper.text()).toContain('请填写准入原因')
    expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/admission'))).toHaveLength(0)

    await wrapper.find('[data-testid="judge-reason"]').setValue('通过 Lv5 资格复核')
    await wrapper.find('[data-testid="judge-admission-toggle"]').trigger('click')
    await flushPromises()

    const updateCall = fetchMock.mock.calls.find(([url]) => String(url).endsWith('/admission'))
    expect(JSON.parse((updateCall?.[1] as RequestInit).body as string)).toEqual({
      admitted: true, expectedVersion: 4, reason: '通过 Lv5 资格复核',
    })
    expect(wrapper.text()).toContain('已准入')
  })

  test('审计详情展示行版本流转与操作原因', async () => {
    const accountId = '33333333-3333-4333-8333-333333333333'
    const judge = {
      id: 'judge-2', accountId, organizationId: null, eligibilityTier: 5, active: true,
      opsAdmitted: true, version: 3, opsAdmittedAt: '2026-08-07T08:00:00Z',
      opsAdmittedBy: 'admin-1', createdAt: '2026-08-01T08:00:00Z',
    }
    const fetchMock = vi.fn().mockImplementation(async (url: string) => {
      if (url.startsWith('/api/admin/users')) return response(paged([]))
      if (url.startsWith('/api/admin/kyb-requests?')) return response(paged([]))
      if (url.startsWith('/api/admin/trust/judges?')) {
        return response({ items: [judge], nextCursor: null, hasMore: false })
      }
      if (url === `/api/admin/trust/judges/${accountId}`) {
        return response({ ...judge, audit: [{
          id: 9, action: 'granted', actorAccountId: 'admin-1', reason: '初次资格审核',
          previousVersion: 2, newVersion: 3, createdAt: '2026-08-07T08:00:00Z',
        }] })
      }
      throw new Error(`unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(AdminView, { global: { stubs: { Teleport: true } } })
    await flushPromises()

    await wrapper.findAll('[role="tab"]')[6].trigger('click')
    await flushPromises()
    const recordButton = wrapper.findAll('button').find((button) => button.text() === '记录')
    await recordButton?.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('初次资格审核')
    expect(wrapper.text()).toContain('v2 → v3')
  })
})

describe('AdminView 用户管理（identity 信封）', () => {
  test('解析 {success,data:{items,total,limit,offset}} 分页信封并渲染用户列表 + 余额', async () => {
    const users = [
      { id: 'u-1', email: 'a@example.com', displayName: '用户A', role: 'user', status: 'active',
        createdAt: '2026-01-01T00:00:00Z', balance: 5, totalEarned: 10, totalSpent: 5 },
      { id: 'u-2', email: 'b@example.com', displayName: null, role: 'user', status: 'active',
        createdAt: '2026-01-02T00:00:00Z', balance: 0, totalEarned: 0, totalSpent: 0 },
    ]
    const fetchMock = vi.fn().mockImplementation(async (url: string) => {
      if (url.startsWith('/api/admin/users')) return response(paged(users))
      if (url.startsWith('/api/admin/kyb-requests?')) return response(paged([]))
      throw new Error(`unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(AdminView, { global: { stubs: { Teleport: true } } })
    await flushPromises()

    expect(wrapper.text()).toContain('a@example.com')
    expect(wrapper.text()).toContain('用户A')
    expect(wrapper.text()).toContain('b@example.com')
    // 第一行的「调整积分」按钮存在
    expect(wrapper.findAll('.adjust-btn').length).toBeGreaterThanOrEqual(1)
  })

  test('调整积分发送 {userId,amount,note} 且成功后重载列表', async () => {
    const users = [
      { id: 'u-1', email: 'a@example.com', displayName: null, role: 'user', status: 'active',
        createdAt: '2026-01-01T00:00:00Z', balance: 3, totalEarned: 3, totalSpent: 0 },
    ]
    let usersCallCount = 0
    const fetchMock = vi.fn().mockImplementation(async (url: string, init?: RequestInit) => {
      if (url.startsWith('/api/admin/users')) {
        usersCallCount++
        return response(paged(users))
      }
      if (url === '/api/admin/adjust-credits') {
        const body = JSON.parse(init?.body as string)
        expect(body.userId).toBe('u-1')
        expect(body.amount).toBe(-2)
        expect(body.note).toBe('扣减测试')
        return response({ adjusted: true })
      }
      if (url.startsWith('/api/admin/kyb-requests?')) return response(paged([]))
      throw new Error(`unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(AdminView, { global: { stubs: { Teleport: true } } })
    await flushPromises()

    await wrapper.find('.adjust-btn').trigger('click')
    await flushPromises()
    await (wrapper.find('input[type="number"]')).setValue(-2)
    await wrapper.find('input[placeholder*="手动充值"]').setValue('扣减测试')
    await wrapper.find('.btn-confirm').trigger('click')
    await flushPromises()

    // 成功后重载用户列表（第二次 GET /api/admin/users）
    expect(usersCallCount).toBe(2)
    // 模态关闭
    expect(wrapper.find('.modal-overlay').exists()).toBe(false)
  })

  test('初始化商家账号：成功展示一次性密码，完成后刷新用户列表', async () => {
    const users = [
      { id: 'u-1', email: 'a@example.com', displayName: null, role: 'user', status: 'active',
        createdAt: '2026-01-01T00:00:00Z', balance: 3, totalEarned: 3, totalSpent: 0 },
    ]
    let usersCallCount = 0
    const fetchMock = vi.fn().mockImplementation(async (url: string, init?: RequestInit) => {
      if (url.startsWith('/api/admin/users')) {
        usersCallCount++
        return response(paged(users))
      }
      if (url === '/api/admin/merchant-accounts') {
        const body = JSON.parse(init?.body as string)
        expect(body).toEqual({ email: 'new-merchant@example.com', displayName: '张老板' })
        return response({ userId: 'm-new', email: 'new-merchant@example.com',
          displayName: '张老板', initialPassword: 'Abcd1234Efgh5678', mustChangePassword: true })
      }
      if (url.startsWith('/api/admin/kyb-requests?')) return response(paged([]))
      throw new Error(`unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(AdminView, { global: { stubs: { Teleport: true } } })
    await flushPromises()

    await wrapper.get('[data-testid="open-merchant-init"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="merchant-init-email"]').setValue('new-merchant@example.com')
    await wrapper.get('[data-testid="merchant-init-name"]').setValue('张老板')
    await wrapper.get('.btn-confirm').trigger('click')
    await flushPromises()

    // 一次性初始密码展示（等宽块），未关闭前弹窗保留
    expect(wrapper.get('[data-testid="initial-password"]').text()).toBe('Abcd1234Efgh5678')
    expect(wrapper.text()).toContain('仅展示一次')
    expect(usersCallCount).toBe(1)

    // 点击「完成」关闭 → 刷新用户列表
    await wrapper.get('[data-testid="init-done"]').trigger('click')
    await flushPromises()
    expect(usersCallCount).toBe(2)
    expect(wrapper.find('[data-testid="initial-password"]').exists()).toBe(false)
  })

  test('初始化商家账号：409 邮箱已注册 → 邮箱字段级错误，不展示密码', async () => {
    const fetchMock = vi.fn().mockImplementation(async (url: string) => {
      if (url.startsWith('/api/admin/users')) {
        return response(paged([{ id: 'u-1', email: 'a@example.com', displayName: null, role: 'user',
          status: 'active', createdAt: '2026-01-01T00:00:00Z', balance: 0, totalEarned: 0, totalSpent: 0 }]))
      }
      if (url === '/api/admin/merchant-accounts') {
        return { ok: false, status: 409, headers: { get: () => 'application/json' },
          json: async () => ({ success: false, error: '该邮箱已注册；商家账号仅支持全新邮箱初始化' }) } as unknown as Response
      }
      if (url.startsWith('/api/admin/kyb-requests?')) return response(paged([]))
      throw new Error(`unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(AdminView, { global: { stubs: { Teleport: true } } })
    await flushPromises()

    await wrapper.get('[data-testid="open-merchant-init"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="merchant-init-email"]').setValue('taken@example.com')
    await wrapper.get('[data-testid="merchant-init-name"]').setValue('李老板')
    await wrapper.get('.btn-confirm').trigger('click')
    await flushPromises()

    // 409 → 字段级错误呈现，仍在表单态（无密码展示）
    expect(wrapper.text()).toContain('该邮箱已注册；商家账号仅支持全新邮箱初始化')
    expect(wrapper.find('[data-testid="initial-password"]').exists()).toBe(false)
  })
})

describe('AdminView 公共素材审核台', () => {
  test('批量生成展示部分成功，待审素材加载缩略图并可携理由驳回', async () => {
    const asset = {
      id: 'asset-1', mediaId: 'media-1', libraryType: 'public', category: 'scene',
      title: '夏日饮品·背景·1', tags: ['夏日饮品', 'background'], status: 'pending_review',
      version: 1, mimeType: 'image/png', sizeBytes: 1024,
      validUntil: '2026-09-01T00:00:00Z', createdAt: '2026-08-19T00:00:00Z', updatedAt: null,
    }
    const fetchMock = vi.fn().mockImplementation(async (url: string, init?: RequestInit) => {
      if (url.startsWith('/api/admin/users')) return response(paged([]))
      if (url.startsWith('/api/admin/kyb-requests?')) return response(paged([]))
      if (url.startsWith('/api/admin/content-assets/review')) return response(paged([asset]))
      if (url === '/api/content-assets/asset-1/download-url') {
        return response({ downloadUrl: 'https://assets.test/asset-1.png', expiresIn: 300 })
      }
      if (url === '/api/admin/content-assets/batch-generate' && init?.method === 'POST') {
        return response({
          items: [
            { index: 1, ok: true, assetId: 'asset-2', errorReason: null },
            { index: 2, ok: false, assetId: null, errorReason: '上游繁忙' },
          ],
          okCount: 1,
        })
      }
      if (url === '/api/admin/content-assets/asset-1/review/reject' && init?.method === 'POST') {
        return response({ ...asset, status: 'rejected', version: 2 })
      }
      throw new Error(`unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(AdminView, { global: { stubs: { Teleport: true } } })
    await flushPromises()
    const publicTab = wrapper.findAll('[role="tab"]').find((tab) => tab.text().includes('公共素材'))!
    await publicTab.trigger('click')
    await flushPromises()

    expect(wrapper.get('.public-review-item img').attributes('src')).toBe('https://assets.test/asset-1.png')
    expect(wrapper.text()).toContain('夏日饮品·背景·1')

    await wrapper.get('[data-testid="public-asset-generation-form"] input[placeholder*="夏日饮品"]')
      .setValue('夏日上新')
    await wrapper.get('[data-testid="public-asset-generation-form"] input[type="number"]').setValue(2)
    await wrapper.get('[data-testid="public-asset-generation-form"]').trigger('submit')
    await flushPromises()

    const generateCall = fetchMock.mock.calls.find(([url]) => url === '/api/admin/content-assets/batch-generate')
    const generateBody = JSON.parse(String((generateCall?.[1] as RequestInit).body))
    expect(generateBody).toMatchObject({ kind: 'icon', theme: '夏日上新', count: 2 })
    expect(new Date(generateBody.validUntil).getTime()).toBeGreaterThan(Date.now())
    expect(wrapper.text()).toContain('本批成功 1 / 2')
    expect(wrapper.text()).toContain('上游繁忙')

    await wrapper.get('.public-asset-body input').setValue('画面含品牌标识')
    await wrapper.get('.public-review-item .reject-btn').trigger('click')
    await flushPromises()

      const rejectCall = fetchMock.mock.calls.find(([url]) => url === '/api/admin/content-assets/asset-1/review/reject')
      expect(JSON.parse(String((rejectCall?.[1] as RequestInit).body))).toEqual({
        expectedVersion: 1, note: '画面含品牌标识',
      })
      expect(wrapper.text()).toContain('暂无待审核公共素材')
    })
})

describe('AdminView 任务审核三态与分页（任务书 #53）', () => {
  const rejectedTask = {
    id: 'task-rej-1', title: '被驳回的任务', platform: 'douyin', bountyCents: 10000,
    organizationId: 'org-1', status: 'draft', version: 3,
    lastReviewAction: 'rejected', lastReviewNote: '标题涉嫌夸张宣传', lastReviewedAt: '2026-08-28T10:00:00Z',
    lastRejectedNote: '标题涉嫌夸张宣传', lastRejectedAt: '2026-08-28T10:00:00Z',
  }
  const pendingTask = {
    id: 'task-pending-1', title: '待审核的任务', platform: 'xhs', bountyCents: 5000,
    organizationId: 'org-1', status: 'pending_review', version: 1,
    lastReviewAction: 'submitted', lastReviewNote: null, lastReviewedAt: '2026-08-28T09:00:00Z',
  }

  /** 任务审核页签 mock：按 query 返回对应视图；stats 端点同 mock。 */
  function stubTaskReview(total = 120) {
    return vi.fn().mockImplementation(async (url: string) => {
      if (url.startsWith('/api/admin/users')) return response(paged([]))
      if (url.startsWith('/api/admin/kyb-requests?')) return response(paged([]))
      if (url === '/api/admin/tasks/review/stats') {
        return response({ pending: 3, overdue: 1, approvedLast24Hours: 2, rejectedLast24Hours: 1 })
      }
      if (url.startsWith('/api/admin/tasks/review?')) {
        const query = new URL(url, 'http://localhost').searchParams
        const status = query.get('status') ?? 'pending_review'
        const item = status === 'rejected' ? rejectedTask : pendingTask
        const offset = Number(query.get('offset') ?? 0)
        return response(paged(total === 0 || (status === 'rejected' && offset > 0) ? [] : [item], total, offset))
      }
      throw new Error(`unexpected request: ${url}`)
    })
  }

  async function openTasksTab() {
    const wrapper = mount(AdminView, { global: { stubs: { Teleport: true } } })
    await flushPromises()
    // 页签按下标是红线（AdminView.vue 顶部注释）：0 users / 1 kyb / 2 org-renames / 3 recommenders / 4 tasks
    await wrapper.findAll('[role="tab"]')[4].trigger('click')
    await flushPromises()
    return wrapper
  }

  test('三态筛选切换：请求带对应 status，重复点击同态不重发', async () => {
    const fetchMock = stubTaskReview()
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = await openTasksTab()

    await wrapper.findAll('.status-pill').find((pill) => pill.text() === '已驳回')!.trigger('click')
    await flushPromises()
    expect(fetchMock.mock.calls.some(([url]) => String(url).includes('status=rejected'))).toBe(true)
    // rejected 视图行展示驳回原因与驳回徽标，操作列为 —（决策 F）
    expect(wrapper.text()).toContain('被驳回的任务')
    expect(wrapper.text()).toContain('标题涉嫌夸张宣传')
    expect(wrapper.text()).toContain('已驳回')

    // 统计条渲染 meta 数字
    expect(wrapper.text()).toContain('24h 通过 2')

    await wrapper.findAll('.status-pill').find((pill) => pill.text() === '已通过')!.trigger('click')
    await flushPromises()
    expect(fetchMock.mock.calls.some(([url]) => String(url).includes('status=published'))).toBe(true)

    // 重复点击同态不重发列表请求
    const callsAfterPublished = fetchMock.mock.calls.length
    await wrapper.findAll('.status-pill').find((pill) => pill.text() === '已通过')!.trigger('click')
    await flushPromises()
    expect(fetchMock.mock.calls.length).toBe(callsAfterPublished)
  })

  test('翻页：点下一页发 offset=10 请求，页码文案正确，搜索条件随请求保留', async () => {
    const fetchMock = stubTaskReview(120)
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = await openTasksTab()

    await wrapper.get('input[placeholder="搜索任务标题或描述"]').setValue('夸张')
    await wrapper.get('.admin-panel form.search-toolbar').trigger('submit')
    await flushPromises()
    // 搜索提交后 offset 归零重载（带 q）
    const searchCall = fetchMock.mock.calls.find(([url]) =>
      String(url).includes('status=pending_review') && String(url).includes('q=%E5%A4%B8%E5%BC%A0'))
    expect(searchCall).toBeDefined()

    await wrapper.get('.ops-pagination .ops-page-btn:nth-child(3)').trigger('click')
    await flushPromises()
    const page2Call = fetchMock.mock.calls.map(([url]) => String(url))
      .filter((url) => url.includes('offset=10')).pop()
    expect(page2Call).toContain('q=')
    expect(page2Call).toContain('status=pending_review')
    // 第 2 / 12 页 · 共 120 条（默认 limit=10，120 条整除 10）
    expect(wrapper.get('.ops-page-info').text()).toBe('第 2 / 12 页 · 共 120 条')

    // 翻页后重新搜索回到第 1 页
    await wrapper.get('input[placeholder="搜索任务标题或描述"]').setValue('新词')
    await wrapper.get('.admin-panel form.search-toolbar').trigger('submit')
    await flushPromises()
    const lastListCall = fetchMock.mock.calls.map(([url]) => String(url))
      .filter((url) => url.startsWith('/api/admin/tasks/review?')).pop()
    expect(lastListCall).toContain('offset=0')
  })

  test('切每页条数：发 limit=20 且 offset 归零重载', async () => {
    const fetchMock = stubTaskReview(120)
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = await openTasksTab()

    // 先翻到第 2 页，再切页大小——应回到第 1 页并以新 limit 重载
    await wrapper.get('.ops-pagination .ops-page-btn:nth-child(3)').trigger('click')
    await flushPromises()
    await wrapper.get('.ops-page-size-select').setValue('20')
    await flushPromises()

    const sizeCall = fetchMock.mock.calls.map(([url]) => String(url))
      .filter((url) => url.startsWith('/api/admin/tasks/review?')).pop()
    expect(sizeCall).toContain('limit=20')
    expect(sizeCall).toContain('offset=0')
    expect(wrapper.get('.ops-page-info').text()).toBe('第 1 / 6 页 · 共 120 条')
  })

  test('页签徽标恒为待审数：切到「已通过」视图后不跟随当前筛选总数', async () => {
    const fetchMock = stubTaskReview(120)
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = await openTasksTab()

    // stubTaskReview 的 stats：pending=3——进页签即出徽标
    expect(wrapper.get('[role="tab"][aria-selected="true"] .count-badge').text()).toBe('3')

    await wrapper.findAll('.status-pill').find((pill) => pill.text() === '已通过')!.trigger('click')
    await flushPromises()
    // 已通过视图 total=120，但徽标仍读 stats.pending 而非当前筛选总数
    expect(wrapper.get('[role="tab"][aria-selected="true"] .count-badge').text()).toBe('3')
  })

  test('total=0 空态：分页器显示共 0 条，列表空态文案随视图切换', async () => {
    const fetchMock = stubTaskReview(0)
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = await openTasksTab()

    expect(wrapper.text()).toContain('暂无待审核任务')
    expect(wrapper.get('.ops-page-info').text()).toContain('共 0 条')

    await wrapper.findAll('.status-pill').find((pill) => pill.text() === '已驳回')!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('暂无已驳回任务')
  })
})
