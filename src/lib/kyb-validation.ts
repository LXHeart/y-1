import { VALID_CHINESE_ID_AREA_CODES } from './kyb-id-area-codes'

/**
 * Client-side validation helpers for merchant KYB contact fields.
 *
 * The API deliberately separates the strict predicates (`isValid*`) from the
 * form-facing validators (`validate*`).  A contact field may be omitted in a
 * draft, so `validate*` treats an empty value as valid unless `required: true`
 * is supplied.  The predicates themselves always return `false` for an empty
 * value.
 */

export type KybValidationValue = string | null | undefined

export interface KybValidationOptions {
  /** Whether an empty value should produce a required-field error. */
  required?: boolean
}

/** Stable, user-facing messages used by the form validators. */
export const KYB_VALIDATION_MESSAGES = {
  phoneRequired: '请输入联系电话',
  phoneInvalid: '请输入有效的联系电话（11 位手机号或座机号码）',
  idCardRequired: '请输入法人身份证号',
  idCardInvalid: '请输入有效的身份证号（支持 18 位或 15 位证件）',
  emailRequired: '请输入联系邮箱',
  emailInvalid: '请输入有效的邮箱地址',
} as const

const MOBILE_PHONE_PATTERN = /^1[3-9]\d{9}$/u

// Mainland China landline: 0 + 2–3 digit area code + 7–8 digit number.
// A hyphen or a single space between the parts is accepted for readability.
const LANDLINE_PHONE_PATTERN = /^0\d{2,3}(?:[-\s]?\d{7,8})$/u

// Common 400/800 service numbers are telephone numbers too, and are often
// used as a merchant contact.  Both compact and hyphen/space-separated forms
// are accepted (400-123-4567, 4001234567, etc.).
const SERVICE_PHONE_PATTERN = /^(?:400|800)[-\s]?\d{3}[-\s]?\d{4}$/u

/**
 * Test a mainland Chinese mobile, landline, or common 400/800 service number.
 * Whitespace around the value is ignored; an empty value is not valid here.
 */
export function isValidPhone(value: KybValidationValue): boolean {
  const text = normalize(value)
  if (!text) return false
  return MOBILE_PHONE_PATTERN.test(text)
    || LANDLINE_PHONE_PATTERN.test(text)
    || SERVICE_PHONE_PATTERN.test(text)
}

/**
 * Return a form-facing phone validation message, or `null` when valid.
 * Contact phone is optional for a draft unless `required` is requested.
 */
export function validatePhone(
  value: KybValidationValue,
  options: KybValidationOptions = {},
): string | null {
  if (!normalize(value)) return options.required ? KYB_VALIDATION_MESSAGES.phoneRequired : null
  return isValidPhone(value) ? null : KYB_VALIDATION_MESSAGES.phoneInvalid
}

// Chinese resident identity-card checksum constants (GB 11643-1999).
const ID_CARD_WEIGHTS = [7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2] as const
const ID_CARD_CHECK_CODES = ['1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'] as const
/** Test a Chinese resident identity-card number (18-digit or legacy 15-digit). */
export function isValidChineseIdCard(value: KybValidationValue): boolean {
  const text = normalize(value).toUpperCase()
  if (!text) return false

  if (/^\d{15}$/u.test(text)) {
    // The legacy form stores a two-digit year and predates the 18-digit
    // checksum format.  Legacy cards were issued in the 1900s.
    return isValidIdAreaCode(text.slice(0, 6))
      && isValidBirthDate(1900 + Number(text.slice(6, 8)), Number(text.slice(8, 10)), Number(text.slice(10, 12)))
      && text.slice(12, 15) !== '000'
  }

  if (!/^\d{17}[0-9X]$/u.test(text)) return false
  if (!isValidIdAreaCode(text.slice(0, 6))) return false

  const year = Number(text.slice(6, 10))
  const month = Number(text.slice(10, 12))
  const day = Number(text.slice(12, 14))
  if (!isValidBirthDate(year, month, day) || text.slice(14, 17) === '000') return false

  let weightedSum = 0
  for (let index = 0; index < ID_CARD_WEIGHTS.length; index += 1) {
    weightedSum += Number(text[index]) * ID_CARD_WEIGHTS[index]
  }
  return ID_CARD_CHECK_CODES[weightedSum % 11] === text[17]
}

/**
 * Return a form-facing identity-card validation message, or `null` when valid.
 * An empty value is allowed for drafts when the server already stores a
 * masked identity number; callers can require it for a new profile.
 */
export function validateChineseIdCard(
  value: KybValidationValue,
  options: KybValidationOptions = {},
): string | null {
  if (!normalize(value)) return options.required ? KYB_VALIDATION_MESSAGES.idCardRequired : null
  return isValidChineseIdCard(value) ? null : KYB_VALIDATION_MESSAGES.idCardInvalid
}

// Keep the public alias concise for callers whose domain model calls this an
// "ID card number" rather than a Chinese resident identity card.
export const isValidIdCard = isValidChineseIdCard
export const validateIdCard = validateChineseIdCard

const EMAIL_LOCAL_PATTERN = /^[A-Z0-9.!#$%&'*+/=?^_`{|}~-]+$/iu
const EMAIL_DOMAIN_LABEL_PATTERN = /^[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?$/iu

/** Test a conventional internet email address suitable for a contact field. */
export function isValidEmail(value: KybValidationValue): boolean {
  const text = normalize(value)
  if (!text || text.length > 254) return false

  const atIndex = text.lastIndexOf('@')
  if (atIndex <= 0 || atIndex === text.length - 1) return false
  const local = text.slice(0, atIndex)
  const domain = text.slice(atIndex + 1)
  if (local.length > 64 || local.startsWith('.') || local.endsWith('.') || local.includes('..')) return false
  if (!EMAIL_LOCAL_PATTERN.test(local)) return false

  // Require a DNS-style domain with at least one dot and a non-empty TLD.
  // This catches common typing mistakes while still allowing subdomains and
  // IDN-looking labels (the latter are handled by the browser/server layer).
  const labels = domain.split('.')
  if (labels.length < 2 || labels.some((label) => !label || label.length > 63)) return false
  if (labels.some((label) => !EMAIL_DOMAIN_LABEL_PATTERN.test(label))) return false
  return labels[labels.length - 1].length >= 2
}

/** Return a form-facing email validation message, or `null` when valid. */
export function validateEmail(
  value: KybValidationValue,
  options: KybValidationOptions = {},
): string | null {
  if (!normalize(value)) return options.required ? KYB_VALIDATION_MESSAGES.emailRequired : null
  return isValidEmail(value) ? null : KYB_VALIDATION_MESSAGES.emailInvalid
}

function normalize(value: KybValidationValue): string {
  return typeof value === 'string' ? value.trim() : ''
}

function isValidIdAreaCode(value: string): boolean {
  return VALID_CHINESE_ID_AREA_CODES.has(value)
}

function isValidBirthDate(year: number, month: number, day: number): boolean {
  if (!Number.isInteger(year) || year < 1800 || year > 2099) return false
  if (!Number.isInteger(month) || !Number.isInteger(day) || month < 1 || month > 12 || day < 1) return false
  const date = new Date(Date.UTC(year, month - 1, day))
  if (date.getUTCFullYear() !== year
    || date.getUTCMonth() !== month - 1
    || date.getUTCDate() !== day) return false

  const today = new Date()
  const todayTimestamp = Date.UTC(today.getFullYear(), today.getMonth(), today.getDate())
  return date.getTime() <= todayTimestamp
}
