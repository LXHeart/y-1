// @vitest-environment happy-dom
import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import EngagementNextAction from './EngagementNextAction.vue'
import type { ApplicationSettlement } from '../../../types/grassland'

const state: ApplicationSettlement = {
  applicationId: 'a', taskId: 't', confirmedAt: null, settlementEligibleAt: null,
  settlementStatus: 'not_confirmed', holdReason: null, allowedActions: [],
  nextActionLabel: '等待商家审稿', nextActionDueAt: '2026-09-08T01:00:00Z',
  blockedReason: '草稿获批后才可发布', benefitStatus: 'booked',
}
afterEach(() => vi.useRealTimers())
describe('服务端下一步展示', () => {
  it('按服务端文案展示并刷新倒计时，卸载释放计时器', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-09-08T00:00:00Z'))
    const clear = vi.spyOn(globalThis, 'clearInterval')
    const wrapper = mount(EngagementNextAction, { props: { state } })
    expect(wrapper.text()).toContain('等待商家审稿')
    expect(wrapper.text()).toContain('草稿获批后才可发布')
    expect(wrapper.text()).toContain('剩余 1 小时')
    expect(wrapper.text()).toContain('体验权益：已预约')
    await vi.advanceTimersByTimeAsync(3600000)
    expect(wrapper.text()).toContain('已到截止时间')
    wrapper.unmount()
    expect(clear).toHaveBeenCalledOnce()
    clear.mockRestore()
  })
  it('未定义截止时间时不推算业务期限', () => {
    const wrapper = mount(EngagementNextAction, { props: { state: { ...state, nextActionDueAt: null } } })
    expect(wrapper.find('time').exists()).toBe(false)
    wrapper.unmount()
  })
})
