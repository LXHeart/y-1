// @vitest-environment happy-dom
import { afterEach, describe, expect, test, vi } from 'vitest'
import { DOMWrapper, enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { defineComponent } from 'vue'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { createPinia } from 'pinia'
import { useAuthStore } from '../../stores/auth'
import VideoCanvasView from './VideoCanvasView.vue'
import type { CanvasDocument } from '../../types/video-canvas'

enableAutoUnmount(afterEach)
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); sessionStorage.clear() })
const json = (data: unknown, status = 200) => new Response(JSON.stringify(
  status >= 400 ? { success: false, error: data } : { success: true, data }),
{ status, headers: { 'Content-Type': 'application/json' } })
const board = (id: string) => ({ id, targetDurationSeconds: 10, resolution: '1080x1920',
  status: 'draft', editVersion: 1, grouping: null, shots: [1, 2].map(seq => ({
    id: `${id}-shot-${seq}`, seq, visual: `${id}画面${seq}`, narration: '旁白', plannedSeconds: 5,
    cameraMove: '固定机位', anchorImageIndex: 0, status: 'draft', takes: [],
  })) })
const project = (id: string) => ({ id: `draft-${id}`, title: `方案${id}`, sourceType: 'independent',
  capability: 'video', status: 'draft', version: 1, createdAt: '2026-09-12T00:00:00Z', updatedAt: '2026-09-12T00:00:00Z',
  workspace: { schemaVersion: 1, capability: 'video', inputs: { video: { storyboardId: id } } }, resultAssetIds: [], runIds: [] })
const binding = (id: string) => ({ project: project(id), storyboardId: id, productionTaskId: null, editVersion: 1 })
const canvas = (id: string): CanvasDocument => ({ id: `canvas-${id}`, draftId: `draft-${id}`, revision: 1,
  updatedAt: '2026-09-12T00:00:00Z', document: { schemaVersion: 1, storyboardId: id,
    viewport: { panX: 0, panY: 0, scale: 1 }, activeBranchId: null, edges: [],
    nodes: board(id).shots.map((shot, index) => ({ id: `shot:${shot.id}`, kind: 'shot', refType: 'shot',
      refId: shot.id, label: null, text: null, x: 777 + index * 320, y: 60 })) } })

async function open(overrides?: (url: string, init?: RequestInit) => Response | Promise<Response> | undefined, authenticated = true) {
  const calls: Array<{ url: string; init?: RequestInit }> = []
  const documents = { A: canvas('A'), B: canvas('B') }
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input); calls.push({ url, init })
    const override = overrides?.(url, init); if (override) return override
    const sb = url.match(/\/storyboards\/([^/]+)/)?.[1] ?? 'A'
    if (url.endsWith('/workspace')) return json(binding(sb))
    if (url.endsWith('/variants')) return json({ items: [] })
    if (/\/storyboards\/[^/]+$/.test(url)) return json(board(sb))
    if (url.endsWith('/canvas')) {
      const current = documents[url.includes('draft-B') ? 'B' : 'A']
      if (init?.method === 'PUT') {
        const body = JSON.parse(String(init.body))
        if (body.expectedRevision !== current.revision) return json('画布版本冲突', 409)
        current.document = body.document; current.revision += 1
      }
      return json(current)
    }
    if (url.includes('/api/creation-drafts/')) return json(project(url.includes('draft-B') ? 'B' : 'A'))
    if (url.includes('/content-assets')) return json({ items: [] })
    if (url.endsWith('/content')) return json({ editVersion: 2, updatedShotIds: ['A-shot-1'] })
    return json({})
  }))
  const pinia = createPinia(); const auth = useAuthStore(pinia)
  if (authenticated) auth.currentUser = { id: 'account-A', email: 'a@test.invalid', role: 'user', displayName: '测试账号' }
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/video-canvas', name: 'video-canvas', component: VideoCanvasView },
    { path: '/video-production', name: 'video-production', component: { template: '<div>快速模式</div>' } },
  ] })
  await router.push('/video-canvas?storyboard=A'); await router.isReady()
  const root = defineComponent({ components: { RouterView },
    template: '<RouterView v-slot="{ Component }"><KeepAlive><component :is="Component" /></KeepAlive></RouterView>' })
  const wrapper = mount(root, { attachTo: document.body, global: { plugins: [pinia, router] } })
  await flushPromises()
  return { wrapper, router, auth, calls, documents }
}

describe('C102 actual canvas page assembly', () => {
  test('mobile starts with a list; selecting multiple shots opens no drawer and a failed close keeps focused input', async () => {
    vi.spyOn(window, 'matchMedia').mockImplementation(query => ({ media: query, matches: query.includes('max-width: 767px'),
      addEventListener: vi.fn(), removeEventListener: vi.fn(), addListener: vi.fn(), removeListener: vi.fn(), dispatchEvent: vi.fn(), onchange: null }))
    let failSave = true
    const f = await open((url, init) => url.endsWith('/content') && init?.method === 'PUT'
      ? failSave ? json('保存失败', 500) : json({ editVersion: 2 }) : undefined)
    expect(f.wrapper.find('[data-test="canvas-shot-list"]').exists()).toBe(true)
    expect(f.wrapper.find('[data-test="canvas-board"]').exists()).toBe(false)
    await f.wrapper.get('[data-test="canvas-list-check-1"]').setValue(true); await flushPromises()
    await f.wrapper.get('[data-test="canvas-list-check-2"]').setValue(true); await flushPromises()
    expect(document.querySelector('[role="dialog"]')).toBeNull()
    const trigger = f.wrapper.get('[data-test="canvas-list-edit-2"]'); (trigger.element as HTMLElement).focus()
    await trigger.trigger('click'); await flushPromises()
    const page = new DOMWrapper(document.body)
    expect(document.querySelectorAll('[role="dialog"]')).toHaveLength(1)
    await page.get('[data-test="director-visual"]').setValue('关闭失败时保留的内容')
    await page.get('[data-action="close-modal"]').trigger('click'); await flushPromises()
    expect(page.get('[data-test="director-visual"]').element).toHaveProperty('value', '关闭失败时保留的内容')
    expect(document.activeElement?.getAttribute('data-test')).toBe('canvas-panel-close-error')
    failSave = false; await page.get('[data-action="close-modal"]').trigger('click'); await flushPromises()
    expect(document.querySelector('[role="dialog"]')).toBeNull()
    expect(document.activeElement).toBe(trigger.element)
    expect(f.wrapper.get('[data-test="canvas-list-shot-2"]').text()).toContain('关闭失败时保留的内容')
  })

  test('desktop replaces the detail content for AI and keeps selection and pending edits in the project session', async () => {
    const f = await open()
    await f.wrapper.get('[data-test="canvas-node-1"]').trigger('pointerdown', { button: 0 }); await flushPromises()
    await f.wrapper.get('[data-test="director-visual"]').setValue('打开助手前保存')
    await f.wrapper.get('[data-test="canvas-toggle-assistant"]').trigger('click'); await flushPromises()
    expect(f.wrapper.findAll('[data-test="canvas-detail-panel"]')).toHaveLength(1)
    expect(f.wrapper.find('[data-test="director-panel"]').exists()).toBe(false)
    expect(f.wrapper.get('[data-test="canvas-assistant-scope"]').text()).toContain('1 个选中节点')
    expect(f.calls.filter(call => call.url.endsWith('/content'))).toHaveLength(1)
    await f.wrapper.get('[data-test="canvas-toggle-assistant"]').trigger('click'); await flushPromises()
    expect(f.wrapper.get('[data-test="director-visual"]').element).toHaveProperty('value', '打开助手前保存')
  })

  test('variant creation flushes actual edited fields and original-request retry navigates to the returned project', async () => {
    const requests: string[] = []; let fail = true
    const f = await open((url, init) => {
      if (!url.endsWith('/variants') || init?.method !== 'POST') return undefined
      requests.push(String(init.body))
      return fail ? json('创建响应丢失', 502) : json({ variant: { storyboardId: 'B', draftId: 'draft-B', title: '新的镜头方案' }, project: project('B'), shotIdMap: {} })
    })
    await f.wrapper.get('[data-test="canvas-node-1"]').trigger('pointerdown', { button: 0 }); await flushPromises()
    await f.wrapper.get('[data-test="director-visual"]').setValue('创建方案前待保存的画面')
    await f.wrapper.get('[data-test="director-tab-variants"]').trigger('click')
    await f.wrapper.get('[data-test="canvas-variant-title"]').setValue('新的镜头方案')
    await f.wrapper.get('[data-test="canvas-variant-shot-2"]').setValue(true)
    await f.wrapper.get('.variant-create-form').trigger('submit'); await flushPromises()
    expect(f.router.currentRoute.value.query.storyboard).toBe('A')
    expect(JSON.parse(requests[0]!)).toMatchObject({ expectedEditVersion: 2, expectedDraftVersion: 1, title: '新的镜头方案', shotIds: ['A-shot-2'] })
    const content = f.calls.findIndex(call => call.url.endsWith('/content'))
    expect(content).toBeGreaterThan(-1)
    expect(content).toBeLessThan(f.calls.findIndex(call => call.url.endsWith('/variants') && call.init?.method === 'POST'))
    fail = false
    await f.wrapper.get('[data-test="canvas-variant-retry"]').trigger('click'); await flushPromises()
    expect(requests).toHaveLength(2); expect(requests[1]).toBe(requests[0])
    expect(f.router.currentRoute.value.query).toMatchObject({ storyboard: 'B', draft: 'draft-B' })
  })

  test('A to B to A restores each own source and millisecond trim; a failed source save prevents leaving', async () => {
    vi.spyOn(HTMLMediaElement.prototype, 'load').mockImplementation(() => {})
    const boards = { A: board('A'), B: board('B') }
    const sources: Record<string, { kind: 'own-media'; mediaId: string; trimStartMs: number; trimEndMs: number; audioMode: 'source' | 'mute' }> = {
      'A-shot-1': { kind: 'own-media', mediaId: 'media-A1', trimStartMs: 500, trimEndMs: 5500, audioMode: 'source' },
      'A-shot-2': { kind: 'own-media', mediaId: 'media-A2', trimStartMs: 1250, trimEndMs: 6250, audioMode: 'mute' },
      'B-shot-1': { kind: 'own-media', mediaId: 'media-B1', trimStartMs: 750, trimEndMs: 5750, audioMode: 'mute' },
    }
    let failSave = false
    const f = await open((url, init) => {
      const key = url.includes('/storyboards/B') ? 'B' : 'A'
      if (/\/storyboards\/[AB]$/.test(url)) return json({ ...boards[key], shots: boards[key].shots.map(shot => ({ ...shot, source: sources[shot.id] ?? { kind: 'generated' } })) })
      if (url.endsWith('/sources') && init?.method === 'PATCH') {
        if (failSave) return json('来源保存失败', 500)
        const body = JSON.parse(String(init.body)); expect(body.expectedEditVersion).toBe(boards[key].editVersion)
        for (const item of body.sources) sources[item.shotId] = item.source
        return json({ editVersion: ++boards[key].editVersion })
      }
      if (url.endsWith('/download-url')) return json({ downloadUrl: '' })
      if (url.includes('/content-assets')) return json({ items: ['A1', 'A2', 'B1'].map(id => ({ id: 'asset-' + id, mediaId: 'media-' + id, title: '素材' + id, mimeType: 'video/mp4', status: 'active' })) })
      return undefined
    })
    await f.wrapper.get('[data-test="canvas-node-1"]').trigger('pointerdown', { button: 0 }); await flushPromises()
    expect(f.wrapper.get('[data-test="director-own-media-select"]').element).toHaveProperty('value', 'media-A1')
    expect(f.wrapper.get('[data-test="canvas-source-trim"]').element).toHaveProperty('value', '0.5')
    await f.wrapper.get('[data-test="canvas-node-2"]').trigger('pointerdown', { button: 0 }); await flushPromises()
    expect(f.wrapper.get('[data-test="director-own-media-select"]').element).toHaveProperty('value', 'media-A2')
    expect(f.wrapper.get('[data-test="canvas-source-trim"]').element).toHaveProperty('value', '1.25')
    expect(f.wrapper.get('[data-test="canvas-source-audio-mute"]').element).toHaveProperty('checked', true)
    await f.router.push('/video-canvas?storyboard=B&draft=draft-B'); await flushPromises()
    await f.wrapper.get('[data-test="canvas-node-1"]').trigger('pointerdown', { button: 0 }); await flushPromises()
    expect(f.wrapper.get('[data-test="director-own-media-select"]').element).toHaveProperty('value', 'media-B1')
    expect(f.wrapper.get('[data-test="canvas-source-trim"]').element).toHaveProperty('value', '0.75')
    failSave = true; await f.wrapper.get('[data-test="canvas-source-trim"]').setValue(1.125)
    await f.router.push('/video-canvas?storyboard=A&draft=draft-A'); await flushPromises()
    expect(f.router.currentRoute.value.query.storyboard).toBe('B')
    expect(f.wrapper.get('[data-test="canvas-source-trim"]').element).toHaveProperty('value', '1.125')
    failSave = false; await f.router.push('/video-canvas?storyboard=A&draft=draft-A'); await flushPromises()
    await f.wrapper.get('[data-test="canvas-node-1"]').trigger('pointerdown', { button: 0 }); await flushPromises()
    expect(f.wrapper.get('[data-test="director-own-media-select"]').element).toHaveProperty('value', 'media-A1')
    expect(f.wrapper.get('[data-test="canvas-source-trim"]').element).toHaveProperty('value', '0.5')
    expect(sources['B-shot-1']).toMatchObject({ mediaId: 'media-B1', trimStartMs: 1125, trimEndMs: 6125 })
    expect(f.calls.filter(call => call.url.includes('/tasks') && call.init?.method === 'POST')).toHaveLength(0)
  })

  test('references survive note editing, edge changes, movement, reload and media replacement without generation', async () => {
    let revoked = false
    const f = await open(url => url.includes('/content-assets') ? json({ items: [
      { id: 'asset-1', mediaId: 'media-1', title: '原参考', status: revoked ? 'revoked' : 'active', mimeType: 'image/png' },
      { id: 'asset-2', mediaId: 'media-2', title: '替换参考', status: 'active', mimeType: 'image/png' },
    ] }) : undefined)
    await f.wrapper.get('[data-test="canvas-add-note"]').trigger('click'); await flushPromises()
    await f.wrapper.get('[data-test="canvas-note-input"]').setValue('记住品牌与原声')
    await f.wrapper.get('[data-test="canvas-reference-target"]').setValue('shot:A-shot-1')
    await f.wrapper.get('[data-test="canvas-reference-add-edge"]').trigger('click')
    const note = f.wrapper.get('[data-test^="canvas-ref-note-"]')
    const initialX = Number.parseFloat((note.element as HTMLElement).style.left)
    await note.trigger('keydown', { key: 'ArrowRight' }); await flushPromises()
    await f.wrapper.get('[data-test="canvas-asset-add-media-1"]').trigger('click'); await flushPromises()
    const mediaNode = f.documents.A.document.nodes.find(node => node.kind === 'media')!
    const originalIdentity = { id: mediaNode.id, x: mediaNode.x, y: mediaNode.y }
    await f.wrapper.get('[data-test="canvas-reference-target"]').setValue('shot:A-shot-2')
    await f.wrapper.get('[data-test="canvas-reference-add-edge"]').trigger('click')
    await f.router.push('/video-production'); await flushPromises()
    expect(f.documents.A.document.nodes.find(node => node.kind === 'note')).toMatchObject({ text: '记住品牌与原声', x: initialX + 8 })
    expect(f.documents.A.document.edges).toHaveLength(2)
    revoked = true
    await f.router.push('/video-canvas?storyboard=A&draft=draft-A'); await flushPromises()
    await f.wrapper.get('[data-test="canvas-asset-reload"]').trigger('click'); await flushPromises()
    await f.wrapper.get('[data-test="canvas-ref-reselect"]').trigger('click'); await flushPromises()
    expect(f.wrapper.get('[data-test="canvas-reference-add-edge"]').attributes('disabled')).toBeDefined()
    await f.wrapper.get('[data-test="canvas-reference-replacement"]').setValue('media-2')
    await f.wrapper.get('[data-test="canvas-reference-replace"]').trigger('click'); await flushPromises()
    await f.wrapper.get('[data-test="canvas-reference-remove-edge"]').trigger('click')
    await f.router.push('/video-production'); await flushPromises()
    expect(f.documents.A.document.nodes.find(node => node.kind === 'media')).toMatchObject({ ...originalIdentity, refId: 'media-2' })
    expect(f.documents.A.document.edges).toHaveLength(1)
    expect(f.documents.A.document.edges[0]?.kind).toBe('reference')
    expect(f.calls.filter(call => ['POST', 'PUT', 'DELETE'].includes(call.init?.method ?? '')
      && !call.url.endsWith('/canvas') && !call.url.endsWith('/workspace'))).toHaveLength(0)
  })
  test('reopened committed project edits delivery and exports the saved video version; failed saves block download', async () => {
    let current = { ...project('A'), platform: 'douyin', workspace: { ...project('A').workspace,
      inputs: { video: { storyboardId: 'A', productionTaskId: 'task-A' } },
      delivery: { version: 1, platform: 'douyin', contentForm: 'video', titleOrOpening: '旧标题' } } }
    let failSave = false
    const liveTask = { id: 'task-A', storyboardId: 'A', phase: 'succeeded', mode: 'video', progress: 100, selection: {}, recommended: {},
      targetDurationSeconds: 10, unitPriceCents: 1, estimatedCostCents: 10, actualCostCents: 10, actualDurationSeconds: 10,
      finalMediaId: 'master-A', srtMediaId: null, recomposeSeq: 2, finalUrl: '/media/master-A', subtitleUrl: null, shots: [] }
    Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:fixture'), revokeObjectURL: vi.fn() })
    const f = await open((url, init) => {
      if (url.endsWith('/A/workspace')) return json({ ...binding('A'), project: current, productionTaskId: 'task-A' })
      if (url.endsWith('/storyboards/A')) return json({ ...board('A'), status: 'committed' })
      if (url.endsWith('/tasks/task-A')) return json(liveTask)
      if (url.endsWith('/creation-drafts/draft-A')) {
        if (init?.method === 'PUT') {
          if (failSave) return json('交付保存失败', 500)
          current = { ...current, ...JSON.parse(String(init.body)), version: current.version + 1 }
        }
        return json(current)
      }
      if (url.endsWith('/exports')) return json({ draftId: 'draft-A', version: current.version, manifest: { resultRefs: (current.workspace as Record<string, unknown>).resultRefs }, downloads: [] })
      return undefined
    })
    await f.wrapper.get('[data-test="canvas-toggle-delivery"]').trigger('click'); await flushPromises()
    expect(f.wrapper.get('[data-test="delivery-title"]').attributes('disabled')).toBeUndefined()
    await f.wrapper.get('[data-test="delivery-title"]').setValue('成片后的新标题')
    await f.wrapper.get('[data-test="delivery-body"]').setValue('成片后的发布配文')
    await f.wrapper.get('[data-test="delivery-export"]').trigger('click'); await flushPromises()
    const exports = f.calls.filter(call => call.url.endsWith('/exports'))
    expect(exports).toHaveLength(1)
    expect(JSON.parse(String(exports[0]?.init?.body)).version).toBe(current.version)
    expect(current.workspace.delivery.titleOrOpening).toBe('成片后的新标题')
    expect(current.workspace.inputs.video.storyboardId).toBe('A')
    expect((current.workspace as Record<string, unknown>).resultRefs).toEqual([{ id: 'master-A', refType: 'media', role: 'video', storyboardId: 'A', productionTaskId: 'task-A', recomposeSeq: 2 }])
    failSave = true
    await f.wrapper.get('[data-test="delivery-title"]').setValue('必须保留的未保存标题')
    await f.wrapper.get('[data-test="delivery-export"]').trigger('click'); await flushPromises()
    expect(f.calls.filter(call => call.url.endsWith('/exports'))).toHaveLength(1)
    expect(f.wrapper.get('[data-test="delivery-export-error"]').text()).toContain('尚未保存')
    expect((f.wrapper.get('[data-test="delivery-title"]').element as HTMLInputElement).value).toBe('必须保留的未保存标题')
    expect(f.calls.filter(call => call.url.endsWith('/video-production/tasks') && call.init?.method === 'POST')).toHaveLength(0)
  })
  test('KeepAlive ignores a late plan and exposes original-request recovery when returning', async () => {
    let finish!: (response: Response) => void
    const requests: string[] = []
    const f = await open((url, init) => {
      if (!url.endsWith('/canvas/plans')) return undefined
      requests.push(String(init?.body))
      return new Promise<Response>(resolve => { finish = resolve })
    })
    await f.wrapper.get('[data-test="canvas-node-2"]').trigger('pointerdown', { button: 0 }); await flushPromises()
    await f.wrapper.get('[data-test="canvas-toggle-assistant"]').trigger('click'); await flushPromises()
    await f.wrapper.get('[data-test="canvas-assistant-instruction"]').setValue('原始要求')
    await f.wrapper.get('[data-test="canvas-assistant-submit"]').trigger('click'); await flushPromises()
    await f.router.push('/video-production'); await flushPromises()
    finish(json({ id: 'old-plan', storyboardId: 'A', draftId: 'draft-A', status: 'preparing' })); await flushPromises()
    await f.router.push('/video-canvas?storyboard=A&draft=draft-A'); await flushPromises()
    expect(f.wrapper.find('[data-test="canvas-assistant-status-preparing"]').exists()).toBe(false)
    expect(f.wrapper.find('[data-test="canvas-assistant-retry"]').exists()).toBe(true)
    await f.wrapper.get('[data-test="canvas-assistant-retry"]').trigger('click'); await flushPromises()
    expect(requests).toHaveLength(2); expect(requests[1]).toBe(requests[0])
    finish(json({ id: 'old-plan', storyboardId: 'A', draftId: 'draft-A', status: 'failed', errorCode: 'CANVAS_AGENT_INVALID_PLAN' })); await flushPromises()
  })
  test('pointer selection and multiline instruction reach the real assistant request and show baseline differences', async () => {
    const f = await open((url, init) => url.endsWith('/canvas/plans') && init?.method === 'POST'
      ? json({ id: 'plan-A', status: 'ready', draftId: 'draft-A', storyboardId: 'A', baseDraftVersion: 1, baseEditVersion: 1,
        baseCanvasRevision: 1, summary: '修改第二镜', clarification: null, errorCode: null, runId: 'run-A',
        expiresAt: new Date(Date.now() + 1800000).toISOString(), action: { kind: 'edit', actions: [{ kind: 'update-shot', patch: { shotId: 'A-shot-2', visual: '新画面二' } }] } }) : undefined)
    await f.wrapper.get('[data-test="canvas-node-2"]').trigger('pointerdown', { button: 0 }); await flushPromises()
    await f.wrapper.get('[data-test="canvas-toggle-assistant"]').trigger('click'); await flushPromises()
    await f.wrapper.get('[data-test="canvas-assistant-instruction"]').setValue('只修改第二镜\n保留品牌名')
    await f.wrapper.get('[data-test="canvas-assistant-instruction"]').trigger('keydown', { key: 'Enter' }); await flushPromises()
    expect(f.calls.filter(call => call.url.endsWith('/canvas/plans'))).toHaveLength(0)
    await f.wrapper.get('[data-test="canvas-assistant-instruction"]').trigger('keydown', { key: 'Enter', ctrlKey: true }); await flushPromises()
    const calls = f.calls.filter(call => call.url.endsWith('/canvas/plans'))
    expect(calls).toHaveLength(1)
    expect(JSON.parse(String(calls[0]?.init?.body))).toMatchObject({ selectedNodeIds: ['shot:A-shot-2'], instruction: '只修改第二镜\n保留品牌名' })
    expect(f.wrapper.get('[data-test="canvas-plan-diff-0"]').text()).toContain('A画面2 → 新画面二')
    await f.wrapper.get('[data-test="canvas-select-shot-1"]').setValue(true); await flushPromises()
    expect(f.wrapper.get('[data-test="canvas-assistant-scope"]').text()).toContain('2 个选中节点')
    expect(f.wrapper.get('[data-test="canvas-plan-scope"]').text()).not.toContain('镜头 1')
  })
  test('independent x=777 restores and keyboard/undo persist only the canvas document', async () => {
    const docs = { A: canvas('A'), B: canvas('B') }
    const f = await open((url, init) => {
      if (!url.endsWith('/canvas')) return undefined
      const id = url.includes('draft-B') ? 'B' : 'A'
      if (init?.method === 'PUT') {
        const body = JSON.parse(String(init.body)); docs[id].document = body.document; docs[id].revision += 1
      }
      return json(docs[id])
    })
    const node = f.wrapper.get('[data-test="canvas-node-1"]')
    expect((node.element as HTMLElement).style.left).toBe('777px')
    await node.trigger('pointerdown', { button: 0 }); await flushPromises()
    await node.trigger('keydown', { key: 'ArrowRight' }); await flushPromises()
    expect((node.element as HTMLElement).style.left).toBe('785px')
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'z', ctrlKey: true, bubbles: true })); await flushPromises()
    expect((node.element as HTMLElement).style.left).toBe('777px')
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'z', ctrlKey: true, shiftKey: true, bubbles: true })); await flushPromises()
    await f.router.push('/video-canvas?storyboard=B'); await flushPromises()
    const writes = f.calls.filter(call => call.init?.method === 'PUT')
    // Selecting first flushes the missing brief projection; movement/undo then saves its own document revision.
    expect(writes).toHaveLength(2)
    expect(writes.map(write => write.url)).toEqual(['/api/creation-drafts/draft-A/canvas', '/api/creation-drafts/draft-A/canvas'])
    expect(JSON.parse(String(writes[0]?.init?.body)).document.nodes.find((node: { kind: string }) => node.kind === 'brief')).toBeDefined()
    expect(JSON.parse(String(writes[0]?.init?.body)).document.nodes[0].x).toBe(777)
    expect(docs.A.document.nodes[0]?.x).toBe(785)
    await f.router.push('/video-canvas?storyboard=A'); await flushPromises()
    expect((f.wrapper.get('[data-test="canvas-node-1"]').element as HTMLElement).style.left).toBe('785px')
  })

  test('slow A binding cannot populate fast B or change B URL', async () => {
    let finish!: (response: Response) => void
    const f = await open(url => url.includes('/A/workspace')
      ? new Promise<Response>(resolve => { finish = resolve }) : undefined)
    await f.router.push('/video-canvas?storyboard=B'); await flushPromises()
    expect(f.wrapper.text()).toContain('B画面1')
    finish(json(binding('A'))); await flushPromises()
    expect(f.wrapper.text()).not.toContain('A画面1')
    expect(f.router.currentRoute.value.query).toMatchObject({ storyboard: 'B', draft: 'draft-B' })
  })

  test('failed content save stops mode switch and browser navigation, preserving input and focus', async () => {
    let fail = true
    const f = await open((url, init) => url.endsWith('/content') && init?.method === 'PUT'
      ? fail ? json('保存失败', 500) : json({ editVersion: 2 }) : undefined)
    await f.wrapper.get('[data-test="canvas-node-1"]').trigger('pointerdown', { button: 0 })
    await flushPromises()
    await f.wrapper.get('[data-test="director-visual"]').setValue('不能丢失的本地内容')
    await f.wrapper.get('[data-test="switch-quick-mode"]').trigger('click'); await flushPromises()
    expect(f.router.currentRoute.value.name).toBe('video-canvas')
    expect((f.wrapper.get('[data-test="director-visual"]').element as HTMLTextAreaElement).value).toBe('不能丢失的本地内容')
    expect(document.activeElement).toBe(f.wrapper.get('[data-test="canvas-save-error"]').element)
    await f.router.push('/video-canvas?storyboard=B'); await flushPromises()
    expect(f.router.currentRoute.value.query.storyboard).toBe('A')
    fail = false
    await f.wrapper.get('[data-test="switch-quick-mode"]').trigger('click'); await flushPromises()
    expect(f.router.currentRoute.value.name).toBe('video-production')
  })

  test('unfinished own-media selection blocks switching shots and leaving', async () => {
    const f = await open()
    await f.wrapper.get('[data-test="canvas-node-1"]').trigger('pointerdown', { button: 0 }); await flushPromises()
    await f.wrapper.get('[data-test="canvas-source-kind-own"]').setValue()
    await f.wrapper.get('[data-test="canvas-node-2"]').trigger('pointerdown', { button: 0 }); await flushPromises()
    expect((f.wrapper.get('[data-test="director-visual"]').element as HTMLTextAreaElement).value).toBe('A画面1')
    expect(f.wrapper.get('[data-test="canvas-save-error"]').text()).toContain('未保存')
    await f.router.push('/video-production'); await flushPromises()
    expect(f.router.currentRoute.value.name).toBe('video-canvas')
  })

  test('anonymous visitors never create or read a private project', async () => {
    const f = await open(undefined, false)
    expect(f.wrapper.get('[data-test="canvas-login-required"]').text()).toContain('登录')
    expect(f.calls.filter(call => call.url.includes('/workspace') || call.url.includes('/canvas'))).toHaveLength(0)
  })
})
