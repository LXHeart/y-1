// @vitest-environment happy-dom
import { computed, ref } from 'vue'
import { mount } from '@vue/test-utils'
import { describe, expect, test } from 'vitest'
import XhsProofChecklist from './XhsProofChecklist.vue'
import { XHS_STUDIO_CONTEXT_KEY } from '../types'
import { getPlatformFormatRule } from '../../../config/platform-format-rules'
import type { XhsStudioContext } from '../types'
import type { GeneratedCard } from '../../../composables/useCardSeries'

/**
 * 发布前检查清单（方案 §6 XhsProofChecklist）：① 标题 21 字超限/19 字通过；
 * ② 正文 <50/>1000 提示；③ 话题 0/8 边界；④ 配图张数=成功卡数；⑤【F4】规格项两行
 * 分列（生成尺寸实际值 + 契约 note，无画幅核验断言）；⑥ 段落 >40 字警示；
 * ⑦ formatIssues 逐条与汇总真实计数；另覆盖 F7 inject 派生路径与 goEdit。
 */

const RULE = getPlatformFormatRule('xiaohongshu')!

function mountList(props: Record<string, unknown> = {}) {
  return mount(XhsProofChecklist, {
    props: {
      formatRule: RULE,
      selectedTitle: '',
      content: '',
      topics: [] as string[],
      successCardCount: 0,
      cardSize: '',
      formatIssues: [] as string[],
      ...props,
    },
  })
}

function item(wrapper: ReturnType<typeof mountList>, key: string) {
  return wrapper.get(`[data-test="xhs-check-${key}"]`)
}

describe('① 标题字数 vs 契约 20 字', () => {
  test('21 字 → 建议关注 + 超限提示；19 字 → 通过', () => {
    const over = mountList({ selectedTitle: 'a'.repeat(21) })
    expect(item(over, 'title').text()).toContain('21/20 字')
    expect(item(over, 'title').find('.badge-warning').text()).toBe('建议关注')
    expect(item(over, 'title').text()).toContain('超过建议上限 20 字')

    const ok = mountList({ selectedTitle: 'a'.repeat(19) })
    expect(item(ok, 'title').text()).toContain('19/20 字')
    expect(item(ok, 'title').find('.badge-success').text()).toBe('通过')
  })

  test('未选择标题 → 建议关注（不显示假通过）', () => {
    const wrapper = mountList({ selectedTitle: '' })
    expect(item(wrapper, 'title').text()).toContain('0/20 字')
    expect(item(wrapper, 'title').find('.badge-warning').exists()).toBe(true)
  })
})

describe('② 正文字数 vs 契约 50-1000 字', () => {
  test('30 字 → 低于下限提示；1001 字 → 超上限提示', () => {
    const short = mountList({ content: '短'.repeat(30) })
    expect(item(short, 'body').text()).toContain('30 字')
    expect(item(short, 'body').text()).toContain('低于建议下限 50 字')

    const long = mountList({ content: '长'.repeat(1001) })
    expect(item(long, 'body').text()).toContain('超过建议上限 1000 字')
  })

  test('字数口径与 useArticleFormatRule 一致：剥离图片 markdown 后计数', () => {
    const wrapper = mountList({ content: `${'字'.repeat(50)}\n\n![配图](https://img.example/a.png)` })
    expect(item(wrapper, 'body').text()).toContain('50 字（建议 50-1000 字）')
    expect(item(wrapper, 'body').find('.badge-success').exists()).toBe(true)
  })
})

describe('③ 话题数量 vs tagHint 建议 3-8 个', () => {
  test('0 个 → 建议关注；8 个 → 通过（边界含）', () => {
    const empty = mountList({ topics: [] })
    expect(item(empty, 'topics').text()).toContain('0 个')
    expect(item(empty, 'topics').find('.badge-warning').exists()).toBe(true)
    expect(item(empty, 'topics').text()).toContain('尚未添加话题')

    const edge = mountList({ topics: Array.from({ length: 8 }, (_, i) => `话题${i}`) })
    expect(item(edge, 'topics').text()).toContain('8 个（建议 3-8 个）')
    expect(item(edge, 'topics').find('.badge-success').exists()).toBe(true)
  })

  test('9 个 → 建议关注', () => {
    const over = mountList({ topics: Array.from({ length: 9 }, (_, i) => `话题${i}`) })
    expect(item(over, 'topics').find('.badge-warning').exists()).toBe(true)
  })
})

describe('④ 配图张数 = 成功卡数', () => {
  test('0 张 → 建议关注；4 张 → 通过（真实计数，无阈值断言）', () => {
    const none = mountList({ successCardCount: 0 })
    expect(item(none, 'images').text()).toContain('0 张成功图卡')
    expect(item(none, 'images').find('.badge-warning').exists()).toBe(true)

    const four = mountList({ successCardCount: 4 })
    expect(item(four, 'images').text()).toContain('4 张成功图卡')
    expect(item(four, 'images').find('.badge-success').exists()).toBe(true)
  })
})

describe('⑤【F4】配图规格两行分列（无画幅核验断言）', () => {
  test('生成尺寸=cards.size 实际值；发布建议=契约 note 文案；状态为参考不计入汇总', () => {
    const wrapper = mountList({ cardSize: '1024x1792' })
    const spec = item(wrapper, 'spec')
    expect(spec.text()).toContain('生成尺寸 1024x1792')
    expect(spec.text()).toContain('发布建议：首图 3:4 占屏最佳，9:16 与 1:1 亦可（平台规范）。')
    // 不做画幅核验断言：恒为参考态徽标。
    expect(spec.find('.badge-neutral').text()).toBe('参考')
    expect(spec.find('.badge-warning').exists()).toBe(false)
    expect(spec.find('.badge-success').exists()).toBe(false)
  })
})

describe('⑥ 段落长度本地警示（任一段 >40 字）', () => {
  test('41 字段落 → badge-warning + 段位提示；全短段 → 通过', () => {
    const long = mountList({ content: `${'段'.repeat(41)}\n\n短段落。` })
    expect(item(long, 'paragraphs').text()).toContain('最长段落 41 字')
    expect(item(long, 'paragraphs').find('.badge-warning').exists()).toBe(true)
    expect(item(long, 'paragraphs').text()).toContain('第 1 段超过 40 字')

    const short = mountList({ content: '短段落一。\n\n短段落二。' })
    expect(item(short, 'paragraphs').find('.badge-success').exists()).toBe(true)
  })
})

describe('⑦ formatIssues 与汇总真实计数', () => {
  test('formatIssues 逐条渲染在规范提示区', () => {
    const wrapper = mountList({
      content: '短'.repeat(30),
      formatIssues: ['正文约 30 字，低于建议下限 50 字，建议补充核心信息。', '必须包含项「探店」尚未出现在标题或正文中。'],
    })
    const issues = wrapper.get('[data-test="xhs-check-issues"]')
    expect(issues.findAll('li')).toHaveLength(2)
    expect(issues.text()).toContain('必须包含项「探店」')
  })

  test('汇总=N 项通过/M 项建议关注 为真实计数（参考项不计入）', () => {
    // 标题超限（关注）+ 正文 30 字（关注）+ 话题 0（关注）+ 配图 0（关注）+ 规格（参考）
    // + 段落全短（通过）→ 1 通过 / 4 建议关注。
    const wrapper = mountList({ selectedTitle: 'a'.repeat(21), content: '短'.repeat(30) })
    const summary = wrapper.get('.xhs-check-summary')
    expect(summary.text()).toContain('1 项通过')
    expect(summary.text()).toContain('4 项建议关注')
  })

  test('无 formatIssues 时不渲染规范提示区', () => {
    const wrapper = mountList()
    expect(wrapper.find('[data-test="xhs-check-issues"]').exists()).toBe(false)
  })
})

describe('F7 inject 派生与 goEdit', () => {
  test('未传 props 时从 inject 上下文只读派生（成功卡数/话题/规范）', () => {
    const results: GeneratedCard[] = [
      { index: 0, cardId: 'a', title: '卡a', ok: true, url: 'https://img.example/a.png' },
      { index: 1, cardId: 'b', title: '卡b', ok: false },
      { index: 2, cardId: 'c', title: '卡c', ok: true, url: 'https://img.example/c.png' },
    ]
    const context = {
      engine: { selectedTitle: ref('注入的标题'), content: ref(`${'文'.repeat(60)}\n\n#探店 #攻略`) },
      cards: { results: ref(results), size: ref('1024x1024') },
      autosave: { deliveryValue: computed(() => ({ topics: ['探店', '攻略', '探店指南', '周未去哪', '露营', '亲子'] })) },
      format: { formatIssues: computed(() => ['必须包含项「门店」尚未出现在标题或正文中。']), formatRule: computed(() => RULE) },
    } as unknown as XhsStudioContext
    const wrapper = mount(XhsProofChecklist, {
      global: { provide: { [XHS_STUDIO_CONTEXT_KEY as symbol]: context } },
    })
    expect(item(wrapper, 'title').text()).toContain('注入的标题'.length + '/20 字')
    expect(item(wrapper, 'images').text()).toContain('2 张成功图卡') // 仅成功卡计数
    expect(item(wrapper, 'spec').text()).toContain('生成尺寸 1024x1024')
    expect(item(wrapper, 'topics').text()).toContain('6 个（建议 3-8 个）')
    expect(wrapper.get('[data-test="xhs-check-issues"]').text()).toContain('必须包含项「门店」')
  })

  test('返回创作修改按钮 emit goEdit', async () => {
    const wrapper = mountList()
    await wrapper.get('[data-test="xhs-check-go-edit"]').trigger('click')
    expect(wrapper.emitted('goEdit')).toHaveLength(1)
  })
})
