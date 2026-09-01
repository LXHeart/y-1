// @vitest-environment happy-dom
import { flushPromises } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useArticleCreation } from './useArticleCreation'

/**
 * 任务书 #63 卡5：检查步 composable 行为。
 * 三路自动复查（报告空 / 已编辑 / 未编辑跳过）、软确认两分支、修复应用回写与复查触发。
 */

afterEach(() => {
  vi.unstubAllGlobals()
})

const emptyReport = { findings: [], lexiconVersion: 'lexicon-v1', deepCheck: false }

function sseResponse(chunks: string[], withSafety = false): Response {
  let body = chunks.map((c) => `data: ${JSON.stringify({ content: c })}\n`).join('')
  if (withSafety) {
    body += `data: ${JSON.stringify({ type: 'safety', safety: emptyReport })}\n`
  }
  body += 'data: [DONE]\n'
  return new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream' } })
}

/** titles 走共享 request，要发成功信封。 */
function titlesResponse(): Response {
  return new Response(JSON.stringify({ success: true, data: { titles: [{ title: 'T', hook: '' }] } }), {
    status: 200, headers: { 'Content-Type': 'application/json' },
  })
}

/** 复查端点响应（success 信封 + data.safety）。 */
function checkResponse(safety: unknown): Response {
  return new Response(JSON.stringify({ success: true, data: { safety } }), {
    status: 200, headers: { 'Content-Type': 'application/json' },
  })
}

function stubFetch(handler: (url: string) => Response) {
  const calls: { url: string; body: Record<string, unknown> }[] = []
  const fetchMock = vi.fn(async (input: unknown, init: RequestInit = {}) => {
    const url = String(input)
    calls.push({ url, body: init.body ? JSON.parse(String(init.body)) : {} })
    return handler(url)
  })
  vi.stubGlobal('fetch', fetchMock)
  return { calls, fetchMock }
}

/** 走完主题→标题→大纲→正文，落在检查步（streamContent 成功会自动 enterCheck）。 */
async function generateToCheck(a: ReturnType<typeof useArticleCreation>) {
  a.topic.value = '探店主题'
  await a.fetchTitles()
  a.selectTitle('某个标题')
  await a.streamOutline()
  a.outline.value = '大纲内容'
  await a.streamContent()
}

describe('useArticleCreation 检查步（任务书 #63 卡5）', () => {
  it('报告空（生成流无 safety 帧）→ 进入检查步自动复查，请求带 platform/contentForm', async () => {
    const { calls } = stubFetch((url) => {
      if (url.includes('/titles')) return titlesResponse()
      if (url === '/api/content-safety/check') return checkResponse(emptyReport)
      return sseResponse(['正文片段'])
    })
    const a = useArticleCreation()
    a.platform.value = 'zhihu'

    await generateToCheck(a)
    await flushPromises()

    expect(a.stage.value).toBe('check')
    const checkCalls = calls.filter((call) => call.url === '/api/content-safety/check')
    expect(checkCalls).toHaveLength(1)
    expect(checkCalls[0].body).toEqual({ text: '正文片段', platform: 'zhihu', contentForm: 'article' })
    expect(a.lastCheckedText.value).toBe('正文片段')
  })

  it('非知乎平台复查不带 contentForm；未编辑时再次进入检查步不重复复查', async () => {
    const { calls } = stubFetch((url) => {
      if (url.includes('/titles')) return titlesResponse()
      if (url === '/api/content-safety/check') return checkResponse(emptyReport)
      return sseResponse(['微信正文', '第二段'], true)
    })
    const a = useArticleCreation()

    await generateToCheck(a)
    await flushPromises()

    expect(a.stage.value).toBe('check')
    // 生成流已带 safety 帧 → 快照已同步，进入检查步零复查请求
    expect(calls.filter((call) => call.url === '/api/content-safety/check')).toHaveLength(0)
    expect(a.lastCheckedText.value).toBe('微信正文第二段')

    // 编辑后再次进入 → 复查，且不带 contentForm（非知乎）
    a.content.value = '微信正文第二段，加了一句。'
    a.enterCheck()
    await flushPromises()
    expect(calls.filter((call) => call.url === '/api/content-safety/check')).toHaveLength(1)
    const checkCall = calls.find((call) => call.url === '/api/content-safety/check')!
    expect(checkCall.body).toEqual({ text: '微信正文第二段，加了一句。', platform: 'wechat' })
  })

  it('applySafetyFix 回写正文与快照并触发复查', async () => {
    const { calls } = stubFetch((url) => {
      if (url.includes('/titles')) return titlesResponse()
      if (url === '/api/content-safety/check') return checkResponse(emptyReport)
      return sseResponse(['原始正文'], true)
    })
    const a = useArticleCreation()

    await generateToCheck(a)
    await flushPromises()
    expect(calls.filter((call) => call.url === '/api/content-safety/check')).toHaveLength(0)

    a.applySafetyFix('修复后的正文')

    expect(a.content.value).toBe('修复后的正文')
    expect(a.lastCheckedText.value).toBe('修复后的正文')
    await flushPromises()
    expect(calls.filter((call) => call.url === '/api/content-safety/check')).toHaveLength(1)
    expect(calls.find((call) => call.url === '/api/content-safety/check')!.body.text).toBe('修复后的正文')
  })

  describe('proceedFromCheck 软确认（P4）', () => {
    async function prepareWithFindings(count: number) {
      const report = {
        findings: Array.from({ length: count }, (_, i) => ({
          category: 'diversion', severity: 'low', match: `命中${i}`, index: i, advice: '删除', deep: false,
        })),
        lexiconVersion: 'lexicon-v1', deepCheck: false,
      }
      stubFetch((url) => {
        if (url.includes('/titles')) return titlesResponse()
        if (url === '/api/content-safety/check') return checkResponse(report)
        return sseResponse(['正文'])
      })
      const a = useArticleCreation()
      await generateToCheck(a)
      await flushPromises()
      return a
    }

    it('有提醒 + confirm 拒绝 → 留在检查步', async () => {
      const a = await prepareWithFindings(2)
      const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false)
      a.proceedFromCheck()
      expect(a.stage.value).toBe('check')
      expect(confirmSpy).toHaveBeenCalledWith('仍有 2 项内容提醒,发布前建议先处理。仍要继续配图?')
      confirmSpy.mockRestore()
    })

    it('有提醒 + confirm 放行 → 进配图步', async () => {
      const a = await prepareWithFindings(1)
      const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)
      a.proceedFromCheck()
      expect(a.stage.value).toBe('images')
      confirmSpy.mockRestore()
    })

    it('noteMode（跳配图）软确认文案为「完成」，放行后收尾完成', async () => {
      const a = await prepareWithFindings(1)
      a.imagesStageSkipped.value = true
      const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)
      a.proceedFromCheck()
      expect(confirmSpy).toHaveBeenCalledWith('仍有 1 项内容提醒,发布前建议先处理。仍要继续完成?')
      expect(a.completed.value).toBe(true)
      confirmSpy.mockRestore()
    })

    it('无提醒 → 不弹 confirm 直接放行', async () => {
      const a = await prepareWithFindings(0)
      const confirmSpy = vi.spyOn(window, 'confirm')
      a.proceedFromCheck()
      expect(confirmSpy).not.toHaveBeenCalled()
      expect(a.stage.value).toBe('images')
    })
  })
})
