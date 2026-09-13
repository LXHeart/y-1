import type { Page, Route } from '@playwright/test'
/**
 * 任务书 #101 C101-13：创作工作台 e2e 隔离 fixtures（§12.1）。
 *
 * M1 阶段的来源→计划→生成→采用「业务态」由浏览器网络 fixture 模拟（§12：UI 网络
 * fixture 覆盖 6 张图的慢、错、unknown 和交互状态）；完整生产链（DB/调用次数/媒体
 * 字节）由后端 IT（VisualJobIT/VisualExecutionIT/VisualAdoptionIT）独立验证，
 * 不把 UI mock 当作那部分证据。
 *
 * 真实账号走 E2E_DATABASE_URL 指向的隔离栈（与 video-canvas 同约定：task101-*
 * @test.invalid、口令经 E2E_PASSWORD 注入、不触碰生产库）。
 */

export const ACCOUNT_A_EMAIL = 'task101-a@test.invalid'
export const ACCOUNT_B_EMAIL = 'task101-b@test.invalid'

/** 六图计划的标准条目（与后端 IT 的来源样稿同密度）。 */
export const SOURCE_TEXT = [
  '门店三年，人均 68 元。',
  '招牌面 32 元，日销两百碗。',
  '小菜 12 元一份。',
  '晚饭人均 45 元。',
  '加菜另算 10 元。',
  '会员再省 8 元。',
].join('\n\n')

export interface StudioFixturePlanItem {
  itemId: string
  cardId: string
  position: number
  role: 'cover' | 'content' | 'summary'
  title: string
}

export function defaultPlanItems(count = 6): StudioFixturePlanItem[] {
  return Array.from({ length: count }, (_, index) => ({
    itemId: `item-${index + 1}`,
    cardId: `card-${index + 1}`,
    position: index + 1,
    role: index === 0 ? 'cover' : 'content',
    title: `第${index + 1}卡`,
  }))
}

export interface StudioJobStateItem {
  itemId: string
  state: 'waiting_anchor' | 'queued' | 'prepared' | 'dispatching' | 'generated_unsettled'
    | 'succeeded' | 'failed' | 'cancelled' | 'unknown'
  artifactId?: string
  deliveryMediaId?: string
}

export interface StudioJobSnapshot {
  id: string
  state: 'queued' | 'running' | 'succeeded' | 'partial' | 'failed' | 'cancelled' | 'unknown'
  version: number
  items: StudioJobStateItem[]
}

/** 成功项附带 artifact（含 original/deliveryMediaRef——候选预览与采用都依赖它）。 */
function artifactOf(item: { itemId: string; artifactId?: string; deliveryMediaId?: string }): Record<string, unknown> {
  const artifactId = item.artifactId ?? `art-${item.itemId}`
  const deliveryMediaId = item.deliveryMediaId ?? `media-del-${item.itemId}`
  return {
    id: artifactId,
    itemId: item.itemId,
    attemptId: `attempt-${item.itemId}`,
    plan: { id: 'plan-e2e-1', revision: 1 },
    originalMediaRef: { id: `media-orig-${item.itemId}`, refType: 'media' },
    deliveryMediaRef: { id: deliveryMediaId, refType: 'media' },
    runId: `run-${item.itemId}`,
    width: 1080,
    height: 1440,
    contentHash: 'f'.repeat(64),
    anchorArtifactId: null,
    createdAt: '2026-09-13T00:00:00Z',
  }
}

export function jobSnapshot(id: string, state: StudioJobSnapshot['state'],
  items: StudioJobStateItem[], version = 1): Record<string, unknown> {
  return {
    id,
    requestId: `req-${id}`,
    draftId: 'draft-e2e-1',
    plan: { id: 'plan-e2e-1', revision: 1 },
    state,
    version,
    quoteId: 'quote-e2e-1',
    cancelRequested: false,
    createdAt: '2026-09-13T00:00:00Z',
    updatedAt: '2026-09-13T00:00:30Z',
    items: items.map((item) => ({
      attemptId: `attempt-${item.itemId}`,
      itemId: item.itemId,
      position: Number(item.itemId.split('-')[1]),
      state: item.state,
      runId: item.state === 'succeeded' ? `run-${item.itemId}` : null,
      error: item.state === 'failed'
        ? { code: 'PROVIDER_FAILED', message: '生成失败，详见错误码' }
        : null,
      artifact: item.state === 'succeeded' ? artifactOf(item) : null,
    })),
  }
}

export const QUOTE_BODY = {
  id: 'quote-e2e-1',
  plan: { id: 'plan-e2e-1', revision: 1 },
  selectedItemIds: [] as string[],
  imageCalls: 6,
  consistencyMode: 'prompt-only',
  anchorArtifactId: null,
  userCredits: 0,
  platformBudgetCents: 180,
  billingSource: 'platform',
  pricingVersion: 'v1',
  configurationFingerprint: 'fixture',
  expiresAt: '2999-01-01T00:00:00Z',
  warnings: [],
}

/**
 * 一次 M1 会话的完整 studio API 状态机：
 * - plan：preparing → ready（一次轮询后落定）
 * - job 队列：按序弹出快照（慢→partial(第3张失败)→…）；读取耗尽后停在最后一张。
 * - adopt：记录 selections，后续 job GET/draft 读回带上已采用 mediaRefs。
 */
export interface StudioSessionScript {
  planId: string
  jobId: string
  jobQueue: Record<string, unknown>[]
  /** 队列耗尽后回放的最后一个快照。 */
  lastJob?: Record<string, unknown>
  adoptedSelections: Array<{ itemId: string; artifactId: string }>
  adoptedMediaIds: string[]
}

export function newSession(): StudioSessionScript {
  return {
    planId: 'plan-e2e-1',
    jobId: 'job-e2e-1',
    jobQueue: [],
    adoptedSelections: [],
    adoptedMediaIds: [],
  }
}

function planBody(status: 'preparing' | 'ready'): Record<string, unknown> {
  const ready = status === 'ready'
  return {
    id: 'plan-e2e-1',
    draftId: 'draft-e2e-1',
    status,
    revision: 1,
    confirmedRevision: ready ? 1 : null,
    source: { id: 'source-e2e-1', contentHash: 'a'.repeat(64) },
    baseDraftVersion: 2,
    baseContentHash: 'b'.repeat(64),
    stale: false,
    document: ready ? {
      recipe: { id: 'social-card-series', version: '1.0.0' },
      strategy: 'information',
      style: { styleId: 'minimal-note', layoutId: 'list', paletteId: 'macaron' },
      items: defaultPlanItems().map((item) => ({
        ...item,
        bullets: ['要点一'],
        caption: `配文${item.position}`,
        purpose: `承载${item.position}`,
        illustration: '暖光小店门头特写，木质招牌与蒸汽，平视构图。',
        sourceBlockIds: ['b1'],
        criticalText: ['元'],
        layoutId: 'list',
        targetAspect: '3:4',
        placement: null,
        inputMediaRef: null,
      })),
      explanation: '按信息密度拆六页',
      uncoveredBlockIds: [],
    } : null,
    runId: 'run-plan-e2e',
    error: null,
    createdAt: '2026-09-13T00:00:00Z',
  }
}

function sourceBody(): Record<string, unknown> {
  return {
    id: 'source-e2e-1',
    draftId: 'draft-e2e-1',
    schemaVersion: 1,
    kind: 'markdown',
    title: '',
    rawText: SOURCE_TEXT,
    normalizedMarkdown: SOURCE_TEXT,
    contentHash: 'a'.repeat(64),
    blocks: Array.from({ length: 6 }, (_, index) => ({
      id: `b${index + 1}`,
      kind: 'paragraph',
      position: index + 1,
      startCodePoint: index * 20,
      endCodePoint: index * 20 + 18,
      text: SOURCE_TEXT.split('\n\n')[index],
      textHash: '',
    })),
    sourceRefs: [],
    warnings: [],
    createdAt: '2026-09-13T00:00:00Z',
  }
}

/**
 * 挂载 studio 域名级路由 fixture。draft 保存（/api/creation-drafts*）不拦截——
 * 真实后端负责草稿版本串联；采用后把已采用引用合入 draft GET 响应模拟服务端写回。
 */
export async function stubStudioApis(page: Page, script: StudioSessionScript): Promise<void> {
  let planReady = false
  await page.route(/\/api\/(creation-studio|media)/, async (route: Route) => {
    const url = new URL(route.request().url())
    const path = url.pathname
    const method = route.request().method()
    const respond = async (data: unknown, status = 200): Promise<void> => {
      await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify({ success: true, data }) })
    }
    if (path === '/api/creation-studio/sources' && method === 'POST') {
      await respond(sourceBody(), 201)
      return
    }
    if (path === '/api/creation-studio/sources/source-e2e-1' && method === 'GET') {
      await respond(sourceBody())
      return
    }
    if (path === '/api/creation-studio/visual-plans' && method === 'POST') {
      await respond(planBody('preparing'), 202)
      return
    }
    if (path === `/api/creation-studio/visual-plans/${script.planId}` && method === 'GET') {
      planReady = true
      await respond(planBody(planReady ? 'ready' : 'preparing'))
      return
    }
    if (path === `/api/creation-studio/visual-plans/${script.planId}/confirm` && method === 'POST') {
      await respond(planBody('ready'))
      return
    }
    if (path === `/api/creation-studio/visual-plans/${script.planId}/estimate` && method === 'POST') {
      const payload = route.request().postDataJSON() as { selectedItemIds?: string[] }
      await respond({ ...QUOTE_BODY, selectedItemIds: payload.selectedItemIds ?? [] })
      return
    }
    if (path === '/api/creation-studio/visual-jobs' && method === 'POST') {
      if (script.jobQueue.length > 0) script.lastJob = script.jobQueue[0]
      await respond(script.jobQueue[0] ?? script.lastJob ?? jobSnapshot(script.jobId, 'running', []), 202)
      return
    }
    if (path === `/api/creation-studio/visual-jobs/${script.jobId}` && method === 'GET') {
      const head = script.jobQueue[0] ?? script.lastJob
      // 每次轮询后队列前移一格（慢→partial→…）；耗尽后停在最后快照（终态）。
      if (script.jobQueue.length > 1) script.jobQueue.shift()
      await respond(head ?? jobSnapshot(script.jobId, 'running', []))
      return
    }
    if (path === `/api/creation-studio/visual-plans/${script.planId}/adopt` && method === 'POST') {
      const payload = route.request().postDataJSON() as {
        selections: Array<{ itemId: string; artifactId: string }>
      }
      script.adoptedSelections.push(...payload.selections)
      for (const selection of payload.selections) {
        script.adoptedMediaIds.push(`media-del-${selection.itemId}`)
      }
      await respond({
        project: adoptedProject(script.adoptedMediaIds),
        appliedVersion: 9,
        alreadyApplied: false,
      })
      return
    }
    if (path.startsWith('/api/media/') && method === 'GET') {
      await respond({ url: 'https://signed.test.invalid/delivery.jpg' })
      return
    }
    await route.continue()
  })
}

/** 服务端采用写回后的草稿视图（refresh 恢复断言用）。 */
export function adoptedProject(adoptedMediaIds: string[]): Record<string, unknown> {
  return {
    id: 'draft-e2e-1',
    title: '原稿到图卡',
    capability: 'article',
    status: 'in_progress',
    version: 9,
    platform: 'xiaohongshu',
    contentForm: 'graphic',
    topic: '门店三年',
    content: SOURCE_TEXT,
    resultAssetIds: [...adoptedMediaIds],
    runIds: adoptedMediaIds.map((id) => `run-${id.replace('media-del-', '')}`),
    updatedAt: '2026-09-13T00:01:00Z',
    workspace: {
      schemaVersion: 1,
      capability: 'article',
      currentStep: 'content',
      inputs: {
        studio: {
          schemaVersion: 1,
          recipe: { id: 'social-card-series', version: '1.0.0' },
          sourceDocumentId: 'source-e2e-1',
          visualPlan: { id: 'plan-e2e-1', revision: 1 },
          activeVisualJobId: 'job-e2e-1',
          lastProposalId: null,
          renderTheme: 'standard',
        },
      },
      resultRefs: adoptedMediaIds.map((id, index) => ({
        id, refType: 'media', role: index === 0 ? 'cover' : 'card',
        cardId: `card-${index + 1}`, position: index + 1,
      })),
      delivery: {
        version: 1,
        platform: 'xiaohongshu',
        contentForm: 'graphic',
        coverRef: adoptedMediaIds.length
          ? { id: adoptedMediaIds[0], refType: 'media', role: 'cover', cardId: 'card-1', position: 1 }
          : undefined,
        mediaRefs: adoptedMediaIds.map((id, index) => ({
          id, refType: 'media', role: 'card', cardId: `card-${index + 1}`, position: index + 1,
        })),
      },
    },
  }
}


