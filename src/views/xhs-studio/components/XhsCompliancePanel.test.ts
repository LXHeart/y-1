// @vitest-environment happy-dom
import { mount } from '@vue/test-utils'
import { computed, ref } from 'vue'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import XhsCompliancePanel from './XhsCompliancePanel.vue'
import ArticleCheckStage from '../../article/components/ArticleCheckStage.vue'
import SafetyFindingsPanel from '../../../components/SafetyFindingsPanel.vue'
import { XHS_STUDIO_CONTEXT_KEY } from '../types'
import type { XhsStudioContext } from '../types'
import type { SafetyReport } from '../../../types/content-safety'

/**
 * 平台合规检测区块（方案 §4.3 工程师B / §6 测试计划）：check 模式 ArticleCheckStage
 * 透传（imagesStageSkipped=true）、proceed/go-edit/apply-fix 事件接 context、
 * 【F-D】inline 模式挂载条件=safetyReport 存在且正文非空。
 */

const REPORT: SafetyReport = {
  findings: [{
    category: 'absolute_claims', severity: 'medium', match: '最有效',
    index: 0, advice: '建议改为具体效果描述', deep: false,
  }],
  lexiconVersion: 'lex-v1',
  deepCheck: false,
}

const BODY = '这款通勤包自重很轻，分层收纳合理，通勤两周实测背感舒适，雨天有防泼水涂层。'

interface MockOptions {
  content?: string
  safetyReport?: SafetyReport | null
  safetyChecking?: boolean
  genre?: string
  style?: string
}

/** 最小 context mock：组件消费 engine 安全字段 / tuning 目录 / steps.go。 */
function mockContext(options: MockOptions = {}) {
  const engine = {
    content: ref(options.content ?? ''),
    safetyReport: ref<SafetyReport | null>(options.safetyReport ?? null),
    safetyChecking: ref(options.safetyChecking ?? false),
    genre: ref(options.genre ?? 'practical_guide'),
    style: ref(options.style ?? 'bestie'),
    checkSafety: vi.fn(),
    onPanelRechecked: vi.fn(),
    applySafetyFix: vi.fn(),
    proceedFromCheck: vi.fn(),
  }
  const tuning = {
    genreOptions: computed(() => [
      { category: 'GENRE' as const, code: 'practical_guide', name: '干货攻略型', description: '', sortOrder: 1 },
    ]),
    styleOptions: computed(() => [
      { category: 'STYLE' as const, code: 'bestie', name: '闺蜜种草风', description: '', sortOrder: 1 },
    ]),
    formulaOptions: computed(() => []),
  }
  const steps = { current: ref('proof'), reached: ref('proof'), canGo: vi.fn(() => true), go: vi.fn() }
  return {
    context: { engine, tuning, steps } as unknown as XhsStudioContext,
    engine,
    steps,
  }
}

function mountPanel(props: { mode: 'check' | 'inline' }, context: XhsStudioContext) {
  return mount(XhsCompliancePanel, {
    props,
    global: { provide: { [XHS_STUDIO_CONTEXT_KEY as symbol]: context } },
  })
}

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn(async () => new Response('{}', { status: 503 })))
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('说明条（真实语义，无假数字）', () => {
  test('陈述词库+AI 语境检测与 advisory 不阻断；不出现规则数/重复度文案', () => {
    const { context } = mockContext({ content: BODY, safetyReport: REPORT })
    const wrapper = mountPanel({ mode: 'check' }, context)
    const note = wrapper.get('.xhs-compliance-note').text()
    expect(note).toContain('词库')
    expect(note).toContain('不阻断发布')
    expect(wrapper.text()).not.toContain('12,400')
    expect(wrapper.text()).not.toContain('重复度')
  })
})

describe("check 模式（proof 步）：ArticleCheckStage 透传", () => {
  test('platform=xiaohongshu、imagesStageSkipped=true、content/safetyReport/safetyChecking/风格名透传', () => {
    const { context } = mockContext({ content: BODY, safetyReport: REPORT, safetyChecking: false })
    const stage = mountPanel({ mode: 'check' }, context).getComponent(ArticleCheckStage)
    expect(stage.props('platform')).toBe('xiaohongshu')
    expect(stage.props('imagesStageSkipped')).toBe(true)
    expect(stage.props('content')).toBe(BODY)
    // props 经 Vue reactive 包装，按结构断言（对象字面量内容一致）。
    expect(stage.props('safetyReport')).toStrictEqual(REPORT)
    expect(stage.props('safetyChecking')).toBe(false)
    // contentForm 仅知乎需要，小红书恒 undefined。
    expect(stage.props('contentForm')).toBeUndefined()
    // 文风句展示名从 tuning 目录 + engine 选择派生（修复请求参数）。
    expect(stage.props('genreName')).toBe('干货攻略型')
    expect(stage.props('styleName')).toBe('闺蜜种草风')
  })

  test('proceed → engine.proceedFromCheck（noteMode 内部 finish→completed，由步骤机自动进 publish）', async () => {
    const { context, engine } = mockContext({ content: BODY, safetyReport: REPORT })
    const wrapper = mountPanel({ mode: 'check' }, context)
    await wrapper.get('[data-test="check-proceed"]').trigger('click')
    expect(engine.proceedFromCheck).toHaveBeenCalledTimes(1)
  })

  test("go-edit → steps.go('generate')（状态机协同置 stage='content'，组件不直接写 stage）", async () => {
    const { context, steps } = mockContext({ content: BODY, safetyReport: REPORT })
    const wrapper = mountPanel({ mode: 'check' }, context)
    await wrapper.get('[data-test="check-edit"]').trigger('click')
    expect(steps.go).toHaveBeenCalledWith('generate')
  })

  test('apply-fix → engine.applySafetyFix（回写正文并自动复查在引擎内实现，此处断言透传）', () => {
    const { context, engine } = mockContext({ content: BODY, safetyReport: REPORT })
    const wrapper = mountPanel({ mode: 'check' }, context)
    wrapper.getComponent(ArticleCheckStage).vm.$emit('apply-fix', '修复后的正文')
    expect(engine.applySafetyFix).toHaveBeenCalledWith('修复后的正文')
  })

  test('rechecked/recheck → engine.onPanelRechecked / engine.checkSafety', () => {
    const { context, engine } = mockContext({ content: BODY, safetyReport: REPORT })
    const wrapper = mountPanel({ mode: 'check' }, context)
    const stage = wrapper.getComponent(ArticleCheckStage)
    stage.vm.$emit('rechecked', REPORT)
    expect(engine.onPanelRechecked).toHaveBeenCalledWith(REPORT)
    stage.vm.$emit('recheck')
    expect(engine.checkSafety).toHaveBeenCalledTimes(1)
  })

  test('safetyChecking 缺省从 context 自取（boolean prop 未传被 cast 为 false 的回归锁）', () => {
    const { context } = mockContext({ content: BODY, safetyReport: REPORT, safetyChecking: true })
    const stage = mountPanel({ mode: 'check' }, context).getComponent(ArticleCheckStage)
    expect(stage.props('safetyChecking')).toBe(true)
  })

  test('check 模式不受 F-D 条件影响：safetyReport 为 null 时舞台仍挂载（内部显示待检查）', () => {
    const { context } = mockContext({ content: BODY, safetyReport: null })
    const wrapper = mountPanel({ mode: 'check' }, context)
    expect(wrapper.findComponent(ArticleCheckStage).exists()).toBe(true)
    expect(wrapper.text()).toContain('尚未检查')
  })
})

describe('【F-D】inline 模式（generate 步）：挂载条件=safetyReport 存在且正文非空', () => {
  test('标题期报告 + 空正文 → 整个区块不渲染（避免错配报告与静默无效复查）', () => {
    const { context } = mockContext({ content: '', safetyReport: REPORT })
    const wrapper = mountPanel({ mode: 'inline' }, context)
    expect(wrapper.findComponent(SafetyFindingsPanel).exists()).toBe(false)
    expect(wrapper.find('section').exists()).toBe(false)
  })

  test('无报告 + 正文非空 → 不渲染', () => {
    const { context } = mockContext({ content: BODY, safetyReport: null })
    expect(mountPanel({ mode: 'inline' }, context).findComponent(SafetyFindingsPanel).exists()).toBe(false)
  })

  test('正文生成完成后（报告+正文都在）→ 挂载且 text=content、platform 透传、不开逐项修复', () => {
    const { context } = mockContext({ content: BODY, safetyReport: REPORT })
    const wrapper = mountPanel({ mode: 'inline' }, context)
    const panel = wrapper.getComponent(SafetyFindingsPanel)
    expect(panel.props('report')).toStrictEqual(REPORT)
    expect(panel.props('text')).toBe(BODY)
    expect(panel.props('platform')).toBe('xiaohongshu')
    expect(panel.props('contentForm')).toBeUndefined()
    expect(panel.props('enableFix')).toBe(false)
  })

  test('updated（面板复查回写）→ engine.onPanelRechecked 同步检查快照', () => {
    const { context, engine } = mockContext({ content: BODY, safetyReport: REPORT })
    const wrapper = mountPanel({ mode: 'inline' }, context)
    wrapper.getComponent(SafetyFindingsPanel).vm.$emit('updated', REPORT)
    expect(engine.onPanelRechecked).toHaveBeenCalledWith(REPORT)
  })
})
