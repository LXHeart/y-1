import { nextTick, onBeforeUnmount, onMounted, watch, type Ref } from 'vue'

const dialogs: HTMLElement[] = []
const inertLocks = new Map<HTMLElement, { count: number; original: boolean }>()
let scrollLocks = 0
let originalOverflow = ''

/** One focus/scroll stack for shared modals and the login/credits shells. */
export function useDialogFocus(dialog: Ref<HTMLElement | null>, options: {
  close: () => void
  persistent?: () => boolean
  trapFocus?: () => boolean
}): void {
  let active: HTMLElement | null = null
  let returnFocus: HTMLElement | null = null
  let background: HTMLElement[] = []
  let trapped = false
  const topmost = () => active !== null && dialogs[dialogs.length - 1] === active

  function focusables(): HTMLElement[] {
    return [...(active?.querySelectorAll<HTMLElement>('button,input,textarea,select,a[href],summary,[tabindex],[contenteditable="true"]') ?? [])]
      .filter(element => {
        const nativeSummary = element.tagName === 'SUMMARY' && !element.hasAttribute('tabindex')
        if ((element.tabIndex < 0 && !nativeSummary) || element.matches(':disabled, input[type="hidden"]') || element.closest('[hidden],[inert],[aria-hidden="true"]')) return false
        for (let parent: HTMLElement | null = element; parent && parent !== active; parent = parent.parentElement) {
          const style = getComputedStyle(parent)
          if (style.display === 'none' || style.visibility === 'hidden') return false
          if (parent instanceof HTMLDetailsElement && !parent.open && !parent.querySelector(':scope > summary')?.contains(element)) return false
        }
        return true
      })
  }

  function focusIn(event: FocusEvent): void {
    if (trapped && topmost() && !active?.contains(event.target as Node)) (focusables()[0] ?? active)?.focus()
  }

  function keydown(event: KeyboardEvent): void {
    if (!topmost() || event.defaultPrevented) return
    if (event.key === 'Escape') {
      event.preventDefault()
      event.stopImmediatePropagation()
      if (!options.persistent?.()) options.close()
      return
    }
    if (!trapped || event.key !== 'Tab') return
    const items = focusables(), index = items.indexOf(document.activeElement as HTMLElement)
    if (!items.length) { event.preventDefault(); active?.focus(); return }
    if (event.shiftKey && index <= 0) {
      event.preventDefault(); items[items.length - 1]?.focus()
    } else if (!event.shiftKey && (index === -1 || index === items.length - 1)) {
      event.preventDefault(); items[0]?.focus()
    }
  }

  function release(): void {
    if (!active) return
    const restore = trapped && topmost(), trigger = returnFocus
    const index = dialogs.indexOf(active)
    if (index >= 0) dialogs.splice(index, 1)
    window.removeEventListener('keydown', keydown)
    document.removeEventListener('focusin', focusIn)
    for (const element of background) {
      const lock = inertLocks.get(element)
      if (lock && --lock.count === 0) { element.inert = lock.original; inertLocks.delete(element) }
    }
    if (trapped && --scrollLocks === 0) document.body.style.overflow = originalOverflow
    background = []; active = null; trapped = false
    if (restore) void nextTick(() => {
      const remaining = dialogs[dialogs.length - 1], focused = document.activeElement
      if (focused?.isConnected && focused !== document.body && focused !== document.documentElement) return
      if (trigger?.isConnected && !trigger.closest('[inert]') && (!remaining || remaining.contains(trigger))) trigger.focus()
    })
  }

  function activate(): void {
    if (dialog.value === active) return
    release()
    if (!dialog.value) return
    active = dialog.value
    returnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
    trapped = options.trapFocus?.() ?? true
    dialogs.push(active)
    if (trapped) {
      background = [...document.body.children].filter((element): element is HTMLElement =>
        element instanceof HTMLElement && !element.contains(active))
      for (const element of background) {
        const lock = inertLocks.get(element) ?? { count: 0, original: element.inert }
        lock.count += 1; inertLocks.set(element, lock); element.inert = true
      }
      if (scrollLocks++ === 0) { originalOverflow = document.body.style.overflow; document.body.style.overflow = 'hidden' }
    }
    window.addEventListener('keydown', keydown)
    document.addEventListener('focusin', focusIn)
    void nextTick(() => { if (trapped && topmost()) (focusables()[0] ?? active)?.focus() })
  }

  onMounted(activate)
  watch(dialog, activate, { flush: 'post' })
  onBeforeUnmount(release)
}
