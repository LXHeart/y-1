// @vitest-environment happy-dom
import { mount } from '@vue/test-utils'
import { expect, it } from 'vitest'
import VoiceSamplesEditor from './VoiceSamplesEditor.vue'
it('新范文需明确确认授权，不能默认勾选', async () => {
  const w = mount(VoiceSamplesEditor, { props: { modelValue: [] } })
  await w.get('button').trigger('click')
  expect(w.emitted('update:modelValue')![0]![0]).toEqual([expect.objectContaining({ consent: false, text: '' })]); w.unmount()
})
