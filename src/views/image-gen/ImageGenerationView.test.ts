// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import ImageGenerationView from '../../views/image-gen/ImageGenerationView.vue'

/**
 * ImageGenerationView 特征测试（重构安全网）。
 *
 * 锁定：渲染骨架、尺寸选择器初始状态、主按钮可用性条件、
 * 生成请求的 URL 与 FormData 载荷、成功结果的渲染。
 * 全部网络请求通过 mock fetch 拦截，无真实网络调用。
 */

type FetchCall = { url: string; init?: RequestInit }

const calls: FetchCall[] = []

beforeEach(() => {
  calls.length = 0
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    calls.push({ url, init })
    return {
      ok: true,
      status: 200,
      json: async () => ({
        success: true,
        data: { imageUrl: 'https://example.com/generated.png', revisedPrompt: '优化后的提示词' },
      }),
    }
  }))
})

afterEach(() => {
  vi.unstubAllGlobals()
})

enableAutoUnmount(afterEach)

describe('ImageGenerationView 渲染骨架与初始状态', () => {
  test('锁定标题、提示词输入与上传入口', () => {
    const wrapper = mount(ImageGenerationView)

    expect(wrapper.find('.section-title').text()).toBe('图片生成')
    // 文案对齐素材生成定位：为图文/视频制作生成封面、配图与素材
    expect(wrapper.find('.section-desc').text()).toContain('为图文/视频制作生成封面、配图与素材')
    expect(wrapper.find('.section-desc').text()).toContain('AI 帮你生成图片')
    // 参考素材使用权授权提示（静态、不阻断）
    const authNote = wrapper.find('.auth-note')
    expect(authNote.exists()).toBe(true)
    expect(authNote.text()).toContain('请确认您拥有上传素材的使用权')
    expect(authNote.text()).toContain('人脸、商标、店铺招牌或个人信息')
    const textarea = wrapper.find('textarea.prompt-input')
    expect(textarea.exists()).toBe(true)
    expect(textarea.attributes('placeholder')).toContain('描述你想生成的图片')
    expect(wrapper.find('.char-count').text()).toBe('0 / 4000')
    expect(wrapper.find('button.upload-btn').text()).toBe('+ 上传素材')
    // 初始无素材区、无结果、无错误
    expect(wrapper.find('.materials-area').exists()).toBe(false)
    expect(wrapper.find('.results-grid').exists()).toBe(false)
    expect(wrapper.find('.error-msg').exists()).toBe(false)
  })

  test('尺寸选择器锁定：三个比例，默认 1:1', () => {
    const wrapper = mount(ImageGenerationView)

    const buttons = wrapper.findAll('.size-btn')
    expect(buttons.map((b) => b.text())).toEqual(['1:1', '2:3', '3:2'])
    expect(buttons[0].classes()).toContain('size-btn-active')
    expect(buttons[1].classes()).not.toContain('size-btn-active')
    expect(buttons[2].classes()).not.toContain('size-btn-active')
  })

  test('主按钮初始禁用，输入提示词后启用', async () => {
    const wrapper = mount(ImageGenerationView)
    const genBtn = wrapper.get('button.gen-btn')

    expect(genBtn.text()).toBe('生成图片')
    expect(genBtn.attributes('disabled')).toBe('')

    await wrapper.find('textarea.prompt-input').setValue('一只橘色的猫坐在窗台上')
    expect(genBtn.attributes('disabled')).toBeUndefined()
  })
})

describe('ImageGenerationView 生成交互', () => {
  test('点击生成图片：POST FormData 到正确 URL，成功后渲染结果', async () => {
    const wrapper = mount(ImageGenerationView)

    await wrapper.find('textarea.prompt-input').setValue('一只橘色的猫坐在窗台上')
    await wrapper.findAll('.size-btn')[2].trigger('click') // 选 3:2
    await wrapper.get('button.gen-btn').trigger('click')
    await flushPromises()

    expect(calls).toHaveLength(1)
    expect(calls[0].url).toBe('/api/article-generation/generate-image')
    expect(calls[0].init?.method).toBe('POST')
    // 统一生图入口后无参考素材走 JSON 主体（有素材仍 FormData），两种契约都带 prompt/size
    const body = calls[0].init?.body
    const payload: Record<string, string> = body instanceof FormData
      ? { prompt: String(body.get('prompt')), size: String(body.get('size')) }
      : JSON.parse(String(body))
    expect(payload.prompt).toBe('一只橘色的猫坐在窗台上')
    expect(payload.size).toBe('1792x1024')

    // 结果卡渲染：图片、优化提示词、下载与复制提示词入口
    const resultCard = wrapper.get('.result-card')
    expect(resultCard.find('img').attributes('src')).toBe('https://example.com/generated.png')
    expect(resultCard.find('.result-prompt').text()).toBe('优化后的提示词')
    expect(resultCard.find('a[download]').attributes('href')).toBe('https://example.com/generated.png')
    expect(resultCard.text()).toContain('复制提示词')
    // 按钮恢复
    expect(wrapper.get('button.gen-btn').text()).toBe('生成图片')
  })

  test('生成失败时展示错误信息', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      calls.push({ url, init })
      return {
        ok: true,
        status: 200,
        json: async () => ({ success: false, error: '图片生成额度不足' }),
      }
    }))
    const wrapper = mount(ImageGenerationView)

    await wrapper.find('textarea.prompt-input').setValue('测试提示词')
    await wrapper.get('button.gen-btn').trigger('click')
    await flushPromises()

    expect(wrapper.find('.error-msg').text()).toBe('图片生成额度不足')
    expect(wrapper.find('.results-grid').exists()).toBe(false)
  })

  test('挂载时不发起任何网络请求', async () => {
    mount(ImageGenerationView)
    await flushPromises()

    expect(calls).toEqual([])
  })
})
