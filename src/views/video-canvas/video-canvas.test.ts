// @vitest-environment happy-dom

import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { mount, enableAutoUnmount, flushPromises } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import CanvasBoard from './CanvasBoard.vue'
import DirectorPanel from './DirectorPanel.vue'
import VideoCanvasView from './VideoCanvasView.vue'
import {
  CANVAS_MAX_SCALE,
  CANVAS_MIN_SCALE,
  clampScale,
  fitViewport,
  zoomViewport,
} from './useCanvasViewport'
import { useVideoCanvas } from './useVideoCanvas'
import type { CanvasStoryboard } from './useVideoCanvas'

enableAutoUnmount(afterEach)

function storyboardFixture(overrides: Partial<CanvasStoryboard> = {}): CanvasStoryboard {
  return {
    id: 'sb-1',
    targetDurationSeconds: 20,
    resolution: '1080x1920',
    status: 'draft',
    grouping: {
      shots: [{ id: 'shot-1', groupId: 'g-open' }, { id: 'shot-2' }],
      branches: [
        { id: 'b1', name: '主版本', shotIds: ['shot-1', 'shot-2'] },
        { id: 'b2', name: '精简版', shotIds: ['shot-1'] },
      ],
    },
    shots: [
      {
        id: 'shot-1', seq: 1, visual: '招牌特写', narration: '老王面馆现熬骨汤', plannedSeconds: 5,
        cameraMove: '缓慢推近', anchorImageIndex: 1, status: 'ready', x: 40, y: 40,
        takes: [{ id: 'take-1', takeNo: 1, status: 'succeeded', selectable: true, score: 88, scoreLabels: [], url: null }],
      },
      {
        id: 'shot-2', seq: 2, visual: '后厨实拍', narration: '每天现切这碗面', plannedSeconds: 5,
        cameraMove: '跟随运镜', anchorImageIndex: 2, status: 'ready', x: 360, y: 40,
        takes: [{ id: 'take-2', takeNo: 1, status: 'succeeded', selectable: true, score: 64, scoreLabels: [], url: null }],
      },
    ],
    ...overrides,
  }
}

describe('卡C2：视口变换计算', () => {
  test('缩放钳制 0.25–2.5，越界与非数回边界/默认', () => {
    expect(clampScale(5)).toBe(CANVAS_MAX_SCALE)
    expect(clampScale(0.01)).toBe(CANVAS_MIN_SCALE)
    expect(clampScale(Number.NaN)).toBe(1)
  })

  test('zoomAt 围绕锚点：锚点像素在变换前后不动', () => {
    const state = { scale: 1, panX: 50, panY: 30 }
    const zoomed = zoomViewport(state, 200, 100, 2)
    expect(zoomed.scale).toBe(2)
    // (px - panX) * k + panX' = px → panX' = px - (px - panX) * k
    expect(zoomed.panX).toBe(200 - 150 * 2)
    expect(zoomed.panY).toBe(100 - 70 * 2)
  })

  test('fitViewport：包围盒整体落入视口并贴 padding', () => {
    const fitted = fitViewport({ scale: 2, panX: 0, panY: 0 },
      { minX: 100, minY: 50, maxX: 900, maxY: 500 }, 800, 600)
    // 宽 800/高 450：scale = min(720/800, 520/450) = 0.9
    expect(fitted.scale).toBeCloseTo(0.9, 5)
    expect(fitted.panX).toBeCloseTo(-100 * 0.9 + 40, 5)
    expect(fitted.panY).toBeCloseTo(-50 * 0.9 + 40, 5)
  })
})

describe('卡C2：节点渲染与增删', () => {
  test('镜头节点随 shots 渲染，增删后重渲染', async () => {
    const wrapper = mount(CanvasBoard, {
      props: {
        shots: storyboardFixture().shots,
        selectedShotId: null,
        branches: [],
        activeBranchId: null,
      },
    })
    expect(wrapper.findAll('.canvas-node').length).toBe(2)
    expect(wrapper.find('[data-test="canvas-takes-1"]').text()).toContain('88')

    await wrapper.setProps({ shots: storyboardFixture().shots.slice(0, 1) })
    expect(wrapper.findAll('.canvas-node').length).toBe(1)
  })

  test('顺序连线 = 镜头数-1；选中节点高亮', async () => {
    const wrapper = mount(CanvasBoard, {
      props: {
        shots: storyboardFixture().shots,
        selectedShotId: 'shot-1',
        branches: [],
        activeBranchId: null,
      },
    })
    expect(wrapper.findAll('.canvas-edge').length).toBe(1)
    expect(wrapper.find('[data-test="canvas-node-1"]').classes()).toContain('canvas-node-selected')
  })
})

describe('卡C2/C3：数据源与互切保数据', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({
      success: true,
      data: {
        id: 'sb-1', targetDurationSeconds: 20, resolution: '1080x1920', status: 'draft',
        grouping: storyboardFixture().grouping,
        shots: storyboardFixture().shots.map(({ x: _x, y: _y, ...rest }) => rest),
      },
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })))
  })

  test('同 storyboard 重载保节点坐标（互切往返不丢排布）', async () => {
    const canvas = useVideoCanvas()
    await canvas.loadStoryboard('sb-1')
    expect(canvas.visibleShots.value).toHaveLength(2)
    canvas.moveShot('shot-1', 500, 300)

    await canvas.loadStoryboard('sb-1')
    const moved = canvas.visibleShots.value.find(shot => shot.id === 'shot-1')
    expect(moved?.x).toBe(500)
    expect(moved?.y).toBe(300)
  })

  test('分支切换过滤镜头序列；回到全分支恢复', async () => {
    const canvas = useVideoCanvas()
    await canvas.loadStoryboard('sb-1')
    canvas.activeBranchId.value = 'b2'
    expect(canvas.visibleShots.value.map(shot => shot.id)).toEqual(['shot-1'])
    canvas.activeBranchId.value = null
    expect(canvas.visibleShots.value).toHaveLength(2)
  })
})

describe('卡C3：导演台面板', () => {
  test('镜头属性编辑发出 save-shot 载荷（同快速模式字段）', async () => {
    const wrapper = mount(DirectorPanel, {
      props: {
        shot: storyboardFixture().shots[0],
        grouping: storyboardFixture().grouping,
        activeBranchId: null,
        dirty: true,
      },
    })
    await wrapper.find('[data-test="director-visual"]').setValue('新画面描述')
    await wrapper.find('[data-test="director-seconds"]').setValue('6')
    await wrapper.find('[data-test="director-save-shot"]').trigger('click')
    expect(wrapper.emitted('save-shot')?.[0]).toEqual(['shot-1', {
      visual: '新画面描述', narration: '老王面馆现熬骨汤', plannedSeconds: 6, cameraMove: '缓慢推近',
    }])
    expect(wrapper.find('[data-test="director-dirty-hint"]').exists()).toBe(true)
  })

  test('分组指派与新分支快照发出 save-grouping 载荷', async () => {
    const wrapper = mount(DirectorPanel, {
      props: {
        shot: storyboardFixture().shots[1],
        grouping: storyboardFixture().grouping,
        activeBranchId: null,
        dirty: false,
      },
    })
    await wrapper.find('[data-test="director-tab-grouping"]').trigger('click')
    await wrapper.find('[data-test="director-group-input"]').setValue('g-food')
    await wrapper.find('[data-test="director-assign-group"]').trigger('click')
    const groupEvent = wrapper.emitted('save-grouping')?.[0]?.[0] as { shots: Array<{ id: string; groupId?: string }> }
    expect(groupEvent.shots).toContainEqual({ id: 'shot-2', groupId: 'g-food' })

    await wrapper.find('[data-test="director-branch-name"]').setValue('彩蛋版')
    await wrapper.find('[data-test="director-create-branch"]').trigger('click')
    const branchEvent = wrapper.emitted('save-grouping')?.[1]?.[0] as { branches: Array<{ name: string; shotIds: string[] }> }
    expect(branchEvent.branches.map(branch => branch.name)).toContain('彩蛋版')
    expect(branchEvent.branches.find(branch => branch.name === '彩蛋版')?.shotIds)
      .toEqual(['shot-1', 'shot-2'])
  })

  test('分支切换按钮发出 switch-branch（再点回 null）', async () => {
    const wrapper = mount(DirectorPanel, {
      props: {
        shot: null,
        grouping: storyboardFixture().grouping,
        activeBranchId: 'b2',
        dirty: false,
      },
    })
    await wrapper.find('[data-test="director-tab-grouping"]').trigger('click')
    await wrapper.find('[data-test="director-branch-精简版"] button').trigger('click')
    expect(wrapper.emitted('switch-branch')?.[0]).toEqual([null])
  })
})

describe('卡C3：页面互切', () => {
  test('dirty 时确认才切快速模式，路由携带 storyboard；取消则留在画布', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({
      success: true,
      data: {
        id: 'sb-1', targetDurationSeconds: 20, resolution: '1080x1920', status: 'draft',
        grouping: storyboardFixture().grouping,
        shots: storyboardFixture().shots.map(({ x: _x, y: _y, ...rest }) => rest),
      },
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })))

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/video-canvas', name: 'video-canvas', component: VideoCanvasView },
        { path: '/video-production', name: 'video-production',
          component: { template: '<div />' } },
      ],
    })
    await router.push('/video-canvas?storyboard=sb-1')
    await router.isReady()

    const confirmSpy = vi.spyOn(window, 'confirm')
    const wrapper = mount(VideoCanvasView, { global: { plugins: [router] } })
    await flushPromises()
    expect(wrapper.findAll('.canvas-node').length).toBe(2)

    // 选中镜头并改草稿 → 未保存徽标出现
    await wrapper.find('[data-test="canvas-node-1"]').trigger('pointerdown')
    await wrapper.find('[data-test="director-visual"]').setValue('改过的画面')
    expect(wrapper.find('[data-test="canvas-dirty-badge"]').exists()).toBe(true)

    confirmSpy.mockReturnValueOnce(false)
    await wrapper.find('[data-test="switch-quick-mode"]').trigger('click')
    expect(router.currentRoute.value.name).toBe('video-canvas')

    confirmSpy.mockReturnValueOnce(true)
    await wrapper.find('[data-test="switch-quick-mode"]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.name).toBe('video-production')
    expect(router.currentRoute.value.query.storyboard).toBe('sb-1')
    confirmSpy.mockRestore()
  })
})
