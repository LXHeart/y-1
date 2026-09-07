/**
 * 治理台面板共享格式化函数（任务书 #91 A2 自 AdminView.vue 迁出；纯搬运）。
 * 注：A2 卡明确指定本文件，但未列入 §9.1 新建清单——按卡级授权执行，偏差记录在卡报告。
 */

export function formatDate(iso: string): string {
  const d = new Date(iso)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

export function formatDateTime(iso: string | null): string {
  if (!iso) return '-'
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return '-'
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
  }).format(date)
}

export function isOverdue(iso: string | null): boolean {
  return Boolean(iso && new Date(iso).getTime() < Date.now())
}

export function formatStructured(raw: string | null): string {
  if (!raw) return '-'
  try {
    const value = JSON.parse(raw) as unknown
    if (Array.isArray(value)) {
      return value.map((item) => {
        if (!item || typeof item !== 'object') return String(item)
        const row = item as Record<string, unknown>
        return [row.dayOfWeek ? `周${row.dayOfWeek}` : null, row.openTime, row.closeTime]
          .filter(Boolean).join(' ')
      }).join('；') || '-'
    }
    if (value && typeof value === 'object') {
      const row = value as Record<string, unknown>
      return ['province', 'city', 'district', 'address']
        .map((key) => row[key]).filter((item) => typeof item === 'string' && item).join(' ') || raw
    }
    return String(value)
  } catch {
    return raw
  }
}

export function formatBytes(value: number | null): string {
  if (value == null || value < 0) return '-'
  if (value < 1024) return `${value} B`
  return `${(value / 1024).toFixed(value < 10240 ? 1 : 0)} KB`
}
