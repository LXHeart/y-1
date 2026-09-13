import { describe, expect, test } from 'vitest'
import { effectScope, ref } from 'vue'
import { useCanvasSelection } from './useCanvasSelection'

describe('C102 explicit selection', () => {
  test('exclusive selection, modifier toggle and 20-node boundary never imply select-all', () => {
    const scope = effectScope()
    const nodes = ref(Array.from({ length: 21 }, (_, i) => ({ id: `node:${i}` })))
    const selection = scope.run(() => useCanvasSelection(nodes, ref('A')))!
    expect(selection.selectedNodeIds.value).toEqual([])
    selection.selectExclusive('node:2'); selection.toggle('node:3')
    expect(selection.selectedNodeIds.value).toEqual(['node:2', 'node:3'])
    selection.toggle('node:2'); expect(selection.focusedNodeId.value).toBe('node:2')
    selection.clear()
    for (let i = 0; i < 20; i++) expect(selection.toggle(`node:${i}`)).toBe(true)
    expect(selection.toggle('node:20')).toBe(false); expect(selection.error.value).toContain('20')
    expect(selection.selectedNodeIds.value).toHaveLength(20)
    scope.stop()
  })
  test('removed nodes are announced and account/project generation clears private selection', () => {
    const scope = effectScope(); const nodes = ref([{ id: 'a' }, { id: 'b' }]); const epoch = ref(1)
    const selection = scope.run(() => useCanvasSelection(nodes, epoch))!
    selection.selectExclusive('a'); nodes.value = [{ id: 'b' }]
    expect(selection.selectedNodeIds.value).toEqual([]); expect(selection.error.value).toContain('不存在')
    selection.selectExclusive('b'); epoch.value++
    expect(selection.selectedNodeIds.value).toEqual([]); expect(selection.focusedNodeId.value).toBeNull()
    expect(selection.selectExclusive('invented')).toBe(false)
    scope.stop()
  })
})
