// hypit-fix2-text-provider.mjs — C107F2-37（步骤 2/§12.5 G2–G5）：受控文本模型
// fixture。只替换「外部商业模型」这一环：仍经平台正式路由（治理台凭据→
// RoutedTextCompletionService→计费/执行适配），原生编译/渲染/PG/文件/浏览器零 mock。
//
// 按 system prompt 家族返回形状正确的响应：
//   覆盖全片的分段   → reference-analysis segments JSON（锚点来自请求内真实 durationSeconds）
//   materialGaps     → clone-plan steps/materialGaps/status JSON（READY）
//   changes/多文件   → author/authoring changeset（C08 已验证可编译渲染的双色 3s 源）
//   其它             → {"result":"ok"} 兜底
import { createServer } from 'node:http'
import process from 'node:process'

const PORT = Number(process.env.HYPIT_FIX2_PROVIDER_PORT ?? 19099)
const TOKEN = process.env.HYPIT_FIX2_PROVIDER_TOKEN ?? ''

/** C08 已验证可编译、可本地渲染的 3s 双色元素源（fixture 作者产物）。 */
const AUTHORED_SVML = `<?svml using="@hypit/markup@1"?>
<svml>
  <import as="time" from="@hypit/timeline-author@1"/>
  <import as="spatial" from="@hypit/spatial@1"/>
  <import as="film" from="@hypit/film@1"/>
  <import as="render" from="@hypit/render-hyperframes@1"/>
  <import as="overlay" from="@hypit/screen-overlay@1"/>
  <import as="recipes" source="./style.svs"/>

  <time:Clock id="clock" frame-rate="10"/>
  <time:Timeline id="animation" clock={clock} end="3s"/>
  <spatial:Canvas id="canvas" width="320" height="240"/>
  <overlay:Track id="overlay" canvas={canvas} timeline={animation.timeline}>
    <overlay:DirectionalMatte id="redWall" z="1" start="0s" end="3s" angle="0" coverage="0.4" feather="0.2" color="#c03030" opacity="1" from="-0.6" to="0.9"/>
    <overlay:DirectionalMatte id="blueWall" z="2" start="0s" end="3s" angle="0" coverage="0.4" feather="0.2" color="#3050c0" opacity="1" from="1.6" to="0.1"/>
  </overlay:Track>
  <film:Film id="main" canvas={canvas} timeline={animation.timeline} appearance={recipes.film.main}>
    <film:Track source={overlay.track}/>
  </film:Film>
  <render:Video id="final" composition={main.composition} timeline={animation.timeline}/>
</svml>
`

const AUTHORED_SVS = `<?svml using="@hypit/svs@1"?>
<sheet version="1">
  /* fixture 作者产物：黑底衬托色墙运动（D-05 样式入口）。 */
  film.main { background: #000000; }
</sheet>
`

function respondFor(system, user) {
  const haystack = `${system}\n${user ?? ''}`
  // C14 planner 调用（system 含「执行规划器」+ intent=X）：按 intent 返回可执行
  // action 计划。author/revise → mutation.apply 真实写回（goalMet：revision>0）；
  // analyze/plan → 只读工具；review → snapshot 取证。
  if (haystack.includes('执行规划器')) {
    const intent = haystack.match(/intent=([a-z]+)/)?.[1] ?? 'plan'
    return JSON.stringify({ actions: plannerActionsFor(intent) })
  }
  // 参考素材综合（真实 prompt 锚点：reference-analysis.md 的「参考素材分析师」/
  // 「严格 JSON」+segments；保留历史标记兼容）。
  if (haystack.includes('覆盖全片的分段') || haystack.includes('分段 JSON')
    || haystack.includes('参考素材分析师')) {
    const duration = Number(haystack.match(/"durationSeconds":\s*([0-9.]+)/)?.[1] ?? 12)
    const half = Number((duration / 2).toFixed(2))
    return JSON.stringify({
      segments: [
        { index: 0, startSeconds: 0, endSeconds: half, summary: '开场片段', evidence: [{ sourceTimeSeconds: 0.5, note: 'fixture 分段' }] },
        { index: 1, startSeconds: half, endSeconds: duration, summary: '收尾片段', evidence: [{ sourceTimeSeconds: half, note: 'fixture 分段' }] },
      ],
      systems: [{ systemId: 'sys-intro', kind: 'title', name: '开场', firstSeenSeconds: 0, lastSeenSeconds: half, segmentIndexes: ['0'] }],
      events: [{ kind: 'cut', atSeconds: half, trigger: null, evidenceAsset: '', inferred: false }],
      openQuestions: [],
    })
  }
  // 作者产物（author.md：输出「多文件 changeset」）——必须先于 clone-plan 判断：
  // author.md 的输入段同样含 materialGaps 字样，旧顺序会把作者调用截胡成方案 JSON。
  if (haystack.includes('多文件 changeset') || haystack.includes('# Author Prompt')
    || (haystack.includes('changeset') && haystack.includes('严格 JSON'))) {
    return JSON.stringify({
      changes: [
        { path: 'main.svml', action: 'put', content: AUTHORED_SVML },
        { path: 'style.svs', action: 'put', content: AUTHORED_SVS },
      ],
      notes: ['fixture 作者：双色 3s 源（C08 已验证编译渲染链）'],
    })
  }
  if (haystack.includes('materialGaps') || haystack.includes('boundSystemId')) {
    return JSON.stringify({
      planId: 'plan-fix2-journey',
      steps: [
        { index: 0, capability: 'video.generate', boundSystemId: 'sys-intro', anchorSeconds: 0, description: '复刻开场双色动效' },
        { index: 1, capability: 'video.generate', boundSystemId: null, anchorSeconds: 3, description: '收尾过渡' },
      ],
      materialGaps: [],
      status: 'READY',
    })
  }
  return JSON.stringify({ result: 'ok' })
}

/** planner 动作计划：author 真实写回（C17 产物面），其余 intent 只读/取证。 */
function plannerActionsFor(intent) {
  if (intent === 'author' || intent === 'revise') {
    return [{
      kind: 'mutation.apply',
      input: {
        applyMode: 'validated',
        changes: [
          { path: 'main.svml', action: 'put', content: AUTHORED_SVML },
          { path: 'style.svs', action: 'put', content: AUTHORED_SVS },
        ],
      },
    }]
  }
  if (intent === 'review') {
    return [{ kind: 'snapshot', input: {} }]
  }
  return [{ kind: 'knowledge.search', input: { query: 'production overview' } }]
}

const server = createServer((request, response) => {
  if (request.url === '/__health') {
    response.writeHead(200, { 'content-type': 'application/json' })
    response.end(JSON.stringify({ ok: true }))
    return
  }
  if (request.url === '/__calls') {
    // 观测面：最近 N 条请求的 system 家族（脱敏，不落内容全文）。
    response.writeHead(200, { 'content-type': 'application/json' })
    response.end(JSON.stringify(recentCalls))
    return
  }
  // C107F2-38 TC-02 受控屏障：/__hold 按家族扣住 LLM 应答——调用到达即计入
  // /__calls（观测面先行），但响应挂起直到 /__release。编排方因此获得秒级
  // 真实断网窗口（fixture 原版在同一个 tick 里记录并应答，窗口为零）。
  // 默认不 armed 时行为与原版完全一致；两端点仅 netns 内可达。
  if (request.url === '/__hold' && request.method === 'POST') {
    let holdBody = ''
    request.on('data', (chunk) => { holdBody += chunk })
    request.on('end', () => {
      try { holdFamily = String(JSON.parse(holdBody).family ?? '') || null }
      catch { holdFamily = null }
      response.writeHead(200, { 'content-type': 'application/json' })
      response.end(JSON.stringify({ hold: holdFamily }))
    })
    return
  }
  if (request.url === '/__release' && request.method === 'POST') {
    const waiters = holdWaiters
    holdWaiters = []
    holdFamily = null
    for (const waiter of waiters) waiter()
    response.writeHead(200, { 'content-type': 'application/json' })
    response.end(JSON.stringify({ released: waiters.length }))
    return
  }
  let body = ''
  request.on('data', (chunk) => { body += chunk })
  request.on('end', async () => {
    if (TOKEN.length > 0) {
      const authorization = request.headers.authorization ?? ''
      if (authorization !== `Bearer ${TOKEN}`) {
        response.writeHead(401, { 'content-type': 'application/json' })
        response.end(JSON.stringify({ error: { message: 'bad token' } }))
        return
      }
    }
    let system
    let user
    try {
      const parsed = JSON.parse(body)
      system = (parsed.messages ?? []).filter((m) => m.role === 'system').map((m) => m.content).join('\n')
      user = [...(parsed.messages ?? [])].reverse().find((m) => m.role === 'user')?.content ?? ''
    } catch {
      response.writeHead(400, { 'content-type': 'application/json' })
      response.end(JSON.stringify({ error: { message: 'bad json' } }))
      return
    }
    const family = familyOf(system)
    recentCalls.push({ family, at: new Date().toISOString() })
    if (recentCalls.length > 50) recentCalls.shift()
    if (holdFamily === family) {
      // 120s 安全阀：release 永不到达也绝不悬挂套接字（客户端 60s 超时先到）。
      await new Promise((resolve) => {
        holdWaiters.push(resolve)
        setTimeout(resolve, 120_000)
      })
    }
    const content = respondFor(system, user)
    response.writeHead(200, { 'content-type': 'application/json' })
    response.end(JSON.stringify({
      id: `chatcmpl-fix2-${Date.now()}`,
      object: 'chat.completion',
      choices: [{ index: 0, message: { role: 'assistant', content }, finish_reason: 'stop' }],
      usage: { prompt_tokens: 64, completion_tokens: 128, total_tokens: 192 },
    }))
  })
})

function familyOf(system) {
  if (system.includes('执行规划器')) return 'planner'
  if (system.includes('覆盖全片的分段') || system.includes('分段 JSON')
    || system.includes('参考素材分析师')) return 'reference-analysis'
  if (system.includes('多文件 changeset') || system.includes('# Author Prompt')
    || (system.includes('changeset') && system.includes('严格 JSON'))) return 'author'
  if (system.includes('materialGaps') || system.includes('boundSystemId')) return 'clone-plan'
  return 'other'
}

const recentCalls = []
let holdFamily = null
let holdWaiters = []
server.listen(PORT, '127.0.0.1', () => {
  process.stdout.write(`hypit-fix2-text-provider on ${PORT}\n`)
})
