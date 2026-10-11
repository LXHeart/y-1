/**
 * 图卡上移/下移（方案 §4.4【pub-image-order】）：纯函数 + 作用于 useCardSeries
 * 可写 refs 的薄封装——不改共享 composable，顺序随 collectWorkspaceState 持久化，
 * 交付 mediaRefs 按新 position 排列（useArticleWorkspace 的 deliveryMediaRefs 按
 * cards 顺序收集）。规则：相邻交换并重编 position；results 按 cardId 跟随卡片移动、
 * index 重写为新位次；生成中不动（避免与 generateCards 的结果回写竞争）；首/尾边界
 * 与空列表/单卡不动作。
 */
import type { Ref } from 'vue'
import type { GeneratedCard, PlannedCard } from '../../../composables/useCardSeries'

export type XhsImageOrderDirection = -1 | 1

export interface XhsImageOrderResult {
  cards: PlannedCard[]
  results: GeneratedCard[]
}

/** useCardSeries 中排序所需的最小结构（结构子集，测试可传假实例）。 */
export interface XhsImageOrderSeries {
  cards: Ref<PlannedCard[]>
  results: Ref<GeneratedCard[]>
  generating: Ref<boolean>
}

/**
 * 相邻交换纯函数：index 与 index+direction 互换后重编 position（1 起）；
 * results 按 cardId 对齐新顺序并重写 index。越界、空列表、单卡返回 null（不动作）。
 */
export function moveCardOrder(
  cards: readonly PlannedCard[],
  results: readonly GeneratedCard[],
  index: number,
  direction: XhsImageOrderDirection,
): XhsImageOrderResult | null {
  if (direction !== -1 && direction !== 1) return null
  if (!Number.isInteger(index)) return null
  const target = index + direction
  if (index < 0 || target < 0 || index >= cards.length || target >= cards.length) return null

  const reordered = [...cards]
  const moved = reordered[index]
  reordered[index] = reordered[target]
  reordered[target] = moved
  const nextCards = reordered.map((card, position) => ({ ...card, position: position + 1 }))

  // results 与 cards 在 plan/generate/remove 各路径均按 cardId 平行维护——移动后按
  // cardId 找回各自卡片的结果并重写 index；未命中的孤儿结果按原相对顺序保留在队尾
  // （正常路径不出现，防御旧草稿脏数据，不丢已生成图片）。
  const consumed = new Set<number>()
  const nextResults: GeneratedCard[] = []
  nextCards.forEach((card, position) => {
    if (!card.cardId) return
    const foundAt = results.findIndex((result, i) => !consumed.has(i) && result.cardId === card.cardId)
    if (foundAt >= 0) {
      consumed.add(foundAt)
      nextResults.push({ ...results[foundAt], index: position })
    }
  })
  results.forEach((result, i) => {
    if (!consumed.has(i)) nextResults.push(result)
  })
  return { cards: nextCards, results: nextResults }
}

/** 薄封装：生成中/边界/纯函数返回 null 时不动作，否则整组写回 useCardSeries 的可写 refs。 */
export function useXhsImageOrder(series: XhsImageOrderSeries) {
  function move(index: number, direction: XhsImageOrderDirection): void {
    if (series.generating.value) return
    const next = moveCardOrder(series.cards.value, series.results.value, index, direction)
    if (!next) return
    series.cards.value = next.cards
    series.results.value = next.results
  }
  return { move }
}
