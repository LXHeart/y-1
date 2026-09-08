// @vitest-environment happy-dom
import { afterEach, describe, expect, test, vi } from 'vitest'
import { useCardSeries } from './useCardSeries'

afterEach(() => vi.unstubAllGlobals())
function series() {
  const state = useCardSeries('xiaohongshu')
  state.cards.value = ['cover', 'content', 'content'].map((role, index) => ({ cardId: `card-${index + 1}`,
    position: index + 1, role: role as 'cover' | 'content', title: `标题 ${index + 1}`, bullets: [], illustration: '画面', caption: '' }))
  state.results.value = state.cards.value.map((card, index) => ({ index, cardId: card.cardId, role: card.role,
    title: card.title, ok: index < 2, url: `/image/${index}`, revisedPrompt: index === 0 ? '统一暖色风格' : '' }))
  const fetchMock = vi.fn(async (_url: unknown, init: RequestInit) => {
    const payload = JSON.parse(String(init.body)) as { cards: Array<{ position?: number; cardId?: string; role?: string; title?: string }> }
    return new Response(JSON.stringify({ success: true, data: { cards: payload.cards.map(card => ({
      index: (card.position ?? 1) - 1, cardId: card.cardId, role: card.role, title: card.title, ok: true, url: `/new/${card.cardId}`,
    })) } }))
  })
  vi.stubGlobal('fetch', fetchMock)
  return { state, fetchMock }
}

describe('图卡稳定身份', () => {
  test('第三张单卡重试保留位置与内容页角色，按 ID 合并结果', async () => {
    const { state, fetchMock } = series()
    await state.generateCards(2)
    expect(JSON.parse(String(fetchMock.mock.calls[0][1]?.body))).toMatchObject({
      cards: [{ cardId: 'card-3', position: 3, role: 'content' }], styleAnchor: '统一暖色风格',
    })
    expect(state.results.value.map(card => card.url)).toEqual(['/image/0', '/image/1', '/new/card-3'])
  })
  test('封面重试也携带系列风格锚', async () => {
    const { state, fetchMock } = series()
    await state.generateCards(0)
    expect(JSON.parse(String(fetchMock.mock.calls[0][1]?.body))).toMatchObject({
      cards: [{ cardId: 'card-1', position: 1, role: 'cover' }], styleAnchor: '统一暖色风格',
    })
  })
  test('删卡后 ID 不变、位置重排，生成中禁止增删和重新规划', async () => {
    const { state, fetchMock } = series()
    state.removeCard(1)
    expect(state.cards.value.map(card => [card.cardId, card.position])).toEqual([['card-1', 1], ['card-3', 2]])
    expect(state.results.value.map(card => card.cardId)).toEqual(['card-1', 'card-3'])
    let finish!: () => void
    fetchMock.mockImplementationOnce(() => new Promise(resolve => { finish = () => resolve(new Response(JSON.stringify({ success: true, data: { cards: [] } }))) }))
    const pending = state.generateCards('all')
    state.removeCard(0)
    state.addCard()
    await state.plan('新的正文')
    expect(state.cards.value.map(card => card.cardId)).toEqual(['card-1', 'card-3'])
    expect(fetchMock).toHaveBeenCalledOnce()
    finish()
    await pending
  })

  test('T21 操作记录：409 待确认复用同一 requestId，成功后释放', async () => {
    const { state, fetchMock } = series()
    fetchMock.mockImplementationOnce(async () => new Response(
      JSON.stringify({ success: false, error: '该图卡操作仍在执行或结果待确认' }), { status: 409 }))
    await state.generateCards(2)
    const first = JSON.parse(String(fetchMock.mock.calls[0][1]?.body)).requestId
    expect(first).toEqual(expect.any(String))

    // 409 后再次点击：同一操作回读（不重复计费）；本次 200 成功 → 释放
    await state.generateCards(2)
    const second = JSON.parse(String(fetchMock.mock.calls[1][1]?.body)).requestId
    expect(second).toBe(first)

    // 释放后新点击 = 新操作
    await state.generateCards(2)
    const third = JSON.parse(String(fetchMock.mock.calls[2][1]?.body)).requestId
    expect(third).not.toBe(first)
  })

  test('工作区序列化/恢复：计划、结果与已保存 mediaId 一起往返', () => {
    const { state } = series()
    state.persistedMediaIds.value = { 'card-1': 'media-1' }
    const saved = state.collectWorkspaceState()
    const next = useCardSeries('xiaohongshu')
    next.restoreWorkspaceState(saved)
    expect(next.cards.value.map(card => card.cardId)).toEqual(['card-1', 'card-2', 'card-3'])
    expect(next.cards.value[0].role).toBe('cover')
    expect(next.results.value.map(result => result.url)).toEqual(['/image/0', '/image/1', '/image/2'])
    expect(next.persistedMediaIds.value).toEqual({ 'card-1': 'media-1' })
    expect(next.collectWorkspaceState()).toEqual(saved)
  })
})
