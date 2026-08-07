import { afterEach, describe, expect, it, vi } from 'vitest'
import { useCreationAssistant } from './useCreationAssistant'

afterEach(() => vi.unstubAllGlobals())

/** 构造一条 SSE 响应（`data: <json>\n\n` 逐帧 + `[DONE]` 收尾，与后端 Sse.stream 一致）。 */
function sse(frames: Array<Record<string, unknown>>): Response {
  return new Response(new ReadableStream({
    start(controller) {
      const lines = frames.flatMap((frame) => [`data: ${JSON.stringify(frame)}`, ''])
      lines.push('data: [DONE]', '')
      controller.enqueue(new TextEncoder().encode(lines.join('\n')))
      controller.close()
    },
  }), { headers: { 'Content-Type': 'text/event-stream' } })
}

function rawSse(chunks: string[]): Response {
  return new Response(new ReadableStream({
    start(controller) {
      for (const chunk of chunks) controller.enqueue(new TextEncoder().encode(chunk))
      controller.close()
    },
  }), { headers: { 'Content-Type': 'text/event-stream' } })
}

function stubFetch(response: Response | (() => Response)): ReturnType<typeof vi.fn> {
  const fn = vi.fn(async () => typeof response === 'function' ? response() : response)
  vi.stubGlobal('fetch', fn)
  return fn
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((next) => { resolve = next })
  return { promise, resolve }
}

describe('useCreationAssistant', () => {
  it('评分累积逐维度帧并读 overall', async () => {
    stubFetch(sse([
      { type: 'score', dimension: '标题吸引力', score: 8, advice: '标题可以更具体' },
      { type: 'score', dimension: '平台规范', score: 6, advice: '补充免责声明' },
      { type: 'overall', score: 7 },
    ]))
    const assistant = useCreationAssistant()
    await assistant.runScore('这是一段足够长的测试内容', 'xiaohongshu')

    expect(assistant.scoreError.value).toBe('')
    expect(assistant.score.value?.overall).toBe(7)
    expect(assistant.score.value?.dimensions).toHaveLength(2)
    expect(assistant.score.value?.dimensions[0]).toEqual({
      dimension: '标题吸引力', score: 8, advice: '标题可以更具体',
    })
  })

  it('评分流收到 DONE 但缺少 overall 收口帧时报告协议错误', async () => {
    stubFetch(sse([{ type: 'score', dimension: '标题吸引力', score: 8 }]))
    const assistant = useCreationAssistant()
    await assistant.runScore('这是一段足够长的测试内容')

    expect(assistant.score.value).toBeNull()
    expect(assistant.scoreError.value).toContain('overall')
  })

  it('评分流只有 overall 而没有维度帧时报告协议错误', async () => {
    stubFetch(sse([{ type: 'overall', score: 8 }]))
    const assistant = useCreationAssistant()
    await assistant.runScore('这是一段足够长的测试内容')

    expect(assistant.score.value).toBeNull()
    expect(assistant.scoreError.value).toContain('dimension')
  })

  it('重置评估后忽略旧评分请求的迟到结果', async () => {
    const response = deferred<Response>()
    vi.stubGlobal('fetch', vi.fn(async () => response.promise))
    const assistant = useCreationAssistant()
    const scoring = assistant.runScore('第一份草稿的正文')
    await Promise.resolve()

    assistant.resetAssessments()
    response.resolve(sse([
      { type: 'score', dimension: '标题吸引力', score: 8 },
      { type: 'overall', score: 8 },
    ]))
    await scoring

    expect(assistant.score.value).toBeNull()
    expect(assistant.scoreError.value).toBe('')
  })

  it('优化建议逐 chunk 拼接（帧只有 content 没有 type）', async () => {
    stubFetch(sse([{ content: '开头' }, { content: '可以更抓人' }]))
    const assistant = useCreationAssistant()
    await assistant.runSuggest('这是一段足够长的测试内容')

    expect(assistant.suggestion.value).toBe('开头可以更抓人')
    expect(assistant.suggestError.value).toBe('')
  })

  it('跨网络 chunk 的 JSON 帧和 DONE 标记仍能完整解析', async () => {
    stubFetch(rawSse([
      'data: {"content":"跨',
      '块内容"}\n\ndata: [DO',
      'NE]\n\n',
    ]))
    const assistant = useCreationAssistant()
    await assistant.runSuggest('这是一段足够长的测试内容')

    expect(assistant.suggestion.value).toBe('跨块内容')
    expect(assistant.suggestError.value).toBe('')
  })

  it('SSE 出现坏 JSON 帧时报告协议错误', async () => {
    stubFetch(rawSse(['data: {bad json}\n\ndata: [DONE]\n\n']))
    const assistant = useCreationAssistant()
    await assistant.runSuggest('这是一段足够长的测试内容')

    expect(assistant.suggestError.value).toContain('格式')
  })

  it('SSE 在 DONE 前截断时报告流中断', async () => {
    stubFetch(rawSse(['data: {"content":"只有半段"}\n\n']))
    const assistant = useCreationAssistant()
    await assistant.runSuggest('这是一段足够长的测试内容')

    expect(assistant.suggestion.value).toBe('只有半段')
    expect(assistant.suggestError.value).toContain('中断')
  })

  it('流内错误帧转成错误信息（后端已 200 开头只能发 error 帧）', async () => {
    stubFetch(sse([{ content: '前半段' }, { error: '优化建议生成失败' }]))
    const assistant = useCreationAssistant()
    await assistant.runSuggest('这是一段足够长的测试内容')

    expect(assistant.suggestError.value).toBe('优化建议生成失败')
    expect(assistant.suggesting.value).toBe(false)
  })

  it('引导 ask 帧进对话，第二轮带上历史', async () => {
    const fetchMock = stubFetch(() => sse([{ type: 'ask', question: '这篇给谁看？' }]))
    const assistant = useCreationAssistant()
    await assistant.sendGuideMessage('想写一篇咖啡店探店')

    expect(assistant.messages.value).toHaveLength(2)
    expect(assistant.messages.value[0]).toMatchObject({ role: 'user', text: '想写一篇咖啡店探店' })
    expect(assistant.messages.value[1]).toMatchObject({ role: 'assistant', text: '这篇给谁看？' })

    await assistant.sendGuideMessage('给上班族')
    const body = JSON.parse((fetchMock.mock.calls[1][1] as RequestInit).body as string)
    expect(body.history).toContain('用户：想写一篇咖啡店探店')
    expect(body.history).toContain('教练：这篇给谁看？')
  })

  it('引导 brief 帧把 inferredFields 拆成数组（§4.9.2 推测标记）', async () => {
    stubFetch(sse([{
      type: 'brief', angle: '性价比', audience: '上班族', structure: '总分总',
      inferredFields: 'audience,structure',
    }]))
    const assistant = useCreationAssistant()
    await assistant.sendGuideMessage('随便写点什么')

    expect(assistant.brief.value?.inferredFields).toEqual(['audience', 'structure'])
    expect(assistant.messages.value[1].brief?.angle).toBe('性价比')
  })

  it('引导流收到 DONE 但缺少 ask/brief 收口帧时报告协议错误', async () => {
    stubFetch(sse([{ content: '无关帧' }]))
    const assistant = useCreationAssistant()
    const result = await assistant.sendGuideMessage('想写探店')

    expect(result).toBeNull()
    expect(assistant.guideError.value).toContain('ask/brief')
  })

  it('任务覆盖：covered 是原生 boolean，false 不能被当成 truthy', async () => {
    stubFetch(sse([
      { type: 'gap', requirement: '必须出现门店名', status: 'missing', hint: '在开头点名' },
      { type: 'covered', covered: false },
    ]))
    const assistant = useCreationAssistant()
    await assistant.checkTaskCoverage('这是一段足够长的测试内容', '必须出现门店名')

    expect(assistant.coverage.value?.covered).toBe(false)
    expect(assistant.coverage.value?.gaps).toHaveLength(1)
    expect(assistant.coverage.value?.gaps[0].requirement).toBe('必须出现门店名')
  })

  it('任务覆盖：全覆盖时 covered=true 且无 gap', async () => {
    stubFetch(sse([{ type: 'covered', covered: true }]))
    const assistant = useCreationAssistant()
    await assistant.checkTaskCoverage('这是一段足够长的测试内容', '必须出现门店名')

    expect(assistant.coverage.value).toEqual({ gaps: [], covered: true })
  })

  it('任务覆盖流收到 DONE 但缺少 covered 收口帧时报告协议错误', async () => {
    stubFetch(sse([{ type: 'gap', requirement: '必须出现门店名' }]))
    const assistant = useCreationAssistant()
    await assistant.checkTaskCoverage('这是一段足够长的测试内容', '必须出现门店名')

    expect(assistant.coverage.value).toBeNull()
    expect(assistant.coverageError.value).toContain('covered')
  })

  it('热点选题把 entryPoints 按「；」拆成数组', async () => {
    stubFetch(sse([{
      type: 'topic', topic: '咖啡店的隐藏菜单', angle: '猎奇', thesis: '小店也有惊喜',
      audience: '年轻人', entryPoints: '第一次探店；老板推荐；隐藏喝法',
    }]))
    const assistant = useCreationAssistant()
    const result = await assistant.topicFromHot('咖啡热搜', 'xiaohongshu')

    expect(result?.topic).toBe('咖啡店的隐藏菜单')
    expect(result?.entryPoints).toEqual(['第一次探店', '老板推荐', '隐藏喝法'])
  })

  it('热点选题流收到 DONE 但缺少 topic 收口帧时报告协议错误', async () => {
    stubFetch(sse([{ type: 'other' }]))
    const assistant = useCreationAssistant()
    const result = await assistant.topicFromHot('咖啡热搜')

    expect(result).toBeNull()
    expect(assistant.topicError.value).toContain('topic')
  })

  it('非 2xx 读后端错误信息而不是通用文案', async () => {
    stubFetch(new Response(JSON.stringify({ success: false, error: '积分不足' }), {
      status: 402, headers: { 'Content-Type': 'application/json' },
    }))
    const assistant = useCreationAssistant()
    await assistant.runScore('这是一段足够长的测试内容')

    expect(assistant.scoreError.value).toBe('积分不足')
    expect(assistant.score.value).toBeNull()
  })

  it('非 JSON 的非 2xx 响应使用带状态码的安全文案', async () => {
    stubFetch(new Response('bad gateway', { status: 502 }))
    const assistant = useCreationAssistant()
    await assistant.runScore('这是一段足够长的测试内容')

    expect(assistant.scoreError.value).toBe('请求失败（502）')
  })

  it('没有响应体的成功响应报告不可读流', async () => {
    stubFetch(new Response(null, { status: 200 }))
    const assistant = useCreationAssistant()
    await assistant.runSuggest('这是一段足够长的测试内容')

    expect(assistant.suggestError.value).toBe('响应没有可读流')
  })

  it('非 Error 异常回退到能力级安全文案', async () => {
    const fetchMock = vi.fn(async () => { throw 'network down' })
    vi.stubGlobal('fetch', fetchMock)
    const assistant = useCreationAssistant()
    await assistant.runScore('这是一段足够长的测试内容')

    expect(assistant.scoreError.value).toBe('内容评分失败')
  })

  it('空输入不发请求', async () => {
    const fetchMock = stubFetch(sse([]))
    const assistant = useCreationAssistant()
    const result = await assistant.sendGuideMessage('   ')

    expect(result).toBeNull()
    expect(fetchMock).not.toHaveBeenCalled()
  })
})
