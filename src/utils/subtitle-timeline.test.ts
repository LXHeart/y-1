import { describe, expect, test } from 'vitest'
import {
  autoSplitSubtitles,
  buildSrt,
  buildVtt,
  formatSrtTime,
  formatVttTime,
  splitSegments,
} from './subtitle-timeline'

describe('字幕断句分轴（任务书 #43 Stage 3）', () => {
  test('按句末标点切句，保留标点', () => {
    const cues = autoSplitSubtitles('你好。世界！今天天气不错；去吧。', 10_000)
    expect(cues.map(c => c.text)).toEqual(['你好。', '世界！', '今天天气不错；', '去吧。'])
  })

  test('句超 24 字按逗号顿号再切', () => {
    const long = '这是一段很长很长的句子，包含了好几个分句、而且全都挤在一起没有句号，需要二次切分。'
    const segments = splitSegments(long)
    expect(segments.length).toBeGreaterThan(1)
    expect(segments.join('')).toBe(long)
  })

  test('无标点长句按 18 字强制切', () => {
    const text = '一二三四五六七八九十一二三四五六七八九十一二三四五六'
    const segments = splitSegments(text)
    expect(segments.every(s => s.length <= 18)).toBe(true)
    expect(segments.join('')).toBe(text)
  })

  test('时间按字数占比分配，首 cue 从 0 起，尾 cue 收在总时长', () => {
    const cues = autoSplitSubtitles('一二。三四。', 10_000)
    expect(cues[0].start).toBe(0)
    expect(cues[cues.length - 1].end).toBe(10)
    // 等长文本均分
    expect(cues[0].end).toBeCloseTo(5, 1)
    expect(cues[1].start).toBeCloseTo(5, 1)
  })

  test('预估不足 0.8s 的段并入下一 cue，文本不丢失', () => {
    // 总时长 3s、9 个等长短句 → 每句仅 0.33s，必须合并到 ≥0.8s 且全文保留
    const text = '一二三。四五六。七八九。十一二。十三四。十五六。十七八。十九二。廿一二。'
    const cues = autoSplitSubtitles(text, 3_000)
    expect(cues.length).toBeGreaterThan(0)
    expect(cues.length).toBeLessThan(9)
    const joined = cues.map(c => c.text).join('')
    expect(joined).toBe(text)
    // 合并后的 cue 时长都应达到下限（除尾 cue 可能因四舍五入略小）
    for (const cue of cues.slice(0, -1)) {
      expect(cue.end - cue.start).toBeGreaterThanOrEqual(0.7)
    }
    expect(cues[cues.length - 1].end).toBe(3)
  })

  test('空文本与零时长返回空轴', () => {
    expect(autoSplitSubtitles('', 10_000)).toEqual([])
    expect(autoSplitSubtitles('你好。', 0)).toEqual([])
  })
})

describe('SRT / VTT 格式化（任务书 #43 Stage 3）', () => {
  test('SRT 时间戳逗号分隔毫秒补零到三位', () => {
    expect(formatSrtTime(0)).toBe('00:00:00,000')
    expect(formatSrtTime(1.5)).toBe('00:00:01,500')
    expect(formatSrtTime(61.256)).toBe('00:01:01,256')
    expect(formatSrtTime(3661)).toBe('01:01:01,000')
  })

  test('VTT 时间戳点号分隔且补头行', () => {
    expect(formatVttTime(1.5)).toBe('00:00:01.500')
    const vtt = buildVtt([{ id: 'cue-0', start: 0, end: 1.5, text: '你好' }])
    expect(vtt.startsWith('WEBVTT\n\n')).toBe(true)
    expect(vtt).toContain('1\n00:00:00.000 --> 00:00:01.500\n你好')
  })

  test('SRT 带序号、箭头与空行分隔', () => {
    const srt = buildSrt([
      { id: 'cue-0', start: 0, end: 1, text: '第一句' },
      { id: 'cue-1', start: 1, end: 2.5, text: '第二句' },
    ])
    expect(srt).toContain('1\n00:00:00,000 --> 00:00:01,000\n第一句\n')
    expect(srt).toContain('2\n00:00:01,000 --> 00:00:02,500\n第二句\n')
  })
})
