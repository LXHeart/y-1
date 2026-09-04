// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import AdjudicationPanel from './AdjudicationPanel.vue'
import type { AdjudicationSnapshot } from '../types/grassland'

/**
 * 审判看板平票重开轮次的 UI 回归（UI 实测清单遗留项）。
 *
 * 浏览器实测（2026-07-26）时两个陪跑争议因窗口内 0 票进入 round 2，
 * 但未在 UI 上验证过「第 2 轮面板重抽 + 计票归零」——后端快照换轮后，
 * 看板必须呈现新轮次/新面板进度/归零计票，而不是残留上一轮的票数。
 */

const api = vi.hoisted(() => ({
  loading: { value: false },
  error: { value: '' },
  getAdjudication: vi.fn(),
  getMyJudgeStatus: vi.fn(),
  startAdjudication: vi.fn(),
  enrollAsJudge: vi.fn(),
  leaveJudgePool: vi.fn(),
  castVote: vi.fn(),
  appealDispute: vi.fn(),
  reauthenticate: vi.fn(),
  finalDecision: vi.fn(),
}))
vi.mock('../composables/useGrassland', () => ({ useGrassland: () => api }))

function snapshot(overrides: Partial<AdjudicationSnapshot> = {}): AdjudicationSnapshot {
  return {
    id: 'dispute-1',
    status: 'voting',
    round: 1,
    decision: null,
    appealState: null,
    finalDecision: null,
    decidedAt: null,
    panel: { size: 7, voted: 7 },
    tallies: { forMerchant: 3, forRecommender: 3, abstain: 1, panelSize: 7, majority: null },
    window: {
      phase: 'vote',
      durationSeconds: 86400,
      startedAt: '2026-08-21T02:00:00Z',
      deadline: '2026-08-22T02:00:00Z',
      remainingSeconds: 3600,
    },
    ...overrides,
  }
}

enableAutoUnmount(afterEach)

beforeEach(() => {
  vi.clearAllMocks()
  api.error.value = ''
  api.getMyJudgeStatus.mockResolvedValue(null)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

function tallyNumbers(wrapper: ReturnType<typeof mount>): string[] {
  return wrapper.findAll('.adj-tally-num').map((node) => node.text())
}

describe('AdjudicationPanel 平票重开下一轮', () => {
  test('round 1 平票呈现双方票数与「平票将重开下一轮」提示', async () => {
    api.getAdjudication.mockResolvedValue(snapshot())
    const wrapper = mount(AdjudicationPanel, { props: { disputeId: 'dispute-1' } })
    await flushPromises()

    expect(wrapper.text()).toContain('投票中')
    expect(wrapper.text()).toContain('7 人 / 已投 7')
    expect(tallyNumbers(wrapper)).toEqual(['3', '3', '1'])
    // 卡 C（D2 抢先 4/7 达票）后文案带多数门槛
    expect(wrapper.text()).toContain('尚无一方过半（4/7 多数即终局，平票将重开下一轮）')
    expect(wrapper.text()).not.toContain('已过半')
  })

  test('刷新到 round 2 快照：轮次/面板进度更新且计票归零，不残留上一轮票数', async () => {
    api.getAdjudication
      .mockResolvedValueOnce(snapshot())
      .mockResolvedValueOnce(snapshot({
        round: 2,
        panel: { size: 7, voted: 0 },
        tallies: { forMerchant: 0, forRecommender: 0, abstain: 0, panelSize: 7, majority: null },
        window: {
          phase: 'vote', durationSeconds: 86400, startedAt: '2026-08-21T04:00:00Z',
          deadline: '2026-08-22T04:00:00Z', remainingSeconds: 86400,
        },
      }))
    const wrapper = mount(AdjudicationPanel, { props: { disputeId: 'dispute-1' } })
    await flushPromises()

    await wrapper.get('.adj-refresh').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('7 人 / 已投 0')
    expect(tallyNumbers(wrapper)).toEqual(['0', '0', '0'])
    // 换轮后的新窗口倒计时必须重置（applySnapshot 同步 remainingSeconds）
    expect(wrapper.get('.adj-window-time').text()).toContain('剩余 1 天')
    expect(wrapper.text()).toContain('尚无一方过半（4/7 多数即终局，平票将重开下一轮）')
  })

  test('重开轮由系统触发启动时，通知文案带新轮次与面板人数', async () => {
    api.getAdjudication.mockResolvedValue(snapshot({ status: 'open', round: 0, panel: { size: 0, voted: 0 }, tallies: { forMerchant: 0, forRecommender: 0, abstain: 0, panelSize: 0, majority: null }, window: { phase: 'none', durationSeconds: 0, startedAt: null, deadline: null, remainingSeconds: null } }))
    api.startAdjudication.mockResolvedValue(snapshot({ round: 2 }))
    const wrapper = mount(AdjudicationPanel, { props: { disputeId: 'dispute-1' } })
    await flushPromises()

    // 卡 B 后「启动审判」按钮改为自愈入口文案（正常流程由系统在质证期满自动开庭）
    const startButton = wrapper.findAll('button').find((item) => item.text().includes('立即开庭'))!
    await startButton.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('审判已启动（第 2 轮，面板 7 人）')
    expect(wrapper.text()).toContain('7 人 / 已投 7')
  })
})
