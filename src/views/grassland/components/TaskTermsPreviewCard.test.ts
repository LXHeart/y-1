// @vitest-environment happy-dom
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import TaskTermsPreviewCard from './TaskTermsPreviewCard.vue'
import type { TaskPreview } from '../../../types/grassland'

const preview: TaskPreview = {
  taskId: 'task-1', title: '合作', status: 'draft',
  what: { platform: 'xiaohongshu', contentForm: 'image', description: '内容要求', productServiceInfo: '门店体验',
    mustInclude: ['门店名称'], forbiddenContent: ['夸大宣传'], metricRequirements: ['播放量'], evidenceRequirements: ['发布链接'],
    publishStartAt: null, publishEndAt: null },
  delivery: { deliveryDeadlineDays: 5, source: 'contract', applicationDeadline: null },
  review: { required: true, reviewWindowHours: 72, reviseCap: 2, resubmitHours: 48, timeoutPolicy: '超时转人工' },
  payout: { mode: 'bounty', bountyCents: 10000, freebieDepositCents: 0, ladder: null,
    estimatedPayoutCents: 8765, maximumPayoutCents: 8765, withdrawableAfterConfirmSeconds: 0,
    withdrawablePolicy: '确认后至少 3 天，存在争议时暂缓结算' },
  cancel: { scriptBps: 1250, deliverableBps: 6000, publishedBps: 2000, source: 'contract', cap: '补偿上限为已保障金额' },
  highlights: ['图文合作'],
}

describe('合作条款预览', () => {
  it('展示服务端到手金额和时间文案，不用赏金或秒数重新推算', () => {
    const wrapper = mount(TaskTermsPreviewCard, { props: { preview } })
    expect(wrapper.get('[data-testid="preview-payout"]').text()).toBe('¥87.65')
    expect(wrapper.get('[data-testid="preview-withdrawable"]').text()).toBe(preview.payout.withdrawablePolicy)
    expect(wrapper.text()).toContain('最多退改 2 次')
    expect(wrapper.text()).toContain('48 小时内补交')
    expect(wrapper.text()).toContain('夸大宣传')
    expect(wrapper.text()).toContain('12.5%')
    wrapper.unmount()
  })

  it.each(['freebie', 'ladder', 'commerce'] as const)('%s 模板区分返还押金、档位和订单佣金', (mode) => {
    const wrapper = mount(TaskTermsPreviewCard, { props: { preview: { ...preview, payout: {
      ...preview.payout, mode, estimatedPayoutCents: mode === 'freebie' ? 3000 : null, maximumPayoutCents: 8000,
    } } } })
    const text = wrapper.text()
    if (mode === 'freebie') expect(text).toContain('达标返还押金')
    if (mode === 'ladder') expect(text).toContain('最高 ¥80.00')
    if (mode === 'commerce') expect(text).toContain('按订单冻结的套餐佣金结算')
    if (mode !== 'ladder') expect(wrapper.get('[data-testid="preview-cancel"]').text()).not.toContain('已确认脚本')
    wrapper.unmount()
  })
})
