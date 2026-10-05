// hypit-fix2-text-provider.mjs — C107F2-37（步骤 2/§12.5 G2–G5）：受控文本模型
// fixture。只替换「外部商业模型」这一环：仍经平台正式路由（治理台凭据→
// RoutedTextCompletionService→计费/执行适配），原生编译/渲染/PG/文件/浏览器零 mock。
//
// 按 system prompt 家族返回形状正确的响应：
//   覆盖全片的分段   → reference-analysis segments JSON
//     · C107F3-08 严格分支（TC-F3-08-01）：用户消息为多模态 parts（事实 JSON 文本 +
//       N 张 image_url data URI 帧）时——实际解码每张收到的帧（PNG 签名 + zlib 全解压 +
//       反滤波 + 中心取样主色，不只检查 image_url 键存在）；核对 facts.frames 的
//       时间锚点/sha256/字节数与解码字节一一对应；缺图 / 坏 base64 / 坏 PNG / 上下文
//       错误（帧数不齐、sha 不符、缺 assetId 分析标识、时长非法）→ HTTP 422 使链路失败；
//       不同帧输入（红绿蓝 vs 蓝红顺序）返回不同分段。旧文本形态（无图像 parts）按
//       C107F3-08 契约同样 422（省略图片不冒充视觉分析成功）。
//   materialGaps     → clone-plan steps/materialGaps/status JSON（READY）
//   changes/多文件   → author/authoring changeset（C08 已验证可编译渲染的双色 3s 源）
//   其它             → {"result":"ok"} 兜底
import { createServer } from 'node:http'
import { createHash } from 'node:crypto'
import { inflateSync } from 'node:zlib'
import { Buffer } from 'node:buffer'
import process from 'node:process'

const PORT = Number(process.env.HYPIT_FIX2_PROVIDER_PORT ?? 19099)
// 默认只绑 127.0.0.1（e2e netns 内同栈）；IT 侧 Java 平台闸门拒绝 IP 字面量环回
// base-url（ProviderUrlGuard），经 localhost 主机名访问——HYPIT_FIX2_PROVIDER_HOST='::'
// 双栈监听同时接受 IPv4/IPv6 环回连接。
const HOST = process.env.HYPIT_FIX2_PROVIDER_HOST ?? '127.0.0.1'
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

// ── C107F3-08 严格分支：真实解码收到的帧（零依赖，node:zlib 反滤波） ──────────

const PNG_SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])
const FILTER_NONE = 0
const FILTER_SUB = 1
const FILTER_UP = 2
const FILTER_AVERAGE = 3
const FILTER_PAETH = 4

class StrictInputError extends Error {
  constructor(message) { super(message) }
}

/**
 * 实际解码 PNG：签名 → IHDR（8bit，colorType 0/2/3/4/6）→ IDAT zlib 全解压 → 逐行反滤波。
 * 返回 { width, height, pixels }（pixels 为 RGB 行主序 Uint8Array）。不可解码抛 StrictInputError。
 */
function decodePng(buffer) {
  if (buffer.length < 8 + 25 || !buffer.subarray(0, 8).equals(PNG_SIGNATURE)) {
    throw new StrictInputError('帧字节不是合法 PNG（签名校验失败）')
  }
  let offset = 8
  let width = 0
  let height = 0
  let bitDepth = 0
  let colorType = -1
  const idatChunks = []
  const palette = []
  while (offset + 8 <= buffer.length) {
    const length = buffer.readUInt32BE(offset)
    const type = buffer.toString('latin1', offset + 4, offset + 8)
    const data = buffer.subarray(offset + 8, offset + 8 + length)
    if (type === 'IHDR') {
      width = data.readUInt32BE(0)
      height = data.readUInt32BE(4)
      bitDepth = data[8]
      colorType = data[9]
      if (data[12] !== 0) throw new StrictInputError('PNG interlace 不支持（Adam7 帧不解码）')
    } else if (type === 'PLTE') {
      for (let index = 0; index + 2 < data.length; index += 3) {
        palette.push([data[index], data[index + 1], data[index + 2]])
      }
    } else if (type === 'IDAT') {
      idatChunks.push(data)
    } else if (type === 'IEND') {
      break
    }
    offset += 12 + length
  }
  if (width <= 0 || height <= 0) throw new StrictInputError('PNG 缺少 IHDR')
  if (bitDepth !== 8) throw new StrictInputError(`PNG 仅支持 8bit（收到 ${bitDepth}）`)
  const channelsByType = { 0: 1, 2: 3, 3: 1, 4: 2, 6: 4 }
  const channels = channelsByType[colorType]
  if (channels === undefined) throw new StrictInputError(`PNG colorType ${colorType} 不支持`)
  const rowBytes = width * channels
  let raw
  try {
    raw = inflateSync(Buffer.concat(idatChunks))
  } catch (error) {
    throw new StrictInputError(`PNG IDAT 解压失败：${error.message}`)
  }
  if (raw.length !== height * (rowBytes + 1)) {
    throw new StrictInputError(`PNG 解压长度不符（${raw.length} != ${height * (rowBytes + 1)}，截断或坏数据）`)
  }
  const pixels = new Uint8Array(width * height * 3)
  const previous = new Uint8Array(rowBytes)
  const current = new Uint8Array(rowBytes)
  for (let row = 0; row < height; row += 1) {
    const base = row * (rowBytes + 1)
    const filter = raw[base]
    for (let column = 0; column < rowBytes; column += 1) {
      const value = raw[base + 1 + column]
      const left = column >= channels ? current[column - channels] : 0
      const up = previous[column]
      const upLeft = column >= channels ? previous[column - channels] : 0
      let reconstructed
      if (filter === FILTER_NONE) reconstructed = value
      else if (filter === FILTER_SUB) reconstructed = value + left
      else if (filter === FILTER_UP) reconstructed = value + up
      else if (filter === FILTER_AVERAGE) reconstructed = value + ((left + up) >> 1)
      else if (filter === FILTER_PAETH) reconstructed = value + paeth(left, up, upLeft)
      else throw new StrictInputError(`PNG 滤波器 ${filter} 非法`)
      current[column] = reconstructed & 0xff
    }
    for (let column = 0; column < width; column += 1) {
      let r
      let g
      let b
      if (colorType === 0) {
        const gray = current[column]
        r = g = b = gray
      } else if (colorType === 2) {
        r = current[column * 3]
        g = current[column * 3 + 1]
        b = current[column * 3 + 2]
      } else if (colorType === 3) {
        const entry = palette[current[column]] ?? [0, 0, 0]
        ;[r, g, b] = entry
      } else if (colorType === 4) {
        const gray = current[column * 2]
        r = g = b = gray
      } else {
        r = current[column * 4]
        g = current[column * 4 + 1]
        b = current[column * 4 + 2]
      }
      const target = (row * width + column) * 3
      pixels[target] = r
      pixels[target + 1] = g
      pixels[target + 2] = b
    }
    previous.set(current)
  }
  return { width, height, pixels }
}

function paeth(a, b, c) {
  const p = a + b - c
  const pa = Math.abs(p - a)
  const pb = Math.abs(p - b)
  const pc = Math.abs(p - c)
  if (pa <= pb && pa <= pc) return a
  if (pb <= pc) return b
  return c
}

/** JPEG 容器级校验（SOI/SOF 尺寸/EOI）——严格分支到达的帧恒为 PNG（broker 输出），此路径兜底。 */
function inspectJpeg(buffer) {
  if (buffer.length < 4 || buffer[0] !== 0xff || buffer[1] !== 0xd8) {
    throw new StrictInputError('帧字节不是合法 JPEG（SOI 缺失）')
  }
  let offset = 2
  let height = 0
  let width = 0
  while (offset + 4 <= buffer.length) {
    if (buffer[offset] !== 0xff) throw new StrictInputError('JPEG 标记错位（不可解码）')
    const marker = buffer[offset + 1]
    if (marker === 0xd9) throw new StrictInputError('JPEG 提前 EOI（无帧数据）')
    const length = buffer.readUInt16BE(offset + 2)
    if (marker >= 0xc0 && marker <= 0xcf && marker !== 0xc4 && marker !== 0xc8 && marker !== 0xcc) {
      height = buffer.readUInt16BE(offset + 5)
      width = buffer.readUInt16BE(offset + 7)
      break
    }
    offset += 2 + length
  }
  if (width <= 0 || height <= 0) throw new StrictInputError('JPEG 缺少 SOF 尺寸（不可解码）')
  return { width, height, pixels: null }
}

/** 中心 4×4 主色（与 W41/W64 判定规则一致：r>g+80 && r>b+80 → red …）。 */
function centreDominant(image) {
  if (image.pixels === null) return 'unknown'
  const { width, height, pixels } = image
  const x0 = Math.max(0, Math.floor(width / 2) - 2)
  const y0 = Math.max(0, Math.floor(height / 2) - 2)
  const sum = [0, 0, 0]
  let count = 0
  for (let y = y0; y < y0 + 4 && y < height; y += 1) {
    for (let x = x0; x < x0 + 4 && x < width; x += 1) {
      const base = (y * width + x) * 3
      sum[0] += pixels[base]
      sum[1] += pixels[base + 1]
      sum[2] += pixels[base + 2]
      count += 1
    }
  }
  if (count === 0) return 'unknown'
  const [r, g, b] = sum.map((value) => value / count)
  if (r > g + 80 && r > b + 80) return 'red'
  if (g > r + 80 && g > b + 80) return 'green'
  if (b > r + 80 && b > g + 80) return 'blue'
  return 'unknown'
}

/** 从多模态 user content 提取 { texts: string[], images: [{ mime, bytes }] }（严格解码，坏 base64 抛错）。 */
function partsOf(content) {
  if (typeof content === 'string') return { texts: [content], images: [] }
  if (!Array.isArray(content)) return { texts: [], images: [] }
  const texts = content.filter((part) => part?.type === 'text').map((part) => String(part.text ?? ''))
  const images = []
  for (const part of content) {
    if (part?.type !== 'image_url') continue
    const url = String(part.image_url?.url ?? '')
    const match = /^data:(image\/(?:png|jpeg));base64,([A-Za-z0-9+/=]+)$/.exec(url)
    if (!match) throw new StrictInputError('image part 不是合法的 data:image/(png|jpeg);base64 URI')
    let bytes
    try {
      bytes = Buffer.from(match[2], 'base64')
    } catch (error) {
      throw new StrictInputError(`帧 base64 解码失败：${error.message}`)
    }
    if (bytes.length === 0) throw new StrictInputError('帧 base64 解码为空字节')
    images.push({ mime: match[1], bytes })
  }
  return { texts, images }
}

/**
 * 严格参考分析分支：上下文核对（时间锚点/sha256/字节数/assetId/时长）+ 逐帧真实解码 +
 * 按解码主色产不同分段。任何违规抛 StrictInputError（→ HTTP 422，链路失败不冒充成功）。
 */
function strictReferenceAnalysis(userContent) {
  const { texts, images } = partsOf(userContent)
  if (images.length === 0) {
    throw new StrictInputError('参考分析请求缺少图像 parts（省略图片不得冒充视觉分析成功）')
  }
  // 事实 JSON：逐个 text part 解析，取含 frames 数组的那段（assetId 分析标识是独立 text part）。
  let facts = null
  for (const text of texts) {
    const trimmed = text.trim()
    if (!trimmed.startsWith('{') || !trimmed.includes('"frames"')) continue
    try {
      const parsed = JSON.parse(trimmed)
      if (Array.isArray(parsed.frames)) facts = parsed
    } catch {
      throw new StrictInputError('事实 JSON 解析失败（上下文错误）')
    }
  }
  if (facts === null) throw new StrictInputError('参考分析请求缺少含 frames 的事实 JSON（上下文错误）')
  const joined = texts.join('\n')
  if (!Number.isFinite(facts.durationSeconds) || facts.durationSeconds <= 0) {
    throw new StrictInputError('事实 durationSeconds 非法')
  }
  if (!joined.includes('"assetId"')) {
    throw new StrictInputError('参考分析请求缺少 assetId 分析标识（上下文错误）')
  }
  const frames = Array.isArray(facts.frames) ? facts.frames : []
  if (frames.length !== images.length) {
    throw new StrictInputError(`图像 parts 数（${images.length}）与事实帧锚点数（${frames.length}）不一致`)
  }
  const decoded = images.map((image, index) => {
    const frame = frames[index]
    if (typeof frame.timestampSeconds !== 'number' || !Number.isFinite(frame.timestampSeconds)) {
      throw new StrictInputError(`第 ${index} 帧缺少 timestampSeconds 时间锚点`)
    }
    const sha256 = createHash('sha256').update(image.bytes).digest('hex')
    if (typeof frame.sha256 !== 'string' || frame.sha256 !== sha256) {
      throw new StrictInputError(`第 ${index} 帧 sha256 与事实锚点不符（时间 ${frame.timestampSeconds}s）`)
    }
    if (typeof frame.bytes === 'number' && frame.bytes !== image.bytes.length) {
      throw new StrictInputError(`第 ${index} 帧字节数与事实不符（${image.bytes.length} != ${frame.bytes}）`)
    }
    const parsed = image.mime === 'image/png' ? decodePng(image.bytes) : inspectJpeg(image.bytes)
    if (typeof frame.mimeType === 'string' && frame.mimeType !== image.mime) {
      throw new StrictInputError(`第 ${index} 帧 MIME 与事实不符（${image.mime} != ${frame.mimeType}）`)
    }
    return { frame, image, parsed, dominant: centreDominant(parsed) }
  })
  // 不同帧输入 → 不同分段：按解码主色的连续游程切段，summary/evidence 携带真实主色序列。
  const groups = []
  for (const item of decoded) {
    const last = groups[groups.length - 1]
    if (last && last.dominant === item.dominant) last.items.push(item)
    else groups.push({ dominant: item.dominant, items: [item] })
  }
  const duration = facts.durationSeconds
  const segments = groups.map((group, index) => {
    const first = group.items[0].frame.timestampSeconds
    const last = group.items[group.items.length - 1].frame.timestampSeconds
    const startSeconds = index === 0 ? 0 : Number(((first + groups[index - 1].items[groups[index - 1].items.length - 1].frame.timestampSeconds) / 2).toFixed(3))
    const endSeconds = index === groups.length - 1 ? duration
      : Number(((last + groups[index + 1].items[0].frame.timestampSeconds) / 2).toFixed(3))
    return {
      index,
      startSeconds,
      endSeconds,
      summary: `主色 ${group.dominant} 的片段（${group.items.length} 帧）`,
      evidence: group.items.map((item) => ({
        sourceTimeSeconds: item.frame.timestampSeconds,
        note: `frame@${item.frame.timestampSeconds}s 主色 ${item.dominant}`,
      })),
    }
  })
  return JSON.stringify({
    segments,
    systems: [],
    events: [],
    openQuestions: decoded.some((item) => item.dominant === 'unknown')
      ? ['存在主色未知的帧（证据不足，未推断内容）'] : [],
  })
}

/** user content 折成纯文本（多模态 parts 只取 text 段；planner 调用恒纯文本）。 */
function userTextOf(user) {
  if (typeof user === 'string') return user
  if (Array.isArray(user)) return user.filter((part) => part?.type === 'text').map((part) => String(part.text ?? '')).join('\n')
  return ''
}

/**
 * C107F3-10（TC-F3-10-01 e2e 链）：再生成 planner 严格分支——user 含
 * 「参考分析上下文（冻结快照…」段时，核对快照四要素（analysisId 非空、
 * mediaHash 为 64 位 sha256、assetIds 非空数组、baseRevision 为正整数）。
 * 缺任一 → StrictInputError（422）：不得凭空编造方案（W20 的注入缺字段时
 * fixture 拒绝冒充成功）。旧 planner 族（无该段）行为完全不变。
 */
function strictRegenerateContext(userText) {
  if (!/"analysisId"\s*:\s*"ra-[0-9a-f-]{8,}"/.test(userText)) {
    throw new StrictInputError('再生成 planner 输入缺少非空 analysisId（快照不完整）')
  }
  if (!/"mediaHash"\s*:\s*"[0-9a-f]{64}"/.test(userText)) {
    throw new StrictInputError('再生成 planner 输入缺少 64 位 mediaHash（快照不完整）')
  }
  if (!/"assetIds"\s*:\s*\[\s*"[0-9a-f-]{36}"(?:\s*,\s*"[0-9a-f-]{36}")*\s*\]/.test(userText)) {
    throw new StrictInputError('再生成 planner 输入缺少非空 assetIds（快照不完整）')
  }
  if (!/"baseRevision"\s*:\s*[1-9]\d*/.test(userText)) {
    throw new StrictInputError('再生成 planner 输入缺少正整数 baseRevision（快照不完整）')
  }
}

function respondFor(system, user) {
  const haystack = `${system}\n${typeof user === 'string' ? user : ''}`
  // C14 planner 调用（system 含「执行规划器」+ intent=X）：按 intent 返回可执行
  // action 计划。author/revise → mutation.apply 真实写回（goalMet：revision>0）；
  // analyze/plan → 只读工具；review → snapshot 取证。
  // C107F3-10：再生成链路的 planner user 首段带「参考分析上下文」冻结快照——
  // 先过严格分支（缺快照要素 422），再按 intent 给计划；旧族零变化。
  if (haystack.includes('执行规划器')) {
    const userText = userTextOf(user)
    if (userText.includes('参考分析上下文')) strictRegenerateContext(userText)
    const intent = haystack.match(/intent=([a-z]+)/)?.[1] ?? 'plan'
    return JSON.stringify({ actions: plannerActionsFor(intent) })
  }
  // 参考素材综合（真实 prompt 锚点：reference-analysis.md 的「参考素材分析师」；
  // C107F3-08 起走严格分支：真实解码帧 + 上下文核对 + 按输入产不同分段，违规 422。
  if (haystack.includes('覆盖全片的分段') || haystack.includes('分段 JSON')
    || haystack.includes('参考素材分析师')) {
    return strictReferenceAnalysis(user)
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
    // 观测面：最近 N 条请求的 system 家族与图像计数/sha 摘要（脱敏，不落内容全文/data URI）。
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
    const observation = { family, at: new Date().toISOString() }
    // 多模态请求的观测：图像计数与 sha 摘要（RULE-009 口径；不落 data URI/全文）。
    if (Array.isArray(user)) {
      observation.imageParts = user.filter((part) => part?.type === 'image_url').length
      observation.textChars = user.filter((part) => part?.type === 'text')
        .reduce((total, part) => total + String(part.text ?? '').length, 0)
    }
    recentCalls.push(observation)
    if (recentCalls.length > 50) recentCalls.shift()
    if (holdFamily === family) {
      // 120s 安全阀：release 永不到达也绝不悬挂套接字（客户端 60s 超时先到）。
      await new Promise((resolve) => {
        holdWaiters.push(resolve)
        setTimeout(resolve, 120_000)
      })
    }
    let content
    try {
      content = respondFor(system, user)
    } catch (error) {
      // 严格分支违规：422 使链路失败（省略图片/坏帧/上下文错误不冒充成功）。
      const message = error instanceof StrictInputError ? error.message
        : `fixture strict branch failed: ${error.message}`
      response.writeHead(422, { 'content-type': 'application/json' })
      response.end(JSON.stringify({ error: { message } }))
      return
    }
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
server.listen(PORT, HOST, () => {
  process.stdout.write(`hypit-fix2-text-provider on ${HOST}:${PORT}\n`)
})
