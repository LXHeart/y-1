import { ref } from 'vue'
import type {
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
import { parseSafetyFrame } from './useContentSafety'
import type { SafetyReport } from './useContentSafety'
import { fetchApi, request } from './grassland-http'
import { generateImage } from './useImageGeneration'

export function useArticleCreation() {
  const stage = ref<ArticleCreationStage>('topic')
  const topic = ref('')
  const platform = ref<ArticlePlatform>('wechat')
  const titles = ref<ArticleTitleOption[]>([])
  const selectedTitle = ref('')
  const outline = ref('')
  const content = ref('')
  const safetyReport = ref<SafetyReport | null>(null)

  const titlesLoading = ref(false)
  const outlineLoading = ref(false)
  const contentLoading = ref(false)
  const error = ref('')

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
    if (!trimmed) {
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
      error.value = '请选择或输入标题'
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
          topic: topic.value.trim(), title: trimmed, platform: platform.value, ...executionContext(),
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
    stage.value = 'content'

    try {
      const response = await fetchApi('/api/article-generation/content', {
        method: 'POST',
        body: JSON.stringify({
          topic: topic.value.trim(),
          title: selectedTitle.value.trim(),
          outline: outline.value.trim(),
          platform: platform.value,
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
        safetyReport.value = report
      }, controller.signal)

      stage.value = 'images'
    } catch (err: unknown) {
      if (err instanceof DOMException && err.name === 'AbortError') return
      error.value = err instanceof Error ? err.message : '正文生成失败，请稍后重试'
    } finally {
      contentLoading.value = false
      if (contentController === controller) contentController = null
    }
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
      const paragraphs = content.value
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
    if (!options?.keepPlatform) platform.value = 'wechat'
    titles.value = []
    selectedTitle.value = ''
    outline.value = ''
    content.value = ''
    safetyReport.value = null
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
    titlesLoading, outlineLoading, contentLoading, error,
    titleFormula, genre, style, styleSkillOptions,
    styleSkillsLoading, styleSkillsError, styleSkillsActive,
    imageSlots, imageRecommendations, loadingRecommendations, completed,
    fetchTitles, streamOutline, streamContent, fetchStyleSkills,
    selectTitle, confirmOutline,
    goToTitles, goToOutline, goToContent,
    loadImageRecommendations, searchImageForSlot, generateImageForSlot,
    selectImageForSlot, clearImageForSlot, toggleSlot,
    reset, cancel, setTopic, bindCreationContext, finish,
  }
}
