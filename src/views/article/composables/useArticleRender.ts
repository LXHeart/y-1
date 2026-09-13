import { onScopeDispose, ref } from 'vue'
import type { RenderPreview } from '../../../types/creation-studio'

/**
 * 任务书 #101 C101-17（§6.7）：排版预览客户端（API101-18）。
 *
 * 渲染前 flush 由编排层完成（本 composable 只带版本请求）；按当前草稿版本请求、
 * 迟到响应用 epoch 丢弃（切版本/切主题连点/账号切换）；主题与开关只影响本次输出，
 * 不改正文与草稿版本；纯计算端点零 AI 调用。
 */
export function useArticleRender(options: {
  draftId: () => string | null
  draftVersion: () => number
}) {
  const preview = ref<RenderPreview | null>(null)
  const rendering = ref(false)
  const error = ref('')
  /** 当前预览的请求参数（主题/开关——重放对比用）。 */
  const lastRequest = ref<{ theme: string; includeTitle: boolean; citeExternalLinks: boolean } | null>(null)

  let epoch = 0

  onScopeDispose(() => { epoch += 1 })

  async function render(input: {
    theme: 'standard' | 'compact'
    includeTitle?: boolean
    citeExternalLinks?: boolean
  }): Promise<RenderPreview | null> {
    const draftId = options.draftId()
    if (!draftId) return null
    // 新请求作废在途请求（epoch 前移）；rendering 只反映「最新请求」是否完成。
    const requestEpoch = ++epoch
    rendering.value = true
    error.value = ''
    try {
      const response = await fetch('/api/creation-studio/render-previews', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          draftId,
          version: options.draftVersion(),
          theme: input.theme,
          includeTitle: input.includeTitle ?? false,
          citeExternalLinks: input.citeExternalLinks ?? false,
        }),
      })
      const body = await response.json().catch(() => null) as
        { success?: boolean; data?: RenderPreview; error?: string } | null
      if (requestEpoch !== epoch) return null
      if (!response.ok || !body?.success || !body.data) {
        error.value = body?.error || '排版预览失败，请重试'
        return null
      }
      preview.value = body.data
      lastRequest.value = {
        theme: input.theme,
        includeTitle: input.includeTitle ?? false,
        citeExternalLinks: input.citeExternalLinks ?? false,
      }
      return body.data
    } finally {
      if (requestEpoch === epoch) rendering.value = false
    }
  }

  /** 参数与上次一致时跳过（切主题/开关才算新请求）。 */
  function isSameRequest(input: { theme: string; includeTitle: boolean; citeExternalLinks: boolean }): boolean {
    const last = lastRequest.value
    return last != null && last.theme === input.theme && last.includeTitle === input.includeTitle
      && last.citeExternalLinks === input.citeExternalLinks && preview.value != null
  }

  function dismiss(): void {
    epoch += 1
    preview.value = null
    error.value = ''
    lastRequest.value = null
    rendering.value = false
  }

  return { preview, rendering, error, render, isSameRequest, dismiss }
}

export type ArticleRenderController = ReturnType<typeof useArticleRender>
