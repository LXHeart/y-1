import { describe, expect, test } from 'vitest'
import { extractZhihuQuestionRef } from './zhihu-question'

describe('extractZhihuQuestionRef（任务书 #62 §3.7 本地提取）', () => {
  test('从问题链接提取 questionId', () => {
    expect(extractZhihuQuestionRef('https://www.zhihu.com/question/1999041081275355787'))
      .toBe('1999041081275355787')
  })

  test('带 /answer/ 路径的链接仍只取 questionId', () => {
    expect(extractZhihuQuestionRef('https://www.zhihu.com/question/123456/answer/98765'))
      .toBe('123456')
  })

  test('移动端 host 与 http 协议同样可提取', () => {
    expect(extractZhihuQuestionRef('http://zhuanlan.zhihu.com/question/222')).toBe('222')
  })

  test('纯问题文本不提取', () => {
    expect(extractZhihuQuestionRef('为什么大厂都在弃用 Kubernetes？')).toBe('')
  })

  test('非知乎链接不提取', () => {
    expect(extractZhihuQuestionRef('https://www.example.com/question/123')).toBe('')
  })

  test('空输入安全返回空串', () => {
    expect(extractZhihuQuestionRef('')).toBe('')
    expect(extractZhihuQuestionRef(undefined as unknown as string)).toBe('')
  })
})
