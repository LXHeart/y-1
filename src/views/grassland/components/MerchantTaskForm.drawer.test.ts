// @vitest-environment happy-dom
import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import { nextTick, reactive } from 'vue'
import MerchantTaskForm from './MerchantTaskForm.vue'

/**
 * 任务表单关闭行为（任务书 #78 卡 H：抽屉改居中 GlModal 弹窗后语义不变）：
 * - 关闭途径收窄：遮罩点击不关（persistent）；Esc=×；只有 取消 / × / 提交审核 / 存为草稿 能关。
 * - 脏表单走三选一确认（存草稿并退出 / 直接退出（作废） / 继续编辑），替换 window.confirm；
 *   文案按模式分派（编辑草稿=保存草稿，修订=保存修订）。干净表单直接关，不打扰。
 * - 确认框独立 Teleport 到 body（主表单 GlModal 自带 Teleport，不再嵌套遮罩）。
 *
 * 脏检测依赖 props.form 变化：挂载必须模拟真实父组件——reactive 表单 + update:field 写回，
 * 静态 props 下 emit 不回流、表单永远「干净」。
 */

const baseForm = {
  title: '', description: '', platform: '', contentForm: '', maxSlots: 1,
  interactionTargetUrl: '', interactionActionType: 'like',
  bountyYuan: '', freebieDepositYuan: '', paymentMode: 'commission' as 'commission' | 'freebie',
  applicationDeadline: '', minRecommenderLevel: 1,
  autoAcceptMinLevel: null as number | null, productServiceInfo: '', mustInclude: '',
  forbiddenContent: '', publishStartAt: '', publishEndAt: '', metricRequirements: '',
  evidenceRequirements: '',
}

type FormOverrides = Partial<typeof baseForm>
type PropOverrides = Partial<Omit<InstanceType<typeof MerchantTaskForm>['$props'], 'form'>>

function mountForm(formOverrides: FormOverrides = {}, props: PropOverrides = {}, stubTeleport = true) {
  const form = reactive({ ...baseForm, ...formOverrides })
  const wrapper = mount(MerchantTaskForm, {
    props: {
      form, open: true, editingDraft: null, revisingTask: null,
      stores: [], selectedStoreId: '', activeOrgId: 'org-1',
      canPublishBounty: true, loading: false,
      'onUpdate:field': (field: string, value: string | number | null) => {
        (form as unknown as Record<string, string | number | null>)[field] = value
      },
      ...props,
    },
    global: { stubs: { Teleport: stubTeleport } },
  })
  return wrapper
}

function pressEscape(): void {
  window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
}

async function makeDirty(wrapper: ReturnType<typeof mountForm>, title = '填了标题即脏'): Promise<void> {
  await wrapper.find('input[placeholder="任务标题"]').setValue(title)
}

/** 主弹窗在 DOM 的节点（GlModal 的 .modal-card）；确认框也有 .modal-card，需用文案区分。 */
function mainModal(wrapper: ReturnType<typeof mountForm>) {
  return wrapper.find('[data-testid="gl-modal-overlay"]')
}

function exitConfirm(wrapper: ReturnType<typeof mountForm>) {
  return wrapper.findAll('.modal-card').find((card) => card.text().includes('离开任务表单？'))
}

afterEach(() => {
  vi.restoreAllMocks()
})

describe('MerchantTaskForm 弹窗关闭行为（任务书 #78 卡 H）', () => {
  test('先挂载关闭表单再打开：真实Teleport的离开确认层必须位于主弹窗之后', async () => {
    const wrapper = mountForm({}, { open: false }, false)
    try {
      await wrapper.setProps({ open: true })
      const title = document.querySelector<HTMLInputElement>('input[name="task-title"]')!
      title.value = '真实Teleport脏表单'
      title.dispatchEvent(new Event('input', { bubbles: true }))
      await nextTick()
      document.querySelector<HTMLButtonElement>('[data-action="close-modal"]')!.click()
      await nextTick()
      const overlays = Array.from(document.querySelectorAll('.modal-overlay'))
      expect(overlays).toHaveLength(2)
      expect(overlays[overlays.length - 1]?.textContent).toContain('离开任务表单？')
      expect(overlays[0].textContent).toContain('发布任务')
    } finally {
      wrapper.unmount()
    }
  })

  test('遮罩空白点击不关闭弹窗（persistent 短路误触，防丢表单）', async () => {
    const wrapper = mountForm()
    await mainModal(wrapper).trigger('mousedown')
    expect(mainModal(wrapper).exists()).toBe(true)
    expect(wrapper.emitted('close')).toBeUndefined()
  })

  test('干净表单：Esc / × / 取消 直接关，不弹确认', async () => {
    const wrapper = mountForm()
    pressEscape()
    await Promise.resolve()
    expect(wrapper.emitted('close')).toHaveLength(1)
    expect(exitConfirm(wrapper)).toBeUndefined()
  })

  test('× 按钮走 requestClose：脏表单弹三选一确认而非直接关', async () => {
    const wrapper = mountForm()
    await makeDirty(wrapper)
    await wrapper.find('[data-action="close-modal"]').trigger('click')
    await Promise.resolve()
    expect(wrapper.emitted('close')).toBeUndefined()
    expect(exitConfirm(wrapper)).toBeDefined()
  })

  test('脏表单：Esc 弹三选一确认；「继续编辑」留在弹窗、「直接退出」才关', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm')
    const wrapper = mountForm()
    await makeDirty(wrapper)
    pressEscape()
    await Promise.resolve()

    // window.confirm 已被替换为应用内确认框
    expect(confirmSpy).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('返回将清空当前已填内容，要先存为草稿吗？')
    expect(wrapper.find('.task-exit-overlay').exists()).toBe(true)
    expect(wrapper.emitted('close')).toBeUndefined()

    // 继续编辑：只收确认框，主弹窗还在
    await wrapper.findAll('button').find((b) => b.text() === '继续编辑')!.trigger('click')
    expect(exitConfirm(wrapper)).toBeUndefined()
    expect(mainModal(wrapper).exists()).toBe(true)

    // 再次 Esc 弹确认，选「直接退出（作废）」→ 关弹窗
    pressEscape()
    await Promise.resolve()
    await wrapper.findAll('button').find((b) => b.text() === '直接退出（作废）')!.trigger('click')
    expect(wrapper.emitted('close')).toHaveLength(1)
  })

  test('脏表单：「存为草稿并退出」发出 save-draft（父组件成功才关弹窗）', async () => {
    const wrapper = mountForm()
    await makeDirty(wrapper, '存草稿退出')
    await wrapper.findAll('button').find((b) => b.text() === '取消')!.trigger('click')
    expect(wrapper.emitted('close')).toBeUndefined()

    await wrapper.findAll('button').find((b) => b.text() === '存为草稿并退出')!.trigger('click')
    expect(wrapper.emitted('save-draft')).toHaveLength(1)
    expect(wrapper.emitted('close')).toBeUndefined()
  })

  test('文案按模式分派：编辑草稿=保存草稿并退出，修订=保存修订并退出', async () => {
    const draftWrapper = mountForm({ title: '草稿标题' }, { editingDraft: { id: 'd-1', version: 3 } })
    await draftWrapper.find('input[placeholder="任务标题"]').setValue('草稿标题改')
    await draftWrapper.findAll('button').find((b) => b.text() === '取消编辑')!.trigger('click')
    expect(draftWrapper.text()).toContain('保存草稿并退出')

    const reviseWrapper = mountForm({ title: '修订标题' }, { revisingTask: { id: 't-1', version: 2 } })
    await reviseWrapper.find('input[placeholder="任务标题"]').setValue('修订标题改')
    await reviseWrapper.findAll('button').find((b) => b.text() === '取消编辑')!.trigger('click')
    expect(reviseWrapper.text()).toContain('返回将清空当前已填内容，要先保存修订吗？')
    expect(reviseWrapper.text()).toContain('保存修订并退出')
  })

  test('确认框开着时 Esc 只收确认框，不穿透再弹', async () => {
    const wrapper = mountForm()
    await makeDirty(wrapper, '穿透测试')
    pressEscape()
    await Promise.resolve()
    expect(exitConfirm(wrapper)).toBeDefined()

    pressEscape()
    await Promise.resolve()
    expect(exitConfirm(wrapper)).toBeUndefined()
    // 主弹窗本体未受影响（未被关闭）
    expect(mainModal(wrapper).exists()).toBe(true)
    expect(wrapper.emitted('close')).toBeUndefined()
  })

  test('提交失败提示渲染在弹窗内告警条（不再写到被盖住的背景页）', async () => {
    const wrapper = mountForm({}, { notice: '已有 2 名推荐官报名成功，任务不可再修改' })
    const alert = wrapper.find('.gl-alert-error')
    expect(alert.exists()).toBe(true)
    expect(alert.text()).toContain('已有 2 名推荐官报名成功')
  })
})
