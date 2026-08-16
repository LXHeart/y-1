import { describe, expect, it } from 'vitest'
import {
  compareMonths, currentMonth, expandMonths, formatMonthLabel, isValidMonth, shiftMonth,
} from './month'

describe('month param helpers', () => {
  it('validates YYYY-MM strictly', () => {
    expect(isValidMonth('2026-08')).toBe(true)
    expect(isValidMonth('2026-1')).toBe(false)
    expect(isValidMonth('2026-13')).toBe(false)
    expect(isValidMonth('2026-00')).toBe(false)
    expect(isValidMonth('26-08')).toBe(false)
    expect(isValidMonth('2026/08')).toBe(false)
    expect(isValidMonth('')).toBe(false)
  })

  it('formats current month with zero-padding', () => {
    expect(currentMonth(new Date(2026, 0, 15))).toBe('2026-01')
    expect(currentMonth(new Date(2026, 7, 17))).toBe('2026-08')
    expect(currentMonth(new Date(2026, 11, 1))).toBe('2026-12')
  })

  it('shifts forward and backward across year boundaries', () => {
    expect(shiftMonth('2026-08', 1)).toBe('2026-09')
    expect(shiftMonth('2026-12', 1)).toBe('2027-01')
    expect(shiftMonth('2026-01', -1)).toBe('2025-12')
    expect(shiftMonth('2026-08', -8)).toBe('2025-12')
    expect(shiftMonth('2026-08', 12)).toBe('2027-08')
  })

  it('expands an inclusive range into consecutive months', () => {
    expect(expandMonths('2026-06', '2026-08')).toEqual(['2026-06', '2026-07', '2026-08'])
    expect(expandMonths('2025-12', '2026-02')).toEqual(['2025-12', '2026-01', '2026-02'])
    expect(expandMonths('2026-08', '2026-08')).toEqual(['2026-08'])
  })

  it('returns empty for reversed or invalid ranges', () => {
    expect(expandMonths('2026-08', '2026-07')).toEqual([])
    expect(expandMonths('bad', '2026-08')).toEqual([])
  })

  it('compares months chronologically', () => {
    expect(compareMonths('2026-08', '2026-09')).toBe(-1)
    expect(compareMonths('2026-08', '2026-08')).toBe(0)
    expect(compareMonths('2027-01', '2026-12')).toBe(1)
  })

  it('renders a Chinese label', () => {
    expect(formatMonthLabel('2026-08')).toBe('2026 年 8 月')
    expect(formatMonthLabel('2026-12')).toBe('2026 年 12 月')
  })
})
