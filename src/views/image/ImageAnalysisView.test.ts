// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import ImageAnalysisView from '../../views/image/ImageAnalysisView.vue'

/**
 * ImageAnalysisView 特征测试（重构安全网）。
 *
 * 只锁定可观察行为：渲染骨架、平台选择器初始状态、主按钮的可用性条件、
 * 挂载时不发请求。不测试实现细节。
 */

const fetchUrls: string[] = []

beforeEach(() => {
  fetchUrls.length = 0
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    fetchUrls.push(url)
    return {
      ok: true,
      status: 200,
      headers: { get: () => 'application/json' },
      json: async () => ({ success: true, data: {} }),
    }
  }))
})

afterEach(() => {
  vi.unstubAllGlobals()
})

enableAutoUnmount(afterEach)

describe('ImageAnalysisView 渲染骨架与初始状态', () => {
  test('锁定标题、说明、上传入口与表单区块', async () => {
    const wrapper = mount(ImageAnalysisView)
    await flushPromises()

    expect(wrapper.find('.section-kicker').text()).toBe('图片评价')
    expect(wrapper.find('.section-title').text()).toBe('上传图片后生成探店评价与消费体验文案')
    expect(wrapper.find('.section-note').text()).toContain('最多 6 张')
    // 上传入口：隐藏的 file input 与 drop zone
    const fileInput = wrapper.find('#image-analysis-input')
    expect(fileInput.exists()).toBe(true)
    expect(fileInput.attributes('accept')).toBe('image/jpeg,image/png,image/webp')
    expect(fileInput.attributes('multiple')).toBe('')
    expect(wrapper.find('label[for="image-analysis-input"] .drop-zone-title').text()).toBe('点击上传或拖入图片')
    // 表单区块
    expect(wrapper.text()).toContain('生成偏好')
    expect(wrapper.text()).toContain('补充感受')
    expect(wrapper.find('textarea').exists()).toBe(true)
  })

  test('平台选择器锁定：淘宝/大众点评，默认选中淘宝', async () => {
    const wrapper = mount(ImageAnalysisView)
    await flushPromises()

    const tablist = wrapper.get('[aria-label="评价平台"]')
    const buttons = tablist.findAll('button')
    expect(buttons.map((b) => b.text())).toEqual(['淘宝', '大众点评'])
    expect(buttons[0].classes()).toContain('platform-btn-active')
    expect(buttons[1].classes()).not.toContain('platform-btn-active')
  })

  test('目标字数默认 0、生成评价在无图片时禁用、清空可用', async () => {
    const wrapper = mount(ImageAnalysisView)
    await flushPromises()

    const lengthInput = wrapper.find('input[type="number"]')
    expect(lengthInput.exists()).toBe(true)
    expect((lengthInput.element as HTMLInputElement).value).toBe('0')

    const actionButtons = wrapper.find('.action-row').findAll('button')
    const generateBtn = actionButtons.find((b) => b.text().includes('生成评价'))!
    const clearBtn = actionButtons.find((b) => b.text().includes('清空'))!
    expect(generateBtn.attributes('disabled')).toBe('')
    expect(clearBtn.attributes('disabled')).toBeUndefined()
    // 没有生成结果/进度时右侧不展示结果卡
    expect(wrapper.find('.result-card').exists()).toBe(false)
    expect(wrapper.find('.progress-card').exists()).toBe(false)
  })

  test('挂载时不发起任何网络请求', async () => {
    mount(ImageAnalysisView)
    await flushPromises()

    expect(fetchUrls).toEqual([])
  })
})

describe('ImageAnalysisView 大众点评探店定位', () => {
  test('默认淘宝时展示淘宝定位文案，不含探店关键词', async () => {
    const wrapper = mount(ImageAnalysisView)
    await flushPromises()

    const hint = wrapper.find('.platform-position-hint')
    expect(hint.exists()).toBe(true)
    expect(hint.text()).toContain('淘宝评价定位')
    expect(hint.text()).not.toContain('探店评价')
  })

  test('选中大众点评后展示探店评价/消费体验/推荐理由定位文案', async () => {
    const wrapper = mount(ImageAnalysisView)
    await flushPromises()

    const tablist = wrapper.get('[aria-label="评价平台"]')
    await tablist.findAll('button')[1].trigger('click')

    const hint = wrapper.find('.platform-position-hint')
    expect(hint.text()).toContain('探店评价')
    expect(hint.text()).toContain('消费体验')
    expect(hint.text()).toContain('推荐理由')
    expect(hint.text()).toContain('真实体验')
  })
})

describe('ImageAnalysisView 多版本对比入口', () => {
  test('右侧顶部渲染多版本对比卡与保存入口，初始为空态', async () => {
    const wrapper = mount(ImageAnalysisView)
    await flushPromises()

    const card = wrapper.find('.session-versions-card')
    expect(card.exists()).toBe(true)
    expect(card.find('.session-versions-title').text()).toBe('多版本对比')
    const saveBtn = card.findAll('button').find((b) => b.text().includes('保存当前版本'))!
    expect(saveBtn.exists()).toBe(true)
    // 无结果时保存入口禁用，列表为空态提示
    expect(saveBtn.attributes('disabled')).toBe('')
    expect(card.find('.session-versions-empty').exists()).toBe(true)
    expect(card.find('.session-versions-list').exists()).toBe(false)
  })
})
