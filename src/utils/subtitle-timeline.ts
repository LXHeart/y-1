/**
 * 字幕时间轴工具（任务书 #43 Stage 3，D5）：纯前端启发式断句分轴 + SRT/VTT 导出格式化。
 * 从 VideoStudioView 抽出的纯函数，供组件与单测共用。
 */
import type { SubtitleCue } from '../types/grassland/ai-studio'

/** 按 。！？； 切句；句超 24 字再按 ，、 切；目标每 cue ≤18 字。 */
export function splitSegments(text: string, maxCharsPerCue = 18): string[] {
  const sentences = text.split(/(?<=[。！？；])/g).map(s => s.trim()).filter(Boolean)
  const segments: string[] = []
  for (const sentence of sentences) {
    if (sentence.length > 24) {
      segments.push(...sentence.split(/(?<=[，、])/g).map(p => p.trim()).filter(Boolean))
    } else {
      segments.push(sentence)
    }
  }
  const chopped: string[] = []
  for (const segment of segments) {
    if (segment.length > maxCharsPerCue) {
      for (let i = 0; i < segment.length; i += maxCharsPerCue) {
        chopped.push(segment.slice(i, i + maxCharsPerCue))
      }
    } else {
      chopped.push(segment)
    }
  }
  return chopped
}

/**
 * 自动分轴：各 cue 按字数占比 × 总时长分配时间；预估不足 0.8s 的段并入下一 cue
 * （文本不丢弃）；首 cue 从 0 起，尾 cue end = 总时长。
 * UI 须显式标注「建议时间轴，请播放校准」（D5：比例分轴是可校准初稿，不是精确轴）。
 */
export function autoSplitSubtitles(text: string, durationMs: number): SubtitleCue[] {
  const MIN_CUE_DURATION = 0.8
  const chopped = splitSegments(text)
  if (chopped.length === 0 || durationMs <= 0) return []

  const totalChars = chopped.reduce((sum, s) => sum + s.length, 0)
  const totalSec = durationMs / 1000
  const merged: string[] = []
  let pending = ''
  for (let i = 0; i < chopped.length; i++) {
    pending = pending ? pending + chopped[i] : chopped[i]
    const estSec = (pending.length / totalChars) * totalSec
    if (estSec >= MIN_CUE_DURATION || i === chopped.length - 1) {
      if (i === chopped.length - 1 && merged.length > 0 && estSec < MIN_CUE_DURATION) {
        merged[merged.length - 1] += pending
      } else {
        merged.push(pending)
      }
      pending = ''
    }
  }

  const mergedChars = merged.reduce((sum, s) => sum + s.length, 0)
  const result: SubtitleCue[] = []
  let cursor = 0
  for (let i = 0; i < merged.length; i++) {
    const dur = (merged[i].length / mergedChars) * totalSec
    const start = cursor
    const end = i === merged.length - 1 ? totalSec : start + dur
    result.push({
      id: `cue-${i}`,
      start: Math.round(start * 10) / 10,
      end: Math.round(Math.min(end, totalSec) * 10) / 10,
      text: merged[i],
    })
    cursor = end
  }
  return result
}

export function formatSrtTime(seconds: number): string {
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  const s = Math.floor(seconds % 60)
  const ms = Math.round((seconds % 1) * 1000)
  return `${pad2(h)}:${pad2(m)}:${pad2(s)},${pad3(ms)}`
}

export function formatVttTime(seconds: number): string {
  return `${formatSrtTime(seconds).replace(',', '.')}`
}

export function buildSrt(cues: SubtitleCue[]): string {
  return cues.map((cue, index) =>
    `${index + 1}\n${formatSrtTime(cue.start)} --> ${formatSrtTime(cue.end)}\n${cue.text}\n`,
  ).join('\n')
}

export function buildVtt(cues: SubtitleCue[]): string {
  return `WEBVTT\n\n${cues.map((cue, index) =>
    `${index + 1}\n${formatVttTime(cue.start)} --> ${formatVttTime(cue.end)}\n${cue.text}\n`,
  ).join('\n')}`
}

function pad2(n: number): string { return n.toString().padStart(2, '0') }
function pad3(n: number): string { return n.toString().padStart(3, '0') }
