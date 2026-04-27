import { ref } from 'vue'
import type {
  ArticleCreationStage,
  ArticleImageSlot,
  ArticlePlatform,
  ArticleTitleOption,
  GeneratedImage,
  ImageRecommendation,
  ImageSearchResult,
} from '../types/article-creation'

export function useArticleCreation() {
  const stage = ref<ArticleCreationStage>('topic')
  const topic = ref('')
  const platform = ref<ArticlePlatform>('wechat')
  const titles = ref<ArticleTitleOption[]>([])
  const selectedTitle = ref('')
  const outline = ref('')
  const content = ref('')

  const titlesLoading = ref(false)
  const outlineLoading = ref(false)
  const contentLoading = ref(false)
  const error = ref('')

  const imageSlots = ref<ArticleImageSlot[]>([])
  const imageRecommendations = ref<ImageRecommendation | null>(null)
  const loadingRecommendations = ref(false)
  const completed = ref(false)

  let titlesController: AbortController | null = null
  let outlineController: AbortController | null = null
  let contentController: AbortController | null = null
  let recommendationsController: AbortController | null = null
  const slotControllers = new Map<number, AbortController>()

  async function consumeSSEStream(
    response: Response,
    onChunk: (text: string) => void,
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
          const parsed = JSON.parse(payload) as { content?: string; error?: string }
          if (parsed.error) throw new Error(parsed.error)
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

    try {
      const response = await fetch('/api/article-generation/titles', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ topic: trimmed, platform: platform.value }),
        signal: controller.signal,
      })

      const body = await response.json() as { success?: boolean; data?: { titles: ArticleTitleOption[] }; error?: string }

      if (!response.ok || !body.success || !body.data?.titles) {
        throw new Error(body.error || '标题生成失败')
      }

      titles.value = body.data.titles
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
      const response = await fetch('/api/article-generation/outline', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ topic: topic.value.trim(), title: trimmed, platform: platform.value }),
        signal: controller.signal,
      })

      if (!response.ok) {
        const body = await response.json() as { error?: string }
        throw new Error(body.error || '大纲生成失败')
      }

      await consumeSSEStream(response, (chunk) => {
        outline.value += chunk
      }, controller.signal)

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
    stage.value = 'content'

    try {
      const response = await fetch('/api/article-generation/content', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          topic: topic.value.trim(),
          title: selectedTitle.value.trim(),
          outline: outline.value.trim(),
          platform: platform.value,
        }),
        signal: controller.signal,
      })

      if (!response.ok) {
        const body = await response.json() as { error?: string }
        throw new Error(body.error || '正文生成失败')
      }

      await consumeSSEStream(response, (chunk) => {
        content.value += chunk
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
    recommendationsController?.abort()
    const controller = new AbortController()
    recommendationsController = controller

    loadingRecommendations.value = true
    error.value = ''

    try {
      const response = await fetch('/api/article-generation/image-recommendations', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          content: content.value.trim(),
          outline: outline.value.trim() || undefined,
          platform: platform.value,
        }),
        signal: controller.signal,
      })

      const body = await response.json() as {
        success?: boolean
        data?: ImageRecommendation
        error?: string
      }

      if (!response.ok || !body.success || !body.data) {
        throw new Error(body.error || '配图推荐失败')
      }

      imageRecommendations.value = body.data
      imageSlots.value = body.data.placements.map((placement) => ({
        placement,
        mode: 'none' as const,
        searchResults: [],
        selectedImage: null,
        generating: false,
        searching: false,
      }))
    } catch (err: unknown) {
      if (err instanceof DOMException && err.name === 'AbortError') return
      error.value = err instanceof Error ? err.message : '配图推荐失败，请稍后重试'
    } finally {
      loadingRecommendations.value = false
      if (recommendationsController === controller) recommendationsController = null
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
      const response = await fetch('/api/article-generation/search-images', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          keywords: slot.placement.searchKeywords,
          count: 3,
        }),
        signal: controller.signal,
      })

      const body = await response.json() as {
        success?: boolean
        data?: { images: ImageSearchResult[] }
        error?: string
      }

      if (!response.ok || !body.success || !body.data?.images) {
        throw new Error(body.error || '搜图失败')
      }

      imageSlots.value = imageSlots.value.map((s, i) =>
        i === index ? { ...s, searchResults: body.data!.images } : s,
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
      const response = await fetch('/api/article-generation/generate-image', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          prompt: slot.placement.prompt,
          size: '1024x1024',
        }),
        signal: controller.signal,
      })

      const body = await response.json() as {
        success?: boolean
        data?: GeneratedImage
        error?: string
      }

      if (!response.ok || !body.success || !body.data) {
        throw new Error(body.error || '图片生成失败')
      }

      imageSlots.value = imageSlots.value.map((s, i) =>
        i === index ? { ...s, selectedImage: body.data! } : s,
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

  function reset(): void {
    titlesController?.abort()
    outlineController?.abort()
    contentController?.abort()
    clearImageControllers()

    titlesController = null
    outlineController = null
    contentController = null

    stage.value = 'topic'
    topic.value = ''
    platform.value = 'wechat'
    titles.value = []
    selectedTitle.value = ''
    outline.value = ''
    content.value = ''
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

  function cancel(): void {
    titlesController?.abort()
    outlineController?.abort()
    contentController?.abort()
    titlesLoading.value = false
    outlineLoading.value = false
    contentLoading.value = false
  }

  return {
    stage, topic, platform, titles, selectedTitle, outline, content,
    titlesLoading, outlineLoading, contentLoading, error,
    imageSlots, imageRecommendations, loadingRecommendations, completed,
    fetchTitles, streamOutline, streamContent,
    selectTitle, confirmOutline,
    goToTitles, goToOutline, goToContent,
    loadImageRecommendations, searchImageForSlot, generateImageForSlot,
    selectImageForSlot, clearImageForSlot,
    reset, cancel, setTopic, finish,
  }
}
