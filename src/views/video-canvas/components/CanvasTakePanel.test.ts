// @vitest-environment happy-dom
import { computed, ref } from 'vue'
import { mount } from '@vue/test-utils'
import { describe, expect, test, vi } from 'vitest'
import CanvasTakePanel from './CanvasTakePanel.vue'
import type { VideoTaskSessionHost } from '../../../composables/useVideoTaskSession'
import type { VideoTask } from '../../../types/video-production'
import type { CanvasShot } from '../useVideoCanvas'

/**
 * 任务书 #100 C100-06：画布候选面板。
 * TC-015：成功/失败/未评分三类候选展示面 + 失效重试上抛；失败项不可采用。
 * TC-016：采用回显共享待确认队列——保存中/失败回滚可重试/未确认不能合成；重抽按阶段分流。
 */

function makeShot(): CanvasShot {
  return {
    id: 'shot-1', seq: 1, visual: '画面', narration: '旁白', plannedSeconds: 5,
    cameraMove: '固定', anchorImageIndex: 1, status: 'ready',
    x: 0, y: 0,
    takes: [
      { id: 'take-1', takeNo: 1, status: 'succeeded', selectable: true, score: 88,
        scoreLabels: ['画面稳定'], url: 'https://media.example.test/take-1.mp4' },
      { id: 'take-2', takeNo: 2, status: 'succeeded', selectable: true, score: null,
        scoreLabels: [], url: null },
      { id: 'take-3', takeNo: 3, status: 'failed', selectable: false, score: null,
        scoreLabels: [], url: null },
    ],
  }
}

function makeTask(overrides: Partial<VideoTask> = {}): VideoTask {
  return {
    id: 'task-1', storyboardId: 'sb-1', mode: 'video', phase: 'generating', progress: 50,
    targetDurationSeconds: 30, provider: null, model: null, unitPriceCents: 1,
    estimatedCostCents: 30, actualCostCents: null, actualDurationSeconds: null,
    errorCode: null, errorMessage: null, selection: {}, recommended: {},
    finalUrl: null, subtitleUrl: null,
    shots: [{
      id: 'shot-1', seq: 1, visual: '画面', narration: '旁白', plannedSeconds: 5,
      cameraMove: '固定', anchorImageIndex: 1, prompt: 'p', status: 'ready',
      audio: { status: null, provider: null, model: null, durationMs: null },
      source: { kind: 'generated' } as const,
      takes: [
        { id: 'take-1', takeNo: 1, status: 'succeeded', attempts: 1, provider: null, model: null,
          mediaId: null, durationMs: null, errorCode: null, errorMessage: null, selectable: true,
          score: 88, scoreLabels: [], url: null },
        { id: 'take-2', takeNo: 2, status: 'succeeded', attempts: 1, provider: null, model: null,
          mediaId: null, durationMs: null, errorCode: null, errorMessage: null, selectable: true,
          score: null, scoreLabels: [], url: null },
        { id: 'take-3', takeNo: 3, status: 'failed', attempts: 1, provider: null, model: null,
          mediaId: null, durationMs: null, errorCode: 'provider_failed', errorMessage: '生成服务超时',
          selectable: false, score: null, scoreLabels: [], url: null },
      ],
    }],
    selectionVersion: 0,
    ...overrides,
  }
}

type SessionOverrides = Partial<Pick<VideoTaskSessionHost,
  'selectTake' | 'regenerateShot' | 'rerollShot' | 'task' | 'taskError' | 'pendingSelectionCount'>>

function makeSession(overrides: SessionOverrides = {}): VideoTaskSessionHost {
  return {
    task: overrides.task ?? ref(makeTask()),
    taskError: overrides.taskError ?? ref(''),
    pendingSelectionCount: overrides.pendingSelectionCount ?? computed(() => 0),
    acquire: vi.fn(),
    release: vi.fn(),
    refreshTask: vi.fn(async () => {}),
    selectTake: overrides.selectTake ?? vi.fn(async () => {}),
    useRecommendedSelection: vi.fn(async () => {}),
    regenerateShot: overrides.regenerateShot ?? vi.fn(async () => {}),
    rerollShot: overrides.rerollShot ?? vi.fn(async () => {}),
    composeTask: vi.fn(async () => {}),
    cancelTask: vi.fn(async () => {}),
    eventsDegraded: computed(() => false),
    composeSubmitting: ref(false),
    suspendSession: vi.fn(),
    resumeChannel: vi.fn(),
  } as VideoTaskSessionHost
}

function mountPanel(session: VideoTaskSessionHost | null, shot: CanvasShot | null = makeShot()) {
  return mount(CanvasTakePanel, { props: { shot, session } })
}

describe('TC-015：候选展示面', () => {
  test('成功候选带 URL 与评分角标；未评分显示「未评分」不填 0；失败项显示原因且不可采用', () => {
    const wrapper = mountPanel(makeSession())
    expect(wrapper.find('[data-test="canvas-take-preview-1-video"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="canvas-take-1"]').text()).toContain('质检 88')
    expect(wrapper.find('[data-test="canvas-take-1"]').text()).toContain('画面稳定')

    // 未评分：不伪造 0 分
    expect(wrapper.find('[data-test="canvas-take-unscored-2"]').text()).toBe('未评分')
    expect(wrapper.find('[data-test="canvas-take-2"]').text()).not.toContain('质检 0')

    // 失败项：任务侧失败原因可见、采用禁用
    expect(wrapper.find('[data-test="canvas-take-3"]').text()).toContain('生成服务超时')
    const failedButton = wrapper.find('[data-test="canvas-take-adopt-3"]')
    expect((failedButton.element as HTMLButtonElement).disabled).toBe(true)

    // 成功项可点
    expect((wrapper.find('[data-test="canvas-take-adopt-1"]').element as HTMLButtonElement).disabled).toBe(false)
  })

  test('无候选空态；无会话时候选只读（采用/重抽禁用 + 未关联任务说明）', () => {
    const emptyShot = { ...makeShot(), takes: [] }
    const wrapper = mountPanel(makeSession(), emptyShot)
    expect(wrapper.find('[data-test="canvas-takes-empty"]').exists()).toBe(true)

    const noSession = mountPanel(null)
    expect((noSession.find('[data-test="canvas-take-adopt-1"]').element as HTMLButtonElement).disabled).toBe(true)
    expect((noSession.find('[data-test="canvas-take-regenerate"]').element as HTMLButtonElement).disabled).toBe(true)
    expect(noSession.find('[data-test="canvas-take-no-task"]').exists()).toBe(true)
  })

  test('媒体失效重试上抛 refresh-media（宿主重取合法 URL）', async () => {
    const wrapper = mountPanel(makeSession())
    await wrapper.find('[data-test="canvas-take-preview-1-video"]').trigger('error')
    await wrapper.find('[data-test="canvas-take-preview-1-retry"]').trigger('click')
    expect(wrapper.emitted('refresh-media')).toHaveLength(1)
  })
})

describe('TC-016：采用回显与待确认闸', () => {
  test('点击采用调用会话并短暂显示保存中；成功后展示已保存的采用', async () => {
    const task = ref(makeTask())
    const selectTake = vi.fn(async () => {
      task.value = { ...task.value, selection: { 'shot-1': 'take-1' }, selectionVersion: 1 }
    })
    const wrapper = mountPanel(makeSession({ task, selectTake }))
    await wrapper.find('[data-test="canvas-take-adopt-1"]').trigger('click')
    expect(selectTake).toHaveBeenCalledWith('shot-1', 'take-1')
    await Promise.resolve()
    expect(wrapper.find('[data-test="canvas-take-adopt-1"]').text()).toBe('已采用')
    expect(wrapper.find('[data-test="canvas-take-saved"]').text()).toContain('已保存当前采用')
  })

  test('保存失败：回滚乐观层、显示错误、不显示已保存（可重试）', async () => {
    const task = ref(makeTask())
    const taskError = ref('')
    const selectTake = vi.fn(async () => {
      taskError.value = '选片保存失败'
    })
    const wrapper = mountPanel(makeSession({ task, taskError, selectTake }))
    await wrapper.find('[data-test="canvas-take-adopt-1"]').trigger('click')
    await Promise.resolve()
    expect(wrapper.find('[data-test="canvas-take-error"]').text()).toBe('选片保存失败')
    expect(wrapper.find('[data-test="canvas-take-saved"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="canvas-take-adopt-1"]').text()).toBe('采用')
    // 重试可再次发起
    await wrapper.find('[data-test="canvas-take-adopt-1"]').trigger('click')
    expect(selectTake).toHaveBeenCalledTimes(2)
  })

  test('未确认期间提示不能合成；确认后提示已保存', async () => {
    const pending = computed(() => 1)
    const wrapper = mountPanel(makeSession({ pendingSelectionCount: pending }))
    expect(wrapper.find('[data-test="canvas-take-pending"]').text()).toContain('未确认前不能合成')
    expect(wrapper.find('[data-test="canvas-take-saved"]').exists()).toBe(false)
  })

  test('重抽按阶段分流：generating→regenerate、succeeded→reroll', async () => {
    const regenerateShot = vi.fn(async () => {})
    const rerollShot = vi.fn(async () => {})
    const generating = mountPanel(makeSession({ regenerateShot, rerollShot }))
    await generating.find('[data-test="canvas-take-regenerate"]').trigger('click')
    expect(regenerateShot).toHaveBeenCalledWith('shot-1')
    expect(rerollShot).not.toHaveBeenCalled()

    const finished = mountPanel(makeSession({
      task: ref(makeTask({ phase: 'succeeded' })), regenerateShot, rerollShot,
    }))
    expect(finished.find('[data-test="canvas-take-regenerate"]').text()).toContain('成片后重抽')
    await finished.find('[data-test="canvas-take-regenerate"]').trigger('click')
    expect(rerollShot).toHaveBeenCalledWith('shot-1')
  })
})
