import { ref, watch } from 'vue'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import { normalizePlatformId } from '../../config/ai-platform-capabilities'
import type { CreationEntry } from '../../types/ai-creation'

/** 首页平台和热点入口在站内跳转、刷新与后退时恢复同一份创作上下文。 */
export function useWorkspaceEntry(route: RouteLocationNormalizedLoaded) {
  const entry = ref<CreationEntry | null>(null)
  let revision = 0
  watch([() => route.query.platform, () => route.query.entry, () => route.query.title, () => route.query.taskId, () => route.query.storeId], () => {
    if (route.query.taskId || route.query.storeId || route.query.entry === 'store') { entry.value = null; return }
    const platformId = typeof route.query.platform === 'string' ? normalizePlatformId(route.query.platform) : null
    const title = route.query.entry === 'hot' && typeof route.query.title === 'string' ? route.query.title.trim().slice(0, 200) : ''
    if (!platformId && !title) { entry.value = null; return }
    revision = Math.max(revision + 1, Date.now())
    entry.value = {
      revision, platformId, contentFormId: null,
      source: title ? { type: 'hot-topic', title } : { type: 'independent' },
      ...(title ? { prefill: { topic: title } } : {}),
    }
  }, { immediate: true })
  return entry
}
