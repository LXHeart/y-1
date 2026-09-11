import { computed } from 'vue'
import type { RouteLocationNormalizedLoaded, Router } from 'vue-router'

/**
 * 画布 URL 状态（任务书 #100 C100-04）：标准入口是 draft+storyboard 双参，
 * 旧 storyboard-only 深链保持可打开（绑定后由服务端唯一补关联）。
 * URL 只是定位器不是权限——跨账号草稿/分镜由服务端 404/409 拦截。
 */
export interface CanvasRouteKey {
  storyboard: string
  draft: string | null
}

/** 解析并归一当前路由的画布参数；无有效 storyboard 返回 null（视图显示空态）。 */
export function normalizeCanvasRoute(route: RouteLocationNormalizedLoaded | { query: Record<string, unknown> }):
CanvasRouteKey | null {
  const raw = route.query.storyboard
  const storyboard = typeof raw === 'string' ? raw.trim() : ''
  if (!storyboard) return null
  const rawDraft = route.query.draft
  const draft = typeof rawDraft === 'string' && rawDraft.trim() ? rawDraft.trim() : null
  return { storyboard, draft }
}

export function useVideoCanvasUrlState(route: RouteLocationNormalizedLoaded, router: Router) {
  const key = computed<CanvasRouteKey | null>(() => normalizeCanvasRoute(route))

  /**
   * 绑定成功后把 draft 回写进 URL（replace 不污染历史）；参数未变化时不动路由，
   * 避免恢复/激活循环里重复触发导航。storyboard 缺失时不写。
   */
  function syncDraft(draftId: string | null | undefined): void {
    const current = key.value
    if (!current || !draftId) return
    if (current.draft === draftId) return
    void router.replace({
      query: { ...route.query, storyboard: current.storyboard, draft: draftId },
    })
  }

  return { key, syncDraft }
}
