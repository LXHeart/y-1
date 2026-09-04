// @vitest-environment happy-dom
import { describe, expect, test, vi, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import MerchantTaskForm from './MerchantTaskForm.vue'
import { emptyCommissionLadderForm } from './commission-ladder'

const { listMerchantPackages } = vi.hoisted(() => ({ listMerchantPackages: vi.fn() }))

vi.mock('../../../composables/useCommerce', () => ({
  useCommerce: () => ({ listMerchantPackages }),
}))

function baseForm(overrides: Record<string, unknown> = {}) {
  return {
    title: '套餐推广任务',
    description: '',
    platform: 'xhs',
    contentForm: 'image',
    interactionTargetUrl: '',
    interactionActionType: 'like',
    maxSlots: 3,
    bountyYuan: 50,
    freebieDepositYuan: 0,
    paymentMode: 'commission' as const,
    applicationDeadline: '',
    minRecommenderLevel: 1,
    autoAcceptMinLevel: null,
    productServiceInfo: '',
    mustInclude: '',
    forbiddenContent: '',
    publishStartAt: '',
    publishEndAt: '',
    metricRequirements: '',
    evidenceRequirements: '',
    commissionLadder: emptyCommissionLadderForm(),
    ...overrides,
  }
}

function mountForm(form: ReturnType<typeof baseForm>) {
  return mount(MerchantTaskForm, {
    props: {
      open: true,
      form,
      editingDraft: null,
      revisingTask: null,
      stores: [],
      selectedStoreId: '',
      activeOrgId: 'org-1',
      hasOrganizationAccess: true,
      canPublishBounty: true,
      loading: false,
    },
    // 表单已抽屉化并 Teleport 到 body：不 stub 的话内容落在 wrapper 之外，find 全查不到。
    global: { stubs: { Teleport: true } },
  })
}

describe('MerchantTaskForm 付费方式三选一（任务书 #75 卡 A7）', () => {
  beforeEach(() => {
    listMerchantPackages.mockReset()
    document.body.innerHTML = ''
  })

  test('切到套餐推广即清零赏金/押金并关阶梯', async () => {
    const wrapper = mountForm(baseForm({ bountyYuan: 50, commissionLadder: { enabled: true, metricKey: 'x', tiers: [] } }))
    await wrapper.find('input[name="task-payment-mode"][value="commerce"]').trigger('change')
    // 资金字段归零（emit 语义；父级写回后 prop 变化触发选择器拉取，见下一用例）。
    expect(wrapper.emitted('update:field')).toContainEqual(['paymentMode', 'commerce'])
    expect(wrapper.emitted('update:field')).toContainEqual(['bountyYuan', 0])
    expect(wrapper.emitted('update:field')).toContainEqual(['freebieDepositYuan', 0])
  })

  test('套餐推广模式：选择器只列已上架套餐、被占用置灰、选中出摘要', async () => {
    listMerchantPackages.mockResolvedValue([
      { id: 'pkg-1', title: '双人下午茶', priceCents: 12800, recommenderShareBps: 1000, status: 'published' },
      { id: 'pkg-2', title: '被占用套餐', priceCents: 9900, recommenderFixedCents: 500, recommenderShareBps: 0, status: 'published', taskId: 'task-other' },
      { id: 'pkg-3', title: '草稿套餐', priceCents: 9900, recommenderShareBps: 1000, status: 'draft' },
    ])
    const wrapper = mountForm(baseForm({ paymentMode: 'commerce' }))
    await flushPromises()

    expect(listMerchantPackages).toHaveBeenCalledWith('org-1', undefined)
    // 选择器出现且只含已上架套餐（草稿被过滤）。
    const select = wrapper.find('select[name="task-commerce-package"]')
    expect(select.exists()).toBe(true)
    const options = select.findAll('option')
    expect(options.map((option) => option.text())).toEqual([
      '选择一个已上架套餐',
      '双人下午茶 · ¥128.00 · 佣金 10% / 单',
      '被占用套餐 · ¥99.00 · 佣金 ¥5.00 / 单（已被推广任务占用）',
    ])
    // 被其他任务占用的套餐置灰。
    expect(options[2].attributes('disabled')).toBeDefined()

    await select.setValue('pkg-1')
    expect(wrapper.emitted('update:field')).toContainEqual(['commercePackageId', 'pkg-1'])
  })

  test('已选套餐出只读摘要（价格 + 佣金来自套餐快照）', async () => {
    listMerchantPackages.mockResolvedValue([
      { id: 'pkg-1', title: '双人下午茶', priceCents: 12800, recommenderShareBps: 1000, status: 'published' },
    ])
    const wrapper = mountForm(baseForm({ paymentMode: 'commerce', commercePackageId: 'pkg-1' }))
    await flushPromises()
    expect(wrapper.find('[data-testid="commerce-package-summary"]').text()).toContain('¥128.00')
    expect(wrapper.find('[data-testid="commerce-package-summary"]').text()).toContain('10% / 单')
  })

  test('本主体无上架套餐：空态引导到「资金与经营」', async () => {
    listMerchantPackages.mockResolvedValue([])
    const wrapper = mountForm(baseForm({ paymentMode: 'commerce' }))
    await flushPromises()
    expect(wrapper.text()).toContain('资金与经营 → 到店套餐与核销')
  })

  test('佣金/霸王餐模式不出套餐选择器', async () => {
    const wrapper = mountForm(baseForm({ paymentMode: 'freebie' }))
    await flushPromises()
    expect(wrapper.find('select[name="task-commerce-package"]').exists()).toBe(false)
    expect(listMerchantPackages).not.toHaveBeenCalled()
  })
})
