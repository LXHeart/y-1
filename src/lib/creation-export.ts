import { fetchApi } from '../composables/grassland-http'

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
  const response = await fetchApi(`/api/creation-drafts/${encodeURIComponent(draftId)}/exports`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ version, format: 'bundle-manifest' }),
  })
  const parsed = await response.json() as { success?: boolean; error?: string; data?: CreationExportResult }
  if (!response.ok || !parsed.success || !parsed.data) {
    throw new Error(parsed.error || `导出失败（${response.status}）`)
  }
  return parsed.data
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
