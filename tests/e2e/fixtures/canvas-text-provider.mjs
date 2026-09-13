import { createServer } from 'node:http'
import { Buffer } from 'node:buffer'
import process from 'node:process'
import { createHash, timingSafeEqual } from 'node:crypto'
import { pathToFileURL } from 'node:url'

const MAX_BODY = 256 * 1024
const hash = text => createHash('sha256').update(text).digest('hex')
const uuid = '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}'

/** Test provider only: selected IDs are read from the server context, never inferred from the instruction. */
export function parseCanvasRequest(body) {
  if (!body || !Array.isArray(body.messages) || body.max_tokens !== 4096 || body.stream === true || typeof body.model !== 'string') return null
  const system = body.messages.filter(message => message.role === 'system').map(message => message.content).join('\n')
  const user = body.messages.findLast(message => message.role === 'user')?.content
  if (!system.startsWith('你是画布创作助手。') || typeof user !== 'string' || !user.startsWith('上下文（只有这些可见）：\n')) return null
  const delimiter = '\n\n用户指令：'; const boundary = user.indexOf(delimiter)
  const suffix = '\n\n按协议输出单一顶层动作 JSON。'
  if (boundary < 0 || !user.endsWith(suffix)) return null
  const context = user.slice(0, boundary)
  const instruction = user.slice(boundary + delimiter.length, -suffix.length)
  if (!instruction.trim()) return null
  const selectedIds = [...context.matchAll(new RegExp(`^\\[选中\\] shot:(${uuid}): kind=shot;`, 'gm'))].map(match => match[1])
  if (!selectedIds.length || selectedIds.length > 20 || new Set(selectedIds).size !== selectedIds.length) return null
  const mode = instruction.match(/\[canvas-e2e:([a-z-]+)\]/)?.[1] ?? 'edit'
  const supported = ['edit', 'append', 'variant', 'initial', 'regenerate', 'reroll', 'invalid-json', 'unknown-field', 'delay', 'slow']
  if (!supported.includes(mode)) return null
  return { selectedIds, mode, instructionHash: hash(instruction), contextHash: hash(context), model: body.model }
}

export function planFor(request) {
  const marker = request.instructionHash.slice(0, 8)
  switch (request.mode) {
    case 'variant': return { kind: 'variant', title: `C102 独立方案 ${marker}`, shotIds: request.selectedIds }
    case 'append': return { kind: 'edit', actions: [{ kind: 'append-shot', shot: { visual: `C102 追加画面 ${marker}`, narration: '新的到店邀请', plannedSeconds: 5, cameraMove: '固定机位', anchorImageIndex: 0 } }] }
    case 'initial': return { kind: 'prepare-generation', mode: 'initial', shotId: null }
    case 'regenerate': case 'reroll': return { kind: 'prepare-generation', mode: request.mode, shotId: request.selectedIds[0] }
    case 'unknown-field': return { kind: 'edit', actions: [{ kind: 'update-shot', patch: { shotId: request.selectedIds[0], hidden: true, visual: `C102 ${marker}` } }] }
    default: return { kind: 'edit', actions: [{ kind: 'update-shot', patch: { shotId: request.selectedIds[0], visual: `C102 定向画面 ${marker}` } }] }
  }
}

export function createCanvasTextProvider({ token, delayMs = 95_000, slowMs = 2000 } = {}) {
  if (typeof token !== 'string' || token.length < 16) throw new Error('A temporary test token of at least 16 characters is required')
  const calls = []; const timers = new Set()
  const authorized = request => {
    const actual = Buffer.from(request.headers.authorization || ''); const expected = Buffer.from(`Bearer ${token}`)
    return actual.length === expected.length && timingSafeEqual(actual, expected)
  }
  const server = createServer(async (request, response) => {
    const reply = (status, body) => { response.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' }); response.end(JSON.stringify(body)) }
    if (request.method === 'GET' && request.url === '/__health') return reply(200, { ok: true, fixture: 'canvas-text-provider' })
    if (!authorized(request)) return reply(401, { error: 'fixture authorization required' })
    if (request.method === 'GET' && request.url === '/__calls') return reply(200, { calls })
    if (request.method === 'POST' && request.url === '/__reset') { calls.length = 0; return reply(200, { ok: true }) }
    if (request.method !== 'POST' || request.url !== '/v1/chat/completions') return reply(404, { error: 'unknown fixture route' })
    try {
      const chunks = []; let bytes = 0
      for await (const chunk of request) {
        bytes += chunk.length
        if (bytes > MAX_BODY) { reply(413, { error: 'fixture request too large' }); return }
        chunks.push(chunk)
      }
      let body
      try { body = JSON.parse(Buffer.concat(chunks).toString('utf8')) } catch { return reply(400, { error: 'invalid JSON request' }) }
      const parsed = parseCanvasRequest(body)
      if (!parsed) return reply(503, { error: { message: 'Only the canvas plan protocol is enabled by this test fixture' } })
      const call = { id: calls.length + 1, ...parsed, startedAt: new Date().toISOString(), outcome: 'pending', maxTokens: 4096 }
      calls.push(call)
      const finish = () => {
        if (response.destroyed) return
        call.outcome = 'responded'
        const content = parsed.mode === 'invalid-json' ? '{invalid fixture JSON' : JSON.stringify(planFor(parsed))
        reply(200, { id: `canvas-fixture-${call.id}`, object: 'chat.completion', model: parsed.model,
          choices: [{ index: 0, message: { role: 'assistant', content }, finish_reason: 'stop' }],
          usage: { prompt_tokens: 120, completion_tokens: 40, total_tokens: 160 } })
      }
      const delay = parsed.mode === 'delay' ? delayMs : parsed.mode === 'slow' ? slowMs : 0
      if (!delay) return finish()
      const timer = setTimeout(() => { timers.delete(timer); finish() }, delay); timers.add(timer)
      response.on('close', () => { clearTimeout(timer); timers.delete(timer); if (call.outcome === 'pending') call.outcome = 'cancelled' })
    } catch { if (!response.destroyed) reply(400, { error: 'fixture request interrupted' }) }
  })
  server.on('close', () => { timers.forEach(clearTimeout); timers.clear() })
  return server
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const server = createCanvasTextProvider({ token: process.env.CANVAS_E2E_PROVIDER_TOKEN })
  server.listen(18999, '127.0.0.1', () => process.stdout.write('Canvas text fixture listening on loopback port 18999\n'))
  for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => { server.closeAllConnections(); server.close(() => process.exit(0)) })
}
