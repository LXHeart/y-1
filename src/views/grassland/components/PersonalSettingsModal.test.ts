// @vitest-environment happy-dom
import { enableAutoUnmount, mount } from '@vue/test-utils'
import { afterEach, describe, expect, test } from 'vitest'
import PersonalSettingsModal from './PersonalSettingsModal.vue'

enableAutoUnmount(afterEach)

/** 弹窗类组件的 mount 必带 Teleport stub，否则内容渲染到 body、findAll 全空（项目实测坑）。 */
function mountModal(props: { open?: boolean; side?: 'merchant' | 'recommender'; section?: string } = {}) {
  return mount(PersonalSettingsModal, {
    props: { open: true, side: 'recommender', section: 'complaints', ...props },
    global: {
      stubs: {
        Teleport: true,
        MyRecommenderProfileCard: true, RecommenderShareCard: true,
        EmailBindingCard: true, MySessionsCard: true, PersonalDataComplianceCard: true,
        ComplaintsPanel: true,
      },
    },
  })
}

describe('PersonalSettingsModal', () => {
  test('open=false 不渲染弹窗', () => {
    const wrapper = mountModal({ open: false })
    expect(wrapper.find('[data-testid="gl-modal-overlay"]').exists()).toBe(false)
  })

  test('推荐官侧：三节齐全，五张卡与举报兜底面板就位', () => {
    const wrapper = mountModal({ side: 'recommender' })
    expect(wrapper.get('.modal-title').text()).toBe('个人设置')
    expect(wrapper.find('.gl-zone[aria-label="主页与分享"]').exists()).toBe(true)
    expect(wrapper.find('.gl-zone[aria-label="账号与合规"]').exists()).toBe(true)
    // 任务书 #74 D7：第三节「举报与投诉」两侧共享
    expect(wrapper.find('.gl-zone[aria-label="举报与投诉"]').exists()).toBe(true)
    // stub 标签断言走 html()：kebab-case 自定义元素选择器在 find() 的类型重载下没有 exists
    expect(wrapper.html()).toContain('my-recommender-profile-card-stub')
    expect(wrapper.html()).toContain('recommender-share-card-stub')
    expect(wrapper.html()).toContain('email-binding-card-stub')
    expect(wrapper.html()).toContain('my-sessions-card-stub')
    expect(wrapper.html()).toContain('personal-data-compliance-card-stub')
    expect(wrapper.html()).toContain('complaints-panel-stub')
  })

  test('商家侧：无「主页与分享」，有「账号与合规」与「举报与投诉」', () => {
    const wrapper = mountModal({ side: 'merchant' })
    expect(wrapper.find('.gl-zone[aria-label="主页与分享"]').exists()).toBe(false)
    expect(wrapper.find('.gl-zone[aria-label="账号与合规"]').exists()).toBe(true)
    expect(wrapper.find('.gl-zone[aria-label="举报与投诉"]').exists()).toBe(true)
    expect(wrapper.html()).not.toContain('my-recommender-profile-card-stub')
    expect(wrapper.html()).not.toContain('recommender-share-card-stub')
    expect(wrapper.html()).toContain('email-binding-card-stub')
    expect(wrapper.html()).toContain('my-sessions-card-stub')
    expect(wrapper.html()).toContain('personal-data-compliance-card-stub')
    expect(wrapper.html()).toContain('complaints-panel-stub')
  })

  test('persistent 语义：遮罩与 Esc 不关闭，× 关闭并透传 close', async () => {
    const wrapper = mountModal()
    await wrapper.get('[data-testid="gl-modal-overlay"]').trigger('mousedown')
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    expect(wrapper.emitted('close')).toBeUndefined()
    await wrapper.get('button[aria-label="关闭弹窗"]').trigger('click')
    expect(wrapper.emitted('close')).toHaveLength(1)
  })

  test('左栏分节（2026-09-04 反馈 6/7）：四节导航，切换透传 update:section；判例库按需挂载', async () => {
    const wrapper = mountModal({ side: 'recommender', section: 'complaints' })
    const railItems = wrapper.findAll('.gl-rail-item')
    expect(railItems.map((item) => item.text())).toEqual(['主页与分享', '账号与合规', '举报与投诉', '判例库'])
    // 深链默认节=举报与投诉（aria-selected），表单节 v-show 常驻
    expect(railItems[2].attributes('aria-selected')).toBe('true')

    // 判例库 v-if 按需挂载：未选中不在 DOM，点左栏后挂载并透传 update:section
    expect(wrapper.find('.gl-zone[aria-label="判例库"]').exists()).toBe(false)
    await railItems[3].trigger('click')
    expect(wrapper.emitted('update:section')).toEqual([['precedents']])
    expect(wrapper.find('.gl-zone[aria-label="判例库"]').exists()).toBe(true)
    expect(wrapper.findAll('.gl-rail-item')[3].attributes('aria-selected')).toBe('true')
  })

  test('外部落入推荐官专属节时（商家侧 profile）回落到第一节', () => {
    const wrapper = mountModal({ side: 'merchant', section: 'profile' })
    expect(wrapper.findAll('.gl-rail-item')[0].attributes('aria-selected')).toBe('true')
    expect(wrapper.findAll('.gl-rail-item')[0].text()).toBe('账号与合规')
  })
})
