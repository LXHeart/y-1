import { Buffer } from 'node:buffer'
import { env } from '../lib/env.js'
import { logger } from '../lib/logger.js'
import type { FeishuIntegration } from '../schemas/settings.js'

const FEISHU_BASE_URL = 'https://open.feishu.cn'
const FEISHU_API_TIMEOUT_MS = () => env.FEISHU_API_TIMEOUT_MS

interface FeishuTokenResponse {
  code: number
  msg: string
  tenant_access_token: string
  expire: number
}

interface FeishuDocCreateResponse {
  code: number
  msg: string
  data: {
    document: {
      document_id: string
      revision_id: number
      title: string
    }
  }
}

interface FeishuMediaUploadResponse {
  code: number
  msg: string
  data: {
    file_token: string
  }
}

interface FeishuAppendBlocksResponse {
  code: number
  msg: string
  data?: {
    children?: Array<{
      block_id: string
      block_type: number
    }>
  }
}

interface FeishuPatchBlockResponse {
  code: number
  msg: string
}

const FEISHU_FILENAME_PREFIX = 'image-export'
const FEISHU_IMAGE_UPLOAD_PERMISSION_CODE = 99991672
const FEISHU_IMAGE_UPLOAD_PERMISSION_MESSAGE = '飞书图片上传失败：当前飞书应用缺少图片上传权限，请在飞书开放平台为该应用开通 docs:document.media:upload 权限后重试'
const LATIN1_SUPPLEMENT_RE = /[À-ÿ]/u
const CJK_RE = /[㐀-鿿豈-﫿]/u

class FeishuImageUploadPermissionError extends Error {
  constructor() {
    super(FEISHU_IMAGE_UPLOAD_PERMISSION_MESSAGE)
    this.name = 'FeishuImageUploadPermissionError'
  }
}

function decodePossiblyMojibakeFileName(fileName: string): string {
  if (!fileName || CJK_RE.test(fileName) || !LATIN1_SUPPLEMENT_RE.test(fileName)) {
    return fileName
  }

  return Buffer.from(fileName, 'latin1').toString('utf8')
}

function getFileExtension(originalName: string, mimeType: string): string {
  if (mimeType === 'image/png') return 'png'
  if (mimeType === 'image/webp') return 'webp'
  if (mimeType === 'image/jpeg') return 'jpg'

  const normalizedName = decodePossiblyMojibakeFileName(originalName).trim()
  const matchedExtension = normalizedName.match(/\.([a-zA-Z0-9]+)$/u)?.[1]?.toLowerCase()
  if (matchedExtension) {
    return matchedExtension === 'jpeg' ? 'jpg' : matchedExtension
  }

  return 'jpg'
}

function buildFeishuUploadFileName(image: { mimeType: string; originalName: string }): string {
  return `${FEISHU_FILENAME_PREFIX}-${Date.now()}.${getFileExtension(image.originalName, image.mimeType)}`
}

interface FeishuBlockResponse {
  code: number
  msg: string
}

export interface ExportToFeishuResult {
  documentId: string
  documentUrl: string
}

export interface FeishuExportInput {
  feishu: FeishuIntegration
  review: string
  title?: string
  tags?: string[]
  images: Array<{
    buffer: Buffer
    mimeType: string
    originalName: string
  }>
  platform?: string
  reviewLength?: number
  feelings?: string
  runId?: string
}

async function feishuFetch<T>(path: string, options: RequestInit & { token?: string }): Promise<T> {
  const url = `${FEISHU_BASE_URL}${path}`
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), FEISHU_API_TIMEOUT_MS())

  try {
    const headers: Record<string, string> = {
      ...(options.headers as Record<string, string> ?? {}),
    }

    if (options.token) {
      headers['Authorization'] = `Bearer ${options.token}`
    }

    const response = await fetch(url, {
      ...options,
      headers,
      signal: controller.signal,
    })

    const body = await response.json() as T
    return body
  } finally {
    clearTimeout(timeout)
  }
}

function validateFeishuResponse<T extends { code: number; msg: string }>(
  response: T,
  operation: string,
): void {
  if (response.code === 0) {
    return
  }

  if (operation === '上传图片' && response.code === FEISHU_IMAGE_UPLOAD_PERMISSION_CODE) {
    throw new FeishuImageUploadPermissionError()
  }

  throw new Error(`飞书${operation}失败: ${response.msg} (code: ${response.code})`)
}

async function getTenantAccessToken(appId: string, appSecret: string): Promise<string> {
  const response = await feishuFetch<FeishuTokenResponse>('/open-apis/auth/v3/tenant_access_token/internal', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ app_id: appId, app_secret: appSecret }),
  })

  validateFeishuResponse(response, '获取访问凭证')
  return response.tenant_access_token
}

async function createDocument(token: string, title: string, folderToken?: string): Promise<string> {
  const body: Record<string, unknown> = { title }
  if (folderToken) {
    body.folder_token = folderToken
  }

  const response = await feishuFetch<FeishuDocCreateResponse>('/open-apis/docx/v1/documents', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    token,
    body: JSON.stringify(body),
  })

  validateFeishuResponse(response, '创建文档')
  return response.data.document.document_id
}

async function uploadImageMedia(
  token: string,
  image: { buffer: Buffer; mimeType: string; originalName: string },
  imageBlockId: string,
): Promise<string> {
  const uploadFileName = buildFeishuUploadFileName(image)
  const imageSize = image.buffer.length
  const formData = new FormData()
  formData.append('file_name', uploadFileName)
  formData.append('parent_type', 'docx_image')
  formData.append('parent_node', imageBlockId)
  formData.append('size', imageSize.toString())
  formData.append('file', new Blob([new Uint8Array(image.buffer)], { type: image.mimeType }), uploadFileName)

  const response = await feishuFetch<FeishuMediaUploadResponse>('/open-apis/drive/v1/medias/upload_all', {
    method: 'POST',
    token,
    body: formData,
  })

  validateFeishuResponse(response, '上传图片')
  return response.data.file_token
}

async function appendBlocks(
  token: string,
  documentId: string,
  blocks: Array<Record<string, unknown>>,
): Promise<FeishuAppendBlocksResponse['data']> {
  const response = await feishuFetch<FeishuAppendBlocksResponse>(
    `/open-apis/docx/v1/documents/${documentId}/blocks/${documentId}/children`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      token,
      body: JSON.stringify({ children: blocks }),
    },
  )

  validateFeishuResponse(response, '写入文档内容')
  return response.data
}

async function replaceImageBlock(token: string, documentId: string, imageBlockId: string, fileToken: string): Promise<void> {
  const response = await feishuFetch<FeishuPatchBlockResponse>(
    `/open-apis/docx/v1/documents/${documentId}/blocks/${imageBlockId}`,
    {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      token,
      body: JSON.stringify({ replace_image: { token: fileToken } }),
    },
  )

  validateFeishuResponse(response, '设置图片内容')
}

function textBlock(content: string, headingLevel?: number): Record<string, unknown> {
  const elements = [{ text_run: { content, text_element_style: {} } }]

  if (headingLevel === 3) {
    return { block_type: 5, heading3: { elements } }
  }

  if (headingLevel === 4) {
    return { block_type: 6, heading4: { elements } }
  }

  return { block_type: 2, text: { elements } }
}

function imageBlock(): Record<string, unknown> {
  return {
    block_type: 27,
    image: {},
  }
}

export async function exportToFeishu(input: FeishuExportInput): Promise<ExportToFeishuResult> {
  const { feishu, review, title, tags, images, platform, reviewLength, feelings, runId } = input

  if (!feishu.appId || !feishu.appSecret) {
    throw new Error('飞书应用凭证未配置，请在设置中填写 App ID 和 App Secret')
  }

  const token = await getTenantAccessToken(feishu.appId, feishu.appSecret)
  const docTitle = title || '图片评价导出'
  const documentId = await createDocument(token, docTitle, feishu.folderToken)

  const blocks: Array<Record<string, unknown>> = []

  // Metadata section
  const metaLines: string[] = []
  if (platform) metaLines.push(`平台: ${platform === 'taobao' ? '淘宝' : '大众点评'}`)
  if (reviewLength) metaLines.push(`字数: ${reviewLength}`)
  if (feelings) metaLines.push(`补充感受: ${feelings}`)
  if (runId) metaLines.push(`追踪 ID: ${runId}`)

  if (metaLines.length > 0) {
    blocks.push(textBlock('生成参数', 3))
    for (const line of metaLines) {
      blocks.push(textBlock(line))
    }
  }

  if (blocks.length > 0) {
    await appendBlocks(token, documentId, blocks)
    blocks.length = 0
  }

  // Images section
  if (images.length > 0) {
    await appendBlocks(token, documentId, [textBlock('上传图片', 3)])
    for (const image of images) {
      const displayFileName = decodePossiblyMojibakeFileName(image.originalName)
      try {
        const imageInsertData = await appendBlocks(token, documentId, [imageBlock()])
        const imageBlockId = imageInsertData?.children?.[0]?.block_id

        if (!imageBlockId) {
          throw new Error('飞书创建图片块失败: missing block id')
        }

        const fileToken = await uploadImageMedia(token, image, imageBlockId)
        await replaceImageBlock(token, documentId, imageBlockId, fileToken)
      } catch (error: unknown) {
        if (error instanceof FeishuImageUploadPermissionError) {
          throw error
        }

        logger.warn({ err: error, originalName: displayFileName }, 'Failed to upload image to Feishu, skipping')
        await appendBlocks(token, documentId, [textBlock('[图片上传失败]')])
      }
    }
  }

  // Review content
  blocks.push(textBlock('评价内容', 3))
  if (title) {
    blocks.push(textBlock(title, 4))
  }
  // Split review by newlines into separate text blocks
  const reviewLines = review.split('\n').filter((line) => line.trim())
  for (const line of reviewLines) {
    blocks.push(textBlock(line))
  }

  if (tags && tags.length > 0) {
    blocks.push(textBlock(`标签: ${tags.join('、')}`))
  }

  // Write all blocks in batches of 50
  const BATCH_SIZE = 50
  for (let i = 0; i < blocks.length; i += BATCH_SIZE) {
    const batch = blocks.slice(i, i + BATCH_SIZE)
    await appendBlocks(token, documentId, batch)
  }

  const documentUrl = `https://bytedance.feishu.cn/docx/${documentId}`

  return { documentId, documentUrl }
}
