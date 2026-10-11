// @vitest-environment happy-dom
import { mount } from '@vue/test-utils'
import { ref } from 'vue'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import XhsImageSeriesPanel from './XhsImageSeriesPanel.vue'
import CardSeriesPanel from '../../article/components/CardSeriesPanel.vue'
import { useCardSeries } from '../../../composables/useCardSeries'
import { XHS_STUDIO_CONTEXT_KEY } from '../types'
import type { XhsStudioContext } from '../types'

/**
 * 配图生成区块（方案 §4.3 工程师B / §6 测试计划）：
 * 挂载阈值（正文 ≥50 字）、CardSeriesPanel 透传（legacy v1 链不传 plan/job）、
 * open-lightbox 冒泡、【F4】生成尺寸与发布建议两段分列文案。
 */

const LONG_CONTENT = '周末露营装备清单一篇讲透：天幕、折叠桌椅、卡式炉、营地灯与驱蚊装备'
  + '逐项给出选购要点和避坑提示，附新手营地预约与雨天备案建议，照着买不踩坑。'

const SHORT_CONTENT = '正文不足五十字。'

/** 最小 context mock：组件只读消费 engine.content / cards / autosave.readonly。 */
function mockContext(overrides: Record<string, unknown> = {}): XhsStudioContext {
  return {
    engine: { content: ref('') },
    cards: useCardSeries('xiaohongshu'),
    autosave: { readonly: ref(false) },
    ...overrides,
  } as unknown as XhsStudioContext
}

function mountPanel(props: Record<string, unknown> = {}, context = mockContext()) {
  return mount(XhsImageSeriesPanel, {
    props,
    global: { provide: { [XHS_STUDIO_CONTEXT_KEY as symbol]: context } },
  })
}

beforeEach(() => {
  // useCardSeries 实例化本身无请求；兜底拦截面板内误发的真实网络调用。
  vi.stubGlobal('fetch', vi.fn(async () => new Response('{}', { status: 503 })))
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('挂载阈值（同旧视图正文 ≥50 字）', () => {
  test('正文不足 50 字：不挂 CardSeriesPanel，显示真实空态说明', () => {
    const wrapper = mountPanel({ content: SHORT_CONTENT })
    expect(wrapper.findComponent(CardSeriesPanel).exists()).toBe(false)
    expect(wrapper.get('.xhs-series-empty').text()).toContain('正文 ≥50 字')
  })

  test('正文 ≥50 字：挂 CardSeriesPanel（F3：生成步不因 stage=check 消失）', () => {
    const wrapper = mountPanel({ content: LONG_CONTENT })
    expect(wrapper.findComponent(CardSeriesPanel).exists()).toBe(true)
    expect(wrapper.find('.xhs-series-empty').exists()).toBe(false)
  })

  test('缺省从 context 自取 engine.content（props 未覆写时）', () => {
    const context = mockContext()
    context.engine.content.value = LONG_CONTENT
    expect(mountPanel({}, context).findComponent(CardSeriesPanel).exists()).toBe(true)
  })
})

describe('CardSeriesPanel 透传（legacy v1 真实链）', () => {
  test('platform/content/series 透传；plan/job 不传；disabled 默认 false', () => {
    const context = mockContext()
    const wrapper = mountPanel({ content: LONG_CONTENT }, context)
    const panel = wrapper.getComponent(CardSeriesPanel)
    expect(panel.props('platform')).toBe('xiaohongshu')
    expect(panel.props('content')).toBe(LONG_CONTENT)
    expect(panel.props('series')).toBe(context.cards)
    expect(panel.props('plan')).toBeUndefined()
    expect(panel.props('job')).toBeUndefined()
    expect(panel.props('disabled')).toBe(false)
  })

  test('版本冲突只读（autosave.readonly=true）时 disabled 透传', () => {
    const context = mockContext()
    ;(context.autosave.readonly as { value: boolean }).value = true
    const panel = mountPanel({ content: LONG_CONTENT }, context).getComponent(CardSeriesPanel)
    expect(panel.props('disabled')).toBe(true)
  })
})

describe('open-lightbox 冒泡', () => {
  test('面板内成功卡放大事件上抛视图层 ArticleLightbox', () => {
    const wrapper = mountPanel({ content: LONG_CONTENT })
    wrapper.getComponent(CardSeriesPanel).vm.$emit('open-lightbox', 'https://img.example/1.png')
    expect(wrapper.emitted('open-lightbox')).toEqual([['https://img.example/1.png']])
  })
})

describe('【F4】规格说明：生成尺寸与发布建议两段分列', () => {
  test('文案含「生成尺寸默认 1024×1792 竖版」与契约发布建议句', () => {
    const spec = mountPanel({ content: SHORT_CONTENT }).get('.xhs-series-spec').text()
    expect(spec).toContain('生成尺寸')
    expect(spec).toContain('1024×1792 竖版')
    expect(spec).toContain('发布建议：首图 3:4 占屏最佳，9:16 与 1:1 亦可')
    // 生成尺寸不是发布承诺：3:4 只出现在发布建议语境，不作生成规格断言。
    expect(spec.indexOf('1024×1792')).toBeLessThan(spec.indexOf('发布建议'))
  })
})
