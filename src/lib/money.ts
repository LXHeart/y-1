/**
 * 金额（分）→ 展示字符串。任务书 #29+#30 D8 共享件：推荐官收入统计与商家月度账单复用。
 *
 * ⚠️ 后端 `*Cents` 字段**本身带符号**（提现/冲正/释放为负），这里只负责格式化，
 * 绝不再按类型手工加/翻符号——那会双重变号。
 */

/** 千分位分隔（避免引入 Intl 依赖差异，输出稳定可测）。 */
function groupDigits(intPart: string): string {
  return intPart.replace(/\B(?=(\d{3})+(?!\d))/gu, ',')
}

/** 绝对值金额：cents → "1,234.56"。用于表头汇总等不关心符号的位置。 */
export function formatCents(cents: number): string {
  const abs = Math.abs(cents)
  const yuan = Math.floor(abs / 100)
  const rem = abs % 100
  return `${groupDigits(String(yuan))}.${String(rem).padStart(2, '0')}`
}

/** 带人民币符号的绝对值：cents → "¥1,234.56"。 */
export function formatYuan(cents: number): string {
  return `¥${formatCents(cents)}`
}

/**
 * 带符号金额：cents → "+1,234.56" / "-5.00" / "0.00"。
 * 用于流水/净变动等需要一眼看方向的列。0 不加正号。
 */
export function formatSignedCents(cents: number): string {
  if (cents > 0) return `+${formatCents(cents)}`
  if (cents < 0) return `-${formatCents(cents)}`
  return '0.00'
}
