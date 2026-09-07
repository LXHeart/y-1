import { computed, inject, onMounted, ref, watch } from 'vue'
import { routerKey } from 'vue-router'
import { useAuth } from '../../../composables/useAuth'
import {
  ADMIN_GROUPS, DEFAULT_TAB_ROLES, TAB_REGISTRY, TAB_ROLES,
  type AdminGroupId, type AdminSection, type AdminTabDef,
} from '../adminTabs'

/** 页签权限、分组记忆和 URL 同步共用一个状态源；登录门禁由 OpsApp 装配。 */
export function useAdminUrlState() {
  const { currentUser, hasBackendRole } = useAuth()
  const router = inject(routerKey, null)
  const lastSections = new Map<AdminGroupId, AdminSection>()

  function canSeeTab(key: AdminSection): boolean {
    if (!currentUser.value) return true
    return (TAB_ROLES[key] ?? DEFAULT_TAB_ROLES).some((role) => hasBackendRole(role))
  }
  function requestedTab(requested: unknown): AdminTabDef | undefined {
    return TAB_REGISTRY.find((tab) => tab.key === requested && canSeeTab(tab.key))
      ?? TAB_REGISTRY.find((tab) => canSeeTab(tab.key))
  }
  const requested = router?.currentRoute.value.query.section ?? new URLSearchParams(window.location.search).get('section')
  const activeSection = ref<AdminSection>(requestedTab(requested)?.key ?? 'kyb')
  const activeTabDef = computed(() => TAB_REGISTRY.find((tab) => tab.key === activeSection.value && canSeeTab(tab.key)) ?? null)
  const activeGroup = computed(() => activeTabDef.value?.group ?? 'review')
  const activeGroupDef = computed(() => ADMIN_GROUPS.find((group) => group.id === activeGroup.value) ?? null)
  const visibleGroups = computed(() => ADMIN_GROUPS.filter((group) =>
    TAB_REGISTRY.some((tab) => tab.group === group.id && canSeeTab(tab.key))))
  const visibleTabs = computed(() => TAB_REGISTRY.filter((tab) => tab.group === activeGroup.value && canSeeTab(tab.key)))

  function syncSection(section: AdminSection, replace = false): void {
    if (router) {
      if (router.currentRoute.value.query.section === section) return
      const target = { query: { ...router.currentRoute.value.query, section }, hash: router.currentRoute.value.hash }
      void (replace ? router.replace(target) : router.push(target))
      return
    }
    // 独立挂载的测试桩仍保留原有 history state，不破坏宿主历史栈。
    const url = new URL(window.location.href)
    url.searchParams.set('section', section)
    window.history.replaceState(window.history.state, '', url)
  }
  function selectTab(tab: AdminTabDef): void {
    if (!canSeeTab(tab.key)) return
    activeSection.value = tab.key
    syncSection(tab.key)
  }
  function selectGroup(group: AdminGroupId): void {
    const remembered = TAB_REGISTRY.find((tab) => tab.key === lastSections.get(group) && canSeeTab(tab.key))
    const first = remembered ?? TAB_REGISTRY.find((tab) => tab.group === group && canSeeTab(tab.key))
    if (first) selectTab(first)
  }

  watch(activeTabDef, (tab) => {
    if (tab) lastSections.set(tab.group, tab.key)
  }, { immediate: true, flush: 'sync' })
  onMounted(() => syncSection(activeSection.value, true))
  watch(() => router?.currentRoute.value.query.section, (next) => {
    const target = requestedTab(next)
    if (!target) return
    activeSection.value = target.key
    if (target.key !== next) syncSection(target.key, true)
  })
  watch(() => canSeeTab(activeSection.value), (visible) => {
    if (visible) return
    const first = requestedTab(null)
    if (first) {
      activeSection.value = first.key
      syncSection(first.key, true)
    }
  })
  return { activeSection, activeTabDef, activeGroup, activeGroupDef, visibleGroups, visibleTabs, selectTab, selectGroup }
}
