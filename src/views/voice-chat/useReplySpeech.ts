import { ref } from 'vue'

export function speechChunks(text: string): string[] {
  // Short utterances avoid long-text stalls in browser speech engines.
  return text.match(/[\s\S]{1,120}(?:[。！？.!?\n]|$)|[\s\S]{1,120}/gu) || []
}
export function useReplySpeech(env = {
  synthesis: typeof window !== 'undefined' ? window.speechSynthesis : undefined,
  utterance: (text: string) => new SpeechSynthesisUtterance(text),
}) {
  const supported = Boolean(env.synthesis)
  const speaking = ref(false)
  const error = ref('')
  let epoch = 0
  let timer: ReturnType<typeof setTimeout> | undefined
  let current: SpeechSynthesisUtterance | undefined
  function stop() {
    epoch++; clearTimeout(timer)
    if (speaking.value) env.synthesis?.cancel()
    speaking.value = false; current = undefined
  }
  function speak(text: string) {
    stop(); error.value = ''
    if (!env.synthesis) { error.value = '当前浏览器不支持朗读，可继续文字聊天。'; return }
    const chunks = speechChunks(text)
    if (!chunks.length) return
    const ticket = epoch
    const voices = env.synthesis.getVoices().filter(voice => /^zh([_-]|$)/i.test(voice.lang))
    const voice = voices.find(item => item.localService) || voices[0]
    let index = 0
    speaking.value = true
    function next() {
      if (ticket !== epoch) return
      clearTimeout(timer)
      const chunk = chunks[index++]
      if (!chunk) { speaking.value = false; current = undefined; return }
      current = env.utterance(chunk); current.lang = 'zh-CN'
      if (voice) current.voice = voice
      current.onend = next
      current.onerror = () => {
        if (ticket !== epoch) return
        stop(); error.value = '朗读失败，请点击回答下方的“朗读”重试，或检查系统中文语音。'
      }
      timer = setTimeout(() => {
        if (ticket !== epoch) return
        stop(); error.value = '朗读没有继续，请检查系统语音后重试。'
      }, 30000)
      try { env.synthesis!.speak(current) } catch {
        stop(); error.value = '无法启动朗读，请检查系统语音后重试。'
      }
    }
    next()
  }
  return { supported, speaking, error, speak, stop }
}
