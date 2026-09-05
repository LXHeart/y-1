// @vitest-environment happy-dom
import { mount } from '@vue/test-utils'
import { describe, expect, test } from 'vitest'
import MerchantTaskForm from './MerchantTaskForm.vue'

/**
 * 任务书 #62 卡7：商家任务表单的「目标问题」（P4 拍板）。
 *
 * 零外呼红线（#62 §3.7）：粘贴链接只走本地正则提取 questionId，组件不发任何请求。
 * 本文件不 stub fetch —— 若哪天有人加了抓取，happy-dom 里未定义的 fetch 会直接炸出来。
 */

const baseForm = {
  title: '知乎任务', description: '', platform: 'zhihu', contentForm: 'image', maxSlots: 1,
  interactionTargetUrl: '', interactionActionType: 'like',
  bountyYuan: '', freebieDepositYuan: '', paymentMode: 'commission' as 'commission' | 'freebie',
  applicationDeadline: '', minRecommenderLevel: 1,
  autoAcceptMinLevel: null as number | null, productServiceInfo: '', mustInclude: '',
  forbiddenContent: '', publishStartAt: '', publishEndAt: '', metricRequirements: '',
  evidenceRequirements: '', questionText: '', questionRef: '',
}

function mountForm(form: Partial<typeof baseForm> = {}) {
  return mount(MerchantTaskForm, {
    props: {
      form: { ...baseForm, ...form }, open: true, editingDraft: null, revisingTask: null,
      stores: [], selectedStoreId: '', activeOrgId: 'org-1', hasOrganizationAccess: true,
      canPublishBounty: true, loading: false,
    },
    global: { stubs: { Teleport: true } },
  })
}

/** 取某字段最后一次 update:field 值（组件受控，父组件回写才变 props）。 */
function lastFieldValue(wrapper: ReturnType<typeof mountForm>, field: string): unknown {
  const calls = (wrapper.emitted('update:field') ?? []).filter((args) => args[0] === field)
  return calls.length > 0 ? calls[calls.length - 1][1] : undefined
}

describe('MerchantTaskForm 目标问题（任务书 #62 卡7）', () => {
  test('platform=zhihu 才渲染目标问题输入', () => {
    expect(mountForm().find('[data-testid="task-question-text"]').exists()).toBe(true)
    expect(mountForm({ platform: 'xiaohongshu' }).find('[data-testid="task-question-text"]').exists())
      .toBe(false)
    expect(mountForm({ platform: '' }).find('[data-testid="task-question-text"]').exists()).toBe(false)
  })

  test('粘贴问题链接：本地提取 questionId 并回显溯源提示，不发请求', async () => {
    const wrapper = mountForm()
    await wrapper.get('[data-testid="task-question-text"]')
      .setValue('https://www.zhihu.com/question/1999041081275355787')

    expect(lastFieldValue(wrapper, 'questionText'))
      .toBe('https://www.zhihu.com/question/1999041081275355787')
    expect(lastFieldValue(wrapper, 'questionRef')).toBe('1999041081275355787')

    // 提示条按 props.questionRef 渲染（受控组件：父组件回写后才显示）
    const withRef = mountForm({ questionRef: '1999041081275355787' })
    expect(withRef.get('[data-testid="task-question-ref"]').text())
      .toContain('已识别问题链接 #1999041081275355787')
  })

  test('手输纯文本问题：不提取 ref，也不显示溯源提示', async () => {
    const wrapper = mountForm()
    await wrapper.get('[data-testid="task-question-text"]').setValue('为什么大厂都在弃用 Kubernetes？')

    expect(lastFieldValue(wrapper, 'questionText')).toBe('为什么大厂都在弃用 Kubernetes？')
    expect(lastFieldValue(wrapper, 'questionRef')).toBe('')
    expect(wrapper.find('[data-testid="task-question-ref"]').exists()).toBe(false)
  })

  test('平台改离知乎时清空残留问题——否则提交撞后端 422', async () => {
    const wrapper = mountForm({ questionText: '为什么大厂都在弃用 Kubernetes？', questionRef: '123' })
    await wrapper.setProps({
      form: { ...baseForm, platform: 'xiaohongshu', questionText: '为什么大厂都在弃用 Kubernetes？', questionRef: '123' },
    })

    expect(lastFieldValue(wrapper, 'questionText')).toBe('')
    expect(lastFieldValue(wrapper, 'questionRef')).toBe('')
  })

  test('平台在知乎内变化不清空问题（同平台切内容形式等操作是安全的）', async () => {
    const wrapper = mountForm({ questionText: '问题原文足够长', questionRef: '' })
    await wrapper.setProps({
      form: { ...baseForm, contentForm: 'video', questionText: '问题原文足够长', questionRef: '' },
    })

    expect(lastFieldValue(wrapper, 'questionText')).toBeUndefined()
  })
})
