import { request, GrasslandHttpError } from '../composables/grassland-http'

/**
 * 图文交付导出（AI内容中心改造-02 §2.5 / T15、T31、T32）：
 * POST /api/creation-drafts/{id}/exports 按指定版本组装 manifest；媒体返回
 * 授权短期下载链接（过期重新请求，不触发生成）。导出错误不得触发重生成。
 */
export interface CreationExportDownload {
  refType?: string
  id?: string
  role?: string
  cardId?: string
  position?: number
  url?: string
  contentType?: string
  unavailable?: string
}

export interface CreationExportResult {
  draftId: string
  version: number
  expiresAt: string
  manifest: Record<string, unknown>
  downloads: CreationExportDownload[]
}

export async function exportCreationDraft(draftId: string, version?: number): Promise<CreationExportResult> {
  if (version !== undefined && (!Number.isSafeInteger(version) || version < 1)) throw new Error('导出需要明确的已保存版本')
  const result = await request<CreationExportResult>(`/api/creation-drafts/${encodeURIComponent(draftId)}/exports`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ version, format: 'bundle-manifest' }),
  })
  if (result.draftId !== draftId || (version !== undefined && result.version !== version))
    throw new GrasslandHttpError(409, '导出版本与已保存草稿不一致，请重试', 'CANVAS_VERSION_CONFLICT')
  return result
}

/** 把 manifest 落为本地文件（文件名取安全标题 + 短 ID，长度有上限）。 */
export function downloadExportManifest(result: CreationExportResult, safeTitle: string): void {
  const sanitized = (safeTitle || '创作交付').replace(/[\\/:*?"<>|\s]+/g, '-').slice(0, 40)
  const blob = new Blob([JSON.stringify(result.manifest, null, 2)], { type: 'application/json' })
  const link = document.createElement('a')
  link.download = `${sanitized}-v${result.version}-${result.draftId.slice(0, 8)}.json`
  link.href = URL.createObjectURL(blob)
  link.click()
  URL.revokeObjectURL(link.href)
}

/** 派生交付描述文案：正文过长时截断（发布描述独立编辑，不回写正文）。 */
export function deriveDescription(content: string, limit = 120): string {
  const text = content.replace(/#[^\s#，。！？.,!?]{1,30}/g, '').replace(/\s+/g, ' ').trim()
  return text.length <= limit ? text : `${text.slice(0, limit)}…`
}

// ---- 任务书 #101 C101-18：新格式真实文件导出（API101-19/20） ----

export type StudioExportFormat = 'markdown' | 'text' | 'wechat-html' | 'bundle-zip'

export interface StudioExportFile {
  exportId: string
  filename: string
  contentType: string
  sha256: string
  url: string
  sizeBytes: number
  expiresAt: string
}

export interface StudioExportResult {
  draftId: string
  version: number
  format: StudioExportFormat
  file: StudioExportFile
  missingItems: string[]
}

export interface StudioExportPending {
  exportId: string
  state: 'building' | 'failed'
  error: { code: string; message: string } | null
}

/** 发起新格式导出（requestId+version 必填；同键重试沿用调用方 requestId）。 */
export async function exportStudioDraft(draftId: string, version: number,
  format: StudioExportFormat, requestId: string,
  options: { theme?: 'standard' | 'compact'; includeTitle?: boolean; citeExternalLinks?: boolean } = {},
): Promise<StudioExportResult | StudioExportPending> {
  if (!Number.isSafeInteger(version) || version < 1) throw new Error('导出需要明确的已保存版本')
  return request<StudioExportResult | StudioExportPending>(
    `/api/creation-drafts/${encodeURIComponent(draftId)}/exports`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        requestId, version, format,
        theme: options.theme ?? 'standard',
        includeTitle: options.includeTitle ?? false,
        citeExternalLinks: options.citeExternalLinks ?? false,
      }),
    })
}

/** 读取导出（building 轮询/失败读回；url 为短时签名，过期重读恢复）。 */
export async function readStudioExport(exportId: string): Promise<StudioExportResult | StudioExportPending> {
  return request<StudioExportResult | StudioExportPending>(
    `/api/creation-studio/exports/${encodeURIComponent(exportId)}`, { method: 'GET' })
}

/** 按实际 contentType 与文件名触发浏览器下载（真实文件，不是 manifest）。 */
export function downloadStudioFile(file: StudioExportFile): void {
  const link = document.createElement('a')
  link.download = file.filename
  link.href = file.url
  link.rel = 'noopener'
  document.body.appendChild(link)
  link.click()
  link.remove()
}
