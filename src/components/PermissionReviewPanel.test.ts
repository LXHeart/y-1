// @vitest-environment happy-dom
/**
 * 任务书 #99 T-03：治理台权限审核面板重构（状态筛选 + 详情/审核弹窗）。
 *
 * AC-15 〜 AC-21 覆盖：默认筛选与徽标源、已审核视图终态卡、详情弹窗证据齐备、
 * 附件下载地址协议白名单 + window.open、驳回备注禁用规则与 CAS 版本号、
 * 批准成功后关弹窗/按当前筛选重拉/emit reviewed、三种筛选空态文案。
 *
 * ⚠️ import 顺序：先 `adminTabs` 后组件。`adminTabs` 静态 import 本组件（TAB_REGISTRY），
 * 反向 import 形成 ESM 环；先经 adminTabs 进入可避免环内 TDZ。
 */
import { ADMIN_BADGE_BRIDGE } from '../ops/admin/adminTabs'
import { defineComponent, h, KeepAlive, reactive } from 'vue'
import { createPinia } from 'pinia'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import PermissionReviewPanel from './PermissionReviewPanel.vue'

enableAutoUnmount(afterEach)

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

function response(data: unknown): Response {
  return {
    ok: true,
    status: 200,
    headers: { get: () => 'application/json' },
    json: async () => ({ success: true, data }),
  } as unknown as Response
}

function permissionRequest(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    id: 'req-1',
    organizationId: 'org-12345678-abcd-0000-000000000000',
    requesterAccountId: 'acct-87654321-abcd-0000-000000000000',
    requestedTier: 'basic_publish',
    status: 'pending',
    industry: 'catering',
    materials: JSON.stringify({ business_license: 'BL-001' }),
    reviewDeadline: '2026-09-12T00:00:00Z',
    slaStatus: 'within',
    reviewerAccountId: null,
    reviewNote: null,
    originalRequestId: null,
    appealNote: null,
    createdAt: '2026-09-10T02:00:00Z',
    version: 3,
    reviewStartedAt: null,
    slaBreachedAt: null,
    autoReviewStatus: 'needs_review',
    autoReviewResult: JSON.stringify({
      attachmentCount: 1,
      failedTypes: ['business_license'],
      humanReviewRequired: true,
    }),
    reviewMode: 'manual',
    riskLevel: 'standard',
    attachmentIds: JSON.stringify(['att-9']),
    decisionAt: null,
    appealCount: 0,
    ...overrides,
  }
}

const detailRow = {
  ...permissionRequest(),
  organization: { id: 'org-12345678-abcd-0000-000000000000', name: '橙子科技' },
  attachments: [{
    id: 'att-9',
    attachmentType: 'business_license',
    mimeType: 'image/png',
    sizeBytes: 2048,
    ocrStatus: 'passed',
    uploadedAt: '2026-09-10T01:00:00Z',
  }],
}

const auditRow = {
  id: 'audit-1',
  actorAccountId: 'acct-admin',
  actorKind: 'admin',
  action: 'claimed',
  fromStatus: 'pending',
  toStatus: 'under_review',
  details: null,
  createdAt: '2026-09-10T03:00:00Z',
}

interface FetchCall { url: string; init?: RequestInit }

function installFetch(
  queueByFilter: Record<string, Record<string, unknown>[]>,
  detail: Record<string, unknown> = detailRow,
  audit: Record<string, unknown>[] = [auditRow],
): FetchCall[] {
  const calls: FetchCall[] = []
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    calls.push({ url, init })
    if (url.startsWith('/api/admin/permission-requests?')) {
      const status = url.split('status=')[1]
      return response(queueByFilter[status] ?? [])
    }
    if (/^\/api\/admin\/permission-requests\/[^/]+\/audit$/.test(url)) return response(audit)
    if (/^\/api\/admin\/permission-requests\/[^/]+\/attachments\/[^/]+\/download-url$/.test(url)) {
      return response({ downloadUrl: 'https://media.test/dl?sig=1', expiresAt: '2026-09-10T12:00:00Z' })
    }
    if (url.endsWith('/review')) return response(detail)
    if (url.endsWith('/claim')) return response({ ...detail, status: 'under_review' })
    if (url === '/api/me/reauthenticate') return response({ authStrength: 'level2', reauthenticatedAt: null })
    if (/^\/api\/admin\/permission-requests\/[^/]+$/.test(url)) return response(detail)
    throw new Error(`unexpected request: ${url}`)
  }))
  return calls
}

function mountPanel() {
  const register = vi.fn()
  const unregister = vi.fn()
  const wrapper = mount(PermissionReviewPanel, {
    global: {
      plugins: [createPinia()],
      stubs: { Teleport: true },
      provide: { [ADMIN_BADGE_BRIDGE]: { register, unregister } } as Record<string, unknown>,
    },
  })
  return { wrapper, register, unregister }
}

/** 打开首张申请卡片的详情/审核弹窗。 */
async function openFirstDetail(wrapper: ReturnType<typeof mount>): Promise<void> {
  await wrapper.get('[data-testid="permission-open-btn"]').trigger('click')
  await flushPromises()
}

describe('PermissionReviewPanel 权限审核队列（任务书 #99）', () => {
  test('AC-15 默认待审核：列表请求带 ?status=pending，徽标源读待审数', async () => {
    const calls = installFetch({ pending: [permissionRequest(), permissionRequest({ id: 'req-2' })] })
    const { wrapper, register, unregister } = mountPanel()
    await flushPromises()

    expect(calls.map((call) => call.url)).toContain('/api/admin/permission-requests?status=pending')
    expect(register).toHaveBeenCalledWith('permission-review', expect.any(Function))
    const badgeSource = register.mock.calls[0][1] as () => number
    expect(badgeSource()).toBe(2)

    wrapper.unmount()
    expect(unregister).toHaveBeenCalledWith('permission-review')
  })

  test('AC-16 切「已审核」：请求 ?status=reviewed，终态卡显示结论与备注、按钮为「详情」', async () => {
    const reviewed = permissionRequest({
      id: 'req-9',
      status: 'approved',
      decisionAt: '2026-09-10T05:00:00Z',
      reviewNote: '材料齐全',
      autoReviewResult: null,
      attachmentIds: null,
    })
    const calls = installFetch({ pending: [], reviewed: [reviewed] })
    const { wrapper } = mountPanel()
    await flushPromises()

    await wrapper.get('[data-testid="permission-status-filter"]').setValue('reviewed')
    await flushPromises()

    expect(calls.map((call) => call.url)).toContain('/api/admin/permission-requests?status=reviewed')
    const card = wrapper.get('.pr-item')
    expect(card.text()).toContain('审核结论：已批准')
    expect(card.text()).toContain('材料齐全')
    expect(card.get('[data-testid="permission-open-btn"]').text()).toBe('详情')
  })

  test('AC-17 点「审核」：GET 详情 + audit 并行，弹窗显示组织名/材料/附件 OCR 状态', async () => {
    const calls = installFetch({ pending: [permissionRequest()] })
    const { wrapper } = mountPanel()
    await flushPromises()
    await openFirstDetail(wrapper)

    const urls = calls.map((call) => call.url)
    expect(urls).toContain('/api/admin/permission-requests/req-1')
    expect(urls).toContain('/api/admin/permission-requests/req-1/audit')

    const modal = wrapper.get('.modal-card')
    expect(modal.text()).toContain('审核 · 权限申请')
    expect(modal.text()).toContain('橙子科技')
    expect(modal.text()).toContain('营业执照')
    expect(modal.text()).toContain('BL-001')
    expect(modal.text()).toContain('OCR通过')
    expect(modal.text()).toContain('领取审核')
  })

  test('AC-18 弹窗点「查看」：GET download-url 后以签发地址 window.open', async () => {
    const calls = installFetch({ pending: [permissionRequest()] })
    const openSpy = vi.spyOn(window, 'open').mockImplementation(() => null)
    const { wrapper } = mountPanel()
    await flushPromises()
    await openFirstDetail(wrapper)

    await wrapper.get('.pr-material-view').trigger('click')
    await flushPromises()

    expect(calls.map((call) => call.url))
      .toContain('/api/admin/permission-requests/req-1/attachments/att-9/download-url')
    expect(openSpy).toHaveBeenCalledWith('https://media.test/dl?sig=1', '_blank', 'noopener,noreferrer')
  })

  test('AC-19 驳回无备注按钮禁用；填备注后 POST review 带 expectedVersion', async () => {
    const calls = installFetch({ pending: [permissionRequest()] })
    const { wrapper } = mountPanel()
    await flushPromises()
    await openFirstDetail(wrapper)

    expect(wrapper.get('[data-testid="permission-reject-btn"]').attributes('disabled')).toBeDefined()

    await wrapper.get('[data-testid="permission-review-note"]').setValue('材料不符')
    await flushPromises()
    expect(wrapper.get('[data-testid="permission-reject-btn"]').attributes('disabled')).toBeUndefined()

    await wrapper.get('[data-testid="permission-reject-btn"]').trigger('click')
    await flushPromises()

    const reviewCall = calls.find((call) => call.url.endsWith('/review'))
    expect(reviewCall?.init?.method).toBe('POST')
    expect(JSON.parse(String(reviewCall?.init?.body)))
      .toEqual({ decision: 'reject', note: '材料不符', expectedVersion: 3 })
  })

  test('AC-20 批准成功：POST review 带 expectedVersion，关弹窗、按当前筛选重拉、emit reviewed', async () => {
    const calls = installFetch({ pending: [permissionRequest()] })
    const { wrapper } = mountPanel()
    await flushPromises()
    await openFirstDetail(wrapper)

    await wrapper.get('[data-testid="permission-approve-btn"]').trigger('click')
    await flushPromises()

    const reviewCall = calls.find((call) => call.url.endsWith('/review'))
    expect(JSON.parse(String(reviewCall?.init?.body))).toEqual({ decision: 'approve', expectedVersion: 3 })
    expect(wrapper.find('.modal-card').exists()).toBe(false)
    expect(wrapper.emitted('reviewed')).toHaveLength(1)
    expect(calls.filter((call) => call.url === '/api/admin/permission-requests?status=pending')).toHaveLength(2)
    expect(wrapper.text()).toContain('已批准：组织升级为「基础发布」')
  })

  test('AC-21 空态文案随筛选：待审核 / 已审核 / 全部', async () => {
    installFetch({ pending: [], reviewed: [], all: [] })
    const { wrapper } = mountPanel()
    await flushPromises()
    expect(wrapper.text()).toContain('当前没有待审核的申请。')

    await wrapper.get('[data-testid="permission-status-filter"]').setValue('reviewed')
    await flushPromises()
    expect(wrapper.text()).toContain('暂无已审核记录。')

    await wrapper.get('[data-testid="permission-status-filter"]').setValue('all')
    await flushPromises()
    expect(wrapper.text()).toContain('暂无申请记录。')
  })

  test('KeepAlive 重进页签：首次激活跳过、再次激活重拉队列', async () => {
    const calls = installFetch({ pending: [permissionRequest()] })
    const state = reactive({ show: true })
    const Host = defineComponent({
      setup: () => () => h(KeepAlive, null, {
        default: () => (state.show ? h(PermissionReviewPanel) : null),
      }),
    })
    mount(Host, {
      global: {
        plugins: [createPinia()],
        stubs: { Teleport: true },
        provide: { [ADMIN_BADGE_BRIDGE]: { register: vi.fn(), unregister: vi.fn() } } as Record<string, unknown>,
      },
    })
    await flushPromises()
    expect(calls.filter((call) => call.url.startsWith('/api/admin/permission-requests?'))).toHaveLength(1)

    state.show = false
    await flushPromises()
    state.show = true
    await flushPromises()
    expect(calls.filter((call) => call.url.startsWith('/api/admin/permission-requests?'))).toHaveLength(2)
  })
})
