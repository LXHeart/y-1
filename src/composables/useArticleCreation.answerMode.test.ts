import { afterEach, describe, expect, it, vi } from 'vitest'
import { useArticleCreation } from './useArticleCreation'

/**
 * 任务书 #62 卡5：知乎回答模式的 composable 状态与载荷。
 *
 * 红线：questionId 只做**本地正则提取**（§3.7 链接抓取实测判死），本套件的
 * fetch stub 只应看到 titles/outline/content 三个自家端点——出现任何 zhihu.com
 * 出站即为回归。
 */

afterEach(() => {
  vi.unstubAllGlobals()
})

/** titles 走共享 `request`，要发成功信封（`{success, data}`）。 */
function jsonResponse(data: unknown): Response {
  return new Response(JSON.stringify({ success: true, data }), {
    status: 200, headers: { 'Content-Type': 'application/json' },
  })
}

/** outline/content 是 SSE，用 data: 帧拼流。 */
function sseResponse(chunks: string[]): Response {
  const body = chunks.map((c) => `data: ${JSON.stringify({ content: c })}\n`).join('') + 'data: [DONE]\n'
  return new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream' } })
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

describe('useArticleCreation 回答模式（任务书 #62）', () => {
  it('默认是文章模式，问题两字段为空', () => {
    const a = useArticleCreation()
    expect(a.contentMode.value).toBe('article')
    expect(a.question.value).toBe('')
    expect(a.questionRef.value).toBe('')
    expect(a.isAnswerMode()).toBe(false)
  })

  describe('questionId 本地提取（零网络请求）', () => {
    it('标准问题链接提取数字 id', () => {
      const a = useArticleCreation()
      a.setQuestion('https://www.zhihu.com/question/1999041081275355787')
      expect(a.questionRef.value).toBe('1999041081275355787')
    })

    it('带 answer 路径的链接仍只取 questionId', () => {
      const a = useArticleCreation()
      a.setQuestion('https://www.zhihu.com/question/123456789/answer/987654321')
      expect(a.questionRef.value).toBe('123456789')
    })

    it('纯文本问题不提取，questionRef 保持空', () => {
      const a = useArticleCreation()
      a.setQuestion('为什么大厂都在弃用 Kubernetes？')
      expect(a.question.value).toBe('为什么大厂都在弃用 Kubernetes？')
      expect(a.questionRef.value).toBe('')
    })

    it('从链接改回纯文本时清空旧 id（不残留上一个问题的溯源）', () => {
      const a = useArticleCreation()
      a.setQuestion('zhihu.com/question/555')
      expect(a.questionRef.value).toBe('555')
      a.setQuestion('换成手打的问题原文，够长了')
      expect(a.questionRef.value).toBe('')
    })

    it('提取函数对非知乎链接与空输入返回空串', () => {
      const a = useArticleCreation()
      expect(a.extractQuestionRef('https://example.com/question/123')).toBe('')
      expect(a.extractQuestionRef('')).toBe('')
      expect(a.extractQuestionRef('zhihu.com/question/abc')).toBe('')
    })
  })

  describe('模式切换重置', () => {
    it('切到回答模式清空产物但保留 question，stage 落到问题步', () => {
      const a = useArticleCreation()
      a.platform.value = 'zhihu'
      a.setQuestion('zhihu.com/question/777')
      a.topic.value = '旧主题'
      a.titles.value = [{ title: '旧标题', hook: 'h' }]
      a.selectedTitle.value = '旧标题'
      a.outline.value = '旧大纲'
      a.content.value = '旧正文'

      a.setContentMode('answer')

      expect(a.contentMode.value).toBe('answer')
      expect(a.titles.value).toEqual([])
      expect(a.selectedTitle.value).toBe('')
      expect(a.outline.value).toBe('')
      expect(a.content.value).toBe('')
      expect(a.stage.value).toBe('question')
      // question / questionRef / topic 是跨模式复用的输入，不清
      expect(a.question.value).toBe('zhihu.com/question/777')
      expect(a.questionRef.value).toBe('777')
      expect(a.topic.value).toBe('旧主题')
    })

    it('切回文章模式同样清空产物，stage 回到主题步', () => {
      const a = useArticleCreation()
      a.setContentMode('answer')
      a.selectedTitle.value = '选定的开头段'
      a.content.value = '回答正文'

      a.setContentMode('article')

      expect(a.contentMode.value).toBe('article')
      expect(a.selectedTitle.value).toBe('')
      expect(a.content.value).toBe('')
      expect(a.stage.value).toBe('topic')
    })

    it('切到同一模式是空操作，不误清产物', () => {
      const a = useArticleCreation()
      a.setContentMode('answer')
      a.selectedTitle.value = '已选开头'
      a.setContentMode('answer')
      expect(a.selectedTitle.value).toBe('已选开头')
    })

    it('isAnswerMode 需要 mode+question 同时成立（全局约束 2）', () => {
      const a = useArticleCreation()
      a.setContentMode('answer')
      expect(a.isAnswerMode()).toBe(false)
      a.setQuestion('  ')
      expect(a.isAnswerMode()).toBe(false)
      a.setQuestion('这是一个足够长的目标问题')
      expect(a.isAnswerMode()).toBe(true)
    })
  })

  describe('载荷组装', () => {
    it('回答模式 titles 带 answerMode+question，topic 作补充说明照带', async () => {
      const { calls } = stubFetch(() => jsonResponse({ titles: [{ title: '开头段文本', hook: 'h' }] }))
      const a = useArticleCreation()
      a.platform.value = 'zhihu'
      a.setContentMode('answer')
      a.setQuestion('https://www.zhihu.com/question/1999041081275355787')
      a.topic.value = '补充背景'

      await a.fetchTitles()

      expect(calls).toHaveLength(1)
      expect(calls[0].url).toContain('/api/article-generation/titles')
      expect(calls[0].body.answerMode).toBe(true)
      expect(calls[0].body.question).toBe('https://www.zhihu.com/question/1999041081275355787')
      expect(calls[0].body.topic).toBe('补充背景')
      expect(calls[0].body.platform).toBe('zhihu')
      expect(a.stage.value).toBe('titles')
      // 全程零 zhihu.com 出站
      expect(calls.some((c) => c.url.includes('zhihu.com'))).toBe(false)
    })

    it('回答模式问题不足 8 字直接报错，不发请求', async () => {
      const { calls } = stubFetch(() => jsonResponse({ titles: [] }))
      const a = useArticleCreation()
      a.setContentMode('answer')
      a.setQuestion('太短')

      await a.fetchTitles()

      expect(calls).toHaveLength(0)
      expect(a.error.value).toContain('目标问题')
    })

    it('回答模式 topic 可为空（问题即输入），仍能发起 titles', async () => {
      const { calls } = stubFetch(() => jsonResponse({ titles: [{ title: '开头', hook: 'h' }] }))
      const a = useArticleCreation()
      a.setContentMode('answer')
      a.setQuestion('一个足够长度的目标问题原文')

      await a.fetchTitles()

      expect(calls).toHaveLength(1)
      expect(a.error.value).toBe('')
    })

    it('outline/content 携带 answerMode+question，选定开头走 title 字段', async () => {
      const { calls } = stubFetch(() => sseResponse(['片段']))
      const a = useArticleCreation()
      a.platform.value = 'zhihu'
      a.setContentMode('answer')
      a.setQuestion('为什么这件事值得展开讲讲呢')
      a.selectTitle('我做这行 8 年，先说结论：这事儿没那么玄。')

      await a.streamOutline()
      a.outline.value = '一、结论层'
      await a.streamContent()

      const outlineCall = calls.find((c) => c.url.includes('/outline'))!
      expect(outlineCall.body.answerMode).toBe(true)
      expect(outlineCall.body.question).toBe('为什么这件事值得展开讲讲呢')
      expect(outlineCall.body.title).toBe('我做这行 8 年，先说结论：这事儿没那么玄。')

      const contentCall = calls.find((c) => c.url.includes('/content'))!
      expect(contentCall.body.answerMode).toBe(true)
      expect(contentCall.body.question).toBe('为什么这件事值得展开讲讲呢')
      expect(contentCall.body.outline).toBe('一、结论层')
    })

    it('文章模式载荷不含新字段（后端=现状，零回归红线）', async () => {
      const { calls } = stubFetch((url) => (url.includes('/titles')
        ? jsonResponse({ titles: [{ title: 'T', hook: 'h' }] })
        : sseResponse(['片段'])))
      const a = useArticleCreation()
      a.platform.value = 'zhihu'
      a.topic.value = '知乎文章主题'
      await a.fetchTitles()
      a.selectTitle('某个标题')
      await a.streamOutline()
      a.outline.value = '大纲'
      await a.streamContent()

      for (const call of calls) {
        expect(call.body).not.toHaveProperty('answerMode')
        expect(call.body).not.toHaveProperty('question')
      }
    })

    it('mode=answer 但问题为空时不发新字段（判据之外按现状处理）', async () => {
      const { calls } = stubFetch(() => sseResponse(['片段']))
      const a = useArticleCreation()
      a.setContentMode('answer')
      a.selectTitle('某个开头')

      await a.streamOutline()

      expect(calls[0].body).not.toHaveProperty('answerMode')
      expect(calls[0].body).not.toHaveProperty('question')
    })
  })

  describe('草稿往返', () => {
    it('draftFields 回填三列，问题空串归一为 null', () => {
      const a = useArticleCreation()
      expect(a.draftFields()).toEqual({
        contentMode: 'article', questionText: null, questionRef: null,
      })

      a.setContentMode('answer')
      a.setQuestion('  https://www.zhihu.com/question/424242  ')
      expect(a.draftFields()).toEqual({
        contentMode: 'answer',
        questionText: 'https://www.zhihu.com/question/424242',
        questionRef: '424242',
      })
    })

    it('applyDraft 还原模式与问题', () => {
      const a = useArticleCreation()
      a.applyDraft({ contentMode: 'answer', questionText: '目标问题原文', questionRef: '9527' })
      expect(a.contentMode.value).toBe('answer')
      expect(a.question.value).toBe('目标问题原文')
      expect(a.questionRef.value).toBe('9527')
    })

    it('applyDraft 对缺列老草稿回落 article 且不炸', () => {
      const a = useArticleCreation()
      a.setContentMode('answer')
      a.setQuestion('先前的问题原文')

      a.applyDraft({})

      expect(a.contentMode.value).toBe('article')
      expect(a.question.value).toBe('')
      expect(a.questionRef.value).toBe('')
    })

    it('applyDraft 缺 questionRef 时按问题原文本地重算（老草稿补溯源）', () => {
      const a = useArticleCreation()
      a.applyDraft({ contentMode: 'answer', questionText: 'https://www.zhihu.com/question/31337' })
      expect(a.questionRef.value).toBe('31337')
    })

    it('draftFields → applyDraft 往返无损', () => {
      const source = useArticleCreation()
      source.setContentMode('answer')
      source.setQuestion('https://www.zhihu.com/question/1999041081275355787/answer/1')
      const saved = source.draftFields()

      const restored = useArticleCreation()
      restored.applyDraft({
        contentMode: saved.contentMode,
        questionText: saved.questionText ?? undefined,
        questionRef: saved.questionRef ?? undefined,
      })

      expect(restored.contentMode.value).toBe('answer')
      expect(restored.question.value).toBe(source.question.value)
      expect(restored.questionRef.value).toBe('1999041081275355787')
    })
  })

  describe('reset', () => {
    it('默认重置清回文章模式并清空问题', () => {
      const a = useArticleCreation()
      a.setContentMode('answer')
      a.setQuestion('zhihu.com/question/606')

      a.reset()

      expect(a.contentMode.value).toBe('article')
      expect(a.question.value).toBe('')
      expect(a.questionRef.value).toBe('')
      expect(a.stage.value).toBe('topic')
    })

    it('keepPlatform 重置保留回答模式与问题，stage 回问题步', () => {
      const a = useArticleCreation()
      a.platform.value = 'zhihu'
      a.setContentMode('answer')
      a.setQuestion('任务锁定的目标问题原文')
      a.content.value = '上一稿正文'

      a.reset({ keepPlatform: true })

      expect(a.platform.value).toBe('zhihu')
      expect(a.contentMode.value).toBe('answer')
      expect(a.question.value).toBe('任务锁定的目标问题原文')
      expect(a.stage.value).toBe('question')
      expect(a.content.value).toBe('')
    })
  })
})
