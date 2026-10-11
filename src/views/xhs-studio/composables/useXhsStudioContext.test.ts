// @vitest-environment happy-dom
import { createPinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h, KeepAlive, nextTick, ref } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { provideXhsStudioContext, useXhsStudioContext } from './useXhsStudioContext'
import type { XhsStudioContext } from '../types'
import type { CreationHandoff } from '../../../types/ai-creation'

/**
 * 视图上下文装配（方案 §2.4/§2.5/§4.1）：inject 契约、F1 handoff 改写副本、
 * toast 1.9s 清除、F-C 非小红书草稿清空、F5/F-A KeepAlive 门控与补偿。
 */

function xhsHandoff(revision = 1, overrides: Partial<CreationHandoff> = {}): CreationHandoff {
  return {
    revision,
    platformId: 'xiaohongshu',
    contentFormId: 'graphic',
    workflowId: 'longform',
    targetView: 'xhs-studio',
    source: { type: 'independent' },
    prefill: { topic: '热榜选题：通勤穿搭' },
    ...overrides,
  }
}

/** 挂载宿主组件（AI 应用语境：dataset.app='ai' 使 autosave engage）。 */
async function host(handoff?: () => CreationHandoff | null) {
  document.documentElement.dataset.app = 'ai'
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/xhs-studio', name: 'xhs-studio', component: { render: () => null } }],
  })
  await router.push('/xhs-studio')
  let context!: XhsStudioContext
  const Host = defineComponent({
    setup() {
      context = provideXhsStudioContext({
        handoff: handoff ?? (() => null),
        goCreationCenter: () => {},
      })
      return () => h('div')
    },
  })
  const wrapper = mount(Host, { global: { plugins: [createPinia(), router] } })
  return { wrapper, context }
}

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn(async () => new Response('{}', { status: 503 })))
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.useRealTimers()
  delete document.documentElement.dataset.app
})

describe('inject 契约', () => {
  test('缺失 provide 时 useXhsStudioContext 抛错（并行组件标准注入方式）', () => {
    const Bare = defineComponent({
      setup() {
        expect(() => useXhsStudioContext()).toThrow()
        return () => h('div')
      },
    })
    mount(Bare, { global: { plugins: [createPinia()] } })
  })
})

describe('F1 handoff 改写副本', () => {
  test("targetView='xhs-studio' 的 handoff 经改写副本 apply：prefill.topic 预填、平台锁定", async () => {
    const handoff = ref<CreationHandoff | null>(xhsHandoff())
    const { context } = await host(() => handoff.value)
    await flushPromises()
    // 若无改写副本，useWorkspaceHandoff 按 targetView!=='article' 过滤，apply 不会执行。
    expect(context.engine.topic.value).toBe('热榜选题：通勤穿搭')
    expect(context.engine.platform.value).toBe('xiaohongshu')
    expect(context.platformLocked.value).toBe(true)
    expect(context.fromCreationCenter.value).toBe(true)
    expect(context.steps.current.value).toBe('pick')
  })

  test('无 handoff 直入：platform 仍固定小红书、不锁定', async () => {
    const { context } = await host()
    await flushPromises()
    expect(context.engine.platform.value).toBe('xiaohongshu')
    expect(context.engine.styleSkillsActive.value).toBe(true)
    expect(context.engine.imagesStageSkipped.value).toBe(true)
    expect(context.platformLocked.value).toBe(false)
  })
})

describe('toast', () => {
  test('notify 置文案，1.9s 后自动清除', async () => {
    vi.useFakeTimers()
    const { context } = await host()
    context.notify('草稿已保存')
    expect(context.toast.value).toBe('草稿已保存')
    vi.advanceTimersByTime(1899)
    expect(context.toast.value).toBe('草稿已保存')
    vi.advanceTimersByTime(1)
    expect(context.toast.value).toBe('')
  })
})

describe('F-C 非小红书草稿直链', () => {
  test('恢复 wechat 草稿（platform 被回填）→ 清空会话回空态并提示，不留在错误平台', async () => {
    const { context } = await host()
    await flushPromises()
    // 模拟 applyProject 回填原平台（restoredProjectId 变化即一次 adopt 完成后的判据）。
    context.engine.platform.value = 'wechat'
    context.engine.topic.value = '公众号草稿主题'
    context.autosave.restoredProjectId.value = 'proj-wechat-1'
    await flushPromises()
    expect(context.toast.value).toContain('不属于小红书')
    expect(context.engine.platform.value).toBe('xiaohongshu')
    expect(context.engine.styleSkillsActive.value).toBe(true)
    expect(context.engine.imagesStageSkipped.value).toBe(true)
    expect(context.engine.topic.value).toBe('')
    expect(context.steps.current.value).toBe('pick')
  })
})

describe('F5/F-A KeepAlive 门控', () => {
  test('deactivated 期间 queueSave 被 gate 短路；activate 补偿后重新入队', async () => {
    document.documentElement.dataset.app = 'ai' // AI 应用语境：autosave engage
    const show = ref(true)
    let context!: XhsStudioContext
    const Host = defineComponent({
      setup() {
        context = provideXhsStudioContext({ handoff: () => null, goCreationCenter: () => {} })
        return () => h('div')
      },
    })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/', component: { render: () => null } }],
    })
    // KeepAlive 缓存 Host：v-if 切换触发 deactivated/activated（非卸载）。
    const Kept = defineComponent({
      setup: () => () => h(KeepAlive, null, { default: () => (show.value ? h(Host) : h('p', 'other view')) }),
    })
    const wrapper = mount(Kept, { global: { plugins: [createPinia(), router] } })
    await flushPromises()
    // 目录拉取失败（fetch stub 503）写 styleSkillsError 会合法入队一次：等 800ms 定时器
    // 走完（topic 空 → isValidInput 拦截）回到 idle，建立干净基线。
    await new Promise((resolve) => setTimeout(resolve, 850))
    expect(context.autosave.saveState.value).toBe('idle')

    // 切走（v-if false → deactivated）：gate=false，改引擎状态不触发保存入队。
    show.value = false
    await nextTick()
    context.engine.topic.value = '缓存期后台 SSE 写入的选题'
    await flushPromises()
    expect(context.autosave.saveState.value).toBe('idle')

    // 切回（activate）：补偿 queueSave 生效，无 draftId 时进入 pending（800ms 定时创建）。
    show.value = true
    await nextTick()
    await flushPromises()
    expect(context.autosave.saveState.value).toBe('pending')

    // 收尾：清空 topic 让定时器 flush 走 idle 分支（不触发 createDraft 网络），再卸载。
    context.engine.topic.value = ''
    await new Promise((resolve) => setTimeout(resolve, 850))
    wrapper.unmount()
  })
})
