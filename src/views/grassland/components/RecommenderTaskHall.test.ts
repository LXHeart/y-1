// @vitest-environment happy-dom
import { enableAutoUnmount, mount } from '@vue/test-utils'
import { afterEach, describe, expect, test } from 'vitest'
import RecommenderTaskHall from './RecommenderTaskHall.vue'
import type { Task } from '../../../types/grassland'

enableAutoUnmount(afterEach)

const BASE_FILTERS = {
  q: '', platform: '', contentForm: '', minBountyYuan: 0, maxDistanceKm: 0,
  latitude: null, longitude: null,
}

interface HallProps {
  feedItems: Task[]
  feedHasMore: boolean
  feedLoading: boolean
  feedPage: number
  feedLimit: number
  feedFilters: typeof BASE_FILTERS
  applyNote: string
  selectedTaskId: string
  loading: boolean
  locating: boolean
  walletBalanceCents?: number | null
  myApplications?: Record<string, import('../../../types/grassland').MyApplication>
}

function makeTask(id: string): Task {
  return { id, title: `任务 ${id}`, status: 'published', bountyCents: 1000 } as Task
}

function mountHall(props: Partial<HallProps> = {}) {
  const defaults: HallProps = {
    feedItems: [makeTask('t-1'), makeTask('t-2')],
    feedHasMore: true,
    feedLoading: false,
    feedPage: 0,
    feedLimit: 10,
    feedFilters: { ...BASE_FILTERS },
    applyNote: '',
    selectedTaskId: '',
    loading: false,
    locating: false,
  }
  return mount(RecommenderTaskHall, { props: { ...defaults, ...props } })
}

describe('RecommenderTaskHall 筛选下拉与分页（2026-09-04 改造）', () => {
  test('平台筛选是下拉：全部平台 + 九平台选项，change 透传 update:feedFilter', async () => {
    const wrapper = mountHall()
    const select = wrapper.get('select[name="task-platform-filter"]')
    const values = select.findAll('option').map((o) => o.attributes('value'))
    expect(values[0]).toBe('')
    expect(values).toContain('xiaohongshu')
    expect(values).toContain('zhihu')
    expect(values).toHaveLength(10) // 空 + 九平台

    await select.setValue('douyin')
    const emitted = wrapper.emitted('update:feedFilter')
    expect(emitted![emitted!.length - 1]).toEqual(['platform', 'douyin'])
  })

  test('内容形式选项随平台裁剪：平台不限=三形式；快手（仅视频）=视频+互动', async () => {
    const wrapper = mountHall()
    const formSelect = wrapper.get('select[name="task-content-form-filter"]')

    // 平台不限：三形式全开
    let values = formSelect.findAll('option').map((o) => o.attributes('value'))
    expect(values).toEqual(['', 'image', 'video', 'interaction'])

    // 模拟父组件回写 platform=快手（能力表仅 VIDEO）：图文被裁掉
    await wrapper.setProps({ feedFilters: { ...BASE_FILTERS, platform: 'kuaishou' } })
    values = wrapper.get('select[name="task-content-form-filter"]').findAll('option').map((o) => o.attributes('value'))
    expect(values).toEqual(['', 'video', 'interaction'])
  })

  test('切换平台时当前形式不被支持则自动清空（筛选器归「不限」）', async () => {
    const wrapper = mountHall({ feedFilters: { ...BASE_FILTERS, platform: 'xiaohongshu', contentForm: 'image' } })

    // 切到快手：图文不支持 → 连发 platform 与 contentForm 清空两条
    await wrapper.get('select[name="task-platform-filter"]').setValue('kuaishou')
    const emitted = wrapper.emitted('update:feedFilter') ?? []
    expect(emitted).toContainEqual(['platform', 'kuaishou'])
    expect(emitted).toContainEqual(['contentForm', ''])
  })

  test('URL 恢复等外部 platform 变化同样触发形式清空兜底（watch）', async () => {
    const wrapper = mountHall({ feedFilters: { ...BASE_FILTERS, platform: 'xiaohongshu', contentForm: 'video' } })
    // 父组件把平台改到知乎（能力表仅 GRAPHIC）：视频不被支持 → watch 兜底清空
    await wrapper.setProps({ feedFilters: { ...BASE_FILTERS, platform: 'zhihu', contentForm: 'video' } })
    expect(wrapper.emitted('update:feedFilter')).toContainEqual(['contentForm', ''])
  })

  test('分页条：空结果不渲染；有结果时显示页码并按状态禁用', async () => {
    const empty = mountHall({ feedItems: [] })
    expect(empty.find('nav[aria-label="任务大厅分页"]').exists()).toBe(false)

    const wrapper = mountHall({ feedPage: 0, feedHasMore: true })
    const pager = wrapper.get('nav[aria-label="任务大厅分页"]')
    expect(pager.text()).toContain('第 1 页')
    const [prev, next] = pager.findAll('button')
    expect(prev.attributes('disabled')).toBeDefined() // 首页禁用上一页
    expect(next.attributes('disabled')).toBeUndefined()

    // 下一页 → load-feed(false)；上一页 → load-feed-prev
    await next.trigger('click')
    expect(wrapper.emitted('load-feed')).toEqual([[false]])

    const last = mountHall({ feedPage: 2, feedHasMore: false })
    const [prevBtn, nextBtn] = last.get('nav[aria-label="任务大厅分页"]').findAll('button')
    expect(nextBtn.attributes('disabled')).toBeDefined() // 末页禁用下一页
    expect(prevBtn.attributes('disabled')).toBeUndefined()
    await prevBtn.trigger('click')
    expect(last.emitted('load-feed-prev')).toHaveLength(1)

    expect(last.get('nav[aria-label="任务大厅分页"]').text()).toContain('第 3 页')
  })

  test('每页条数选择器：10/20/50 三档，change 透传 update:feedLimit（2026-09-04 反馈 3）', async () => {
    const wrapper = mountHall()
    const limitSelect = wrapper.get('select[name="task-feed-limit"]')
    const values = limitSelect.findAll('option').map((o) => Number(o.attributes('value')))
    expect(values).toEqual([10, 20, 50])
    expect((limitSelect.element as HTMLSelectElement).value).toBe('10')

    await limitSelect.setValue('50')
    expect(wrapper.emitted('update:feedLimit')).toEqual([[50]])
  })

  test('查询按钮重置回首页（既有行为回归）', async () => {
    const wrapper = mountHall({ feedPage: 3 })
    const queryBtn = wrapper.findAll('button').find((b) => b.text() === '查询')!
    await queryBtn.trigger('click')
    expect(wrapper.emitted('load-feed')).toEqual([[true]])
  })

  test('行内举报按钮已撤（迁入详情卡）；未选中任务不渲染详情卡（2026-09-04 反馈 2）', () => {
    const wrapper = mountHall()
    expect(wrapper.findAll('button').some((b) => b.text() === '举报')).toBe(false)
    expect(wrapper.find('[data-testid="task-detail-card"]').exists()).toBe(false)
  })

  test('操作列五态：报名/取消报名/处理中/去创作/详情（#77 卡 C，「已报名」禁用态废除）', async () => {
    const wrapper = mountHall({
      myApplications: {
        't-1': { applicationId: 'app-1', taskId: 't-1', applicationStatus: 'pending' },
        't-2': { applicationId: 'app-2', taskId: 't-2', applicationStatus: 'reserving' },
        't-3': { applicationId: 'app-3', taskId: 't-3', applicationStatus: 'accepted' },
        't-4': { applicationId: 'app-4', taskId: 't-4', applicationStatus: 'withdrawn' },
      } as never,
      feedItems: [makeTask('t-1'), makeTask('t-2'), makeTask('t-3'), makeTask('t-4'), makeTask('t-5')],
    })

    const rowButton = (row: number, text: string) =>
      wrapper.get(`tbody tr:nth-child(${row})`).findAll('button').find((b) => b.text() === text)

    // 未报名 → 报名（e2e 行级锚文案不变）；pending → 取消报名；reserving → 禁用「处理中」
    expect(rowButton(5, '报名')).toBeDefined()
    await rowButton(1, '取消报名')!.trigger('click')
    expect(wrapper.emitted('withdraw')).toHaveLength(1)
    expect(wrapper.emitted('withdraw')![0][0]).toMatchObject({ applicationId: 'app-1' })
    const reservingBtn = rowButton(2, '处理中')!
    expect(reservingBtn.attributes('disabled')).toBeDefined()
    // accepted → 去创作（整包抛给父级走快照链）；终态 withdrawn → 详情（不可再报名——UNIQUE 阻断）
    await rowButton(3, '去创作')!.trigger('click')
    const startEvents = wrapper.emitted('start-creation')
    expect(startEvents).toHaveLength(1)
    expect(startEvents![0][0]).toMatchObject({ task: { id: 't-3' }, application: { applicationId: 'app-3' } })
    expect(rowButton(4, '详情')).toBeDefined()
    await rowButton(4, '详情')!.trigger('click')
    expect(wrapper.emitted('select-task')).toEqual([['t-4']])
    // 「已报名」禁用按钮不再出现
    expect(wrapper.findAll('button').some((b) => b.text() === '已报名')).toBe(false)
  })

  test('平台列中文：canonical id 显中文，未知值兜底显原文（#77 卡 C）', () => {
    const wrapper = mountHall({
      feedItems: [
        { ...makeTask('t-1'), platform: 'xiaohongshu' },
        { ...makeTask('t-2'), platform: '自由文本平台' },
      ],
    })
    expect(wrapper.get('tbody tr:nth-child(1) td:nth-child(3)').text()).toBe('小红书')
    expect(wrapper.get('tbody tr:nth-child(2) td:nth-child(3)').text()).toBe('自由文本平台')
  })

  test('行内报名徽标：pending=已报名·待处理；accepted=已报名·履约中；未报名不渲染徽标（2026-09-04 反馈 4）', () => {
    const pending = mountHall({
      myApplications: { 't-1': { applicationId: 'app-1', taskId: 't-1', applicationStatus: 'pending' } } as never,
    })
    expect(pending.get('tbody tr:nth-child(1)').text()).toContain('已报名 · 待处理')
    expect(pending.find('tbody tr:nth-child(2) .badge').exists()).toBe(false)

    const accepted = mountHall({
      myApplications: { 't-2': { applicationId: 'app-2', taskId: 't-2', applicationStatus: 'accepted' } } as never,
    })
    expect(accepted.get('tbody tr:nth-child(2)').text()).toContain('已报名 · 履约中')
  })
})
