import { computed, onScopeDispose, ref, watch } from 'vue'

export type CanvasDetail = 'shot' | 'reference' | 'assistant' | 'variants' | 'delivery'
/** Layout state only. Business drafts and the decision to leave stay in the project session. */
export function useCanvasResponsive(options: { beforeChange: () => Promise<boolean>; identity: () => string }) {
  const mobileQuery = window.matchMedia('(max-width: 767px)')
  const desktopQuery = window.matchMedia('(min-width: 1024px)')
  const wideQuery = window.matchMedia('(min-width: 1440px)')
  const mobile = ref(mobileQuery.matches); const desktop = ref(desktopQuery.matches)
  const viewMode = ref<'list' | 'canvas'>(mobile.value ? 'list' : 'canvas')
  const activeDetail = ref<CanvasDetail>('shot')
  const detailOpen = ref(desktop.value); const assetsOpen = ref(desktop.value && wideQuery.matches)
  let generation = 0
  const sync = () => {
    const wasDesktop = desktop.value; const wasMobile = mobile.value
    mobile.value = mobileQuery.matches; desktop.value = desktopQuery.matches
    if (mobile.value !== wasMobile) viewMode.value = mobile.value ? 'list' : 'canvas'
    if (desktop.value !== wasDesktop) { detailOpen.value = desktop.value; assetsOpen.value = desktop.value && wideQuery.matches }
    if (desktop.value && !wideQuery.matches) assetsOpen.value = false
  }
  for (const query of [mobileQuery, desktopQuery, wideQuery]) query.addEventListener('change', sync)
  onScopeDispose(() => { generation++; for (const query of [mobileQuery, desktopQuery, wideQuery]) query.removeEventListener('change', sync) })
  watch(options.identity, () => { generation++; activeDetail.value = 'shot'; detailOpen.value = desktop.value; assetsOpen.value = desktop.value && wideQuery.matches })
  function revealDetail(mode: CanvasDetail): void {
    activeDetail.value = mode; detailOpen.value = true
    if (!desktop.value) assetsOpen.value = false
  }
  async function openDetail(mode: CanvasDetail): Promise<boolean> {
    const ticket = generation
    if (mode !== activeDetail.value && !(await options.beforeChange())) return false
    if (ticket !== generation) return false
    revealDetail(mode)
    return true
  }
  async function toggleAssets(): Promise<void> {
    const ticket = generation
    if (!(await options.beforeChange()) || ticket !== generation) return
    assetsOpen.value = !assetsOpen.value
    if (!desktop.value && assetsOpen.value) detailOpen.value = false
  }
  async function setView(mode: 'list' | 'canvas'): Promise<void> {
    if (await options.beforeChange()) viewMode.value = mode
  }
  const detailTitle = computed(() => ({ shot: '镜头详情', reference: '参考详情', assistant: 'AI 助手', variants: '独立方案', delivery: '交付与导出' })[activeDetail.value])
  return { mobile, desktop, viewMode, activeDetail, detailOpen, assetsOpen, detailTitle, openDetail, revealDetail, toggleAssets, setView }
}
