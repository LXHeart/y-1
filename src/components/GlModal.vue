<template>
  <Teleport to="body">
    <div class="modal-overlay" data-testid="gl-modal-overlay" @mousedown.self="onOverlay">
      <div ref="dialog" class="modal-card" :class="{ 'modal-card--wide': wide, 'modal-card--managed': trapFocus, 'studio-panel': trapFocus }" role="dialog" aria-modal="true" :aria-label="title" :tabindex="trapFocus ? -1 : undefined">
        <header class="modal-header">
          <h3 class="modal-title">{{ title }}</h3>
          <button type="button" class="modal-close" aria-label="关闭弹窗" data-action="close-modal"
                  @click="emit('close')">×</button>
        </header>
        <div class="modal-body" :class="{ 'modal-body--scroll': scroll }">
          <slot />
        </div>
        <footer v-if="$slots.actions" class="modal-actions modal-card__footer">
          <slot name="actions" />
        </footer>
      </div>
    </div>
  </Teleport>
</template>
<script lang="ts">
// All instances share the stack so Escape only acts on the frontmost dialog.
const modalStack: HTMLElement[] = []
const inertLocks = new Map<HTMLElement, { count: number; original: boolean }>()
</script>
<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'

/** 哑组件：只管壳（Teleport/遮罩/Esc/宽窄档），确认逻辑与数据全在调用方。 */
const props = withDefaults(defineProps<{
  title: string
  wide?: boolean
  scroll?: boolean
  /** true 时点遮罩与 Esc 不关闭（防误触丢编辑中的表单，如 draft 单价表）。 */
  persistent?: boolean
  /** Enable shared focus management for migrated flows; existing custom focus managers remain unchanged. */
  trapFocus?: boolean
}>(), { wide: false, scroll: false, persistent: false, trapFocus: false })

const emit = defineEmits<{ close: [] }>()
const dialog = ref<HTMLElement | null>(null)
let returnFocus: HTMLElement | null = null
let background: HTMLElement[] = []

function isTopmost(): boolean { return modalStack[modalStack.length - 1] === dialog.value }

function focusables(): HTMLElement[] {
  return [...(dialog.value?.querySelectorAll<HTMLElement>('button,input,textarea,select,a[href],[tabindex],[contenteditable="true"]') ?? [])]
    .filter(element => {
      if (element.tabIndex < 0 || element.matches(':disabled') || element.closest('[hidden],[inert],[aria-hidden="true"]')) return false
      for (let parent: HTMLElement | null = element; parent && parent !== dialog.value; parent = parent.parentElement) {
        const style = getComputedStyle(parent)
        if (style.display === 'none' || style.visibility === 'hidden') return false
      }
      return true
    })
}

function onFocusIn(event: FocusEvent): void {
  if (props.trapFocus && isTopmost() && !dialog.value?.contains(event.target as Node)) {
    (focusables()[0] ?? dialog.value)?.focus()
  }
}

function onOverlay(): void {
  if (!props.persistent) emit('close')
}

function onKeydown(event: KeyboardEvent): void {
  if (!isTopmost() || event.defaultPrevented) return
  if (event.key === 'Escape') {
    event.preventDefault()
    event.stopImmediatePropagation()
    if (!props.persistent) emit('close')
    return
  }
  if (!props.trapFocus || event.key !== 'Tab') return
  const items = focusables()
  const index = items.indexOf(document.activeElement as HTMLElement)
  if (!items.length) { event.preventDefault(); dialog.value?.focus(); return }
  if (event.shiftKey && index <= 0) {
    event.preventDefault(); items[items.length - 1]?.focus()
  } else if (!event.shiftKey && (index === -1 || index === items.length - 1)) {
    event.preventDefault(); items[0]?.focus()
  }
}

onMounted(async () => {
  if (!dialog.value) return
  returnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
  modalStack.push(dialog.value)
  if (props.trapFocus) {
    background = [...document.body.children].filter((element): element is HTMLElement =>
      element instanceof HTMLElement && !element.contains(dialog.value))
    for (const element of background) {
      const lock = inertLocks.get(element) ?? { count: 0, original: element.inert }
      lock.count += 1; inertLocks.set(element, lock); element.inert = true
    }
  }
  window.addEventListener('keydown', onKeydown)
  document.addEventListener('focusin', onFocusIn)
  await nextTick()
  if (props.trapFocus && isTopmost()) (focusables()[0] ?? dialog.value)?.focus()
})
onBeforeUnmount(() => {
  const restore = props.trapFocus && isTopmost()
  const index = dialog.value ? modalStack.indexOf(dialog.value) : -1
  if (index >= 0) modalStack.splice(index, 1)
  window.removeEventListener('keydown', onKeydown)
  document.removeEventListener('focusin', onFocusIn)
  for (const element of background) {
    const lock = inertLocks.get(element)
    if (lock && --lock.count === 0) { element.inert = lock.original; inertLocks.delete(element) }
  }
  background = []
  if (restore) void nextTick(() => {
    const remaining = modalStack[modalStack.length - 1]
    const active = document.activeElement
    // Respect a host that already restored focus explicitly after closing.
    if (active?.isConnected && active !== document.body && active !== document.documentElement) return
    if (returnFocus?.isConnected && (!remaining || remaining.contains(returnFocus))) returnFocus.focus()
  })
})
</script>
