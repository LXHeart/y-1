import { computed, type ComputedRef } from 'vue'
import {
  CHINA_REGIONS,
  getCitiesByProvince,
  getDistrictsByCity,
} from '../../constants/china-regions'
import type { Industry } from '../../types/grassland'

/** 任务书 #68 卡 F：MerchantKybCard 三域 composable 的共享工具（自卡片 script 迁出，逐字符保真）。 */

export { CHINA_REGIONS }

// 状态映射
export const statusLabels: Record<string, string> = {
  draft: '草稿',
  pending: '待审核',
  under_review: '审核中',
  approved: '已通过',
  rejected: '已拒绝',
  active: '启用',
  inactive: '停用',
}

export const accountTypeLabels: Record<string, string> = {
  bank_card: '银行卡',
  alipay: '支付宝',
  wechat: '微信',
}

export const INDUSTRY_OPTIONS: ReadonlyArray<{ value: Industry; label: string }> = [
  { value: 'catering', label: '餐饮' },
  { value: 'retail', label: '零售' },
  { value: 'beauty', label: '美业' },
  { value: 'education', label: '教育培训' },
  { value: 'e_commerce', label: '电商' },
  { value: 'healthcare', label: '医疗健康' },
  { value: 'finance', label: '金融服务' },
  { value: 'real_estate', label: '房地产' },
  { value: 'travel', label: '旅游' },
  { value: 'children', label: '母婴儿童' },
  { value: 'other', label: '其他' },
]

export const BUSINESS_TYPE_OPTIONS: ReadonlyArray<{ value: string; label: string }> = [
  { value: 'individual', label: '个体工商户' },
  { value: 'sole_proprietorship', label: '个人独资企业' },
  { value: 'partnership', label: '合伙企业' },
  { value: 'llc', label: '有限责任公司' },
  { value: 'corp', label: '股份有限公司' },
  { value: 'company', label: '公司' },
]

export function optionsWithCurrentValue(
  options: ReadonlyArray<{ value: string; label: string }>,
  currentValue: string,
): ReadonlyArray<{ value: string; label: string }> {
  if (!currentValue || options.some((option) => option.value === currentValue)) return options
  return [{ value: currentValue, label: `${currentValue}（已保存）` }, ...options]
}

export function parseAddress(value: unknown): Record<string, string> {
  if (!value) return {}
  // 后端当前返回 jsonb 文本，但兼容网关/旧客户端已经解码的对象，避免
  // 回填时把可用的省市区静默清空。
  if (typeof value === 'object' && !Array.isArray(value)) {
    return value as Record<string, string>
  }
  if (typeof value !== 'string') return {}
  try {
    const parsed = JSON.parse(value) as unknown
    return parsed !== null && typeof parsed === 'object' ? parsed as Record<string, string> : {}
  } catch {
    return {}
  }
}

/**
 * 地址级联选项（值使用行政区中文全称，与现有 JSON 地址契约保持一致）。
 * merchant/store 两域各调一次，传入各自表单的 getter。
 */
export function buildRegionCascade(
  getProvince: () => string,
  getCity: () => string,
  getDistrict: () => string,
): {
  provinceOptions: ComputedRef<ReadonlyArray<{ value: string; label: string }>>
  cityOptions: ComputedRef<ReadonlyArray<{ value: string; label: string }>>
  districtOptions: ComputedRef<ReadonlyArray<{ value: string; label: string }>>
} {
  const provinceOptions = computed(() => optionsWithCurrentValue(CHINA_REGIONS, getProvince()))
  const cityOptions = computed(() => optionsWithCurrentValue(
    getCitiesByProvince(getProvince()),
    getCity(),
  ))
  const districtOptions = computed(() => optionsWithCurrentValue(
    getDistrictsByCity(getProvince(), getCity()),
    getDistrict(),
  ))
  return { provinceOptions, cityOptions, districtOptions }
}
