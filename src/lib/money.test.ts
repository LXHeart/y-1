import { describe, expect, it } from 'vitest'
import { formatCents, formatSignedCents, formatYuan, yuanToCents } from './money'

describe('money conversion', () => {
  it('converts yuan input to integer cents with rounding', () => {
    expect(yuanToCents(0)).toBe(0)
    expect(yuanToCents(1000)).toBe(100000)
    expect(yuanToCents(19.99)).toBe(1999)  // 19.99 * 100 = 1998.9999… 须四舍五入而非截断
    expect(yuanToCents(0.005)).toBe(1)
  })
})

describe('money formatting', () => {
  it('formats whole and fractional cents', () => {
    expect(formatCents(0)).toBe('0.00')
    expect(formatCents(5)).toBe('0.05')
    expect(formatCents(99)).toBe('0.99')
    expect(formatCents(100)).toBe('1.00')
    expect(formatCents(123456)).toBe('1,234.56')
  })

  it('treats input as absolute value (sign handled by caller)', () => {
    expect(formatCents(-500)).toBe('5.00')
  })

  it('prepends currency symbol', () => {
    expect(formatYuan(123456)).toBe('¥1,234.56')
  })

  it('signs non-zero values without double-negating', () => {
    expect(formatSignedCents(6200)).toBe('+62.00')
    expect(formatSignedCents(-2000)).toBe('-20.00')
    expect(formatSignedCents(0)).toBe('0.00')
  })

  it('groups thousands for large signed values', () => {
    expect(formatSignedCents(123456789)).toBe('+1,234,567.89')
  })
})
