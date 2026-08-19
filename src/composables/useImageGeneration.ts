import { request } from './grassland-http'

export interface GeneratedImageResult {
  imageUrl: string
  revisedPrompt?: string
}

export interface GenerateImageInput {
  prompt: string
  /** D7 白名单尺寸：1024x1024 / 1024x1792 / 1792x1024。 */
  size: string
  /** 参考图走 multipart（浏览器自动带 boundary，不得手工覆写 Content-Type）。 */
  images?: File[]
  /** 附加 JSON 字段（任务上下文等），仅无参考图时生效。 */
  json?: Record<string, unknown>
  signal?: AbortSignal
}

/**
 * 统一生图入口：/api/article-generation/generate-image。
 * 原本三处调用（图片生成页 multipart / 文章配图 JSON+AbortSignal / AI 中心视频封面 JSON）各自手写
 * fetch 与信封解析，2026-08-20 收敛到共享 client；失败抛 Error（message 优先后端 error）。
 */
export async function generateImage(input: GenerateImageInput): Promise<GeneratedImageResult> {
  if (input.images?.length) {
    const form = new FormData()
    form.append('prompt', input.prompt)
    form.append('size', input.size)
    for (const file of input.images) {
      form.append('images', file)
    }
    return request<GeneratedImageResult>('/api/article-generation/generate-image', {
      method: 'POST',
      body: form,
      signal: input.signal,
    }, { fallbackError: '图片生成失败' })
  }
  return request<GeneratedImageResult>('/api/article-generation/generate-image', {
    method: 'POST',
    body: JSON.stringify({
      prompt: input.prompt,
      size: input.size,
      ...input.json,
    }),
    signal: input.signal,
  }, { fallbackError: '图片生成失败' })
}
