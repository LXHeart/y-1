// @vitest-environment happy-dom
import { describe, expect, test } from 'vitest'
import {
  NUDGE_BASE_STEP,
  NUDGE_LARGE_STEP,
  isInteractiveTarget,
  spacingTokenStep,
  useCanvasInteraction,
} from './useCanvasInteraction'
import { CANVAS_POSITION_MAX } from '../useCanvasViewport'

function pointerEvent(init: Partial<PointerEventInit> & { pointerId?: number } = {}): PointerEvent {
  return new PointerEvent('pointermove', {
    pointerId: 1, clientX: 0, clientY: 0, button: 0, bubbles: true, ...init,
  })
}

function makeMachine(scale: number) {
  const transient: Array<[string, number, number]> = []
  const commits: Array<{ shotId: string; from: { x: number; y: number }; to: { x: number; y: number } }> = []
  const cancels: Array<{ shotId: string; origin: { x: number; y: number } }> = []
  const machine = useCanvasInteraction({
    scale: () => scale,
    onTransientMove: (shotId, x, y) => transient.push([shotId, x, y]),
    onCommit: (shotId, from, to) => commits.push({ shotId, from, to }),
    onCancel: (shotId, origin) => cancels.push({ shotId, origin }),
  })
  return { machine, transient, commits, cancels }
}

describe('TC-005：拖拽坐标换算（视觉 80px → 逻辑 160/80/40）', () => {
  test.each([
    [0.5, 40 + 160],
    [1, 40 + 80],
    [2, 40 + 40],
  ])('scale=%s：屏幕位移 80px 的瞬时与提交逻辑坐标', (scale, expectedX) => {
    const { machine, transient, commits } = makeMachine(scale)
    expect(machine.beginNodeDrag('shot-1', pointerEvent({ clientX: 100, clientY: 100 }), 40, 40)).toBe(true)

    machine.dragMove(pointerEvent({ clientX: 180, clientY: 100 }))
    expect(transient).toHaveLength(1)
    expect(transient[0]).toEqual(['shot-1', expectedX, 40])

    // 结束只提交一次最终逻辑坐标，并携带起点供历史记录
    machine.dragEnd(pointerEvent({ clientX: 180, clientY: 100 }))
    expect(commits).toHaveLength(1)
    expect(commits[0]).toEqual({ shotId: 'shot-1', from: { x: 40, y: 40 }, to: { x: expectedX, y: 40 } })
  })

  test('平移后拖拽换算不受 pan 影响（换算只用 scale），位置钳制在 ±100000', () => {
    const { machine, transient, commits } = makeMachine(1)
    expect(machine.beginNodeDrag('shot-1', pointerEvent({ clientX: 0, clientY: 0 }), 999000, 0)).toBe(true)
    machine.dragMove(pointerEvent({ clientX: 100000, clientY: 0 }))
    expect(transient[0]?.[1]).toBe(CANVAS_POSITION_MAX)
    machine.dragEnd(pointerEvent({ clientX: 100000, clientY: 0 }))
    expect(commits[0]?.to.x).toBe(CANVAS_POSITION_MAX)
  })

  test('交互控件内的按下不启动拖拽；非左键不启动；手势互斥', () => {
    const { machine } = makeMachine(1)
    const button = document.createElement('button')
    document.body.appendChild(button)
    const onButton = pointerEvent({ clientX: 0, clientY: 0 })
    Object.defineProperty(onButton, 'target', { value: button })
    expect(machine.beginNodeDrag('shot-1', onButton, 0, 0)).toBe(false)

    expect(machine.beginNodeDrag('shot-1', pointerEvent({ clientX: 0, clientY: 0, button: 2 }), 0, 0)).toBe(false)

    expect(machine.beginNodeDrag('shot-1', pointerEvent({ clientX: 0, clientY: 0 }), 0, 0)).toBe(true)
    // 已有手势在途：第二个节点不开新手势
    expect(machine.beginNodeDrag('shot-2', pointerEvent({ clientX: 0, clientY: 0, pointerId: 2 }), 0, 0)).toBe(false)
  })
})

describe('TC-006：手势取消与键盘布局操作', () => {
  test('pointercancel / Escape / lostpointercapture：不提交半次拖拽，回起始位置', () => {
    const { machine, transient, commits, cancels } = makeMachine(1)
    machine.beginNodeDrag('shot-1', pointerEvent({ clientX: 100, clientY: 100 }), 40, 40)
    machine.dragMove(pointerEvent({ clientX: 180, clientY: 100 }))
    expect(transient).toHaveLength(1)

    machine.dragCancel()
    expect(commits).toHaveLength(0)
    expect(cancels).toEqual([{ shotId: 'shot-1', origin: { x: 40, y: 40 } }])
    expect(machine.isDragActive()).toBe(false)

    // Escape 与 pointercancel 同路径
    machine.beginNodeDrag('shot-1', pointerEvent({ clientX: 100, clientY: 100, pointerId: 3 }), 40, 40)
    machine.dragCancel()
    expect(cancels).toHaveLength(2)
  })

  test('键盘微移：方向键 8 / Shift 24 逻辑单位（取根 spacing token），边界钳制', () => {
    const { machine } = makeMachine(1)
    const base = spacingTokenStep('--space-xs', NUDGE_BASE_STEP)
    const large = spacingTokenStep('--space-lg', NUDGE_LARGE_STEP)
    expect(base).toBe(8)
    expect(large).toBe(24)

    expect(machine.keyboardNudge('ArrowRight', false, 100, 100)).toEqual({ x: 100 + base, y: 100 })
    expect(machine.keyboardNudge('ArrowDown', true, 100, 100)).toEqual({ x: 100, y: 100 + large })
    expect(machine.keyboardNudge('ArrowLeft', false, -CANVAS_POSITION_MAX + 4, 0)?.x).toBe(-CANVAS_POSITION_MAX)
    // 非方向键（含删除键）不动业务实体
    expect(machine.keyboardNudge('Delete', false, 100, 100)).toBeNull()
    expect(machine.keyboardNudge('Backspace', true, 100, 100)).toBeNull()
  })

  test('键盘微移经 commitKeyboardMove 提交（携带起点）；拖拽在途时方向键不落位', () => {
    const { machine, commits } = makeMachine(1)
    expect(machine.commitKeyboardMove('shot-1', 'ArrowUp', false, 100, 100)).toBe(true)
    expect(commits[0]).toEqual({ shotId: 'shot-1', from: { x: 100, y: 100 }, to: { x: 100, y: 92 } })
    expect(machine.commitKeyboardMove('shot-1', 'Enter', false, 100, 92)).toBe(false)

    machine.beginNodeDrag('shot-1', pointerEvent({ clientX: 0, clientY: 0, pointerId: 7 }), 100, 92)
    expect(machine.keyboardNudge('ArrowRight', false, 100, 92)).toEqual({ x: 108, y: 92 })
  })
})

describe('交互目标守卫（§8.2：video/button/input/textarea/select/a 不启动拖拽）', () => {
  test('closest 命中交互选择器；普通节点内容不命中', () => {
    const input = document.createElement('input')
    const video = document.createElement('video')
    const link = document.createElement('a')
    const plain = document.createElement('p')
    expect(isInteractiveTarget(input)).toBe(true)
    expect(isInteractiveTarget(video)).toBe(true)
    expect(isInteractiveTarget(link)).toBe(true)
    expect(isInteractiveTarget(plain)).toBe(false)
    expect(isInteractiveTarget(null)).toBe(false)
  })
})
