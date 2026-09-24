// eslint-disable-next-line @typescript-eslint/ban-ts-comment -- worklet 模块经 new URL 原样分发，必须保持纯 JS（见下）
// @ts-nocheck
// 数字人麦克风采集 worklet（任务书 #105E C105E-04 / 共享契约 K07）。
//
// 自包含模块：AudioWorklet 经 new URL(...) 加载，产物不经打包转换，因此不 import 其它源文件、
// 只用标准 JS 语法（@ts-nocheck 是这一约束的结果，不是绕过检查的借口）。重采样算法与
// pcm-resampler.ts 同构（63 tap 窗 sinc、cutoff 0.9×Nyquist、相位保持）——算法正确性由
// pcm-resampler.test.ts 覆盖，本文件经真实浏览器（E-06/H）验证。
//
// 输出约定：postMessage 传输 ArrayBuffer（int16 LE、16 kHz 单声道），由主线程封 K07 帧
//（8 字节 BE header：sequence/sampleCount + PCM）。20ms≈320 样本为默认块（浮点采样率决定）。

const TAPS = 63
const HALF = (TAPS - 1) / 2
const OUTPUT_RATE = 16000

function sinc(x) {
  if (x === 0) return 1
  const pix = Math.PI * x
  return Math.sin(pix) / pix
}

function blackman(t) {
  if (t <= -1 || t >= 1) return 0
  return 0.42 + 0.5 * Math.cos(Math.PI * t) + 0.08 * Math.cos(2 * Math.PI * t)
}

function quantize(value) {
  const clipped = Math.max(-1, Math.min(1, value))
  return Math.round(clipped * 32767)
}

class Resampler {
  constructor() {
    this.history = new Float32Array(TAPS - 1)
    this.totalInput = 0
    this.outputCount = 0
  }

  reset() {
    this.history = new Float32Array(TAPS - 1)
    this.totalInput = 0
    this.outputCount = 0
  }

  push(input, inputRate) {
    if (inputRate === OUTPUT_RATE) {
      this.totalInput += input.length
      this.outputCount = this.totalInput
      const out = new Int16Array(input.length)
      for (let i = 0; i < input.length; i += 1) out[i] = quantize(input[i])
      return out
    }
    const step = inputRate / OUTPUT_RATE
    const fc = (Math.min(8000, inputRate / 2) * 0.9) / inputRate
    const buffer = new Float32Array(this.history.length + input.length)
    buffer.set(this.history, 0)
    buffer.set(input, this.history.length)
    const bufferBase = this.totalInput - this.history.length
    const lastCenter = this.totalInput + input.length - 1
    const collected = []
    // 只产出窗口被已到输入完全覆盖的输出（与 pcm-resampler.ts 的 push 一致；流结束后残余
    // ≈HALF 个输入样本对应的输出随 worklet 停止自然丢弃，≈11ms，不影响 K07 帧序号语义）。
    for (let n = this.outputCount; ; n += 1) {
      const phase = n * step
      if (Math.floor(phase) + HALF > lastCenter) break
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
      collected.push(quantize(sum))
    }
    const produced = collected.length
    this.totalInput += input.length
    this.outputCount += produced
    this.history = buffer.slice(Math.max(0, buffer.length - (TAPS - 1)))
    return Int16Array.from(collected)
  }
}

class Pcm16kProcessor extends AudioWorkletProcessor {
  constructor() {
    super()
    this.resampler = new Resampler()
  }

  process(inputs) {
    const channel = inputs[0] && inputs[0][0]
    if (channel && channel.length > 0) {
      const pcm = this.resampler.push(channel, sampleRate)
      if (pcm.length > 0) {
        // 转移所有权，避免复制。
        this.port.postMessage(pcm.buffer, [pcm.buffer])
      }
    }
    return true
  }
}

registerProcessor('pcm-16k', Pcm16kProcessor)
