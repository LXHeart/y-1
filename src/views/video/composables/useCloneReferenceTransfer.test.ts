// @vitest-environment happy-dom
// useCloneReferenceTransfer.test.ts — C107-22（TC107-22-02/04）：
// 交接只带稳定 ai_run id；无/非法 id 拒绝；label 只是展示预填不进权限路径。
import { describe, expect, test } from 'vitest'
import { resolveCloneReference, useCloneReferenceTransfer } from './useCloneReferenceTransfer'

describe('克隆参考交接（TC107-22-02 / TC107-22-04）', () => {
  test('分析 runId：交接带 sourceKind/sourceId 与截断后的展示 label', () => {
    const result = resolveCloneReference({
      kind: 'analysis',
      id: '9d2f6a5e-1234-4c01-9a01-00000000abc1',
      label: 'x'.repeat(80),
    })
    expect(result.ok).toBe(true)
    if (!result.ok) return
    expect(result.target).toEqual({
      name: 'video-clone',
      query: { sourceKind: 'analysis', sourceId: '9d2f6a5e-1234-4c01-9a01-00000000abc1', label: 'x'.repeat(60) },
    })
  })

  test('无 runId（未分析/已过期）：拒绝并给 missing_id，调用方禁用按钮', () => {
    const missing = resolveCloneReference({ kind: 'analysis', id: null, label: '标题' })
    expect(missing).toEqual({ ok: false, reason: 'missing_id' })
    const blank = resolveCloneReference({ kind: 'analysis', id: '   ', label: null })
    expect(blank).toEqual({ ok: false, reason: 'missing_id' })
  })

  test('非法 id 形状：拒绝（URL 拼接面不接收任意串）', () => {
    const result = resolveCloneReference({ kind: 'analysis', id: '../etc/passwd', label: null })
    expect(result).toEqual({ ok: false, reason: 'invalid_id' })
  })

  test('无权/他人分析不因前端放行：query 不含任何 owner 字段，归属判定只在服务端', () => {
    const { fromVideoAnalysis } = useCloneReferenceTransfer()
    const result = fromVideoAnalysis('44444444-4444-4c01-9a01-00000000abc2', '门头参考')
    expect(result.ok).toBe(true)
    if (!result.ok) return
    const query = (result.target as { query: Record<string, string> }).query
    expect(Object.keys(query).sort()).toEqual(['label', 'sourceId', 'sourceKind'])
    // TC107-22-02：不信任 query owner——服务端按会话账号核验 ai_run 归属。
    expect(query).not.toHaveProperty('owner')
    expect(query.sourceKind).toBe('analysis')
  })
})
