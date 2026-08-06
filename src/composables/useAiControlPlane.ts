import type {
  AiProviderKey,
  AiRun,
  CreateAiProviderKeyInput,
  CreatePlatformModelInput,
  PlatformModelConfig,
  PlatformModelRole,
  UpdateAiProviderKeyInput,
  UpdatePlatformModelInput,
} from '../types/ai-control-plane'

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
  const response = await fetch(url, {
    ...init,
    credentials: 'include',
    headers: init.body
      ? { 'Content-Type': 'application/json', ...(init.headers || {}) }
      : init.headers,
  })
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
    listModels,
    createModel,
    updateModel,
    disableModel,
  }
}
