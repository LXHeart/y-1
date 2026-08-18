import { describe, expect, test } from 'vitest'
import {
  buildCommissionLadderPayload,
  calculateCommissionPayoutCents,
  commissionLadderFormFromTask,
  emptyCommissionLadderForm,
  getCommissionLadderValidationError,
  parseConfirmedMetricValue,
  type CommissionLadderFormData,
} from './commission-ladder'

const validForm = (): CommissionLadderFormData => ({
  enabled: true,
  policyVersion: 'ladder-v1',
  metricKey: 'douyin.play_count',
  tiers: [
    { threshold: 50_000, payoutYuan: 100 },
    { threshold: 10_000, payoutYuan: 50 },
  ],
})

describe('commission ladder domain', () => {
  test('builds sorted cents and omits a disabled ladder', () => {
    expect(buildCommissionLadderPayload(validForm())).toEqual({
      policyVersion: 'ladder-v1',
      metricKey: 'douyin.play_count',
      tiers: [
        { threshold: 10_000, payoutCents: 5_000 },
        { threshold: 50_000, payoutCents: 10_000 },
      ],
    })
    expect(buildCommissionLadderPayload({ ...validForm(), enabled: false })).toBeUndefined()
  })

  test('hydrates the frozen policy version and converts cents to yuan', () => {
    expect(commissionLadderFormFromTask({
      policyVersion: 'legacy-v3',
      metricKey: 'xiaohongshu.like_count',
      tiers: [{ threshold: 100, payoutCents: 12_345 }],
    })).toEqual({
      enabled: true,
      policyVersion: 'legacy-v3',
      metricKey: 'xiaohongshu.like_count',
      tiers: [{ threshold: 100, payoutYuan: 123.45 }],
    })
    expect(emptyCommissionLadderForm().policyVersion).toBe('ladder-v1')
  })

  test.each([
    [{ ...validForm(), policyVersion: '' }, 10_000, 0, '策略版本异常'],
    [{ ...validForm(), metricKey: '播放量' }, 10_000, 0, '指标标识'],
    [{ ...validForm(), tiers: [] }, 10_000, 0, '至少配置一个'],
    [{ ...validForm(), tiers: [{ threshold: 1.5, payoutYuan: 1 }] }, 10_000, 0, '非负整数'],
    [{ ...validForm(), tiers: [{ threshold: 10, payoutYuan: 5 }, { threshold: 10, payoutYuan: 6 }] }, 10_000, 0, '不能重复'],
    [{ ...validForm(), tiers: [{ threshold: 10, payoutYuan: 6 }, { threshold: 20, payoutYuan: 5 }] }, 10_000, 0, '不能下降'],
    [validForm(), 9_999, 0, '不能超过任务赏金'],
    [validForm(), 10_000, 1, '不能与霸王餐'],
  ])('rejects an invalid ladder', (form, bountyCents, freebieDepositCents, message) => {
    expect(getCommissionLadderValidationError(
      form as CommissionLadderFormData,
      bountyCents as number,
      freebieDepositCents as number,
    )).toContain(message)
  })

  test('rejects fractional cents and unsafe confirmed metrics', () => {
    expect(getCommissionLadderValidationError({
      ...validForm(), tiers: [{ threshold: 1, payoutYuan: 1.001 }],
    }, 1_000, 0)).toContain('两位小数')
    expect(parseConfirmedMetricValue(String(Number.MAX_SAFE_INTEGER + 1))).toEqual({
      value: null, error: '实际指标必须是非负安全整数',
    })
  })

  test('selects the highest fixed payout without accumulating tiers', () => {
    const ladder = buildCommissionLadderPayload(validForm())!
    expect(calculateCommissionPayoutCents(ladder, 9_999)).toBe(0)
    expect(calculateCommissionPayoutCents(ladder, 10_000)).toBe(5_000)
    expect(calculateCommissionPayoutCents(ladder, 99_999)).toBe(10_000)
    expect(parseConfirmedMetricValue('50000')).toEqual({ value: 50_000, error: null })
  })
})
