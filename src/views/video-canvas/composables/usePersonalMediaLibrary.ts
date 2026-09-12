import { fetchApi } from '../../../composables/grassland-http'

/**
 * 任务书 #100 C100-20（C100-10 补缺）：画布个人媒体库统一取数。
 *
 * 素材轨与制作来源表单共用既有 /api/content-assets?libraryType=personal（#42 内容资产
 * 链路）——此前素材轨误连不存在的 /api/media/media（MediaController 无列表端点，
 * /{id} 把 "media" 当 id 吞掉 → 404 常态化）。mediaId 是 media_reference 句柄：
 * 画布 media 节点 refId（§6.3 media:{uuid}）与 own-media 制作来源键（§6.5）都用它，
 * 不是 content_asset 行 id。
 */
export interface PersonalMediaAsset {
  /** media_reference id（画布引用与 own-media 来源的键）。 */
  mediaId: string
  /** content_asset id（下载 URL 等内容资产端点用）。 */
  assetId: string
  title: string
  status: 'draft' | 'pending_review' | 'active' | 'rejected' | 'expired'
  mimeType: string | null
  validUntil: string | null
}

interface ContentAssetRow {
  id: string
  mediaId: string
  title: string
  status: PersonalMediaAsset['status']
  mimeType?: string | null
  validUntil?: string | null
}

/** 个人库素材（服务端已按归属过滤；失败抛错由调用方展示重试）。 */
export async function fetchPersonalMediaAssets(): Promise<PersonalMediaAsset[]> {
  const response = await fetchApi('/api/content-assets?libraryType=personal')
  if (!response.ok) throw new Error('素材读取失败')
  const body = await response.json() as { success: boolean; data?: { items?: ContentAssetRow[] } }
  return (body.data?.items ?? []).map(item => ({
    mediaId: item.mediaId,
    assetId: item.id,
    title: item.title,
    status: item.status,
    mimeType: item.mimeType ?? null,
    validUntil: item.validUntil ?? null,
  }))
}
