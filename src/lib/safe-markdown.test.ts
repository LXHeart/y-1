// @vitest-environment happy-dom
import { describe, expect, test } from 'vitest'
import { renderSafeMarkdown } from './safe-markdown'

describe('renderSafeMarkdown', () => {
  test('保留创作正文需要的 Markdown 结构', () => {
    const html = renderSafeMarkdown('# 标题\n\n**重点**\n\n![门店照片](https://cdn.example.com/store.jpg)')

    expect(html).toContain('<h1>标题</h1>')
    expect(html).toContain('<strong>重点</strong>')
    expect(html).toContain('src="https://cdn.example.com/store.jpg"')
  })

  test('移除图片属性注入、脚本标签和危险协议', () => {
    const html = renderSafeMarkdown([
      '![x" onerror="alert(1)](x)',
      '<script>alert(2)</script>',
      '[危险链接](javascript:alert(3))',
      '![危险图片](data:text/html;base64,PHNjcmlwdD4=)',
    ].join('\n\n'))

    expect(html).not.toMatch(/onerror|<script|javascript:|data:text\/html/i)
  })
})
