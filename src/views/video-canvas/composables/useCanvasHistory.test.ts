// @vitest-environment happy-dom
import { describe, expect, test } from 'vitest'
import {
  HISTORY_MAX_BYTES,
  HISTORY_MAX_ENTRIES,
  estimateEntryBytes,
  useCanvasHistory,
} from './useCanvasHistory'

function change(shotId: string, fromX: number, toX: number) {
  return { shotId, from: { x: fromX, y: 0 }, to: { x: toX, y: 0 } }
}

describe('useCanvasHistory（R07 有界布局历史）', () => {
  test('撤销/重做往返：undo 返回逆向（to→from），redo 返回正向', () => {
    const history = useCanvasHistory()
    history.record([change('shot-1', 0, 80)])

    const undone = history.undo()
    expect(undone).toEqual([{ shotId: 'shot-1', from: { x: 80, y: 0 }, to: { x: 0, y: 0 } }])
    expect(history.canUndo.value).toBe(false)
    expect(history.canRedo.value).toBe(true)

    const redone = history.redo()
    expect(redone).toEqual([change('shot-1', 0, 80)])
    expect(history.canRedo.value).toBe(false)
    expect(history.canUndo.value).toBe(true)
  })

  test('新操作清空 redo；零位移与空记录不入栈', () => {
    const history = useCanvasHistory()
    history.record([change('shot-1', 0, 80)])
    history.undo()
    expect(history.canRedo.value).toBe(true)

    history.record([change('shot-2', 10, 30)])
    expect(history.canRedo.value).toBe(false)
    expect(history.redo()).toBeNull()

    history.record([change('shot-2', 30, 30)])
    history.record([])
    const snapshot = history.undo() // 只有两条可撤销
    expect(snapshot).toEqual([{ shotId: 'shot-2', from: { x: 30, y: 0 }, to: { x: 10, y: 0 } }])
  })

  test('50 步上限：第 51 条淘汰最旧；字节预算超限同样淘汰最旧', () => {
    const history = useCanvasHistory()
    for (let index = 0; index < HISTORY_MAX_ENTRIES + 1; index += 1) {
      history.record([change(`shot-${index}`, 0, index)])
    }
    // 最旧的 shot-0 已被淘汰：连撤 50 步后第 51 次无票
    let steps = 0
    while (history.undo()) steps += 1
    expect(steps).toBe(HISTORY_MAX_ENTRIES)
    expect(history.undo()).toBeNull()

    // 字节预算：3 条 ~800KB 载荷超 2MiB → 淘汰最旧只剩 2 条
    const heavy = useCanvasHistory()
    const bigFrom = { x: 0, y: 0 }
    const bigTo = { x: 1, y: 1 }
    const pad = 'p'.repeat(800_000)
    const entryChanges = [
      { shotId: `shot-${pad}`, from: bigFrom, to: bigTo },
    ]
    expect(estimateEntryBytes(entryChanges)).toBeGreaterThan(800_000)
    heavy.record(entryChanges)
    heavy.record(entryChanges)
    heavy.record(entryChanges)
    let heavySteps = 0
    while (heavy.undo()) heavySteps += 1
    expect(heavySteps).toBe(2)
  })

  test('clear() 清空栈（重新加载/换账号）', () => {
    const history = useCanvasHistory()
    history.record([change('shot-1', 0, 80)])
    history.clear()
    expect(history.canUndo.value).toBe(false)
    expect(history.canRedo.value).toBe(false)
    expect(history.undo()).toBeNull()
  })

  test('HISTORY_MAX_BYTES 常量为 2 MiB', () => {
    expect(HISTORY_MAX_BYTES).toBe(2 * 1024 * 1024)
  })
})
