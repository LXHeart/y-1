// @vitest-environment happy-dom
import { defineComponent, h, nextTick, ref } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { useArticleCreation } from '../../../composables/useArticleCreation'
import { useXhsStudioSteps } from './useXhsStudioSteps'
import type { XhsStudioStepsApi } from '../types'

/**
 * 四步状态机（本地用户步语义，方案 §4.1【F3/N3/F-B】）：
 * 初始映射、stage 推进不自动切步、终态跳转、go 协同、reached 派生与 canGo、
 * ?step= 深链、恢复完成重算与二次 handoff 重置。
 */

enableAutoUnmount(afterEach)

// go('proof') 的 enterCheck 协同可能触发自动复查（/api/content-safety/check）——
// 统一 stub 成 503（recheckSafety 内部 catch，返回 null 不抛出）。
beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn(async () => new Response('{}', { status: 503 })))
})

afterEach(() => {
  vi.unstubAllGlobals()
})

interface HostOptions {
  stage?: string
  completed?: boolean
  titles?: number
  content?: string
  query?: Record<string, string>
}

async function host(options: HostOptions = {}) {
  const article = useArticleCreation()
  if (options.stage) article.stage.value = options.stage as typeof article.stage.value
  if (options.completed) article.completed.value = true
  if (options.titles) article.titles.value = Array.from({ length: options.titles }, (_, i) => ({ title: `候选${i}`, hook: '' }))
  if (options.content) article.content.value = options.content
  const restoredProjectId = ref('')
  // 生产链路读 props.creationHandoff?.revision（props 响应式）；测试用 ref 模拟同一响应式源。
  const handoffRevision = ref<number | undefined>(undefined)
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/xhs-studio', name: 'xhs-studio', component: { render: () => null } }],
  })
  await router.push({ path: '/xhs-studio', query: options.query ?? {} })
  let steps!: XhsStudioStepsApi
  mount(defineComponent({
    setup() {
      steps = useXhsStudioSteps({
        article,
        restoredProjectId,
        handoffRevision: () => handoffRevision.value,
      })
      return () => h('div')
    },
  }), { global: { plugins: [router] } })
  return {
    article, router, steps, restoredProjectId, handoffRevision,
  }
}

describe('初始映射（N3：引擎 stage → 用户步）', () => {
  test('topic/question → pick；titles/outline/content → generate；check → proof', async () => {
    expect((await host({ stage: 'topic' })).steps.current.value).toBe('pick')
    expect((await host({ stage: 'question' })).steps.current.value).toBe('pick')
    expect((await host({ stage: 'titles' })).steps.current.value).toBe('generate')
    expect((await host({ stage: 'outline' })).steps.current.value).toBe('generate')
    expect((await host({ stage: 'content' })).steps.current.value).toBe('generate')
    expect((await host({ stage: 'check' })).steps.current.value).toBe('proof')
  })

  test('completed 优先于 stage → publish', async () => {
    expect((await host({ stage: 'topic', completed: true })).steps.current.value).toBe('publish')
  })

  test('?step= 深链合法且 ≤reached 时覆盖初始映射；超深回落映射值', async () => {
    const legal = await host({ stage: 'topic', titles: 3, query: { step: 'generate' } })
    expect(legal.steps.current.value).toBe('generate')

    const tooDeep = await host({ stage: 'topic', query: { step: 'proof' } })
    expect(tooDeep.steps.current.value).toBe('pick')

    const invalid = await host({ stage: 'topic', query: { step: 'nonsense' } })
    expect(invalid.steps.current.value).toBe('pick')
  })
})

describe('本地用户步红线（F3）', () => {
  test('引擎 stage 推进不自动改 current：正文生成完（stage 置 check）仍停留在生成步', async () => {
    const { article, steps } = await host({ stage: 'content', titles: 3, content: '正文内容' })
    expect(steps.current.value).toBe('generate')
    article.stage.value = 'check' // streamContent 末尾 enterCheck() 的效果
    await nextTick()
    expect(steps.current.value).toBe('generate')
  })

  test('终态跳转：completed 变 true → publish（唯一自动跳转）并同步 ?step=', async () => {
    const { article, steps, router } = await host({ stage: 'content', titles: 3, content: '正文' })
    article.completed.value = true
    await flushPromises()
    expect(steps.current.value).toBe('publish')
    expect(router.currentRoute.value.query.step).toBe('publish')
  })
})

describe('reached 数据派生与 canGo', () => {
  test('titles⇒generate；content⇒proof；completed⇒publish；canGo 拒绝超深步', async () => {
    const empty = await host({ stage: 'topic' })
    expect(empty.steps.reached.value).toBe('pick')
    expect(empty.steps.canGo('generate')).toBe(false)

    const withTitles = await host({ stage: 'titles', titles: 3 })
    expect(withTitles.steps.reached.value).toBe('generate')
    expect(withTitles.steps.canGo('proof')).toBe(false)
    expect(withTitles.steps.canGo('pick')).toBe(true)

    const withContent = await host({ stage: 'content', titles: 3, content: '正文' })
    expect(withContent.steps.reached.value).toBe('proof')
    expect(withContent.steps.canGo('publish')).toBe(false)

    const done = await host({ stage: 'publish', completed: true })
    expect(done.steps.reached.value).toBe('publish')
  })

  test('go 超深步被拒绝（不动 current）；go(pick) 恒可（completed 后仍可回选题）', async () => {
    const { steps } = await host({ stage: 'content', titles: 3, content: '正文' })
    expect(steps.current.value).toBe('generate') // 初始映射按 stage，与 reached 无关
    steps.go('publish') // reached=proof，publish 超深被拒
    expect(steps.current.value).toBe('generate')

    const done = await host({ stage: 'publish', completed: true, content: '正文' })
    done.steps.go('pick')
    expect(done.steps.current.value).toBe('pick')
  })
})

describe('go() 的引擎协同（stage 写入口仅有的两条转换）', () => {
  test("go('proof') 且 stage!=='check' → 先 enterCheck（自动复查语义保留）", async () => {
    const { article, steps } = await host({ stage: 'content', titles: 3, content: '正文' })
    steps.go('proof')
    expect(article.stage.value).toBe('check')
    expect(steps.current.value).toBe('proof')
  })

  test("go('generate') 且 stage==='check' → stage 置回 'content'（回编辑工位）", async () => {
    const { article, steps } = await host({ stage: 'check', titles: 3, content: '正文' })
    expect(steps.current.value).toBe('proof')
    steps.go('generate')
    expect(article.stage.value).toBe('content')
    expect(steps.current.value).toBe('generate')
  })
})

describe('F-B 重映射钩子', () => {
  test('恢复完成重算：restoredProjectId 变化后按映射表重算 current（异步 stage 回填窗口）', async () => {
    const { article, steps, restoredProjectId } = await host({ stage: 'topic' })
    expect(steps.current.value).toBe('pick')
    // ?draft= 恢复异步完成：引擎状态被 applyProject 回填后才触发重算。
    article.stage.value = 'content'
    article.titles.value = [{ title: '恢复的标题', hook: '' }]
    article.content.value = '恢复的正文'
    restoredProjectId.value = 'proj-1'
    await nextTick()
    expect(steps.current.value).toBe('generate')
  })

  test('二次 handoff：revision 非 initial 变化 → current 立即回 pick（不等 apply 完成）', async () => {
    const { article, steps, handoffRevision } = await host({ stage: 'content', titles: 3, content: '正文' })
    expect(steps.current.value).toBe('generate')
    handoffRevision.value = 2
    await nextTick()
    expect(steps.current.value).toBe('pick')
    // apply 链 reset 清数据也不带动 current（红线）；current 已由钩子回 pick。
    article.stage.value = 'topic'
    article.titles.value = []
    article.content.value = ''
    await nextTick()
    expect(steps.current.value).toBe('pick')
  })
})

describe('go() 同步 ?step= URL（queued replace）', () => {
  test('go 成功后 replace 同步 query.step', async () => {
    const { steps, router } = await host({ stage: 'content', titles: 3, content: '正文' })
    steps.go('generate')
    await flushPromises()
    expect(router.currentRoute.value.query.step).toBe('generate')
  })
})
