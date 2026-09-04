// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import GrasslandHomeView from './GrasslandHomeView.vue'
import router from '../../router'
import { useActiveIdentity } from '../../composables/useActiveIdentity'
import { useAuth } from '../../composables/useAuth'
import type { IdentityProfile } from '../../types/grassland'

/**
 * 草场主页（平台门面）特征测试。
 *
 * 锁定 PRD 的角色规则：
 * - 未登录：平台介绍 + 登录引导，不露出工作台入口；
 * - 商家 / 推荐官：各自的工作台入口卡（按已开通身份显隐）；
 * - 平台管理人员：运营处置 / 管理后台入口（按后端角色）；
 * - 共享能力（AI 中心 / 到店消费 / 举报投诉）对相应范围可见。
 */

function identity(type: 'merchant' | 'recommender'): IdentityProfile {
  return { id: `identity-${type}`, identityType: type, organizationId: null, status: 'active' }
}

/** 主页不读路由参数，导航动作只走 router.push（跳转结果由 App.test 断言），这里挂真实 router 只为注入。 */
function mountView() {
  return mount(GrasslandHomeView, { global: { plugins: [router] } })
}

beforeEach(() => {
  useActiveIdentity().reset()
  useAuth().currentUser.value = null
})

afterEach(() => {
  useAuth().currentUser.value = null
  vi.unstubAllGlobals()
})

enableAutoUnmount(afterEach)

describe('草场主页 · 未登录', () => {
  test('展示平台主张与登录引导，不露出工作台与举报投诉', () => {
    const wrapper = mountView()

    expect(wrapper.get('.hero-title').text()).toContain('商家 × 推荐官')
    expect(wrapper.get('.hero-cta').text()).toContain('登录 / 注册')
    expect(wrapper.find('[data-testid="home-merchant-entry"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="home-recommender-entry"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="home-onboarding-entry"]').exists()).toBe(false)
    // 共享能力对游客可见（AI 中心游客有限体验），治理入口与到店消费（付费方式而非模块）不可见
    expect(wrapper.text()).toContain('AI 内容创作中心')
    expect(wrapper.text()).not.toContain('到店消费')
    expect(wrapper.text()).not.toContain('举报投诉')
  })

  test('点击登录引导会发 request-login 事件', async () => {
    const wrapper = mountView()
    await wrapper.get('.hero-cta').trigger('click')
    expect(wrapper.emitted('request-login')).toHaveLength(1)
  })
})

describe('草场主页 · 角色感知入口', () => {
  test('商家身份：显示商家工作台，不显示推荐官入口', async () => {
    useAuth().currentUser.value = { id: 'm-1', email: 'm@example.com', role: 'user', roles: [] }
    const state = useActiveIdentity()
    await state.loadAccountIdentity({
      listIdentities: async () => [identity('merchant')],
      listMyStoreScopes: async () => [],
      activateIdentity: async () => ({}),
      getActiveIdentity: async () => ({ activeIdentityType: null }),
      clearError: () => {},
    } as never)
    await flushPromises()

    const wrapper = mountView()

    expect(wrapper.find('[data-testid="home-merchant-entry"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="home-recommender-entry"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="home-onboarding-entry"]').exists()).toBe(false)
    expect(wrapper.get('.hero-identity').text()).toContain('商家')
  })

  test('推荐官身份：显示推荐官工作台与耕耘入口文案', async () => {
    useAuth().currentUser.value = { id: 'r-1', email: 'r@example.com', role: 'user', roles: [] }
    const state = useActiveIdentity()
    await state.loadAccountIdentity({
      listIdentities: async () => [identity('recommender')],
      listMyStoreScopes: async () => [],
      activateIdentity: async () => ({}),
      getActiveIdentity: async () => ({ activeIdentityType: null }),
      clearError: () => {},
    } as never)
    await flushPromises()

    const wrapper = mountView()

    expect(wrapper.find('[data-testid="home-recommender-entry"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="home-merchant-entry"]').exists()).toBe(false)
    expect(wrapper.get('.hero-identity').text()).toContain('推荐官')
  })

  test('平台管理员主页不再露出治理入口（治理台独立 origin）', async () => {
    useAuth().currentUser.value = {
      id: 'a-1', email: 'a@example.com', role: 'admin', roles: ['platform_admin'],
    }
    await flushPromises()

    const wrapper = mountView()

    // 治理入口已拆到独立治理台（ops.html）；用户端主页对管理员也不显示
    expect(wrapper.find('[data-testid="home-ops-entry"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="home-admin-entry"]').exists()).toBe(false)
    // 无业务身份 → 引导开通
    expect(wrapper.find('[data-testid="home-onboarding-entry"]').exists()).toBe(true)
  })

  test('无任何身份的普通账号：引导完善资料开通第一个身份', async () => {
    useAuth().currentUser.value = { id: 'p-1', email: 'p@example.com', role: 'user', roles: [] }
    const state = useActiveIdentity()
    await state.loadAccountIdentity({
      listIdentities: async () => [],
      listMyStoreScopes: async () => [],
      // 零档案兜底（D6）只对「无组织归属」的存量裸账号开推荐官；此处给组织归属
      // （主体子账号场景）保持零档案 + 商家视角入驻引导。
      listOrganizations: async () => [{ id: 'org-1' }],
      openIdentity: async () => ({}),
      activateIdentity: async () => ({}),
      getActiveIdentity: async () => ({ activeIdentityType: null }),
      clearError: () => {},
    } as never)
    await flushPromises()

    const wrapper = mountView()

    expect(wrapper.find('[data-testid="home-onboarding-entry"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('举报投诉')
  })
})
