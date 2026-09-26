/**
 * useCloneReferenceTransfer.ts — C107-22：参考分析 → 视频克隆交接。
 *
 * 只通过稳定 ai_run 分析 id 交接；抖音/B 站解析产物是代理 URL（临时媒体，无永久
 * media_reference id），不得作为克隆参考直传——临时媒体须先经素材库固化（服务端
 * media kind 引用只接受 active 状态，TC107-22-02）。label 属展示字段，仅用于
 * 预填工程标题，不参与任何权限判定。
 */
import type { RouteLocationRaw } from 'vue-router'

export type CloneReferenceKind = 'media' | 'analysis'

export interface CloneReferenceInput {
  kind: CloneReferenceKind
  id: string | null
  label: string | null
}

export type CloneReferenceTransfer =
  | { ok: true; target: RouteLocationRaw }
  | { ok: false; reason: 'missing_id' | 'invalid_id' }

const ID_PATTERN = /^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$/

/** 展示标签上限与服务端标题上限一致（60 字）；超长截断，不报错。 */
function displayLabel(raw: string | null): string | undefined {
  const text = raw?.trim() ?? ''
  return text.length === 0 ? undefined : text.slice(0, 60)
}

export function resolveCloneReference(input: CloneReferenceInput): CloneReferenceTransfer {
  if (input.id === null || input.id.trim().length === 0) {
    return { ok: false, reason: 'missing_id' }
  }
  const id = input.id.trim()
  if (!ID_PATTERN.test(id)) {
    return { ok: false, reason: 'invalid_id' }
  }
  const query: Record<string, string> = { sourceKind: input.kind, sourceId: id }
  const label = displayLabel(input.label)
  if (label !== undefined) query.label = label
  return { ok: true, target: { name: 'video-clone', query } }
}

export function useCloneReferenceTransfer() {
  /**
   * 视频分析页交接入口：runId 是唯一稳定锚点（ai_run 真实 id）。
   * 无 runId（未分析/已过期）返回失败原因，调用方禁用按钮并如实提示。
   */
  function fromVideoAnalysis(runId: string | null, label: string | null): CloneReferenceTransfer {
    return resolveCloneReference({ kind: 'analysis', id: runId, label })
  }

  return { fromVideoAnalysis, resolveCloneReference }
}
