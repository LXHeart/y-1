import { ref } from 'vue'

const MIME_TYPES = ['audio/webm;codecs=opus', 'audio/mp4', 'audio/ogg;codecs=opus', 'audio/webm']
export function useHoldRecorder(onRecorded: (file: File) => void, env = {
  getUserMedia: () => navigator.mediaDevices.getUserMedia({ audio: true }),
  create: (stream: MediaStream, mimeType: string) => new MediaRecorder(stream, { mimeType }),
  supported: (mime: string) => typeof MediaRecorder !== 'undefined' && MediaRecorder.isTypeSupported(mime),
}) {
  const state = ref<'idle' | 'requesting' | 'recording'>('idle')
  const error = ref('')
  let sequence = 0
  let active: MediaRecorder | undefined
  let media: MediaStream | undefined
  let timer: ReturnType<typeof setTimeout> | undefined
  let submit = false
  let began = 0
  function releaseTracks() { media?.getTracks().forEach(track => track.stop()); media = undefined }
  function stop(save = true) {
    sequence++
    clearTimeout(timer)
    submit = save && state.value === 'recording' && Date.now() - began >= 300
    if (save && state.value === 'recording' && !submit) error.value = '录音太短，请按住说完后再松开。'
    if (active?.state === 'recording') active.stop()
    releaseTracks()
    state.value = 'idle'
  }
  async function start() {
    if (state.value !== 'idle' || active) return
    error.value = ''
    const mime = MIME_TYPES.find(env.supported)
    if (!mime) { error.value = '当前浏览器不支持录音，请使用新版浏览器或直接输入文字。'; return }
    const ticket = ++sequence
    state.value = 'requesting'
    try {
      const stream = await env.getUserMedia()
      if (ticket !== sequence) { stream.getTracks().forEach(track => track.stop()); return }
      media = stream
      const recorder = env.create(stream, mime)
      active = recorder
      const chunks: Blob[] = []
      let bytes = 0
      recorder.ondataavailable = event => {
        bytes += event.data.size
        if (bytes > 25 * 1024 * 1024) { error.value = '录音超过 25MB，请缩短后重试。'; stop(false); return }
        if (event.data.size) chunks.push(event.data)
      }
      recorder.onerror = () => { error.value = '录音中断，请检查麦克风后重试。'; stop(false) }
      recorder.onstop = () => {
        active = undefined
        if (!submit) return
        submit = false
        const type = (recorder.mimeType || mime).split(';')[0]!
        const blob = new Blob(chunks, { type })
        if (!blob.size) { error.value = '未录到音频，请重试。'; return }
        const extension = type === 'audio/mp4' ? 'm4a' : type === 'audio/ogg' ? 'ogg' : 'webm'
        onRecorded(new File([blob], `voice.${extension}`, { type }))
      }
      recorder.start(250)
      began = Date.now(); state.value = 'recording'
      timer = setTimeout(() => stop(true), 60000)
    } catch {
      if (ticket !== sequence) return
      releaseTracks(); active = undefined; state.value = 'idle'
      error.value = '无法使用麦克风。请允许麦克风权限，并使用 HTTPS 或本机地址。'
    }
  }
  return { state, error, start, stop }
}
