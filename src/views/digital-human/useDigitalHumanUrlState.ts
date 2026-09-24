/**
 * 数字人工作台 URL 状态（任务书 #105E C105E-01 / 共享契约 K11、K13.4）。
 *
 * 只承载三项公开 ID 状态（profileId/sessionId/view）；票据、正文、媒体签名等敏感值
 * 一律不解析、不传播、不持久（TC105E-01-02）。写入用 `router.replace`——字幕/选择
 * 变化不制造历史条目；解析只接受严格小写 UUID 与固定 view 枚举，数组（重复 query）/
 * 空/非法值直接丢弃，不从 storage 恢复。
 */
import { ref, watch, type Ref } from 'vue'
import type { RouteLocationNormalizedLoaded, Router } from 'vue-router'

export type DigitalHumanView = 'workbench' | 'history'

/** URL 只保留的公开状态；无任何票据/正文字段。 */
export interface DigitalHumanSelection {
  profileId: string | null
  sessionId: string | null
  view: DigitalHumanView
}

/** K01 Id=小写标准 UUID（版本 1-5 + variant 位），大写/无连写/花括号等非规范形态拒绝。 */
const STRICT_UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/

export function isStrictUuid(value: unknown): value is string {
  return typeof value === 'string' && STRICT_UUID.test(value)
}

function parseUuid(query: Record<string, unknown>, key: string): string | null {
  const raw = query[key]
  // 重复 query（数组）/空串/非严格 UUID → 不进选择状态（TC105E-01-02 参数化）。
  if (Array.isArray(raw) || typeof raw !== 'string') return null
  return isStrictUuid(raw) ? raw : null
}

function parseView(query: Record<string, unknown>): DigitalHumanView {
  // view 只认 history；其它值（含数组/空）回落 workbench，不把未知值写回 URL。
  return query.view === 'history' ? 'history' : 'workbench'
}

export function parseSelection(query: Record<string, unknown>): DigitalHumanSelection {
  return {
    profileId: parseUuid(query, 'profile'),
    sessionId: parseUuid(query, 'session'),
    view: parseView(query),
  }
}

export function useDigitalHumanUrlState(
  route: RouteLocationNormalizedLoaded,
  router: Router,
): {
  selection: Readonly<Ref<DigitalHumanSelection>>
  replaceSelection: (next: DigitalHumanSelection) => Promise<void>
} {
  const selection = ref<DigitalHumanSelection>(parseSelection(route.query))

  // 浏览器返回/前进或外部深链改写地址栏 → 只重解析（不产生新历史条目）。
  watch(() => route.query, (query) => {
    const next = parseSelection(query)
    if (next.profileId !== selection.value.profileId
      || next.sessionId !== selection.value.sessionId
      || next.view !== selection.value.view) {
      selection.value = next
    }
  })

  async function replaceSelection(next: DigitalHumanSelection): Promise<void> {
    selection.value = next
    // 重建 query 只含三项公开键：未知 query（token/prompt 等）不随本次写回传播。
    const query: Record<string, string> = {}
    if (next.profileId) query.profile = next.profileId
    if (next.sessionId) query.session = next.sessionId
    if (next.view === 'history') query.view = 'history'
    await router.replace({ query })
  }

  return { selection, replaceSelection }
}
