import { Buffer } from 'node:buffer'
import process from 'node:process'
import { createServer } from 'node:http'
import { createHash, timingSafeEqual } from 'node:crypto'
import { pathToFileURL } from 'node:url'

export function voiceReply(body) {
  const text = (body.messages ?? []).map(message => typeof message.content === 'string' ? message.content : (message.content ?? []).filter(p => p.type === 'text').map(p => p.text).join('\n')).join('\n')
  if (/只返回 JSON 字符串数组/.test(text)) return JSON.stringify(['少用感叹号，保持明确判断'])
  if (/"action":"(adapt-body|suggest-metadata)"/.test(text)) return JSON.stringify({ title: null, summary: null, body: '合成门店，人均38元；排队较久。', changes: ['保留价格和不足'] })
  if (/朋友圈/.test(text)) return JSON.stringify({ copy: '合成门店，人均38元；排队较久。', imageOrder: [], captions: [] })
  if (/"review"/.test(text)) return JSON.stringify({ title: '一次到店记录', review: '合成门店，人均38元；排队较久。', tags: ['到店记录'] })
  if (/"titles"/.test(text)) return JSON.stringify({ titles: Array.from({ length: 5 }, (_, i) => ({ title: `合成门店记录${i + 1}`, hook: '价格与不足' })) })
  return '合成门店，人均38元；排队较久。材料不足的内容仍待核对。'
}
export function createVoiceProvider(token) {
  if (!token || token.length < 16) throw new Error('temporary fixture token required')
  const calls = []
  return createServer(async (req, res) => {
    const json = (status, value) => { res.writeHead(status, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(value)) }
    if (req.url === '/__health') return json(200, { ok: true })
    const actual = Buffer.from(req.headers.authorization ?? ''), expected = Buffer.from(`Bearer ${token}`)
    if (actual.length !== expected.length || !timingSafeEqual(actual, expected)) return json(401, { error: 'unauthorized' })
    if (req.url === '/__calls' && req.method === 'GET') return json(200, { calls })
    if (req.url !== '/v1/chat/completions' || req.method !== 'POST') return json(404, { error: 'not found' })
    try {
      const chunks = []; let length = 0
      for await (const chunk of req) { length += chunk.length; if (length > 20 * 1024 * 1024) return json(413, { error: 'too large' }); chunks.push(chunk) }
      const body = JSON.parse(Buffer.concat(chunks).toString('utf8'))
      if (!Array.isArray(body.messages) || typeof body.model !== 'string') return json(400, { error: 'invalid request' })
      const payload = JSON.stringify(body.messages)
      const count = payload.split('【我的文风档案（仅表达参考，不是本次事实）】').length - 1
      if (count > 1) return json(400, { error: 'duplicate voice appendix' })
      calls.push({ id: calls.length + 1, hash: createHash('sha256').update(payload).digest('hex'), voiceSections: count, hasFactBoundary: payload.includes('不是本次事实'), stream: !!body.stream, imageCount: (payload.match(/image_url/g) ?? []).length })
      const content = voiceReply(body)
      if (body.stream) {
        res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-store' })
        res.write(`data: ${JSON.stringify({ choices: [{ index: 0, delta: { content }, finish_reason: null }] })}\n\n`)
        res.end(`data: ${JSON.stringify({ choices: [{ index: 0, delta: {}, finish_reason: 'stop' }], usage: { prompt_tokens: 20, completion_tokens: 20, total_tokens: 40 } })}\n\ndata: [DONE]\n\n`)
      } else json(200, { choices: [{ index: 0, message: { role: 'assistant', content }, finish_reason: 'stop' }], usage: { prompt_tokens: 20, completion_tokens: 20, total_tokens: 40 } })
    } catch { json(400, { error: 'invalid fixture request' }) }
  })
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const server = createVoiceProvider(process.env.VOICE_PROVIDER_TOKEN)
  server.listen(18999, '0.0.0.0')
  for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => server.close(() => process.exit(0)))
}
