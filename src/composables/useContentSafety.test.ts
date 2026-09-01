// @vitest-environment happy-dom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { fixSafety, parseSafetyFrame, recheckSafety } from './useContentSafety'

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

  it('复查携带 platform/contentForm（任务书 #63：修「未知平台」根因）', async () => {
    const fetchMock = vi.fn(async (_url: string, _init?: RequestInit) => new Response(JSON.stringify({
      success: true, data: { safety: report },
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(recheckSafety('文案', 'zhihu', 'answer')).resolves.toEqual(report)
    expect(JSON.parse(String(fetchMock.mock.calls[0][1]?.body))).toEqual({
      text: '文案', platform: 'zhihu', contentForm: 'answer',
    })
    // 不传时请求体与现状一致（undefined 序列化时剔除）
    await recheckSafety('文案')
    expect(JSON.parse(String(fetchMock.mock.calls[1][1]?.body))).toEqual({ text: '文案' })
  })

  it('fixSafety 从 SSE result 帧取修复全文、忽略 progress', async () => {
    const fetchMock = vi.fn(async () => new Response(
      'data: {"type":"progress"}\n'
      + 'data: {"type":"result","text":"修复后的全文"}\n'
      + 'data: [DONE]\n',
      { status: 200, headers: { 'Content-Type': 'text/event-stream' } }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(fixSafety({
      text: '原文',
      findings: [{ category: 'diversion', match: '加微信', advice: '删除' }],
      platform: 'xiaohongshu',
    })).resolves.toBe('修复后的全文')
    expect(fetchMock).toHaveBeenCalledWith('/api/content-safety/fix', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({
        text: '原文',
        findings: [{ category: 'diversion', match: '加微信', advice: '删除' }],
        platform: 'xiaohongshu',
      }),
    }))
  })

  it('fixSafety 非 2xx 时抛出后端 error 文案（如修复模型未配置）', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(
      JSON.stringify({ success: false, error: '修复模型未配置,请在治理台为「内容修复」能力配置模型' }),
      { status: 503, headers: { 'Content-Type': 'application/json' } })))
    await expect(fixSafety({
      text: '原文',
      findings: [{ category: 'diversion', match: '加微信', advice: '删除' }],
    })).rejects.toThrow('修复模型未配置,请在治理台为「内容修复」能力配置模型')
  })
})
