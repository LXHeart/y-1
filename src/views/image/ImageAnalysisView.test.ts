// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import ImageAnalysisView from '../../views/image/ImageAnalysisView.vue'
import type { CreationHandoff } from '../../types/ai-creation'

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

/**
 * 创作中心 → 大众点评图文 → 本页的 handoff 契约：
 * 平台定死大众点评（不出现淘宝切换）、补充感受预填、页级返回创作中心入口。
 */
describe('ImageAnalysisView 创作中心 handoff 模式', () => {
  function dianpingHandoff(): CreationHandoff {
    return {
      revision: 1,
      platformId: 'dianping',
      contentFormId: 'graphic',
      source: { type: 'independent' },
      workflowId: 'review-copy',
      targetView: 'image',
      prefill: { topic: '城西新开的本帮菜馆', instructions: '语气自然，突出招牌菜' },
    }
  }

  test('handoff 后平台锁定大众点评：无淘宝切换、展示锁定标识与预填感受', async () => {
    const wrapper = mount(ImageAnalysisView, { props: { creationHandoff: dianpingHandoff() } })
    await flushPromises()

    expect(wrapper.find('[aria-label="评价平台"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('淘宝')
    const chip = wrapper.find('.platform-locked-chip')
    expect(chip.exists()).toBe(true)
    expect(chip.text()).toContain('大众点评')

    const feelings = wrapper.get('textarea')
    expect((feelings.element as HTMLTextAreaElement).value).toContain('城西新开的本帮菜馆')
    expect((feelings.element as HTMLTextAreaElement).value).toContain('语气自然，突出招牌菜')

    const hint = wrapper.find('.platform-position-hint')
    expect(hint.text()).toContain('探店评价')
  })

  test('清空不解除平台锁定：仍无淘宝切换', async () => {
    const wrapper = mount(ImageAnalysisView, { props: { creationHandoff: dianpingHandoff() } })
    await flushPromises()

    const clearBtn = wrapper.find('.action-row').findAll('button').find((b) => b.text().includes('清空'))!
    await clearBtn.trigger('click')

    expect(wrapper.find('[aria-label="评价平台"]').exists()).toBe(false)
    expect(wrapper.find('.platform-locked-chip').exists()).toBe(true)
    expect(wrapper.find('.platform-position-hint').text()).toContain('探店评价')
  })

  test('页级返回入口 emit open-view 回创作中心', async () => {
    const wrapper = mount(ImageAnalysisView)
    await flushPromises()

    const back = wrapper.find('.page-back .btn-back')
    expect(back.exists()).toBe(true)
    expect(back.text()).toContain('返回创作中心')
    await back.trigger('click')
    expect(wrapper.emitted('open-view')?.[0]).toEqual(['ai-center'])
  })

  test('无 handoff 直达时顶部仅返回入口、不显示流程上下文标识', async () => {
    const wrapper = mount(ImageAnalysisView)
    await flushPromises()

    expect(wrapper.find('.page-back-context').exists()).toBe(false)
    expect(wrapper.find('.platform-locked-chip').exists()).toBe(false)
  })
})

/**
 * 任务书 #47 S7a / D18②：飞书凭据从顶部「分析设置」modal 搬到本视图。
 *
 * <p>这个入口是 S7c 删 modal 的<b>前提</b>——modal 一旦删掉、这里又没有入口，用户就再也
 * 配不了飞书凭据，而后端仍在读那些值（表现为「功能还在跑但改不了配置」）。
 */
describe('ImageAnalysisView 飞书凭据内联入口', () => {
  test('挂载时不请求设置；折叠按钮就位', async () => {
    const wrapper = mount(ImageAnalysisView)
    await flushPromises()

    // 挂载阶段不该为了这个折叠面板去拉设置
    expect(fetchUrls.some((u) => u.includes('/api/settings/analysis'))).toBe(false)

    const toggle = wrapper.find('button[data-action="toggle-feishu-config"]')
    expect(toggle.exists()).toBe(true)
    expect(toggle.text()).toContain('配置飞书导出凭据')
  })

  test('保存只提交 integrations.feishu 局部对象，密钥留空则不传', async () => {
    const wrapper = mount(ImageAnalysisView)
    await flushPromises()

    await wrapper.find('button[data-action="toggle-feishu-config"]').trigger('click')
    await flushPromises()

    await wrapper.get('input[name="feishuAppId"]').setValue('cli_test_app')
    await wrapper.get('input[name="feishuFolderToken"]').setValue('fldtest')
    // 刻意不填 appSecret：既有掩码语义是「留空 = 保持不变」
    await wrapper.get('form.feishu-form').trigger('submit')
    await flushPromises()

    const putCall = (fetch as unknown as { mock: { calls: unknown[][] } }).mock.calls
      .find((call) => (call[1] as RequestInit | undefined)?.method === 'PUT')
    expect(putCall).toBeTruthy()
    const body = JSON.parse(String((putCall![1] as RequestInit).body))
    // 只带飞书子树——后端掩码感知 merge 保证其余字段不动
    expect(Object.keys(body)).toEqual(['integrations'])
    expect(body.integrations.feishu.appId).toBe('cli_test_app')
    expect(body.integrations.feishu.folderToken).toBe('fldtest')
    expect(body.integrations.feishu).not.toHaveProperty('appSecret')
  })

  test('提交后明文密钥不留在 DOM 里', async () => {
    const wrapper = mount(ImageAnalysisView)
    await flushPromises()

    await wrapper.find('button[data-action="toggle-feishu-config"]').trigger('click')
    await flushPromises()

    const secretInput = wrapper.get('input[name="feishuAppSecret"]')
    await secretInput.setValue('secret-plaintext-value')
    await wrapper.get('form.feishu-form').trigger('submit')
    expect((secretInput.element as HTMLInputElement).value).toBe('')
    await flushPromises()

    expect(wrapper.text()).not.toContain('secret-plaintext-value')
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
