import { createHash, randomUUID } from 'node:crypto'
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { deflateSync } from 'node:zlib'
import type { Page } from '@playwright/test'

/** Complete isolated HTTP fixture. Real Java/DB/provider behavior is verified separately in IT. */
type Json = Record<string, unknown>
const object = (value: unknown): Json => value && typeof value === 'object' ? value as Json : {}
const clone = <T>(value: T): T => structuredClone(value)
export const SOURCE_TEXT = ['门店三年，人均 68 元。', '招牌面 32 元，日销两百碗。', '小菜 12 元一份。',
  '晚饭人均 45 元。', '加菜另算 10 元。', '会员再省 8 元。'].join('\n\n')
export const DRAFT_ID = '00000101-0000-4000-8000-000000000001'
export const ACCOUNT_ID = '00000101-0000-4000-8000-000000000002'
const hash = (value: string | Buffer) => createHash('sha256').update(value).digest('hex')
const recipes = JSON.parse(readFileSync('contracts/creation-recipes.v1.json', 'utf8')).recipes as Json[]
const now = () => new Date().toISOString()
const mediaId = (position: number) => '00000101-0000-4000-8000-' + String(100 + position).padStart(12, '0')
const header = (size: number) => Buffer.alloc(size)
function crc32(bytes: Buffer): number {
  let crc = 0xffffffff
  for (const byte of bytes) {
    crc ^= byte
    for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ (0xedb88320 & -(crc & 1))
  }
  return (crc ^ 0xffffffff) >>> 0
}
function png(): Buffer {
  const chunk = (type: string, data: Buffer) => {
    const size = header(4); size.writeUInt32BE(data.length)
    const content = Buffer.concat([Buffer.from(type), data])
    const crc = header(4); crc.writeUInt32BE(crc32(content))
    return Buffer.concat([size, content, crc])
  }
  const ihdr = header(13); ihdr.writeUInt32BE(120, 0); ihdr.writeUInt32BE(160, 4); ihdr[8] = 8; ihdr[9] = 2
  const pixels = header(160 * (120 * 3 + 1))
  for (let y = 0; y < 160; y++) for (let x = 0; x < 120; x++) {
    const i = y * 361 + x * 3 + 1
    const color = y < 40 || (y > 64 && y < 96) ? [83, 58, 253] : [239, 237, 255]
    color.forEach((value, channel) => { pixels[i + channel] = value })
  }
  return Buffer.concat([Buffer.from('89504e470d0a1a0a', 'hex'), chunk('IHDR', ihdr), chunk('IDAT', deflateSync(pixels)), chunk('IEND', Buffer.alloc(0))])
}
export const IMAGE = png()
export function fixtureZip(text = SOURCE_TEXT): Buffer {
  const files: Record<string, Buffer> = {
    'README.txt': Buffer.from('Synthetic UI download fixture; server export is verified by Java IT.'),
    'article.md': Buffer.from(text + '\n\n![封面](images/01-cover.png)'),
    'article.txt': Buffer.from(text), 'article.html': Buffer.from('<article><p>' + text + '</p><img src="images/01-cover.png"></article>'),
    'publication.json': Buffer.from(JSON.stringify({ title: '原稿图文验收', summary: '保留文字与来源' })),
    'sources.json': Buffer.from(JSON.stringify({ text })),
    'images/01-cover.png': IMAGE,
  }
  files['manifest.json'] = Buffer.from(JSON.stringify({ files: Object.entries(files).map(([path, bytes]) => ({ path, sizeBytes: bytes.length, sha256: hash(bytes) })) }))
  const locals: Buffer[] = [], directories: Buffer[] = []
  let offset = 0
  for (const [path, bytes] of Object.entries(files)) {
    const name = Buffer.from(path), crc = crc32(bytes), local = header(30), directory = header(46)
    local.writeUInt32LE(0x04034b50); local.writeUInt16LE(20, 4); local.writeUInt16LE(0x800, 6)
    local.writeUInt32LE(crc, 14); local.writeUInt32LE(bytes.length, 18); local.writeUInt32LE(bytes.length, 22); local.writeUInt16LE(name.length, 26)
    directory.writeUInt32LE(0x02014b50); directory.writeUInt16LE(20, 4); directory.writeUInt16LE(20, 6); directory.writeUInt16LE(0x800, 8)
    directory.writeUInt32LE(crc, 16); directory.writeUInt32LE(bytes.length, 20); directory.writeUInt32LE(bytes.length, 24)
    directory.writeUInt16LE(name.length, 28); directory.writeUInt32LE(offset, 42)
    locals.push(local, name, bytes); directories.push(directory, name); offset += local.length + name.length + bytes.length
  }
  const directory = Buffer.concat(directories), end = header(22)
  end.writeUInt32LE(0x06054b50); end.writeUInt16LE(Object.keys(files).length, 8); end.writeUInt16LE(Object.keys(files).length, 10)
  end.writeUInt32LE(directory.length, 12); end.writeUInt32LE(offset, 16)
  return Buffer.concat([...locals, directory, end])
}
export interface StudioFixture {
  project: Json | null
  versions: Map<number, Json>
  sources: Map<string, Json>
  plan: Json | null
  jobs: Json[]
  proposals: Map<string, Json>
  requests: Array<{ path: string; method: string; body: Json }>
  exports: Map<string, Buffer>
  sync: Json | null
  failItem: number | null
  unknownItem: number | null
  syncUnknown: boolean
  failCandidates: boolean
  writesEnabled: boolean
  sourceFailure: boolean
  preparePending: boolean
}
export function newSession(): StudioFixture {
  return { project: null, versions: new Map(), sources: new Map(), plan: null, jobs: [], proposals: new Map(),
    requests: [], exports: new Map(), sync: null, failItem: 3, unknownItem: null, syncUnknown: false,
    failCandidates: false, writesEnabled: true, sourceFailure: false, preparePending: false }
}
export function seedCompleted(session: StudioFixture, platform = 'wechat-official'): void {
  session.project = {
    id: DRAFT_ID, title: '原稿图文验收', articleTitle: '原稿图文验收', content: SOURCE_TEXT, topic: '原稿',
    version: 4, capability: 'article', platform, contentForm: 'graphic', sourceType: 'independent',
    status: 'completed', contentMode: 'article', updatedAt: now(), runIds: [], resultAssetIds: [],
    workspace: { schemaVersion: 1, capability: 'article', currentStep: 'content',
      inputs: { article: { completed: true }, brief: { processingMode: 'format' },
        studio: { schemaVersion: 1, recipe: { id: 'article-format', version: '1.0.0' }, sourceDocumentId: null,
          visualPlan: null, activeVisualJobId: null, lastProposalId: null, renderTheme: 'standard' } },
      resultRefs: [{ id: mediaId(1), refType: 'media', role: 'cover', position: 1 }],
      delivery: { version: 1, platform, contentForm: 'graphic', titleOrOpening: '原稿图文验收',
        bodyOrDescription: SOURCE_TEXT, summary: '保留文字与来源', coverRef: { id: mediaId(1), refType: 'media', role: 'cover' },
        declarations: { aiGenerated: 'confirmed', commercial: 'confirmed', original: 'confirmed' } } },
  }
  session.versions.set(4, clone(session.project))
}
function save(session: StudioFixture, body: Json): Json {
  const previous = session.project
  const version = Number(previous?.version ?? 0) + 1
  const clean = { ...body }; delete clean.expectedVersion
  session.project = { ...previous, ...clean, id: DRAFT_ID, version, updatedAt: now(), createdAt: previous?.createdAt ?? now(),
    capability: 'article', resultAssetIds: previous?.resultAssetIds ?? [], runIds: previous?.runIds ?? [] }
  session.versions.set(version, clone(session.project))
  return session.project
}
function sourceOf(session: StudioFixture, body: Json): Json {
  const text = String(body.kind === 'draft-content' ? session.project?.content ?? '' : body.text ?? '').replace(/\r\n?/g, '\n')
  let start = 0
  const blocks = text.split(/\n\n+/).map((text, index) => {
    const value = { id: randomUUID(), kind: 'paragraph', position: index + 1, startCodePoint: start,
      endCodePoint: start + [...text].length, text, textHash: hash(text) }
    start += [...text].length + 2
    return value
  })
  return { id: randomUUID(), draftId: DRAFT_ID, schemaVersion: 1, kind: body.kind, title: '', rawText: text,
    normalizedMarkdown: text, contentHash: hash(text), blocks, sourceRefs: [], warnings: [], createdAt: now() }
}
function planOf(session: StudioFixture, body: Json): Json {
  const source = session.sources.get(String(object(body.source).id))!
  const blocks = source.blocks as Json[]
  const article = object(body.recipe).id === 'article-visuals'
  return { id: randomUUID(), draftId: DRAFT_ID, status: 'ready', revision: 1, confirmedRevision: null,
    source: body.source, baseDraftVersion: body.expectedDraftVersion, baseContentHash: hash(String(session.project?.content)),
    stale: false, runId: randomUUID(), error: null, createdAt: now(),
    document: { recipe: body.recipe, strategy: body.strategy ?? 'information',
      style: { styleId: 'minimal-note', layoutId: 'list', paletteId: 'macaron' },
      explanation: '按来源段落安排内容，每页保留原文依据。', uncoveredBlockIds: [],
      items: Array.from({ length: Number(body.itemCount ?? (article ? 3 : 6)) }, (_, index) => ({
        itemId: randomUUID(), cardId: randomUUID(), position: index + 1, role: index === 0 ? 'cover' : article ? 'illustration' : 'content',
        title: index === 0 ? '原稿图文封面' : '第 ' + (index + 1) + ' 页', bullets: [], caption: '', purpose: '说明原文信息',
        illustration: '以原文事实制作简洁画面', sourceBlockIds: [blocks[index % blocks.length].id], criticalText: [blocks[index % blocks.length].text],
        layoutId: 'list', targetAspect: article ? '16:9' : '3:4', placement: article && index ? { afterBlockId: blocks[index % blocks.length].id } : null,
        inputMediaRef: null,
      })) } }
}
function jobOf(session: StudioFixture, body: Json): Json {
  const plan = session.plan!, document = object(plan.document), jobId = randomUUID()
  const selected = body.selectedItemIds as string[]
  const items = (document.items as Json[]).filter(item => selected.includes(String(item.itemId))).map(item => {
    const position = Number(item.position), attemptId = randomUUID()
    const state = session.jobs.length === 0 && position === session.unknownItem ? 'unknown'
      : session.jobs.length === 0 && position === session.failItem ? 'failed' : 'succeeded'
    return { attemptId, itemId: item.itemId, position, state, runId: randomUUID(),
      error: state === 'failed' ? { code: 'STUDIO_PROVIDER_FAILED', message: '此图生成失败，可单独重做' } : null,
      artifact: state === 'succeeded' ? { id: randomUUID(), itemId: item.itemId, attemptId, plan: { id: plan.id, revision: plan.revision },
        originalMediaRef: { id: mediaId(position), refType: 'media' }, deliveryMediaRef: { id: mediaId(position), refType: 'media' },
        runId: randomUUID(), width: 120, height: 160, contentHash: hash(IMAGE), anchorArtifactId: null, createdAt: now() } : null }
  })
  return { id: jobId, requestId: body.requestId, draftId: DRAFT_ID, plan: body.plan, version: 1, items,
    state: items.some(item => item.state === 'unknown') ? 'unknown' : items.some(item => item.state === 'failed') ? 'partial' : 'succeeded',
    quoteId: body.quoteId, cancelRequested: false, createdAt: now(), updatedAt: now() }
}
export async function stubStudioApis(page: Page, session: StudioFixture): Promise<void> {
  const replay = new Map<string, { input: string; data: unknown }>()
  await page.context().route('**/*', async route => {
    const request = route.request(), url = new URL(request.url()), path = url.pathname, method = request.method()
    if (path.startsWith('/fixture-images/')) return route.fulfill({ contentType: 'image/png', body: IMAGE })
    if (path.startsWith('/fixture-files/')) {
      const bytes = session.exports.get(path.split('/').pop()!)
      return route.fulfill({ status: bytes ? 200 : 404, body: bytes ?? Buffer.alloc(0),
        headers: { 'Content-Type': 'application/zip', 'Content-Disposition': 'attachment; filename="studio-fixture.zip"' } })
    }
    if (!path.startsWith('/api/')) {
      if (!['127.0.0.1', 'localhost'].includes(url.hostname)) return route.abort()
      return route.continue()
    }
    const body = request.postData() ? object(request.postDataJSON()) : {}
    session.requests.push({ path, method, body })
    const respond = (data: unknown, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify({ success: true, data }) })
    const fail = (code: string, error: string, status = 409) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify({ success: false, code, error }) })
    const key = method + ':' + path + ':' + body.requestId
    const prior = body.requestId ? replay.get(key) : null
    const result = async (data: unknown, status = 200) => {
      if (body.requestId) replay.set(key, { input: JSON.stringify(body), data: clone(data) })
      await respond(data, status)
    }
    if (prior) return prior.input === JSON.stringify(body) ? respond(prior.data) : fail('STUDIO_OPERATION_CONFLICT', '同键异参')
    if (path === '/api/auth/me') return respond({ user: { id: ACCOUNT_ID, email: 'task101@test.invalid', displayName: '创作验收', role: 'admin', roles: ['platform_admin'] } })
    if (path === '/api/auth/logout') return respond({ loggedOut: true })
    if (['/api/me/identities', '/api/me/store-scopes', '/api/me/organization-scopes', '/api/organizations'].includes(path)) return respond([])
    if (path === '/api/creation-studio/recipes') return respond({ items: recipes.map(recipe => ({ ...recipe, enabled: session.writesEnabled })) })
    if (path.endsWith('/capabilities') && path.includes('creation-studio')) return respond({ studioEnabled: session.writesEnabled, wechatEnabled: true,
      image: { available: true, protocol: 'openai-image', referenceKinds: ['image'], maxReferences: 1, maxReferenceBytes: 5242880,
        outputTextReliability: 'model-dependent', configurationFingerprint: 'fixture', billingSource: 'platform', unavailableReason: null } })
    if (path === '/api/creation-drafts' && method === 'POST') return respond(save(session, body))
    if (path === '/api/creation-drafts' && method === 'GET') return respond({ items: session.project ? [session.project] : [], nextCursor: null })
    if (path === '/api/creation-drafts/' + DRAFT_ID) {
      if (!session.project) return fail('STUDIO_NOT_FOUND', '草稿不存在', 404)
      if (method === 'PUT') {
        if (body.expectedVersion !== session.project.version) return fail('STUDIO_VERSION_CONFLICT', '草稿版本已变化')
        return respond(save(session, body))
      }
      return respond(session.project)
    }
    if (path === '/api/creation-studio/sources' && method === 'POST') {
      if (session.sourceFailure) return fail('STUDIO_VERSION_CONFLICT', '草稿版本已变化，请刷新后重试')
      if (body.expectedDraftVersion !== session.project?.version) return fail('STUDIO_VERSION_CONFLICT', '来源版本不一致')
      const source = sourceOf(session, body); session.sources.set(String(source.id), source); return result(source, 201)
    }
    if (path.startsWith('/api/creation-studio/sources/')) return respond(session.sources.get(path.split('/').pop()!))
    if (path === '/api/creation-studio/visual-plans' && method === 'POST') {
      session.plan = planOf(session, body)
      return result(session.preparePending ? { ...session.plan, status: 'preparing', revision: 0, document: null } : session.plan, session.preparePending ? 202 : 200)
    }
    if (path.startsWith('/api/creation-studio/visual-plans/')) {
      if (!session.plan) return fail('STUDIO_NOT_FOUND', '计划不存在', 404)
      if (path.endsWith('/confirm')) {
        if (body.expectedDraftVersion !== session.project?.version || body.expectedRevision !== session.plan.revision) return fail('STUDIO_VERSION_CONFLICT', '确认版本不一致')
        session.plan.confirmedRevision = session.plan.revision; return result(session.plan)
      }
      if (path.endsWith('/estimate')) return result({ id: randomUUID(), plan: { id: session.plan.id, revision: session.plan.revision },
        selectedItemIds: body.selectedItemIds, imageCalls: (body.selectedItemIds as string[]).length, consistencyMode: body.consistencyMode,
        anchorArtifactId: body.anchorArtifactId ?? null, userCredits: 0, platformBudgetCents: (body.selectedItemIds as string[]).length * 30,
        billingSource: 'platform', pricingVersion: 'v1', configurationFingerprint: 'fixture', expiresAt: new Date(Date.now() + 120000).toISOString(), warnings: [] })
      if (path.endsWith('/adopt')) {
        if (body.expectedDraftVersion !== session.project?.version) return fail('STUDIO_VERSION_CONFLICT', '采用版本不一致')
        const refs = (body.selections as Json[]).map(selection => {
          const planItem = (object(session.plan!.document).items as Json[]).find(item => item.itemId === selection.itemId)!
          const item = session.jobs.flatMap(job => job.items as Json[]).find(item => object(item.artifact).id === selection.artifactId)!
          return { ...object(object(item.artifact).deliveryMediaRef), cardId: planItem.cardId, position: planItem.position, role: planItem.role === 'cover' ? 'cover' : 'card' }
        })
        const workspace = clone(object(session.project?.workspace)), delivery = object(workspace.delivery)
        workspace.resultRefs = [...((workspace.resultRefs as Json[]) ?? []).filter(ref => !refs.some(next => next.cardId === ref.cardId)), ...refs]
        delivery.mediaRefs = workspace.resultRefs
        if (refs.some(ref => ref.role === 'cover')) delivery.coverRef = refs.find(ref => ref.role === 'cover')
        workspace.delivery = delivery
        const project = save(session, { ...session.project, workspace })
        return result({ project, appliedVersion: project.version, alreadyApplied: false })
      }
      if (method === 'PATCH') {
        if (body.expectedRevision !== session.plan.revision) return fail('STUDIO_VERSION_CONFLICT', '计划版本不一致')
        session.plan = { ...session.plan, document: body.document, revision: Number(session.plan.revision) + 1, confirmedRevision: null }
      }
      return result(session.plan)
    }
    if (path === '/api/creation-studio/visual-jobs') {
      if (method === 'POST') { const job = jobOf(session, body); session.jobs.unshift(job); return result(job, 202) }
      return respond({ items: session.jobs, nextCursor: null })
    }
    if (path.startsWith('/api/creation-studio/visual-jobs/')) {
      const id = path.split('/')[4], job = session.jobs.find(job => job.id === id)
      return job ? respond(job) : fail('STUDIO_NOT_FOUND', '任务不存在', 404)
    }
    if (path.startsWith('/api/media/')) return respond({ id: path.split('/')[3], downloadUrl: url.origin + '/fixture-images/card.png', status: 'active' })
    if (path === '/api/creation-studio/render-previews') return respond({ draftId: DRAFT_ID, version: body.version,
      renderVersion: 'creation-render-1.0.0', contentHash: hash(String(session.project?.content)), text: session.project?.content,
      html: '<section style="color:#0d253d;background-color:#ffffff;padding:16px"><h2>原稿图文验收</h2>'
        + String(session.project?.content).split('\n\n').map(text => '<p>' + text + '</p>').join('') + '</section>',
      warnings: [], unresolvedMediaIds: [] })
    if (path.endsWith('/exports')) {
      const version = Number(body.version), project = session.versions.get(version)
      if (!project) return fail('STUDIO_VERSION_CONFLICT', '导出版本不存在')
      const id = randomUUID(), bytes = fixtureZip(String(project.content)); session.exports.set(id, bytes)
      mkdirSync('test-artifacts/task-101/http-files', { recursive: true })
      writeFileSync('test-artifacts/task-101/http-files/' + id, bytes)
      return result({ draftId: DRAFT_ID, version, format: body.format, file: { exportId: id, filename: 'studio-fixture.zip',
        contentType: 'application/zip', sha256: hash(bytes), sizeBytes: bytes.length, url: url.origin + '/fixture-files/' + id,
        expiresAt: new Date(Date.now() + 900000).toISOString() }, missingItems: [] })
    }
    if (path === '/api/creation-channels/wechat/accounts') return respond({ items: [{ id: ACCOUNT_ID, displayName: '验收公众号',
      appId: 'wx0000000000000101', state: 'active', version: 2, verifiedAt: now(), error: null }], nextCursor: null })
    if (path === '/api/creation-channels/wechat/draft-syncs') {
      if (method === 'GET') return respond({ items: session.sync ? [session.sync] : [], nextCursor: null })
      session.sync = { id: randomUUID(), requestId: body.requestId, accountId: body.accountId, draftId: DRAFT_ID,
        draftVersion: body.draftVersion, state: session.syncUnknown ? 'unknown' : 'succeeded', externalDraftMediaId: session.syncUnknown ? null : 'FIXTURE-WX-ID',
        payloadHash: hash(JSON.stringify(body)), version: 5, createdAt: now(), verifiedAt: session.syncUnknown ? null : now(), error: null }
      return result(session.sync, 202)
    }
    if (path.includes('/draft-syncs/')) {
      if (path.endsWith('/candidates')) return session.failCandidates ? fail('STUDIO_TIMEOUT', '搜索超时，请输入草稿 ID 核实', 503)
        : respond({ items: [{ externalDraftMediaId: 'FIXTURE-WX-ID', title: '原稿图文验收', updatedAt: now(), contentMatches: true }], searchedCount: 1, hasMore: false })
      if (path.endsWith('/reconcile')) session.sync = { ...session.sync, state: 'succeeded', version: 6, verifiedAt: now(), externalDraftMediaId: body.externalDraftMediaId }
      return respond(session.sync)
    }
    if (path === '/api/creation-studio/text-proposals') {
      const proposal = { id: randomUUID(), requestId: body.requestId, draftId: DRAFT_ID, action: body.action, status: 'ready',
        baseDraftVersion: body.expectedDraftVersion, baseContentHash: hash(String(session.project?.content)), source: body.source,
        result: { title: '原稿标题建议', summary: '建议摘要，保留事实与原文。', body: body.action === 'adapt-body' ? SOURCE_TEXT : null,
          changes: ['调整结构'], sourceBlockIds: body.selectedBlockIds }, runId: randomUUID(), safety: null, appliedDraftVersion: null,
        error: null, createdAt: now(), expiresAt: new Date(Date.now() + 1800000).toISOString() }
      session.proposals.set(proposal.id, proposal); return result(proposal)
    }
    if (path.startsWith('/api/creation-studio/text-proposals/')) return respond(session.proposals.get(path.split('/')[4]))
    if (path.includes('credits')) return respond({ balance: 100, remaining: 100, available: 100, items: [] })
    if (path.includes('style-skills')) return respond({ items: [], titleFormulas: [], genres: [], styles: [] })
    if (path.includes('check') || path.includes('safety')) return respond({ findings: [], overallRisk: 'low', passed: true })
    if (path.startsWith('/api/creation-') || path.startsWith('/api/article-generation/')) return fail('FIXTURE_UNHANDLED', '未覆盖接口：' + path, 501)
    return respond({ items: [], profiles: [], stores: [], organizations: [], activeIdentity: null })
  })
}
export const byTestId = (page: Page, id: string) => page.locator('[data-test="' + id + '"], [data-testid="' + id + '"]')
export async function writeTheme(page: Page, theme: 'light' | 'dark'): Promise<void> {
  await page.addInitScript(value => localStorage.setItem('theme-preference', value), theme)
}
