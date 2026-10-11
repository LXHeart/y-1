// @vitest-environment happy-dom
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, test } from 'vitest'
import XhsSideConfigPanel from './XhsSideConfigPanel.vue'
import { useAuth } from '../../../composables/useAuth'
import type { CreationStyleSkillOption } from '../../../types/article-creation'

/**
 * 左栏创作配置（阶段 0 最终态，方案 §4.1/§6）：三组风格 chips 单选、目标人群单选与
 * 清空、目录 loading/error/retry、生成按钮禁用与未登录上抛、积分余额三态、规范卡文案。
 */

const option = (code: string, name: string, applicablePlatforms?: string[]): CreationStyleSkillOption =>
  ({ category: 'GENRE', code, name, description: '', sortOrder: 1, ...(applicablePlatforms ? { applicablePlatforms } : {}) })

const GENRE = [option('practical_guide', '干货攻略型'), option('review', '种草测评型')]
const STYLE = [option('bestie', '闺蜜种草风')]
const FORMULA = [option('number', '数字型'), option('suspense', '悬念型')]

function baseProps(overrides: Record<string, unknown> = {}) {
  return {
    genreOptions: GENRE,
    styleOptions: STYLE,
    formulaOptions: FORMULA,
    genre: '',
    style: '',
    titleFormula: '',
    loading: false,
    error: '',
    audience: '',
    balance: null as number | null,
    balanceError: '',
    formatSummary: '小红书规范建议：正文 50-1000 字；标题上限 20 字；图片建议 3:4(1080×1440)。',
    emojiHint: 'Emoji 可用可不用，按本人表达习惯决定。',
    canGenerate: true,
    generating: false,
    generateLabel: '✦ AI 生成内容',
    ...overrides,
  }
}

function mountPanel(props: Record<string, unknown> = {}) {
  return mount(XhsSideConfigPanel, { props: baseProps(props), global: { plugins: [pinia] } })
}

let pinia: ReturnType<typeof createPinia>
let auth: ReturnType<typeof useAuth>

beforeEach(() => {
  pinia = createPinia()
  setActivePinia(pinia)
  auth = useAuth()
  auth.currentUser.value = { id: 'u-1', email: 'creator@example.com', role: 'user', roles: [] }
})

afterEach(() => {
  auth.currentUser.value = null
})

function chipsOf(wrapper: ReturnType<typeof mountPanel>, label: string) {
  return wrapper.get(`[aria-label="${label}"]`).findAll('button')
}

describe('三组风格 chips 单选', () => {
  test('渲染目录并按选中态高亮（aria-checked + gl-chip-active）', () => {
    const wrapper = mountPanel({ genre: 'review', style: 'bestie', titleFormula: 'number' })
    const genreChips = chipsOf(wrapper, '内容赛道')
    expect(genreChips.map((c) => c.text())).toEqual(['干货攻略型', '种草测评型'])
    expect(genreChips[0].attributes('aria-checked')).toBe('false')
    expect(genreChips[1].attributes('aria-checked')).toBe('true')
    expect(genreChips[1].classes()).toContain('gl-chip-active')

    expect(chipsOf(wrapper, '内容语气')[0].classes()).toContain('gl-chip-active')
    expect(chipsOf(wrapper, '标题套路')[0].classes()).toContain('gl-chip-active')
  })

  test('点击 emit update:genre/update:style/update:titleFormula；再点同项不清空（emit 相同值）', async () => {
    const wrapper = mountPanel({ genre: 'review' })
    const chips = chipsOf(wrapper, '内容赛道')
    await chips[0].trigger('click')
    expect(wrapper.emitted('update:genre')).toEqual([['practical_guide']])
    await chips[1].trigger('click')
    expect(wrapper.emitted('update:genre')).toEqual([['practical_guide'], ['review']])
  })
})

describe('目标人群', () => {
  test('三段固定值，选中写 audience；再点同项清空（update 传空串）', async () => {
    const wrapper = mountPanel({ audience: '职场人' })
    const chips = chipsOf(wrapper, '目标人群')
    expect(chips.map((c) => c.text())).toEqual(['学生党', '职场人', '宝妈'])
    expect(chips[1].attributes('aria-checked')).toBe('true')

    await chips[0].trigger('click')
    expect(wrapper.emitted('update:audience')).toEqual([['学生党']])
    await chips[1].trigger('click')
    expect(wrapper.emitted('update:audience')).toEqual([['学生党'], ['']])
  })
})

describe('目录加载态', () => {
  test('loading 显示加载文案、chips 为空不渲染假目录', () => {
    const wrapper = mountPanel({ loading: true, genreOptions: [], styleOptions: [], formulaOptions: [] })
    expect(wrapper.text()).toContain('风格目录加载中…')
    expect(wrapper.find('[aria-label="内容赛道"] button').exists()).toBe(false)
  })

  test('失败显示 error + 显式重试按钮', async () => {
    const wrapper = mountPanel({ error: '风格目录加载失败' })
    expect(wrapper.get('.xhs-config-error').text()).toContain('风格目录加载失败')
    await wrapper.get('.xhs-config-retry').trigger('click')
    expect(wrapper.emitted('retry')).toHaveLength(1)
  })
})

describe('生成按钮', () => {
  test('禁用条件：generating 或 !canGenerate；文案随 generating 切换', () => {
    const disabled = mountPanel({ generating: true })
    const button = disabled.get('.xhs-config-generate')
    expect(button.attributes('disabled')).toBeDefined()
    expect(button.text()).toBe('生成中…')

    const noTopic = mountPanel({ canGenerate: false, generateLabel: '✦ AI 生成内容' })
    expect(noTopic.get('.xhs-config-generate').attributes('disabled')).toBeDefined()
  })

  test('已登录点击 emit generate；未登录点击 emit request-login（不 emit generate）', async () => {
    const wrapper = mountPanel()
    await wrapper.get('.xhs-config-generate').trigger('click')
    expect(wrapper.emitted('generate')).toHaveLength(1)
    expect(wrapper.emitted('request-login')).toBeUndefined()

    auth.currentUser.value = null
    const anon = mountPanel()
    await anon.get('.xhs-config-generate').trigger('click')
    expect(anon.emitted('request-login')).toHaveLength(1)
    expect(anon.emitted('generate')).toBeUndefined()
  })
})

describe('积分余额（真实值，不伪造 0）', () => {
  test('null→「…」；错误→「获取失败」；数字→「N 次」', () => {
    expect(mountPanel({ balance: null, balanceError: '' }).get('.xhs-config-credits').text()).toContain('…')
    expect(mountPanel({ balance: null, balanceError: '获取积分失败' }).get('.xhs-config-credits').text()).toContain('获取失败')
    expect(mountPanel({ balance: 12 }).get('.xhs-config-credits').text()).toContain('12 次')
  })
})

describe('平台规范常读卡', () => {
  test('真实契约句 + emojiHint 提示；无滑杆等假控件', () => {
    const wrapper = mountPanel()
    expect(wrapper.get('.xhs-config-summary').text()).toContain('正文 50-1000 字')
    expect(wrapper.get('.xhs-config-summary').text()).toContain('标题上限 20 字')
    expect(wrapper.text()).toContain('Emoji 可用可不用')
    expect(wrapper.find('input[type="range"]').exists()).toBe(false)
  })
})
