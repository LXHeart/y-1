// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { defineComponent, h, KeepAlive, nextTick, ref } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import XhsStudioView from './XhsStudioView.vue'
import { useAuth } from '../../composables/useAuth'
import { projectAsDraft, useCreationDraftSessions } from '../../lib/creation-draft-session'
import type { CreationHandoff } from '../../types/ai-creation'
import type { CreationProject } from '../../types/creation'

/**
 * 视图骨架（阶段 0）：三栏布局 + 步骤条 + 四步占位 + 左栏真实装配 +
 * provide 链路（默认视图自身 provide，mount 即集成验证）。
 */

const SKILLS_FIXTURE = {
  success: true,
  data: {
    skills: [
      { category: 'GENRE', code: 'practical_guide', name: '干货攻略型', description: '', sortOrder: 1 },
      { category: 'STYLE', code: 'bestie', name: '闺蜜种草风', description: '', sortOrder: 1 },
      { category: 'TITLE_FORMULA', code: 'number', name: '数字型', description: '', sortOrder: 1 },
    ],
  },
}

function stubFetch(drafts: Record<string, Record<string, unknown>> = {}) {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    if (url === '/api/creation-style-skills') {
      return { ok: true, json: async () => SKILLS_FIXTURE } as Response
    }
    if (url === '/api/article-generation/titles') {
      return {
        ok: true,
        json: async () => ({ success: true, data: { titles: [{ title: '候选标题一', hook: '' }] } }),
      } as Response
    }
    const draftMatch = url.match(/^\/api\/creation-drafts\/([\w-]+)$/)
    if (draftMatch && drafts[draftMatch[1]]) {
      // creation-workspace 的本地 request 走 response.text()——必须给真 Response（仅 {json()} 的
      // 简化 stub 会让 text 缺失而抛错，恢复链路静默吞掉返回 null）。
      return new Response(JSON.stringify({ success: true, data: drafts[draftMatch[1]] }), {
        status: 200, headers: { 'Content-Type': 'application/json' },
      })
    }
    return new Response('{}', { status: 503 })
  }))
}

/** 小红书 article 草稿夹具（useArticleWorkspace 的 applyProject 消费面：顶层字段 + inputs.article）。 */
function xhsDraftFixture(id: string, overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    id,
    title: '春日通勤穿搭草稿',
    capability: 'article',
    status: 'in_progress',
    version: 5,
    platform: 'xiaohongshu',
    topic: '春日通勤穿搭',
    articleTitle: '3 套春日通勤公式',
    outline: '',
    content: '春天通勤想要又轻又有型。第一套用针织开衫配直筒裤，显高不挑人。第二套是衬衫裙加小白鞋，通勤约会两相宜。第三套风衣内搭高领，早晚温差也不怕。',
    workspace: {
      schemaVersion: 1,
      capability: 'article',
      currentStep: 'check',
      inputs: { article: { completed: false } },
    },
    resultAssetIds: [],
    runIds: [],
    updatedAt: '2026-10-10T00:00:00Z',
    ...overrides,
  }
}

async function mountView(handoff?: CreationHandoff | null, path = '/xhs-studio') {
  document.documentElement.dataset.app = 'ai'
  const pinia = createPinia()
  setActivePinia(pinia)
  useAuth().currentUser.value = { id: 'u-1', email: 'creator@example.com', role: 'user', roles: [] }
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div />' } },
      { path: '/xhs-studio', name: 'xhs-studio', component: XhsStudioView },
      { path: '/ai-center', name: 'ai-center', component: { template: '<div />' } },
    ],
  })
  // ?draft= 恢复在 setup 期一次性读取 route.query——先完成导航再挂载。
  await router.push(path)
  return mount(XhsStudioView, {
    props: { creationHandoff: handoff ?? null },
    global: { plugins: [pinia, router] },
  })
}

beforeEach(() => { stubFetch() })

afterEach(() => {
  vi.unstubAllGlobals()
  delete document.documentElement.dataset.app
})

describe('骨架结构', () => {
  test('三栏布局 + 顶栏 + 步骤条（四步固定文案）+ 左栏配置卡渲染', async () => {
    const wrapper = await mountView()
    await flushPromises()

    expect(wrapper.find('.xhs-layout').exists()).toBe(true)
    expect(wrapper.find('.xhs-side').exists()).toBe(true)
    expect(wrapper.find('.xhs-stage.glass-card').exists()).toBe(true)
    expect(wrapper.find('.xhs-preview').exists()).toBe(true)

    expect(wrapper.findAll('.xhs-step-dot').map((el) => el.text())).toEqual(['1选题', '2创作', '3校对', '4发布'])
    expect(wrapper.text()).toContain('小红书')
    expect(wrapper.text()).toContain('种草图文')
    expect(wrapper.text()).toContain('创作配置')
    expect(wrapper.text()).toContain('平台规范')
    // 左栏目录真实拉取渲染（服务端目录驱动，不硬编码）。
    expect(wrapper.text()).toContain('干货攻略型')
  })

  test('pick 步渲染选题面板（自定义表单 + 热榜真实源），右栏手机预览挂载', async () => {
    const wrapper = await mountView()
    await flushPromises()
    // 工程师A 的选题面板接管 pick 步：题目/角度/概要表单 + 热榜区块（拉取失败走真实错误态）。
    expect(wrapper.find('.xhs-topic-picker').exists()).toBe(true)
    expect(wrapper.text()).toContain('选题灵感')
    expect(wrapper.find('#xhs-topic-title').exists()).toBe(true)
    expect(wrapper.find('#xhs-topic-summary').exists()).toBe(true)
    expect(wrapper.find('.xhs-topic-hot').exists()).toBe(true)
    // 右栏已由 XhsPhonePreview 接管（并行阶段替换占位）：固定浅色手机壳挂载。
    expect(wrapper.find('.xhs-preview .xhs-phone').exists()).toBe(true)
  })
})

describe('四步显隐与步骤条联动', () => {
  test('超出 reached 的步 disabled；选题步点生成（titles 成功）后自动进创作步、占位切换', async () => {
    // handoff 预填 topic（pick 步生成按钮的可用条件），其余状态与直入一致。
    const wrapper = await mountView({
      revision: 1,
      platformId: 'xiaohongshu',
      contentFormId: 'graphic',
      workflowId: 'longform',
      targetView: 'xhs-studio',
      source: { type: 'independent' },
      prefill: { topic: '通勤穿搭' },
    })
    await flushPromises()
    const dots = wrapper.findAll('.xhs-step-dot')
    // 初始 reached=pick：创作/校对/发布均不可进；选题步为 current。
    expect(dots[1].attributes('disabled')).toBeDefined()
    expect(dots[1].attributes('aria-disabled')).toBe('true')
    expect(dots[0].classes()).toContain('xhs-step-active')

    // 左栏「以当前选题生成」→ fetchTitles 真实链 → titles 非空 → go('generate')。
    expect(wrapper.get('.xhs-config-generate').attributes('disabled')).toBeUndefined()
    await wrapper.get('.xhs-config-generate').trigger('click')
    await flushPromises()
    // 工程师A 的标题候选/正文编辑/话题标签接入创作步（真实 titles 渲染）；
    // 配图区块保持真实空态（正文不足阈值），inline 合规区块因正文为空不挂载（F-D）。
    expect(wrapper.find('.xhs-title-candidates').exists()).toBe(true)
    expect(wrapper.text()).toContain('候选标题一')
    expect(wrapper.find('.xhs-body-editor').exists()).toBe(true)
    expect(wrapper.find('.xhs-tags-editor').exists()).toBe(true)
    expect(wrapper.find('.xhs-series').exists()).toBe(true)
    expect(wrapper.text()).toContain('正文 ≥50 字后')
    expect(wrapper.find('.xhs-compliance').exists()).toBe(false)
    // reached 派生为 generate：选题步可点回看，校对仍 disabled。
    expect(wrapper.findAll('.xhs-step-dot')[0].attributes('disabled')).toBeUndefined()
    expect(wrapper.findAll('.xhs-step-dot')[2].attributes('disabled')).toBeDefined()
  })

  test('点击「发布」超深步不切面板（canGo 拒绝）', async () => {
    const wrapper = await mountView()
    await flushPromises()
    await wrapper.findAll('.xhs-step-dot')[3].trigger('click')
    await flushPromises()
    expect(wrapper.find('.xhs-topic-picker').exists()).toBe(true)
  })
})

describe('handoff 进入（F1 适配层经视图整链）', () => {
  test('targetView=xhs-studio 的 handoff：prefill.topic 到达引擎，初始停在选题步', async () => {
    const handoff: CreationHandoff = {
      revision: 3,
      platformId: 'xiaohongshu',
      contentFormId: 'graphic',
      workflowId: 'longform',
      targetView: 'xhs-studio',
      source: { type: 'independent' },
      prefill: { topic: '周末露营装备清单' },
    }
    const wrapper = await mountView(handoff)
    await flushPromises()
    // 初始停在选题步：面板挂载且 handoff 预填的 topic 到达表单题目（prefill 单向同步）。
    expect(wrapper.find('.xhs-topic-picker').exists()).toBe(true)
    expect((wrapper.get('#xhs-topic-title').element as HTMLInputElement).value).toBe('周末露营装备清单')
    // 左栏生成按钮在 pick 步且 topic 非空时可用（canGenerate 派生正确）。
    expect(wrapper.get('.xhs-config-generate').attributes('disabled')).toBeUndefined()
  })
})

describe('保存与 toast', () => {
  test('保存草稿按钮触发 flush（无草稿内容时 flush 直接成功）并出现 toast', async () => {
    vi.useFakeTimers()
    try {
      const wrapper = await mountView()
      await flushPromises()
      await wrapper.get('.xhs-topbar-save').trigger('click')
      await flushPromises()
      expect(wrapper.find('.xhs-toast').exists()).toBe(true)
      vi.advanceTimersByTime(1900)
      await flushPromises()
      expect(wrapper.find('.xhs-toast').exists()).toBe(false)
    } finally {
      vi.useRealTimers()
    }
  })
})

describe('?draft= 恢复路径与 proof/publish 步装配（集成层）', () => {
  test('恢复 stage=check 草稿：初始落校对步，合规检查（check 模式）与检查清单挂载、内容真实回填', async () => {
    stubFetch({ 'draft-xhs-check': xhsDraftFixture('draft-xhs-check') })
    const wrapper = await mountView(null, '/xhs-studio?draft=draft-xhs-check')
    await flushPromises()

    // F-B 恢复重算：currentStep='check' → current='proof'（setup 期 stage 尚为 topic，靠钩子对齐）。
    expect(wrapper.find('.xhs-compliance[data-mode="check"]').exists()).toBe(true)
    expect(wrapper.find('.xhs-proof-checklist').exists()).toBe(true)
    // 生成步组件不渲染；右栏预览展示恢复出的真实标题/正文。
    expect(wrapper.find('.xhs-body-editor').exists()).toBe(false)
    expect(wrapper.find('.xhs-preview .xhs-phone').exists()).toBe(true)
    expect(wrapper.text()).toContain('3 套春日通勤公式')
    expect(wrapper.text()).toContain('第一套用针织开衫')
  })

  test('恢复已完成草稿：初始落发布步，发布面板挂载（含平台约束警示与配图排序列表）', async () => {
    stubFetch({
      'draft-xhs-done': xhsDraftFixture('draft-xhs-done', {
        status: 'completed',
        workspace: { schemaVersion: 1, capability: 'article', currentStep: 'check', inputs: { article: { completed: true } } },
      }),
    })
    const wrapper = await mountView(null, '/xhs-studio?draft=draft-xhs-done')
    await flushPromises()

    // completed=true → current='publish'（唯一自动跳转步）。
    expect(wrapper.find('.xhs-publish').exists()).toBe(true)
    expect(wrapper.text()).toContain('小红书未开放内容发布 API')
    expect(wrapper.find('.xhs-proof-checklist').exists()).toBe(false)
  })

  test('恢复正文草稿（stage=content）：初始落创作步，生成区四组件按序挂载', async () => {
    stubFetch({
      'draft-xhs-content': xhsDraftFixture('draft-xhs-content', {
        workspace: { schemaVersion: 1, capability: 'article', currentStep: 'content', inputs: { article: { completed: false } } },
      }),
    })
    const wrapper = await mountView(null, '/xhs-studio?draft=draft-xhs-content')
    await flushPromises()

    expect(wrapper.find('.xhs-title-candidates').exists()).toBe(true)
    expect(wrapper.find('.xhs-body-editor').exists()).toBe(true)
    expect(wrapper.find('.xhs-tags-editor').exists()).toBe(true)
    expect(wrapper.find('.xhs-series').exists()).toBe(true)
    // 正文 ≥50 字：配图面板挂真实 CardSeriesPanel 空态（未生成图卡）。
    expect(wrapper.find('.xhs-compliance[data-mode="inline"]').exists()).toBe(false)
  })
})

describe('【F-01】pick 步找回两项既有能力（创作简报 + 我的文风）', () => {
  test('pick 步挂载 CreationBriefEditor（补充要求收敛）与 VoiceProfilePanel（xiaohongshu 支持）', async () => {
    const wrapper = await mountView()
    await flushPromises()

    // 创作简报：表达身份/已确认经历/商业关系可编辑；extraInstructions 由选题表单承担（hideFields）。
    const brief = wrapper.findComponent({ name: 'CreationBriefEditor' })
    expect(brief.exists()).toBe(true)
    expect(brief.text()).toContain('表达身份')
    // 补充要求字段已收敛：只剩「已确认的经历/商业关系说明」两个 textarea（summary 标题仍含字样）。
    expect(brief.findAll('textarea')).toHaveLength(2)
    // 我的文风：VOICE_PLATFORMS 含 xiaohongshu → supported 渲染。
    expect(wrapper.find('[data-testid="voice-profile-panel"]').exists()).toBe(true)

    // 非 pick 步不占版面（生成步为工作区组件）。
    stubFetch()
    const wrapper2 = await mountView({
      revision: 1,
      platformId: 'xiaohongshu',
      contentFormId: 'graphic',
      workflowId: 'longform',
      targetView: 'xhs-studio',
      source: { type: 'independent' },
      prefill: { topic: '通勤穿搭' },
    })
    await flushPromises()
    await wrapper2.get('.xhs-config-generate').trigger('click')
    await flushPromises()
    expect(wrapper2.find('.xhs-title-candidates').exists()).toBe(true)
    expect(wrapper2.findComponent({ name: 'CreationBriefEditor' }).exists()).toBe(false)
    expect(wrapper2.find('[data-testid="voice-profile-panel"]').exists()).toBe(false)
  })
})

describe('【N1/F5】KeepAlive 共享会话隔离（方案用例⑥/⑦：gate 短路与缓存视图本地状态不被改写）', () => {
  test('deactivated 期间共享 session 写入被 gate 短路；activate 后下一次写入恢复 apply', async () => {
    // 创建链：保存草稿 → POST /api/creation-drafts → draft-k1（同池会话按 id 命中）。
    const draftK1 = (version: number, topic: string) => xhsDraftFixture('draft-k1', {
      version,
      topic,
      articleTitle: '',
      content: '',
      workspace: { schemaVersion: 1, capability: 'article', currentStep: 'topic', inputs: {} },
    })
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      if (url === '/api/creation-style-skills') return { ok: true, json: async () => SKILLS_FIXTURE } as Response
      if (url === '/api/creation-drafts' && init?.method === 'POST') {
        return new Response(JSON.stringify({ success: true, data: draftK1(1, '隔离前选题') }), {
          status: 200, headers: { 'Content-Type': 'application/json' },
        })
      }
      if (url === '/api/creation-drafts/draft-k1') {
        return new Response(JSON.stringify({ success: true, data: draftK1(2, '隔离前选题') }), {
          status: 200, headers: { 'Content-Type': 'application/json' },
        })
      }
      return new Response('{}', { status: 503 })
    }))

    document.documentElement.dataset.app = 'ai'
    const pinia = createPinia()
    setActivePinia(pinia)
    useAuth().currentUser.value = { id: 'u-1', email: 'creator@example.com', role: 'user', roles: [] }
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/xhs-studio', name: 'xhs-studio', component: XhsStudioView },
      ],
    })
    await router.push('/xhs-studio')

    const show = ref(true)
    let getSession!: ReturnType<typeof useCreationDraftSessions>
    const handoff: CreationHandoff = {
      revision: 1,
      platformId: 'xiaohongshu',
      contentFormId: 'graphic',
      workflowId: 'longform',
      targetView: 'xhs-studio',
      source: { type: 'independent' },
      prefill: { topic: '隔离前选题' },
    }
    const Kept = defineComponent({
      setup: () => {
        // 同 pinia 池的 session getter（与视图 autosave 共享 draft 会话）。
        getSession = useCreationDraftSessions()
        return () => h(KeepAlive, null, {
          default: () => (show.value ? h(XhsStudioView, { creationHandoff: handoff }) : h('div')),
        })
      },
    })
    const wrapper = mount(Kept, { global: { plugins: [pinia, router] } })
    await flushPromises()
    const view = wrapper.getComponent(XhsStudioView)
    const topicOf = () => (view.vm as unknown as { engine: { topic: { value: string } } }).engine.topic.value
    expect(topicOf()).toBe('隔离前选题')

    // 保存草稿 → 创建 draft-k1（会话注册进共享池）。
    await view.get('.xhs-topbar-save').trigger('click')
    await flushPromises()
    const shared = getSession('draft-k1')
    expect(shared).toBeTruthy()

    // 用例⑥：切走（deactivated → gate=false）期间，另一视图对同会话 adopt 新版本——
    // apply 被 gate 短路，缓存视图本地状态不被共享写入改写。
    show.value = false
    await nextTick()
    shared.adopt(projectAsDraft(draftK1(6, '共享会话写入的新选题') as unknown as CreationProject))
    await nextTick()
    expect(topicOf()).toBe('隔离前选题')

    // 用例⑦方向（隔离解除后）：切回（activate）→ 下一次共享写入恢复 apply。
    show.value = true
    await nextTick()
    await flushPromises()
    shared.adopt(projectAsDraft(draftK1(7, '恢复后写入的新选题') as unknown as CreationProject))
    await nextTick()
    expect(topicOf()).toBe('恢复后写入的新选题')

    wrapper.unmount()
    await flushPromises()
  })
})
