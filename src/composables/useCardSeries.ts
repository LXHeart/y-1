import { computed, ref } from 'vue'
import { fetchApi } from './grassland-http'
import {
  findCardSeriesLayout,
  findCardSeriesPalette,
  findCardSeriesStyle,
} from '../constants/card-series-templates'
import { stripTrailingHashtagLines } from '../lib/article-hashtags'

/**
 * 系列 AI 图卡（任务书 #54）：两段式——计划（SSE）→ 编辑 → 逐卡生成（JSON，部分成功）。
 * 文字渲染策略：生图是无文字插画底图，卡片标题/要点由前端 canvas 叠排后导出。
 */

export interface PlannedCard {
  title: string
  bullets: string[]
  illustration: string
  caption: string
}

export interface GeneratedCard {
  index: number
  title: string
  ok: boolean
  url?: string
  revisedPrompt?: string
  errorReason?: string
}

export function useCardSeries(initialPlatform = '') {
  const platform = ref(initialPlatform)
  const cardCount = ref(6)
  const styleId = ref('cute-fresh')
  const layoutId = ref('balanced')
  const paletteId = ref('macaron')
  const size = ref('1024x1792')

  const planning = ref(false)
  const planProgress = ref('')
  const planError = ref('')
  const cards = ref<PlannedCard[]>([])

  const generating = ref(false)
  const generateError = ref('')
  const results = ref<GeneratedCard[]>([])

  let planController: AbortController | null = null

  const styleText = computed(() => findCardSeriesStyle(styleId.value)?.prompt ?? styleId.value)
  const layoutText = computed(() => findCardSeriesLayout(layoutId.value)?.prompt ?? layoutId.value)
  const paletteText = computed(() => findCardSeriesPalette(paletteId.value)?.prompt ?? '')

  const canPlan = computed(() => !planning.value)

  /** 拆卡对象是已生成的长图文内容（2026-08-30 修订：制作方式取消，并入图文流；
   *  任务书 #60：末尾话题标签行先剥离——话题属于笔记正文，不拆成卡片要点）。 */
  async function plan(content: string): Promise<void> {
    const planContent = stripTrailingHashtagLines(content).trim().slice(0, 8000)
    if (planning.value || !planContent) return
    planController?.abort()
    planController = new AbortController()
    planning.value = true
    planError.value = ''
    planProgress.value = ''
    cards.value = []
    results.value = []

    try {
      const response = await fetchApi('/api/card-series/plan', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          platform: platform.value || undefined,
          content: planContent,
          cardCount: cardCount.value,
          styleText: styleText.value,
          layoutText: layoutText.value,
          paletteText: paletteText.value || undefined,
        }),
        signal: planController.signal,
      })
      if (!response.ok) {
        let message = `请求失败（${response.status}）`
        try {
          const parsed = await response.json() as { error?: string }
          if (parsed.error) message = parsed.error
        } catch { /* 非 JSON 错误体保留默认文案 */ }
        throw new Error(message)
      }
      await consumePlanFrames(response)
    } catch (err: unknown) {
      if (err instanceof DOMException && err.name === 'AbortError') return
      planError.value = err instanceof Error ? err.message : '卡片计划生成失败，请稍后重试'
    } finally {
      planning.value = false
      planController = null
    }
  }

  async function consumePlanFrames(response: Response): Promise<void> {
    const reader = response.body?.getReader()
    if (!reader) throw new Error('响应没有可读流')
    const decoder = new TextDecoder()
    let buffer = ''
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop() ?? ''
      for (const line of lines) {
        if (!line.startsWith('data: ')) continue
        const payload = line.slice(6).trim()
        if (payload === '[DONE]') return
        if (!payload) continue
        let frame: { type?: string; message?: string; cards?: PlannedCard[]; error?: string }
        try {
          frame = JSON.parse(payload) as typeof frame
        } catch {
          throw new Error('SSE 响应格式错误')
        }
        if (frame.type === 'error' || frame.error) {
          throw new Error(frame.error || '卡片计划生成失败')
        }
        if (frame.type === 'progress') {
          planProgress.value = frame.message || '正在拆解卡片计划…'
          continue
        }
        if (frame.type === 'result' && Array.isArray(frame.cards) && frame.cards.length) {
          cards.value = frame.cards.map((card) => ({
            title: card.title || '',
            bullets: Array.isArray(card.bullets) ? card.bullets : [],
            illustration: card.illustration || '',
            caption: card.caption || '',
          }))
        }
      }
    }
    throw new Error('SSE 响应流意外中断：未收到 [DONE]')
  }

  function cancelPlan(): void {
    planController?.abort()
    planController = null
  }

  async function generateCards(target: 'all' | number): Promise<void> {
    if (generating.value) return
    const payloadCards = target === 'all'
      ? cards.value
      : [cards.value[target]]
    if (!payloadCards.length) return
    generating.value = true
    generateError.value = ''
    try {
      const response = await fetchApi('/api/card-series/generate', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          platform: platform.value || undefined,
          cards: payloadCards,
          styleText: styleText.value,
          layoutText: layoutText.value,
          paletteText: paletteText.value || undefined,
          size: size.value,
          styleAnchor: target !== 'all'
            ? (results.value.find((card) => card.ok && card.revisedPrompt)?.revisedPrompt ?? undefined)
            : undefined,
        }),
      })
      const parsed = await response.json() as {
        success?: boolean
        error?: string
        data?: { cards?: GeneratedCard[] }
      }
      if (!response.ok || !parsed.success) {
        throw new Error(parsed.error || `请求失败（${response.status}）`)
      }
      const incoming = parsed.data?.cards ?? []
      if (target === 'all') {
        results.value = incoming
      } else {
        // 单卡重试：替换对应卡片结果（后端 index 是请求内序号，映射回原卡位）
        const replaced = [...results.value]
        replaced[target] = { ...incoming[0], index: target }
        results.value = replaced
      }
    } catch (err: unknown) {
      generateError.value = err instanceof Error ? err.message : '卡片生成失败，请稍后重试'
    } finally {
      generating.value = false
    }
  }

  function removeCard(index: number): void {
    if (cards.value.length <= 1) return
    cards.value = cards.value.filter((_, position) => position !== index)
  }

  function addCard(): void {
    if (cards.value.length >= 9) return
    cards.value = [...cards.value, { title: '', bullets: [], illustration: '', caption: '' }]
  }

  /** TTL 卡转永久并注册进个人素材库（复用既有 content-assets 链）。 */
  async function persistCard(card: GeneratedCard): Promise<string | null> {
    if (!card.url) return null
    const cardId = card.url.substring(card.url.lastIndexOf('/') + 1)
    try {
      const persistResponse = await fetchApi(`/api/card-series/cards/${cardId}/persist`, { method: 'POST' })
      const persistParsed = await persistResponse.json() as {
        success?: boolean; error?: string; data?: { mediaId?: string }
      }
      if (!persistResponse.ok || !persistParsed.success || !persistParsed.data?.mediaId) {
        throw new Error(persistParsed.error || '持久化失败')
      }
      const mediaId = persistParsed.data.mediaId
      const registerResponse = await fetchApi('/api/content-assets', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          libraryType: 'personal',
          mediaId,
          category: 'other',
          title: card.title || '系列图卡',
          tags: ['系列图卡'],
        }),
      })
      if (!registerResponse.ok) {
        const registerParsed = await registerResponse.json() as { error?: string }
        throw new Error(registerParsed.error || '素材库登记失败')
      }
      return mediaId
    } catch (err: unknown) {
      generateError.value = err instanceof Error ? err.message : '保存到素材库失败'
      return null
    }
  }

  /** 下载成图：文字已由生图模型绘制在画面中（2026-09-02 策略改版），直接下载原图、不再 canvas 叠排。 */
  async function downloadCardWith(card: GeneratedCard): Promise<void> {
    if (!card.url) return
    try {
      const response = await fetch(card.url)
      if (!response.ok) throw new Error(`下载失败：${response.status}`)
      const blob = await response.blob()
      const link = document.createElement('a')
      link.download = `系列图卡-${card.index + 1}.png`
      link.href = URL.createObjectURL(blob)
      link.click()
      URL.revokeObjectURL(link.href)
    } catch {
      generateError.value = '卡片导出失败，请重试'
    }
  }

  function reset(): void {
    cards.value = []
    results.value = []
    planError.value = ''
    generateError.value = ''
    planProgress.value = ''
  }

  return {
    platform, cardCount, styleId, layoutId, paletteId, size,
    planning, planProgress, planError, cards,
    generating, generateError, results,
    styleText, layoutText, paletteText, canPlan,
    plan, cancelPlan, generateCards, removeCard, addCard, persistCard, downloadCardWith, reset,
  }
}
