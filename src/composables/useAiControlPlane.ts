import type {
  AiOrgByokPolicyState,
  AiProviderKey,
  AiRun,
  CreateAiProviderKeyInput,
  CreatePlatformModelInput,
  PlatformModelConfig,
  PlatformModelRole,
  UpdateAiProviderKeyInput,
  UpdatePlatformModelInput,
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

  const listModels = () => request<PlatformModelConfig[]>('/api/admin/ai/models')
  const createModel = (input: CreatePlatformModelInput) => request<PlatformModelConfig>('/api/admin/ai/models', {
    method: 'POST', body: jsonBody(input),
  })
  const updateModel = (capability: string, role: PlatformModelRole, input: UpdatePlatformModelInput) =>
    request<PlatformModelConfig>(modelPath(capability, role), { method: 'PUT', body: jsonBody(input) })
  const disableModel = (capability: string, role: PlatformModelRole) =>
    request<void>(modelPath(capability, role), { method: 'DELETE' })

  return {
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
    listModels,
    createModel,
    updateModel,
    disableModel,
  }
}
