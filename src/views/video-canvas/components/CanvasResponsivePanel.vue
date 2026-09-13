<script setup lang="ts">
import { nextTick, onActivated, onBeforeUnmount, onDeactivated, ref, watch } from 'vue'
import GlModal from '../../../components/GlModal.vue'

const props = withDefaults(defineProps<{
  open: boolean
  docked: boolean
  title: string
  kind?: 'detail' | 'assets'
  keepMounted?: boolean
  returnFocusSelector?: string
  beforeClose: () => Promise<boolean>
}>(), { kind: 'detail', keepMounted: false })
const emit = defineEmits<{ close: [] }>()
const content = ref<HTMLElement | null>(null); const closeError = ref<HTMLElement | null>(null)
const portal = ref<HTMLElement | null>(null)
const closing = ref(false); const error = ref('')
let returnFocus: HTMLElement | null = null; let dialog: HTMLElement | null = null
let overlay: HTMLElement | null = null; let installed = false; let generation = 0
let overflow = ''; let background: Array<{ element: HTMLElement; inert: boolean }> = []
function focusables(): HTMLElement[] {
  return [...(dialog?.querySelectorAll<HTMLElement>('button,input,textarea,select,a[href],[tabindex]') ?? [])].filter(element => {
    if (element.matches(':disabled,[tabindex="-1"]') || element.closest('[hidden],[inert],[aria-hidden="true"]')) return false
    for (let parent: HTMLElement | null = element; parent && parent !== dialog; parent = parent.parentElement)
      if (getComputedStyle(parent).display === 'none' || getComputedStyle(parent).visibility === 'hidden') return false
    return true
  })
}
function keydown(event: KeyboardEvent): void {
  if (!dialog) return
  if (event.key === 'Escape') { event.preventDefault(); event.stopImmediatePropagation(); void close(); return }
  if (event.key !== 'Tab') return
  const items = focusables(); const index = items.indexOf(document.activeElement as HTMLElement)
  if (!items.length) { event.preventDefault(); dialog.focus(); return }
  if (event.shiftKey && index <= 0) { event.preventDefault(); items[items.length - 1]?.focus() }
  else if (!event.shiftKey && (index === -1 || index === items.length - 1)) { event.preventDefault(); items[0]?.focus() }
}
function focusIn(event: FocusEvent): void { if (dialog && !dialog.contains(event.target as Node)) (focusables()[0] ?? dialog).focus() }
function cleanup(restore = true): void {
  const wasOpen = installed || returnFocus !== null
  generation++
  if (installed) { document.removeEventListener('keydown', keydown, true); document.removeEventListener('focusin', focusIn, true) }
  installed = false; overlay?.classList.remove('canvas-drawer-overlay'); overlay = null; dialog = null
  for (const { element, inert } of background) element.inert = inert
  if (background.length) document.body.style.overflow = overflow
  background = []
  if (restore && wasOpen) {
    if (returnFocus?.isConnected) returnFocus.focus()
    else if (props.returnFocusSelector) document.querySelector<HTMLElement>(props.returnFocusSelector)?.focus()
  }
  returnFocus = null
}
async function install(): Promise<void> {
  cleanup(false)
  if (!props.open || props.docked) return
  const ticket = generation; const trigger = document.activeElement as HTMLElement | null
  await nextTick()
  if (ticket !== generation || !props.open || props.docked) return
  dialog = content.value?.closest<HTMLElement>('[role="dialog"]') ?? null
  if (!dialog) return
  returnFocus = trigger; dialog.tabIndex = -1
  overlay = dialog.parentElement; overlay?.classList.add('canvas-drawer-overlay')
  overflow = document.body.style.overflow; document.body.style.overflow = 'hidden'
  background = [...document.body.children].filter(element => element !== overlay && element instanceof HTMLElement)
    .map(element => ({ element: element as HTMLElement, inert: (element as HTMLElement).inert }))
  for (const { element } of background) element.inert = true
  document.addEventListener('keydown', keydown, true); document.addEventListener('focusin', focusIn, true); installed = true
  ;(focusables()[0] ?? dialog).focus()
}
async function close(): Promise<void> {
  if (closing.value) return
  closing.value = true; error.value = ''
  try {
    if (await props.beforeClose()) emit('close')
    else { error.value = '修改尚未保存，请处理当前错误后再关闭'; await nextTick(); closeError.value?.focus() }
  } catch { error.value = '保存失败，已保留当前编辑'; await nextTick(); closeError.value?.focus() }
  finally { closing.value = false }
}
watch(() => [props.open, props.docked], () => {
  if (props.open && !props.docked) void install()
  else cleanup()
}, { immediate: true, flush: 'post' })
onBeforeUnmount(() => cleanup()); onDeactivated(() => cleanup())
onActivated(() => { if (props.open && !props.docked) void install() })
</script>

<template>
  <GlModal v-if="open && !docked" :title="title" scroll :persistent="closing" @close="close">
    <div ref="portal"></div>
  </GlModal>
  <aside v-show="open && docked" class="canvas-docked-panel" :class="`canvas-docked-${kind}`" :aria-label="title">
    <Teleport :to="portal ?? 'body'" :disabled="docked || !open || !portal">
      <div v-if="open || keepMounted" ref="content" class="canvas-responsive-content gl-field" :data-test="`canvas-${kind}-panel`">
        <p v-if="error" ref="closeError" tabindex="-1" class="canvas-panel-error" role="alert" data-test="canvas-panel-close-error">{{ error }}</p>
        <slot />
      </div>
    </Teleport>
  </aside>
</template>
