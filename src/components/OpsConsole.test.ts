// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import OpsConsole from '../views/ops/OpsConsole.vue'

/**
 * 运营处置台。重点锁的是「错了会让运营做出错误资金决策」的几处：
 * - 动作按钮按来源收窄（对暂缓单不该出现「重试对账」）；
 * - 动作只在 `approved` 态出现（绕过双人审批就不是双人审批了）；
 * - 每次动作都带**新生成的** operationId（复用会被后端当重放，静默不执行）；
 * - 流转请求必须回传当前 `version`（乐观锁）；
 * - 动作返回 `failed` 时 UI 必须显示失败（HTTP 200 不等于成功）；
 * - 待判定视图展示 check 明细，并支持对 inconclusive 做带原因的人工改判。
 */

const CASE_BLOCKED = {
  id: 'case-1', sourceKind: 'settlement_blocked', sourceRef: 'evt-1', organizationId: 'org-1',
  applicationId: 'app-1', reason: 'finance_blocked', severity: 'high', status: 'open', version: 1,
  submittedBy: null, submittedAt: null, submitNote: null, approvedBy: null, approvedAt: null,
  approveNote: null, resolvedAt: null, resolution: null,
  createdAt: '2026-08-02T01:00:00Z', updatedAt: '2026-08-02T01:00:00Z',
}

const CASE_HELD_APPROVED = {
  ...CASE_BLOCKED, id: 'case-2', sourceKind: 'settlement_held', sourceRef: 'app-9',
  reason: 'open_dispute', severity: 'normal', status: 'approved', version: 3,
  submittedBy: 'ops-a', approvedBy: 'ops-b',
}

const AUDITS = [
  { id: 'au-1', action: 'registered', actorAccountId: null, actorRole: 'system',
    fromStatus: null, toStatus: 'open', note: 'finance_blocked', createdAt: '2026-08-02T01:00:00Z' },
]

/** 按 URL 前缀分派的 fetch 桩；记录所有调用供断言。 */
function stubFetch(routes: { match: string; data: unknown }[]) {
  const calls: { url: string; method?: string; body?: string }[] = []
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    calls.push({ url, method: init?.method, body: init?.body as string | undefined })
    const hit = [...routes].reverse().find((r) => url.includes(r.match))
    return {
      ok: true,
      headers: { get: () => 'application/json' },
      json: async () => ({ success: true, data: hit ? hit.data : [] }),
    }
  }))
  return calls
}

enableAutoUnmount(afterEach)

async function mountConsole(routes: { match: string; data: unknown }[]) {
  const calls = stubFetch(routes)
  const wrapper = mount(OpsConsole)
  await flushPromises()
  return { wrapper, calls }
}

describe('OpsConsole', () => {
  test('队列默认拉未终态（不带 status），高危单标出来', async () => {
    const { wrapper, calls } = await mountConsole([{ match: '/api/ops/cases', data: [CASE_BLOCKED] }])

    expect(calls[0].url).toBe('/api/ops/cases')
    expect(wrapper.text()).toContain('对账阻断')
    expect(wrapper.text()).toContain('高危')
    expect(wrapper.find('.ops-row-high').exists()).toBe(true)
  })

  test('提审带上当前 version（乐观锁），备注进请求体', async () => {
    const { wrapper, calls } = await mountConsole([
      { match: '/api/ops/cases', data: [CASE_BLOCKED] },
      { match: '/api/ops/cases/case-1', data: { case: CASE_BLOCKED, audits: AUDITS, actions: [] } },
    ])

    await wrapper.find('.ops-table .ops-quiet').trigger('click')
    await flushPromises()
    await wrapper.find('.ops-flow input').setValue('人工核对后确认可重试')
    await wrapper.findAll('.ops-flow button').find((b) => b.text() === '提审')!.trigger('click')
    await flushPromises()

    const submit = calls.find((c) => c.url.endsWith('/submit'))!
    expect(submit.method).toBe('POST')
    expect(JSON.parse(submit.body!)).toEqual({ expectedVersion: 1, note: '人工核对后确认可重试' })
  })

  test('open 态不出现任何处置动作按钮（动作须先过双人审批）', async () => {
    const { wrapper } = await mountConsole([
      { match: '/api/ops/cases', data: [CASE_BLOCKED] },
      { match: '/api/ops/cases/case-1', data: { case: CASE_BLOCKED, audits: AUDITS, actions: [] } },
    ])

    await wrapper.find('.ops-table .ops-quiet').trigger('click')
    await flushPromises()
    const labels = wrapper.findAll('.ops-flow button').map((b) => b.text())
    expect(labels).toContain('提审')
    expect(labels).not.toContain('重试对账')
    expect(labels).not.toContain('释放托管资金')
  })

  test('动作按来源收窄：暂缓单只给「释放托管资金」，不给「重试对账」', async () => {
    const { wrapper, calls } = await mountConsole([
      { match: '/api/ops/cases', data: [CASE_HELD_APPROVED] },
      { match: '/api/ops/cases/case-2', data: { case: CASE_HELD_APPROVED, audits: AUDITS, actions: [] } },
      { match: '/actions', data: { id: 'act-1', caseId: 'case-2', operationId: 'op-1',
        action: 'release_funds', status: 'succeeded', requestedBy: 'ops-b',
        outcome: 'released', error: null, createdAt: null, completedAt: null } },
    ])

    await wrapper.find('.ops-table .ops-quiet').trigger('click')
    await flushPromises()
    const labels = wrapper.findAll('.ops-flow button').map((b) => b.text())
    expect(labels).toContain('释放托管资金')
    expect(labels).not.toContain('重试对账')

    await wrapper.findAll('.ops-flow button').find((b) => b.text() === '释放托管资金')!.trigger('click')
    await flushPromises()

    const body = JSON.parse(calls.find((c) => c.url.endsWith('/actions'))!.body!)
    expect(body.action).toBe('release_funds')
    expect(body.operationId).toMatch(/^release_funds-/)
    expect(wrapper.text()).toContain('成功')
  })

  test('每次动作都用新 operationId（复用会被后端当重放而不执行）', async () => {
    const { wrapper, calls } = await mountConsole([
      { match: '/api/ops/cases', data: [CASE_HELD_APPROVED] },
      { match: '/api/ops/cases/case-2', data: { case: CASE_HELD_APPROVED, audits: AUDITS, actions: [] } },
      { match: '/actions', data: { id: 'act-1', caseId: 'case-2', operationId: 'op-1',
        action: 'release_funds', status: 'succeeded', requestedBy: 'ops-b',
        outcome: 'released', error: null, createdAt: null, completedAt: null } },
    ])

    await wrapper.find('.ops-table .ops-quiet').trigger('click')
    await flushPromises()
    const button = wrapper.findAll('.ops-flow button').find((b) => b.text() === '释放托管资金')!
    await button.trigger('click')
    await flushPromises()
    await button.trigger('click')
    await flushPromises()

    const ids = calls.filter((c) => c.url.endsWith('/actions'))
      .map((c) => JSON.parse(c.body!).operationId as string)
    expect(ids).toHaveLength(2)
    expect(ids[0]).not.toBe(ids[1])
  })

  test('动作返回 failed 时明确显示失败（HTTP 200 不等于成功）', async () => {
    const { wrapper } = await mountConsole([
      { match: '/api/ops/cases', data: [CASE_HELD_APPROVED] },
      { match: '/api/ops/cases/case-2', data: { case: CASE_HELD_APPROVED, audits: AUDITS, actions: [] } },
      { match: '/actions', data: { id: 'act-1', caseId: 'case-2', operationId: 'op-1',
        action: 'release_funds', status: 'failed', requestedBy: 'ops-b',
        outcome: null, error: 'escrow 不在可释放状态', createdAt: null, completedAt: null } },
    ])

    await wrapper.find('.ops-table .ops-quiet').trigger('click')
    await flushPromises()
    await wrapper.findAll('.ops-flow button').find((b) => b.text() === '释放托管资金')!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('失败')
    expect(wrapper.text()).toContain('escrow 不在可释放状态')
  })

  test('审计时间线显示系统登记与状态迁移', async () => {
    const { wrapper } = await mountConsole([
      { match: '/api/ops/cases', data: [CASE_BLOCKED] },
      { match: '/api/ops/cases/case-1', data: { case: CASE_BLOCKED, audits: AUDITS, actions: [] } },
    ])

    await wrapper.find('.ops-table .ops-quiet').trigger('click')
    await flushPromises()
    const timeline = wrapper.find('.ops-timeline').text()
    expect(timeline).toContain('系统登记')
    expect(timeline).toContain('系统')
    expect(timeline).toContain('open')
  })

  test('终态单不留流转区（收单/驳回后没有可做的事）', async () => {
    const resolved = {
      ...CASE_BLOCKED, status: 'resolved', version: 4,
      submittedBy: 'ops-a', approvedBy: 'ops-b', resolution: 'escrow 不存在，转财务人工核对',
    }
    const { wrapper } = await mountConsole([
      { match: '/api/ops/cases', data: [resolved] },
      { match: '/api/ops/cases/case-1', data: { case: resolved, audits: AUDITS, actions: [] } },
    ])

    await wrapper.find('.ops-table .ops-quiet').trigger('click')
    await flushPromises()
    expect(wrapper.find('.ops-flow').exists()).toBe(false)
    expect(wrapper.find('.ops-resolution').text()).toContain('escrow 不存在')
  })

  test('死信报错摘出尾段做标题，Spring 样板收进 details', async () => {
    const springBlob = [
      'Async Fail', 'Endpoint handler details:',
      'Method [public reactor.core.publisher.Mono<java.lang.Void> com.grassland.marketplace.event.TrustEventConsumer.onEvent(...)]',
      'Bean [com.grassland.marketplace.event.TrustEventConsumer@4bf4680c]; trust event field aggregateType must be a non-blank string',
    ].join('\n')
    const msg = {
      id: 'dlt-1', topic: 'grassland.trust.events.DLT', partition: 0, offset: 1,
      originalTopic: 'grassland.trust.events', messageKey: 'k-1', payload: '{"eventType":"X"}',
      errorSummary: springBlob, status: 'pending', replayedAt: null, discardedAt: null, createdAt: null,
    }
    const { wrapper } = await mountConsole([
      { match: '/api/ops/cases', data: [] },
      { match: '/api/ops/dlt', data: [msg] },
    ])

    await wrapper.findAll('[role="tab"]')[1].trigger('click')
    await flushPromises()
    expect(wrapper.find('.ops-err-head').text())
      .toBe('trust event field aggregateType must be a non-blank string')
    expect(wrapper.find('.ops-err-summary details').text()).toContain('Async Fail')
  })

  test('死信卡可跳到对应处置单（按 topic:partition:offset 定位）', async () => {
    const dltCase = {
      ...CASE_BLOCKED, id: 'case-dlt', sourceKind: 'dlt_message',
      sourceRef: 'grassland.trust.events.DLT:0:12', severity: 'normal', reason: 'grassland.trust.events',
    }
    const msg = {
      id: 'dlt-1', topic: 'grassland.trust.events.DLT', partition: 0, offset: 12,
      originalTopic: 'grassland.trust.events', messageKey: 'k-1', payload: '{"eventType":"X"}',
      errorSummary: 'NPE', status: 'pending', replayedAt: null, discardedAt: null, createdAt: null,
    }
    const { wrapper, calls } = await mountConsole([
      { match: '/api/ops/cases', data: [dltCase] },
      { match: '/api/ops/dlt', data: [msg] },
      { match: '/api/ops/cases/case-dlt', data: { case: dltCase, audits: AUDITS, actions: [] } },
    ])

    await wrapper.findAll('[role="tab"]')[1].trigger('click')
    await flushPromises()
    const jump = wrapper.findAll('.ops-item .ops-actions button')
      .find((b) => b.text() === '查看处置单')!
    await jump.trigger('click')
    await flushPromises()

    expect(wrapper.find('.ops-drawer').exists()).toBe(true)
    expect(wrapper.find('.ops-drawer').text()).toContain('grassland.trust.events.DLT:0:12')
    expect(calls.some((c) => c.url.includes('/api/ops/cases/case-dlt'))).toBe(true)
  })

  test('失败提示用告警配色，不复用成功的绿色', async () => {
    const approved = { ...CASE_BLOCKED, status: 'approved', version: 3 }
    const { wrapper } = await mountConsole([
      { match: '/api/ops/cases', data: [approved] },
      { match: '/api/ops/cases/case-1', data: { case: approved, audits: AUDITS, actions: [] } },
      { match: '/actions', data: { id: 'act-9', caseId: 'case-1', operationId: 'op-9',
        action: 'retry_reconciliation', status: 'failed', requestedBy: 'ops-b',
        outcome: null, error: '对账仍未通过：missing/reservation_missing',
        createdAt: null, completedAt: null } },
    ])

    await wrapper.find('.ops-table .ops-quiet').trigger('click')
    await flushPromises()
    await wrapper.find('.ops-actions button').trigger('click')
    await flushPromises()

    const alert = wrapper.find('.ops-alert.ops-err')
    expect(alert.exists()).toBe(true)
    expect(alert.text()).toContain('失败')
    expect(wrapper.find('.ops-alert.ops-ok').exists()).toBe(false)
  })

  test('Escape 关闭详情抽屉（遮罩铺满视口，头部点不到）', async () => {
    const { wrapper } = await mountConsole([
      { match: '/api/ops/cases', data: [CASE_BLOCKED] },
      { match: '/api/ops/cases/case-1', data: { case: CASE_BLOCKED, audits: AUDITS, actions: [] } },
    ])

    await wrapper.find('.ops-table .ops-quiet').trigger('click')
    await flushPromises()
    expect(wrapper.find('.ops-drawer').exists()).toBe(true)

    // 监听挂在 document：流转后按钮出 DOM，焦点掉回 body，挂遮罩上的 keydown 会收不到。
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    await flushPromises()
    expect(wrapper.find('.ops-drawer').exists()).toBe(false)
  })

  test('死信重投走原 topic；已处置的不再给按钮', async () => {
    const pendingMsg = {
      id: 'dlt-1', topic: 'grassland.trust.events.DLT', partition: 0, offset: 12,
      originalTopic: 'grassland.trust.events', messageKey: 'k-1', payload: '{"eventType":"X"}',
      errorSummary: 'NPE', status: 'pending', replayedAt: null, discardedAt: null, createdAt: null,
    }
    const { wrapper, calls } = await mountConsole([
      { match: '/api/ops/cases', data: [] },
      { match: '/api/ops/dlt', data: [pendingMsg, { ...pendingMsg, id: 'dlt-2', status: 'discarded' }] },
      { match: '/actions', data: { id: 'act-2', caseId: 'case-3', operationId: 'op-2',
        action: 'dlt_replay', status: 'succeeded', requestedBy: 'ops-b',
        outcome: 'replayed', error: null, createdAt: null, completedAt: null } },
    ])

    await wrapper.findAll('.ops-tab').find((b) => b.text() === '死信队列')!.trigger('click')
    await flushPromises()
    expect(calls.some((c) => c.url === '/api/ops/dlt')).toBe(true)

    const items = wrapper.findAll('.ops-item')
    expect(items).toHaveLength(2)
    expect(items[1].findAll('button')).toHaveLength(0)  // discarded：无动作

    await items[0].findAll('button').find((b) => b.text() === '重投原 topic')!.trigger('click')
    await flushPromises()

    const body = JSON.parse(calls.find((c) => c.url.includes('/api/ops/dlt/dlt-1/actions'))!.body!)
    expect(body.replay).toBe(true)
    expect(body.operationId).toMatch(/^dlt_replay-/)
    expect(wrapper.text()).toContain('已重投至 grassland.trust.events')
  })

  test('待判定视图展示 check 明细并支持带原因人工改判', async () => {
    const { wrapper, calls } = await mountConsole([
      { match: '/api/ops/cases', data: [] },
      { match: '/api/ops/pending-verifications', data: [{
        verificationId: 'v-1', submissionId: 's-1', applicationId: 'a-1', taskId: 't-1',
        taskTitle: '门店探店视频', organizationId: 'org-1', recommenderAccountId: 'rec-1',
        contentUrl: 'https://example.com/post', lastCheckedAt: '2026-08-02T02:00:00Z',
        submittedAt: '2026-08-02T01:00:00Z',
        checks: '[{"type":"ai_visual","status":"inconclusive","detail":"图片不足以判定"}]',
      }] },
      { match: '/api/ops/pending-verifications/s-1/override', data: {
        submissionId: 's-1', status: 'failed', reviewerAccountId: 'ops-1', reviewNote: '证据不足',
      } },
    ])

    await wrapper.findAll('.ops-tab').find((b) => b.text() === '待判定核验')!.trigger('click')
    await flushPromises()

    const panels = wrapper.findAll('.ops-panel')
    const panel = panels[panels.length - 1]
    expect(panel.text()).toContain('门店探店视频')
    expect(panel.text()).toContain('ai_visual')
    expect(panel.text()).toContain('图片不足以判定')
    expect(panel.find('input[placeholder="填写人工复核原因"]').exists()).toBe(true)
    expect(panel.text()).toContain('判定通过')
    expect(panel.text()).toContain('判定不通过')

    await panel.find('input[placeholder="填写人工复核原因"]').setValue('证据不足')
    await panel.findAll('button').find((b) => b.text() === '判定不通过')!.trigger('click')
    await flushPromises()

    const call = calls.find((item) => item.url.includes('/api/ops/pending-verifications/s-1/override'))
    expect(call?.method).toBe('POST')
    expect(JSON.parse(call?.body || '{}')).toEqual({ status: 'failed', note: '证据不足' })
  })

  test('坏 checks JSON 不炸 UI（后端字段是字符串，直接遍历会逐字符展开）', async () => {
    const { wrapper } = await mountConsole([
      { match: '/api/ops/cases', data: [] },
      { match: '/api/ops/pending-verifications', data: [{
        verificationId: 'v-2', submissionId: 's-2', applicationId: 'a-2', taskId: 't-2',
        taskTitle: '坏数据', organizationId: 'org-1', recommenderAccountId: 'rec-1',
        contentUrl: 'https://example.com/p', lastCheckedAt: null, submittedAt: null,
        checks: 'not-json',
      }] },
    ])

    await wrapper.findAll('.ops-tab').find((b) => b.text() === '待判定核验')!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('坏数据')
    expect(wrapper.findAll('.ops-checks li')).toHaveLength(0)
  })

  test('状态筛选把 status 带进查询串', async () => {
    const { wrapper, calls } = await mountConsole([{ match: '/api/ops/cases', data: [CASE_BLOCKED] }])

    await wrapper.find('.ops-filters select').setValue('resolved')
    await flushPromises()

    expect(calls.some((c) => c.url === '/api/ops/cases?status=resolved')).toBe(true)
  })

  test('来源筛选是前端过滤（同一次拉取内切换，不重复打后端）', async () => {
    const { wrapper, calls } = await mountConsole([
      { match: '/api/ops/cases', data: [CASE_BLOCKED, CASE_HELD_APPROVED] },
    ])
    const before = calls.length

    await wrapper.findAll('.ops-filters select')[1].setValue('settlement_held')
    await flushPromises()

    expect(calls.length).toBe(before)
    expect(wrapper.findAll('.ops-table tbody tr')).toHaveLength(1)
    expect(wrapper.find('.ops-table tbody').text()).toContain('结算暂缓')
  })

  test('merchant_rejection 处置单提供客服裁定快捷入口', async () => {
    const merchantRejection = {
      ...CASE_BLOCKED, id: 'case-mr', sourceKind: 'merchant_rejection', sourceRef: 'dispute-42',
      reason: 'merchant_contested_verified_work',
    }
    const { wrapper } = await mountConsole([
      { match: '/api/ops/cases', data: [merchantRejection] },
      { match: '/api/ops/cases/case-mr', data: { case: merchantRejection, audits: AUDITS, actions: [] } },
    ])

    expect(wrapper.text()).toContain('商家履约异议')
    await wrapper.find('.ops-table .ops-quiet').trigger('click')
    await flushPromises()
    await wrapper.findAll('button').find((button) => button.text() === '前往客服裁定')!.trigger('click')

    expect(wrapper.emitted('open-dispute')).toEqual([['dispute-42']])
  })
})
