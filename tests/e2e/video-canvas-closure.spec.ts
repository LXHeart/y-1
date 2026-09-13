import { execFile } from 'node:child_process'
import { createHash, randomUUID } from 'node:crypto'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { promisify } from 'node:util'
import { expect, test, type APIResponse, type Page, type Response } from '@playwright/test'
import { Pool } from 'pg'
import type { ApplyCanvasPlanResult, CanvasDocumentBody, CanvasPlanResult, CreateCanvasPlanRequest, CreateVariantResult } from '../../src/types/video-canvas'
import type { CreationExportResult } from '../../src/lib/creation-export'
import {
  assertCanvasSubtitleRuntime, readCanvasProviderCalls, readZipEntry, renderCanvasClosureMedia, seedCanvasClosureFixture,
  uploadCanvasMedia, walkZipEntries,
} from './fixtures/video-canvas'

/** TC102-057..060: real UI → Edge → Java → loopback model → PostgreSQL → FFmpeg/MinIO.
 * API/SQL are used only to seed inputs, inspect results, replay idempotency or inject faults.
 * The only browser route handlers deliberately discard real responses; none fulfills success.
 */
const baseURL = process.env.BASE_URL || 'http://127.0.0.1:18080'
const aiBaseURL = process.env.AI_BASE_URL || 'http://127.0.0.1:18082'
const exec = promisify(execFile)
const sha = (value: string | Buffer) => createHash('sha256').update(value).digest('hex')
const selector = (name: string) => `[data-test="${name}"]`
type Entry = 'grassland' | 'ai'
type HttpResponse = APIResponse | Response
type Fixture = Awaited<ReturnType<typeof seedCanvasClosureFixture>>
interface ShotRow { id: string; seq: number; visual: string; prompt: string; narration: string }
interface TaskRow {
  id: string; phase: string; recompose_seq: number; final_media_id: string | null
  actual_duration_seconds: number | null; actual_cost_cents: number | null; unit_price_cents: number
}
interface ProjectRow {
  id: string; title: string; version: number; source_type: string; task_id: string | null; task_version: number | null
  store_id: string | null; platform: string | null; organization_id: string | null; workspace_json: Record<string, unknown>
}
interface Probe { streams: Array<{ codec_type: string; duration?: string; r_frame_rate?: string }>; format: { duration: string } }

async function data<T>(response: HttpResponse, status = 200): Promise<T> {
  expect(response.status(), await response.text()).toBe(status)
  const body = await response.json() as { success: boolean; data: T }
  expect(body.success).toBe(true)
  return body.data
}
async function expectError(response: HttpResponse, status: number, code: string): Promise<void> {
  expect(response.status(), await response.text()).toBe(status)
  expect((await response.json()).code).toBe(code)
}
function pool(): Pool {
  if (!process.env.E2E_DATABASE_URL) throw new Error('An isolated E2E_DATABASE_URL is required')
  return new Pool({ connectionString: process.env.E2E_DATABASE_URL, max: 2 })
}
async function project(db: Pool, id: string): Promise<ProjectRow> {
  return (await db.query<ProjectRow>('SELECT * FROM creation_draft WHERE id=$1', [id])).rows[0]
}
async function shots(db: Pool, id: string): Promise<ShotRow[]> {
  return (await db.query<ShotRow>('SELECT id::text,seq,visual,prompt,narration FROM video_shot WHERE storyboard_id=$1 ORDER BY seq', [id])).rows
}
async function canvas(db: Pool, draft: string): Promise<{ revision: number; document: CanvasDocumentBody } | undefined> {
  const row = (await db.query<{ revision: string; document: CanvasDocumentBody }>('SELECT revision,document FROM creation_canvas_document WHERE draft_id=$1', [draft])).rows[0]
  return row && { revision: Number(row.revision), document: row.document }
}
async function taskRow(db: Pool, id: string): Promise<TaskRow> {
  return (await db.query<TaskRow>('SELECT * FROM video_production_task WHERE id=$1', [id])).rows[0]
}
async function counts(db: Pool, account: string) {
  const row = (await db.query<{ tasks: number; runs: number; reservations: number; takes: number }>(
    `SELECT (SELECT count(*)::int FROM video_production_task WHERE account_id=$1) tasks,
      (SELECT count(*)::int FROM ai_run WHERE account_id=$1) runs,
      (SELECT count(*)::int FROM credits_consume_operation WHERE account_id=$1::uuid) reservations,
      (SELECT count(*)::int FROM video_shot_take t JOIN video_shot s ON s.id=t.shot_id
        JOIN video_storyboard b ON b.id=s.storyboard_id WHERE b.account_id=$1) takes`, [account])).rows[0]
  return row
}
async function login(page: Page, entry: Entry, email: string): Promise<void> {
  await page.goto(entry === 'ai' ? aiBaseURL : baseURL)
  await page.getByRole('button', { name: entry === 'ai' ? '登录 / 注册' : '登录', exact: true }).click()
  const dialog = page.getByRole('dialog')
  await dialog.locator('#login-email').fill(email)
  await dialog.locator('#login-password').fill(process.env.E2E_PASSWORD!)
  await dialog.locator('button[type="submit"]').click()
  await expect(page.getByTestId('auth-pill')).toBeVisible({ timeout: 30_000 })
}
async function enter(page: Page, origin: string, storyboardId: string, draftId?: string): Promise<string> {
  await page.goto(`${origin}/video-canvas?storyboard=${storyboardId}${draftId ? `&draft=${draftId}` : ''}`)
  await expect(page.locator(selector('canvas-node-1'))).toBeVisible({ timeout: 40_000 })
  await page.waitForURL(/draft=/)
  return new URL(page.url()).searchParams.get('draft')!
}
async function choose(page: Page, seq: number): Promise<void> {
  await expect(page.locator(selector('canvas-add-note'))).toBeEnabled({ timeout: 30_000 })
  const node = page.locator(selector(`canvas-node-${seq}`))
  await node.focus()
  await node.press('Enter')
  await expect(node).toHaveAttribute('aria-selected', 'true')
}
async function detail(page: Page): Promise<void> {
  await page.locator(selector('canvas-toggle-detail')).click()
}
async function delivery(page: Page): Promise<void> {
  await page.locator(selector('canvas-toggle-delivery')).click({ timeout: 60_000 })
  await expect(page.locator(selector('canvas-delivery'))).toBeVisible()
}
async function submit(page: Page, instruction: string): Promise<Response> {
  if (!(await page.locator(selector('canvas-assistant-panel')).isVisible())) await page.locator(selector('canvas-toggle-assistant')).click()
  await page.locator(selector('canvas-assistant-instruction')).fill(instruction)
  const response = page.waitForResponse(item => item.request().method() === 'POST'
    && new URL(item.url()).pathname === '/api/creation-assistant/canvas/plans', { timeout: 125_000 })
  await page.locator(selector('canvas-assistant-submit')).click()
  return response
}
async function apply(page: Page, plan: CanvasPlanResult): Promise<ApplyCanvasPlanResult> {
  await expect(page.locator(selector('canvas-assistant-status-ready'))).toBeVisible()
  const response = page.waitForResponse(item => item.request().method() === 'POST'
    && item.url().endsWith(`/canvas/plans/${plan.id}/apply`))
  await page.locator(selector('canvas-assistant-apply')).click()
  return data<ApplyCanvasPlanResult>(await response)
}
async function verifyRun(db: Pool, plan: CanvasPlanResult, instruction: string, snapshotId: string | null, selectedIds: string[]) {
  const modelCalls = (await readCanvasProviderCalls()).filter(call => call.instructionHash === sha(instruction))
  expect(modelCalls).toHaveLength(1)
  expect(modelCalls[0]).toMatchObject({ selectedIds, model: 'qwen-plus', maxTokens: 4096 })
  const runs = (await db.query('SELECT id::text,status,operation_id::text,context_snapshot_id::text FROM ai_run WHERE id=$1', [plan.runId])).rows
  expect(runs).toHaveLength(1)
  expect(runs[0]).toMatchObject({ status: 'completed', context_snapshot_id: snapshotId })
  expect((await db.query('SELECT operation_id FROM credits_consume_operation WHERE operation_id=$1', [runs[0].operation_id])).rows).toHaveLength(1)
  return { model: modelCalls[0], run: runs[0] }
}
async function saveShotSource(page: Page, storyboard: string, seq: number, mediaId: string, start: number, audio: string) {
  await choose(page, seq)
  await page.locator(selector('director-tab-property')).click()
  await page.locator(selector('director-own-media-select')).selectOption(mediaId)
  await page.locator(selector('canvas-source-kind-own')).check()
  await page.locator(selector('canvas-source-trim')).fill(String(start))
  await page.locator(selector(`canvas-source-audio-${audio}`)).check()
  const response = page.waitForResponse(item => item.request().method() === 'PATCH' && item.url().endsWith(`/storyboards/${storyboard}/sources`))
  await page.locator(selector('canvas-source-save')).click()
  const saved = await response
  await data(saved)
  expect(saved.request().postDataJSON().sources[0].source).toMatchObject({ mediaId, trimStartMs: start * 1000, trimEndMs: start * 1000 + 5000, audioMode: audio })
  await expect(page.locator(selector('canvas-source-trim-label'))).toContainText(`${start * 1000}–${start * 1000 + 5000} ms`)
  await expect(page.locator(selector('canvas-source-save'))).toBeEnabled()
}
async function screenshot(page: Page, name: string, theme: 'light' | 'dark') {
  await page.evaluate(async value => {
    localStorage.setItem('theme-preference', value); document.documentElement.dataset.theme = value
    await new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve())))
  }, theme)
  await expect(page.locator('html')).toHaveAttribute('data-theme', theme)
  const dir = process.env.E2E_SHOT_DIR || join(process.cwd(), 'test-artifacts/task-102/shots/real-e2e')
  await mkdir(dir, { recursive: true })
  await page.screenshot({ path: join(dir, `${test.info().project.name}-${name}-${theme}.png`), fullPage: true, animations: 'disabled' })
}
async function probe(file: string): Promise<Probe> {
  const result = await exec('ffprobe', ['-v', 'error', '-show_streams', '-show_format', '-of', 'json', file])
  return JSON.parse(result.stdout) as Probe
}
async function audioStats(file: string, start: number) {
  const result = await exec('ffmpeg', ['-v', 'error', '-ss', String(start), '-i', file, '-t', '1', '-vn', '-ac', '1', '-ar', '8000', '-f', 'f32le', 'pipe:1'], { encoding: 'buffer', maxBuffer: 1_000_000 })
  const samples = Array.from({ length: result.stdout.length / 4 }, (_, i) => result.stdout.readFloatLE(i * 4))
  const rms = Math.sqrt(samples.reduce((sum, sample) => sum + sample * sample, 0) / samples.length)
  let peak = 0; let frequency = 0
  for (let hz = 430; hz <= 450; hz++) {
    let real = 0; let imaginary = 0
    samples.forEach((sample, index) => { const angle = 2 * Math.PI * hz * index / 8000; real += sample * Math.cos(angle); imaginary += sample * Math.sin(angle) })
    const amplitude = Math.hypot(real, imaginary) / samples.length
    if (amplitude > peak) { peak = amplitude; frequency = hz }
  }
  return { rms, frequency }
}
async function frame(file: string, seconds: number, output: string) {
  await exec('ffmpeg', ['-v', 'error', '-y', '-ss', String(seconds), '-i', file, '-frames:v', '1', output])
  const result = await exec('ffmpeg', ['-v', 'error', '-i', output, '-vf', 'crop=64:64:240:100,scale=1:1', '-pix_fmt', 'rgb24', '-f', 'rawvideo', 'pipe:1'], { encoding: 'buffer' })
  return [...result.stdout.subarray(0, 3)]
}
async function verifyMedia(zip: Buffer, dir: string, task: TaskRow, mediaId: string, shotRows: ShotRow[]) {
  await mkdir(dir, { recursive: true })
  await writeFile(join(dir, 'bundle.zip'), zip)
  const manifest = JSON.parse(readZipEntry(zip, 'bundle/manifest.json').toString()) as { taskId: string; recomposeSeq: number; finalMediaId: string; shots: Array<{ shotId: string; seq: number; source: Record<string, unknown> }> }
  expect(manifest).toMatchObject({ taskId: task.id, recomposeSeq: task.recompose_seq, finalMediaId: task.final_media_id })
  expect(manifest.shots.map(shot => shot.shotId)).toEqual(shotRows.map(shot => shot.id))
  const srt = readZipEntry(zip, 'bundle/subtitle.srt').toString()
  for (const seq of [1, 3, 5]) expect(srt).toContain(`第${seq}镜头欢迎光临`)
  for (const seq of [2, 4]) expect(srt).not.toContain(`第${seq}镜头欢迎光临`)
  const master = join(dir, 'master.mp4')
  await writeFile(master, readZipEntry(zip, 'bundle/master.mp4'))
  const probes: Record<string, Probe> = { master: await probe(master) }
  const frames: Array<{ seq: number; seconds: number; rgb: number[] }> = []
  const audio: Record<string, Awaited<ReturnType<typeof audioStats>>> = {}
  let offset = 0
  for (const shot of shotRows) {
    const file = join(dir, `shot-${shot.seq}.mp4`)
    await writeFile(file, readZipEntry(zip, `bundle/segments/shot-${shot.seq}.mp4`))
    const info = await probe(file); probes[`shot${shot.seq}`] = info
    const video = info.streams.find(stream => stream.codec_type === 'video')!
    const expectedSeconds = [1, 5].includes(shot.seq) ? 2 : 5
    expect(Math.abs(Number(video.duration) - expectedSeconds)).toBeLessThanOrEqual(1 / 30 + 0.002)
    expect(info.streams.some(stream => stream.codec_type === 'audio')).toBe(true)
    if ([2, 3, 4].includes(shot.seq)) {
      const trim = shot.seq === 3 ? 1250 : 500
      const mode = shot.seq === 2 ? 'source' : shot.seq === 3 ? 'narration' : 'mute'
      expect(manifest.shots[shot.seq - 1].source).toMatchObject({ kind: 'own-media', mediaId, trimStartMs: trim, trimEndMs: trim + 5000, audioMode: mode })
      for (const [time, channel] of [[0.2, 0], [shot.seq === 3 ? 0.9 : 1.7, 2], [4.8, 1]]) {
        const rgb = await frame(master, offset + time, join(dir, `master-shot-${shot.seq}-${time}.png`))
        expect(rgb[channel]).toBeGreaterThan(180)
        expect(rgb.filter((_, index) => index !== channel).every(value => value < 45)).toBe(true)
        frames.push({ seq: shot.seq, seconds: offset + time, rgb })
      }
      audio[mode] = await audioStats(master, offset + 0.25)
    }
    offset += Number(video.duration)
  }
  const masterSeconds = Number(probes.master.streams.find(stream => stream.codec_type === 'video')!.duration)
  expect(Math.abs(masterSeconds - 19)).toBeLessThanOrEqual(5 / 30 + 0.002)
  expect(Math.abs(masterSeconds - offset)).toBeLessThanOrEqual(5 / 30 + 0.002)
  expect(audio.source.rms).toBeGreaterThan(0.06)
  expect(audio.source.rms).toBeLessThan(0.13)
  expect(Math.abs(audio.source.frequency - 440)).toBeLessThanOrEqual(2)
  expect(audio.narration.rms).toBeGreaterThan(audio.source.rms * 2)
  expect(audio.mute.rms).toBeLessThan(0.001)
  expect(walkZipEntries(zip).map(entry => entry.name)).toContain('bundle/audio/shot-3.wav')
  await writeFile(join(dir, 'media-evidence.json'), JSON.stringify({ manifest, probes, frames, audio, zipSha256: sha(zip), masterSha256: sha(await readFile(master)) }, null, 2))
  return { manifest, masterSeconds, audio }
}

test.beforeAll(async () => {
  if (!process.env.E2E_PASSWORD || !process.env.E2E_DATABASE_URL) throw new Error('Task #102 requires the isolated stack; missing prerequisites must fail')
  await readCanvasProviderCalls()
  await assertCanvasSubtitleRuntime()
})

for (const entry of ['grassland', 'ai'] as const) {
  test(`TC102-057/058/060 ${entry}: 登录→选镜计划→应用→独立方案→引用/来源→真实成片→交付导出`, async ({ browser }) => {
    test.setTimeout(900_000)
    const namespace = `${test.info().project.name}-${entry}-r${test.info().retry}`
    const fixture = await seedCanvasClosureFixture(baseURL, namespace, entry === 'grassland')
    const db = pool()
    const origin = entry === 'ai' ? aiBaseURL : baseURL
    const context = await browser.newContext({ baseURL: origin, viewport: { width: 1440, height: 900 }, reducedMotion: 'reduce' })
    const page = await context.newPage()
    const requests: Array<{ method: string; path: string; body: unknown }> = []
    page.on('request', request => {
      const path = new URL(request.url()).pathname
      if (['POST', 'PUT', 'PATCH'].includes(request.method()) && /\/api\/(creation-assistant\/canvas|creation-drafts|video-production)/.test(path)) {
        requests.push({ method: request.method(), path, body: request.postDataJSON() })
      }
    })
    try {
      await login(page, entry, fixture.accountA.email)
      const generatedMedia = await renderCanvasClosureMedia()
      const ownMediaId = await uploadCanvasMedia(baseURL, fixture.accountA.email, generatedMedia.video, 'video/mp4')
      const coverId = await uploadCanvasMedia(baseURL, fixture.accountA.email, generatedMedia.cover, 'image/png')
      const ownAsset = await data<{ id: string; mediaId: string }>(await page.request.post(`${origin}/api/content-assets`, { data: { libraryType: 'personal', mediaId: ownMediaId, category: 'scene', title: '三色实拍与440Hz原音' } }))
      expect(ownAsset.mediaId).toBe(ownMediaId)
      await data(await page.request.post(`${origin}/api/content-assets`, { data: { libraryType: 'personal', mediaId: coverId, category: 'scene', title: '本次交付封面' } }))
      const rootId = fixture.storyboardA.id
      const rootDraft = await enter(page, origin, rootId)
      await expect.poll(async () => (await canvas(db, rootDraft))?.document.nodes.length, { timeout: 15_000 }).toBeGreaterThanOrEqual(5)
      const rootProject = await project(db, rootDraft)
      expect(rootProject).toMatchObject({ source_type: fixture.snapshotId ? 'task' : 'independent', task_id: fixture.taskId, store_id: fixture.storeId, platform: 'douyin' })
      const initialCanvas = (await canvas(db, rootDraft))!
      await choose(page, 1)
      await page.locator(selector('canvas-node-1')).press('ArrowRight')
      await expect.poll(async () => (await canvas(db, rootDraft))!.revision).toBeGreaterThan(initialCanvas.revision)
      expect((await project(db, rootDraft)).version).toBe(rootProject.version)
      await choose(page, 2)
      const instruction = `[canvas-e2e:edit] ${namespace}\n只改第二镜，保留其他镜头和来源。`
      const response = await submit(page, instruction)
      const plan = await data<CanvasPlanResult>(response)
      const sent = response.request().postDataJSON() as CreateCanvasPlanRequest
      expect(sent.instruction).toBe(instruction)
      expect(sent.selectedNodeIds).toEqual([`shot:${fixture.storyboardA.shots[1].id}`])
      expect(sent.expectedCanvasRevision).toBe((await canvas(db, rootDraft))!.revision)
      await expect(page.locator(selector('canvas-plan-preview'))).toContainText('第2镜画面')
      await expect(page.locator(selector('canvas-plan-preview'))).toContainText(`C102 定向画面 ${sha(instruction).slice(0, 8)}`)
      const applied = await apply(page, plan)
      expect(applied.affectedShotIds).toEqual([fixture.storyboardA.shots[1].id])
      expect(await data(await page.request.post(`${origin}/api/creation-assistant/canvas/plans/${plan.id}/apply`, { data: {} }))).toEqual(applied)
      const edited = (await shots(db, rootId))[1]
      expect(edited.prompt).toBe(edited.visual)
      const runs = [await verifyRun(db, plan, instruction, fixture.snapshotId, [edited.id])]
      await screenshot(page, `c102-${entry}-applied`, 'dark')
      let variant: { storyboardId: string; draftId: string }
      if (entry === 'grassland') {
        await choose(page, 1)
        for (const seq of [2, 3, 4, 5]) await page.locator(selector(`canvas-select-shot-${seq}`)).check()
        const variantInstruction = `[canvas-e2e:variant] ${namespace} 从明确选择的五镜派生`
        const variantPlan = await data<CanvasPlanResult>(await submit(page, variantInstruction))
        const result = await apply(page, variantPlan)
        variant = result.variant!
        expect(variant).toBeTruthy()
        runs.push(await verifyRun(db, variantPlan, variantInstruction, fixture.snapshotId, (await shots(db, rootId)).map(shot => shot.id)))
      } else {
        await detail(page)
        await page.locator(selector('director-tab-variants')).click()
        await page.locator(selector('canvas-variant-title')).fill(`C102 ${entry} B方案`)
        await page.locator(selector('canvas-variant-all')).click()
        const created = page.waitForResponse(item => item.request().method() === 'POST' && item.url().endsWith(`/storyboards/${rootId}/variants`))
        await page.locator(selector('canvas-variant-create')).click()
        const result = await data<CreateVariantResult>(await created)
        variant = result.variant
        expect(new Set(Object.values(result.shotIdMap)).size).toBe(5)
      }
      await page.waitForURL(url => url.searchParams.get('storyboard') === variant.storyboardId)
      await expect(page.locator(selector('canvas-node-5'))).toBeVisible()
      const beforeB = await shots(db, rootId)
      const rootVersion = Number((await db.query('SELECT edit_version FROM video_storyboard WHERE id=$1', [rootId])).rows[0].edit_version)
      const bShots = await shots(db, variant.storyboardId)
      expect(bShots.every(shot => !beforeB.some(original => original.id === shot.id))).toBe(true)
      expect((await project(db, variant.draftId))).toMatchObject({ source_type: rootProject.source_type, task_id: fixture.taskId, store_id: fixture.storeId })
      await choose(page, 1)
      const bInstruction = `[canvas-e2e:edit] ${namespace} 只为B方案改第一镜`
      const bPlan = await data<CanvasPlanResult>(await submit(page, bInstruction))
      await apply(page, bPlan)
      runs.push(await verifyRun(db, bPlan, bInstruction, fixture.snapshotId, [bShots[0].id]))
      const beforeReferences = await counts(db, fixture.accountA.id)
      await page.locator(selector('canvas-add-note')).click()
      await page.locator(selector('canvas-note-input')).fill('三色实拍仅作当前方案参考；原音与配文分别验收。')
      await expect.poll(async () => (await canvas(db, variant.draftId))!.document.nodes.find(node => node.kind === 'note')?.text).toContain('三色实拍')
      await page.locator(selector('canvas-reference-target')).selectOption(`shot:${bShots[1].id}`)
      await page.locator(selector('canvas-reference-add-edge')).click()
      await expect.poll(async () => (await canvas(db, variant.draftId))!.document.edges.length).toBe(1)
      const note = (await canvas(db, variant.draftId))!.document.nodes.find(node => node.kind === 'note')!
      const noteNode = page.locator(selector(`canvas-ref-note-${note.id}`))
      await noteNode.focus(); await noteNode.press('ArrowRight')
      await expect.poll(async () => (await canvas(db, variant.draftId))!.document.nodes.find(node => node.id === note.id)!.x).not.toBe(note.x)
      if (!(await page.locator(selector('canvas-asset-list')).isVisible())) await page.locator(selector('canvas-toggle-assets')).click()
      await page.locator(selector(`canvas-asset-add-${ownMediaId}`)).click()
      await page.locator(selector('canvas-reference-target')).selectOption(`shot:${bShots[1].id}`)
      await page.locator(selector('canvas-reference-add-edge')).click()
      await expect.poll(async () => (await canvas(db, variant.draftId))!.document.edges.length).toBe(2)
      await page.locator(selector('canvas-asset-toggle')).click()
      expect(await counts(db, fixture.accountA.id)).toEqual(beforeReferences)
      const savedGraph = (await canvas(db, variant.draftId))!.document
      await screenshot(page, `c102-${entry}-references`, 'light')
      await page.reload()
      await expect(page.locator(selector('canvas-node-1'))).toBeVisible()
      await expect(page.locator(selector('canvas-note-text'))).toContainText('三色实拍')
      expect((await canvas(db, variant.draftId))!.document).toEqual(savedGraph)
      for (const [seq, start, audio] of [[2, 0.5, 'source'], [3, 1.25, 'narration'], [4, 0.5, 'mute']] as const) {
        await saveShotSource(page, variant.storyboardId, seq, ownMediaId, start, audio)
      }
      await choose(page, 2)
      await expect(page.locator(selector('canvas-source-trim'))).toHaveValue('0.5')
      await page.locator(selector('director-tab-takes')).click()
      await expect(page.locator(selector('canvas-take-own-source'))).toContainText('500–5500 ms')
      await expect(page.locator('[data-test^="canvas-take-reroll"]')).toHaveCount(0)
      await page.locator(selector('canvas-own-preview-load')).click()
      await page.locator(selector('canvas-own-preview')).scrollIntoViewIfNeeded()
      const preview = page.locator(selector('canvas-own-preview-video'))
      await expect.poll(() => preview.evaluate(video => (video as HTMLVideoElement).readyState >= 2
        && !(video as HTMLVideoElement).seeking), { timeout: 30_000 }).toBe(true)
      expect(await preview.evaluate(video => (video as HTMLVideoElement).currentTime)).toBeCloseTo(0.5, 2)
      expect(await preview.evaluate(video => (video as HTMLVideoElement).muted)).toBe(false)
      await preview.focus(); await preview.press('Space')
      await expect.poll(() => preview.evaluate(video => (video as HTMLVideoElement).currentTime)).toBeGreaterThan(0.6)
      await expect.poll(() => preview.evaluate(video => (video as HTMLVideoElement).paused
        && Math.abs((video as HTMLVideoElement).currentTime - 5.5) < 0.01), { timeout: 10_000 }).toBe(true)
      expect(await counts(db, fixture.accountA.id)).toEqual(beforeReferences)
      await choose(page, 1)
      const prepareInstruction = `[canvas-e2e:initial] ${namespace} 准备制作`
      const prepare = await data<CanvasPlanResult>(await submit(page, prepareInstruction))
      const prepared = await apply(page, prepare)
      expect(prepared.preparedGeneration).toEqual({ mode: 'initial', shotId: null })
      expect((await counts(db, fixture.accountA.id)).tasks).toBe(0)
      runs.push(await verifyRun(db, prepare, prepareInstruction, fixture.snapshotId, [bShots[0].id]))
      const creation = page.waitForResponse(item => item.request().method() === 'POST' && item.url().endsWith('/api/video-production/tasks'))
      await page.locator(selector('canvas-run-begin')).click()
      const task = await data<{ id: string }>(await creation)
      await expect(page.locator(selector('canvas-prepared-action'))).toHaveCount(0)
      for (const seq of [1, 5]) {
        await choose(page, seq)
        await page.locator(selector('director-tab-takes')).click()
        await page.locator(selector('canvas-take-adopt-2')).click({ timeout: 240_000 })
        await expect(page.locator(selector('canvas-take-saved'))).toContainText('候选 2', { timeout: 30_000 })
      }
      await page.locator(selector('canvas-run-compose')).click({ timeout: 60_000 })
      await expect.poll(async () => {
        const row = await taskRow(db, task.id)
        if (row.phase === 'failed') throw new Error(`Actual composition failed for ${task.id}`)
        return row.phase
      }, { timeout: 420_000, intervals: [2000, 3000] }).toBe('succeeded')
      await expect(page.locator(selector('canvas-run-phase'))).toHaveText('已完成', { timeout: 60_000 })
      const done = await taskRow(db, task.id)
      expect(done.actual_cost_cents).toBe(done.actual_duration_seconds! * done.unit_price_cents)
      const beforeDelivery = await counts(db, fixture.accountA.id)
      expect(beforeDelivery.tasks).toBe(1)
      expect(beforeDelivery.takes).toBe(4)
      expect(beforeDelivery.reservations).toBe(runs.length + 1)
      await delivery(page)
      await page.locator(selector('delivery-title')).fill(`C102 ${entry} 成片标题`)
      await page.locator(selector('delivery-topics')).fill('草场 实拍')
      await page.locator(selector('canvas-delivery-cover')).selectOption(coverId)
      const exportResponse = page.waitForResponse(item => item.request().method() === 'POST' && item.url().endsWith(`/creation-drafts/${variant.draftId}/exports`))
      const download = page.waitForEvent('download')
      await page.locator(selector('delivery-body')).fill(`C102 ${namespace} 最后配文：保留当前成片，不再生成。`)
      await page.locator(selector('delivery-export')).click()
      const exported = await data<CreationExportResult>(await exportResponse)
      const saved = await project(db, variant.draftId)
      expect(exported.version).toBe(saved.version)
      expect(exported.manifest.delivery).toEqual(saved.workspace_json.delivery)
      expect(exported.manifest.resultRefs).toEqual(expect.arrayContaining([expect.objectContaining({ productionTaskId: task.id, recomposeSeq: done.recompose_seq, id: done.final_media_id })]))
      const mediaDir = join(process.cwd(), 'test-artifacts/task-102/media', namespace)
      await mkdir(mediaDir, { recursive: true })
      await (await download).saveAs(join(mediaDir, 'delivery-manifest.json'))
      const bundleResponse = page.waitForResponse(item => new URL(item.url()).pathname.endsWith(`/tasks/${task.id}/export/bundle`))
      await page.locator(selector('canvas-delivery-export-bundle')).click()
      const bundle = await data<{ downloadUrl: string; recomposeSeq: number }>(await bundleResponse)
      expect(bundle.recomposeSeq).toBe(done.recompose_seq)
      const bytes = await page.request.get(bundle.downloadUrl)
      expect(bytes.status()).toBe(200)
      const media = await verifyMedia(Buffer.from(await bytes.body()), mediaDir, done, ownMediaId, await shots(db, variant.storyboardId))
      expect(await counts(db, fixture.accountA.id)).toEqual(beforeDelivery)
      expect(await taskRow(db, task.id)).toEqual(done)
      await screenshot(page, `c102-${entry}-delivery`, 'light')
      await screenshot(page, `c102-${entry}-delivery`, 'dark')
      expect(await shots(db, rootId)).toEqual(beforeB)
      expect(Number((await db.query('SELECT edit_version FROM video_storyboard WHERE id=$1', [rootId])).rows[0].edit_version)).toBe(rootVersion)
      expect((await db.query('SELECT id FROM video_production_task WHERE storyboard_id=$1', [rootId])).rows).toHaveLength(0)
      await detail(page)
      await page.locator(selector('director-tab-variants')).click()
      await page.locator(selector('canvas-variant-compare')).selectOption(rootId)
      await expect(page.locator(selector('canvas-variant-comparison'))).toContainText(rootProject.title)
      await expect(page.locator(selector('canvas-variant-comparison'))).toContainText('来源版本')
      await page.locator(selector(`canvas-variant-switch-${rootId}`)).click()
      await page.waitForURL(url => url.searchParams.get('storyboard') === rootId)
      await page.locator(selector('switch-quick-mode')).click()
      await page.locator(selector('open-canvas-mode')).click({ timeout: 30_000 })
      await page.waitForURL(url => url.pathname === '/video-canvas')
      await detail(page)
      await page.locator(selector('director-tab-variants')).click()
      await page.locator(selector(`canvas-variant-switch-${variant.storyboardId}`)).click()
      await page.waitForURL(url => url.searchParams.get('storyboard') === variant.storyboardId)
      await delivery(page)
      await expect(page.locator(selector('delivery-body'))).toHaveValue(`C102 ${namespace} 最后配文：保留当前成片，不再生成。`)
      const otherEntry = entry === 'ai' ? 'grassland' : 'ai'
      const other = await browser.newContext({ baseURL: otherEntry === 'ai' ? aiBaseURL : baseURL, viewport: { width: 1440, height: 900 } })
      const otherPage = await other.newPage()
      await login(otherPage, otherEntry, fixture.accountA.email)
      await enter(otherPage, otherEntry === 'ai' ? aiBaseURL : baseURL, variant.storyboardId, variant.draftId)
      await delivery(otherPage)
      await expect(otherPage.locator(selector('delivery-body'))).toHaveValue(`C102 ${namespace} 最后配文：保留当前成片，不再生成。`)
      await expect(otherPage.locator(selector('canvas-delivery-cover'))).toHaveValue(coverId)
      await screenshot(otherPage, `c102-${entry}-restored-in-${otherEntry}`, 'light')
      await other.close()
      const head = (await exec('git', ['rev-parse', 'HEAD'])).stdout.trim()
      await writeFile(join(mediaDir, 'closure-evidence.json'), JSON.stringify({ layer: 'real-ui-edge-java-model-database-media', head, specSha256: sha(await readFile(new URL(import.meta.url))), entry, rootId, rootDraft, variant, snapshotId: fixture.snapshotId, task: done, draftVersion: saved.version, runs, requests, counts: beforeDelivery, media }, null, 2))
    } finally { await context.close(); await db.end() }
  })
}

test('TC102-059: 400/409/502/504 分类、真实丢回包原键恢复与跨账号拒绝', async ({ browser }) => {
  test.setTimeout(420_000)
  const namespace = `${test.info().project.name}-errors-r${test.info().retry}`
  const fixture: Fixture = await seedCanvasClosureFixture(baseURL, namespace, false)
  const db = pool()
  const context = await browser.newContext({ baseURL, viewport: { width: 1440, height: 900 } })
  const page = await context.newPage()
  const outcomes: Array<Record<string, unknown>> = []
  try {
    await login(page, 'grassland', fixture.accountA.email)
    const storyboard = fixture.storyboardA.id
    const draft = await enter(page, baseURL, storyboard)
    await choose(page, 1)
    await page.locator(selector('canvas-toggle-assistant')).click()
    await expect(page.locator(selector('canvas-assistant-submit'))).toBeDisabled()
    await expect.poll(async () => (await canvas(db, draft))?.revision).toBeGreaterThan(0)
    const request = { operationId: randomUUID(), draftId: draft, storyboardId: storyboard,
      selectedNodeIds: [`shot:${fixture.storyboardA.shots[0].id}`], expectedEditVersion: 1,
      expectedCanvasRevision: (await canvas(db, draft))!.revision, instruction: '' }
    const before = await counts(db, fixture.accountA.id)
    await expectError(await page.request.post('/api/creation-assistant/canvas/plans', { data: request }), 400, 'CANVAS_INVALID_INPUT')
    expect(await counts(db, fixture.accountA.id)).toEqual(before)
    outcomes.push({ scenario: 'empty instruction contract', status: 400, code: 'CANVAS_INVALID_INPUT', newRuns: 0 })
    await db.query('UPDATE creation_canvas_document SET revision=revision+1 WHERE draft_id=$1', [draft])
    await expectError(await submit(page, `[canvas-e2e:edit] ${test.info().project.name} stale canvas`), 409, 'CANVAS_VERSION_CONFLICT')
    await expect(page.locator(selector('canvas-assistant-error'))).toHaveAttribute('data-error-code', 'CANVAS_VERSION_CONFLICT')
    expect(await counts(db, fixture.accountA.id)).toEqual(before)
    outcomes.push({ scenario: 'stale canvas', status: 409, code: 'CANVAS_VERSION_CONFLICT', newRuns: 0 })
    await page.reload()
    await choose(page, 1)
    for (const [mode, status, code] of [['invalid-json', 502, 'CANVAS_AGENT_INVALID_PLAN'], ['unknown-field', 502, 'CANVAS_AGENT_INVALID_PLAN'], ['delay', 504, 'CANVAS_AGENT_TIMEOUT']] as const) {
      const instruction = `[canvas-e2e:${mode}] ${namespace} 故障分类`
      const failed = await submit(page, instruction)
      await expectError(failed, status, code)
      await expect(page.locator(selector('canvas-assistant-error'))).toHaveAttribute('data-error-code', code)
      const original = failed.request().postDataJSON() as CreateCanvasPlanRequest
      const replay = page.waitForResponse(item => item.request().method() === 'POST' && item.url().endsWith('/canvas/plans'))
      await page.locator(selector('canvas-assistant-retry')).click()
      const response = await replay
      expect(response.request().postDataJSON()).toEqual(original)
      const persisted = await data<CanvasPlanResult>(response)
      expect(persisted).toMatchObject({ status: 'failed', errorCode: code })
      const calls = (await readCanvasProviderCalls()).filter(call => call.instructionHash === sha(instruction))
      expect(calls).toHaveLength(1)
      await expect.poll(async () => (await db.query('SELECT c.state FROM ai_run r JOIN credits_consume_operation c ON c.operation_id=r.operation_id::text WHERE r.id=$1', [persisted.runId])).rows[0]?.state, { timeout: 20_000 }).toBe('compensated')
      outcomes.push({ scenario: mode, status, code, operationId: original.operationId, planId: persisted.id, runId: persisted.runId, modelCalls: calls.length, replayStatus: response.status() })
    }
    const instruction = `[canvas-e2e:slow] ${namespace} 丢响应后只改第一镜`
    let lost: CreateCanvasPlanRequest | undefined
    await page.route('**/api/creation-assistant/canvas/plans', async route => {
      lost = route.request().postDataJSON() as CreateCanvasPlanRequest
      const real = await route.fetch({ timeout: 120_000 })
      expect(real.status(), await real.text()).toBe(200)
      await route.abort('failed')
    }, { times: 1 })
    await page.locator(selector('canvas-assistant-instruction')).fill(instruction)
    await page.locator(selector('canvas-assistant-submit')).click()
    await expect(page.locator(selector('canvas-assistant-retry'))).toBeVisible({ timeout: 30_000 })
    await page.locator(selector('canvas-assistant-instruction')).fill('这是稍后提交的新要求，不应混入原键')
    const replay = page.waitForResponse(item => item.request().method() === 'POST' && item.url().endsWith('/canvas/plans'))
    await page.locator(selector('canvas-assistant-retry')).click()
    const replayed = await replay
    expect(replayed.request().postDataJSON()).toEqual(lost)
    const ready = await data<CanvasPlanResult>(replayed)
    await verifyRun(db, ready, instruction, null, [fixture.storyboardA.shots[0].id])
    await page.route(`**/canvas/plans/${ready.id}/apply`, async route => {
      const real = await route.fetch()
      expect(real.status(), await real.text()).toBe(200)
      await route.abort('failed')
    }, { times: 1 })
    await page.locator(selector('canvas-assistant-apply')).click()
    await expect(page.locator(selector('canvas-assistant-recover-apply'))).toBeVisible()
    await page.locator(selector('canvas-assistant-recover-apply')).click()
    await expect(page.locator(selector('canvas-assistant-status-applied'))).toBeVisible()
    const savedShot = (await shots(db, storyboard))[0]
    expect(savedShot.visual).toBe(`C102 定向画面 ${sha(instruction).slice(0, 8)}`)
    expect(savedShot.prompt).toBe(savedShot.visual)
    expect(Number((await db.query('SELECT edit_version FROM video_storyboard WHERE id=$1', [storyboard])).rows[0].edit_version)).toBe(2)
    outcomes.push({ scenario: 'real response lost then original-key replay', planId: ready.id, operationId: lost!.operationId, modelCalls: 1, editVersion: 2 })
    await screenshot(page, 'c102-error-recovery', 'light')
    const intruder = await browser.newContext({ baseURL })
    const intruderPage = await intruder.newPage()
    await login(intruderPage, 'grassland', fixture.accountB.email)
    expect((await intruderPage.request.get(`/api/creation-assistant/canvas/plans/${ready.id}`)).status()).toBe(404)
    const rejected = intruderPage.waitForResponse(item => item.request().method() === 'POST' && item.url().endsWith(`/storyboards/${storyboard}/workspace`))
    await intruderPage.goto(`/video-canvas?storyboard=${storyboard}`)
    expect((await rejected).status()).toBe(404)
    await expect(intruderPage.locator(`${selector('canvas-binding-failed')},${selector('canvas-error')}`).first()).toBeVisible()
    await expect(intruderPage.locator(selector('canvas-node-1'))).toHaveCount(0)
    await intruder.close()
    const artifactDir = join(process.cwd(), 'test-artifacts/task-102/commands')
    await mkdir(artifactDir, { recursive: true })
    await writeFile(join(artifactDir, `c102-15-errors-${test.info().project.name}.json`), JSON.stringify({ layer: 'real-ui-and-explicit-contract-faults', outcomes, finalCounts: await counts(db, fixture.accountA.id) }, null, 2))
  } finally { await context.close(); await db.end() }
})
