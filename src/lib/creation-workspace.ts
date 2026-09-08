import { computed, getCurrentInstance, ref, watch } from 'vue'
import { useAccountSessionStore } from '../stores/account-session'
import type { LocationQuery } from 'vue-router'
import { fetchApi } from '../composables/grassland-http'
import type {
  CreationProject,
  CreationProjectCapability,
  CreationProjectFields,
  CreationProjectStatus,
  CreationWorkspacePayload,
} from '../types/creation'

/**
 * 创作工作区前端域（任务书 #92）：
 * - 深链 query → `CreationSourceContext`（C-01）；
 * - 最近项目列表/继续创作/归档（C-03，复用 C-02 草稿 API，客户端只做展示缓存——D-02）；
 * - `pendingContinue` 模块级交接：C-03 写入，C-04/C-05 创作视图消费（§10.2 交接协议）。
 */

/**
 * 工作区能力枚举（§5.1）：与平台 × 内容形式矩阵正交——矩阵决定生成链路，能力决定工作区归属。
 */
export type CreationCapability = CreationProjectCapability

export const CREATION_CAPABILITIES: readonly CreationCapability[] = ['article', 'image', 'video', 'moments']

/** 创作入口来源上下文（C-01）：能力 + 门店/任务来源，页面级单一状态。 */
export interface CreationSourceContext {
  capability: CreationCapability
  storeId: string
  taskId: string
  /** 展示名（门店/任务名）由既有查询结果解析后回填，解析不到时页面回退 ID 截断态。 */
  label: string
}

function queryText(value: unknown): string {
  const first = Array.isArray(value) ? value[0] : value
  return typeof first === 'string' ? first.trim() : ''
}

/**
 * 深链 query → CreationSourceContext（C-01）。只读 capability/storeId/taskId 三个已知键；
 * 非法枚举回退 article，未知键一律忽略，不读取也不回写任何敏感参数；ID 超长截断防注入超限载荷。
 */
export function parseCreationSourceQuery(query: LocationQuery): CreationSourceContext {
  const rawCapability = queryText(query.capability)
  const capability = CREATION_CAPABILITIES.includes(rawCapability as CreationCapability)
    ? (rawCapability as CreationCapability)
    : 'article'
  return {
    capability,
    storeId: queryText(query.storeId).slice(0, 64),
    taskId: queryText(query.taskId).slice(0, 64),
    label: '',
  }
}

/** 能力展示名（与 AI 中心来源胶囊同词表）。 */
export const CAPABILITY_LABELS: Record<CreationProjectCapability, string> = {
  article: '文章', image: '图片', video: '视频', moments: '朋友圈',
}

interface SourceContextOptions {
  /** 响应式 route（vue-router useRoute() 的返回值；query 变化驱动重解析）。 */
  route: { query: LocationQuery }
  router: { replace: (to: { query: LocationQuery }) => unknown }
  /** 门店名解析（§8.3 名称取既有查询结果）；不提供则直接走 ID 截断态。 */
  fetchStoreName?: (storeId: string) => Promise<string | null>
}

/**
 * 来源上下文状态（C-01 的页面级逻辑，自 AiCreationCenter 外迁）：query 深链解析为单一状态，
 * 板块切换不影响；清除只移除来源（storeId/taskId），能力与其它 query 保留。
 */
export function useCreationSourceContext(options: SourceContextOptions) {
  const sourceContext = ref<CreationSourceContext>(parseCreationSourceQuery(options.route.query))
  watch(() => options.route.query, (query) => {
    sourceContext.value = parseCreationSourceQuery(query)
  })
  const capabilityFromQuery = computed(() => 'capability' in options.route.query)
  const hasSource = computed(() => Boolean(sourceContext.value.storeId || sourceContext.value.taskId))
  const capabilityBadgeLabel = computed(() => CAPABILITY_LABELS[sourceContext.value.capability])
  /** 同时带门店与任务时以任务为准（任务深链可含门店范围，任务是更具体的来源）。 */
  const sourceKindLabel = computed(() => (sourceContext.value.taskId ? '任务' : '门店'))
  const sourceResolvedLabel = ref('')
  let sourceLabelEpoch = 0
  watch(() => [sourceContext.value.storeId, sourceContext.value.taskId] as const, async ([storeId, taskId]) => {
    const epoch = ++sourceLabelEpoch
    sourceResolvedLabel.value = ''
    if (!storeId || taskId) return
    // 门店名取既有公开档案查询（§8.3）；拉不到（无权/不存在/网络失败）由展示层回退 ID 截断态
    const name = options.fetchStoreName ? await options.fetchStoreName(storeId) : null
    if (epoch !== sourceLabelEpoch) return
    sourceResolvedLabel.value = name || ''
  }, { immediate: true })
  const sourceDisplayLabel = computed(() => {
    if (sourceResolvedLabel.value) return sourceResolvedLabel.value
    const fallbackId = sourceContext.value.taskId || sourceContext.value.storeId
    return fallbackId.length > 12 ? `${fallbackId.slice(0, 12)}…` : fallbackId
  })

  /** 清除来源（AC-001）：只移除 storeId/taskId，能力与其它既有 query 原样保留，不触碰任何草稿。 */
  function clearSourceContext(): void {
    const query: LocationQuery = {}
    for (const [key, value] of Object.entries(options.route.query)) {
      if (key !== 'storeId' && key !== 'taskId') query[key] = value
    }
    void options.router.replace({ query })
  }

  return {
    sourceContext, capabilityFromQuery, hasSource, capabilityBadgeLabel, sourceKindLabel,
    sourceDisplayLabel, clearSourceContext,
  }
}

interface Envelope<T> {
  success: boolean
  data?: T
  error?: string
}

/**
 * 草稿端点的非 2xx 语义只透出 JSON 信封里的 error，且 409/404 判定要求错误携带 status——
 * 与 useCreationDraft 的本地包装同契约（其测试已锁定），复用 fetchApi 做传输层统一。
 */
async function request<T>(url: string, init: RequestInit = {}): Promise<T> {
  const response = await fetchApi(url, init)
  const raw = await response.text()
  let body: Envelope<T> | null
  try {
    body = raw ? JSON.parse(raw) as Envelope<T> : null
  } catch {
    body = null
  }
  if (!response.ok) {
    const error = new Error(body?.error || `请求失败（${response.status}）`) as Error & { status?: number }
    error.status = response.status
    throw error
  }
  if (!body?.success) {
    throw new Error(body?.error || '请求失败')
  }
  return body.data as T
}

/** 继续创作的跨视图交接（§10.2）：C-03 置入，C-04/C-05 创作视图消费后清除。模块级单例。 */
const pendingContinue = ref<CreationProject | null>(null)

/** 会话级「当前项目」标识（C-06）：助手/运行记录面板据此显示关联；不参与权限与计费判断（§0.2.7）。 */
const currentProjectId = ref('')

/** 最近项目列表状态（D-02：客户端最多缓存最近一次响应，服务端为权威）。模块级单例。 */
const projects = ref<CreationProject[]>([])
const projectsLoading = ref(false)
const projectsError = ref('')
const nextCursor = ref<string | null>(null)
const archivedVersions = new Map<string, number>()
let listEpoch = 0

/**
 * 最近项目工作区（C-03）：列表/读取/归档/撤销，全部复用 C-02 草稿 API。
 * 列表函数取消旧请求语义 = epoch 守卫：乱序响应一律丢弃，以最后一次为准。
 */
export function useCreationWorkspace() {
  const pinia = getCurrentInstance()?.appContext.config.globalProperties.$pinia
  const account = pinia ? useAccountSessionStore(pinia) : null
  if (account) watch(() => account.epoch, () => {
    listEpoch += 1
    projects.value = []
    projectsLoading.value = false
    projectsError.value = ''
    nextCursor.value = null
    pendingContinue.value = null
    currentProjectId.value = ''
    archivedVersions.clear()
  }, { flush: 'sync' })

  async function loadProjects(append = false): Promise<void> {
    if (append && (!nextCursor.value || projectsLoading.value)) return
    const epoch = ++listEpoch
    const ticket = account?.capture()
    projectsLoading.value = true
    projectsError.value = ''
    try {
      const cursor = append ? `&cursor=${encodeURIComponent(nextCursor.value!)}` : ''
      const data = await request<{ items: CreationProject[]; nextCursor?: string | null }>(`/api/creation-drafts?limit=20&status=active${cursor}`)
      if (epoch !== listEpoch || (ticket && !account?.isCurrent(ticket))) return
      const items = data.items ?? []
      projects.value = append ? [...projects.value, ...items.filter(item => !projects.value.some(previous => previous.id === item.id))] : items
      nextCursor.value = data.nextCursor ?? null
    } catch (err: unknown) {
      if (epoch === listEpoch) {
        projectsError.value = err instanceof Error ? err.message : '最近项目加载失败'
      }
    } finally {
      if (epoch === listEpoch) projectsLoading.value = false
    }
  }

  /** 读取单个项目（继续创作前取最新版本）；不存在/无权 → null（从列表移除，不泄露原因）。 */
  async function loadProject(id: string): Promise<CreationProject | null> {
    const ticket = account?.capture()
    try {
      const project = await request<CreationProject>(`/api/creation-drafts/${id}`)
      return ticket && !account?.isCurrent(ticket) ? null : project
    } catch {
      return null
    }
  }

  /** 归档（删除最近项目索引）。返回 ok / gone（他端已归档或不存在 → 调用方刷新列表）/ error。 */
  async function archiveProject(id: string, expectedVersion?: number): Promise<'ok' | 'gone' | 'error'> {
    try {
      const version = expectedVersion ?? projects.value.find(item => item.id === id)?.version ?? (await loadProject(id))?.version
      if (version == null) return 'gone'
      const saved = await request<CreationProject>(`/api/creation-drafts/${id}`, {
        method: 'PUT', body: JSON.stringify({ expectedVersion: version, status: 'archived' }),
      })
      archivedVersions.set(id, saved.version)
      return 'ok'
    } catch (err: unknown) {
      if ((err as { status?: number }).status === 404) return 'gone'
      projectsError.value = err instanceof Error ? err.message : '移除失败'
      return 'error'
    }
  }

  /**
   * 撤销归档（§8.4：不新增接口）——GET 最新版本后 PUT 回原状态；整行回传与 useCreationDraft
   * 同口径（未变更字段回当前值，后端对省略的工作区三字段做保留）。
   */
  async function undoArchive(id: string, previousStatus: CreationProjectStatus): Promise<boolean> {
    try {
      const version = archivedVersions.get(id) ?? (await loadProject(id))?.version
      if (version == null) return false
      await request<CreationProject>(`/api/creation-drafts/${id}`, {
        method: 'PUT',
        body: JSON.stringify({
          expectedVersion: version,
          status: previousStatus === 'archived' ? 'draft' : previousStatus,
        }),
      })
      archivedVersions.delete(id)
      return true
    } catch {
      return false
    }
  }

  function removeLocal(id: string): void {
    projects.value = projects.value.filter((item) => item.id !== id)
  }

  /**
   * 工作区保存（C-04 自动保存的落库口）：无 id 走 POST 创建，有 id 走 PUT（expectedVersion 必填）。
   * 每次保存最多一次数据库写入由服务端保证；409 冲突以 status=409 的 Error 抛出（调用方合并）。
   */
  async function saveProject(payload: {
    id?: string
    expectedVersion?: number
    title: string
    capability: CreationProjectCapability
    workspace: Record<string, unknown>
    status?: CreationProjectStatus
    resultAssetIds?: string[]
    fields?: CreationProjectFields
  }): Promise<CreationProject> {
    const common = {
      ...payload.fields,
      title: payload.title,
      capability: payload.capability,
      workspace: payload.workspace,
      ...(payload.status ? { status: payload.status } : {}),
      ...(payload.resultAssetIds ? { resultAssetIds: payload.resultAssetIds } : {}),
    }
    if (payload.id) {
      if (payload.expectedVersion == null) {
        throw new Error('expectedVersion 不能为空')
      }
      return request<CreationProject>(`/api/creation-drafts/${payload.id}`, {
        method: 'PUT',
        body: JSON.stringify({ ...common, expectedVersion: payload.expectedVersion }),
      })
    }
    return request<CreationProject>('/api/creation-drafts', {
      method: 'POST',
      body: JSON.stringify({ sourceType: 'independent', ...common }),
    })
  }

  function setPendingContinue(project: CreationProject | null): void {
    pendingContinue.value = project
    currentProjectId.value = project?.id || currentProjectId.value
  }

  function setCurrentProjectId(id: string): void {
    currentProjectId.value = id
  }

  return {
    projects, projectsLoading, projectsError, nextCursor,
    loadProjects, loadProject, archiveProject, undoArchive, removeLocal, saveProject,
    pendingContinue, setPendingContinue, currentProjectId, setCurrentProjectId,
  }
}

/** 工作区负载的类型窄化读取（未知形态回退缺省，服务端已保证结构）。 */
export function workspaceOf(project: CreationProject): CreationWorkspacePayload {
  return project.workspace && typeof project.workspace === 'object' ? project.workspace : {}
}
