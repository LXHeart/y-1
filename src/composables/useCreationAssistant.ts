import { ref } from 'vue'
import { fetchApi } from './grassland-http'
import type {
  ContentScore,
  CoverageGap,
  GuideBrief,
  GuideMessage,
  GuideResult,
  ScoreDimension,
  StructuredTopic,
  TaskCoverage,
} from '../types/creation-assistant'

/** SSE 帧的宽松形状：各端点共用一个解析器，字段按 type 分支取用。 */
interface AssistantFrame {
  type?: string
  content?: string
  error?: string
  dimension?: string
  score?: number
  advice?: string
  question?: string
  angle?: string
  audience?: string
  structure?: string
  inferredFields?: string
  requirement?: string
  status?: string
  hint?: string
  covered?: boolean
  topic?: string
  thesis?: string
  entryPoints?: string
}

/**
 * 逐帧消费 SSE（`data: <json>\n\n`，`[DONE]` 收尾），镜像 `useArticleCreation.consumeSSEStream`。
 *
 * 与那份的差别：这里把**整帧对象**交给回调而不是只给 `content` 字符串——助手的帧是判别联合
 * （score/ask/brief/gap/covered/topic），只取 content 会丢掉全部结构。
 */
async function consumeFrames(
  response: Response,
  onFrame: (frame: AssistantFrame) => void,
  signal?: AbortSignal,
): Promise<void> {
  const reader = response.body?.getReader()
  if (!reader) throw new Error('响应没有可读流')

  const decoder = new TextDecoder()
  let buffer = ''

  while (true) {
    if (signal?.aborted) {
      await reader.cancel()
      return
    }
    const { done, value } = await reader.read()
    if (done) break
    if (signal?.aborted) {
      await reader.cancel()
      return
    }

    buffer += decoder.decode(value, { stream: true })
    const lines = buffer.split('\n')
    buffer = lines.pop() ?? ''

    for (const line of lines) {
      if (!line.startsWith('data: ')) continue
      const payload = line.slice(6).trim()
      if (payload === '[DONE]') return
      if (!payload) continue

      let parsed: AssistantFrame
      try {
        parsed = JSON.parse(payload) as AssistantFrame
      } catch {
        // 跨 chunk 的半帧仍留在 buffer；能走到这里说明服务端发出了完整但非法的 data 行。
        throw new Error('SSE 响应格式错误')
      }
      // 错误帧：后端在流已 200 开头后无法改状态码，改发 {error}（/suggest 退款后走这条）。
      if (parsed.error) throw new Error(parsed.error)
      onFrame(parsed)
    }
  }

  if (signal?.aborted) return
  throw new Error('SSE 响应流意外中断：未收到 [DONE]')
}

async function postStream(url: string, body: unknown, signal: AbortSignal): Promise<Response> {
  const response = await fetchApi(url, {
    method: 'POST',
    body: JSON.stringify(body),
    signal,
  })
  if (!response.ok) {
    let message = `请求失败（${response.status}）`
    try {
      const parsed = await response.json() as { error?: string }
      if (parsed.error) message = parsed.error
    } catch {
      // 非 JSON 错误体（网关 HTML 等）保留默认文案
    }
    throw new Error(message)
  }
  return response
}

/** 逗号/「；」分隔字符串 → 数组（后端 frame 只发标量，数组是拼接下发的）。 */
function splitList(raw: string | undefined, separator: string): string[] {
  if (!raw) return []
  return raw.split(separator).map((item) => item.trim()).filter(Boolean)
}

function toMessage(error: unknown, fallback: string): string {
  if (error instanceof DOMException && error.name === 'AbortError') return ''
  return error instanceof Error && error.message ? error.message : fallback
}

/**
 * 智能创作助手（§4.9.1~§4.9.6）：评分、优化建议、问答引导、任务覆盖、热点选题。
 *
 * 每个能力单独持 AbortController —— 用户可能同时开着评分和 chat，一个 abort 不该掀掉另一个。
 * **abort 不退积分**（内容已流出，后端策略），所以 UI 上不把 abort 当失败提示。
 */
export function useCreationAssistant() {
  const score = ref<ContentScore | null>(null)
  const scoring = ref(false)
  const scoreError = ref('')

  const suggestion = ref('')
  const suggesting = ref(false)
  const suggestError = ref('')

  const messages = ref<GuideMessage[]>([])
  const guiding = ref(false)
  const guideError = ref('')
  const brief = ref<GuideBrief | null>(null)

  const coverage = ref<TaskCoverage | null>(null)
  const checkingCoverage = ref(false)
  const coverageError = ref('')

  const structuredTopic = ref<StructuredTopic | null>(null)
  const resolvingTopic = ref(false)
  const topicError = ref('')

  let scoreController: AbortController | null = null
  let suggestController: AbortController | null = null
  let guideController: AbortController | null = null
  let coverageController: AbortController | null = null
  let topicController: AbortController | null = null

  /** 内容评分：逐维度帧累积 + overall 帧收口。 */
  async function runScore(content: string, platform?: string, title?: string): Promise<void> {
    scoreController?.abort()
    const controller = new AbortController()
    scoreController = controller
    const signal = controller.signal

    scoring.value = true
    scoreError.value = ''
    score.value = null
    const dimensions: ScoreDimension[] = []
    let overall = 0
    let sawOverall = false

    try {
      const response = await postStream('/api/creation-assistant/score',
        { content, platform, title }, signal)
      await consumeFrames(response, (frame) => {
        if (frame.type === 'score' && frame.dimension) {
          dimensions.push({
            dimension: frame.dimension,
            score: Number(frame.score ?? 0),
            advice: frame.advice ?? '',
          })
        } else if (frame.type === 'overall') {
          overall = Number(frame.score ?? 0)
          sawOverall = true
        }
      }, signal)
      if (signal.aborted || scoreController !== controller) return
      if (!sawOverall) throw new Error('SSE 响应格式错误：缺少 overall 收口帧')
      if (!dimensions.length) throw new Error('SSE 响应格式错误：缺少 score dimension 帧')
      score.value = { dimensions, overall }
    } catch (error: unknown) {
      if (scoreController === controller) {
        scoreError.value = toMessage(error, '内容评分失败')
      }
    } finally {
      if (scoreController === controller) scoring.value = false
    }
  }

  /** 优化建议：纯流式，逐 chunk 追加（帧只有 content，没有 type）。 */
  async function runSuggest(content: string, platform?: string, title?: string): Promise<void> {
    suggestController?.abort()
    const controller = new AbortController()
    suggestController = controller
    const signal = controller.signal

    suggesting.value = true
    suggestError.value = ''
    suggestion.value = ''

    try {
      const response = await postStream('/api/creation-assistant/suggest',
        { content, platform, title }, signal)
      await consumeFrames(response, (frame) => {
        if (suggestController === controller && frame.content) suggestion.value += frame.content
      }, signal)
    } catch (error: unknown) {
      if (suggestController === controller) {
        suggestError.value = toMessage(error, '优化建议生成失败')
      }
    } finally {
      if (suggestController === controller) suggesting.value = false
    }
  }

  /**
   * 问答引导：把用户输入追加进对话，AI 回 ask（继续问）或 brief（简报）。
   *
   * history 传给后端的是**已格式化的对话文本**（后端 prompt 直接插字符串，不解析结构）。
   */
  async function sendGuideMessage(userInput: string, platform?: string): Promise<GuideResult | null> {
    const trimmed = userInput.trim()
    if (!trimmed) return null

    guideController?.abort()
    guideController = new AbortController()
    const signal = guideController.signal

    const history = messages.value
      .map((msg) => `${msg.role === 'user' ? '用户' : '教练'}：${msg.text}`)
      .join('\n')

    messages.value = [...messages.value, { role: 'user', text: trimmed }]
    guiding.value = true
    guideError.value = ''

    let result: GuideResult | null = null
    try {
      const response = await postStream('/api/creation-assistant/guide',
        { userInput: trimmed, platform, history: history || undefined }, signal)
      await consumeFrames(response, (frame) => {
        if (frame.type === 'ask' && frame.question) {
          result = { type: 'ask', question: frame.question }
        } else if (frame.type === 'brief') {
          result = {
            type: 'brief',
            angle: frame.angle ?? '',
            audience: frame.audience ?? '',
            structure: frame.structure ?? '',
            inferredFields: splitList(frame.inferredFields, ','),
          }
        }
      }, signal)

      if (signal.aborted) return null
      if (!result) throw new Error('SSE 响应格式错误：缺少 ask/brief 收口帧')
      if (result) {
        const resolved = result as GuideResult
        if (resolved.type === 'ask') {
          messages.value = [...messages.value, { role: 'assistant', text: resolved.question }]
        } else {
          brief.value = resolved
          messages.value = [...messages.value, {
            role: 'assistant',
            text: '已整理出创作简报',
            brief: resolved,
          }]
        }
      }
    } catch (error: unknown) {
      guideError.value = toMessage(error, '引导失败')
    } finally {
      guiding.value = false
    }
    return result
  }

  function resetGuide(): void {
    guideController?.abort()
    messages.value = []
    brief.value = null
    guideError.value = ''
  }

  /** 任务覆盖检查：gap 帧累积 + covered 帧收口。 */
  async function checkTaskCoverage(
    content: string, taskRequirements: string, platform?: string,
  ): Promise<void> {
    coverageController?.abort()
    const controller = new AbortController()
    coverageController = controller
    const signal = controller.signal

    checkingCoverage.value = true
    coverageError.value = ''
    coverage.value = null
    const gaps: CoverageGap[] = []
    // 后端 covered 是原生 boolean（曾被序列化成字符串 "false" —— JS 里 truthy，判断会反）。
    let covered = false
    let sawCovered = false

    try {
      const response = await postStream('/api/creation-assistant/task-coverage',
        { content, taskRequirements, platform }, signal)
      await consumeFrames(response, (frame) => {
        if (coverageController !== controller) return
        if (frame.type === 'gap' && frame.requirement) {
          gaps.push({
            requirement: frame.requirement,
            status: frame.status ?? 'missing',
            hint: frame.hint ?? '',
          })
        } else if (frame.type === 'covered') {
          covered = frame.covered === true
          sawCovered = true
        }
      }, signal)
      if (signal.aborted || coverageController !== controller) return
      if (!sawCovered) throw new Error('SSE 响应格式错误：缺少 covered 收口帧')
      coverage.value = { gaps, covered }
    } catch (error: unknown) {
      if (coverageController === controller) {
        coverageError.value = toMessage(error, '任务覆盖检查失败')
      }
    } finally {
      if (coverageController === controller) checkingCoverage.value = false
    }
  }

  /** 热点 → 结构化选题（§4.9.5）。 */
  async function topicFromHot(
    hotTitle: string, platform?: string, angleHint?: string,
  ): Promise<StructuredTopic | null> {
    topicController?.abort()
    topicController = new AbortController()
    const signal = topicController.signal

    resolvingTopic.value = true
    topicError.value = ''
    structuredTopic.value = null

    try {
      const response = await postStream('/api/creation-assistant/topic-from-hot',
        { hotTitle, platform, angleHint }, signal)
      await consumeFrames(response, (frame) => {
        if (frame.type === 'topic' && frame.topic) {
          structuredTopic.value = {
            topic: frame.topic,
            angle: frame.angle ?? '',
            thesis: frame.thesis ?? '',
            audience: frame.audience ?? '',
            entryPoints: splitList(frame.entryPoints, '；'),
          }
        }
      }, signal)
      if (signal.aborted) return null
      if (!structuredTopic.value) throw new Error('SSE 响应格式错误：缺少 topic 收口帧')
    } catch (error: unknown) {
      topicError.value = toMessage(error, '热点选题生成失败')
    } finally {
      resolvingTopic.value = false
    }
    return structuredTopic.value
  }

  /** 组件卸载时统一收线，避免流在后台继续读。 */
  function cancelAll(): void {
    scoreController?.abort()
    suggestController?.abort()
    guideController?.abort()
    coverageController?.abort()
    topicController?.abort()
  }

  function resetAssessments(): void {
    scoreController?.abort()
    suggestController?.abort()
    coverageController?.abort()
    scoreController = null
    suggestController = null
    coverageController = null
    score.value = null
    scoreError.value = ''
    scoring.value = false
    suggestion.value = ''
    suggestError.value = ''
    suggesting.value = false
    coverage.value = null
    coverageError.value = ''
    checkingCoverage.value = false
  }

  return {
    score, scoring, scoreError, runScore,
    suggestion, suggesting, suggestError, runSuggest,
    messages, guiding, guideError, brief, sendGuideMessage, resetGuide,
    coverage, checkingCoverage, coverageError, checkTaskCoverage,
    structuredTopic, resolvingTopic, topicError, topicFromHot,
    resetAssessments, cancelAll,
  }
}
