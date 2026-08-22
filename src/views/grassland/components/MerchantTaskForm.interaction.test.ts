// @vitest-environment happy-dom
import { mount } from '@vue/test-utils'
import { describe, expect, test } from 'vitest'
import MerchantTaskForm from './MerchantTaskForm.vue'
import RecommenderTaskHall from './RecommenderTaskHall.vue'
import type { Task } from '../../../types/grassland'

/** 任务书 #23 Stage B3：内容形式下拉受控化 + 互动任务条件字段 + 大厅徽标。 */

const baseForm = {
  title: '互动任务', description: '', platform: 'xiaohongshu', contentForm: 'image', maxSlots: 1,
  interactionTargetUrl: '', interactionActionType: 'like',
  bountyYuan: 0, freebieDepositYuan: 0, paymentMode: 'commission' as 'commission' | 'freebie', applicationDeadline: '', minRecommenderLevel: 1,
  autoAcceptMinLevel: null as number | null, productServiceInfo: '', mustInclude: '',
  forbiddenContent: '', publishStartAt: '', publishEndAt: '', metricRequirements: '',
  evidenceRequirements: '',
}

function mountForm(form: typeof baseForm) {
  return mount(MerchantTaskForm, {
    props: {
      form, editingDraft: null, revisingTask: null, stores: [], selectedStoreId: '',
      activeOrgId: 'org-1', hasOrganizationAccess: true, canPublishBounty: true, loading: false,
    },
  })
}

describe('MerchantTaskForm 内容形式下拉与互动条件字段（任务书 #23 R6）', () => {
  test('未选发布平台：内容形式为空且不可选（先定平台再定形式）', () => {
    const wrapper = mountForm({ ...baseForm, platform: '', contentForm: '' })
    const select = wrapper.get('select[name="task-content-form"]')
    expect(select.attributes('disabled')).toBe('')
    expect(select.text()).toContain('请先选择发布平台')
  })

  test('内容形式随平台能力裁剪并自动纠正（PRD §4.2 平台×形式）', () => {
    const optionsOf = (form: Partial<typeof baseForm>) =>
      mountForm({ ...baseForm, ...form })
        .get('select[name="task-content-form"]')
        .findAll('option').map((o) => o.element.value)

    // 公众号仅图文；当前值不被支持时挂载即纠正回 image
    expect(optionsOf({ platform: 'wechat-official' })).toEqual(['image', 'interaction'])
    const correction = mountForm({ ...baseForm, platform: 'wechat-official', contentForm: 'video' })
    expect(correction.emitted('update:field')?.some((a) => a[0] === 'contentForm' && a[1] === 'image'))
      .toBe(true)

    // B 站仅视频
    expect(optionsOf({ platform: 'bilibili' })).toEqual(['video', 'interaction'])
    // 小红书图文视频双能力
    expect(optionsOf({ platform: 'xiaohongshu' })).toEqual(['image', 'video', 'interaction'])
  })

  test('非互动任务不渲染互动字段；选「点赞互动」后展示目标链接与动作类型', async () => {
    const wrapper = mountForm({ ...baseForm })
    expect(wrapper.find('input[placeholder*="互动目标链接"]').exists()).toBe(false)

    const select = wrapper.get('select[name="task-content-form"]')
    await select.setValue('interaction')
    expect(wrapper.emitted('update:field')?.some((a) => a[0] === 'contentForm' && a[1] === 'interaction'))
      .toBe(true)

    const interaction = mountForm({ ...baseForm, contentForm: 'interaction' })
    expect(interaction.find('input[placeholder*="互动目标链接"]').exists()).toBe(true)
    expect(interaction.findAll('select').some((s) => s.text().includes('点赞')
      && s.text().includes('收藏') && s.text().includes('关注'))).toBe(true)
    // 缺口清偿之九：评论动作类型可选
    expect(interaction.findAll('select').some((s) => s.text().includes('评论'))).toBe(true)
  })

  test('互动字段变更发出 update:field 事件', async () => {
    const wrapper = mountForm({ ...baseForm, contentForm: 'interaction' })
    const input = wrapper.find('input[placeholder*="互动目标链接"]')
    await input.setValue('https://www.xiaohongshu.com/post/9')
    expect(wrapper.emitted('update:field')?.some(
      (a) => a[0] === 'interactionTargetUrl' && a[1] === 'https://www.xiaohongshu.com/post/9')).toBe(true)
  })
})

const interactionTask: Task = {
  id: 'task-i', ownerAccountId: 'owner-1', organizationId: 'org-1', title: '给笔记点赞',
  description: null, status: 'published', contentForm: 'interaction', platform: 'xiaohongshu',
  maxSlots: null, bountyCents: 500, freebieDepositCents: null, minRecommenderLevel: 1,
  createdAt: null, version: 1, applicationDeadline: null, publishedAt: null, cancelledAt: null,
  requirements: {
    mustInclude: [], forbiddenContent: [], metricRequirements: [], evidenceRequirements: [],
    interaction: { targetUrl: 'https://www.xiaohongshu.com/post/1', actionType: 'like' },
  },
  autoAcceptMinLevel: null,
}

function mountHall(feedItems: Task[]) {
  return mount(RecommenderTaskHall, {
    props: {
      feedItems, feedHasMore: false, feedLoading: false,
      feedFilters: {
        platform: '', contentForm: '', minBountyYuan: 0, maxDistanceKm: 0,
        latitude: null, longitude: null,
      },
      applyNote: '', selectedTaskId: '', loading: false, locating: false, walletBalanceCents: null,
    },
  })
}

describe('RecommenderTaskHall 互动任务徽标（任务书 #23 R6）', () => {
  test('contentForm=interaction 显示「点赞互动」徽标', () => {
    const wrapper = mountHall([interactionTask])
    expect(wrapper.text()).toContain('点赞互动')
  })

  test('普通任务无徽标', () => {
    const wrapper = mountHall([{ ...interactionTask, id: 'task-n', contentForm: 'video' }])
    expect(wrapper.text()).not.toContain('点赞互动')
  })
})
