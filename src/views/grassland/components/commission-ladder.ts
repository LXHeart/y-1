import type { CommissionLadder } from '../../../types/grassland'

export interface CommissionLadderFormTier {
  threshold: number
  payoutYuan: number
}

export interface CommissionLadderFormData {
  enabled: boolean
  policyVersion: string
  metricKey: string
  tiers: CommissionLadderFormTier[]
}

export interface ParsedConfirmedMetric {
  value: number | null
  error: string | null
}

export function emptyCommissionLadderForm(): CommissionLadderFormData {
  return {
    enabled: false,
    policyVersion: 'ladder-v1',
    metricKey: '',
    tiers: [{ threshold: 0, payoutYuan: 0 }],
  }
}

function centsFromYuan(value: number): number {
  return Math.round(value * 100)
}

function hasAtMostTwoDecimals(value: number): boolean {
  return Math.abs(value * 100 - Math.round(value * 100)) < 1e-8
}

export function buildCommissionLadderPayload(form: CommissionLadderFormData): CommissionLadder | undefined {
  if (!form.enabled) return undefined
  return {
    policyVersion: form.policyVersion.trim(),
    metricKey: form.metricKey.trim(),
    tiers: form.tiers
      .map((tier) => ({ threshold: tier.threshold, payoutCents: centsFromYuan(tier.payoutYuan) }))
      .sort((left, right) => left.threshold - right.threshold),
  }
}

export function commissionLadderFormFromTask(
  ladder: CommissionLadder | null | undefined,
): CommissionLadderFormData {
  if (!ladder) return emptyCommissionLadderForm()
  return {
    enabled: true,
    policyVersion: ladder.policyVersion,
    metricKey: ladder.metricKey,
    tiers: ladder.tiers.map((tier) => ({
      threshold: tier.threshold,
      payoutYuan: tier.payoutCents / 100,
    })),
  }
}

export function getCommissionLadderValidationError(
  form: CommissionLadderFormData,
  bountyCents: number,
  freebieDepositCents: number,
): string | null {
  if (!form.enabled) return null
  const policyVersion = form.policyVersion.trim()
  if (!policyVersion || policyVersion.length > 64) return '阶梯佣金策略版本异常，请重新配置'
  const metricKey = form.metricKey.trim()
  if (!metricKey || metricKey.length > 128 || !/^[a-zA-Z][a-zA-Z0-9_.-]*$/.test(metricKey)) {
    return '指标标识须以字母开头，且只能包含字母、数字、点、下划线或连字符'
  }
  if (form.tiers.length === 0) return '至少配置一个佣金档位'
  if (form.tiers.length > 20) return '最多配置 20 个佣金档位'
  if (bountyCents <= 0) return '阶梯佣金任务赏金必须大于 0'
  if (freebieDepositCents > 0) return '阶梯佣金不能与霸王餐押金同时启用'

  const tiers = [...form.tiers].sort((left, right) => left.threshold - right.threshold)
  for (const tier of tiers) {
    if (!Number.isSafeInteger(tier.threshold) || tier.threshold < 0) return '档位阈值必须是非负整数'
    if (!Number.isFinite(tier.payoutYuan) || tier.payoutYuan < 0) return '佣金金额不能为负数'
    if (!hasAtMostTwoDecimals(tier.payoutYuan)) return '佣金金额最多保留两位小数'
    if (!Number.isSafeInteger(centsFromYuan(tier.payoutYuan))) return '佣金金额超出安全范围'
  }
  for (let index = 1; index < tiers.length; index += 1) {
    if (tiers[index].threshold === tiers[index - 1].threshold) return '档位阈值不能重复'
    if (tiers[index].payoutYuan < tiers[index - 1].payoutYuan) return '佣金金额随档位升高不能下降'
  }
  if (centsFromYuan(tiers[tiers.length - 1].payoutYuan) > bountyCents) {
    return '最高档佣金不能超过任务赏金'
  }
  return null
}

export function parseConfirmedMetricValue(raw: string): ParsedConfirmedMetric {
  if (!raw.trim()) return { value: null, error: '请输入实际指标' }
  const value = Number(raw)
  if (!Number.isSafeInteger(value) || value < 0) {
    return { value: null, error: '实际指标必须是非负安全整数' }
  }
  return { value, error: null }
}

export function calculateCommissionPayoutCents(
  ladder: CommissionLadder,
  confirmedMetricValue: number,
): number {
  return [...ladder.tiers]
    .sort((left, right) => left.threshold - right.threshold)
    .reduce((payout, tier) => confirmedMetricValue >= tier.threshold ? tier.payoutCents : payout, 0)
}
