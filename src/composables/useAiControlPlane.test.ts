import { afterEach, describe, expect, test, vi } from 'vitest'
import { useAiControlPlane } from './useAiControlPlane'

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

afterEach(() => vi.unstubAllGlobals())

describe('useAiControlPlane', () => {
  test('reads raw run arrays and always includes the browser session', async () => {
    const fetchMock = vi.fn().mockResolvedValue(json([{ runId: 'run-1' }]))
    vi.stubGlobal('fetch', fetchMock)

    const runs = await useAiControlPlane().listRuns()

    expect(runs).toEqual([{ runId: 'run-1' }])
    expect(fetchMock).toHaveBeenCalledWith('/api/ai/runs', expect.objectContaining({ credentials: 'include' }))
  })

  test('sends BYOK creation and rotation bodies without retaining the plaintext', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json({ id: 'key-1', maskedHint: 'sk-***xyz' }, 201))
      .mockResolvedValueOnce(json({ id: 'key-1', maskedHint: 'sk-***new' }))
    vi.stubGlobal('fetch', fetchMock)
    const api = useAiControlPlane()

    await api.createKey({
      capability: 'text', provider: 'openai-compatible', baseUrl: 'https://ai.example/v1',
      model: 'model-a', apiKey: 'sk-secret', // secret-scan: allow - test fixture
    })
    await api.rotateKey('key-1', 'sk-new-secret') // secret-scan: allow - test fixture

    expect(fetchMock.mock.calls[0][1]).toMatchObject({ method: 'POST', credentials: 'include' })
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({
      capability: 'text', provider: 'openai-compatible', baseUrl: 'https://ai.example/v1',
      model: 'model-a', apiKey: 'sk-secret', // secret-scan: allow - test fixture
    })
    expect(fetchMock.mock.calls[1][0]).toBe('/api/ai/keys/key-1/key')
    expect(JSON.parse(fetchMock.mock.calls[1][1].body)).toEqual({ apiKey: 'sk-new-secret' }) // secret-scan: allow - test fixture
  })

  test('accepts empty 204 responses and URL-encodes model natural keys', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetchMock)

    await useAiControlPlane().disableModel('image generation', 'primary')

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/admin/ai/models/image%20generation/primary',
      expect.objectContaining({ method: 'DELETE', credentials: 'include' }),
    )
  })

  test('surfaces JSON and text error messages', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json({ success: false, error: '密钥能力重复' }, 409))
      .mockResolvedValueOnce(new Response('upstream unavailable', { status: 503 }))
    vi.stubGlobal('fetch', fetchMock)
    const api = useAiControlPlane()

    await expect(api.listKeys()).rejects.toThrow('密钥能力重复')
    await expect(api.listModels()).rejects.toThrow('upstream unavailable')
  })
})
