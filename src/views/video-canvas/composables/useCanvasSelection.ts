import { ref, watch, type Ref } from 'vue'

export interface CanvasSelectionState { selectedNodeIds: string[]; focusedNodeId: string | null }
interface SelectableNode { id: string }

/** A single explicit selection, shared by canvas, list, detail and assistant. */
export function useCanvasSelection(nodes: Ref<SelectableNode[]>, generation: Ref<string | number>) {
  const selectedNodeIds = ref<string[]>([])
  const focusedNodeId = ref<string | null>(null)
  const error = ref('')
  const exists = (id: string) => nodes.value.some(node => node.id === id)

  function selectExclusive(id: string): boolean {
    if (!exists(id)) { error.value = '节点已不存在，请重新选择'; return false }
    selectedNodeIds.value = [id]; focusedNodeId.value = id; error.value = ''; return true
  }
  function toggle(id: string): boolean {
    if (!exists(id)) { error.value = '节点已不存在，请重新选择'; return false }
    if (selectedNodeIds.value.includes(id)) selectedNodeIds.value = selectedNodeIds.value.filter(value => value !== id)
    else {
      if (selectedNodeIds.value.length >= 20) { error.value = '最多选择 20 个节点'; return false }
      selectedNodeIds.value = [...selectedNodeIds.value, id]
    }
    focusedNodeId.value = id; error.value = ''; return true
  }
  function clear(): void { selectedNodeIds.value = []; focusedNodeId.value = null; error.value = '' }
  watch(generation, clear, { flush: 'sync' })
  watch(nodes, next => {
    const ids = new Set(next.map(node => node.id))
    const remaining = selectedNodeIds.value.filter(id => ids.has(id))
    if (remaining.length !== selectedNodeIds.value.length) {
      selectedNodeIds.value = remaining; error.value = '部分已选节点已不存在，请确认选择范围'
    }
    if (focusedNodeId.value && !ids.has(focusedNodeId.value)) focusedNodeId.value = null
  }, { flush: 'sync' })
  return { selectedNodeIds, focusedNodeId, error, selectExclusive, toggle, clear }
}
