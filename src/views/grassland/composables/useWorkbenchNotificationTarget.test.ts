// @vitest-environment happy-dom
/**
 * 任务书 #103 C103-13：合作通知深链合约（TC103-13-04/06）。
 * resolveLinkTarget 白名单校验为纯函数测试；工作台落点（服务端回读鉴权 + 定位 + 明确提示）
 * 用真实 watch 宿主 + 桩 grassland 验证（不 mock Vue 响应机制）。
 */
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h, nextTick, ref } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { resolveLinkTarget } from '../../../stores/notifications'
import type { NotificationLinkTarget } from '../../../types/notification'
import type { useGrassland } from '../../../composables/useGrassland'
import { useWorkbenchNavigation } from './useWorkbenchNavigation'
import type { Task } from '../../../types/grassland'

enableAutoUnmount(afterEach)

// ---------- TC103-13-04/E13/E17：resolveLinkTarget 白名单 ----------

test('合作通知：applicationId + 白名单 focus 生成类型化目标', () => {
  const target = resolveLinkTarget('/me/engagements', {
    taskId: 'task-1',
    applicationId: 'app-1',
    focus: 'extension',
  })
  expect(target).toEqual({
    view: 'grassland',
    anchor: 'gl-engagements',
    taskId: 'task-1',
    applicationId: 'app-1',
    focus: 'extension',
  })
})

test('未知 focus / 非 focus 白名单值一律丢弃，不拼任意定位', () => {
  expect(resolveLinkTarget('/me/engagements', { applicationId: 'app-1', focus: 'javascript:' }))
    .toEqual({ view: 'grassland', anchor: 'gl-engagements', applicationId: 'app-1' })
  expect(resolveLinkTarget('/me/engagements', { applicationId: 'app-1', focus: 'unknown-zone' }))
    .toEqual({ view: 'grassland', anchor: 'gl-engagements', applicationId: 'app-1' })
  // applicationId 非字符串/缺失 → 回落整区块锚点
  expect(resolveLinkTarget('/me/engagements', { applicationId: 42, focus: 'benefit' }))
    .toEqual({ view: 'grassland', anchor: 'gl-engagements' })
  expect(resolveLinkTarget('/me/engagements', {}))
    .toEqual({ view: 'grassland', anchor: 'gl-engagements' })
})

test('六类 focus 白名单值全部可解析；其余 linkPath 不受影响', () => {
  for (const focus of ['delivery', 'extension', 'draft-review', 'exit', 'benefit', 'milestone']) {
    expect(resolveLinkTarget('/me/engagements', { applicationId: 'a', focus })?.focus).toBe(focus)
  }
  expect(resolveLinkTarget('/me/wallet', {})).toEqual({ view: 'grassland', anchor: 'gl-wallet' })
})

// ---------- TC103-13-04/06：工作台落点（服务端回读鉴权 + 提示） ----------

function navigationHost(grasslandStub: unknown) {
  const target = ref<NotificationLinkTarget | null>(null)
  const anchor = ref('')
  const notices: string[] = []
  const subTab = ref('hall')
  const side = ref<'merchant' | 'recommender'>('recommender')
  const tasks = ref<Task[]>([])
  const selectTask = vi.fn()
  const openTaskDetail = vi.fn()
  const switchSide = vi.fn(async () => { side.value = side.value === 'merchant' ? 'recommender' : 'merchant' })
  let controller!: ReturnType<typeof useWorkbenchNavigation>
  const Subject = defineComponent({
    setup() {
      controller = useWorkbenchNavigation({
        grassland: grasslandStub as ReturnType<typeof useGrassland>,
        grasslandAnchor: anchor,
        grasslandNavigationTarget: target,
        emit: vi.fn(),
        setNotice: (message: string) => { notices.push(message) },
        side,
        switchSide,
        subTab,
        financeSection: ref('account'),
        tasks,
        feedItems: ref([]),
        taskContextLoadingAppId: ref(''),
        selectTask,
        openTaskDetail,
      })
      return () => h('div')
    },
  })
  mount(Subject)
  return { target, anchor, notices, subTab, side, tasks, selectTask, controller }
}

function stubGrassland(options: { application?: unknown; task?: unknown; error?: string }) {
  return {
    getApplication: vi.fn(async () => options.application ?? null),
    getTask: vi.fn(async () => options.task ?? null),
    error: { value: options.error ?? '' },
  } as unknown as ReturnType<typeof useGrassland>
}

const applicationRow = { id: 'app-1', taskId: 'task-1', status: 'accepted' }
const taskRow = { id: 'task-1', title: '任务' } as unknown as Task

beforeEach(() => {
  setActivePinia(createPinia())
})

afterEach(() => {
  vi.restoreAllMocks()
})

test('可读合作：切履约页签 + 滚动锚点 + 定位提示；消费后清目标', async () => {
  const grassland = stubGrassland({ application: applicationRow })
  const { target, anchor, notices, subTab } = navigationHost(grassland)
  target.value = { view: 'grassland', anchor: 'gl-engagements', applicationId: 'app-1', taskId: 'task-1', focus: 'exit' }
  await flushPromises()
  await nextTick()
  expect(grassland.getApplication).toHaveBeenCalledWith('task-1', 'app-1')
  expect(subTab.value).toBe('engagements')
  expect(notices.some((message) => message.includes('app-1'.slice(0, 8)))).toBe(true)
  expect(target.value).toBeNull() // 消费完成
  expect(anchor.value).toBe('') // 锚点路径不经此分支
})

test('E07/E18 不可读/无权限：服务端拒绝给明确提示，不打开相邻记录', async () => {
  const grassland = stubGrassland({ application: null, error: '无权查看该合作' })
  const { target, notices, subTab } = navigationHost(grassland)
  target.value = { view: 'grassland', anchor: 'gl-engagements', applicationId: 'app-1', taskId: 'task-1' }
  await flushPromises()
  expect(notices.some((message) => message.includes('无权查看该合作'))).toBe(true)
  expect(subTab.value).toBe('hall') // 未切换页签（不打开相邻记录）
  expect(target.value).toBeNull()
})

test('TC103-13-06 加载合作 A 期间切到 B：迟到回包不落 A 的定位', async () => {
  let resolveA!: (value: unknown) => void
  const grassland = {
    getApplication: vi.fn(() => new Promise((done) => { resolveA = done })),
    getTask: vi.fn(async () => null),
    error: { value: '' },
  } as unknown as ReturnType<typeof useGrassland>
  const { target, notices, subTab } = navigationHost(grassland)

  target.value = { view: 'grassland', anchor: 'gl-engagements', applicationId: 'app-a', taskId: 'task-a' }
  await nextTick()
  // A 在途时用户点了 B 的通知
  target.value = { view: 'grassland', anchor: 'gl-engagements', applicationId: 'app-b', taskId: 'task-b' }
  let resolveB!: (value: unknown) => void
  grassland.getApplication = vi.fn(() => new Promise((done) => { resolveB = done })) as never
  await nextTick()
  // A 的回包迟到（null = 不可读）：不得给 B 弹 A 的错误提示
  resolveA(null)
  await flushPromises()
  expect(notices.join()).not.toContain('不可查看')
  expect(subTab.value).toBe('hall')

  // B 的回包就绪：B 正常定位
  resolveB(applicationRow)
  await flushPromises()
  expect(subTab.value).toBe('engagements')
  expect(target.value).toBeNull()
})

test('商家侧落点：回读通过后选中任务并切任务页签', async () => {
  const grassland = stubGrassland({ application: applicationRow, task: taskRow })
  const { target, subTab, side, selectTask, tasks } = navigationHost(grassland)
  side.value = 'merchant'
  target.value = { view: 'grassland', anchor: 'gl-engagements', applicationId: 'app-1', taskId: 'task-1' }
  await flushPromises()
  expect(subTab.value).toBe('tasks')
  expect(selectTask).toHaveBeenCalledWith('task-1')
  expect(tasks.value[0]?.id).toBe('task-1')
})

test('E13 通知缺任务定位：明确提示且不发请求', async () => {
  const grassland = stubGrassland({ application: applicationRow })
  const { target, notices } = navigationHost(grassland)
  target.value = { view: 'grassland', anchor: 'gl-engagements', applicationId: 'app-1' }
  await flushPromises()
  expect(grassland.getApplication).not.toHaveBeenCalled()
  expect(notices.some((message) => message.includes('无法定位'))).toBe(true)
})
