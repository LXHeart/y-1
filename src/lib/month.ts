/**
 * 月份参数（YYYY-MM）工具。任务书 #29+#30 D8 共享件：推荐官收入统计与商家月度账单复用。
 *
 * <p>口径（D2）：月份是**北京时间自然月**的字符串标记，前端只生成/展示 YYYY-MM，
 * 不传时间戳；具体 [start,end) 展开在后端做。这里所有运算按「日历月」推进，与时区无关。
 */

const MONTH_PATTERN = /^\d{4}-(0[1-9]|1[0-2])$/u

/** 是否合法的 YYYY-MM。 */
export function isValidMonth(value: string): boolean {
  return MONTH_PATTERN.test(value)
}

/** 当前月（本机日历，YYYY-MM）。 */
export function currentMonth(now: Date = new Date()): string {
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`
}

/** 解析 YYYY-MM 为 {year, month}；非法抛错（调用方先用 isValidMonth 守卫）。 */
function parse(month: string): { year: number; month: number } {
  if (!isValidMonth(month)) throw new Error(`invalid month: ${month}`)
  const [y, m] = month.split('-').map(Number)
  return { year: y, month: m }
}

/** month 偏移 delta 个月（可负），返回 YYYY-MM。 */
export function shiftMonth(month: string, delta: number): string {
  const { year, month: m } = parse(month)
  const total = year * 12 + (m - 1) + delta
  const ny = Math.floor(total / 12)
  const nm = (total % 12) + 1
  return `${ny}-${String(nm).padStart(2, '0')}`
}

/** from..to（含端）展开为连续月份数组；from 晚于 to 或非法返回空数组。 */
export function expandMonths(from: string, to: string): string[] {
  if (!isValidMonth(from) || !isValidMonth(to)) return []
  if (compareMonths(from, to) > 0) return []
  const months: string[] = []
  let cursor = from
  // 上限保护：收入统计跨度 ≤12 个月，给 24 兜底防死循环。
  for (let i = 0; i < 24; i += 1) {
    months.push(cursor)
    if (cursor === to) break
    cursor = shiftMonth(cursor, 1)
  }
  return months
}

/** 比较两个 YYYY-MM：a<b → -1，a==b → 0，a>b → 1。 */
export function compareMonths(a: string, b: string): number {
  const pa = parse(a)
  const pb = parse(b)
  const ta = pa.year * 12 + pa.month
  const tb = pb.year * 12 + pb.month
  return Math.sign(ta - tb)
}

/** 展示用中文标签："2026-08" → "2026 年 8 月"。 */
export function formatMonthLabel(month: string): string {
  const { year, month: m } = parse(month)
  return `${year} 年 ${m} 月`
}
