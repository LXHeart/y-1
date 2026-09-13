// @vitest-environment happy-dom
import { afterEach, describe, expect, test } from 'vitest'
import { enableAutoUnmount, mount } from '@vue/test-utils'
import CanvasReferenceInspector from './CanvasReferenceInspector.vue'
import type { GraphNode } from '../composables/useCanvasGraph'

enableAutoUnmount(afterEach)
const note: GraphNode = { id: 'note:n', kind: 'note', refType: 'note', refId: null, label: null, text: '备注', x: 0, y: 0, unavailableReason: null }
const target: GraphNode = { ...note, id: 'shot:s', kind: 'shot', refType: 'shot', refId: 's', label: '镜头 1' }
const base = { node: note, noteText: '备注', targets: [target], edges: [], mediaOptions: [], error: '' }

describe('C102 accessible reference inspector', () => {
  test('note editing, target selection, edge creation and node removal are reachable controls', async () => {
    const wrapper = mount(CanvasReferenceInspector, { props: base })
    await wrapper.get('[data-test="canvas-note-input"]').setValue('新的备注')
    expect(wrapper.emitted('update-note')?.[0]).toEqual(['新的备注'])
    await wrapper.get('[data-test="canvas-reference-target"]').setValue('shot:s')
    await wrapper.get('[data-test="canvas-reference-add-edge"]').trigger('click')
    expect(wrapper.emitted('add-reference')?.[0]).toEqual(['shot:s'])
    await wrapper.get('[data-test="canvas-reference-remove-node"]').trigger('click')
    expect(wrapper.emitted('remove-node')).toHaveLength(1)
  })
  test('unavailable media offers replacement while readonly disables writes', async () => {
    const asset = { id: 'new', name: '新素材', authorized: true, status: 'active' as const }
    const wrapper = mount(CanvasReferenceInspector, { props: { ...base,
      node: { ...note, kind: 'media', refType: 'media', refId: 'old', unavailableReason: '素材已失效' }, mediaOptions: [asset] } })
    expect(wrapper.get('[data-test="canvas-reference-preview"]').attributes('disabled')).toBeDefined()
    await wrapper.get('[data-test="canvas-reference-replacement"]').setValue('new')
    await wrapper.get('[data-test="canvas-reference-replace"]').trigger('click')
    expect(wrapper.emitted('replace-media')?.[0]).toEqual([asset])
    await wrapper.setProps({ readonly: true })
    expect(wrapper.get('[data-test="canvas-reference-replace"]').attributes('disabled')).toBeDefined()
  })
})
