/**
 * 纯重采样算法（任务书 #105E C105E-04 / 共享契约 K07）。
 *
 * 带低通的窗 sinc：taps=63、cutoff=min(8000, inputRate/2)×0.9、Blackman 窗；输出固定 16 kHz
 * 单声道 int16（clip [-1,1]）。跨 chunk 保留输入历史与绝对相位（边界连续无爆音、不重不漏）；
 * 输出计数 = floor(总输入×16000/inputRate)±1（尾部零延拓，滤波群延迟不吞样本）。
 * 本文件是可单测的算法源；pcm-worklet.ts 内嵌同构实现（worklet 模块需自包含、不经打包转换）。
 */

const TAPS = 63
const HALF = (TAPS - 1) / 2 // 31
const OUTPUT_RATE = 16_000

function sinc(x: number): number {
  if (x === 0) return 1
  const pix = Math.PI * x
  return Math.sin(pix) / pix
}

/** Blackman 窗（|t| ≥ 1 为 0）。 */
function blackman(t: number): number {
  if (t <= -1 || t >= 1) return 0
  return 0.42 + 0.5 * Math.cos(Math.PI * t) + 0.08 * Math.cos(2 * Math.PI * t)
}

function quantize(value: number): number {
  const clipped = Math.max(-1, Math.min(1, value))
  return Math.round(clipped * 32767)
}

export class PcmResampler {
  /** 最近 TAPS-1 个输入样本（buffer[0] 的绝对序号 = base()）。 */
  private history: Float32Array = new Float32Array(TAPS - 1)
  /** 累计输入样本数（绝对坐标）。 */
  private totalInput = 0
  /** 已产出输出样本数——相位按 outputCount×step 单次乘法推导，跨任意切块位同（不累加浮点误差）。 */
  private outputCount = 0
  /** 最近一次输入的采样率（flush 收尾需要重建核参数）。 */
  private flushRate = 0

  reset(): void {
    this.history = new Float32Array(TAPS - 1)
    this.totalInput = 0
    this.outputCount = 0
    this.flushRate = 0
  }

  private base(): number {
    return this.totalInput - this.history.length
  }

  /**
   * 喂入一段单声道 float 输入（任意长度/任意切分），返回重采样后的 int16 输出（可为空）。
   */
  push(input: Float32Array, inputRate: number): Int16Array {
    if (inputRate === OUTPUT_RATE) {
      this.totalInput += input.length
      this.outputCount = this.totalInput
      const out = new Int16Array(input.length)
      for (let i = 0; i < input.length; i += 1) out[i] = quantize(input[i])
      return out
    }
    const step = inputRate / OUTPUT_RATE
    const fc = (Math.min(8000, inputRate / 2) * 0.9) / inputRate // 归一化截止（周期/样本）
    this.flushRate = inputRate

    const buffer = new Float32Array(this.history.length + input.length)
    buffer.set(this.history, 0)
    buffer.set(input, this.history.length)
    const bufferBase = this.base()
    const lastCenter = this.totalInput + input.length - 1

    // 只产出窗口 [center-HALF, center+HALF] 完全被已到输入覆盖的输出——绝不把「未来样本」
    // 当零算掉（chunk 尾不冒进）；流结束时的残余由 flush() 以零延拓收尾。
    const collected: number[] = []
    for (let n = this.outputCount; ; n += 1) {
      const phase = n * step
      if (Math.floor(phase) + HALF > lastCenter) break
      collected.push(this.renderOutput(buffer, bufferBase, phase, fc))
    }

    const produced = collected.length
    this.totalInput += input.length
    this.outputCount += produced
    this.history = buffer.slice(Math.max(0, buffer.length - (TAPS - 1)))
    return Int16Array.from(collected)
  }

  /** 流结束收尾：剩余 phase ≤ 最后一个输入样本的输出按零延拓产出（计数契约 ±1）。 */
  flush(): Int16Array {
    if (this.outputCount === 0 && this.totalInput === 0) return new Int16Array(0)
    const rate = this.flushRate ?? 0
    if (rate === 0) return new Int16Array(0)
    const step = rate / OUTPUT_RATE
    const fc = (Math.min(8000, rate / 2) * 0.9) / rate
    const buffer = this.history
    const bufferBase = this.totalInput - this.history.length
    const lastCenter = this.totalInput - 1
    const collected: number[] = []
    for (let n = this.outputCount; ; n += 1) {
      const phase = n * step
      if (phase > lastCenter) break
      collected.push(this.renderOutput(buffer, bufferBase, phase, fc))
    }
    this.outputCount += collected.length
    return Int16Array.from(collected)
  }

  /** 单输出卷积（buffer 内越界按零延拓）。 */
  private renderOutput(buffer: Float32Array, bufferBase: number, phase: number, fc: number): number {
    const center = Math.floor(phase)
    const frac = phase - center
    let sum = 0
    for (let tap = -HALF; tap <= HALF; tap += 1) {
      const index = center + tap - bufferBase
      if (index < 0 || index >= buffer.length) continue
      sum += buffer[index]
        * sinc(2 * fc * (tap - frac))
        * blackman((tap - frac) / (HALF + 1))
        * 2 * fc
    }
    return quantize(sum)
  }
}

/** 测试辅助：正弦波。 */
export function sineWave(frequencyHz: number, rate: number, seconds: number, amplitude = 0.8): Float32Array {
  const length = Math.round(rate * seconds)
  const wave = new Float32Array(length)
  for (let i = 0; i < length; i += 1) {
    wave[i] = amplitude * Math.sin((2 * Math.PI * frequencyHz * i) / rate)
  }
  return wave
}

/** 测试辅助：Goertzel 幅度（跳过前后 20% 边缘）。 */
export function goertzelMagnitude(samples: Int16Array, frequencyHz: number, rate = OUTPUT_RATE): number {
  const start = Math.floor(samples.length * 0.2)
  const end = Math.floor(samples.length * 0.8)
  const n = end - start
  if (n <= 0) return 0
  const k = Math.round((n * frequencyHz) / rate)
  const coeff = 2 * Math.cos((2 * Math.PI * k) / n)
  let s1 = 0
  let s2 = 0
  for (let i = start; i < end; i += 1) {
    const s0 = samples[i] / 32768 + coeff * s1 - s2
    s2 = s1
    s1 = s0
  }
  return Math.sqrt(Math.max(0, s1 * s1 + s2 * s2 - coeff * s1 * s2)) / (n / 2)
}

/** 测试辅助：RMS（int16 归一化）。 */
export function rms(samples: Int16Array): number {
  if (samples.length === 0) return 0
  let sum = 0
  for (const sample of samples) sum += (sample / 32768) ** 2
  return Math.sqrt(sum / samples.length)
}
