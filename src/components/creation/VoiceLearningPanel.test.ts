// @vitest-environment happy-dom
import { mount } from '@vue/test-utils'
import { expect, it } from 'vitest'
import VoiceLearningPanel from './VoiceLearningPanel.vue'
it('候选默认不选，编辑与确认后才交付规则', async () => {
  const w = mount(VoiceLearningPanel, { props: { candidates: ['少用感叹号'], busy: false } })
  expect(w.get('input[type=checkbox]').element).toHaveProperty('checked', false)
  expect(w.findAll('button')[w.findAll('button').length - 1]!.attributes('disabled')).toBeDefined()
  await w.get('input[type=checkbox]').setValue(true)
  await w.get('input[maxlength]').setValue('保留具体判断')
  await w.findAll('button')[w.findAll('button').length - 1]!.trigger('click')
  expect(w.emitted('confirm')![0]).toEqual([['保留具体判断']]); w.unmount()
})
