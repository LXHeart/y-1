// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import JudgeAdminPanel from './JudgeAdminPanel.vue'

/**
 * 任务书 #74 卡 E.8：审判官管理四子页签（准入/题库/考试记录/考核看板）。
 * 弹窗挂载带 teleport stub（happy-dom 铁律）。
 */

const fetchCalls: Array<{ url: string; init?: RequestInit }> = []

function jsonResponse(data: unknown, ok = true, status = 200) {
  return {
    ok,
    status,
    headers: { get: () => 'application/json' },
    json: async () => ({ success: ok, data }),
    text: async () => JSON.stringify({ success: ok, data }),
  }
}

const judgeRow = {
  id: 'judge-1',
  accountId: '11111111-1111-1111-1111-111111111111',
  organizationId: null,
  eligibilityTier: 4,
  active: true,
  opsAdmitted: true,
  version: 3,
  opsAdmittedAt: null,
  opsAdmittedBy: null,
  admissionLevel: 'probation',
  probation: true,
  examPassedAt: '2026-09-01T00:00:00Z',
  suspendedNow: false,
}

const questionRow = {
  id: 'q-1',
  category: '规则',
  question: '平票后案件如何处理？',
  options: ['进入下一轮', '直接终局'],
  answerIndex: 0,
  active: true,
  version: 2,
  createdAt: '2026-09-01T00:00:00Z',
}

const attemptRow = {
  id: 'a-1',
  accountId: '22222222-2222-2222-2222-222222222222',
  score: 90,
  passed: true,
  answers: JSON.stringify([
    { questionId: 'q-1', choiceIndex: 0, correct: true },
    { questionId: 'q-2', choiceIndex: 1, correct: true },
    { questionId: 'q-3', choiceIndex: 2, correct: false },
  ]),
  createdAt: '2026-09-02T00:00:00Z',
}

const assessmentData = {
  windowDays: 90,
  items: [
    { accountId: '33333333-3333-3333-3333-333333333333', assigned: 8, voted: 3, abstained: 5, abstainRate: 0.625, suggestSuspension: true, suspendedNow: false },
    { accountId: '44444444-4444-4444-4444-444444444444', assigned: 2, voted: 2, abstained: 0, abstainRate: 0, suggestSuspension: false, suspendedNow: true },
  ],
}

beforeEach(() => {
  fetchCalls.length = 0
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    fetchCalls.push({ url, init })
    if (url.startsWith('/api/admin/trust/judges?')) {
      return jsonResponse({ items: [{ ...judgeRow }], nextCursor: null, hasMore: false })
    }
    if (url === '/api/admin/trust/judges/11111111-1111-1111-1111-111111111111') {
      return jsonResponse({ ...judgeRow, audit: [] })
    }
    if (url.startsWith('/api/admin/trust/judge-exam/questions') && (!init || init.method === undefined || init.method === 'GET')) {
      return jsonResponse({ items: [{ ...questionRow }] })
    }
    if (url === '/api/admin/trust/judge-exam/questions' && init?.method === 'POST') {
      return jsonResponse({ ...questionRow, id: 'q-new', version: 0 })
    }
    if (url === '/api/admin/trust/judge-exam/questions/q-1' && init?.method === 'PUT') {
      return jsonResponse({ ...questionRow, version: 3 })
    }
    if (url === '/api/admin/trust/judge-exam/questions/q-1' && init?.method === 'DELETE') {
      return jsonResponse({ ...questionRow, active: false })
    }
    if (url === '/api/admin/trust/judge-exam/attempts') {
      return jsonResponse({ items: [{ ...attemptRow }, { ...attemptRow, id: 'a-2', score: 40, passed: false, answers: null }] })
    }
    if (url === '/api/admin/trust/judges/assessment') {
      return jsonResponse(assessmentData)
    }
    if (url.endsWith('/suspension') && init?.method === 'POST') {
      return jsonResponse({ accountId: '33333333-3333-3333-3333-333333333333', suspendedNow: true, suspendedUntil: '2026-10-04T00:00:00Z', suspensionReason: '弃权率过高' })
    }
    return jsonResponse({})
  }))
})

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

enableAutoUnmount(afterEach)

function mountPanel() {
  return mount(JudgeAdminPanel, {
    global: { stubs: { teleport: true } },
  })
}

describe('JudgeAdminPanel 四子页签', () => {
  test('默认准入管理：列表渲染见习/正式身份列', async () => {
    const wrapper = mountPanel()
    await flushPromises()

    expect(wrapper.find('[data-testid="judge-tab-admission"]').classes()).toContain('subtab-active')
    expect(wrapper.text()).toContain('审判官准入')
    expect(wrapper.text()).toContain('见习')
    expect(wrapper.findAll('tbody tr').length).toBeGreaterThanOrEqual(1)
  })

  test('题库管理：懒加载渲染选项预览与正确项', async () => {
    const wrapper = mountPanel()
    await flushPromises()

    await wrapper.find('[data-testid="judge-tab-questions"]').trigger('click')
    await flushPromises()

    expect(fetchCalls.some((call) => call.url.includes('/judge-exam/questions?activeOnly=false'))).toBe(true)
    expect(wrapper.text()).toContain('平票后案件如何处理？')
    expect(wrapper.text()).toContain('进入下一轮／直接终局')
  })

  test('新建题目：POST 载荷带 options 数组与 answerIndex', async () => {
    const wrapper = mountPanel()
    await flushPromises()
    await wrapper.find('[data-testid="judge-tab-questions"]').trigger('click')
    await flushPromises()

    await wrapper.find('[data-testid="question-create"]').trigger('click')
    await flushPromises()

    await (wrapper.find('[data-testid="question-form-category"]').setValue('职业操守'))
    await (wrapper.find('[data-testid="question-form-question"]').setValue('收到双方红包怎么办？'))
    const optionInputs = wrapper.findAll('.option-row input[type="text"]')
    await optionInputs[0].setValue('上报平台并回避')
    await optionInputs[1].setValue('悄悄收下')
    await wrapper.find('[data-testid="question-form-submit"]').trigger('click')
    await flushPromises()

    const post = fetchCalls.find((call) => call.url === '/api/admin/trust/judge-exam/questions' && call.init?.method === 'POST')
    expect(post).toBeDefined()
    expect(JSON.parse(String(post!.init!.body))).toEqual({
      category: '职业操守',
      question: '收到双方红包怎么办？',
      options: ['上报平台并回避', '悄悄收下'],
      answerIndex: 0,
    })
    expect(wrapper.text()).toContain('题目已创建')
  })

  test('编辑题目：PUT 带 expectedVersion 乐观锁', async () => {
    const wrapper = mountPanel()
    await flushPromises()
    await wrapper.find('[data-testid="judge-tab-questions"]').trigger('click')
    await flushPromises()

    const editBtn = wrapper.findAll('button').find((button) => button.text() === '编辑')!
    await editBtn.trigger('click')
    await flushPromises()

    await (wrapper.find('[data-testid="question-form-question"]').setValue('平票后案件如何处理？（修订）'))
    await wrapper.find('[data-testid="question-form-submit"]').trigger('click')
    await flushPromises()

    const put = fetchCalls.find((call) => call.url.endsWith('/judge-exam/questions/q-1') && call.init?.method === 'PUT')
    expect(put).toBeDefined()
    const body = JSON.parse(String(put!.init!.body)) as Record<string, unknown>
    expect(body.expectedVersion).toBe(2)
    expect(body.question).toBe('平票后案件如何处理？（修订）')
  })

  test('下线题目：DELETE 软删', async () => {
    const wrapper = mountPanel()
    await flushPromises()
    await wrapper.find('[data-testid="judge-tab-questions"]').trigger('click')
    await flushPromises()

    await wrapper.find('[data-testid="question-deactivate"]').trigger('click')
    await flushPromises()

    expect(fetchCalls.some((call) => call.url.endsWith('/judge-exam/questions/q-1') && call.init?.method === 'DELETE')).toBe(true)
    expect(wrapper.text()).toContain('题目已下线')
  })

  test('考试记录：及格/未及格与答题摘要', async () => {
    const wrapper = mountPanel()
    await flushPromises()
    await wrapper.find('[data-testid="judge-tab-attempts"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('及格')
    expect(wrapper.text()).toContain('未及格')
    expect(wrapper.text()).toContain('2/3 题正确')
  })

  test('考核看板：建议暂停标记 + 挂起需理由 + 挂起中渲染恢复', async () => {
    const wrapper = mountPanel()
    await flushPromises()
    await wrapper.find('[data-testid="judge-tab-assessment"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('建议暂停')
    expect(wrapper.text()).toContain('62.5%')
    expect(wrapper.text()).toContain('挂起中')
    // 挂起中的行渲染恢复按钮而非理由输入
    expect(wrapper.find('[data-testid="judge-reinstate"]').exists()).toBe(true)

    // 空理由提交被前端拦截
    await wrapper.find('[data-testid="judge-suspend"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('挂起须填写理由')

    // 填理由后提交
    await wrapper.find('[data-testid="suspension-reason"]').setValue('弃权率过高，运营确认暂停')
    await wrapper.find('[data-testid="judge-suspend"]').trigger('click')
    await flushPromises()

    const suspension = fetchCalls.find((call) => call.url.endsWith('/suspension') && call.init?.method === 'POST')
    expect(suspension).toBeDefined()
    expect(JSON.parse(String(suspension!.init!.body))).toEqual({ suspend: true, reason: '弃权率过高，运营确认暂停' })
    expect(wrapper.text()).toContain('审判官已挂起 30 天')
  })
})
