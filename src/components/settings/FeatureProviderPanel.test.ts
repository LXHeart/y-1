// @vitest-environment happy-dom
import { enableAutoUnmount, mount } from '@vue/test-utils'
import { afterEach, describe, expect, test } from 'vitest'
import FeatureProviderPanel from './FeatureProviderPanel.vue'

enableAutoUnmount(afterEach)
const state = { loading: false, error: '', availableModels: [{ id: 'model-a' }], verifying: false, verifyResult: 'idle' as const, verifyError: '' }

describe('FeatureProviderPanel', () => {
  test('emits connection, secret visibility, model selection and verification commands', async () => {
    const wrapper = mount(FeatureProviderPanel, { props: {
      fieldPrefix: 'image', baseUrl: '', apiKey: '', model: '', baseUrlPlaceholder: 'https://api.example',
      modelPlaceholder: 'model', showSecret: false, hasSavedSecret: true, modelState: state,
      canFetchModels: true, useCustomModel: false,
    } })
    await wrapper.get('#image-base-url').setValue('https://api.example')
    await wrapper.get('#image-api-key').setValue('secret')
    await wrapper.find('.token-row button').trigger('click')
    await wrapper.get('select').setValue('model-a')
    await wrapper.setProps({ model: 'model-a' })
    const commands = wrapper.findAll('.settings-group-head button, .model-row button')
    await commands[0].trigger('click'); await commands[1].trigger('click')

    expect(wrapper.emitted('update:baseUrl')?.[0]).toEqual(['https://api.example'])
    expect(wrapper.emitted('update:apiKey')?.[0]).toEqual(['secret'])
    expect(wrapper.emitted('toggle:showSecret')).toHaveLength(1)
    expect(wrapper.emitted('update:model')?.[0]).toEqual(['model-a'])
    expect(wrapper.emitted('fetchModels')).toHaveLength(1)
    expect(wrapper.emitted('verifyModel')).toHaveLength(1)
  })

  test('disables model commands while prerequisites are absent', () => {
    const wrapper = mount(FeatureProviderPanel, { props: {
      fieldPrefix: 'article', baseUrl: '', apiKey: '', model: '', baseUrlPlaceholder: '', modelPlaceholder: '',
      showSecret: false, hasSavedSecret: false, modelState: { ...state, availableModels: [] },
      canFetchModels: false, useCustomModel: true,
    } })
    expect(wrapper.findAll('button').filter(button => button.attributes('disabled') !== undefined)).toHaveLength(2)
  })
})
