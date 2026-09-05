import type {
  AiOrgByokPolicyState,
  AiProviderKey,
  AiRun,
  CreateAiProviderKeyInput,
  CreatePlatformCredentialInput,
  CreatePlatformModelInput,
  CredentialProbeResult,
  ModelSource,
  PlatformModelConfig,
  PlatformModelRole,
  PlatformProviderCredential,
  PlatformTrustedOrigin,
  UpdateAiProviderKeyInput,
  UpdatePlatformCredentialInput,
  PriceModelEntry,
  PriceTableVersion,
  UpdatePlatformModelInput,
  UpstreamModel,
} from '../types/ai-control-plane'
import { fetchApi } from './grassland-http'

async function readError(response: Response): Promise<string> {
  const fallback = `请求失败（${response.status}）`
  const contentType = response.headers.get('content-type') || ''
  if (contentType.includes('application/json')) {
    const body = await response.json().catch(() => null) as { error?: string; message?: string } | null
    return body?.error || body?.message || fallback
  }
  const text = await response.text().catch(() => '')
  return text.trim() || fallback
}

export class AiControlPlaneError extends Error {
  constructor(readonly status: number, message: string) {
    super(message)
    this.name = 'AiControlPlaneError'
  }
}

async function request<T>(url: string, init: RequestInit = {}): Promise<T> {
  // 这些控制面端点回非信封裸 JSON（数组/对象/204），不走 grassland-http 的信封解析；
  // 只复用其传输层统一（cookie 与字符串主体的 JSON Content-Type）。
  const response = await fetchApi(url, init)
  if (!response.ok) throw new AiControlPlaneError(response.status, await readError(response))
  if (response.status === 204) return undefined as T
  const text = await response.text()
  return (text ? JSON.parse(text) : undefined) as T
}

function jsonBody(value: unknown): string {
  return JSON.stringify(value)
}

function modelPath(capability: string, role: PlatformModelRole): string {
  return `/api/admin/ai/models/${encodeURIComponent(capability)}/${encodeURIComponent(role)}`
}

export function useAiControlPlane() {
  const listRuns = () => request<AiRun[]>('/api/ai/runs')
  const getRun = (id: string) => request<AiRun>(`/api/ai/runs/${encodeURIComponent(id)}`)

  const listKeys = () => request<AiProviderKey[]>('/api/ai/keys')
  const createKey = (input: CreateAiProviderKeyInput) => request<AiProviderKey>('/api/ai/keys', {
    method: 'POST', body: jsonBody(input),
  })
  const updateKey = (id: string, input: UpdateAiProviderKeyInput) => request<AiProviderKey>(
    `/api/ai/keys/${encodeURIComponent(id)}`,
    { method: 'PUT', body: jsonBody(input) },
  )
  const rotateKey = (id: string, apiKey: string) => request<AiProviderKey>(
    `/api/ai/keys/${encodeURIComponent(id)}/key`,
    { method: 'PUT', body: jsonBody({ apiKey }) },
  )
  const disableKey = (id: string) => request<void>(`/api/ai/keys/${encodeURIComponent(id)}`, {
    method: 'DELETE',
  })

  // ---------- 组织级 BYOK（ADR-D17）----------

  const orgKeysPath = (organizationId: string) =>
    `/api/ai/organizations/${encodeURIComponent(organizationId)}/keys`
  const listOrgKeys = (organizationId: string) => request<AiProviderKey[]>(orgKeysPath(organizationId))
  const createOrgKey = (organizationId: string, input: CreateAiProviderKeyInput) =>
    request<AiProviderKey>(orgKeysPath(organizationId), { method: 'POST', body: jsonBody(input) })
  const updateOrgKey = (organizationId: string, id: string, input: UpdateAiProviderKeyInput) =>
    request<AiProviderKey>(`${orgKeysPath(organizationId)}/${encodeURIComponent(id)}`, {
      method: 'PUT', body: jsonBody(input),
    })
  const rotateOrgKey = (organizationId: string, id: string, apiKey: string) =>
    request<AiProviderKey>(`${orgKeysPath(organizationId)}/${encodeURIComponent(id)}/key`, {
      method: 'PUT', body: jsonBody({ apiKey }),
    })
  const disableOrgKey = (organizationId: string, id: string) =>
    request<void>(`${orgKeysPath(organizationId)}/${encodeURIComponent(id)}`, { method: 'DELETE' })

  /** 策略端点走 {success, data} 信封（组织管理域约定），这里解包 data。 */
  const orgPolicyPath = (organizationId: string) =>
    `/api/ai/organizations/${encodeURIComponent(organizationId)}/byok-policy`
  const getOrgByokPolicy = (organizationId: string) =>
    request<{ data: AiOrgByokPolicyState }>(orgPolicyPath(organizationId)).then((body) => body.data)
  const saveOrgByokPolicy = (
    organizationId: string,
    input: { expectedVersion: number; allowPlatformFallback: boolean },
  ) => request<{ data: AiOrgByokPolicyState }>(orgPolicyPath(organizationId), {
    method: 'PUT', body: jsonBody(input),
  }).then((body) => body.data)

  // ---------- 个人模型来源总开关（任务书 #78 卡 B/C）----------

  /** 偏好端点走 {success, data} 信封（同组织策略约定），这里解包。 */
  const getModelSource = () =>
    request<{ data: { modelSource: ModelSource; masterVersion: number } }>('/api/ai/preferences')
      .then((body) => body.data)
  /** `expectedVersion` 原样回传服务端给的 masterVersion；冲突时后端回 409。 */
  const setModelSource = (input: { modelSource: ModelSource; expectedVersion: number }) =>
    request<{ data: { modelSource: ModelSource; masterVersion: number } }>(
      '/api/ai/preferences/model-source',
      { method: 'PUT', body: jsonBody(input) },
    ).then((body) => body.data)

  // ---------- 受信端点（任务书 #58 决策 B）----------

  const trustedOriginsPath = '/api/admin/ai/trusted-origins'
  const listTrustedOrigins = () => request<PlatformTrustedOrigin[]>(trustedOriginsPath)
  const createTrustedOrigin = (input: { origin: string; label?: string }) =>
    request<PlatformTrustedOrigin>(trustedOriginsPath, { method: 'POST', body: jsonBody(input) })
  /** 乐观锁：expectedVersion 不匹配时后端回 409（「已被他人修改，请刷新后重试」）。 */
  const updateTrustedOrigin = (id: string, input: {
    origin: string; label?: string; enabled: boolean; expectedVersion: number
  }) => request<PlatformTrustedOrigin>(`${trustedOriginsPath}/${encodeURIComponent(id)}`, {
    method: 'PUT', body: jsonBody(input),
  })
  const deleteTrustedOrigin = (id: string) =>
    request<void>(`${trustedOriginsPath}/${encodeURIComponent(id)}`, { method: 'DELETE' })

  // ---------- 平台通用凭据（任务书 #47 S1）----------

  const credentialsPath = '/api/admin/ai/credentials'
  /** `includeDisabled=true` 时含已停用行（治理台「显示已停用」开关）。默认只回生效行——
   *  平台模型表单的凭据下拉依赖此默认值，勿翻转。 */
  const listCredentials = (includeDisabled = false) =>
    request<PlatformProviderCredential[]>(
      includeDisabled ? `${credentialsPath}?includeDisabled=true` : credentialsPath)
  const createCredential = (input: CreatePlatformCredentialInput) =>
    request<PlatformProviderCredential>(credentialsPath, { method: 'POST', body: jsonBody(input) })
  const updateCredential = (id: string, input: UpdatePlatformCredentialInput) =>
    request<PlatformProviderCredential>(`${credentialsPath}/${encodeURIComponent(id)}`, {
      method: 'PUT', body: jsonBody(input),
    })
  /** 轮换走独立端点——改连接信息的 PUT 刻意不含密钥。 */
  const rotateCredentialKey = (id: string, apiKey: string) =>
    request<PlatformProviderCredential>(`${credentialsPath}/${encodeURIComponent(id)}/key`, {
      method: 'PUT', body: jsonBody({ apiKey }),
    })
  /** 软删；仍被有效模型配置引用时后端回 409 并在 error 里报引用数。 */
  const disableCredential = (id: string) =>
    request<void>(`${credentialsPath}/${encodeURIComponent(id)}`, { method: 'DELETE' })

  /** 硬删一行已停用凭据；生效中/被模型配置行引用（含历史行）后端回 409。 */
  const hardDeleteCredential = (id: string) =>
    request<void>(`${credentialsPath}/${encodeURIComponent(id)}/hard`, { method: 'DELETE' })

  /** 连通性探测（任务书 #69 卡E）：手动触发、不缓存——每次点击实打（GET {baseUrl}/models）。 */
  const probeCredential = (id: string) =>
    request<CredentialProbeResult>(`${credentialsPath}/${encodeURIComponent(id)}/probe`, {
      method: 'POST',
    })

  /**
   * 列该凭据上游实际可用的模型（供平台模型表单的模型名下拉）。
   *
   * 上游不通、无密钥或 KEK 未配都会抛——调用方应降级为手填而不是阻断表单。
   */
  const listCredentialModels = (id: string) =>
    request<UpstreamModel[]>(`${credentialsPath}/${encodeURIComponent(id)}/models`)

  /** 读 admin 已勾选的模型（平台模型表单的下拉数据源，不触网）。 */
  const listSelectedModels = (id: string) =>
    request<UpstreamModel[]>(`${credentialsPath}/${encodeURIComponent(id)}/selected-models`)

  /** 整份覆盖勾选集；空数组 = 取消全部勾选。 */
  const replaceSelectedModels = (id: string, models: UpstreamModel[]) =>
    request<UpstreamModel[]>(`${credentialsPath}/${encodeURIComponent(id)}/selected-models`, {
      method: 'PUT', body: jsonBody({ models }),
    })

  const priceTablesPath = '/api/admin/ai/price-tables'
  const listPriceTables = () => request<PriceTableVersion[]>(priceTablesPath)
  const getPriceTable = (id: string) =>
    request<PriceTableVersion>(`${priceTablesPath}/${encodeURIComponent(id)}`)
  /** 新建 draft；`copyFromVersionId` 用于「复制现有版本再改」这条常规调价路径。 */
  const createPriceTableDraft = (input: { label: string; note?: string; copyFromVersionId?: string }) =>
    request<PriceTableVersion>(priceTablesPath, { method: 'POST', body: jsonBody(input) })
  /** 整份覆盖某 draft 的明细；后端对 active/retired 回 409。 */
  const replacePriceTableModels = (id: string, models: PriceModelEntry[]) =>
    request<PriceTableVersion>(`${priceTablesPath}/${encodeURIComponent(id)}/models`,
      { method: 'PUT', body: jsonBody({ models }) })
  const activatePriceTable = (id: string) =>
    request<PriceTableVersion>(`${priceTablesPath}/${encodeURIComponent(id)}/activate`,
      { method: 'POST' })
  const deletePriceTableDraft = (id: string) =>
    request<void>(`${priceTablesPath}/${encodeURIComponent(id)}`, { method: 'DELETE' })

  /** `includeDisabled=true` 时含已停用的历史版本（治理台开关）。 */
  const listModels = (includeDisabled = false) =>
    request<PlatformModelConfig[]>(
      includeDisabled ? '/api/admin/ai/models?includeDisabled=true' : '/api/admin/ai/models')

  /** 恢复一行已停用配置；该能力+角色已有生效行时后端回 409。 */
  const restoreModel = (id: string) =>
    request<PlatformModelConfig>(`/api/admin/ai/models/${encodeURIComponent(id)}/restore`,
      { method: 'POST' })

  /** 硬删一行已停用配置（生效中的后端回 409）。 */
  const deleteModel = (id: string) =>
    request<void>(`/api/admin/ai/models/${encodeURIComponent(id)}`, { method: 'DELETE' })
  const createModel = (input: CreatePlatformModelInput) => request<PlatformModelConfig>('/api/admin/ai/models', {
    method: 'POST', body: jsonBody(input),
  })
  const updateModel = (capability: string, role: PlatformModelRole, input: UpdatePlatformModelInput) =>
    request<PlatformModelConfig>(modelPath(capability, role), { method: 'PUT', body: jsonBody(input) })
  const disableModel = (capability: string, role: PlatformModelRole) =>
    request<void>(modelPath(capability, role), { method: 'DELETE' })

  return {
    listTrustedOrigins,
    createTrustedOrigin,
    updateTrustedOrigin,
    deleteTrustedOrigin,
    listRuns,
    getRun,
    listKeys,
    createKey,
    updateKey,
    rotateKey,
    disableKey,
    listOrgKeys,
    createOrgKey,
    updateOrgKey,
    rotateOrgKey,
    disableOrgKey,
    getOrgByokPolicy,
    saveOrgByokPolicy,
    getModelSource,
    setModelSource,
    listCredentials,
    listCredentialModels,
    listSelectedModels,
    replaceSelectedModels,
    createCredential,
    updateCredential,
    rotateCredentialKey,
    disableCredential,
    hardDeleteCredential,
    probeCredential,
    listPriceTables,
    getPriceTable,
    createPriceTableDraft,
    replacePriceTableModels,
    activatePriceTable,
    deletePriceTableDraft,
    listModels,
    restoreModel,
    deleteModel,
    createModel,
    updateModel,
    disableModel,
  }
}
