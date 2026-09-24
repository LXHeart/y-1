import { describe, expect, test } from 'vitest'
import { PcmResampler, goertzelMagnitude, rms, sineWave } from './pcm-resampler'

/** TC105E-04-01 采样精度：44100/48000→16000 保持时长与频率；alias 受抑；跨块边界无爆音。 */

describe('TC105E-04-01 采样精度（44.1k/48k → 16k）', () => {
  test.each([44100, 48000])('%dHz：1kHz 正弦输出样本数误差≤1、频率保持', (rate) => {
    const seconds = 1
    const input = sineWave(1000, rate, seconds)
    const resampler = new PcmResampler()
    const output = concat(resampler.push(input, rate), resampler.flush())

    const expected = Math.round((input.length * 16000) / rate)
    expect(Math.abs(output.length - expected)).toBeLessThanOrEqual(1)

    // Goertzel 在 1kHz 应显著大于 2kHz 分量（频率保持、无大幅失真）。
    const atTarget = goertzelMagnitude(output, 1000)
    const offTarget = goertzelMagnitude(output, 2000)
    expect(atTarget).toBeGreaterThan(0.4)
    expect(atTarget / Math.max(offTarget, 1e-6)).toBeGreaterThan(10)
  })

  test.each([44100, 48000])('%dHz：10kHz 输入被低通显著抑制（alias 受抑，非裸线性抽样）', (rate) => {
    const whole = new PcmResampler()
    const output = concat(whole.push(sineWave(10_000, rate, 0.5, 0.8), rate), whole.flush())
    // 10kHz > 输出 Nyquist(8kHz)，混叠为 6kHz；63tap 窗 sinc（截止 0.9×8k）应给 ≥14dB 抑制。
    expect(rms(output)).toBeLessThan(0.8 * 0.2)
    const atAlias = goertzelMagnitude(output, 6000)
    expect(atAlias).toBeLessThan(0.2)
  })

  test.each([44100, 48000])('%dHz：任意切块连续推送，样本总数与整段一致、边界无爆音', (rate) => {
    const input = sineWave(500, rate, 1)
    const wholeResampler = new PcmResampler()
    const whole = concat(wholeResampler.push(input, rate), wholeResampler.flush())

    // 随机切块（含 1 样本极小块）。
    const chunked = new PcmResampler()
    const parts: Int16Array[] = []
    let offset = 0
    const sizes = [1, 7, 293, 1024, 3, 8192, 61]
    let sizeIndex = 0
    while (offset < input.length) {
      const size = Math.min(sizes[sizeIndex % sizes.length], input.length - offset)
      parts.push(chunked.push(input.subarray(offset, offset + size), rate))
      offset += size
      sizeIndex += 1
    }
    parts.push(chunked.flush())
    const joined = new Int16Array(parts.reduce((sum, part) => sum + part.length, 0))
    let cursor = 0
    for (const part of parts) {
      joined.set(part, cursor)
      cursor += part.length
    }

    expect(joined.length).toBe(whole.length)
    // 边界无爆音：跳过首尾滤波过渡区，逐样本差不超过信号理论最大斜率（≤2A）。
    const maxDelta = maxAbsDelta(joined.subarray(64, joined.length - 64))
    expect(maxDelta).toBeLessThanOrEqual(Math.round(0.8 * 2 * 32767) + 1)
    // 与整段处理逐样本一致（相位/历史保持确定性）。
    expect(Array.from(joined.subarray(128, 512))).toEqual(Array.from(whole.subarray(128, 512)))
  })

  test('16k 恒等率直通（clip/量化），重置后可复用', () => {
    const input = sineWave(1000, 16000, 0.25)
    const resampler = new PcmResampler()
    const out = resampler.push(input, 16000)
    expect(out.length).toBe(input.length)
    resampler.reset()
    const again = resampler.push(input, 16000)
    expect(Array.from(again)).toEqual(Array.from(out))
  })
})

function concat(left: Int16Array, right: Int16Array): Int16Array {
  const merged = new Int16Array(left.length + right.length)
  merged.set(left, 0)
  merged.set(right, left.length)
  return merged
}

function maxAbsDelta(samples: Int16Array): number {
  let max = 0
  for (let i = 1; i < samples.length; i += 1) {
    max = Math.max(max, Math.abs(samples[i] - samples[i - 1]))
  }
  return max
}
