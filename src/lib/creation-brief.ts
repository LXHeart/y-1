import type { CreationEntry } from '../types/ai-creation'
import type { CreationBrief } from '../types/creation'

export function buildCreationBrief(
  entry: CreationEntry | null | undefined,
  platformId: string,
  contentFormId: string,
  topic: string,
  instructions: string,
): CreationBrief {
  return {
    ...entry?.brief,
    processingMode: entry?.brief?.processingMode || entry?.processingMode || 'create',
    contentSubtype: entry?.brief?.contentSubtype || entry?.contentSubtype || `${platformId}:${contentFormId}`,
    deliveryIntent: entry?.brief?.deliveryIntent || entry?.deliveryIntent
      || (contentFormId === 'video' || contentFormId === 'video-text' ? 'script-only' : 'text-only'),
    extraInstructions: instructions.trim() || entry?.brief?.extraInstructions || entry?.prefill?.instructions || undefined,
    objective: topic.trim() || entry?.brief?.objective || undefined,
  }
}

export function formatCreationAddress(raw: string | null | undefined): string {
  if (!raw) return ''
  try {
    const parsed = JSON.parse(raw) as { province?: string; city?: string; district?: string; address?: string }
    return [parsed.province, parsed.city, parsed.district, parsed.address].filter(Boolean).join(' ')
  } catch {
    return raw
  }
}
