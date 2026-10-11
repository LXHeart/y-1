import { ref } from 'vue'
import { describe, expect, test } from 'vitest'
import { moveCardOrder, useXhsImageOrder } from './useXhsImageOrder'
import type { GeneratedCard, PlannedCard } from '../../../composables/useCardSeries'

/**
 * 图卡排序（方案 §6 useXhsImageOrder）：① 相邻交换+position 重编；② results 按
 * cardId 对齐；③ 首末边界；④ generating 不动作；⑤ 空列表/单卡。
 */

function card(id: string, position: number, role: PlannedCard['role'] = 'content'): PlannedCard {
  return { cardId: id, position, role, title: `卡${id}`, bullets: [], illustration: '', caption: '' }
}

function result(cardId: string, index: number, ok = true): GeneratedCard {
  return { index, cardId, title: `卡${cardId}`, ok, url: ok ? `https://img.example/${cardId}.png` : undefined }
}

const THREE_CARDS = () => [card('a', 1, 'cover'), card('b', 2), card('c', 3)]
const THREE_RESULTS = () => [result('a', 0), result('b', 1), result('c', 2)]

describe('moveCardOrder：相邻交换与重编', () => {
  test('上移：b 与 a 互换，position 全量重编为 1..n', () => {
    const next = moveCardOrder(THREE_CARDS(), THREE_RESULTS(), 1, -1)
    expect(next).not.toBeNull()
    expect(next!.cards.map((item) => item.cardId)).toEqual(['b', 'a', 'c'])
    expect(next!.cards.map((item) => item.position)).toEqual([1, 2, 3])
    // 角色随卡片移动不被重编（a 移到第 2 位仍是 cover，role 只在生成/恢复路径分配）。
    expect(next!.cards.find((item) => item.cardId === 'a')?.role).toBe('cover')
    expect(next!.cards[0].role).toBe('content')
  })

  test('下移：a 与 b 互换', () => {
    const next = moveCardOrder(THREE_CARDS(), THREE_RESULTS(), 0, 1)
    expect(next!.cards.map((item) => item.cardId)).toEqual(['b', 'a', 'c'])
    expect(next!.cards.map((item) => item.position)).toEqual([1, 2, 3])
  })

  test('不修改入参数组（纯函数：返回新数组）', () => {
    const cards = THREE_CARDS()
    const results = THREE_RESULTS()
    moveCardOrder(cards, results, 0, 1)
    expect(cards.map((item) => item.cardId)).toEqual(['a', 'b', 'c'])
    expect(results.map((item) => item.cardId)).toEqual(['a', 'b', 'c'])
  })
})

describe('moveCardOrder：results 按 cardId 对齐', () => {
  test('结果跟随卡片移动，index 重写为新位次', () => {
    const next = moveCardOrder(THREE_CARDS(), THREE_RESULTS(), 1, -1)!
    expect(next.results.map((item) => item.cardId)).toEqual(['b', 'a', 'c'])
    expect(next.results.map((item) => item.index)).toEqual([0, 1, 2])
  })

  test('结果乱序传入也按 cardId 归位（不依赖入参顺序）', () => {
    const shuffled = [result('c', 2), result('a', 0), result('b', 1)]
    const next = moveCardOrder(THREE_CARDS(), shuffled, 0, 1)!
    expect(next.results.map((item) => item.cardId)).toEqual(['b', 'a', 'c'])
    expect(next.results.map((item) => item.index)).toEqual([0, 1, 2])
  })

  test('失败卡结果保留（ok=false 不丢弃）；无 cardId 的卡不吞结果', () => {
    const cards = [card('a', 1, 'cover'), card('b', 2)]
    const results = [result('a', 0), { ...result('b', 1, false), url: undefined }]
    const next = moveCardOrder(cards, results, 1, -1)!
    expect(next.results.map((item) => item.cardId)).toEqual(['b', 'a'])
    expect(next.results[0].ok).toBe(false)
  })

  test('未命中任何卡片的孤儿结果按原相对顺序保留在队尾（防御旧草稿脏数据）', () => {
    const results = [...THREE_RESULTS(), result('orphan', 9)]
    const next = moveCardOrder(THREE_CARDS(), results, 0, 1)!
    expect(next.results.map((item) => item.cardId)).toEqual(['b', 'a', 'c', 'orphan'])
  })
})

describe('moveCardOrder：边界与非法输入', () => {
  test('首卡上移 / 末卡下移返回 null', () => {
    expect(moveCardOrder(THREE_CARDS(), THREE_RESULTS(), 0, -1)).toBeNull()
    expect(moveCardOrder(THREE_CARDS(), THREE_RESULTS(), 2, 1)).toBeNull()
  })

  test('越界与非整数下标返回 null', () => {
    expect(moveCardOrder(THREE_CARDS(), THREE_RESULTS(), 3, 1)).toBeNull()
    expect(moveCardOrder(THREE_CARDS(), THREE_RESULTS(), -1, 1)).toBeNull()
    expect(moveCardOrder(THREE_CARDS(), THREE_RESULTS(), 1.5, 1)).toBeNull()
  })

  test('非法方向（0/2）返回 null', () => {
    expect(moveCardOrder(THREE_CARDS(), THREE_RESULTS(), 1, 0 as -1 | 1)).toBeNull()
    expect(moveCardOrder(THREE_CARDS(), THREE_RESULTS(), 1, 2 as -1 | 1)).toBeNull()
  })

  test('空列表/单卡不动作', () => {
    expect(moveCardOrder([], [], 0, 1)).toBeNull()
    expect(moveCardOrder([card('solo', 1)], [result('solo', 0)], 0, 1)).toBeNull()
    expect(moveCardOrder([card('solo', 1)], [result('solo', 0)], 0, -1)).toBeNull()
  })
})

describe('useXhsImageOrder：写回 useCardSeries 可写 refs', () => {
  test('move 写回 cards/results；生成中不动', () => {
    const series = { cards: ref(THREE_CARDS()), results: ref(THREE_RESULTS()), generating: ref(false) }
    const { move } = useXhsImageOrder(series)
    move(0, 1)
    expect(series.cards.value.map((item) => item.cardId)).toEqual(['b', 'a', 'c'])
    expect(series.results.value.map((item) => item.index)).toEqual([0, 1, 2])

    series.generating.value = true
    move(0, 1)
    expect(series.cards.value.map((item) => item.cardId)).toEqual(['b', 'a', 'c']) // 未变化
  })

  test('边界不动作（写回不被触发）', () => {
    const series = { cards: ref(THREE_CARDS()), results: ref(THREE_RESULTS()), generating: ref(false) }
    const { move } = useXhsImageOrder(series)
    move(0, -1)
    move(2, 1)
    expect(series.cards.value.map((item) => item.cardId)).toEqual(['a', 'b', 'c'])
  })
})
