import { computed, ref } from 'vue'
import { fetchApi } from './grassland-http'
import { getPlatformFormatRule } from '../config/platform-format-rules'
import {
  findCardSeriesLayout,
  findCardSeriesPalette,
  findCardSeriesStyle,
} from '../constants/card-series-templates'
import { stripTrailingHashtagLines } from '../lib/article-hashtags'
import type { CreationBrief } from '../types/creation'

/**
 * 系列 AI 图卡（任务书 #54）：两段式——计划（SSE）→ 编辑 → 逐卡生成（JSON，部分成功）。
 * 文字渲染策略：生图是无文字插画底图，卡片标题/要点由前端 canvas 叠排后导出。
 *
 * AI内容中心改造-02：状态提升到工作流级——由 useArticleWorkspace 持有实例并序列化进
 * workspace.inputs.cards；生成带 owner+requestId 操作记录（网络失败复用同一 requestId，
 * 服务端同请求回读原结果/异请求 409，见 T21）；brief/任务快照随计划与生成透传。
 */

export interface PlannedCard {
  /** 稳定卡片身份；重试、排序和删除都不能用请求数组下标替代它。 */
  cardId?: string
  position?: number
  role?: 'cover' | 'content' | 'summary'
  title: string
  bullets: string[]
  illustration: string
  caption: string
}

export interface GeneratedCard {
  index: number
  cardId?: string
  role?: 'cover' | 'content' | 'summary'
  title: string
  ok: boolean
  url?: string
  revisedPrompt?: string
  errorReason?: string
}

export interface CardSeriesWorkspaceState {
  styleId: string
  layoutId: string
  paletteId: string
  size: string
  cardCount: number
  cards: PlannedCard[]
  results: GeneratedCard[]
  persistedMediaIds: Record<string, string>
}

/**
 * 图卡默认尺寸按平台 imageSpec 画幅联动（任务书 #70 卡C）：aspect 命中生成白名单三档才改初值，
 * 否则维持竖版现状（如小红书 3:4 不在生图白名单，契约 note 已说明 9:16 亦可）。
 * 仅初始化一次——用户显式选择后不再自动覆盖。
 */
const CARD_SIZE_BY_ASPECT: Readonly<Record<string, string>> = {
  '9:16': '1024x1792',
  '1:1': '1024x1024',
  '16:9': '1792x1024',
}

function defaultCardSizeForPlatform(platform: string): string {
  const aspect = getPlatformFormatRule(platform)?.imageSpec?.aspect
  return (aspect && CARD_SIZE_BY_ASPECT[aspect]) || '1024x1792'
}

export function useCardSeries(initialPlatform = '') {
  const platform = ref(initialPlatform)
  const cardCount = ref(6)
  const styleId = ref('cute-fresh')
  const layoutId = ref('balanced')
  const paletteId = ref('macaron')
  const size = ref(defaultCardSizeForPlatform(initialPlatform))
  /** 任务/独立模式的上下文注入（由宿主工作流在保存会话建立后 set；生成请求时读取）。 */
  const brief = ref<CreationBrief | undefined>()
  const contextSnapshotId = ref('')

  const planning = ref(false)
  const planProgress = ref('')
  const planError = ref('')
  const cards = ref<PlannedCard[]>([])

  const generating = ref(false)
  const generateError = ref('')
  const results = ref<GeneratedCard[]>([])
  /** cardId → 永久 mediaId（存素材库成功后；工作区恢复时交付引用的权威来源）。 */
  const persistedMediaIds = ref<Record<string, string>>({})

  /** T21：同一逻辑生成操作复用同一 requestId——网络失败重试回读原操作，不重复计费。 */
  let pendingRequestId: string | null = null

  let planController: AbortController | null = null

  const styleText = computed(() => findCardSeriesStyle(styleId.value)?.prompt ?? styleId.value)
  const layoutText = computed(() => findCardSeriesLayout(layoutId.value)?.prompt ?? layoutId.value)
  const paletteText = computed(() => findCardSeriesPalette(paletteId.value)?.prompt ?? '')

  const canPlan = computed(() => !planning.value && !generating.value)

  /** 拆卡对象是已生成的长图文内容（2026-08-30 修订：制作方式取消，并入图文流；
   *  任务书 #60：末尾话题标签行先剥离——话题属于笔记正文，不拆成卡片要点）。 */
  async function plan(content: string): Promise<void> {
    const planContent = stripTrailingHashtagLines(content).trim().slice(0, 8000)
    if (planning.value || generating.value || !planContent) return
    planController?.abort()
    planController = new AbortController()
    planning.value = true
    planError.value = ''
    planProgress.value = ''
    cards.value = []
    results.value = []
    // 重新拆卡 = 新的一组卡片身份；旧卡的持久化记录随旧卡一起退场（素材仍在素材库）。
    persistedMediaIds.value = {}

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
          brief: brief.value,
          contextSnapshotId: contextSnapshotId.value || undefined,
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
          cards.value = frame.cards.map((card, index) => ({
            cardId: card.cardId || crypto.randomUUID(),
            position: index + 1,
            role: card.role === 'cover' || card.role === 'content' || card.role === 'summary'
              ? card.role
              : index === 0 ? 'cover' : 'content',
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
    cards.value = cards.value.map((card, index) => ({ ...card,
      cardId: card.cardId || crypto.randomUUID(), position: index + 1,
      role: card.role || (index === 0 ? 'cover' : 'content'),
    }))
    const payloadCards = target === 'all'
      ? cards.value
      : cards.value[target] ? [cards.value[target]] : []
    if (!payloadCards.length) return
    generating.value = true
    generateError.value = ''
    const requestId = pendingRequestId ?? crypto.randomUUID()
    // 记录在途操作：网络失败/409 时复用同一 ID 回读；确定性应答后释放。
    pendingRequestId = requestId
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
          contextSnapshotId: contextSnapshotId.value || undefined,
          requestId,
        }),
      })
      // 服务端已给出确定性应答即代表操作已终结（记录已落）——释放复用键；
      // 唯 409（执行中待确认/参数冲突）保留，再次点击回读同一操作，不重复计费。
      if (response.status !== 409) pendingRequestId = null
      const parsed = await response.json() as {
        success?: boolean
        error?: string
        data?: { cards?: GeneratedCard[] }
      }
      if (!response.ok || !parsed.success) {
        throw new Error(parsed.error || `请求失败（${response.status}）`)
      }
      const incoming = (parsed.data?.cards ?? []).map((result, index) => ({ ...result,
        cardId: result.cardId || payloadCards[index]?.cardId, role: result.role || payloadCards[index]?.role,
      }))
      if (target === 'all') {
        results.value = cards.value.map((card, index) => ({
          ...(incoming.find(result => result.cardId === card.cardId) ?? { cardId: card.cardId, title: card.title, ok: false, errorReason: '未返回卡片结果' }), index,
        }))
      } else {
        const cardId = payloadCards[0].cardId
        const position = cards.value.findIndex(card => card.cardId === cardId)
        const result = incoming.find(item => item.cardId === cardId)
        if (position < 0 || !result) throw new Error('未返回对应卡片结果')
        const replaced = [...results.value]
        replaced[position] = { ...result, index: position }
        results.value = replaced
      }
    } catch (err: unknown) {
      generateError.value = err instanceof Error ? err.message : '卡片生成失败，请稍后重试'
    } finally {
      generating.value = false
    }
  }

  function removeCard(index: number): void {
    if (generating.value || cards.value.length <= 1) return
    const removed = cards.value[index]?.cardId
    cards.value = cards.value.filter((_, position) => position !== index)
      .map((card, position) => ({ ...card, position: position + 1 }))
    results.value = results.value.filter((card, position) => removed ? card.cardId !== removed : position !== index)
      .map((card, position) => ({ ...card, index: position }))
  }

  function addCard(): void {
    if (generating.value || cards.value.length >= 9) return
    cards.value = [...cards.value, { cardId: crypto.randomUUID(), position: cards.value.length + 1,
      role: 'content', title: '', bullets: [], illustration: '', caption: '' }]
  }

  /** TTL 卡转永久并注册进个人素材库（复用既有 content-assets 链）。成功后记录 cardId → mediaId。 */
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
      if (card.cardId) persistedMediaIds.value = { ...persistedMediaIds.value, [card.cardId]: mediaId }
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

  /** 工作区序列化（AI内容中心改造-02 §2.2：刷新/切换步骤不丢计划与成功卡）。 */
  function collectWorkspaceState(): CardSeriesWorkspaceState {
    return {
      styleId: styleId.value, layoutId: layoutId.value, paletteId: paletteId.value,
      size: size.value, cardCount: cardCount.value,
      cards: cards.value.map(card => ({ ...card, bullets: [...card.bullets] })),
      results: results.value.map(result => ({ ...result })),
      persistedMediaIds: { ...persistedMediaIds.value },
    }
  }

  /** 工作区恢复：URL 过期（TTL 30 分钟）的已保存卡保留身份与 mediaId，图片可经导出重新取链接。 */
  function restoreWorkspaceState(state: Partial<CardSeriesWorkspaceState> | undefined | null): void {
    if (!state) return
    styleId.value = state.styleId ?? styleId.value
    layoutId.value = state.layoutId ?? layoutId.value
    paletteId.value = state.paletteId ?? paletteId.value
    size.value = state.size ?? size.value
    cardCount.value = state.cardCount ?? cardCount.value
    cards.value = Array.isArray(state.cards)
      ? state.cards.map((card, index) => ({
        ...card,
        cardId: card.cardId || crypto.randomUUID(),
        position: card.position ?? index + 1,
        role: card.role ?? (index === 0 ? 'cover' as const : 'content' as const),
        bullets: Array.isArray(card.bullets) ? [...card.bullets] : [],
      }))
      : []
    results.value = Array.isArray(state.results)
      ? state.results.map((result, index) => ({ ...result, index: result.index ?? index }))
      : []
    persistedMediaIds.value = { ...(state.persistedMediaIds ?? {}) }
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
    persistedMediaIds.value = {}
    pendingRequestId = null
    planError.value = ''
    generateError.value = ''
    planProgress.value = ''
  }

  return {
    platform, cardCount, styleId, layoutId, paletteId, size,
    planning, planProgress, planError, cards,
    generating, generateError, results, persistedMediaIds,
    styleText, layoutText, paletteText, canPlan,
    setBrief: (value: CreationBrief | undefined) => { brief.value = value },
    setContextSnapshotId: (value: string) => { contextSnapshotId.value = value },
    plan, cancelPlan, generateCards, removeCard, addCard, persistCard, downloadCardWith, reset,
    collectWorkspaceState, restoreWorkspaceState,
  }
}
