// @vitest-environment happy-dom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { parseSafetyFrame, recheckSafety } from './useContentSafety'

afterEach(() => vi.unstubAllGlobals())

const report = {
  findings: [{
    category: 'absolute_claims', severity: 'medium', match: '最好', index: 2,
    advice: '改为具体描述', deep: false,
  }],
  lexiconVersion: 'lexicon-v1',
  deepCheck: false,
  appliedOverlays: [],
}

describe('useContentSafety', () => {
  it('只从带 safety 块的帧提取规范化报告', () => {
    expect(parseSafetyFrame({ content: '普通内容帧' })).toBeNull()
    expect(parseSafetyFrame(null)).toBeNull()
    expect(parseSafetyFrame({ safety: report })).toEqual(report)
    expect(parseSafetyFrame({ safety: { findings: 'bad', deepCheck: 1 } })).toEqual({
      findings: [], lexiconVersion: '', deepCheck: false, appliedOverlays: [],
    })
  })

  it('手动复查携带登录态并解析统一响应信封', async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({
      success: true,
      data: { safety: report },
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(recheckSafety('这是修改后的文案')).resolves.toEqual(report)
    expect(fetchMock).toHaveBeenCalledWith('/api/content-safety/check', expect.objectContaining({
      method: 'POST',
      credentials: 'include',
      body: JSON.stringify({ text: '这是修改后的文案' }),
    }))
  })

  it('复查失败或响应缺少 safety 时返回 null', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('{}', { status: 200 })))
    await expect(recheckSafety('文案')).resolves.toBeNull()
  })
})
