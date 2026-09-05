import { describe, expect, it } from 'vitest'
import {
  KYB_VALIDATION_MESSAGES,
  isValidChineseIdCard,
  isValidEmail,
  isValidIdCard,
  isValidPhone,
  validateChineseIdCard,
  validateEmail,
  validateIdCard,
  validatePhone,
} from './kyb-validation'

const ID_CARD_WEIGHTS = [7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2] as const
const ID_CARD_CHECK_CODES = ['1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'] as const

function completeIdCard(body: string): string {
  const weightedSum = body.split('').reduce(
    (sum, digit, index) => sum + Number(digit) * ID_CARD_WEIGHTS[index],
    0,
  )
  return `${body}${ID_CARD_CHECK_CODES[weightedSum % 11]}`
}

describe('KYB phone validation', () => {
  it('accepts mainland mobile, landline, and service numbers', () => {
    expect(isValidPhone('13800138000')).toBe(true)
    expect(isValidPhone('19912345678')).toBe(true)
    expect(isValidPhone('010-12345678')).toBe(true)
    expect(isValidPhone('02112345678')).toBe(true)
    expect(isValidPhone('400-123-4567')).toBe(true)
    expect(isValidPhone('8001234567')).toBe(true)
    expect(isValidPhone(' 13800138000 ')).toBe(true)
  })

  it('rejects malformed numbers and treats empty values as invalid predicates', () => {
    expect(isValidPhone('12800138000')).toBe(false)
    expect(isValidPhone('1380013800')).toBe(false)
    expect(isValidPhone('010-123456')).toBe(false)
    expect(isValidPhone('010--12345678')).toBe(false)
    expect(isValidPhone('400-123-456')).toBe(false)
    expect(isValidPhone('800-1234-5678')).toBe(false)
    expect(isValidPhone('')).toBe(false)
    expect(isValidPhone(null)).toBe(false)
  })

  it('allows an omitted optional phone but can enforce required input', () => {
    expect(validatePhone('')).toBeNull()
    expect(validatePhone('   ')).toBeNull()
    expect(validatePhone('')).toBeNull()
    expect(validatePhone('', { required: true })).toBe(KYB_VALIDATION_MESSAGES.phoneRequired)
    expect(validatePhone('not-a-phone')).toBe(KYB_VALIDATION_MESSAGES.phoneInvalid)
  })
})

describe('KYB Chinese identity-card validation', () => {
  it('accepts a valid 18-digit card and lower-case x check code', () => {
    expect(isValidChineseIdCard('11010519491231002X')).toBe(true)
    expect(isValidChineseIdCard('11010519491231002x')).toBe(true)
    expect(isValidIdCard(' 11010519491231002X ')).toBe(true)
    expect(isValidChineseIdCard(completeIdCard('44030519900101001'))).toBe(true)
    expect(isValidChineseIdCard(completeIdCard('65010219900101001'))).toBe(true)
  })

  it('accepts historical county and province-aggregate codes via the province-prefix whitelist（任务书 #78 卡 F）', () => {
    // 已撤销县级码：县级白名单追不上历史区划，现按省级前缀放行（合法 18 位即过）。
    expect(isValidChineseIdCard(completeIdCard('11010319900101001'))).toBe(true)
    expect(isValidChineseIdCard(completeIdCard('31010319880615002'))).toBe(true)
    // 省级汇总/非常见县码：原县级白名单拒真，降级后放行。
    expect(isValidChineseIdCard('110000199001010013')).toBe(true)
    expect(isValidChineseIdCard('119999199001010012')).toBe(true)
    expect(isValidChineseIdCard(completeIdCard('44030019900101001'))).toBe(true)
  })

  it('rejects legacy 15-digit cards（任务书 #78 卡 F：只支持 18 位）', () => {
    expect(isValidChineseIdCard('110105491231002')).toBe(false)
    expect(isValidChineseIdCard('110103900101001')).toBe(false)
    expect(isValidChineseIdCard('110105491332002')).toBe(false)
    expect(isValidChineseIdCard('110105491231000')).toBe(false)
  })

  it('rejects bad checksum, date, province-external area code, and shape', () => {
    expect(isValidChineseIdCard('110105194912310021')).toBe(false)
    expect(isValidChineseIdCard('110105199902300022')).toBe(false)
    expect(isValidChineseIdCard('00000019491231002X')).toBe(false)
    expect(isValidChineseIdCard(completeIdCard('16010519491231002'))).toBe(false)
    expect(isValidChineseIdCard(completeIdCard('99010119900101001'))).toBe(false)
    expect(isValidChineseIdCard('11010519491231002')).toBe(false)
    expect(isValidChineseIdCard('')).toBe(false)
  })

  it('rejects future birth dates and zero sequence codes', () => {
    expect(isValidChineseIdCard(completeIdCard('11010520991231002'))).toBe(false)
    expect(isValidChineseIdCard(completeIdCard('11010519491231000'))).toBe(false)
  })

  it('allows an omitted masked-card replacement but can enforce required input', () => {
    expect(validateChineseIdCard('')).toBeNull()
    expect(validateChineseIdCard('', { required: true })).toBe(KYB_VALIDATION_MESSAGES.idCardRequired)
    expect(validateChineseIdCard('123')).toBe('请输入有效的身份证号（18 位）')
    expect(validateIdCard('123')).toBe(KYB_VALIDATION_MESSAGES.idCardInvalid)
  })
})

describe('KYB email validation', () => {
  it('accepts common email addresses and trims surrounding whitespace', () => {
    expect(isValidEmail('alice@example.com')).toBe(true)
    expect(isValidEmail('name+tag@sub.example.cn')).toBe(true)
    expect(isValidEmail('  support@example.co.uk ')).toBe(true)
  })

  it('rejects incomplete, malformed, and overlong addresses', () => {
    expect(isValidEmail('alice')).toBe(false)
    expect(isValidEmail('alice@example')).toBe(false)
    expect(isValidEmail('alice@@example.com')).toBe(false)
    expect(isValidEmail('.alice@example.com')).toBe(false)
    expect(isValidEmail('alice..smith@example.com')).toBe(false)
    expect(isValidEmail('alice@-example.com')).toBe(false)
    expect(isValidEmail('')).toBe(false)
  })

  it('allows an omitted optional email but can enforce required input', () => {
    expect(validateEmail('')).toBeNull()
    expect(validateEmail('', { required: true })).toBe(KYB_VALIDATION_MESSAGES.emailRequired)
    expect(validateEmail('bad-email')).toBe(KYB_VALIDATION_MESSAGES.emailInvalid)
  })
})
