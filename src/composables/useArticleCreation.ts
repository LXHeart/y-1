import { ref } from 'vue'
import type {
  ArticleContentMode,
  ArticleCreationStage,
  ArticleImageSlot,
  ArticlePlatform,
  ArticleTitleOption,
  CreationStyleSkillCategory,
  CreationStyleSkillOption,
  GeneratedImage,
  ImageRecommendation,
  ImageSearchResult,
} from '../types/article-creation'
import type { AiPlatformId } from '../types/ai-creation'
import type { CreationDraft, CreationDraftVersion, SaveDraftInput } from '../types/creation-assistant'
import { parseSafetyFrame, recheckSafety } from './useContentSafety'
import type { SafetyReport } from './useContentSafety'
import { fetchApi, request } from './grassland-http'
import { generateImage } from './useImageGeneration'
import { stripTrailingHashtagLines } from '../lib/article-hashtags'
import { extractZhihuQuestionRef } from '../lib/zhihu-question'

export function useArticleCreation() {
  const stage = ref<ArticleCreationStage>('topic')
  const topic = ref('')
  const platform = ref<ArticlePlatform>('wechat')
  const titles = ref<ArticleTitleOption[]>([])
  const selectedTitle = ref('')
  const outline = ref('')
  const content = ref('')
  const safetyReport = ref<SafetyReport | null>(null)
  /**
   * 任务书 #63 卡5：最近一次检查对应的正文快照。进入检查步时 content !== lastCheckedText
   * 或报告为空 → 自动复查；生成流内 safety 帧到达时同步回填（正文帧在安全帧之前），
   * 未编辑的正文进检查步不重复打检查端点。
   */
  const lastCheckedText = ref<string | null>(null)
  /** 检查步的自动/手动复查进行中。 */
  const safetyChecking = ref(false)

  const titlesLoading = ref(false)
  const outlineLoading = ref(false)
  const contentLoading = ref(false)
  const error = ref('')

  /**
   * 任务书 #62：知乎回答/文章双模式。仅知乎分叉，其余平台恒 article（视图负责显隐与显式同步，
   * 全局约束 5——不能只看 platform，抖音与小红书共用 platform 值就是先例）。
   */
  const contentMode = ref<ArticleContentMode>('article')
  /** 回答模式的目标问题原文（纯手输，P2 拍板）。 */
  const question = ref('')
  /**
   * 从粘贴的知乎问题链接本地正则提取的 questionId，仅溯源存档。
   * **零网络请求**（任务书 #62 §3.7：抓取实测全 403，执行期不得 reintroduce 链接抓取）。
   */
  const questionRef = ref('')

  // 任务书 #57：小红书图文（非抖音）风格三选。''=未选（必选无默认，未选禁用生成按钮）；
  // 目录由服务端下发（治理台改完/停用即随下次拉取生效），前端不硬编码清单。
  const titleFormula = ref('')
  const genre = ref('')
  const style = ref('')
  const styleSkillOptions = ref<Record<CreationStyleSkillCategory, CreationStyleSkillOption[]>>({
    TITLE_FORMULA: [],
    GENRE: [],
    STYLE: [],
  })
  const styleSkillsLoading = ref(false)
  const styleSkillsError = ref('')
  /** 视图同步：仅小红书非抖音时携带新字段（抖音 platform 值同为 xiaohongshu，不能只看 platform）。 */
  const styleSkillsActive = ref(false)
  /** 任务书 #60：小红书图文（非抖音）跳过配图阶段——正文不配图，视觉素材由图卡承担。 */
  const imagesStageSkipped = ref(false)

  const imageSlots = ref<ArticleImageSlot[]>([])
  const imageRecommendations = ref<ImageRecommendation | null>(null)
  const loadingRecommendations = ref(false)
  const completed = ref(false)
  const taskMode = ref(false)
  const contextSnapshotId = ref<string | null>(null)
  const taskPlatformId = ref<AiPlatformId | null>(null)

  let titlesController: AbortController | null = null
  let outlineController: AbortController | null = null
  let contentController: AbortController | null = null
  let recommendationsController: AbortController | null = null
  const slotControllers = new Map<number, AbortController>()

  function executionContext() {
    return taskMode.value
      ? { taskMode: true, contextSnapshotId: contextSnapshotId.value }
      : {}
  }

  const MIN_QUESTION_LENGTH = 8

  /** 回答模式判据（任务书 #62 全局约束 2）：mode=answer 且问题非空，二者同时成立才成立。 */
  function isAnswerMode(): boolean {
    return contentMode.value === 'answer' && question.value.trim().length > 0
  }

  /**
   * 从输入里本地提取知乎 questionId（`zhihu.com/question/{数字}`，含 `/answer/xxx` 后缀链接）。
   * 纯正则、**零网络请求**；纯文本问题（无链接）不提取，返回 ''。
   */
  function extractQuestionRef(raw: string): string {
    return extractZhihuQuestionRef(raw)
  }

  /** 问题输入变更：原文照存（手输为准），链接则同步刷新溯源 id；非链接输入清空旧 id。 */
  function setQuestion(value: string): void {
    question.value = value
    questionRef.value = extractQuestionRef(value)
  }

  /**
   * 切换内容模式：清空 titles/outline/content 与已选开头（两套 prompt 产物不可混用），
   * **question 保留**（同一个目标问题可以在两种模式间比稿）。
   */
  function setContentMode(mode: ArticleContentMode): void {
    if (contentMode.value === mode) return
    titlesController?.abort()
    outlineController?.abort()
    contentController?.abort()
    clearImageControllers()
    contentMode.value = mode
    titles.value = []
    selectedTitle.value = ''
    outline.value = ''
    content.value = ''
    safetyReport.value = null
    lastCheckedText.value = null
    titlesLoading.value = false
    outlineLoading.value = false
    contentLoading.value = false
    error.value = ''
    imageSlots.value = []
    imageRecommendations.value = null
    completed.value = false
    stage.value = mode === 'answer' ? 'question' : 'topic'
  }

  /** 回答模式载荷：`answerMode:true` + 问题原文；文章模式不带新字段（后端=现状）。 */
  function answerPayload(): Record<string, unknown> {
    return isAnswerMode() ? { answerMode: true, question: question.value.trim() } : {}
  }

  /** 草稿三新列（任务书 #62）：整行覆盖语义下每次保存都要回填，漏带会把回答降级成文章。 */
  function draftFields(): Pick<SaveDraftInput, 'contentMode' | 'questionText' | 'questionRef'> {
    return {
      contentMode: contentMode.value,
      questionText: question.value.trim() || null,
      questionRef: questionRef.value || null,
    }
  }

  /** 恢复草稿/版本快照：还原模式与问题（缺省 article，与后端默认一致）。 */
  function applyDraft(draft: Pick<CreationDraft | CreationDraftVersion,
    'contentMode' | 'questionText' | 'questionRef'>): void {
    contentMode.value = draft.contentMode === 'answer' ? 'answer' : 'article'
    question.value = draft.questionText ?? ''
    // 溯源 id 以快照为准；快照没有则按当前问题原文重算（老草稿没有这列）。
    questionRef.value = draft.questionRef ?? extractQuestionRef(question.value)
  }

  /** 风格三选注入载荷：active 且已选才携带；未选/非小红书非抖音 → 不带新字段（后端=现状）。 */
  function stylePayload(): Record<string, string> {
    if (!styleSkillsActive.value) return {}
    const payload: Record<string, string> = {}
    if (titleFormula.value) payload.titleFormula = titleFormula.value
    if (genre.value) payload.genre = genre.value
    if (style.value) payload.style = style.value
    return payload
  }

  /** 拉取风格目录（一次全量三组合并；失败置 error 态供重试，不阻塞其它步骤）。 */
  async function fetchStyleSkills(): Promise<void> {
    if (styleSkillsLoading.value) return
    styleSkillsLoading.value = true
    styleSkillsError.value = ''
    try {
      const data = await request<{ skills: CreationStyleSkillOption[] }>('/api/creation-style-skills')
      const grouped: Record<CreationStyleSkillCategory, CreationStyleSkillOption[]> = {
        TITLE_FORMULA: [],
        GENRE: [],
        STYLE: [],
      }
      for (const skill of data?.skills || []) {
        if (skill.category in grouped) grouped[skill.category].push(skill)
      }
      styleSkillOptions.value = grouped
    } catch (err: unknown) {
      styleSkillsError.value = err instanceof Error ? err.message : '风格目录加载失败，请稍后重试'
    } finally {
      styleSkillsLoading.value = false
    }
  }

  function imageExecutionContext() {
    if (!taskMode.value) return {}
    const targetPlatform = taskPlatformId.value
      || (platform.value === 'wechat' ? 'wechat-official' : platform.value)
    return {
      taskMode: true,
      contextSnapshotId: contextSnapshotId.value,
      targetPlatform,
    }
  }

  async function consumeSSEStream(
    response: Response,
    onChunk: (text: string) => void,
    onSafety?: (report: SafetyReport) => void,
    signal?: AbortSignal,
  ): Promise<void> {
    const reader = response.body?.getReader()
    if (!reader) throw new Error('No response body')

    const decoder = new TextDecoder()
    let buffer = ''

    while (true) {
      if (signal?.aborted) {
        reader.cancel()
        break
      }

      const { done, value } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop() ?? ''

      for (const line of lines) {
        if (!line.startsWith('data: ')) continue
        const payload = line.slice(6).trim()
        if (payload === '[DONE]') return

        try {
          const parsed = JSON.parse(payload) as Record<string, unknown> & { content?: string; error?: string }
          if (parsed.error) throw new Error(parsed.error)
          const report = parseSafetyFrame(parsed)
          if (report) onSafety?.(report)
          if (parsed.content) onChunk(parsed.content)
        } catch (err: unknown) {
          if (err instanceof Error && err.message !== 'Unexpected end of JSON input') {
            throw err
          }
        }
      }
    }
  }

  async function fetchTitles(): Promise<void> {
    const trimmed = topic.value.trim()
    // 回答模式：问题是必填项，topic 降级为可选「补充说明」（后端同判据）。
    if (contentMode.value === 'answer') {
      if (question.value.trim().length < MIN_QUESTION_LENGTH) {
        error.value = `请输入目标问题（至少 ${MIN_QUESTION_LENGTH} 字）`
        return
      }
    } else if (!trimmed) {
      error.value = '请输入主题或关键词'
      return
    }

    titlesController?.abort()
    const controller = new AbortController()
    titlesController = controller

    titlesLoading.value = true
    error.value = ''
    titles.value = []
    selectedTitle.value = ''
    safetyReport.value = null

    try {
      const data = await request<{ titles: ArticleTitleOption[]; safety?: SafetyReport }>(
        '/api/article-generation/titles',
        {
          method: 'POST',
          body: JSON.stringify({
            topic: trimmed,
            platform: platform.value,
            ...answerPayload(),
            ...stylePayload(),
            ...executionContext(),
          }),
          signal: controller.signal,
        },
        { fallbackError: '标题生成失败' },
      )

      if (!data?.titles) {
        throw new Error('标题生成失败')
      }

      titles.value = data.titles
      safetyReport.value = parseSafetyFrame({ safety: data.safety })
      stage.value = 'titles'
    } catch (err: unknown) {
      if (err instanceof DOMException && err.name === 'AbortError') return
      error.value = err instanceof Error ? err.message : '标题生成失败，请稍后重试'
    } finally {
      titlesLoading.value = false
      if (titlesController === controller) titlesController = null
    }
  }

  async function streamOutline(): Promise<void> {
    const trimmed = selectedTitle.value.trim()
    if (!trimmed) {
      error.value = contentMode.value === 'answer' ? '请选择或输入开头' : '请选择或输入标题'
      return
    }

    outlineController?.abort()
    const controller = new AbortController()
    outlineController = controller

    outlineLoading.value = true
    error.value = ''
    outline.value = ''
    stage.value = 'outline'

    try {
      const response = await fetchApi('/api/article-generation/outline', {
        method: 'POST',
        body: JSON.stringify({
          topic: topic.value.trim(),
          // 回答模式复用 title 字段承载「选定开头」全文（后端 title 语义随 mode 分叉）。
          title: trimmed,
          platform: platform.value,
          ...answerPayload(),
          ...executionContext(),
        }),
        signal: controller.signal,
      })

      if (!response.ok) {
        const body = await response.json() as { error?: string }
        throw new Error(body.error || '大纲生成失败')
      }

      await consumeSSEStream(response, (chunk) => {
        outline.value += chunk
      }, undefined, controller.signal)

      stage.value = 'outline'
    } catch (err: unknown) {
      if (err instanceof DOMException && err.name === 'AbortError') return
      error.value = err instanceof Error ? err.message : '大纲生成失败，请稍后重试'
    } finally {
      outlineLoading.value = false
      if (outlineController === controller) outlineController = null
    }
  }

  async function streamContent(): Promise<void> {
    contentController?.abort()
    const controller = new AbortController()
    contentController = controller

    contentLoading.value = true
    error.value = ''
    content.value = ''
    safetyReport.value = null
    lastCheckedText.value = null
    stage.value = 'content'

    try {
      const response = await fetchApi('/api/article-generation/content', {
        method: 'POST',
        body: JSON.stringify({
          topic: topic.value.trim(),
          title: selectedTitle.value.trim(),
          outline: outline.value.trim(),
          platform: platform.value,
          ...answerPayload(),
          ...stylePayload(),
          ...executionContext(),
        }),
        signal: controller.signal,
      })

      if (!response.ok) {
        const body = await response.json() as { error?: string }
        throw new Error(body.error || '正文生成失败')
      }

      await consumeSSEStream(response, (chunk) => {
        content.value += chunk
      }, (report) => {
        // 安全帧在全部正文帧之后——此刻累积文本即被检文本（任务书 #63 卡5）
        safetyReport.value = report
        lastCheckedText.value = content.value
      }, controller.signal)

      enterCheck()
    } catch (err: unknown) {
      if (err instanceof DOMException && err.name === 'AbortError') return
      error.value = err instanceof Error ? err.message : '正文生成失败，请稍后重试'
    } finally {
      contentLoading.value = false
      if (contentController === controller) contentController = null
    }
  }

  /** 手动/自动复查：带 platform/contentForm（修「未知平台」根因），成功后同步检查快照。 */
  async function checkSafety(): Promise<void> {
    if (safetyChecking.value || !content.value.trim()) return
    safetyChecking.value = true
    const fresh = await recheckSafety(content.value, platform.value,
      platform.value === 'zhihu' ? contentMode.value : undefined)
    safetyChecking.value = false
    if (fresh) {
      safetyReport.value = fresh
      lastCheckedText.value = content.value
    }
  }

  /**
   * 进入检查步（任务书 #63 卡5）：报告为空或正文已改动 → 自动复查；未编辑不重复打端点。
   * 生成流完成与内容步「去检查」共用此入口。
   */
  function enterCheck(): void {
    stage.value = 'check'
    if (safetyReport.value === null || content.value !== lastCheckedText.value) {
      void checkSafety()
    }
  }

  /** 面板内复查回写（面板自己打的复查也要同步快照，避免下次进入检查步重复检查）。 */
  function onPanelRechecked(report: SafetyReport): void {
    safetyReport.value = report
    lastCheckedText.value = content.value
  }

  /** 修复应用：回写正文与快照并自动复查刷新报告（advisory——复查失败不阻断，报告保留旧值）。 */
  function applySafetyFix(fixed: string): void {
    content.value = fixed
    lastCheckedText.value = fixed
    void checkSafety()
  }

  /**
   * 检查步继续（P4 软确认）：仍有提醒时 confirm 放行（advisory 姿态不变硬闸）；
   * noteMode 检查为收尾步 → 完成；其余 → 进配图。
   */
  function proceedFromCheck(): void {
    const count = safetyReport.value?.findings.length ?? 0
    if (count > 0 && !window.confirm(
      `仍有 ${count} 项内容提醒,发布前建议先处理。仍要继续${imagesStageSkipped.value ? '完成' : '配图'}?`,
    )) return
    if (imagesStageSkipped.value) {
      finish()
      return
    }
    clearImageControllers()
    imageSlots.value = []
    imageRecommendations.value = null
    stage.value = 'images'
  }

  function selectTitle(title: string): void {
    selectedTitle.value = title
  }

  function confirmOutline(): void {
    stage.value = 'outline'
  }

  function goToTitles(): void {
    outlineController?.abort()
    outline.value = ''
    outlineLoading.value = false
    error.value = ''
    stage.value = 'titles'
  }

  function goToOutline(): void {
    contentController?.abort()
    content.value = ''
    safetyReport.value = null
    lastCheckedText.value = null
    contentLoading.value = false
    error.value = ''
    stage.value = 'outline'
  }

  function goToContent(): void {
    clearImageControllers()
    imageSlots.value = []
    imageRecommendations.value = null
    loadingRecommendations.value = false
    error.value = ''
    stage.value = 'content'
  }

  async function loadImageRecommendations(): Promise<void> {
    loadingRecommendations.value = true
    error.value = ''

    try {
      const paragraphs = stripTrailingHashtagLines(content.value)
        .split(/\n\n+/)
        .map(p => p.trim())
        .filter(p => p.length > 0)

      if (paragraphs.length === 0) {
        error.value = '文章内容为空'
        return
      }

      const placements = paragraphs.map((p, i) => ({
        position: `第${i + 1}段`,
        description: p.length > 80 ? p.slice(0, 80) + '…' : p,
        searchKeywords: p.replace(/\n/g, ' ').slice(0, 100),
        prompt: `基于以下内容生成插图：${p.replace(/\n/g, ' ').slice(0, 200)}`,
      }))

      imageRecommendations.value = { recommendedCount: paragraphs.length, placements }
      imageSlots.value = placements.map((placement) => ({
        placement,
        mode: 'none' as const,
        searchResults: [],
        selectedImage: null,
        generating: false,
        searching: false,
        skipped: false,
      }))
    } catch (err: unknown) {
      error.value = err instanceof Error ? err.message : '配图推荐失败，请稍后重试'
    } finally {
      loadingRecommendations.value = false
    }
  }

  async function searchImageForSlot(index: number): Promise<void> {
    const slot = imageSlots.value[index]
    if (!slot) return

    const existing = slotControllers.get(index)
    existing?.abort()
    const controller = new AbortController()
    slotControllers.set(index, controller)

    slot.searching = true
    slot.searchResults = []
    slot.mode = 'search'

    try {
      const data = await request<{ images: ImageSearchResult[] }>(
        '/api/article-generation/search-images',
        {
          method: 'POST',
          body: JSON.stringify({
            keywords: slot.placement.searchKeywords,
            count: 3,
          }),
          signal: controller.signal,
        },
        { fallbackError: '搜图失败' },
      )

      if (!data?.images) {
        throw new Error('搜图失败')
      }

      imageSlots.value = imageSlots.value.map((s, i) =>
        i === index ? { ...s, searchResults: data.images } : s,
      )
    } catch (err: unknown) {
      if (err instanceof DOMException && err.name === 'AbortError') return
      error.value = err instanceof Error ? err.message : '搜图失败，请稍后重试'
    } finally {
      const current = imageSlots.value[index]
      if (current) {
        imageSlots.value = imageSlots.value.map((s, i) =>
          i === index ? { ...s, searching: false } : s,
        )
      }
      if (slotControllers.get(index) === controller) slotControllers.delete(index)
    }
  }

  async function generateImageForSlot(index: number): Promise<void> {
    const slot = imageSlots.value[index]
    if (!slot) return

    const existing = slotControllers.get(index)
    existing?.abort()
    const controller = new AbortController()
    slotControllers.set(index, controller)

    slot.generating = true
    slot.mode = 'generate'

    try {
      const generated = await generateImage({
        prompt: slot.placement.prompt,
        size: '1024x1024',
        json: imageExecutionContext(),
        signal: controller.signal,
      })

      if (!generated?.imageUrl) {
        throw new Error('图片生成失败')
      }

      imageSlots.value = imageSlots.value.map((s, i) =>
        i === index ? { ...s, selectedImage: generated } : s,
      )
    } catch (err: unknown) {
      if (err instanceof DOMException && err.name === 'AbortError') return
      error.value = err instanceof Error ? err.message : '图片生成失败，请稍后重试'
    } finally {
      const current = imageSlots.value[index]
      if (current) {
        imageSlots.value = imageSlots.value.map((s, i) =>
          i === index ? { ...s, generating: false } : s,
        )
      }
      if (slotControllers.get(index) === controller) slotControllers.delete(index)
    }
  }

  function selectImageForSlot(index: number, image: ImageSearchResult | GeneratedImage): void {
    imageSlots.value = imageSlots.value.map((s, i) =>
      i === index ? { ...s, selectedImage: image } : s,
    )
  }

  function toggleSlot(index: number): void {
    imageSlots.value = imageSlots.value.map((s, i) =>
      i === index ? { ...s, skipped: !s.skipped } : s,
    )
  }

  function clearImageForSlot(index: number): void {
    imageSlots.value = imageSlots.value.map((s, i) =>
      i === index ? { ...s, selectedImage: null } : s,
    )
  }

  function clearImageControllers(): void {
    recommendationsController?.abort()
    recommendationsController = null
    for (const controller of slotControllers.values()) {
      controller.abort()
    }
    slotControllers.clear()
  }

  /**
   * 重置为第一步。keepPlatform：创作中心 handoff 会话内「重新开始」时保留已锁定的
   * 发布平台（否则会退回默认 wechat，与创作中心的配置脱节）；无 handoff 的直入
   * 场景保持原行为（回到默认公众号）。
   * 任务书 #57 决策 J：风格三选择随平台一并保留（连载创作少重复选）；清空仅靠手动改选。
   */
  function reset(options?: { keepPlatform?: boolean }): void {
    titlesController?.abort()
    outlineController?.abort()
    contentController?.abort()
    clearImageControllers()

    titlesController = null
    outlineController = null
    contentController = null

    stage.value = 'topic'
    topic.value = ''
    // 任务书 #62：模式随平台一并保留/清空——handoff 会话内保留已锁定平台时，
    // 模式也不该悄悄退回文章（否则与创作中心/任务锁定的形态脱节）。
    if (!options?.keepPlatform) {
      platform.value = 'wechat'
      contentMode.value = 'article'
      question.value = ''
      questionRef.value = ''
    } else if (contentMode.value === 'answer') {
      stage.value = 'question'
    }
    titles.value = []
    selectedTitle.value = ''
    outline.value = ''
    content.value = ''
    safetyReport.value = null
    lastCheckedText.value = null
    titlesLoading.value = false
    outlineLoading.value = false
    contentLoading.value = false
    error.value = ''

    imageSlots.value = []
    imageRecommendations.value = null
    loadingRecommendations.value = false
    completed.value = false
  }

  function finish(): void {
    completed.value = true
  }

  function setTopic(value: string): void {
    if (stage.value !== 'topic') reset()
    topic.value = value
    stage.value = 'topic'
  }

  function bindCreationContext(
    isTaskMode: boolean,
    snapshotId?: string,
    platformId?: AiPlatformId,
  ): void {
    taskMode.value = isTaskMode
    contextSnapshotId.value = snapshotId || null
    taskPlatformId.value = isTaskMode ? platformId || null : null
  }

  function cancel(): void {
    titlesController?.abort()
    outlineController?.abort()
    contentController?.abort()
    titlesLoading.value = false
    outlineLoading.value = false
    contentLoading.value = false
  }

  return {
    stage, topic, platform, titles, selectedTitle, outline, content, safetyReport,
    lastCheckedText, safetyChecking,
    titlesLoading, outlineLoading, contentLoading, error,
    contentMode, question, questionRef,
    titleFormula, genre, style, styleSkillOptions,
    styleSkillsLoading, styleSkillsError, styleSkillsActive, imagesStageSkipped,
    imageSlots, imageRecommendations, loadingRecommendations, completed,
    fetchTitles, streamOutline, streamContent, fetchStyleSkills,
    selectTitle, confirmOutline,
    goToTitles, goToOutline, goToContent,
    checkSafety, enterCheck, onPanelRechecked, applySafetyFix, proceedFromCheck,
    loadImageRecommendations, searchImageForSlot, generateImageForSlot,
    selectImageForSlot, clearImageForSlot, toggleSlot,
    reset, cancel, setTopic, bindCreationContext, finish,
    setContentMode, setQuestion, extractQuestionRef, isAnswerMode, draftFields, applyDraft,
  }
}
