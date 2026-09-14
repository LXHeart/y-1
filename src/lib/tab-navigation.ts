/** Shared keyboard behavior for horizontal tabs and responsive vertical rails. */
export function handleTabKeydown(event: KeyboardEvent): void {
  if (event.defaultPrevented || event.altKey || event.ctrlKey || event.metaKey) return
  if (!['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown', 'Home', 'End'].includes(event.key)) return
  const list = event.currentTarget
  const target = event.target
  if (!(list instanceof HTMLElement) || !(target instanceof HTMLElement)) return
  const current = target.closest<HTMLElement>('[role="tab"]')
  if (!current || current.closest('[role="tablist"]') !== list) return
  const tabs = [...list.querySelectorAll<HTMLElement>('[role="tab"]')].filter(tab =>
    tab.closest('[role="tablist"]') === list
    && !tab.matches(':disabled, [aria-disabled="true"]')
    && !tab.closest('[hidden], [inert]')
    && getComputedStyle(tab).display !== 'none'
    && getComputedStyle(tab).visibility !== 'hidden',
  )
  const index = tabs.indexOf(current)
  if (index < 0 || !tabs.length) return
  const direction = event.key === 'ArrowRight' || event.key === 'ArrowDown' ? 1 : -1
  const next = event.key === 'Home' ? 0 : event.key === 'End' ? tabs.length - 1
    : (index + direction + tabs.length) % tabs.length
  event.preventDefault()
  tabs[next]?.focus()
  tabs[next]?.click()
}
