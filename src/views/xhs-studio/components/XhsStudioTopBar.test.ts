// @vitest-environment happy-dom
import { mount } from '@vue/test-utils'
import { describe, expect, test } from 'vitest'
import XhsStudioTopBar from './XhsStudioTopBar.vue'

/** 顶栏（阶段 0）：保存徽标透传、保存草稿/返回/重试事件上抛、conflict/readonly 态。 */

function mountBar(overrides: Record<string, unknown> = {}) {
  return mount(XhsStudioTopBar, {
    props: {
      saveState: 'idle',
      draftTitle: '',
      ...overrides,
    },
  })
}

describe('XhsStudioTopBar', () => {
  test('平台徽章与标题文案；返回创作中心按钮 emit back', async () => {
    const wrapper = mountBar()
    expect(wrapper.get('.badge-accent').text()).toBe('小红书')
    expect(wrapper.text()).toContain('种草图文')
    await wrapper.get('.xhs-topbar-back').trigger('click')
    expect(wrapper.emitted('back')).toHaveLength(1)
  })

  test('保存状态映射：saving→保存中…；conflict 显示提示与两个动作按钮', () => {
    const saving = mountBar({ saveState: 'saving' })
    expect(saving.text()).toContain('保存中…')

    const conflict = mountBar({ saveState: 'conflict', conflict: '草稿已在其他设备修改' })
    expect(conflict.text()).toContain('草稿已在其他设备修改')
    expect(conflict.findAll('.save-retry')).toHaveLength(2)
  })

  test('readonly 态显示只读文案且无重试按钮', () => {
    const wrapper = mountBar({ saveState: 'idle', readonly: true })
    expect(wrapper.text()).toContain('当前版本仅可查看')
    expect(wrapper.find('.save-retry').exists()).toBe(false)
  })

  test('保存草稿按钮 emit saveDraft；保存徽标 retry/reload 事件透传上抛', async () => {
    const wrapper = mountBar({ saveState: 'error', conflict: '网络异常' })
    await wrapper.get('.xhs-topbar-save').trigger('click')
    expect(wrapper.emitted('saveDraft')).toHaveLength(1)

    const retries = wrapper.findAll('[data-testid="save-retry"]')
    await retries[0].trigger('click') // error 态唯一按钮=重试
    expect(wrapper.emitted('retry')).toHaveLength(1)

    const conflict = mountBar({ saveState: 'conflict', conflict: '草稿已在其他设备修改' })
    const actions = conflict.findAll('.save-retry')
    await actions[0].trigger('click')
    expect(conflict.emitted('reload')).toHaveLength(1)
    await actions[1].trigger('click')
    expect(conflict.emitted('retry')).toHaveLength(1)
  })

  test('draftTitle 显示（超长截断由 CSS ellipsis 承担，DOM 保留全文）', () => {
    const wrapper = mountBar({ draftTitle: '候选标题一：通勤穿搭攻略' })
    expect(wrapper.get('.xhs-topbar-draft').text()).toBe('候选标题一：通勤穿搭攻略')
  })
})
