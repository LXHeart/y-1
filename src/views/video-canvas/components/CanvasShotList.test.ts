// @vitest-environment happy-dom
import { describe, expect, test } from 'vitest'
import { mount } from '@vue/test-utils'
import CanvasShotList from './CanvasShotList.vue'
import type { CanvasShot } from '../useVideoCanvas'

const shots = [1, 2].map(seq => ({ id: `shot-${seq}`, seq, visual: `第${seq}镜`, narration: '保留旁白', plannedSeconds: 5, takes: [], x: 0, y: 0 } as unknown as CanvasShot))
describe('C102 mobile shot list', () => {
  test('checkbox multi-selection is separate from opening one shot and needs no dragging', async () => {
    const wrapper = mount(CanvasShotList, { props: { shots, focusedShotId: null, selectedNodeIds: [] } })
    await wrapper.get('[data-test="canvas-list-check-1"]').setValue(true)
    await wrapper.get('[data-test="canvas-list-check-2"]').setValue(true)
    expect(wrapper.emitted('select')).toEqual([['shot-1', true], ['shot-2', true]])
    await wrapper.get('[data-test="canvas-list-edit-2"]').trigger('click')
    expect(wrapper.emitted('select')?.[2]).toEqual(['shot-2'])
    await wrapper.setProps({ focusedShotId: 'shot-2', selectedNodeIds: ['shot:shot-2'] })
    expect(wrapper.get('[data-test="canvas-list-edit-2"]').attributes('aria-expanded')).toBe('true')
    expect(wrapper.get('[data-test="canvas-list-check-2"]').element).toHaveProperty('checked', true)
  })
  test('empty state offers a next step, references remain selectable without opening a canvas', async () => {
    const wrapper = mount(CanvasShotList, { props: { shots: [], focusedShotId: null, selectedNodeIds: [], references: [
      { id: 'note:1', kind: 'note', refType: 'note', refId: null, text: '创作要点', label: null, x: 0, y: 0, unavailableReason: null },
    ] } })
    expect(wrapper.text()).toContain('从快速模式生成分镜')
    await wrapper.get('.canvas-reference-row button').trigger('click')
    expect(wrapper.emitted('selectNode')).toEqual([['note:1']])
  })
})
