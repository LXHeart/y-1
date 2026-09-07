import { nextTick, onMounted, onUnmounted, ref, watch, type Ref } from 'vue'
import type { AdminSection, AdminTabDef } from '../adminTabs'

/** 侧栏交互与焦点生命周期独立于业务面板和 URL 状态。 */
export function useAdminNavigation(
  activeSection: Ref<AdminSection>, visibleTabs: Ref<readonly AdminTabDef[]>, activate: (tab: AdminTabDef) => void,
) {
  const navigationOpen = ref(false)
  const navigationTrigger = ref<HTMLButtonElement | null>(null)
  const navigationSidebar = ref<HTMLElement | null>(null)
  let desktop: MediaQueryList | undefined

  function closeNavigation(): void {
    if (!navigationOpen.value) return
    navigationOpen.value = false
    void nextTick(() => navigationTrigger.value?.focus())
  }
  function handleNavigationKeydown(event: KeyboardEvent): void {
    if (!navigationOpen.value) return
    if (event.key === 'Escape') closeNavigation()
    if (event.key !== 'Tab') return
    const focusable = navigationSidebar.value?.querySelectorAll<HTMLElement>('button:not([tabindex="-1"])')
    if (!focusable?.length) return
    const first = focusable[0]
    const last = focusable[focusable.length - 1]
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus() }
    if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
  }
  function handleTabKeydown(event: KeyboardEvent): void {
    if (!['ArrowUp', 'ArrowDown', 'Home', 'End'].includes(event.key)) return
    event.preventDefault()
    const tabs = visibleTabs.value
    const index = tabs.findIndex((tab) => tab.key === activeSection.value)
    const next = event.key === 'Home' ? 0 : event.key === 'End' ? tabs.length - 1
      : (index + (event.key === 'ArrowDown' ? 1 : -1) + tabs.length) % tabs.length
    const tab = tabs[next]
    if (!tab) return
    activate(tab)
    void nextTick(() => document.getElementById(`admin-link-${tab.key}`)?.focus())
  }
  function handleViewportChange(): void {
    if (desktop?.matches) closeNavigation()
  }
  watch(navigationOpen, (open) => {
    if (open) void nextTick(() => navigationSidebar.value?.querySelector<HTMLButtonElement>('button')?.focus())
  })
  onMounted(() => {
    document.addEventListener('keydown', handleNavigationKeydown)
    desktop = window.matchMedia('(min-width: 768px)')
    desktop.addEventListener('change', handleViewportChange)
  })
  onUnmounted(() => {
    document.removeEventListener('keydown', handleNavigationKeydown)
    desktop?.removeEventListener('change', handleViewportChange)
  })
  return { navigationOpen, navigationTrigger, navigationSidebar, closeNavigation, handleTabKeydown }
}
