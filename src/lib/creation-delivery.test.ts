import { describe, expect, test } from 'vitest'
import { deliveryReadiness, deliveryReady, parseHashtagTopics, creationDeclarations } from './creation-delivery'

describe('交付 readiness（任务书2 §2.5 / 总方案 §7.4）', () => {
  test('小红书完整交付：标题/正文/话题/声明齐全才算 ready', () => {
    const delivery = { platform: 'xiaohongshu', contentForm: 'graphic',
      titleOrOpening: '标题', bodyOrDescription: '正文', topics: ['探店'],
      declarations: creationDeclarations({ aiGenerated: 'confirmed', commercial: 'not-applicable', original: 'confirmed' }) }
    const checks = deliveryReadiness(delivery, { platform: 'xiaohongshu' })
    expect(checks.every(check => check.state === 'ready')).toBe(true)
    expect(deliveryReady(checks)).toBe(true)
  })

  test('话题缺失或声明 pending 都算缺项', () => {
    const delivery = { platform: 'xiaohongshu', contentForm: 'graphic',
      titleOrOpening: '标题', bodyOrDescription: '正文',
      declarations: creationDeclarations() }
    const checks = deliveryReadiness(delivery, { platform: 'xiaohongshu' })
    expect(checks.find(check => check.key === 'topics')?.state).toBe('missing')
    expect(checks.find(check => check.key === 'declarations')?.state).toBe('missing')
    expect(deliveryReady(checks)).toBe(false)
  })

  test('公众号要求摘要；朋友圈/视频号要求分享配文；complete-content 检查选定媒体', () => {
    const wechat = deliveryReadiness({ platform: 'wechat-official', titleOrOpening: 't', bodyOrDescription: 'b', summary: '' },
      { platform: 'wechat-official' })
    expect(wechat.find(check => check.key === 'summary')?.state).toBe('missing')

    const moments = deliveryReadiness({ platform: 'moments', bodyOrDescription: '文案', shareCopy: '' },
      { platform: 'moments' })
    expect(moments.find(check => check.key === 'shareCopy')?.state).toBe('missing')

    const channels = deliveryReadiness({ platform: 'wechat-channels', bodyOrDescription: '描述', shareCopy: '转发文案' },
      { platform: 'wechat-channels' })
    expect(channels.find(check => check.key === 'shareCopy')?.state).toBe('ready')

    const withMedia = deliveryReadiness({ platform: 'xiaohongshu', titleOrOpening: 't', bodyOrDescription: 'b', topics: ['x'] },
      { platform: 'xiaohongshu', mediaExpected: true })
    expect(withMedia.find(check => check.key === 'media')?.state).toBe('missing')
  })
})

describe('话题解析（任务书2 §2.4：话题与正文分离存储）', () => {
  test('提取正文中的 #话题，去重且上限 10 个', () => {
    const content = '今天探店 #探店 很值 #美食 分享\n#同城 #美食'
    expect(parseHashtagTopics(content)).toEqual(['探店', '美食', '同城'])
    const many = Array.from({ length: 15 }, (_, i) => `#话题${i}`).join(' ')
    expect(parseHashtagTopics(many)).toHaveLength(10)
  })

  test('无话题返回空数组', () => {
    expect(parseHashtagTopics('没有标签的正文')).toEqual([])
  })
})
