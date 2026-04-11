const defaultDownloadFilename = 'bilibili-video.mp4'
const invalidFilenameCharsPattern = /[<>:"/\\|?*\x00-\x1F]+/g
const whitespacePattern = /\s+/g
const repeatedDashPattern = /-+/g

function sanitizeFilenamePart(value: string | undefined): string | undefined {
  if (!value) {
    return undefined
  }

  const normalized = value
    .replace(invalidFilenameCharsPattern, '-')
    .replace(whitespacePattern, '-')
    .replace(repeatedDashPattern, '-')
    .replace(/^-|-$/g, '')
    .trim()

  return normalized || undefined
}

function ensureMp4Extension(filename: string): string {
  return filename.toLowerCase().endsWith('.mp4') ? filename : `${filename}.mp4`
}

function truncateFilenameBase(base: string, maxLength = 80): string {
  if (base.length <= maxLength) {
    return base
  }

  return base.slice(0, maxLength).replace(/-+$/g, '').trim() || 'bilibili-video'
}

export function buildBilibiliDownloadFilename(input: {
  title?: string
  author?: string
  videoId?: string
}): string {
  const parts = [input.title, input.author, input.videoId]
    .map(sanitizeFilenamePart)
    .filter((value): value is string => Boolean(value))

  if (parts.length === 0) {
    return defaultDownloadFilename
  }

  return ensureMp4Extension(truncateFilenameBase(parts.join('-')))
}

export function normalizeBilibiliDownloadFilename(filename: string | undefined): string | undefined {
  const sanitizedFilename = sanitizeFilenamePart(filename)
  return sanitizedFilename ? ensureMp4Extension(sanitizedFilename) : undefined
}
