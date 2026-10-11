// @vitest-environment happy-dom
import { mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { computed, ref } from 'vue'
import XhsTopicTagsEditor from './XhsTopicTagsEditor.vue'
import { getPlatformFormatRule } from '../../../config/platform-format-rules'
import { parseHashtagTopics } from '../../../lib/creation-delivery'
import { useArticleCreation } from '../../../composables/useArticleCreation'
import { XHS_STUDIO_CONTEXT_KEY } from '../types'
import type { XhsStudioContext } from '../types'

/**
 * 话题标签 chips（方案 §6）：增删/去 #/≤30 字/上限 10、update→updateDelivery({topics})、
 * 缺省 parseHashtagTopics 从正文派生、契约 tagHint 建议文案。
 */

/** 模拟 deliveryValue/updateDelivery 的「编辑后固定」语义（对齐 useArticleWorkspace）。 */
function mockContext(content = '') {
  const engine = useArticleCreation()
  engine.platform.value = 'xiaohongshu'
  engine.content.value = content
  const deliveryTopics = ref<string[] | undefined>(undefined)
  const deliveryValue = computed(() => ({
    topics: deliveryTopics.value ?? parseHashtagTopics(engine.content.value),
  }))
  const updateDelivery = vi.fn((value: { topics?: string[] }) => {
    deliveryTopics.value = value.topics
  })
  const ctx = {
    engine,
    autosave: { deliveryValue, updateDelivery, readonly: computed(() => false) },
    format: { formatRule: computed(() => getPlatformFormatRule('xiaohongshu')) },
  } as unknown as XhsStudioContext
  return { ctx, updateDelivery }
}

function mountEditor(ctx: XhsStudioContext) {
  return mount(XhsTopicTagsEditor, {
    global: { provide: { [XHS_STUDIO_CONTEXT_KEY]: ctx } },
  })
}

beforeEach(() => { vi.clearAllMocks() })
afterEach(() => { vi.restoreAllMocks() })

describe('缺省派生（parseHashtagTopics 口径）', () => {
  test('正文 #话题 行自动带出（去重、上限内）', () => {
    const { ctx } = mockContext('种草正文\n#通勤穿搭 #平价好物\n#通勤穿搭')
    const wrapper = mountEditor(ctx)

    const tags = wrapper.findAll('.xhs-tag-text').map(el => el.text())
    expect(tags).toEqual(['# 通勤穿搭', '# 平价好物'])
  })

  test('契约 tagHint 建议文案常读展示', () => {
    const { ctx } = mockContext('')
    const wrapper = mountEditor(ctx)
    expect(wrapper.get('.gl-hint').text()).toContain('3-8 个话题')
  })

  test('无话题时空态说明（不渲染假标签）', () => {
    const { ctx } = mockContext('没有话题行的正文')
    const wrapper = mountEditor(ctx)
    expect(wrapper.text()).toContain('还没有话题标签')
    expect(wrapper.findAll('.xhs-tag-chip')).toHaveLength(0)
  })
})

describe('编辑（增删与规范化）', () => {
  test('添加去 # 前缀；回车与按钮等价；update→updateDelivery({topics})', async () => {
    const { ctx, updateDelivery } = mockContext('#已有话题')
    const wrapper = mountEditor(ctx)

    await wrapper.get('.xhs-tag-add input').setValue('#新话题')
    await wrapper.get('.xhs-tag-add input').trigger('keydown.enter')
    const tags = wrapper.findAll('.xhs-tag-text').map(el => el.text())
    expect(tags).toEqual(['# 已有话题', '# 新话题'])
    expect(updateDelivery).toHaveBeenCalledWith({ topics: ['已有话题', '新话题'] })
    // tsconfig lib=ES2020 无 Array.prototype.at，用 slice 取末次事件。
    expect(wrapper.emitted('update')?.slice(-1)[0]).toEqual([['已有话题', '新话题']])
    // 添加后输入框清空。
    expect((wrapper.get('.xhs-tag-add input').element as HTMLInputElement).value).toBe('')
  })

  test('单个话题 ≤30 字截断', async () => {
    const { ctx } = mockContext('')
    const wrapper = mountEditor(ctx)
    await wrapper.get('.xhs-tag-add input').setValue(`#${'长'.repeat(40)}`)
    await wrapper.get('.xhs-tag-add button').trigger('click')
    const tags = wrapper.findAll('.xhs-tag-text').map(el => el.text())
    expect(tags).toEqual([`# ${'长'.repeat(30)}`])
  })

  test('重复话题提示且不重复添加', async () => {
    const { ctx, updateDelivery } = mockContext('#通勤穿搭')
    const wrapper = mountEditor(ctx)

    await wrapper.get('.xhs-tag-add input').setValue('通勤穿搭')
    await wrapper.get('.xhs-tag-add button').trigger('click')
    expect(wrapper.get('.xhs-tags-error').text()).toContain('已添加过')
    expect(wrapper.findAll('.xhs-tag-chip')).toHaveLength(1)
    expect(updateDelivery).not.toHaveBeenCalled()
  })

  test('上限 10 个：满员隐藏添加行并提示；删除后恢复', async () => {
    const topics = Array.from({ length: 10 }, (_, i) => `话题${i}`)
    const { ctx, updateDelivery } = mockContext(topics.map(t => `#${t}`).join(' '))
    const wrapper = mountEditor(ctx)

    expect(wrapper.findAll('.xhs-tag-chip')).toHaveLength(10)
    expect(wrapper.find('.xhs-tag-add').exists()).toBe(false)
    expect(wrapper.text()).toContain('已达上限 10 个')

    await wrapper.findAll('.xhs-tag-remove')[0].trigger('click')
    expect(updateDelivery).toHaveBeenCalledWith({ topics: topics.slice(1) })
    expect(wrapper.find('.xhs-tag-add').exists()).toBe(true)
  })

  test('删除指定话题（aria-label 带话题名）', async () => {
    const { ctx, updateDelivery } = mockContext('#甲话题 #乙话题')
    const wrapper = mountEditor(ctx)

    const removes = wrapper.findAll('.xhs-tag-remove')
    expect(removes[1].attributes('aria-label')).toBe('删除话题 乙话题')
    await removes[1].trigger('click')
    expect(updateDelivery).toHaveBeenCalledWith({ topics: ['甲话题'] })
    expect(wrapper.findAll('.xhs-tag-text').map(el => el.text())).toEqual(['# 甲话题'])
  })
})
